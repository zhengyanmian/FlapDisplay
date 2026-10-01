/**
 * 媒体显示同步包（服务端 → 客户端）：
 * 服务端「翻牌媒体显示源」在显示链接器刷新时，把翻牌坐标 + 媒体路径 + 显示模式
 * 发给附近客户端。客户端收到后写入 MediaRenderRegistry，由 FlapDisplayRenderer
 * 的 Mixin 在渲染该翻牌时叠加绘制媒体帧。
 *
 * 「整片拼图」支持：中间有洞时显示带被拆成多个子显示带，每个子显示带显示
 * 整张图片的对应部分（board 为整片包围盒，offset/seg 为当前子显示带的位置尺寸），
 * 视觉上是一张完整的图（洞处物理空缺）。
 *
 * 【Forge 1.20.1 移植说明】NeoForge 1.21.1 的 StreamCodec 载荷协议改为
 * Forge SimpleChannel + FriendlyByteBuf 手工编解码；客户端处理器改走
 * Minecraft.getInstance() 取关卡。
 *
 * @param flapPos     翻牌显示器（显示链接目标）controller 坐标
 * @param mediaPath   媒体文件路径（空 = 该翻牌不显示媒体，走原版字符显示）
 * @param displayMode FIT / STRETCH / COVER
 */
package com.flapdisplayplus.network;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.client.MediaManager;
import com.flapdisplayplus.client.MediaRenderRegistry;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public record MediaDisplayPacket(BlockPos flapPos, String mediaPath, String displayMode) {

    /** 收包计数（仅用于热路径日志节流） */
    private static final java.util.concurrent.atomic.AtomicLong PACKET_RX =
            new java.util.concurrent.atomic.AtomicLong();

    // ===== SimpleChannel 编解码 =====

    public static void encode(MediaDisplayPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.flapPos());
        buf.writeUtf(msg.mediaPath());
        buf.writeUtf(msg.displayMode());
    }

    public static MediaDisplayPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String path = buf.readUtf();
        String mode = buf.readUtf();
        return new MediaDisplayPacket(pos, path, mode);
    }

    public static void handle(MediaDisplayPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            Level level = Minecraft.getInstance().level;
            BlockPos key = resolveRenderPos(msg.flapPos(), level);
            // 【热路径日志铁律】本方法现在每秒都会被服务端心跳触发，绝不能打 info。
            // 只在【状态真的变化】时打一条 info（选图/清除事件），其余降为 debug 并节流。
            String incoming = msg.mediaPath() == null ? "" : msg.mediaPath();
            MediaRenderRegistry.MediaInfo old = MediaRenderRegistry.peek(key);
            boolean changed = old == null
                    ? !incoming.isEmpty()
                    : !incoming.equals(old.mediaPath) || !msg.displayMode().equals(old.displayMode);
            if (changed) {
                FlapDisplayPlus.LOGGER.info("[MediaPacket] 客户端收到: renderKey={} media={} mode={}",
                        key, incoming.isEmpty() ? "(空)" : incoming, msg.displayMode());
            } else if ((PACKET_RX.incrementAndGet() % 200) == 0) {
                FlapDisplayPlus.LOGGER.debug("[MediaPacket] 心跳刷新 renderKey={} media={}", key, incoming);
            }
            String oldPath = old == null ? null : old.mediaPath;
            if (incoming.isEmpty()) {
                MediaRenderRegistry.remove(key);
                // 【孤儿音频修复】清空只删注册表是不够的：旧视频的播放器若仍被别的
                // 显示器引用则保留，否则必须立即硬停 —— 否则「画面消失但声音继续播」
                if (oldPath != null) {
                    MediaManager.stopVideoIfUnreferenced(oldPath);
                }
            } else {
                MediaRenderRegistry.put(key, incoming, msg.displayMode());
                // 换了媒体：旧的立即回收（避免旧视频声音残留）
                if (oldPath != null && !oldPath.equals(incoming)) {
                    MediaManager.stopVideoIfUnreferenced(oldPath);
                }
            }
        });
        ctx.setPacketHandled(true);
    }

    /**
     * 把显示链接器的目标坐标解析为翻牌渲染实际使用的坐标：
     * 1. 目标本身是 FlapDisplayBlockEntity → 取其 controller（getController()，自身为 controller 时返回自身）
     * 2. 目标不是翻牌 → 检查 6 个相邻方块是否为翻牌，取 controller
     * 3. 都找不到 → 原样返回（渲染侧会打 info=null 日志便于诊断）
     */
    private static BlockPos resolveRenderPos(BlockPos flapPos, Level level) {
        try {
            if (level == null) {
                return flapPos;
            }
            Set<BlockPos> tried = new HashSet<>();
            tried.add(flapPos);
            BlockEntity target = level.getBlockEntity(flapPos);
            if (target instanceof FlapDisplayBlockEntity fbe) {
                return controllerPos(fbe, flapPos);
            }
            // 相邻 6 方向找翻牌
            for (Direction d : Direction.values()) {
                BlockPos p = flapPos.relative(d);
                if (!tried.add(p)) {
                    continue;
                }
                if (level.getBlockEntity(p) instanceof FlapDisplayBlockEntity fbe2) {
                    return controllerPos(fbe2, p);
                }
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[MediaPacket] 解析渲染坐标失败, fallback flapPos: {}", t.toString());
        }
        return flapPos;
    }

    private static BlockPos controllerPos(FlapDisplayBlockEntity fbe, BlockPos fallback) {
        try {
            FlapDisplayBlockEntity controller = fbe.getController();
            return (controller != null ? controller : fbe).getBlockPos();
        } catch (Throwable t) {
            return fallback;
        }
    }
}
