/*
 * WebScreen.java
 *
 * 网页预览 / 交互界面（全屏）。
 *
 * 翻牌显示器是世界内方块，没有键鼠焦点（Minecraft 不存在世界内指针事件），
 * 所以「看」在翻牌上、「点」在这里：本界面把网页纹理全屏铺开，
 * 并把玩家的键鼠事件换算成浏览器像素坐标转发给 CEF（经 WebScreenManager → McefBridge）。
 * 界面关闭后浏览器实例保留，翻牌继续镜像同一画面。
 *
 * 本类不 import 任何 MCEF 类型（软依赖隔离），未装 MCEF 时显示提示并只能 ESC 退出。
 */
package com.flapdisplayplus.client.web;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class WebScreen extends Screen {

    /** 正在预览的网页媒体路径（"web://" + 网址） */
    final String webPath;

    /** 打开预览前的界面（媒体选择界面），ESC 返回它而不是退出全部 */
    final Screen parent;

    public WebScreen(String webPath, Screen parent) {
        super(Component.literal("网页预览"));
        this.webPath = webPath;
        this.parent = parent;
    }

    public WebScreen(String webPath) {
        this(webPath, null);
    }

    /**
     * 1.21 的 Screen.renderBackground 会对整帧套「菜单背景模糊」着色器，
     * 把网页纹理一起糊掉 —— 网页预览必须跳过任何背景处理。
     */
    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics graphics,
                                 int mouseX, int mouseY, float partialTick) {
        // 不做任何模糊/变暗：网页本身就是全屏内容
    }

    /** ESC 关闭预览 → 回到媒体选择界面（而非退出全部），选择状态得以保留 */
    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    protected void init() {
        super.init();
        // 音频焦点：预览开着才出声（v1.0.2：修「没选择网页时还在放声音」）
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.notifyPreviewOpen(webPath);
        }
    }

    /** 被任何界面替换（含 ESC 关闭、打开登录框、切别的网页）时立刻停声 */
    @Override
    public void removed() {
        super.removed();
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.notifyPreviewClosed(webPath);
        }
    }

    /** 纹理绘制区域（全屏拉伸铺满——与浏览器 resize 尺寸的换算保持线性一致） */
    private int drawX() {
        return 0;
    }

    private int drawY() {
        return 0;
    }

    private int drawW() {
        return this.width;
    }

    private int drawH() {
        return this.height;
    }

    /** GUI 坐标 → 浏览器像素坐标（全屏拉伸下的线性换算） */
    private int toBrowserX(double mx) {
        int bx = (int) ((mx - drawX()) * McefBridge.BROWSER_W / Math.max(1, drawW()));
        return Math.max(0, Math.min(McefBridge.BROWSER_W - 1, bx));
    }

    private int toBrowserY(double my) {
        int by = (int) ((my - drawY()) * McefBridge.BROWSER_H / Math.max(1, drawH()));
        return Math.max(0, Math.min(McefBridge.BROWSER_H - 1, by));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 深色底（纹理未就绪时不至于白屏/透明）
        graphics.fill(drawX(), drawY(), drawX() + drawW(), drawY() + drawH(), 0xFF101010);

        if (!WebScreenManager.isMcefLoaded()) {
            graphics.drawCenteredString(this.font, "网页功能需要安装 MCEF 前置（Modrinth 搜索 mcef）",
                    this.width / 2, this.height / 2 - 10, 0xFFFFAA55);
            graphics.drawCenteredString(this.font, "按 ESC 关闭",
                    this.width / 2, this.height / 2 + 8, 0xFF888888);
            return;
        }

        net.minecraft.resources.ResourceLocation frame = WebScreenManager.getFrame(webPath);
        if (frame != null) {
            graphics.blit(frame, drawX(), drawY(), drawW(), drawH(),
                    0, 0, McefBridge.BROWSER_W, McefBridge.BROWSER_H,
                    McefBridge.BROWSER_W, McefBridge.BROWSER_H);
        } else {
            graphics.drawCenteredString(this.font, "浏览器加载中…（首次安装 MCEF 需下载 CEF 运行时）",
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
        }

        // 顶部地址提示 + 底部操作提示
        String url = webPath.length() > McefBridge.WEB_PREFIX.length()
                ? webPath.substring(McefBridge.WEB_PREFIX.length()) : "";
        if (url.length() > 90) {
            url = url.substring(0, 88) + "…";
        }
        graphics.drawString(this.font, url, 4, 4, 0xFF888888);
        graphics.drawCenteredString(this.font, "ESC 关闭预览（翻牌继续显示） · 可直接点击/打字/滚动网页",
                this.width / 2, this.height - 14, 0xFF666666);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ===== 输入转发 =====

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendMousePress(webPath, toBrowserX(mx), toBrowserY(my), button);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendMouseRelease(webPath, toBrowserX(mx), toBrowserY(my), button);
        }
        return true;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendMouseMove(webPath, toBrowserX(mx), toBrowserY(my));
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double xDelta, double yDelta) {
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendMouseWheel(webPath, toBrowserX(mx), toBrowserY(my), yDelta);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 留给关界面（256 = GLFW_KEY_ESCAPE），其余按键全部转发给浏览器
        if (keyCode == 256) {
            this.onClose();
            return true;
        }
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendKeyPress(webPath, keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendKeyRelease(webPath, keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendKeyTyped(webPath, chr, modifiers);
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 网页预览不暂停单人游戏
    }
}
