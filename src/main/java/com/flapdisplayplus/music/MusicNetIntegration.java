package com.flapdisplayplus.music;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.arm.ModArmInteractionPoints;
import com.flapdisplayplus.music.config.Config;
import com.flapdisplayplus.music.netease.NeteaseVIPResolver;
import com.flapdisplayplus.music.resolver.QQMusicUrlResolver;
import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.NetEaseMusic;
import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.github.tartaricacid.netmusic.init.InitBlocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 网络音乐联动集成类（合并自原「不是显示来源」模组）。
 *
 * 作为 FlapDisplayPlus 的一个可选功能模块：网络音乐机（Net Music）不是前置，
 * 而是软联动——检测到 Net Music 模组已安装时才注册音乐显示源；
 * 未安装时功能禁用（界面显示"请安装网络音乐机"提示）。
 *
 * 作用：把自定义 Create 显示数据源注册进 Create 的 DISPLAY_SOURCE 注册表，
 * 关联到 Net Music 的 CD 播放机方块实体，玩家用显示链接器指向 CD 播放机时，
 * 可在翻牌显示器上看到歌曲名/播放状态/封面/歌词。
 */
public final class MusicNetIntegration {
    /** Net Music 模组 id */
    public static final String NETMUSIC_MODID = "netmusic";
    public static final Logger LOGGER = LogManager.getLogger("FlapDisplayPlus.Music");

    private MusicNetIntegration() {
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
     * 检测到 Net Music 才执行注册；未安装则静默跳过（不触发 netmusic 类加载）。
     */
    public static void init(IEventBus modBus, ModContainer modContainer) {
        if (!isNetMusicLoaded()) {
            LOGGER.info("[Music] 未检测到网络音乐机模组，音乐联动功能禁用（软联动）。");
            return;
        }
        LOGGER.info("[Music] 检测到网络音乐机，启用音乐联动。");
        try {
            // 注册模组配置（NeoForge 21.1：registerConfig 返回 void，配置值经 Config.SPEC 访问）
            // 注意：必须用独立文件名——FlapDisplayPlus 主配置已占用 flapdisplayplus-common.toml，
            // 同名注册会抛 "Detected config file conflict" 并被下方 catch 吞掉，
            // 导致 Config.SPEC 永不加载，mixin 里 Config.XXX.get() 会崩
            // ("Cannot get config value before config is loaded")。
            modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC,
                    "flapdisplayplus-music-common.toml");
            // 注册 VIP 播放解析器（必须在 Net Music 的 load complete 事件之前）
            MusicPlayResolverManager.registerResolver(new NeteaseVIPResolver());
            // 注册 QQ 音乐播放地址解析器（qqmusic:{songmid} 伪 URL -> 实时换取 vkey 播放地址）
            MusicPlayResolverManager.registerResolver(new QQMusicUrlResolver());
            // 注册自定义数据源到 Create 的注册表
            ModDisplaySources.register(modBus);
            // 注册动力臂交互点类型（让机械臂能识别 CD 播放机）
            ModArmInteractionPoints.register(modBus);
            // 在通用初始化阶段，把数据源关联到 Net Music 的 CD 播放机方块实体
            modBus.addListener(MusicNetIntegration::commonSetup);
        } catch (Throwable t) {
            LOGGER.error("[Music] 音乐联动初始化失败（可能 Net Music API 不兼容）", t);
        }
    }

    private static void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                // 如果配置了网易云 Cookie，用带认证的 API 替换 Net Music 默认的匿名 API
                initNeteaseCookie();

                LOGGER.info("[Music] Registering display sources to MUSIC_PLAYER_TE...");

                // 检查 InitBlocks.MUSIC_PLAYER_TE 是否存在
                if (InitBlocks.MUSIC_PLAYER_TE == null || InitBlocks.MUSIC_PLAYER_TE.get() == null) {
                    LOGGER.error("[Music] InitBlocks.MUSIC_PLAYER_TE is null! NetMusic mod may not be ready.");
                    return;
                }

                // DisplaySource.BY_BLOCK_ENTITY 是 Create 提供的「方块实体类型 -> 数据源」映射表。
                // 玩家用显示链接器指向 Net Music CD 播放机时，会看到六个选项：
                //   单行：歌曲名 / 播放状态 / 原歌词 / 翻译歌词
                //   多行：综合（状态+歌名+原歌词+翻译）/ 双行歌词（原歌词+翻译）
                // 注：封面图文显示源已废弃（封面走 FlapDisplayPlus 图片渲染管线）
                DisplaySource.BY_BLOCK_ENTITY.add(InitBlocks.MUSIC_PLAYER_TE.get(), ModDisplaySources.NETMUSIC_SONG_NAME.get());
                DisplaySource.BY_BLOCK_ENTITY.add(InitBlocks.MUSIC_PLAYER_TE.get(), ModDisplaySources.NETMUSIC_PLAY_STATUS.get());
                DisplaySource.BY_BLOCK_ENTITY.add(InitBlocks.MUSIC_PLAYER_TE.get(), ModDisplaySources.NETMUSIC_LYRIC.get());
                DisplaySource.BY_BLOCK_ENTITY.add(InitBlocks.MUSIC_PLAYER_TE.get(), ModDisplaySources.NETMUSIC_TRANS_LYRIC.get());
                DisplaySource.BY_BLOCK_ENTITY.add(InitBlocks.MUSIC_PLAYER_TE.get(), ModDisplaySources.NETMUSIC_ALL_IN_ONE.get());
                DisplaySource.BY_BLOCK_ENTITY.add(InitBlocks.MUSIC_PLAYER_TE.get(), ModDisplaySources.NETMUSIC_DUAL_LYRIC.get());

                LOGGER.info("[Music] All 6 display sources registered successfully!");
            } catch (Throwable t) {
                LOGGER.error("[Music] 显示源注册失败", t);
            }
        });
    }

    /**
     * 如果配置了网易云 Cookie，用带认证的 API 替换 Net Music 默认的匿名 API。
     */
    private static void initNeteaseCookie() {
        String cookie = Config.NETEASE_COOKIE.get();
        if (cookie == null || cookie.trim().isEmpty()) {
            LOGGER.info("[Music] 未配置网易云 Cookie，使用匿名 API。");
            return;
        }
        applyCookie(cookie);
    }

    /**
     * 应用网易云 Cookie，替换 Net Music 的 API 实例。
     * 供启动初始化与游戏内指令共同调用。
     *
     * @param cookie 网易云 Cookie 字符串，空字符串 = 使用匿名 API
     */
    public static void applyCookie(String cookie) {
        // 软联动守卫：Net Music 未安装时 NetMusic.NET_EASE_WEB_API 不存在，
        // 直接引用会抛 NoClassDefFoundError（Error，catch(Exception) 接不住），必须前置判断
        if (!isNetMusicLoaded()) {
            LOGGER.info("[Music] 未安装网络音乐机，跳过 Cookie 应用。");
            return;
        }
        try {
            if (cookie == null || cookie.trim().isEmpty()) {
                NetMusic.NET_EASE_WEB_API = new NetEaseMusic().getApi();
                LOGGER.info("[Music] 已切换为匿名 API。");
            } else {
                NetMusic.NET_EASE_WEB_API = new NetEaseMusic(cookie.trim()).getApi();
                LOGGER.info("[Music] 已启用网易云 Cookie 认证 API。");
            }
        } catch (Throwable t) {
            LOGGER.error("[Music] 应用网易云 Cookie 失败。", t);
        }
    }
}
