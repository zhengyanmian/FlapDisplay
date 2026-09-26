/*
 * FlapDisplayPlusClient.java
 *
 * 客户端初始化与事件处理：
 * - 显示链接器指向布谷鸟时钟时，GUI 由 DisplayLinkScreenMixin 替换为媒体配置界面
 * - 此处预留客户端初始化（如资源重载清理缓存）
 */
package com.flapdisplayplus.client;

import com.flapdisplayplus.FlapDisplayPlus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = FlapDisplayPlus.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FlapDisplayPlusClient {

    private FlapDisplayPlusClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 注册世界卸载/断线/每刻清理事件（NeoForge 总线），修复视频音频退出世界后不停、内存泄漏
        ClientWorldEvents.register();
        // 网络媒体缓存目录（需 gameDirectory，放在客户端初始化阶段）
        event.enqueueWork(() -> com.flapdisplayplus.net.NetCache.init(
                net.minecraft.client.Minecraft.getInstance().gameDirectory));
        FlapDisplayPlus.LOGGER.info("[FlapDisplayPlus] 客户端初始化完成");
    }
}
