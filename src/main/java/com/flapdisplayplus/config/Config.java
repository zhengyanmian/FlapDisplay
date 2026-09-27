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

    /** 媒体叠加层左右内缩（1/32 方块/单位；正数向内） */
    public static final ModConfigSpec.DoubleValue MEDIA_INSET_X;

    /** 媒体叠加层上下内缩（1/32 方块/单位；正数向内） */
    public static final ModConfigSpec.DoubleValue MEDIA_INSET_Y;

    /** 媒体叠加层深度偏移（局部单位，1 单位 = 1/32 方块） */
    public static final ModConfigSpec.DoubleValue MEDIA_Z_OFFSET;

    /** 静态图片纹理长边上限（像素）：图片只解码一次，可给较大值保证清晰 */
    public static final ModConfigSpec.IntValue MEDIA_IMAGE_MAX_DIM;

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
                .comment("视频纹理长边上限(像素)，默认 720。",
                        "【2026-09-27 更新】逐像素填充已移到后台视频线程（零分配管线），主线程每帧只做一次纹理上传，",
                        "该值不再影响主线程卡顿；主要影响清晰度与纹理显存。720p 及以下视频设 720 = 原生分辨率不缩放。",
                        "嫌糊就调高（1024/2048），显存吃紧再调低。")
                .defineInRange("media.videoMaxDim", 720, 64, 4096);

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

        MEDIA_INSET_X = builder
                .comment("媒体叠加层相对【整条显示带】的左右内缩（单位 = 1/32 方块，2 单位 ≈ 1 像素）。",
                        "正数向带内缩，负数向带外扩。",
                        "默认 -0.5 = 略微外扩，让媒体完整盖住翻牌可见面板的左右边缘。",
                        "可用游戏内指令 /fdpcal ix <值> 实时微调。")
                .defineInRange("media.insetX", -0.5, -16.0, 16.0);

        MEDIA_INSET_Y = builder
                .comment("媒体叠加层相对【整条显示带】的上下内缩（单位 = 1/32 方块，2 单位 ≈ 1 像素）。",
                        "默认 2.5 = 可见面板比整条显示带上下各少 2.5 单位。",
                        "【注意】它只影响【正面平视】时的上下边缘对齐；",
                        "「斜着看时媒体与方块有半像素错位」是深度视差，只能靠 media.panelDepth 修，",
                        "调这个值再久也没用。",
                        "可用游戏内指令 /fdpcal iy <值> 实时微调。")
                .defineInRange("media.insetY", 2.5, -16.0, 16.0);

        MEDIA_Z_OFFSET = builder
                .comment("媒体叠加层在【翻牌字符坐标系】里的深度（1 单位 = 1/32 方块）。",
                        "★「从侧面看，媒体和方块之间还差半个像素」的【唯一】来源就是它 ——",
                        "  x / y / inset 都是正向平移，结构上不可能修掉一个随视角变化的偏差，",
                        "  所以以前无论怎么调那三个旋钮都没用。",
                        "",
                        "坐标基准（create 6.0.10 FlapDisplayRenderer.renderSafe 字节码实测）：",
                        "   0    = 翻牌【字符平面】。Create 把字符叶片画在面板可见正面【前方 0.5 单位】，",
                        "          用途是防止叶片与面板共面闪烁。旧默认 0.01 就落在这个平面上，",
                        "          于是媒体跟着一起前凸 0.5 单位 ⇒ 斜看时错开约半个像素。",
                        "  -0.5   = 与面板【可见正面】完全共面 ⇒ 视差恒为 0（数学上彻底消除）。这是默认值。",
                        "  > 0    = 更靠外，斜看时媒体明显浮在方块外（旧行为）。",
                        "",
                        "为什么可以安全地压回 -0.5：本模组的媒体显示源 provideLine() 返回 EMPTY_LINE，",
                        "字符平面上没有任何叶片（空格不产生字形四边形），所以不会被字符挡住；",
                        "与面板共面时的深度排序由 entityCutoutNoCullZOffset 的深度偏置保证，不会闪烁。",
                        "默认 -0.49 = 留 0.01 单位的确定性余量（视差仅为 -0.5 的 2%，肉眼不可见）。",
                        "可用游戏内指令 /fdpcal z <值> 实时微调。",
                        "",
                        "【为什么键名从 media.zOffset 改成 media.panelDepth】",
                        "旧键 media.zOffset 的语义理解错了（当时以为是「随便的深度微调」），",
                        "而且旧默认 0.01 已经落盘到 config/flapdisplayplus-common.toml 里了 ——",
                        "NeoForge 读文件值优先于代码默认值，只改默认值对老存档【毫无效果】。",
                        "改名后旧键自然失效，本修复才能保证对所有已存在的存档立即生效。")
                .defineInRange("media.panelDepth", -0.49, -2.0, 0.5);

        MEDIA_IMAGE_MAX_DIM = builder
                .comment("静态图片纹理长边上限(像素)，默认2048。图片只解码一次，可给大值保证清晰。")
                .defineInRange("media.imageMaxDim", 2048, 64, 8192);

        MEDIA_NET_CACHE_DIR = builder
                .comment("网络媒体缓存目录；留空则使用 游戏目录/flap-media/netcache。")
                .define("media.netCacheDir", "");

        MEDIA_NET_CACHE_MAX_MB = builder
                .comment("网络媒体缓存总上限(MB)，默认512；超出后清理最久未使用的文件。")
                .defineInRange("media.netCacheMaxMb", 512, 16, 8192);

        SPEC = builder.build();
    }
}
