package com.flapdisplayplus.music.client.gui;

import com.flapdisplayplus.client.FdpButton;
import com.flapdisplayplus.client.FdpWidgets;
import com.flapdisplayplus.music.MusicNetIntegration;
import com.mojang.blaze3d.platform.NativeImage;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.config.Config;
import com.flapdisplayplus.music.netease.NeteaseApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 网易云登录界面（游戏内）。
 *
 * 布局：左边二维码（扫码登录），右边登录表单（邮箱/手机验证码切换）。
 * 二维码在游戏内直接渲染，无需切出游戏打开浏览器。
 *
 * 【2026-08-28 重构】按钮换用 FdpButton 统一木质风格；标题取 title 字符串而非硬编码。
 */
public class LoginScreen extends Screen {

    private static final int QR_SIZE = 128;

    private String unikey;
    private ResourceLocation qrLocation;
    private DynamicTexture qrTexture;

    private EditBox accountInput;
    private EditBox passwordInput;
    private Button sendCaptchaButton;
    private boolean phoneMode = false; // false=邮箱, true=手机验证码

    private int timer = 0;
    private String tip = "";

    private int qrLeft;
    private int qrTop;
    private int formLeft;
    private int formTop;

    public LoginScreen() {
        super(Component.literal("网易云登录"));
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;

        qrLeft = centerX - 160;
        qrTop = centerY - QR_SIZE / 2;
        formLeft = centerX + 20;
        formTop = centerY - 45;

        // 账号输入框（邮箱或手机号）
        accountInput = new EditBox(font, formLeft, formTop, 170, 20, Component.literal("账号"));
        accountInput.setMaxLength(64);
        addRenderableWidget(accountInput);

        // 密码/验证码输入框
        passwordInput = new EditBox(font, formLeft, formTop + 28, 170, 20, Component.literal("密码"));
        passwordInput.setMaxLength(64);
        addRenderableWidget(passwordInput);

        // 发送验证码按钮（仅手机模式显示，放在验证码输入框右侧）
        sendCaptchaButton = FdpButton.create(formLeft + 90, formTop + 28, 80, 20,
                Component.literal("发送验证码"), btn -> sendCaptcha());
        sendCaptchaButton.visible = false;
        addRenderableWidget(sendCaptchaButton);

        // 登录按钮
        addRenderableWidget(FdpButton.create(formLeft, formTop + 56, 170, 20,
                Component.literal("登 录"), btn -> doLogin()));

        // 切换登录方式
        addRenderableWidget(FdpButton.create(formLeft, formTop + 84, 170, 20,
                Component.literal("切换手机/邮箱"), btn -> switchMode()));

        // 刷新二维码
        addRenderableWidget(FdpButton.create(qrLeft, qrTop + QR_SIZE + 10, QR_SIZE, 20,
                Component.literal("刷新二维码"), btn -> refreshQR()));

        // 关闭按钮
        addRenderableWidget(FdpButton.create(width / 2 - 50, formTop + 140, 100, 20,
                Component.literal("关闭"), btn -> onClose()));

        refreshQR();
    }

    @Override
    public void tick() {
        timer = (timer + 1) % 40;
        // 每 40 tick（约 2 秒）轮询一次扫码状态
        if (timer == 0 && unikey != null) {
            checkQRStatus();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        // 标题
        FdpWidgets.title(graphics, "网易云音乐登录", width / 2, formTop - 25);

        // 二维码（浅色底框保证在深色背景上可扫）
        if (qrLocation != null) {
            graphics.fill(qrLeft - 4, qrTop - 4, qrLeft + QR_SIZE + 4, qrTop + QR_SIZE + 4, 0xFFFFFFFF);
            graphics.blit(qrLocation, qrLeft, qrTop, 0, 0, QR_SIZE, QR_SIZE, QR_SIZE, QR_SIZE);
        } else {
            graphics.drawCenteredString(font, "二维码加载中...",
                    qrLeft + QR_SIZE / 2, qrTop + QR_SIZE / 2, FdpWidgets.TEXT_DIM);
        }

        // 提示文字（§ 颜色码样式在 tip 里已有；这里用统一主文字色）
        if (!tip.isEmpty()) {
            graphics.drawString(font, tip, formLeft, formTop + 165, FdpWidgets.TEXT);
        }
    }

    /** 切换邮箱/手机验证码模式 */
    private void switchMode() {
        phoneMode = !phoneMode;
        passwordInput.setValue("");
        tip = "";
        if (phoneMode) {
            sendCaptchaButton.visible = true;
            passwordInput.setWidth(85);
            passwordInput.setMessage(Component.literal("验证码"));
        } else {
            sendCaptchaButton.visible = false;
            passwordInput.setWidth(170);
            passwordInput.setMessage(Component.literal("密码"));
        }
    }

    /** 刷新二维码 */
    private void refreshQR() {
        unikey = null;
        tip = "正在获取二维码...";
        CompletableFuture.supplyAsync(() -> {
            try {
                return NeteaseApi.getQRKey();
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(key -> {
            Minecraft.getInstance().execute(() -> {
                if (key == null) {
                    tip = "§c获取二维码失败，请检查网络";
                    return;
                }
                unikey = key;
                try {
                    NativeImage image = QRCodeGenerator.generate(
                            "https://music.163.com/login?codekey=" + key, QR_SIZE);
                    closeOldTexture();
                    qrTexture = new DynamicTexture(image);
                    // 每次用唯一 location，避免重复打开时纹理冲突
                    qrLocation = new ResourceLocation(
                            FlapDisplayPlus.MODID, "qrlogin_" + System.nanoTime());
                    Minecraft.getInstance().getTextureManager().register(qrLocation, qrTexture);
                    tip = "请用手机网易云音乐 APP 扫码";
                } catch (Exception e) {
                    tip = "§c二维码生成失败";
                }
            });
        });
    }

    /** 轮询扫码状态 */
    private void checkQRStatus() {
        CompletableFuture.supplyAsync(() -> {
            try {
                return NeteaseApi.checkQRStatus(unikey);
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(resp -> {
            if (resp == null) return;
            int code = NeteaseApi.parseCode(resp);
            Minecraft.getInstance().execute(() -> {
                switch (code) {
                    case 800 -> { tip = "§c二维码已过期，请刷新"; unikey = null; }
                    case 802 -> tip = "§a已扫码，请在手机上确认";
                    case 803 -> {
                        String cookie = NeteaseApi.extractCookie(resp);
                        if (cookie != null && !cookie.isEmpty()) {
                            applyCookie(cookie);
                        } else {
                            tip = "§c登录成功但获取 Cookie 失败";
                        }
                    }
                }
            });
        });
    }

    /** 邮箱/手机验证码登录 */
    private void doLogin() {
        String account = accountInput.getValue().trim();
        String password = passwordInput.getValue().trim();
        if (account.isEmpty() || password.isEmpty()) {
            tip = "§c请输入账号和" + (phoneMode ? "验证码" : "密码");
            return;
        }
        tip = "登录中...";
        CompletableFuture.supplyAsync(() -> {
            try {
                return phoneMode
                        ? NeteaseApi.phoneCaptchaLogin(account, password)
                        : NeteaseApi.emailLogin(account, password);
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(resp -> {
            Minecraft.getInstance().execute(() -> {
                if (resp == null) {
                    tip = "§c登录失败，请检查网络";
                    return;
                }
                int code = NeteaseApi.parseCode(resp);
                if (code == 200) {
                    String cookie = NeteaseApi.extractCookie(resp);
                    if (cookie != null && !cookie.isEmpty()) {
                        applyCookie(cookie);
                    } else {
                        tip = "§c登录成功但获取 Cookie 失败";
                    }
                } else {
                    tip = "§c登录失败（code=" + code + "），请检查账号" + (phoneMode ? "和验证码" : "和密码");
                }
            });
        });
    }

    /** 发送手机验证码 */
    private void sendCaptcha() {
        String phone = accountInput.getValue().trim();
        if (phone.isEmpty()) {
            tip = "§c请先输入手机号";
            return;
        }
        tip = "正在发送验证码...";
        CompletableFuture.supplyAsync(() -> {
            try {
                return NeteaseApi.sendCaptcha(phone);
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(body -> {
            Minecraft.getInstance().execute(() -> {
                tip = body != null && body.contains("200") ? "§a验证码已发送" : "§c验证码发送失败";
            });
        });
    }

    /** 应用登录结果 */
    private void applyCookie(String cookie) {
        try {
            Config.NETEASE_COOKIE.set(cookie);
            MusicNetIntegration.applyCookie(cookie);
            Config.SPEC.save();
            tip = "§a登录成功！VIP 歌曲已解锁";
        } catch (Exception e) {
            tip = "§c应用 Cookie 失败：" + e.getMessage();
        }
    }

    private void closeOldTexture() {
        if (qrTexture != null) {
            try {
                qrTexture.close();
            } catch (Exception ignored) {
            }
            qrTexture = null;
        }
    }

    @Override
    public void onClose() {
        // 先关闭屏幕恢复鼠标状态，再清理纹理（清理失败不能影响屏幕关闭）
        super.onClose();
        closeOldTexture();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
