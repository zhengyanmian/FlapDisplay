/*
 * SeekMessage.java
 *
 * 续播位置消息：服务端在「暂停续播」时发送给附近玩家，
 * 告知客户端应从哪个 tick 开始播放。
 *
 * 【Forge 1.20.1 移植说明】StreamCodec → SimpleChannel + FriendlyByteBuf。
 * 发送方走 NetMusic 自带 NetworkHandler.sendToNearby（接收侧是本模组的 SimpleChannel，
 * 两边通道名/包索引只需各自一致即可互通吗？——不行：Forge 按「通道 ResourceLocation + 包
 * discriminator」路由，NetMusic 的 sendToNearby 发的是 netmusic 通道的包，本包由
 * TileEntityMusicPlayerMixin 里直接用本模组 CHANNEL 发送，见该 Mixin。
 *
 * @param pos       播放机方块位置
 * @param startTick 已播放的 tick 数（续播起始位置）
 */
package com.flapdisplayplus.music.network;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.client.ResumeTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SeekMessage(BlockPos pos, int startTick) {

    // ===== SimpleChannel 编解码 =====

    public static void encode(SeekMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos());
        buf.writeVarInt(msg.startTick());
    }

    public static SeekMessage decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int tick = buf.readVarInt();
        return new SeekMessage(pos, tick);
    }

    /** 客户端处理：存入 ResumeTracker，供 NetMusicSound 构造时读取 */
    public static void handle(SeekMessage msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ResumeTracker.put(msg.pos(), msg.startTick())));
        ctx.setPacketHandled(true);
    }
}
