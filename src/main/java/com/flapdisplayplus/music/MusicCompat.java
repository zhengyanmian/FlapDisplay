/*
 * MusicCompat.java
 *
 * 网络音乐机(Net Music)软联动的【守卫类】——本类绝不 import 任何 netmusic 类。
 *
 * 【为什么存在】所有「无论是否安装 Net Music 都会被类加载」的代码（主类构造、
 * 客户端搜索源注册、QQ 凭证管理、配置界面）只能引用本类。涉及 netmusic 类的
 * 实现（MusicNetIntegration）只在 isNetMusicLoaded() 通过后才允许被类加载——
 * JVM 的常量池懒解析保证：未执行到的静态调用不会触发目标类加载与校验。
 *
 * 反例教训（2026-10-02 崩溃）：主类直接调用 MusicNetIntegration.init()，
 * 其 init() 方法体内有 netmusic 类型流（registerResolver(NeteaseVIPResolver)，
 * 解析器实现了 netmusic 的 IAsyncSongUrlResolver 接口）→ 类校验阶段就加载
 * netmusic 类 → 未装 Net Music 时 NoClassDefFoundError，模组无法启动。
 */
package com.flapdisplayplus.music;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class MusicCompat {

    /** Net Music 模组 id */
    public static final String NETMUSIC_MODID = "netmusic";
    public static final Logger LOGGER = LogManager.getLogger("FlapDisplayPlus.Music");

    private MusicCompat() {
    }

    /** 是否检测到网络音乐机模组 */
    public static boolean isNetMusicLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(NETMUSIC_MODID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 由 FlapDisplayPlus 主类在构造时调用。
     * 检测到 Net Music 才加载 MusicNetIntegration（真正的集成实现）并执行注册；
     * 未安装则静默跳过——MusicNetIntegration 类不会被加载。
     */
    public static void initIfLoaded(IEventBus modBus, ModContainer modContainer) {
        if (!isNetMusicLoaded()) {
            LOGGER.info("[Music] 未检测到网络音乐机模组，音乐联动功能禁用（软联动）。");
            return;
        }
        MusicNetIntegration.init(modBus, modContainer);
    }

    /**
     * 应用网易云 Cookie 的守卫入口（配置界面等未门控调用方使用）。
     * 未安装 Net Music 时直接跳过，不加载 MusicNetIntegration。
     */
    public static void applyCookie(String cookie) {
        if (!isNetMusicLoaded()) {
            LOGGER.info("[Music] 未安装网络音乐机，跳过 Cookie 应用。");
            return;
        }
        MusicNetIntegration.applyCookie(cookie);
    }
}
