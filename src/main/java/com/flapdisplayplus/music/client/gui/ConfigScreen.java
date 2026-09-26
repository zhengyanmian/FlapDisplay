/*
 * ConfigScreen.java
 *
 * 自定义配置界面（客户端），采用「主页 Hub + 各功能子页」的分页结构：
 * - 主页列出所有功能入口按钮；
 * - 每个功能单独一个子页（显示 / 红石 / 播放 / 网易云 / 搜索），含对应的设置项与中文使用说明。
 * - QQ 登录复用 QqLoginScreen。
 *
 * 保存：经由 ModConfigSpec，调用 Config.SPEC.save() 落盘（NeoForge 21.1）。
 */
package com.flapdisplayplus.music.client.gui;

import com.flapdisplayplus.music.MusicNetIntegration;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.config.Config;
import com.flapdisplayplus.music.qq.QqCredentialManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ConfigScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    private final Screen parent;
    private final ModContainer modContainer;
    private final Page page;

    /** 说明文字行（在 render 中绘制） */
    private final List<InfoLine> infoLines = new ArrayList<>();

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
        MUSIC_HUB("网络音乐机配置"),
        DISPLAY("显示设置"),
        REDSTONE("红石设置"),
        PLAYBACK("播放设置"),
        NETEASE("网易云设置"),
        SEARCH("搜索设置");

        final String title;
        Page(String t) { this.title = t; }
    }

    private record InfoLine(int x, int y, int color, Component text) {}

    @Override
    protected void init() {
        infoLines.clear();
        switch (page) {
            case HUB -> buildHub();
            case MUSIC_HUB -> buildMusicHub();
            case DISPLAY -> buildDisplay();
            case REDSTONE -> buildRedstone();
            case PLAYBACK -> buildPlayback();
            case NETEASE -> buildNetease();
            case SEARCH -> buildSearch();
        }
    }

    // ===================== 主页 Hub（FlapDisplayPlus 主菜单）=====================
    private void buildHub() {
        int cx = this.width / 2;
        int y = 48;
        int gap = 32;
        int w = 260;

        // 网络音乐机配置入口（软联动：未安装时点击提示）
        boolean netMusic = MusicNetIntegration.isNetMusicLoaded();
        this.addRenderableWidget(Button.builder(
                        Component.literal("🎵 网络音乐机配置" + (netMusic ? "" : "（未安装）")),
                        btn -> {
                            if (netMusic) {
                                Minecraft.getInstance().setScreen(
                                        new ConfigScreen(modContainer, this, Page.MUSIC_HUB));
                            } else if (this.minecraft != null && this.minecraft.player != null) {
                                this.minecraft.player.displayClientMessage(
                                        Component.literal("未安装网络音乐机（Net Music）模组，无法配置音乐联动"), false);
                            }
                        })
                .bounds(cx - w / 2, y, w, 20).build());
        y += gap;

        this.addRenderableWidget(Button.builder(Component.literal("返回"),
                        btn -> this.onClose()).bounds(cx - 50, this.height - 30, 100, 20).build());
    }

    // ===================== 网络音乐机配置子菜单 =====================
    private void buildMusicHub() {
        int cx = this.width / 2;

        // ===== 软联动守卫：未安装网络音乐机 → 显示安装提示 =====
        if (!MusicNetIntegration.isNetMusicLoaded()) {
            infoLines.add(new InfoLine(cx - 130, 72, 0xFFFF5555,
                    Component.literal("未检测到网络音乐机（Net Music）模组")));
            infoLines.add(new InfoLine(cx - 130, 96, 0xFFFFAA55,
                    Component.literal("音乐联动功能当前不可用")));
            infoLines.add(new InfoLine(cx - 130, 120, 0xFFAAAAAA,
                    Component.literal("请安装 Net Music 后重启游戏，即可联动显示")));
            infoLines.add(new InfoLine(cx - 130, 142, 0xFFAAAAAA,
                    Component.literal("封面、歌名与歌词（翻牌/CD 播放机）")));
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
        this.addRenderableWidget(Button.builder(Component.literal("QQ 登录（开发中）"),
                        btn -> {
                            if (this.minecraft != null && this.minecraft.player != null) {
                                this.minecraft.player.displayClientMessage(
                                        Component.literal("QQ音乐功能开发中，敬请期待"), false);
                            }
                        })
                .bounds(cx - w / 2, y, w, 20).build());
        y += gap;
        backToHubButton();
    }

    /** 返回设置主页（HUB）按钮 */
    private void backToHubButton() {
        this.addRenderableWidget(Button.builder(Component.literal("← 返回设置主页"),
                        btn -> Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, parent, Page.HUB)))
                .bounds(this.width / 2 - 80, this.height - 30, 160, 20).build());
    }

    private void addPageButton(String label, Page target, int cx, int y) {
        this.addRenderableWidget(Button.builder(Component.literal(label),
                        btn -> Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, this, target)))
                .bounds(cx - 130, y, 260, 20).build());
    }

    private void backButton() {
        this.addRenderableWidget(Button.builder(Component.literal("← 返回音乐配置"),
                        btn -> Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, parent, Page.MUSIC_HUB)))
                .bounds(this.width / 2 - 80, this.height - 30, 160, 20).build());
    }

    // ===================== 显示设置 =====================
    private void buildDisplay() {
        int cx = this.width / 2;
        int y = 48;
        addBoolRow("暂停时显示歌词", Config.SHOW_LYRIC_WHEN_PAUSED, cx, y); y += 30;
        addBoolRow("暂停时显示时间", Config.SHOW_PAUSE_TIME, cx, y); y += 30;
        addBoolRow("显示播放/暂停符号", Config.PAUSE_SYMBOL_ENABLED, cx, y); y += 40;
        info("这些选项控制翻牌显示器在歌曲暂停时显示的内容。", cx - 150, y, 0xC8C8C8);
        backButton();
    }

    // ===================== 红石设置 =====================
    private void buildRedstone() {
        int cx = this.width / 2;
        int y = 48;
        addEnumRow("红石模式", Config.REDSTONE_MODE, cx, y); y += 40;
        info("边沿触发：红石上升沿切换播放/暂停（原版行为）。", cx - 150, y, 0xC8C8C8); y += 18;
        info("持续模式：有信号=播放，无信号=暂停（需配合暂停续播）。", cx - 150, y, 0xC8C8C8);
        backButton();
    }

    // ===================== 播放设置 =====================
    private void buildPlayback() {
        int cx = this.width / 2;
        int y = 48;
        addBoolRow("暂停后续播", Config.PAUSE_RESUME, cx, y); y += 40;
        info("开启后，暂停再播放会从暂停位置继续，而非从头开始。", cx - 150, y, 0xC8C8C8);
        backButton();
    }

    // ===================== 网易云设置 =====================
    private void buildNetease() {
        int cx = this.width / 2;
        int y = 44;
        info("网易云 Cookie（可选，留空=默认音质）", cx - 150, y, 0xE0E0E0); y += 22;
        EditBox cookieBox = new EditBox(this.font, cx - 150, y, 300, 20, Component.literal(""));
        cookieBox.setMaxLength(3000);
        cookieBox.setValue(Config.NETEASE_COOKIE.get());
        cookieBox.setResponder(s -> Config.NETEASE_COOKIE.set(s.trim()));
        this.addRenderableWidget(cookieBox);
        y += 30;
        addEnumRow("歌曲音质", Config.AUDIO_QUALITY, cx, y); y += 36;
        this.addRenderableWidget(Button.builder(Component.literal("保存 Cookie"),
                        btn -> saveConfig()).bounds(cx - 150, y, 140, 20).build());
        // 打开网易云登录界面（原 /netmusicdisplay gui 指令的网易云入口，现集成进配置界面）
        this.addRenderableWidget(Button.builder(Component.literal("打开登录界面"),
                        btn -> Minecraft.getInstance().setScreen(new LoginScreen()))
                .bounds(cx + 10, y, 140, 20).build());
        y += 30;
        // 当前登录状态 + 退出登录
        String cookieNow = Config.NETEASE_COOKIE.get();
        boolean hasCookie = cookieNow != null && !cookieNow.trim().isEmpty();
        info(hasCookie ? "当前状态：已配置 Cookie（长度 " + cookieNow.trim().length() + "）"
                : "当前状态：未登录（使用匿名 API）",
                cx - 150, y, hasCookie ? 0x55FF55 : 0xC8C8C8);
        y += 22;
        this.addRenderableWidget(Button.builder(Component.literal("退出登录"),
                        btn -> {
                            Config.NETEASE_COOKIE.set("");
                            MusicNetIntegration.applyCookie("");
                            Config.SPEC.save();
                            Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, parent, Page.NETEASE));
                        }).bounds(cx - 150, y, 140, 20).build());
        y += 30;
        info("【制作网易云唱片】", cx - 150, y, 0xFFD060); y += 18;
        info("在网易云 App/网页复制歌曲分享链接，形如：", cx - 150, y, 0xC8C8C8); y += 16;
        info("music.163.com/song?id=数字&uct2=...", cx - 150, y, 0x9CDCFE); y += 16;
        info("粘贴到刻录机输入框即可，模组自动提取 id= 后的", cx - 150, y, 0xC8C8C8); y += 16;
        info("那一串数字制作唱片（无需手动解析）。", cx - 150, y, 0xC8C8C8); y += 16;
        info("Cookie 仅用于解锁 VIP 歌曲/高音质，非必填。", cx - 150, y, 0xC8C8C8);
        backButton();
    }

    // ===================== 搜索设置 =====================
    private void buildSearch() {
        int cx = this.width / 2;
        int y = 44;
        addEnumRow("搜索结果列表模式", Config.SEARCH_LIST_MODE, cx, y); y += 30;
        info("每页显示数量（翻页模式生效）：当前 " + Config.SEARCH_PAGE_SIZE.get() + " 首", cx - 150, y, 0xE0E0E0); y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("-5"),
                btn -> changePageSize(-5)).bounds(cx - 80, y, 60, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+5"),
                btn -> changePageSize(5)).bounds(cx + 20, y, 60, 20).build());
        y += 36;
        info("【搜索结果制作唱片说明】", cx - 150, y, 0xFFD060); y += 18;
        info("· 网易云：点击搜索结果会把分享链接（含 id=数", cx - 150, y, 0xC8C8C8); y += 16;
        info("  字）回填到刻录机，原版刻录机提取该数字即可", cx - 150, y, 0xC8C8C8); y += 16;
        info("  直接制作唱片，无需其他操作。", cx - 150, y, 0xC8C8C8); y += 16;
        info("· QQ 音乐：点击结果回填 qqmusic: 内部标识，需", cx - 150, y, 0xC8C8C8); y += 16;
        info("  先在「QQ 登录」扫码登录，否则只能搜索、", cx - 150, y, 0xC8C8C8); y += 16;
        info("  无法播放出声（也做不了能出声的唱片）。", cx - 150, y, 0xC8C8C8); y += 16;
        info("· 最稳妥做法：网易云直接复制分享链接粘贴到", cx - 150, y, 0xC8C8C8); y += 16;
        info("  刻录机，模组取 id 制作唱片。", cx - 150, y, 0xC8C8C8);
        backButton();
    }

    // ===================== 通用控件 =====================
    /** 开关行：标签用文字显示（按钮仅作开关），描述不放按钮里 */
    private void addBoolRow(String label, ModConfigSpec.BooleanValue val, int cx, int y) {
        info(label, cx - 150, y + 5, 0xE0E0E0);
        Button toggle = Button.builder(Component.literal(boolLabel(val)), b -> {
            val.set(!val.get());
            Config.SPEC.save();
            b.setMessage(Component.literal(boolLabel(val)));
        }).bounds(cx + 40, y, 70, 20).build();
        this.addRenderableWidget(toggle);
    }

    /** 枚举行：标签用文字，值按钮显示中文名，描述不放按钮里 */
    private <T extends Enum<T>> void addEnumRow(String label, ModConfigSpec.EnumValue<T> val, int cx, int y) {
        info(label, cx - 150, y + 5, 0xE0E0E0);
        Button btn = Button.builder(Component.literal(enumLabel(val.get())), b -> {
            T[] vs = val.get().getDeclaringClass().getEnumConstants();
            List<T> list = Arrays.asList(vs);
            T next = list.get((list.indexOf(val.get()) + 1) % vs.length);
            val.set(next);
            Config.SPEC.save();
            b.setMessage(Component.literal(enumLabel(next)));
        }).bounds(cx + 40, y, 100, 20).build();
        this.addRenderableWidget(btn);
    }

    /** 枚举值的中文显示名 */
    private String enumLabel(Enum<?> e) {
        if (e instanceof Config.RedstoneMode m) {
            return m == Config.RedstoneMode.EDGE_TOGGLE ? "边沿触发" : "持续模式";
        }
        if (e instanceof Config.AudioQuality q) {
            if (q == Config.AudioQuality.STANDARD) return "标准";
            if (q == Config.AudioQuality.HIGHER) return "较高";
            if (q == Config.AudioQuality.EXHIGH) return "极高";
            if (q == Config.AudioQuality.LOSSLESS) return "无损";
            if (q == Config.AudioQuality.HIRES) return "Hi-Res";
        }
        if (e instanceof Config.SearchListMode m) {
            return m == Config.SearchListMode.SCROLL ? "滚动" : "翻页";
        }
        return e.name();
    }

    private void changePageSize(int delta) {
        int v = Math.max(5, Math.min(50, Config.SEARCH_PAGE_SIZE.get() + delta));
        if (v != Config.SEARCH_PAGE_SIZE.get()) {
            Config.SEARCH_PAGE_SIZE.set(v);
            Config.SPEC.save();
            Minecraft.getInstance().setScreen(new ConfigScreen(modContainer, parent, Page.SEARCH));
        }
    }

    private void info(String text, int x, int y, int color) {
        infoLines.add(new InfoLine(x, y, color, Component.literal(text)));
    }

    private String boolLabel(ModConfigSpec.BooleanValue val) {
        return val.get() ? "开" : "关";
    }

    private void saveConfig() {
        try {
            Config.SPEC.save();
        } catch (Exception e) {
            LOGGER.error("[配置] 保存失败", e);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        for (InfoLine line : infoLines) {
            graphics.drawString(this.font, line.text(), line.x(), line.y(), line.color());
        }
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
