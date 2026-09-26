/*
 * ModNetwork.java
 *
 * 网络消息注册（mod 总线）。
 */
package com.flapdisplayplus.network;

import com.flapdisplayplus.FlapDisplayPlus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = FlapDisplayPlus.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {

    private ModNetwork() {
    }

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(MediaDisplayPacket.TYPE, MediaDisplayPacket.STREAM_CODEC, MediaDisplayPacket::handle)
                .playToServer(SetMediaPacket.TYPE, SetMediaPacket.STREAM_CODEC, SetMediaPacket::handle);
    }
}
