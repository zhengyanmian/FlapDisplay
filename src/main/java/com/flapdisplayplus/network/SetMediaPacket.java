/*
 * SetMediaPacket.java
 *
 * 媒体配置设置包（客户端 → 服务端）：
 * 客户端 GUI 选择媒体后，把「布谷鸟时钟坐标 + 媒体路径 + 显示模式」发给服务端，
 * 服务端写入布谷鸟时钟 BlockEntity（经 Mixin 持久化到 NBT），并立即把指向该布谷鸟时钟的
 * 显示链接器设为媒体显示源、手动推送一次（选图即同步）。
 *
 * 【2026-08-28 重构】两处修正：
 *  1. activeSource 改为直接字段访问（javap 验证该字段是 public）。
 *  2. syncDisplayLink 收紧范围并提前退出：以 cuckooPos 为中心的 17^3 立方，
 *     并对同一 tick 内的重复请求做去重。
 *
 * 【Forge 1.20.1 移植说明】NeoForge 1.21.1 的 StreamCodec 载荷协议改为
 * Forge SimpleChannel + FriendlyByteBuf 手工编解码。
 */
package com.flapdisplayplus.network;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.ModDisplaySources;
import com.flapdisplayplus.api.CuckooClockMedia;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public record SetMediaPacket(BlockPos cuckooPos, String mediaPath, String displayMode,
                             String sourceType) {

    /** 链接器搜索半径（格）。Create 显示链接器通常紧邻时钟或同一机器组，17^3 足够覆盖 */
    private static final int LINK_SEARCH_RADIUS = 8;

    /** 短时去重：同一时钟在极短时间内重复应用只同步一次（防连点造成重复全量扫描） */
    private static final Set<String> RECENT_SYNC = ConcurrentHashMap.newKeySet();

    /** 便捷构造（默认图片源） */
    public SetMediaPacket(BlockPos cuckooPos, String mediaPath, String displayMode) {
        this(cuckooPos, mediaPath, displayMode, CuckooClockMedia.SOURCE_IMAGE);
    }

    // ===== SimpleChannel 编解码 =====

    public static void encode(SetMediaPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.cuckooPos());
        buf.writeUtf(msg.mediaPath());
        buf.writeUtf(msg.displayMode());
        buf.writeUtf(msg.sourceType());
    }

    public static SetMediaPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String path = buf.readUtf();
        String mode = buf.readUtf();
        String type = buf.readUtf();
        return new SetMediaPacket(pos, path, mode, type);
    }

    public static void handle(SetMediaPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) {
                return;
            }
            // 权限检查：只能改自己附近、可交互的布谷鸟时钟
            if (sp.distanceToSqr(msg.cuckooPos().getX() + 0.5, msg.cuckooPos().getY() + 0.5,
                    msg.cuckooPos().getZ() + 0.5) > 64 * 64) {
                FlapDisplayPlus.LOGGER.warn("[SetMedia] {} 尝试修改过远的时钟 {}", sp.getName().getString(), msg.cuckooPos());
                return;
            }
            BlockEntity be = sp.serverLevel().getBlockEntity(msg.cuckooPos());
            if (be instanceof CuckooClockMedia cuckoo) {
                cuckoo.flapdisplayplus$setMediaPath(msg.mediaPath());
                cuckoo.flapdisplayplus$setDisplayMode(msg.displayMode());
                cuckoo.flapdisplayplus$setSourceType(msg.sourceType() == null ? CuckooClockMedia.SOURCE_IMAGE : msg.sourceType());
                FlapDisplayPlus.LOGGER.debug("[SetMedia] {} 设置媒体: path={} mode={} type={}",
                        msg.cuckooPos(), msg.mediaPath(), msg.displayMode(), msg.sourceType());
            }
            // 选图即同步：找到指向该布谷鸟时钟的显示链接器，立即设为媒体源并推送一次
            syncDisplayLink(sp.serverLevel(), msg.cuckooPos());
        });
        ctx.setPacketHandled(true);
    }

    /**
     * 遍历 cuckooPos 附近，把所有「source 指向该布谷鸟时钟」的显示链接器设为媒体源并立即推送。
     *
     * 范围收紧到 (2*LINK_SEARCH_RADIUS+1)^3 = 4913 次查询（原为 912,673 次，降低约 185 倍），
     * 且整个循环零对象分配（BlockPos.MutableBlockPos 复用）。
     */
    private static void syncDisplayLink(ServerLevel level, BlockPos cuckooPos) {
        String key = level.dimension().location() + "@" + cuckooPos.asLong();
        if (!RECENT_SYNC.add(key)) {
            return; // 同一时钟的重复应用：已同步过，跳过
        }
        // 用一次性延迟任务移除，避免长期占用内存（不引入额外线程，走服务端 tick）
        level.getServer().execute(() -> RECENT_SYNC.remove(key));

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -LINK_SEARCH_RADIUS; dx <= LINK_SEARCH_RADIUS; dx++) {
            for (int dy = -LINK_SEARCH_RADIUS; dy <= LINK_SEARCH_RADIUS; dy++) {
                for (int dz = -LINK_SEARCH_RADIUS; dz <= LINK_SEARCH_RADIUS; dz++) {
                    cursor.set(cuckooPos.getX() + dx, cuckooPos.getY() + dy, cuckooPos.getZ() + dz);
                    BlockEntity e = level.getBlockEntity(cursor);
                    if (!(e instanceof DisplayLinkBlockEntity link)) {
                        continue;
                    }
                    if (!Objects.equals(link.getSourcePosition(), cuckooPos)) {
                        continue;
                    }
                    // activeSource 是 public 字段，直接赋值（无需反射）
                    link.activeSource = ModDisplaySources.FLAP_DISPLAY_MEDIA.get();
                    // updateGatheredData 是 public 方法，直接调用即可立即推送一次
                    try {
                        link.updateGatheredData();
                        FlapDisplayPlus.LOGGER.debug("[SetMedia] 链接器 {} 已立即设为媒体显示源并推送", link.getBlockPos());
                    } catch (Throwable t) {
                        FlapDisplayPlus.LOGGER.warn("[SetMedia] 链接器 {} 立即推送失败（下一 tick 会自动重试）", link.getBlockPos(), t);
                    }
                }
            }
        }
    }
}
