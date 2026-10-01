/*
 * ModNetwork.java
 *
 * 网络通道注册（Forge 1.20.1：SimpleChannel，由主类 commonSetup 调用）。
 */
package com.flapdisplayplus.network;

import com.flapdisplayplus.FlapDisplayPlus;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

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
                new ResourceLocation(FlapDisplayPlus.MODID, "main"),
                () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
        // 客户端 -> 服务端
        CHANNEL.registerMessage(packetId++, SetMediaPacket.class,
                SetMediaPacket::encode, SetMediaPacket::decode, SetMediaPacket::handle);
        // 服务端 -> 客户端
        CHANNEL.registerMessage(packetId++, MediaDisplayPacket.class,
                MediaDisplayPacket::encode, MediaDisplayPacket::decode, MediaDisplayPacket::handle);
        FlapDisplayPlus.LOGGER.info("[{}] 网络通道注册完成（2 个包）", FlapDisplayPlus.MODID);
    }
}
