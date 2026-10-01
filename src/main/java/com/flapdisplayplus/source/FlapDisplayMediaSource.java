/*
 * FlapDisplayMediaSource.java
 *
 * 翻牌媒体显示源（服务端）：
 * 绑定到 Create 布谷鸟时钟（CuckooClockBlockEntity）。当显示链接器指向
 * 布谷鸟时钟时，本数据源被读取，把「翻牌坐标 + 布谷鸟人时钟上配置的媒体路径
 * + 显示模式」发给附近客户端，客户端 Mixin 在翻牌正面叠加绘制媒体帧。
 *
 * 关键约束（Create 6.0.x 字节码验证）：
 *  - `updateGatheredData()` 会执行 `DisplaySource.getAll(level, sourcePos).contains(activeSource)`，
 *    若为 false 则把 activeSource 清空并不推送。因此绑定到 BY_BLOCK_ENTITY 的实例
 *    必须与 setActiveSource 用的是同一个（FLAP_DISPLAY_MEDIA.get()），否则引用比较失败。
 *  - `shouldPassiveReset()` 默认 true，会导致 DisplayLinkBlockEntity.tick 周期性清空 activeSource。
 *    必须重写为 false，否则我们的源每周期被重置、永远不推送。
 *  - `getPassiveRefreshTicks()` 控制被动刷新间隔（以 tick 计）。
 */
package com.flapdisplayplus.source;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.api.CuckooClockMedia;
import com.flapdisplayplus.network.MediaDisplayPacket;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.flapdisplayplus.network.ModNetwork;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FlapDisplayMediaSource extends SingleLineDisplaySource {

    /**
     * 链接器位置 → 最后一次实际发送的媒体状态（renderPos|mediaPath|mode）。
     *
     * 【2026-09-27 修正】这个「幂等去重」曾经是本模组最严重的隐性 bug：
     * 它让「状态未变就不发包」，而客户端 MediaRenderRegistry 有一套 5 秒过期机制
     * （认为服务端停发 = 链接器被拆了）。两者组合 ⇒ 画面稳定 5 秒后必然消失。
     *
     * 现在的策略：状态**变化时立即发**（保证「选图即同步」），状态**未变时低频心跳重发**
     * （保证客户端不判过期）。心跳间隔见 HEARTBEAT_MS，必须显著小于客户端 EXPIRE_MS。
     */
    private static final Map<BlockPos, String> LAST_SENT = new ConcurrentHashMap<>();

    /** 上次发送时间戳（毫秒），用于心跳重发 */
    private static final Map<BlockPos, Long> LAST_SENT_AT = new ConcurrentHashMap<>();

    /**
     * 心跳重发间隔（毫秒）。必须显著小于客户端的过期时间
     * （MediaRenderRegistry.EXPIRE_MS = 30000），留足余量以吸收网络抖动与丢包；
     * 同时远大于一个 tick（50ms）避免无谓开销。
     */
    private static final long HEARTBEAT_MS = 1500L;

    /** 清理节流：每 N 次发送做一次孤儿条目清理 */
    private static final long CLEANUP_INTERVAL = 512L;

    /** 清理计数器 */
    private static final java.util.concurrent.atomic.AtomicLong CLEANUP_COUNTER =
            new java.util.concurrent.atomic.AtomicLong();

    /** 超过此时长（毫秒）未再被访问的发送记录视为孤儿 */
    private static final long STALE_MS = 10 * 60 * 1000L;

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        DisplayLinkBlockEntity gatherer = context.blockEntity();
        BlockPos sourcePos = gatherer.getSourcePosition();
        BlockPos flapPos = context.getTargetPos();
        BlockEntity be = context.level().getBlockEntity(sourcePos);

        // 服务端直接解析「实际渲染坐标」= 显示带 controller（左上角）。
        // Create 的 FlapDisplayRenderer 只渲染 controller 块，且 controller 渲染整个显示带。
        // 服务端 BE 的 isController/xSize 状态可靠（updateControllerStatus 在服务端跑），
        // 客户端 getController() 依赖同步可能不稳定——所以在这里解析好再发，客户端不再猜。
        BlockPos renderPos = flapPos;
        try {
            if (context.level().getBlockEntity(flapPos) instanceof FlapDisplayBlockEntity fbe) {
                FlapDisplayBlockEntity c = fbe.getController();
                if (c != null) {
                    renderPos = c.getBlockPos();
                } else {
                    renderPos = fbe.getBlockPos();
                }
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[MediaSource] 解析 controller 失败, fallback flapPos: {}", t.toString());
        }

        if (be instanceof CuckooClockMedia cuckoo) {
            // 通过 Mixin 注入的接口读取媒体配置
            String mediaPath = cuckoo.flapdisplayplus$getMediaPath();
            String mode = cuckoo.flapdisplayplus$getDisplayMode();
            String sourceType = cuckoo.flapdisplayplus$getSourceType();
            if (sourceType == null || sourceType.isEmpty()) {
                sourceType = CuckooClockMedia.SOURCE_IMAGE;
            }

            // ===== 信息源：返回文本行走原版翻牌字符显示，不叠加图片 =====
            if (!CuckooClockMedia.SOURCE_IMAGE.equals(sourceType)) {
                return provideTextLine(context, sourceType);
            }

            // 【性能约束】provideLine() 在服务端 tick 热路径上（每 getPassiveRefreshTicks 一次）。
            // 这里的日志曾经是 info 且每次都打 → 每个媒体显示源每 5 秒刷一行。
            // 改为 debug，并把「同一组配置只打一次」交给日志级别控制；需要排障时调 debug。
            FlapDisplayPlus.LOGGER.debug("[MediaSource] 布谷鸟时钟 {} 媒体={} mode={} target={} render={}",
                    sourcePos, mediaPath.isEmpty() ? "(空)" : mediaPath, mode, flapPos, renderPos);

            // 幂等去重 + 心跳：媒体路径与目标都没变时，不必每 tick 重发，但也**不能永久不发**。
            // 客户端 MediaRenderRegistry 有过期机制（停发 = 链接器被拆），
            // 因此「状态变化立即发、状态未变每 HEARTBEAT_MS 补发一次」。
            String prev = LAST_SENT.get(gatherer.getBlockPos());
            String now = renderPos.asLong() + "|" + mediaPath + "|" + mode;
            long nowMs = System.currentTimeMillis();
            Long lastAt = LAST_SENT_AT.get(gatherer.getBlockPos());
            boolean changed = !now.equals(prev);
            boolean heartbeatDue = lastAt == null || nowMs - lastAt >= HEARTBEAT_MS;
            if (changed || heartbeatDue) {
                // 清理：链接器被拆除时 blockEntity 没了，这两个 Map 会留下孤儿条目（只写不读 = 内存泄漏）。
                // 周期性剔除「很久没被任何链接器访问」的条目。
                if ((CLEANUP_COUNTER.incrementAndGet() % CLEANUP_INTERVAL) == 0) {
                    netmusicdisplay$evictStale(nowMs);
                }
                LAST_SENT.put(gatherer.getBlockPos(), now);
                LAST_SENT_AT.put(gatherer.getBlockPos(), nowMs);
                if (context.level() instanceof ServerLevel serverLevel) {
                    final BlockPos rp = renderPos; // lambda 捕获要求实际最终变量
                    ModNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() ->
                            new PacketDistributor.TargetPoint(
                                    rp.getX() + 0.5, rp.getY() + 0.5, rp.getZ() + 0.5, 64.0, serverLevel.dimension())),
                            new MediaDisplayPacket(rp, mediaPath, mode));
                }
            }
        } else {
            // source 不是布谷鸟时钟：清空该翻牌的媒体叠加（同样只在状态变化时发一次）
            String key = "clear:" + renderPos.asLong();
            if (LAST_SENT.remove(gatherer.getBlockPos()) != null
                    || LAST_SENT.putIfAbsent(gatherer.getBlockPos(), key) == null) {
                LAST_SENT_AT.remove(gatherer.getBlockPos());
                if (context.level() instanceof ServerLevel serverLevel) {
                    final BlockPos rp = renderPos; // lambda 捕获要求实际最终变量
                    ModNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() ->
                            new PacketDistributor.TargetPoint(
                                    rp.getX() + 0.5, rp.getY() + 0.5, rp.getZ() + 0.5, 64.0, serverLevel.dimension())),
                            new MediaDisplayPacket(rp, "", "FIT"));
                }
            }
        }
        return EMPTY_LINE;
    }

    /**
     * 剔除长期未被访问的发送记录，防止链接器被拆后这两个 Map 无限增长
     * （本项目已有「只写不读的 Map 是无界内存泄漏」的教训）。
     */
    private static void netmusicdisplay$evictStale(long nowMs) {
        long cutoff = nowMs - STALE_MS;
        try {
            LAST_SENT_AT.entrySet().removeIf(e -> e.getValue() < cutoff);
            LAST_SENT.keySet().removeIf(p -> !LAST_SENT_AT.containsKey(p));
        } catch (Throwable ignored) {
        }
    }

    /** 信息源：生成文本行（走原版翻牌字符渲染） */
    private MutableComponent provideTextLine(DisplayLinkContext context, String sourceType) {
        try {
            switch (sourceType) {
                case CuckooClockMedia.SOURCE_INFO_TIME -> {
                    return Component.literal(InfoSourceHelpers.realTimeText());
                }
                case CuckooClockMedia.SOURCE_INFO_GAME_TIME -> {
                    return Component.literal(InfoSourceHelpers.gameTimeText(context.level()));
                }
                case CuckooClockMedia.SOURCE_INFO_WEATHER -> {
                    return Component.literal(InfoSourceHelpers.weatherText(context.level()));
                }
                case CuckooClockMedia.SOURCE_INFO_TPS -> {
                    return Component.literal(InfoSourceHelpers.tpsText(context.level()));
                }
                default -> {
                    return EMPTY_LINE;
                }
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[MediaSource] 信息源生成失败 type={}", sourceType, t);
            return Component.literal("§7（信息源错误）");
        }
    }

    @Override
    protected String getTranslationKey() {
        return "flap_display_media";
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return true;
    }

    /**
     * 【2026-09-27 关键修正 —— 返回 false 会让整个媒体显示源彻底失效】
     *
     * Create 6.0.10 `DisplayLinkBlockEntity.tick()` 实际字节码（javap 逐条核对）：
     * <pre>
     *   if (isVirtual()) return;
     *   if (activeSource == null) return;
     *   if (level.isClientSide) return;
     *   refreshTicks++;
     *   if (refreshTicks < activeSource.getPassiveRefreshTicks()) return;   // 未到期
     *   if (!activeSource.shouldPassiveReset()) return;                     // ← 返回 false 直接 return
     *   tickSource();                                                       // ← 只有 true 才真的取数据
     * </pre>
     *
     * 也就是说：**返回 true 才会调用 tickSource()**，provideLine() 才会被执行。
     * 返回 false = Create 永远不去刷新这个源 = 本类的主逻辑（含发包）成为死代码。
     *
     * 此前这里返回 false，注释还写着「必须返回 false 防止被重置」——正好写反了：
     * 后果是刷新包从未发出，客户端等不到刷新就按超时清理，媒体消失
     * （图片/视频几秒后不见的根因）。方法名里的 "Reset" 指「重置/重建显示内容」，
     * 对我们是**想要**的行为。
     */
    @Override
    public boolean shouldPassiveReset() {
        return true;
    }

    /**
     * 被动刷新间隔（tick）：20 = 约 1 秒一次。
     * 这是 provideLine() 的实际调用频率，决定了心跳包的上游节奏，
     * 必须显著小于客户端 EXPIRE_MS，否则到期与刷新之间会出现可见的空档。
     */
    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }
}
