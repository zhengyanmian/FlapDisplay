/*
 * WebAuthScreen.java
 *
 * 网页 HTTP 认证登录框（401 Basic/Digest）。
 *
 * CEF 离屏渲染模式弹不出任何原生对话框——遇到 401 认证时页面会直接显示错误页，
 * 玩家根本没机会输入账号密码。这里用 MC 自己的界面替代：CEF 的认证回调
 * （CefAuthCallback）被 McefBridge 接住后转交本界面，用户提交后异步 Continue。
 *
 * 本类不 import 任何 MCEF/CEF 类型（软依赖隔离）：凭据通过 Consumer<String[]> 回传，
 * null = 用户取消。
 */
package com.flapdisplayplus.client.web;

import com.flapdisplayplus.client.FdpButton;
import com.flapdisplayplus.client.FdpWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class WebAuthScreen extends Screen {

    /** 显示给用户的认证说明（站点/代理 · host:port（域）） */
    private final String what;
    /** 提交/取消后返回的界面（通常是网页预览或媒体选择界面） */
    private final Screen returnTo;
    /** 凭据回传：[0]=用户名 [1]=密码；null=取消 */
    private final java.util.function.Consumer<String[]> onFinish;

    private EditBox userBox;
    private EditBox passBox;

    public WebAuthScreen(String what, Screen returnTo, java.util.function.Consumer<String[]> onFinish) {
        super(Component.literal("网页登录"));
        this.what = what;
        this.returnTo = returnTo;
        this.onFinish = onFinish;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        userBox = new EditBox(this.font, cx - 100, cy - 26, 200, 18, Component.literal("用户名"));
        userBox.setMaxLength(120);
        passBox = new EditBox(this.font, cx - 100, cy + 6, 200, 18, Component.literal("密码"));
        passBox.setMaxLength(120);
        this.addRenderableWidget(userBox);
        this.addRenderableWidget(passBox);
        this.addRenderableWidget(FdpButton.create(cx - 104, cy + 36, 100, 20,
                Component.literal("登录"), b -> submit()));
        this.addRenderableWidget(FdpButton.create(cx + 4, cy + 36, 100, 20,
                Component.literal("取消"), b -> cancel()));
        this.setInitialFocus(userBox);
    }

    private void submit() {
        String u = userBox == null ? "" : userBox.getValue();
        String p = passBox == null ? "" : passBox.getValue();
        finish(new String[]{u, p});
    }

    private void cancel() {
        finish(null);
    }

    private void finish(String[] cred) {
        try {
            this.minecraft.setScreen(returnTo);
        } catch (Throwable ignored) {
        }
        try {
            onFinish.accept(cred);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明遮罩（不用 1.21 的模糊着色器）
        graphics.fill(0, 0, this.width, this.height, 0xA0101010);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        // 面板
        int px = cx - 130;
        int py = cy - 60;
        graphics.fill(px, py, px + 260, py + 130, 0xFF0E0000);
        FdpWidgets.outline(graphics, px, py, 260, 130, FdpWidgets.BORDER);
        graphics.drawCenteredString(this.font, "需要登录", cx, py + 8, FdpWidgets.TEXT_HL);
        String w = what == null ? "" : what;
        if (w.length() > 34) {
            w = w.substring(0, 32) + "…";
        }
        graphics.drawCenteredString(this.font, w, cx, py + 22, FdpWidgets.TEXT_DIM);
        graphics.drawString(this.font, "用户名", px + 8, cy - 36, FdpWidgets.TEXT);
        graphics.drawString(this.font, "密码（明文显示）", px + 8, cy - 4, FdpWidgets.TEXT);
        graphics.drawCenteredString(this.font, "回车登录 · ESC 取消", cx, py + 116, FdpWidgets.TEXT_DIM);

        userBox.render(graphics, mouseX, mouseY, partialTick);
        passBox.render(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            cancel();
            return true;
        }
        if (keyCode == 257) { // Enter
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // 兜底：任何关闭路径都必须应答回调，否则 CEF 请求悬挂
        finish(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
