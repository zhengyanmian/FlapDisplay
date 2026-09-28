/*
 * WebScreenManager.java
 *
 * 网页媒体的对外门面（MCEF 零引用）。
 *
 * 所有 MCEF 相关代码都隔离在 McefBridge 里；本类是「MCEF 已加载」判断的唯一入口：
 * 每个 McefBridge 调用前都有 isMcefLoaded() 守卫，未装 MCEF 时 McefBridge
 * 类永远不会被 JVM 加载（其类体引用的 com.cinemamod.mcef.* 缺失也无妨）。
 *
 * 网页媒体路径约定：mediaPath = "web://" + 网址。
 * 服务端把该字符串当作普通媒体路径透传（FlapDisplayMediaSource 不校验内容），
 * 客户端在 MediaManager 各入口按前缀分流到本管理器。
 */
package com.flapdisplayplus.client.web;

import com.flapdisplayplus.FlapDisplayPlus;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

public final class WebScreenManager {

    private WebScreenManager() {
    }

    /** 是否是网页媒体路径（"web://" 前缀） */
    public static boolean isWebPath(String path) {
        return path != null && path.startsWith(McefBridge.WEB_PREFIX);
    }

    /** MCEF 可用性（缓存 ModList 查询结果） */
    private static Boolean mcefLoaded;

    public static boolean isMcefLoaded() {
        if (mcefLoaded == null) {
            try {
                mcefLoaded = net.neoforged.fml.ModList.get().isLoaded("mcef");
            } catch (Throwable t) {
                mcefLoaded = false;
            }
            FlapDisplayPlus.LOGGER.info("[Web] MCEF 可用性: {}", mcefLoaded);
        }
        return mcefLoaded;
    }

    // ===== 帧纹理（MediaManager / 渲染 Mixin 调用） =====

    /** 获取网页当前帧纹理（首次调用创建离屏浏览器；未就绪返回 null 下刻重试） */
    public static ResourceLocation getFrame(String webPath) {
        if (!isMcefLoaded()) {
            return null;
        }
        return McefBridge.getFrame(webPath);
    }

    public static int getWidth(String webPath) {
        return isMcefLoaded() ? McefBridge.getWidth(webPath) : 0;
    }

    public static int getHeight(String webPath) {
        return isMcefLoaded() ? McefBridge.getHeight(webPath) : 0;
    }

    /** 每刻上传新帧（渲染线程，由 MediaManager.tick 调用） */
    public static void tick() {
        if (isMcefLoaded()) {
            McefBridge.tick();
        }
    }

    /**
     * 游戏菜单（ESC）暂停/恢复（MediaManager.tick 边沿触发）。
     * 暂停时：冻结网页纹理（翻牌画面停在最后一帧）+ 全部网页静音 + JS 暂停页面媒体；
     * 恢复时反向。单人/联机/服务器按 ESC 打开的是同一个 PauseScreen，行为一致。
     */
    public static void setGamePaused(boolean paused) {
        if (isMcefLoaded()) {
            McefBridge.setGamePaused(paused);
        }
    }

    // ===== 生命周期 =====

    /**
     * 翻牌渲染心跳（MediaManager.getVideoFrame 每帧调用）：
     * 该网页此刻正显示在翻牌上 → 允许出声（v1.0.6：上屏出声，不再只有预览出声）。
     */
    public static void notifyDisplayed(String webPath) {
        if (isMcefLoaded()) {
            McefBridge.displayHeartbeat(webPath);
        }
    }

    /** 关闭网页并释放浏览器与纹理（媒体被清除 / 不再被任何翻牌引用时） */
    public static void stop(String webPath) {
        if (isMcefLoaded()) {
            McefBridge.close(webPath);
        }
    }

    /** 退出世界时全部关闭 */
    public static void stopAll() {
        if (isMcefLoaded()) {
            McefBridge.closeAll();
        }
    }

    // ===== 音频焦点（只有预览中的网页出声） =====

    /** 预览界面打开：该网页成为唯一出声者 */
    public static void notifyPreviewOpen(String webPath) {
        if (isMcefLoaded()) {
            McefBridge.previewOpened(webPath);
        }
    }

    /** 预览界面关闭/被替换：立刻停声 */
    public static void notifyPreviewClosed(String webPath) {
        if (isMcefLoaded()) {
            McefBridge.previewClosed(webPath);
        }
    }

    // ===== 预览交互 =====

    /** 打开网页预览界面（全屏 Screen：可点击 / 打字 / 滚动；ESC 返回打开前的界面） */
    public static void openPreview(String webPath) {
        if (!isMcefLoaded()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof WebScreen ws && ws.webPath.equals(webPath)) {
            return; // 已在该网页的预览中
        }
        // 记住打开前的界面（若已在另一个网页的预览里，沿用更早的父界面）
        net.minecraft.client.gui.screens.Screen parent =
                mc.screen instanceof WebScreen ws2 ? ws2.parent : mc.screen;
        mc.setScreen(new WebScreen(webPath, parent));
    }

    // ===== 输入转发（WebScreen 调用；坐标为浏览器像素坐标） =====

    public static void sendMouseMove(String webPath, int x, int y) {
        if (isMcefLoaded()) {
            McefBridge.mouseMove(webPath, x, y);
        }
    }

    public static void sendMousePress(String webPath, int x, int y, int button) {
        if (isMcefLoaded()) {
            McefBridge.mousePress(webPath, x, y, button);
        }
    }

    public static void sendMouseRelease(String webPath, int x, int y, int button) {
        if (isMcefLoaded()) {
            McefBridge.mouseRelease(webPath, x, y, button);
        }
    }

    public static void sendMouseWheel(String webPath, int x, int y, double yDelta) {
        if (isMcefLoaded()) {
            McefBridge.mouseWheel(webPath, x, y, yDelta);
        }
    }

    public static void sendKeyPress(String webPath, int keyCode, int scanCode, int modifiers) {
        if (isMcefLoaded()) {
            McefBridge.keyPress(webPath, keyCode, scanCode, modifiers);
        }
    }

    public static void sendKeyRelease(String webPath, int keyCode, int scanCode, int modifiers) {
        if (isMcefLoaded()) {
            McefBridge.keyRelease(webPath, keyCode, scanCode, modifiers);
        }
    }

    public static void sendKeyTyped(String webPath, char chr, int modifiers) {
        if (isMcefLoaded()) {
            McefBridge.keyTyped(webPath, chr, modifiers);
        }
    }

    /**
     * 把一段文本注入页面焦点元素（中文 / IME 输入）。
     * 非 ASCII 字符走原生键路径会被 Windows 原生层静默丢弃，必须用 JS 注入。
     */
    public static void sendTextInput(String webPath, String text) {
        if (isMcefLoaded()) {
            McefBridge.sendTextInput(webPath, text);
        }
    }
}
