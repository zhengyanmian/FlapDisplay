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

import com.flapdisplayplus.FlapDisplayPlus;
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
    public void renderBackground(net.minecraft.client.gui.GuiGraphics graphics) {
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
        FlapDisplayPlus.LOGGER.info("[Web] 预览界面已打开: {}", webPath);
        firstKeyLogged = false;
        nonAsciiCount = 0;
        // 窗口尺寸变化会重建界面：输入框模式下把控件重新挂上，别让玩家打到一半丢掉
        if (inputActive && inputBox != null) {
            inputBox.setX(boxX());
            inputBox.setY(boxY());
            this.addRenderableWidget(inputBox);
            this.setFocused(inputBox);
            inputBox.setFocused(true);
        }
        // 音频焦点：预览开着才出声（v1.0.2：修「没选择网页时还在放声音」）
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.notifyPreviewOpen(webPath);
        }
    }

    /** 被任何界面替换（含 ESC 关闭、打开登录框、切别的网页）时立刻停声 */
    @Override
    public void removed() {
        super.removed();
        FlapDisplayPlus.LOGGER.info("[Web] 预览界面已关闭: {}", webPath);
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

    /** MCEF 下载/初始化是否已判定失败（软依赖守卫：仅在 MCEF 已加载时才触碰该类） */
    private static boolean mcefInitFailed() {
        try {
            return com.cinemamod.mcef.internal.MCEFDownloadListener.INSTANCE.isFailed();
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== 关闭按钮（v1.0.5：鼠标兜底退出，键盘焦点丢失时也能退出预览） =====

    /** 关闭按钮区域（右上角），宽高单位为 GUI 像素 */
    private int closeX() {
        return this.width - 96;
    }

    private int closeY() {
        return 4;
    }

    private boolean hitClose(double mx, double my) {
        return mx >= closeX() && mx <= closeX() + 92 && my >= closeY() && my <= closeY() + 16;
    }

    /** 首个到达本界面的按键（诊断日志：键盘事件有没有到达游戏窗口） */
    private boolean firstKeyLogged;

    // ===== 中文输入（v1.0.9）=====

    /**
     * 【为什么还要一个输入框】MC 自己没有任何 IME Java 代码（javap 全 jar 无 IME 类），
     * 中文完全靠 Mojang 定制的 GLFW 在原生层把「已提交的字符」喂给 charTyped。
     * 这条通路在我们这里若不通（不同环境/输入法差异），网页里就永远打不出中文。
     * 而聊天框那套（EditBox）用的是同一条通路但久经考验 —— 于是给一个保底入口：
     * 在 MC 原生输入框里打字（输入法候选框正常出现），回车后整段文字注入网页焦点元素。
     */
    private boolean inputActive;
    private net.minecraft.client.gui.components.EditBox inputBox;
    /** 已收到并转发的非 ASCII 字符数（诊断日志节流） */
    private int nonAsciiCount;

    /** 「输入文字…」按钮区域（左上角，URL 提示下方） */
    private int inputBtnX() {
        return 4;
    }

    private int inputBtnY() {
        return 18;
    }

    private boolean hitInputButton(double mx, double my) {
        return mx >= inputBtnX() && mx <= inputBtnX() + 72 && my >= inputBtnY() && my <= inputBtnY() + 14;
    }

    private int boxX() {
        return this.width / 2 - 120;
    }

    private int boxY() {
        return this.height - 30;
    }

    private void openTextInput() {
        if (inputBox == null) {
            inputBox = new net.minecraft.client.gui.components.EditBox(
                    this.font, boxX(), boxY(), 240, 18, Component.literal("网页文字输入"));
            inputBox.setMaxLength(1000);
        }
        inputBox.setX(boxX());
        inputBox.setY(boxY());
        inputBox.setValue("");
        this.addRenderableWidget(inputBox);
        this.setFocused(inputBox);
        inputBox.setFocused(true);
        inputActive = true;
        FlapDisplayPlus.LOGGER.info("[Web] 文字输入框已打开（输入法可用，回车注入网页）");
    }

    private void closeTextInput() {
        inputActive = false;
        if (inputBox != null) {
            this.removeWidget(inputBox);
        }
    }

    private void submitTextInput() {
        String text = inputBox == null ? "" : inputBox.getValue();
        closeTextInput();
        if (!text.isEmpty() && WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendTextInput(webPath, text);
            FlapDisplayPlus.LOGGER.info("[Web] 文字已注入网页（{} 字符）", text.length());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 深色底（纹理未就绪时不至于白屏/透明）
        graphics.fill(drawX(), drawY(), drawX() + drawW(), drawY() + drawH(), 0xFF101010);

        // 右上角关闭按钮（鼠标永远有效，键盘失焦时兜底退出）
        boolean hover = hitClose(mouseX, mouseY);
        graphics.fill(closeX(), closeY(), closeX() + 92, closeY() + 16,
                hover ? 0xFF6A3030 : 0xB0303030);
        graphics.fill(closeX(), closeY(), closeX() + 92, closeY() + 1, 0xFF555555);
        graphics.drawCenteredString(this.font, "✕ 关闭 (ESC)",
                closeX() + 46, closeY() + 4, hover ? 0xFFFFAAAA : 0xFFCCCCCC);

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
        } else if (mcefInitFailed()) {
            graphics.drawCenteredString(this.font, "MCEF 初始化失败（多为网络原因，无法连接校验服务器）",
                    this.width / 2, this.height / 2 - 10, 0xFFFF5555);
            graphics.drawCenteredString(this.font, "请重启游戏重试；若反复出现请检查网络/代理后重启",
                    this.width / 2, this.height / 2 + 8, 0xFF888888);
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

        // 「输入文字…」按钮：中文输入保底通路（MC 原生输入框 + IME）
        boolean btnHover = hitInputButton(mouseX, mouseY);
        graphics.fill(inputBtnX(), inputBtnY(), inputBtnX() + 72, inputBtnY() + 14,
                btnHover || inputActive ? 0xB0557A55 : 0xB0303030);
        graphics.fill(inputBtnX(), inputBtnY(), inputBtnX() + 72, inputBtnY() + 1, 0xFF555555);
        graphics.drawCenteredString(this.font, "输入文字…",
                inputBtnX() + 36, inputBtnY() + 3, btnHover ? 0xFFFFFFFF : 0xFFCCCCCC);

        String hint = inputActive
                ? "在下方输入框打字（可用输入法），回车注入网页焦点元素 · ESC 取消输入"
                : "ESC 关闭预览（翻牌继续显示） · 可直接点击/打字/滚动网页 · 中文打不进去时用左上角「输入文字…」";
        graphics.drawCenteredString(this.font, hint, this.width / 2, this.height - 14, 0xFF666666);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ===== 输入转发 =====

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 关闭按钮优先于网页点击（右上角 92x16 不再转发给浏览器）
        if (hitClose(mx, my)) {
            FlapDisplayPlus.LOGGER.info("[Web] 关闭按钮点击退出预览: {}", webPath);
            this.onClose();
            return true;
        }
        if (hitInputButton(mx, my)) {
            if (inputActive) {
                closeTextInput();
            } else {
                openTextInput();
            }
            return true;
        }
        if (inputActive) {
            // 点在输入框内 → 交给 EditBox 定位光标；点在别处 → 收起输入框并按正常流程点网页
            boolean inBox = mx >= boxX() && mx <= boxX() + 240 && my >= boxY() && my <= boxY() + 18;
            if (inBox) {
                return super.mouseClicked(mx, my, button);
            }
            closeTextInput();
        }
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
    public boolean mouseScrolled(double mx, double my, double yDelta) {
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendMouseWheel(webPath, toBrowserX(mx), toBrowserY(my), yDelta);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC：输入框模式下先收起输入框，否则关闭预览（256 = GLFW_KEY_ESCAPE）
        if (keyCode == 256) {
            if (inputActive) {
                closeTextInput();
                return true;
            }
            FlapDisplayPlus.LOGGER.info("[Web] ESC 关闭预览: {}", webPath);
            this.onClose();
            return true;
        }
        if (inputActive) {
            // 回车（257）/ 小键盘回车（335）→ 把输入框内容注入网页
            if (keyCode == 257 || keyCode == 335) {
                submitTextInput();
                return true;
            }
            // 其余按键交给输入框（光标移动/退格/输入法候选选择等）
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (!firstKeyLogged) {
            firstKeyLogged = true;
            FlapDisplayPlus.LOGGER.info("[Web] 预览收到首个按键 keyCode={}（键盘事件已到达游戏）", keyCode);
        }
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendKeyPress(webPath, keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (inputActive) {
            return super.keyReleased(keyCode, scanCode, modifiers);
        }
        if (WebScreenManager.isMcefLoaded()) {
            WebScreenManager.sendKeyRelease(webPath, keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        // 输入框模式：字符交给 EditBox（它自己会处理输入法提交的文本）
        if (inputActive) {
            return super.charTyped(chr, modifiers);
        }
        if (WebScreenManager.isMcefLoaded()) {
            if (chr < 0x20) {
                // 控制字符（回车/退格等）由 keyPressed 路径处理，char 通道直接忽略
                return true;
            }
            if (chr < 0x80) {
                WebScreenManager.sendKeyTyped(webPath, chr, modifiers);
            } else {
                // 【中文/IME】非 ASCII 字符走原生键路径会被 Windows 原生层静默丢弃
                // （字符由 scancode→MapVirtualKey 反推，汉字没有虚拟键码），改走 JS 文本注入。
                // 诊断：前几个字符打日志，用来判断「MC 到底有没有把 IME 文本交给我们」
                if (nonAsciiCount < 5) {
                    FlapDisplayPlus.LOGGER.info("[Web] 收到非 ASCII 字符 U+{}（{} 注入网页）",
                            String.format("%04X", (int) chr),
                            chr < 0x10000 ? "直接" : "补充平面");
                }
                nonAsciiCount++;
                WebScreenManager.sendTextInput(webPath, String.valueOf(chr));
            }
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 网页预览不暂停单人游戏
    }
}
