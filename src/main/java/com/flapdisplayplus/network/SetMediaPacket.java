/*
 * SetMediaPacket.java
 *
 * 媒体配置设置包（客户端 → 服务端）：
 * 客户端 GUI 选择媒体后，把「布谷鸟时钟坐标 + 媒体路径 + 显示模式」发给服务端，
 * 服务端写入布谷鸟时钟 BlockEntity（经 Mixin 持久化到 NBT），并立即把指向该布谷鸟时钟的
 * 显示链接器设为媒体显示源、手动推送一次（选图即同步）。
 *
 * 注意：对 DisplayLinkBlockEntity 一律通过「普通方法调用 / 安全反射 getDeclaredField」操作，
 * 绝不用 getDeclaredMethod 反射（其类签名引用未安装的 ComputerCraft API，枚举方法签名会
 * 触发 NoClassDefFoundError 崩溃）。updateGatheredData() 是 public 方法，直接调用即可。
 */
package com.flapdisplayplus.network;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.ModDisplaySources;
import com.flapdisplayplus.api.CuckooClockMedia;
import com.flapdisplayplus.compat.DisplayLinkReflection;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;

public record SetMediaPacket(BlockPos cuckooPos, String mediaPath, String displayMode,
                             String sourceType)
        implements CustomPacketPayload {

    public static final Type<SetMediaPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FlapDisplayPlus.MODID, "set_media"));

    public static final StreamCodec<ByteBuf, SetMediaPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetMediaPacket::cuckooPos,
                    ByteBufCodecs.STRING_UTF8, SetMediaPacket::mediaPath,
                    ByteBufCodecs.STRING_UTF8, SetMediaPacket::displayMode,
                    ByteBufCodecs.STRING_UTF8, SetMediaPacket::sourceType,
                    SetMediaPacket::new
            );

    /** 便捷构造（默认图片源） */
    public SetMediaPacket(BlockPos cuckooPos, String mediaPath, String displayMode) {
        this(cuckooPos, mediaPath, displayMode, CuckooClockMedia.SOURCE_IMAGE);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetMediaPacket msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) {
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
                FlapDisplayPlus.LOGGER.info("[SetMedia] {} 设置媒体: path={} mode={} type={}",
                        msg.cuckooPos(), msg.mediaPath(), msg.displayMode(), msg.sourceType());
            }
            // 选图即同步：找到指向该布谷鸟时钟的显示链接器，立即设为媒体源并推送一次
            syncDisplayLink(sp.serverLevel(), msg.cuckooPos());
        });
    }

    /** 遍历 cuckooPos 附近，把所有「source 指向该布谷鸟时钟」的显示链接器设为媒体源并立即推送 */
    private static void syncDisplayLink(ServerLevel level, BlockPos cuckooPos) {
        int r = 48;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = cuckooPos.offset(dx, dy, dz);
                    BlockEntity e = level.getBlockEntity(p);
                    if (!(e instanceof DisplayLinkBlockEntity link)) {
                        continue;
                    }
                    if (!Objects.equals(link.getSourcePosition(), cuckooPos)) {
                        continue;
                    }
                    // 设源（安全反射，不触发 CC 崩溃）
                    DisplayLinkReflection.setActiveSource(link, ModDisplaySources.FLAP_DISPLAY_MEDIA.get());
                    // updateGatheredData 是 public 方法，直接调用即可立即推送一次
                    try {
                        link.updateGatheredData();
                        FlapDisplayPlus.LOGGER.info("[SetMedia] 链接器 {} 已立即设为媒体显示源并推送", link.getBlockPos());
                    } catch (Throwable t) {
                        FlapDisplayPlus.LOGGER.warn("[SetMedia] 链接器 {} 立即推送失败（下一 tick 会自动重试）", link.getBlockPos(), t);
                    }
                }
            }
        }
    }
}
