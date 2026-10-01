package com.flapdisplayplus.music.network;

import com.flapdisplayplus.FlapDisplayPlus;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络消息注册（音乐联动模块，Forge SimpleChannel）。
 */
public final class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";
    private static int packetId = 0;

    public static SimpleChannel CHANNEL;

    private ModNetwork() {
    }

    public static void register() {
        if (CHANNEL != null) {
            return;
        }
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(FlapDisplayPlus.MODID, "music"),
                () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
        // 服务端 -> 客户端（续播位置）
        CHANNEL.registerMessage(packetId++, SeekMessage.class,
                SeekMessage::encode, SeekMessage::decode, SeekMessage::handle);
    }
}
