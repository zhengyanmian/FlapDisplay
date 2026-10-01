/*
 * FlapDisplayPlus.java
 *
 * 翻牌万象 FlapDisplayPlus —— 机械动力(Create)翻牌显示器扩展附属模组。
 * 【Forge 1.20.1 版】由 NeoForge 1.21.1 版移植。
 *
 * 设计核心：
 * - 复用 Create 布谷鸟时钟作为「显示源方块」：显示链接器指向它时，
 *   可以读取其上配置的媒体（图片/GIF/视频帧/信息/网页），
 *   由客户端 Mixin 在翻牌显示器正面叠加绘制。
 * - 与网络音乐机(Net Music)为【软联动】：编译期引用其类仅用于探测，
 *   运行时不存在则该层整体跳过，模组独立可用。
 */
package com.flapdisplayplus;

import com.flapdisplayplus.config.Config;
import com.flapdisplayplus.event.ServerBlockEvents;
import com.flapdisplayplus.music.MusicClientInit;
import com.flapdisplayplus.music.MusicNetIntegration;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

@Mod(FlapDisplayPlus.MODID)
public class FlapDisplayPlus {
    public static final String MODID = "flapdisplayplus";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FlapDisplayPlus() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModContainer modContainer = ModLoadingContext.get().getActiveContainer();
        LOGGER.info("[{}] 翻牌万象 FlapDisplayPlus (Forge 1.20.1) 加载完成", MODID);
        // 注册配置
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // 注册显示源
        ModDisplaySources.register(modEventBus);

        // 服务端方块事件：拆除显示链接器时主动清空对应翻牌的媒体叠加
        // （否则链接器 BE 消失后没有任何包会发出，画面会一直残留）
        MinecraftForge.EVENT_BUS.addListener(ServerBlockEvents::onBlockBroken);

        // ===== 网络音乐机软联动（可选模块）=====
        // 检测到 Net Music 才注册音乐显示源 / 解析器 / 动力臂交互点；
        // 未安装则整个模块跳过（界面显示"请安装网络音乐机"提示）。
        MusicNetIntegration.init(modEventBus, modContainer);

        // 客户端专用初始化（配置界面 / 多平台搜索源 / QQ 凭证）——只在客户端执行，
        // 服务端直接跳过（music 客户端类不加载，避免 NoClassDefFoundError）
        if (FMLLoader.getDist() == Dist.CLIENT) {
            MusicClientInit.init(modContainer);
        }

        // 网络通道注册（SimpleChannel，须在 commonSetup 完成注册）
        modEventBus.addListener(FlapDisplayPlus::commonSetup);
    }

    private static void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            com.flapdisplayplus.network.ModNetwork.register();
            com.flapdisplayplus.music.network.ModNetwork.register();
        });
    }
}
