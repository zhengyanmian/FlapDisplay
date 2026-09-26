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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FlapDisplayMediaSource extends SingleLineDisplaySource {

    /** 链接器位置 → 最后一次推送时间戳（服务端，诊断用） */
    public static final Map<BlockPos, Long> LAST_PUSH = new ConcurrentHashMap<>();

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        DisplayLinkBlockEntity gatherer = context.blockEntity();
        BlockPos sourcePos = gatherer.getSourcePosition();
        BlockPos flapPos = context.getTargetPos();
        BlockEntity be = context.level().getBlockEntity(sourcePos);

        // 记录本次推送时间（链接器有转速/激活时 provideLine 才会被调用）
        LAST_PUSH.put(gatherer.getBlockPos(), System.currentTimeMillis());

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

            FlapDisplayPlus.LOGGER.info("[MediaSource] 布谷鸟时钟 {} 媒体={} mode={} target={} render={}",
                    sourcePos, mediaPath.isEmpty() ? "(空)" : mediaPath, mode, flapPos, renderPos);

            if (context.level() instanceof ServerLevel serverLevel) {
                PacketDistributor.sendToPlayersNear(serverLevel, null,
                        renderPos.getX() + 0.5, renderPos.getY() + 0.5, renderPos.getZ() + 0.5, 64.0,
                        new MediaDisplayPacket(renderPos, mediaPath, mode));
            }
        } else {
            // source 不是布谷鸟时钟：清空该翻牌的媒体叠加
            if (context.level() instanceof ServerLevel serverLevel) {
                PacketDistributor.sendToPlayersNear(serverLevel, null,
                        renderPos.getX() + 0.5, renderPos.getY() + 0.5, renderPos.getZ() + 0.5, 64.0,
                        new MediaDisplayPacket(renderPos, "", "FIT"));
            }
        }
        return EMPTY_LINE;
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

    /** 防止 DisplayLinkBlockEntity.tick 周期性清空我们设好的 activeSource */
    @Override
    public boolean shouldPassiveReset() {
        return false;
    }

    /** 被动刷新间隔（tick）：20 = 约 1 秒一次，足够推送媒体配置 */
    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }
}
