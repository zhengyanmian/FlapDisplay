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
    /** 全局 handler（弹窗拦截 + 音频路由）只注册一次 */
    private static volatile boolean handlersRegistered;

    private McefBridge() {
    }

    /**
     * 注册全局 CEF handler（幂等，首次建浏览器前调用）：
     * <ul>
     * <li>【弹窗拦截】B 站等站点的链接大量使用 target=_blank / window.open，CEF 默认会为
     * 弹窗新建离屏浏览器——画面画进我们丢弃的 popup 缓冲，玩家看来就是「点了没反应」。
     * onBeforePopup 里把目标 URL 改到原浏览器就地打开并取消弹窗。</li>
     * <li>【音频路由】OSR 模式不挂 CefAudioHandler 就完全没有声音。CEF 以浮点 PCM
     * 回调音频包，这里转 16-bit 交 Java Sound 播放（与视频播放器同一套 SourceDataLine 机制）。</li>
     * </ul>
     */
    private static void ensureHandlers() {
        if (handlersRegistered) {
            return;
        }
        synchronized (McefBridge.class) {
            if (handlersRegistered) {
                return;
            }
            try {
                org.cef.CefClient cc = MCEF.getClient().getHandle();
                cc.addLifeSpanHandler(new org.cef.handler.CefLifeSpanHandlerAdapter() {
                    @Override
                    public boolean onBeforePopup(org.cef.browser.CefBrowser browser,
                                                 org.cef.browser.CefFrame frame,
                                                 String targetUrl, String requestMethod) {
                        if (browser != null && targetUrl != null && !targetUrl.isEmpty()
                                && !targetUrl.startsWith("javascript:")
                                && !targetUrl.startsWith("about:blank")) {
                            try {
                                browser.loadURL(targetUrl);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true; // 一律取消弹窗创建
                    }
                });
                cc.addAudioHandler(new org.cef.handler.CefAudioHandler() {
                    @Override
                    public boolean getAudioParameters(org.cef.browser.CefBrowser browser,
                                                      org.cef.misc.CefAudioParameters parameters) {
                        return true; // 接受 CEF 提供的参数（通常 48kHz 立体声）
                    }

                    @Override
                    public void onAudioStreamStarted(org.cef.browser.CefBrowser browser,
                                                     org.cef.misc.CefAudioParameters parameters,
                                                     int channels) {
                        WebAudio.start(browser, parameters.sampleRate, channels);
                    }

                    @Override
                    public void onAudioStreamPacket(org.cef.browser.CefBrowser browser,
                                                    float[] data, int framesPerChannel, long pts) {
                        WebAudio.feed(browser, data, framesPerChannel);
                    }

                    @Override
                    public void onAudioStreamStopped(org.cef.browser.CefBrowser browser) {
                        WebAudio.stop(browser);
                    }

                    @Override
                    public void onAudioStreamError(org.cef.browser.CefBrowser browser, String error) {
                        FlapDisplayPlus.LOGGER.warn("[Web] 音频流错误: {}", error);
                        WebAudio.stop(browser);
                    }
                });
                handlersRegistered = true;
                FlapDisplayPlus.LOGGER.info("[Web] CEF 全局 handler 已注册（弹窗拦截 + 音频路由）");
            } catch (Throwable t) {
                // 注册失败不阻塞视频画面；音频/弹窗退化为基础行为
                FlapDisplayPlus.LOGGER.warn("[Web] CEF handler 注册失败（弹窗与声音可能异常）: {}", t.toString());
                handlersRegistered = true;
            }
        }
    }

    /**
     * CEF 浮点 PCM → Java Sound 播放（每浏览器一条 SourceDataLine）。
     * write 的天然背压防止缓冲无限增长；CEF 音频线程阻塞可接受。
     */
    private static final class WebAudio {
        private static final Map<org.cef.browser.CefBrowser, javax.sound.sampled.SourceDataLine> LINES =
                new ConcurrentHashMap<>();

        static void start(org.cef.browser.CefBrowser browser, int sampleRate, int channels) {
            stop(browser);
            try {
                if (channels <= 0 || channels > 2) {
                    channels = 2; // 只处理单声道/立体声，其它按立体声处理
                }
                javax.sound.sampled.AudioFormat fmt =
                        new javax.sound.sampled.AudioFormat(sampleRate, 16, channels, true, false);
                javax.sound.sampled.SourceDataLine line =
                        javax.sound.sampled.AudioSystem.getSourceDataLine(fmt);
                line.open(fmt, Math.max(8192, sampleRate * channels * 2 / 5)); // ~100ms 缓冲
                line.start();
                LINES.put(browser, line);
                FlapDisplayPlus.LOGGER.info("[Web] 音频开始: {}Hz x{}ch", sampleRate, channels);
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[Web] 音频线打开失败: {}", t.toString());
            }
        }

        static void feed(org.cef.browser.CefBrowser browser, float[] data, int framesPerChannel) {
            javax.sound.sampled.SourceDataLine line = LINES.get(browser);
            if (line == null || framesPerChannel <= 0) {
                return;
            }
            int channels = line.getFormat().getChannels();
            int samples = Math.min(data.length, framesPerChannel * channels);
            byte[] out = new byte[samples * 2];
            for (int i = 0; i < samples; i++) {
                float v = data[i];
                if (v > 1f) {
                    v = 1f;
                } else if (v < -1f) {
                    v = -1f;
                }
                int s = (int) (v * 32000f);
                out[2 * i] = (byte) s;
                out[2 * i + 1] = (byte) (s >> 8);
            }
            try {
                line.write(out, 0, out.length);
            } catch (Throwable ignored) {
            }
        }

        static void stop(org.cef.browser.CefBrowser browser) {
            javax.sound.sampled.SourceDataLine line = LINES.remove(browser);
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Throwable ignored) {
                }
            }
        }
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
            ensureHandlers();
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
                WebAudio.stop(e.browser);
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

    /**
     * GLFW 键码 → Windows 虚拟键码。
     * MCEFBrowser.sendKeyPress 把键码原样写进 CefKeyEvent（javap 实锤，无任何转换），
     * 而字母/数字的 GLFW 码恰好等于 Windows VK 码，所以打字一直正常；
     * 但回车(257≠13)、退格(259≠8)、方向键、F1-F12、小键盘、标点全都错位——
     * 这就是 B 站搜索框按回车没反应的根因。
     */
    private static int toWindowsVk(int glfw) {
        switch (glfw) {
            case 257: return 13;   // ENTER
            case 258: return 9;    // TAB
            case 259: return 8;    // BACKSPACE
            case 260: return 45;   // INSERT
            case 261: return 46;   // DELETE
            case 262: return 39;   // RIGHT
            case 263: return 37;   // LEFT
            case 264: return 40;   // DOWN
            case 265: return 38;   // UP
            case 266: return 33;   // PAGE_UP
            case 267: return 34;   // PAGE_DOWN
            case 268: return 36;   // HOME
            case 269: return 35;   // END
            case 270: return 20;   // CAPS_LOCK
            case 280: return 145;  // SCROLL_LOCK
            case 281: return 144;  // NUM_LOCK
            case 283: return 19;   // PAUSE
            case 340: case 344: return 16; // SHIFT（左右）
            case 341: case 345: return 17; // CTRL（左右）
            case 342: case 346: return 18; // ALT（左右）
            case 343: case 347: return 91; // SUPER（左右）
            case 348: return 93;   // MENU
            case 330: return 110;  // KP_DECIMAL
            case 331: return 111;  // KP_DIVIDE
            case 332: return 106;  // KP_MULTIPLY
            case 333: return 109;  // KP_SUBTRACT
            case 334: return 107;  // KP_ADD
            case 335: return 13;   // KP_ENTER
            case 44:  return 188;  // COMMA → VK_OEM_COMMA
            case 45:  return 189;  // MINUS → VK_OEM_MINUS
            case 46:  return 190;  // PERIOD → VK_OEM_PERIOD
            case 47:  return 191;  // SLASH → VK_OEM_2
            case 51:  return 186;  // SEMICOLON → VK_OEM_1
            case 52:  return 187;  // EQUAL → VK_OEM_PLUS
            case 91:  return 219;  // LEFT_BRACKET → VK_OEM_4
            case 92:  return 220;  // BACKSLASH → VK_OEM_5
            case 93:  return 221;  // RIGHT_BRACKET → VK_OEM_6
            case 96:  return 192;  // GRAVE → VK_OEM_3
            case 39:  return 222;  // APOSTROPHE → VK_OEM_7
            default:
                if (glfw >= 290 && glfw <= 301) {
                    return glfw - 290 + 112; // F1-F12 → VK_F1(0x70)..
                }
                if (glfw >= 320 && glfw <= 329) {
                    return glfw - 320 + 96;  // KP_0-KP_9 → VK_NUMPAD0(0x60)..
                }
                return glfw; // 字母/数字/空格 GLFW 与 VK 相同
        }
    }

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
            e.browser.sendKeyPress(toWindowsVk(keyCode), scanCode, modifiers);
            e.browser.setFocus(true);
        }
    }

    static void keyRelease(String webPath, int keyCode, int scanCode, int modifiers) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendKeyRelease(toWindowsVk(keyCode), scanCode, modifiers);
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
