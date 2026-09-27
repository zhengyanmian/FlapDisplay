/*
 * McefBridge.java
 *
 * MCEF（Minecraft Chromium Embedded Framework）的隔离桥接层。
 *
 * 【软依赖铁律】本类只在「MCEF 已加载」被确认后才会被 JVM 加载
 * （唯一入口 WebScreenManager，所有方法调用前都有 ModList.isLoaded("mcef") 守卫），
 * 因此本类里可以放心 import com.cinemamod.mcef.*；未装 MCEF 时这些类不存在也不会崩。
 * 反过来，MCEF 类型绝不能出现在 WebScreenManager / WebScreen 的签名里。
 *
 * 【像素链路】（全部经 javap 对 MCEF 2.1.6-1.21.1 实际字节码核实）：
 *   - MCEF.createBrowser = new MCEFBrowser(MCEF.getClient(), url, transparent)
 *     + setCloseAllowed() + createImmediately() (+ resize(w,h))；
 *   - MCEFBrowser extends CefBrowserOsr，onPaint 由 CEF 消息循环回调
 *     （MCEF 的 CefRenderUpdateMixin 在渲染线程泵 DoMessageLoopWork），
 *     内部先 super 上传到 MCEF 自己的 GL 纹理——我们重写 onPaint 搭车截获像素；
 *   - 截获的 BGRA 帧转成 NativeImage 的 ABGR int，写入自建 DynamicTexture，
 *     由 tick() 在渲染线程 upload——之后与图片/视频共用同一条 Mixin 渲染管线。
 *
 * 【输入链路】（照抄 MCEF 官方 ExampleScreen 的调用约定）：
 *   sendMouseMove(x,y) / sendMousePress(x,y,btn) / sendMouseWheel(x,y,yDelta,0)
 *   sendKeyPress(keyCode, scanCode, modifiers) / sendKeyTyped(chr, modifiers)，
 *   每次键盘事件后 setFocus(true)。
 */
package com.flapdisplayplus.client.web;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.MCEFClient;
import com.flapdisplayplus.FlapDisplayPlus;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.cef.browser.CefBrowser;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class McefBridge {

    /** 离屏浏览器渲染尺寸（16:9，翻牌显示带多为宽条，长边 1024 与视频默认档一致） */
    static final int BROWSER_W = 1024;
    static final int BROWSER_H = 576;

    /** "web://" 前缀：网页媒体路径约定（服务端原样透传，客户端据此分流） */
    public static final String WEB_PREFIX = "web://";

    private static final Map<String, Entry> BROWSERS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private McefBridge() {
    }

    /** 一个网页 = 一个离屏浏览器 + 一张自建 DynamicTexture */
    private static final class Entry {
        volatile FdpWebBrowser browser;
        final DynamicTexture texture;
        final ResourceLocation loc;
        /** 最近一帧（已转换为 NativeImage ABGR 格式），由 onPaint 写入、tick 消费 */
        int[] pending;
        volatile boolean hasPending;
        volatile int pendingW = BROWSER_W;
        volatile int pendingH = BROWSER_H;

        Entry(DynamicTexture texture, ResourceLocation loc) {
            this.texture = texture;
            this.loc = loc;
        }
    }

    /**
     * MCEFBrowser 子类：重写 onPaint 截获每帧像素（super 会先走 MCEF 自己的纹理上传，
     * 我们在它之后把同一份 ByteBuffer 复制进自建纹理的待上传缓冲）。
     */
    private static final class FdpWebBrowser extends MCEFBrowser {
        private final Entry owner;

        FdpWebBrowser(MCEFClient client, String url, boolean transparent, Entry owner) {
            super(client, url, transparent);
            this.owner = owner;
        }

        @Override
        public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                            ByteBuffer buffer, int width, int height) {
            super.onPaint(browser, popup, dirtyRects, buffer, width, height);
            if (popup || owner == null) {
                return;
            }
            try {
                if (width != BROWSER_W || height != BROWSER_H) {
                    // 视图尺寸在 create 时已锁定为 BROWSER_W×BROWSER_H，理论上不会变；
                    // 若真变了（极端竞态），丢这一帧等下一帧，避免越界写 pending。
                    FlapDisplayPlus.LOGGER.debug("[Web] 忽略尺寸不符的帧 {}x{}", width, height);
                    return;
                }
                copyFrame(owner, buffer, width, height);
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.debug("[Web] onPaint 处理失败: {}", t.toString());
            }
        }
    }

    /**
     * CEF BGRA → NativeImage ABGR 通道转换并缓存。
     * CEF 缓冲按小端 int 读出 = A<<24 | R<<16 | G<<8 | B；
     * NativeImage.setPixelRGBA 期望 = A<<24 | B<<16 | G<<8 | R（见 MediaManager.argbToRgba 注释）。
     * 即交换最低与第三字节（B ↔ R），G 与 A 原样保留。
     */
    private static void copyFrame(Entry e, ByteBuffer buffer, int w, int h) {
        int[] px = e.pending;
        if (px == null || px.length != w * h) {
            px = e.pending = new int[w * h];
        }
        ByteBuffer dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        dup.asIntBuffer().get(px, 0, w * h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >>> 16) & 0xFF);
        }
        e.pendingW = w;
        e.pendingH = h;
        e.hasPending = true;
    }

    // ===== 查询 =====

    static ResourceLocation getFrame(String webPath) {
        Entry e = BROWSERS.get(webPath);
        if (e == null) {
            e = create(webPath);
        }
        return e == null ? null : e.loc;
    }

    static int getWidth(String webPath) {
        Entry e = BROWSERS.get(webPath);
        return e == null ? 0 : BROWSER_W;
    }

    static int getHeight(String webPath) {
        Entry e = BROWSERS.get(webPath);
        return e == null ? 0 : BROWSER_H;
    }

    /** 创建浏览器 + 自建纹理（渲染线程调用；MCEF 未初始化完成时返回 null 下刻重试） */
    private static Entry create(String webPath) {
        if (!MCEF.isInitialized()) {
            return null; // CEF 运行时解压/初始化中（首次安装 MCEF 会下载 80-200MB）
        }
        String url = webPath.substring(WEB_PREFIX.length());
        DynamicTexture tex;
        ResourceLocation loc;
        try {
            NativeImage img = new NativeImage(BROWSER_W, BROWSER_H, true);
            tex = new DynamicTexture(img);
            loc = Minecraft.getInstance().getTextureManager()
                    .register("flapdisplayplus/web_" + SEQ.incrementAndGet(), tex);
            tex.setFilter(true, false);
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[Web] 创建网页纹理失败: {}", t.toString());
            return null;
        }
        Entry e = new Entry(tex, loc);
        try {
            FdpWebBrowser b = new FdpWebBrowser(MCEF.getClient(), url, false, e);
            b.setCloseAllowed();
            b.createImmediately();
            b.useBrowserControls(false);
            b.resize(BROWSER_W, BROWSER_H);
            e.browser = b;
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[Web] 浏览器创建失败 {}: {}", url, t.toString());
            try {
                Minecraft.getInstance().getTextureManager().release(loc);
            } catch (Throwable ignored) {
            }
            return null;
        }
        BROWSERS.put(webPath, e);
        FlapDisplayPlus.LOGGER.info("[Web] 打开网页: {} → {}", webPath, url);
        return e;
    }

    // ===== 帧上传（渲染线程） =====

    /** 把 onPaint 截获的待上传帧写入 DynamicTexture 并 upload（MediaManager.tick 每刻调用） */
    static void tick() {
        if (BROWSERS.isEmpty()) {
            return;
        }
        for (Entry e : BROWSERS.values()) {
            if (!e.hasPending) {
                continue;
            }
            e.hasPending = false;
            try {
                NativeImage img = e.texture.getPixels();
                if (img.getWidth() != e.pendingW || img.getHeight() != e.pendingH) {
                    continue;
                }
                int w = e.pendingW;
                int h = e.pendingH;
                int[] px = e.pending;
                for (int y = 0; y < h; y++) {
                    int row = y * w;
                    for (int x = 0; x < w; x++) {
                        img.setPixelRGBA(x, y, px[row + x]);
                    }
                }
                e.texture.upload();
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.debug("[Web] 帧上传失败: {}", t.toString());
            }
        }
    }

    // ===== 生命周期 =====

    static void close(String webPath) {
        Entry e = BROWSERS.remove(webPath);
        if (e == null) {
            return;
        }
        try {
            if (e.browser != null) {
                e.browser.close();
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.debug("[Web] 浏览器关闭异常: {}", t.toString());
        }
        try {
            Minecraft.getInstance().getTextureManager().release(e.loc);
        } catch (Throwable ignored) {
        }
        FlapDisplayPlus.LOGGER.info("[Web] 关闭网页: {}", webPath);
    }

    static void closeAll() {
        for (String k : BROWSERS.keySet().toArray(new String[0])) {
            close(k);
        }
    }

    // ===== 输入转发（约定照抄 MCEF ExampleScreen 字节码） =====

    static void mouseMove(String webPath, int x, int y) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendMouseMove(x, y);
        }
    }

    static void mousePress(String webPath, int x, int y, int button) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendMousePress(x, y, button);
        }
    }

    static void mouseRelease(String webPath, int x, int y, int button) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendMouseRelease(x, y, button);
        }
    }

    static void mouseWheel(String webPath, int x, int y, double yDelta) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendMouseWheel(x, y, yDelta, 0);
        }
    }

    static void keyPress(String webPath, int keyCode, int scanCode, int modifiers) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendKeyPress(keyCode, scanCode, modifiers);
            e.browser.setFocus(true);
        }
    }

    static void keyRelease(String webPath, int keyCode, int scanCode, int modifiers) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendKeyRelease(keyCode, scanCode, modifiers);
        }
    }

    static void keyTyped(String webPath, char chr, int modifiers) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendKeyTyped(chr, modifiers);
            e.browser.setFocus(true);
        }
    }
}
