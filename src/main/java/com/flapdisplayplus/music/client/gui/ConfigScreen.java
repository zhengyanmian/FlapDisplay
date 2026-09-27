/*
 * ConfigScreen.java
 *
 * 模组统一配置界面（客户端），「主页 Hub + 各功能子页」结构：
 * - 主页：功能入口
 * - 通用/媒体（本模组自身配置，对应 config/Config.java）
 * - 显示/红石/播放/网易云/搜索（音乐联动配置，对应 music/config/Config.java）
 *
 * 【2026-08-28 重构】
 *  1. 全部按钮换用 FdpWidgets（自绘木质风格），消除与主界面的风格割裂。
 *  2. 说明文字改 TextFlow 累加排版，不再手工累加 y 坐标（加一行要重算后面所有行）。
 *  3. 新增「媒体性能」子页，暴露 videoMaxDim / videoFps / imageMaxDim —— 这三个配置
 *     原先只能手改配置文件，用户遇到卡顿时无从下手。
 *  4. 布尔/枚举控件改 FdpWidgets.toggle/cycle，配置写入与落盘逻辑收敛到一处。
 */
package com.flapdisplayplus.music.client.gui;

import com.flapdisplayplus.client.FdpButton;
import com.flapdisplayplus.client.FdpWidgets;
import com.flapdisplayplus.config.Config;
import com.flapdisplayplus.music.MusicNetIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

public class ConfigScreen extends Screen {

    private final Screen parent;
    private final ModContainer modContainer;
    private final Page page;

    /** 说明文字：由 TextFlow 累加排版 */
    private final TextFlow flow = new TextFlow();

    public ConfigScreen(ModContainer modContainer, Screen parent) {
        this(modContainer, parent, Page.HUB);
    }

    public ConfigScreen(ModContainer modContainer, Screen parent, Page page) {
        super(Component.literal(page.title));
        this.modContainer = modContainer;
        this.parent = parent;
        this.page = page;
    }

    enum Page {
        HUB("翻牌万象 FlapDisplayPlus 设置"),
        GENERAL("通用设置"),
        MEDIA("媒体性能"),
        MUSIC_HUB("网络音乐机配置"),
        DISPLAY("显示设置"),
        REDSTONE("红石设置"),
        PLAYBACK("播放设置"),
        NETEASE("网易云设置"),
        SEARCH("搜索设置");

        final String title;
        Page(String t) { this.title = t; }
    }

    /**
     * 说明文字的累加排版器。
     *
     * 原实现是 `info("...", cx - 150, y, 0xC8C8C8); y += 16;` 反复手写 ——
     * 在 buildNetease 里 14 行、buildSearch 里 9 行，插入一行要重算后续全部坐标。
     * 现在每类文字自带行高与颜色，位置由累加器维护，每行在加入时固化自己的 y。
     */
    static final class TextFlow {
        private static final int GAP = 16;

        private record Line(String text, int color, int y) {
        }

        private final List<Line> lines = new ArrayList<>();
        private int y = 44;

        /** 重新开始（init 每次重建界面时调用） */
        void reset() {
            lines.clear();
            y = 44;
        }

        /** 空移光标（用于给控件让位） */
        void skip(int px) { y += px; }

        /** 小节标题（金色，上方留白） */
        void h1(String text) {
            y += 6;
            add(text, FdpWidgets.TEXT_HL);
            y += 2;
        }

        /** 正文（浅灰） */
        void p(String text) { add(text, 0xFFC8C8C8); }

        /** 次要说明（暗米色） */
        void dim(String text) { add(text, FdpWidgets.TEXT_DIM); }

        /** 等宽风格（链接/标识符，浅蓝） */
        void mono(String text) { add(text, 0xFF9CDCFE); }

        /** 自定义颜色 */
        void colored(String text, int color) { add(text, color); }

        private void add(String text, int color) {
            lines.add(new Line(text, color, y));
            y += GAP;
        }

        /** 当前光标位置（用于在同一行放控件） */
        int y() { return y; }

        void render(GuiGraphics graphics, net.minecraft.client.gui.Font font, int left) {
            for (Line l : lines) {
                graphics.drawString(font, l.text(), left, l.y(), l.color());
            }
        }
    }

    @Override
    protected void init() {
        flow.reset();
        switch (page) {
            case HUB -> buildHub();
            case GENERAL -> buildGeneral();
            case MEDIA -> buildMedia();
            case MUSIC_HUB -> buildMusicHub();
            case DISPLAY -> buildDisplay();
            case REDSTONE -> buildRedstone();
            case PLAYBACK -> buildPlayback();
            case NETEASE -> buildNetease();
            case SEARCH -> buildSearch();
        }
    }

    // ===================== 主页 Hub =====================
    private void buildHub() {
        int cx = this.width / 2;
        int y = 56;
        int gap = 32;
        int w = 260;

        this.addRenderableWidget(FdpButton.create(cx - w / 2, y, w, 20,
                Component.literal("通用与媒体设置"), b ->
                        Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, this, Page.GENERAL))));
        y += gap;

        boolean netMusic = MusicNetIntegration.isNetMusicLoaded();
        this.addRenderableWidget(FdpButton.create(cx - w / 2, y, w, 20,
                Component.literal("🎵 网络音乐机配置" + (netMusic ? "" : "（未安装）")), b -> {
                    if (netMusic) {
                        Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, this, Page.MUSIC_HUB));
                    } else if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.displayClientMessage(
                                Component.literal("未安装网络音乐机（Net Music）模组，无法配置音乐联动"), false);
                    }
                }));
        y += gap;

        this.addRenderableWidget(FdpButton.create(cx - 50, this.height - 30, 100, 20,
                Component.literal("返回"), b -> this.onClose()));
    }

    // ===================== 通用设置 =====================
    private void buildGeneral() {
        int cx = this.width / 2;
        int left = cx - 150;

        flow.h1("视频");
        addToggle(left, flow.y(), 70, Config.MEDIA_VIDEO_SOUND, "视频播放声音");
        flow.skip(26);

        this.addRenderableWidget(FdpButton.create(left, flow.y(), 160, 20,
                Component.literal("媒体性能（清晰度/帧率）"), b ->
                        Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, this, Page.MEDIA))));
        flow.skip(34);

        flow.h1("网络媒体");
        flow.p("直链（http 图片/视频）可直接使用。");
        flow.p("视频网站链接需要本机的 yt-dlp 解析，");
        flow.p("模组自身不含任何站点解析代码。");

        addPathRow(left, flow.y(), "yt-dlp 路径", Config.MEDIA_YTDLP_PATH, 150);
        flow.dim("未安装时可用 winget install yt-dlp 安装。");
        flow.dim("直链图片/视频无需 yt-dlp。");
        flow.skip(6);

        this.addRenderableWidget(FdpButton.create(left, flow.y(), 160, 20,
                Component.literal("打开媒体缓存文件夹"), b -> {
                    java.io.File dir = new java.io.File(
                            Minecraft.getInstance().gameDirectory, "flap-media/netcache");
                    dir.mkdirs();
                    net.minecraft.Util.getPlatform().openFile(dir);
                }));

        backToHubButton();
    }

    // ===================== 媒体性能（本次新增）=====================
    private void buildMedia() {
        int cx = this.width / 2;
        int left = cx - 150;
        int w = 300;

        flow.h1("视频清晰度（纹理长边上限）");
        addIntCycle(left, flow.y(), w, Config.MEDIA_VIDEO_MAX_DIM,
                new int[]{512, 768, 1024, 1440, 2048}, v -> v + " px  " + qualityHint(v));
        flow.skip(26);

        flow.h1("视频帧率上限");
        addIntCycle(left, flow.y(), w, Config.MEDIA_VIDEO_FPS,
                new int[]{12, 15, 20, 24, 30, 60}, v -> v + " fps  " + fpsHint(v));
        flow.skip(26);

        flow.h1("图片清晰度（纹理长边上限）");
        addIntCycle(left, flow.y(), w, Config.MEDIA_IMAGE_MAX_DIM,
                new int[]{1024, 2048, 4096, 8192}, v -> v + " px");
        flow.skip(34);

        flow.h1("调参指引");
        flow.p("卡顿（画面掉帧、翻牌不刷新）：先降帧率到 15，");
        flow.p("再把清晰度降到 768；两者都降仍卡则改用小视频。");
        flow.p("模糊（画面看不清）：提高清晰度到 1440/2048。");
        flow.p("图片只解码一次，可放心给大值。");
        flow.colored("改动即时生效，正在播放的视频会自动重启。", FdpWidgets.TEXT_OK);

        backToHubButton();
    }

    private String qualityHint(int v) {
        if (v <= 512) return "最省";
        if (v <= 768) return "省";
        if (v <= 1024) return "默认";
        if (v <= 1440) return "清晰";
        return "最清晰";
    }

    private String fpsHint(int v) {
        if (v <= 15) return "最省";
        if (v <= 20) return "省";
        if (v <= 24) return "默认";
        return "流畅";
    }

    // ===================== 网络音乐机配置子菜单 =====================
    private void buildMusicHub() {
        int cx = this.width / 2;

        if (!MusicNetIntegration.isNetMusicLoaded()) {
            flow.h1("未检测到网络音乐机（Net Music）模组");
            flow.colored("音乐联动功能当前不可用", 0xFFFFAA55);
            flow.p("请安装 Net Music 后重启游戏，即可联动显示");
            flow.p("封面、歌名与歌词（翻牌 / CD 播放机）");
            backToHubButton();
            return;
        }

        int y = 48;
        int gap = 32;
        int w = 260;
        addPageButton("显示设置", Page.DISPLAY, cx, y); y += gap;
        addPageButton("红石设置", Page.REDSTONE, cx, y); y += gap;
        addPageButton("播放设置", Page.PLAYBACK, cx, y); y += gap;
        addPageButton("网易云设置", Page.NETEASE, cx, y); y += gap;
        addPageButton("搜索设置", Page.SEARCH, cx, y); y += gap;
        this.addRenderableWidget(FdpButton.create(cx - w / 2, y, w, 20,
                Component.literal("QQ音乐设置（扫码登录）"), b ->
                        Minecraft.getInstance().setScreen(new QqLoginScreen(this))));
        y += gap;
        backToHubButton();
    }

    private void backToHubButton() {
        this.addRenderableWidget(FdpButton.create(this.width / 2 - 80, this.height - 30, 160, 20,
                Component.literal("← 返回设置主页"),
                b -> Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, parent, Page.HUB))));
    }

    private void addPageButton(String label, Page target, int cx, int y) {
        this.addRenderableWidget(FdpButton.create(cx - 130, y, 260, 20,
                Component.literal(label),
                b -> Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, this, target))));
    }

    private void backButton() {
        this.addRenderableWidget(FdpButton.create(this.width / 2 - 80, this.height - 30, 160, 20,
                Component.literal("← 返回音乐配置"),
                b -> Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, parent, Page.MUSIC_HUB))));
    }

    // ===================== 显示设置 =====================
    private void buildDisplay() {
        int cx = this.width / 2;
        int left = cx - 150;
        int y = 48;
        addToggle(left, y, 70, com.flapdisplayplus.music.config.Config.SHOW_LYRIC_WHEN_PAUSED, "暂停时显示歌词"); y += 30;
        addToggle(left, y, 70, com.flapdisplayplus.music.config.Config.SHOW_PAUSE_TIME, "暂停时显示时间"); y += 30;
        addToggle(left, y, 70, com.flapdisplayplus.music.config.Config.PAUSE_SYMBOL_ENABLED, "显示播放/暂停符号"); y += 40;

        flow.skip(y - flow.y());
        flow.p("这些选项控制翻牌显示器在歌曲暂停时显示的内容。");
        backButton();
    }

    // ===================== 红石设置 =====================
    private void buildRedstone() {
        int cx = this.width / 2;
        int left = cx - 150;
        int y = 48;
        addEnumToggle(left, y, 110, com.flapdisplayplus.music.config.Config.REDSTONE_MODE); y += 44;

        flow.skip(y - flow.y());
        flow.p("边沿触发：红石上升沿切换播放/暂停（原版行为）。");
        flow.p("持续模式：有信号=播放，无信号=暂停（需配合暂停续播）。");
        backButton();
    }

    // ===================== 播放设置 =====================
    private void buildPlayback() {
        int cx = this.width / 2;
        int left = cx - 150;
        int y = 48;
        addToggle(left, y, 70, com.flapdisplayplus.music.config.Config.PAUSE_RESUME, "暂停后续播"); y += 44;

        flow.skip(y - flow.y());
        flow.p("开启后，暂停再播放会从暂停位置继续，而非从头开始。");
        backButton();
    }

    // ===================== 网易云设置 =====================
    private void buildNetease() {
        int cx = this.width / 2;
        int left = cx - 150;
        int w = 300;

        flow.h1("登录与会话");

        // Cookie 输入框（光标所在行即控件行）
        int boxY = flow.y();
        EditBox cookieBox = new EditBox(this.font, left, boxY, w, 20, Component.literal(""));
        cookieBox.setMaxLength(3000);
        cookieBox.setValue(com.flapdisplayplus.music.config.Config.NETEASE_COOKIE.get());
        cookieBox.setResponder(s -> com.flapdisplayplus.music.config.Config.NETEASE_COOKIE.set(s.trim()));
        this.addRenderableWidget(cookieBox);
        flow.skip(26);
        flow.dim("网易云 Cookie（可选，留空 = 默认音质）");
        flow.skip(4);

        // 音质
        int qualityY = flow.y();
        flow.skip(4);
        addEnumToggle(left, qualityY, 110, com.flapdisplayplus.music.config.Config.AUDIO_QUALITY);
        flow.skip(8);

        // 保存 / 登录按钮
        int btnY = flow.y();
        this.addRenderableWidget(FdpButton.create(left, btnY, 140, 20,
                Component.literal("保存 Cookie"), b -> FdpWidgets.save()));
        this.addRenderableWidget(FdpButton.create(left + 160, btnY, 140, 20,
                Component.literal("打开登录界面"),
                b -> Minecraft.getInstance().setScreen(new LoginScreen())));
        flow.skip(28);

        // 登录状态
        String cookieNow = com.flapdisplayplus.music.config.Config.NETEASE_COOKIE.get();
        boolean hasCookie = cookieNow != null && !cookieNow.trim().isEmpty();
        flow.colored(hasCookie ? "当前状态：已登录（Cookie 长度 " + cookieNow.trim().length() + "）"
                        : "当前状态：未登录（使用匿名 API）",
                hasCookie ? FdpWidgets.TEXT_OK : 0xFFC8C8C8);

        this.addRenderableWidget(FdpButton.create(left, flow.y(), 140, 20,
                Component.literal("退出登录"), b -> {
                    com.flapdisplayplus.music.config.Config.NETEASE_COOKIE.set("");
                    MusicNetIntegration.applyCookie("");
                    FdpWidgets.save();
                    Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, parent, Page.NETEASE));
                }));
        flow.skip(32);

        flow.h1("【制作网易云唱片】");
        flow.p("在网易云 App/网页复制歌曲分享链接，形如：");
        flow.mono("music.163.com/song?id=数字&uct2=...");
        flow.p("粘贴到刻录机输入框即可，模组自动提取 id= 后的");
        flow.p("那一串数字制作唱片（无需手动解析）。");
        flow.dim("Cookie 仅用于解锁 VIP 歌曲/高音质，非必填。");
        backButton();
    }

    // ===================== 搜索设置 =====================
    private void buildSearch() {
        int cx = this.width / 2;
        int left = cx - 150;

        flow.h1("结果列表");
        int modeY = flow.y();
        flow.skip(4);
        addEnumToggle(left, modeY, 110, com.flapdisplayplus.music.config.Config.SEARCH_LIST_MODE);
        flow.skip(10);

        // 每页数量（翻页模式生效）
        // 【2026-09-27 修复排版】此前 statText 宽 120 且文字居中，"每页 N 首"压到 -5 按钮。
        // 现在三段各归其位：-5（left..+50）｜数值文字（+56 居中，宽 60）｜+5（+120..+180）。
        int sizeY = flow.y();
        this.addRenderableWidget(FdpButton.create(left, sizeY, 50, 20,
                Component.literal("-5"), b -> {
                    int v = Math.max(5, com.flapdisplayplus.music.config.Config.SEARCH_PAGE_SIZE.get() - 5);
                    com.flapdisplayplus.music.config.Config.SEARCH_PAGE_SIZE.set(v);
                    FdpWidgets.save();
                }));
        this.addRenderableWidget(FdpWidgets.statText(left + 56, sizeY + 4, 60,
                () -> "每页 " + com.flapdisplayplus.music.config.Config.SEARCH_PAGE_SIZE.get() + " 首"));
        this.addRenderableWidget(FdpButton.create(left + 120, sizeY, 50, 20,
                Component.literal("+5"), b -> {
                    int v = Math.min(50, com.flapdisplayplus.music.config.Config.SEARCH_PAGE_SIZE.get() + 5);
                    com.flapdisplayplus.music.config.Config.SEARCH_PAGE_SIZE.set(v);
                    FdpWidgets.save();
                }));
        flow.skip(30);

        flow.h1("【搜索结果制作唱片说明】");
        flow.p("· 网易云：点结果把分享链接回填到刻录机，");
        flow.p("  原版刻录机提取其中数字即可直接制作唱片。");
        flow.p("· QQ 音乐：点结果回填 qqmusic: 内部标识，");
        flow.p("  需先在「QQ 登录」扫码登录才能播放。");
        flow.dim("· 最稳妥做法：网易云直接复制分享链接粘贴到");
        flow.dim("  刻录机，模组取 id 制作唱片。");
        backButton();
    }

    // ===================== 通用控件 =====================

    /**
     * 「标签 + 开关」行：标签在左、按钮在右，同一行显示。
     *
     * 实现：先把标签写入 TextFlow（它会停在 when 的 y），再把按钮放在同一 y 的右侧。
     * 注意 TextFlow 的 add 会推进光标，所以按钮的 y 要在写入标签**之前**取。
     */
    private void addToggle(int left, int y, int btnW, ModConfigSpec.BooleanValue val) {
        addToggle(left, y, btnW, val, "开", "关");
    }

    private void addToggle(int left, int y, int btnW, ModConfigSpec.BooleanValue val,
                           String onText, String offText) {
        flow.skip(y - flow.y());                       // 对齐到指定行
        int labelY = flow.y();
        flow.colored(toggleLabel(val.getPath()), FdpWidgets.TEXT);
        this.addRenderableWidget(FdpButton.create(left + 150 - btnW, labelY, btnW, 20,
                Component.literal(val.get() ? onText : offText),
                b -> {
                    val.set(!val.get());
                    FdpWidgets.save();
                    b.setMessage(Component.literal(val.get() ? onText : offText));
                }));
    }

    /** 「标签 + 枚举循环」行 */
    private <T extends Enum<T>> void addEnumToggle(int left, int y, int btnW,
                                                   ModConfigSpec.EnumValue<T> val) {
        flow.skip(y - flow.y());
        int labelY = flow.y();
        flow.colored(toggleLabel(val.getPath()), FdpWidgets.TEXT);
        this.addRenderableWidget(FdpWidgets.cycle(left + 150 - btnW, labelY, btnW, 20, val,
                this::enumLabel, null));
    }

    /**
     * 布尔开关的标签：由调用方显式指定，避免依赖配置键推断出的名字不达意。
     * 保留这个重载供简单场景使用。
     */
    private void addToggle(int left, int y, int btnW, ModConfigSpec.BooleanValue val,
                           String label) {
        flow.skip(y - flow.y());
        int labelY = flow.y();
        flow.colored(label, FdpWidgets.TEXT);
        this.addRenderableWidget(FdpWidgets.toggle(left + 150 - btnW, labelY, btnW, 20, val));
    }

    /** 整数档位循环（清晰度/帧率） */
    private void addIntCycle(int left, int y, int w, ModConfigSpec.IntValue val,
                             int[] options, java.util.function.IntFunction<String> fmt) {
        this.addRenderableWidget(FdpWidgets.cycleList(left, y, w, 20, val, options,
                fmt::apply, null));
    }

    /** 路径输入行（文本配置项）：输入框在上、说明在下，共用 TextFlow 光标 */
    private void addPathRow(int left, int y, String label, ModConfigSpec.ConfigValue<String> val,
                            int boxW) {
        flow.skip(y - flow.y());
        int rowY = flow.y();
        EditBox box = new EditBox(this.font, left, rowY, boxW, 20, Component.literal(""));
        box.setMaxLength(1024);
        box.setValue(val.get() == null ? "" : val.get());
        box.setResponder(s -> {
            val.set(s.trim());
            FdpWidgets.save();
        });
        this.addRenderableWidget(box);
        flow.skip(26);                                  // 给输入框让出高度
        flow.dim(label + "（留空 = 自动查找）");
    }

    /** 配置路径末段 → 中文标签，如 "media.videoSound" → "视频播放声音" */
    private String toggleLabel(List<String> path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String last = path.get(path.size() - 1);
        return LABELS.getOrDefault(last, last);
    }

    /** 配置项 → 中文标签（比把配置键直接给玩家看友好得多） */
    private static final java.util.Map<String, String> LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("videoSound", "视频播放声音"),
            java.util.Map.entry("show_lyric_when_paused", "暂停时显示歌词"),
            java.util.Map.entry("show_pause_time", "暂停时显示时间"),
            java.util.Map.entry("pause_symbol_enabled", "显示播放/暂停符号"),
            java.util.Map.entry("redstone_mode", "红石模式"),
            java.util.Map.entry("pause_resume", "暂停后续播"),
            java.util.Map.entry("audio_quality", "歌曲音质"),
            java.util.Map.entry("search_list_mode", "结果列表模式"),
            java.util.Map.entry("search_page_size", "每页显示数量")
    );

    /** 枚举值的中文显示名 */
    private String enumLabel(Object e) {
        if (e instanceof com.flapdisplayplus.music.config.Config.RedstoneMode m) {
            return m == com.flapdisplayplus.music.config.Config.RedstoneMode.EDGE_TOGGLE ? "边沿触发" : "持续模式";
        }
        if (e instanceof com.flapdisplayplus.music.config.Config.AudioQuality q) {
            return switch (q) {
                case STANDARD -> "标准";
                case HIGHER -> "较高";
                case EXHIGH -> "极高";
                case LOSSLESS -> "无损";
                case HIRES -> "Hi-Res";
            };
        }
        if (e instanceof com.flapdisplayplus.music.config.Config.SearchListMode m) {
            return m == com.flapdisplayplus.music.config.Config.SearchListMode.SCROLL ? "滚动" : "翻页";
        }
        return String.valueOf(e);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        FdpWidgets.title(graphics, this.title.getString(), this.width / 2, 20);
        int left = this.width / 2 - 150;
        flow.render(graphics, this.font, left);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }
}
