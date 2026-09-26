/*
 * Config.java
 *
 * 模组配置（NeoForge ModConfigSpec）。
 */
package com.flapdisplayplus.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

    private Config() {
    }

    public static final ModConfigSpec SPEC;

    /** 当前显示的本地媒体文件路径（图片/GIF，后续支持视频） */
    public static final ModConfigSpec.ConfigValue<String> MEDIA_IMAGE_PATH;

    /** 媒体叠加层在翻牌上的绘制模式 */
    public static final ModConfigSpec.EnumValue<MediaFit> MEDIA_FIT;

    /** 视频播放时是否播放声音（音轨存在时） */
    public static final ModConfigSpec.BooleanValue MEDIA_VIDEO_SOUND;

    /** 视频纹理长边上限（像素）：越大越清晰，但也更吃 CPU/显存；配合「解码即降采样」避免原生分辨率大缓冲 */
    public static final ModConfigSpec.IntValue MEDIA_VIDEO_MAX_DIM;

    /** 视频显示帧率上限（解码抽帧）：源帧率高于此值时抽帧显示，降低 CPU 占用 */
    public static final ModConfigSpec.IntValue MEDIA_VIDEO_FPS;
    /** 媒体叠加层横向微调（1/32 方块/单位） */
    public static final ModConfigSpec.IntValue MEDIA_MEDIA_OFFSET_X;
    /** 媒体叠加层纵向微调（1/32 方块/单位，正数向下） */
    public static final ModConfigSpec.IntValue MEDIA_MEDIA_OFFSET_Y;
    /** 媒体叠加层四周内缩（1/32 方块/单位） */
    public static final ModConfigSpec.IntValue MEDIA_MEDIA_INSET;

    /** 静态图片纹理长边上限（像素）：图片只解码一次，可给较大值保证清晰 */
    public static final ModConfigSpec.IntValue MEDIA_IMAGE_MAX_DIM;

    /**
     * 外部视频解析工具（yt-dlp）路径；留空则按系统 PATH 查找。
     * 模组仅以子进程方式调用它获取直链，自身不包含任何站点解析逻辑。
     */
    public static final ModConfigSpec.ConfigValue<String> MEDIA_YTDLP_PATH;

    /** 外部解析超时（秒）：站点解析慢或网络差时避免卡死界面 */
    public static final ModConfigSpec.IntValue MEDIA_RESOLVER_TIMEOUT_SEC;

    /** 网络媒体缓存目录（留空则用 游戏目录/flap-media/netcache） */
    public static final ModConfigSpec.ConfigValue<String> MEDIA_NET_CACHE_DIR;

    /** 网络媒体缓存总上限（MB），超出后清理最久未用的文件 */
    public static final ModConfigSpec.IntValue MEDIA_NET_CACHE_MAX_MB;

    public enum MediaFit {
        /** 完整显示（保持宽高比，可能留黑边） */
        FIT,
        /** 拉伸铺满整个翻牌正面 */
        STRETCH,
        /** 裁切铺满（cover） */
        COVER
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        MEDIA_IMAGE_PATH = builder
                .comment("当前显示的本地媒体文件绝对路径（PNG/JPG/GIF，后续支持视频）")
                .define("media.imagePath", "");

        MEDIA_FIT = builder
                .comment("媒体在翻牌上的绘制模式: FIT=完整显示 STRETCH=拉伸铺满 COVER=裁切铺满")
                .defineEnum("media.fit", MediaFit.FIT);

        MEDIA_VIDEO_SOUND = builder
                .comment("播放视频时是否播放声音（需视频本身带音轨）")
                .define("media.videoSound", true);

        MEDIA_VIDEO_MAX_DIM = builder
                .comment("视频纹理长边上限(像素)，默认 256。",
                        "为什么默认从 1024 降到 256：实测视频卡顿的主因不是解码，而是每帧在【主线程】",
                        "把像素逐个写进纹理（w*h 次 setPixelRGBA）。1024×576≈59 万次/帧 → 主线程被压垮。",
                        "翻牌面板在屏幕上通常只有一两百像素宽，256 已完全够看。",
                        "调高会更清晰但主线程开销按面积平方增长；卡顿就继续调低（如 128）。")
                .defineInRange("media.videoMaxDim", 256, 64, 4096);

        MEDIA_VIDEO_FPS = builder
                .comment("视频显示帧率上限，默认24。卡顿严重时调低(如15/20)；追求流畅与省CPU。")
                .defineInRange("media.videoFps", 24, 1, 60);

        MEDIA_MEDIA_OFFSET_X = builder
                .comment("媒体叠加层相对翻牌面板的横向微调（单位 = 1/32 方块，即约 0.5 像素；2 = 1 像素）。",
                        "用于消除「图片/视频与方块错开一个像素」的观感。正数向右。")
                .defineInRange("media.offsetX", 0, -32, 32);

        MEDIA_MEDIA_OFFSET_Y = builder
                .comment("媒体叠加层相对翻牌面板的纵向微调（单位 = 1/32 方块，即约 0.5 像素；2 = 1 像素）。",
                        "正数向下。")
                .defineInRange("media.offsetY", 0, -32, 32);

        MEDIA_MEDIA_INSET = builder
                .comment("媒体叠加层四周内缩量（单位 = 1/32 方块，2 单位 = 1 像素）。",
                        "默认 0 = 与翻牌【可见面板】四边完全对齐（消除「与方块差一个像素」）。",
                        "正数向内缩（避免压住面板边框）；负数向外扩 —— 例如 -3 可把可见面板",
                        "之外的边框一起盖住，让媒体铺满整块面板。")
                .defineInRange("media.inset", 0, -16, 16);

        MEDIA_IMAGE_MAX_DIM = builder
                .comment("静态图片纹理长边上限(像素)，默认2048。图片只解码一次，可给大值保证清晰。")
                .defineInRange("media.imageMaxDim", 2048, 64, 8192);

        MEDIA_YTDLP_PATH = builder
                .comment("yt-dlp 可执行文件路径；留空则按系统 PATH 查找。"
                        + "模组只以子进程方式调用它获取直链，不含任何站点解析代码。"
                        + "未安装时可用 winget install yt-dlp 安装。")
                .define("media.ytdlpPath", "");

        MEDIA_RESOLVER_TIMEOUT_SEC = builder
                .comment("调用外部解析器(yt-dlp)的超时秒数，默认60。")
                .defineInRange("media.resolverTimeoutSec", 60, 5, 600);

        MEDIA_NET_CACHE_DIR = builder
                .comment("网络媒体缓存目录；留空则使用 游戏目录/flap-media/netcache。")
                .define("media.netCacheDir", "");

        MEDIA_NET_CACHE_MAX_MB = builder
                .comment("网络媒体缓存总上限(MB)，默认512；超出后清理最久未使用的文件。")
                .defineInRange("media.netCacheMaxMb", 512, 16, 8192);

        SPEC = builder.build();
    }
}
