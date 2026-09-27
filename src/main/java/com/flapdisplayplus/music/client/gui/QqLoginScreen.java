/*
 * QqLoginScreen.java
 *
 * QQ 音乐登录界面（客户端）。
 * - 已登录：显示登录态（musicid）+「注销并退出登录」按钮，不再展示二维码。
 * - 未登录：展示二维码并轮询扫码状态；可点击「刷新二维码」重新获取。
 * 登录态由 QqCredentialManager 持久化，重新进入本界面会读取并据此切换两种状态。
 * 借用 NetMusicCanNeedQQ 的登录服务（BSD-3-Clause，原作者 Yincmewy）。
 *
 * 【2026-08-28 重构】按钮换用 FdpButton，与主界面/配置界面统一木质风格；
 * parent 为 null（由指令直接打开）时安全回退到关闭界面。
 */
package com.flapdisplayplus.music.client.gui;

import com.flapdisplayplus.client.FdpButton;
import com.flapdisplayplus.client.FdpWidgets;
import com.flapdisplayplus.music.qq.QqCredentialManager;
import com.flapdisplayplus.music.qq.QqLoginService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public class QqLoginScreen extends Screen {
    private static final int COLOR_OK = 0xFF8FBF6A;
    private static final int COLOR_ERR = FdpWidgets.TEXT_ERROR;
    private static final int COLOR_NORMAL = FdpWidgets.TEXT;

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("NetMusicDisplay");

    private final Screen parent;
    private final QrCodeRenderer qr = new QrCodeRenderer();
    /** true = 即使已有登录态也强制走扫码流程（「扫码登录其他账号」入口） */
    private final boolean forceQr;
    private QqLoginService.LoginState state = QqLoginService.LoginState.IDLE;
    private String statusText = "";
    private boolean polling = false;
    private boolean closed = false;
    private boolean loggedIn = false;

    public QqLoginScreen(Screen parent) {
        this(parent, false);
    }

    public QqLoginScreen(Screen parent, boolean forceQr) {
        super(Component.literal("QQ音乐设置"));
        this.parent = parent;
        this.forceQr = forceQr;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        // 返回按钮始终存在
        this.addRenderableWidget(FdpButton.create(cx - 50, this.height - 30, 100, 20,
                Component.literal("返回"), b -> this.onClose()));

        // 已登录：展示登录态 + 换号扫码 / 注销，不再展示二维码
        if (QqCredentialManager.hasValidCredential() && !forceQr) {
            loggedIn = true;
            statusText = "已登录（musicid=" + QqCredentialManager.getMusicId() + "）";
            this.addRenderableWidget(FdpButton.create(cx - 90, this.height / 2 - 2, 180, 20,
                    Component.literal("扫码登录其他账号"), b ->
                            Minecraft.getInstance().setScreen(new QqLoginScreen(parent, true))));
            this.addRenderableWidget(FdpButton.create(cx - 90, this.height / 2 + 26, 180, 20,
                    Component.literal("注销并退出登录"), b -> doLogout()));
        } else {
            loggedIn = false;
            // 未登录：提供刷新二维码入口
            this.addRenderableWidget(FdpButton.create(cx - 60, this.height - 56, 120, 20,
                    Component.literal("刷新二维码"), b -> startLogin()));
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
        FdpWidgets.title(graphics, this.title.getString(), cx, 20);

        // 已登录：只画登录态面板（文字与下方两个按钮的 y 对齐成组）
        if (loggedIn) {
            graphics.drawCenteredString(this.font, "QQ 音乐已登录", cx, this.height / 2 - 44, COLOR_OK);
            graphics.drawCenteredString(this.font, statusText, cx, this.height / 2 - 26, 0xFFC8C8C8);
            graphics.drawCenteredString(this.font, "登录后可刻录/播放 VIP 歌曲",
                    cx, this.height / 2 + 54, FdpWidgets.TEXT_DIM);
            return;
        }

        // 未登录：画二维码（浅色底框，保证二维码在深色背景上可扫）
        int qrSize = 200;
        int qrX = cx - qrSize / 2;
        int qrY = 50;
        graphics.fill(qrX - 6, qrY - 6, qrX + qrSize + 6, qrY + qrSize + 6, 0xFFFFFFFF);
        if (qr.isLoaded()) {
            qr.render(graphics, qrX, qrY, qrSize);
        } else if (state == QqLoginService.LoginState.FAILED) {
            graphics.drawCenteredString(this.font, "二维码不可用", cx, qrY + qrSize / 2, COLOR_ERR);
        } else {
            graphics.drawCenteredString(this.font, "加载二维码中...", cx, qrY + qrSize / 2, FdpWidgets.TEXT_DIM);
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
            // parent 可能为 null（由指令直接打开），此时关闭到游戏内
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }
}
