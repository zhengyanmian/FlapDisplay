/*
 * QqLoginScreen.java
 *
 * QQ 音乐登录界面（客户端）。
 * - 已登录：显示登录态（musicid）+「注销并退出登录」按钮，不再展示二维码。
 * - 未登录：展示二维码并轮询扫码状态；可点击「刷新二维码」重新获取。
 * 登录态由 QqCredentialManager 持久化，重新进入本界面会读取并据此切换两种状态。
 * 借用 NetMusicCanNeedQQ 的登录服务（BSD-3-Clause，原作者 Yincmewy）。
 */
package com.flapdisplayplus.music.client.gui;

import com.flapdisplayplus.music.qq.QqCredentialManager;
import com.flapdisplayplus.music.qq.QqLoginService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public class QqLoginScreen extends Screen {
    private static final int COLOR_OK = 0x55FF55;
    private static final int COLOR_ERR = 0xFF5555;
    private static final int COLOR_NORMAL = 0xFFFFFF;

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("NetMusicDisplay");

    private final Screen parent;
    private final QrCodeRenderer qr = new QrCodeRenderer();
    private QqLoginService.LoginState state = QqLoginService.LoginState.IDLE;
    private String statusText = "";
    private boolean polling = false;
    private boolean closed = false;
    private boolean loggedIn = false;

    public QqLoginScreen(Screen parent) {
        super(Component.literal("QQ音乐登录"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        // 返回按钮始终存在
        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(cx - 50, this.height - 30, 100, 20).build());

        // 已登录：展示登录态 + 注销，不再展示二维码
        if (QqCredentialManager.hasValidCredential()) {
            loggedIn = true;
            statusText = "已登录（musicid=" + QqCredentialManager.getMusicId() + "）";
            this.addRenderableWidget(Button.builder(Component.literal("注销并退出登录"), b -> doLogout())
                    .bounds(cx - 90, this.height / 2 + 20, 180, 20).build());
        } else {
            loggedIn = false;
            // 未登录：提供刷新二维码入口
            this.addRenderableWidget(Button.builder(Component.literal("刷新二维码"), b -> startLogin())
                    .bounds(cx - 60, this.height - 56, 120, 20).build());
            startLogin();
        }
    }

    /** 注销：清除本地凭证后重新进入本界面（此时应显示二维码） */
    private void doLogout() {
        QqCredentialManager.clear();
        Minecraft.getInstance().setScreen(new QqLoginScreen(parent));
    }

    private void startLogin() {
        this.state = QqLoginService.LoginState.FETCHING_QR;
        this.statusText = "正在获取二维码...";
        QqLoginService.fetchQrCode().whenComplete((png, ex) -> {
            if (closed) return;
            if (ex != null || png == null) {
                this.state = QqLoginService.LoginState.FAILED;
                this.statusText = "获取二维码失败，请返回重试";
                LOGGER.error("[QQ登录] 获取二维码失败", ex);
                return;
            }
            Minecraft.getInstance().execute(() -> {
                if (closed) return;
                if (qr.load(png)) {
                    this.state = QqLoginService.LoginState.WAITING_SCAN;
                    this.statusText = "请使用 QQ 扫一扫登录";
                    startPolling();
                } else {
                    this.state = QqLoginService.LoginState.FAILED;
                    this.statusText = "二维码解析失败";
                }
            });
        });
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        CompletableFuture.runAsync(() -> {
            while (!closed && polling) {
                try {
                    QqLoginService.LoginState s = QqLoginService.pollLogin().get();
                    QqLoginService.LoginState captured = s;
                    Minecraft.getInstance().execute(() -> updateState(captured));
                    if (captured == QqLoginService.LoginState.SUCCESS
                            || captured == QqLoginService.LoginState.FAILED
                            || captured == QqLoginService.LoginState.QR_EXPIRED) {
                        polling = false;
                        return;
                    }
                    Thread.sleep(1500);
                } catch (Exception e) {
                    LOGGER.error("[QQ登录] 轮询异常", e);
                    Minecraft.getInstance().execute(() -> {
                        this.state = QqLoginService.LoginState.FAILED;
                        this.statusText = "登录轮询异常";
                    });
                    polling = false;
                    return;
                }
            }
        });
    }

    private void updateState(QqLoginService.LoginState s) {
        this.state = s;
        switch (s) {
            case WAITING_SCAN -> this.statusText = "请使用 QQ 扫一扫登录";
            case AUTHORIZING, LOGGING_IN -> this.statusText = "正在登录...";
            case SUCCESS -> {
                this.statusText = "登录成功！musicid=" + QqCredentialManager.getMusicId();
                qr.release();
            }
            case QR_EXPIRED -> this.statusText = "二维码已过期，请点击刷新二维码";
            case FAILED -> this.statusText = "登录失败，请返回重试";
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, 20, 0xFFFFFF);

        // 已登录：只画登录态面板
        if (loggedIn) {
            graphics.drawCenteredString(this.font, "QQ 音乐已登录", cx, this.height / 2 - 30, COLOR_OK);
            graphics.drawCenteredString(this.font, statusText, cx, this.height / 2 - 8, 0xC8C8C8);
            graphics.drawCenteredString(this.font, "点击「注销并退出登录」可清除登录状态", cx, this.height / 2 + 48, 0xAAAAAA);
            return;
        }

        // 未登录：画二维码
        int qrSize = 200;
        int qrX = cx - qrSize / 2;
        int qrY = 50;
        if (qr.isLoaded()) {
            qr.render(graphics, qrX, qrY, qrSize);
        } else if (state == QqLoginService.LoginState.FAILED) {
            graphics.drawCenteredString(this.font, "二维码不可用", cx, qrY + qrSize / 2, COLOR_ERR);
        } else {
            graphics.drawCenteredString(this.font, "加载二维码中...", cx, qrY + qrSize / 2, 0xAAAAAA);
        }

        int color = (state == QqLoginService.LoginState.SUCCESS) ? COLOR_OK
                : (state == QqLoginService.LoginState.FAILED || state == QqLoginService.LoginState.QR_EXPIRED) ? COLOR_ERR
                : COLOR_NORMAL;
        graphics.drawCenteredString(this.font, this.statusText, cx, qrY + qrSize + 16, color);
    }

    @Override
    public void onClose() {
        this.closed = true;
        this.polling = false;
        this.qr.release();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }
}
