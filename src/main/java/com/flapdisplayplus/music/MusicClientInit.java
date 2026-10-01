package com.flapdisplayplus.music;

import com.flapdisplayplus.music.client.gui.ConfigScreen;
import com.flapdisplayplus.music.qq.QqCredentialManager;
import com.flapdisplayplus.music.search.SearchSourceManager;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * 音乐联动客户端初始化（合并自原「不是显示来源」客户端入口）。【Forge 1.20.1 版】
 *
 * 原 NetMusicDisplayClient 是独立的 @Mod(dist = CLIENT) 类；合并后改为普通工具类，
 * 由 FlapDisplayPlus 主类在客户端环境（Dist.CLIENT）时调用，避免重复注册 mod。
 *
 * 注册：自定义配置界面（ConfigScreenHandler 扩展点）、多平台搜索源、QQ 登录凭证加载。
 */
public final class MusicClientInit {

    private MusicClientInit() {
    }

    public static void init(ModContainer container) {
        // 注册自定义配置界面（列表模式 / 每页数量 / QQ 登录），取代 Forge 默认配置界面
        // Forge 的扩展点：ConfigScreenHandler.ConfigScreenFactory（BiFunction<Minecraft, Screen, Screen>）
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new ConfigScreen(
                                ModLoadingContext.get().getActiveContainer(), parent)));
        // 注册多平台搜索源（网易云 / QQ音乐 / 酷狗 ...）
        SearchSourceManager.registerAll();
        // 加载 QQ 登录凭证（位于游戏配置目录）
        Path configDir = FMLPaths.CONFIGDIR.get();
        QqCredentialManager.init(configDir);
    }
}
