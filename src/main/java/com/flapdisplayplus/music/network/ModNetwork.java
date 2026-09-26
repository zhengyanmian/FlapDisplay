package com.flapdisplayplus.music.network;

import com.flapdisplayplus.FlapDisplayPlus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 网络消息注册。
 */
@EventBusSubscriber(modid = FlapDisplayPlus.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {

    private ModNetwork() {
    }

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(SeekMessage.TYPE, SeekMessage.STREAM_CODEC, SeekMessage::handle);
    }
}
