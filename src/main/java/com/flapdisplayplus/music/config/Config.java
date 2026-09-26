package com.flapdisplayplus.music.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置类。
 *
 * 所有可调参数集中在这里，通过 NeoForge 的 ModConfigSpec 机制管理。
 * 配置文件生成在 config/netmusicdisplay-common.toml。
 *
 * 配置分组：
 * - display：显示相关（暂停时是否显示歌词/时间/符号）
 * - redstone：红石信号模式
 * - playback：播放行为（暂停续播）
 * - netease：网易云 Cookie 登录
 */
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ===== 显示设置 =====
    public static final ModConfigSpec.BooleanValue SHOW_LYRIC_WHEN_PAUSED;
    public static final ModConfigSpec.BooleanValue SHOW_PAUSE_TIME;
    public static final ModConfigSpec.BooleanValue PAUSE_SYMBOL_ENABLED;

    // ===== 红石模式 =====
    public static final ModConfigSpec.EnumValue<RedstoneMode> REDSTONE_MODE;

    // ===== 播放行为 =====
    public static final ModConfigSpec.BooleanValue PAUSE_RESUME;

    // ===== 网易云 Cookie =====
    public static final ModConfigSpec.ConfigValue<String> NETEASE_COOKIE;

    // ===== 音质 =====
    public static final ModConfigSpec.EnumValue<AudioQuality> AUDIO_QUALITY;

    // ===== 搜索结果列表 =====
    public static final ModConfigSpec.EnumValue<SearchListMode> SEARCH_LIST_MODE;
    public static final ModConfigSpec.IntValue SEARCH_PAGE_SIZE;

    static {
        // --- 显示 ---
        BUILDER.push("display");

        SHOW_LYRIC_WHEN_PAUSED = BUILDER
                .comment("暂停时是否继续显示当前歌词行（而不是显示 ~）")
                .translation("netmusicdisplay.config.show_lyric_when_paused")
                .define("show_lyric_when_paused", true);

        SHOW_PAUSE_TIME = BUILDER
                .comment("暂停时是否在播放状态行显示暂停位置时间")
                .translation("netmusicdisplay.config.show_pause_time")
                .define("show_pause_time", true);

        PAUSE_SYMBOL_ENABLED = BUILDER
                .comment("播放状态行是否显示播放/暂停符号（▶/■）")
                .translation("netmusicdisplay.config.pause_symbol_enabled")
                .define("pause_symbol_enabled", true);

        BUILDER.pop();

        // --- 红石 ---
        BUILDER.push("redstone");

        REDSTONE_MODE = BUILDER
                .comment("红石信号模式")
                .comment("EDGE_TOGGLE: 边沿触发切换（原版行为，红石信号上升沿切换播放/暂停）")
                .comment("CONTINUOUS: 持续模式（有信号=播放，无信号=暂停，需配合暂停续播）")
                .translation("netmusicdisplay.config.redstone_mode")
                .defineEnum("redstone_mode", RedstoneMode.EDGE_TOGGLE);

        BUILDER.pop();

        // --- 播放 ---
        BUILDER.push("playback");

        PAUSE_RESUME = BUILDER
                .comment("暂停后继续播放时从暂停位置恢复，而非从头开始")
                .translation("netmusicdisplay.config.pause_resume")
                .define("pause_resume", true);

        BUILDER.pop();

        // --- 网易云 ---
        BUILDER.push("netease");

        NETEASE_COOKIE = BUILDER
                .comment("网易云音乐 Cookie（用于访问 VIP 歌曲、私人 FM 等）。留空=不使用。")
                .comment("获取方式：浏览器登录网易云网页版，F12 打开开发者工具，Network 面板找任意请求的 Cookie 头。")
                .comment("也可在「网易云设置」页点「打开登录界面」扫码/账号登录后自动写入；此处支持手动粘贴 Cookie。")
                .translation("netmusicdisplay.config.netease_cookie")
                .define("cookie", "");

        AUDIO_QUALITY = BUILDER
                .comment("歌曲音质等级（需登录才能使用高音质）")
                .comment("STANDARD: 标准 / HIGHER: 较高 / EXHIGH: 极高 / LOSSLESS: 无损 / HIRES: Hi-Res")
                .comment("Song audio quality level (login required for high quality)")
                .comment("STANDARD / HIGHER / EXHIGH / LOSSLESS / HIRES")
                .translation("netmusicdisplay.config.audio_quality")
                .defineEnum("audio_quality", AudioQuality.HIGHER);

        BUILDER.pop();

        // --- 搜索结果列表 ---
        BUILDER.push("search_list");

        SEARCH_LIST_MODE = BUILDER
                .comment("搜索结果列表展示方式")
                .comment("SCROLL: 可滚动长列表，列出全部结果")
                .comment("PAGINATE: 分页展示，每页数量由 search_page_size 决定")
                .translation("netmusicdisplay.config.search_list_mode")
                .defineEnum("search_list_mode", SearchListMode.SCROLL);

        SEARCH_PAGE_SIZE = BUILDER
                .comment("翻页模式下每页显示的歌曲数量（仅在 SCROLL 关闭时生效）")
                .translation("netmusicdisplay.config.search_page_size")
                .defineInRange("search_page_size", 10, 5, 50);

        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * 红石信号模式枚举。
     */
    public enum RedstoneMode {
        /** 边沿触发切换（原版行为） */
        EDGE_TOGGLE,
        /** 持续模式：有信号=播放，无信号=暂停 */
        CONTINUOUS
    }

    /**
     * 歌曲音质枚举。level 字段对应网易云 API 的 level 参数。
     */
    public enum AudioQuality {
        /** 标准音质 */
        STANDARD("standard"),
        /** 较高音质（默认，VIP 可用） */
        HIGHER("higher"),
        /** 极高音质 */
        EXHIGH("exhigh"),
        /** 无损音质 */
        LOSSLESS("lossless"),
        /** Hi-Res 高解析度 */
        HIRES("hires");

        private final String level;

        AudioQuality(String level) {
            this.level = level;
        }

        public String getLevel() {
            return level;
        }
    }

    /**
     * 搜索结果列表展示方式。
     */
    public enum SearchListMode {
        /** 可滚动长列表（列出全部结果） */
        SCROLL,
        /** 翻页展示 */
        PAGINATE
    }
}
