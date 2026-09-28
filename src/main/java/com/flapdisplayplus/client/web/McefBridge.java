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
 *   创建浏览器后、以及每次鼠标按下/释放与键盘事件后 setFocus(true)——
 *   ★ OSR 不聚焦时 CEF 静默丢弃全部鼠标事件（键盘不受影响），独立实测实锤。
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

    /**
     * 离屏浏览器渲染尺寸（16:9）。
     * 1600×900：预览界面全屏铺开时只有 ~1.2 倍上采样，文字清晰（1024×576 会被玩家
     * 抱怨「很糊」）；对翻牌世界渲染同样是纹理源，分辨率只高不低。
     */
    static final int BROWSER_W = 1600;
    static final int BROWSER_H = 900;

    /** "web://" 前缀：网页媒体路径约定（服务端原样透传，客户端据此分流） */
    public static final String WEB_PREFIX = "web://";

    private static final Map<String, Entry> BROWSERS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();
    /** 全局 handler（弹窗兜底 + 音频路由 + JS 注入）只注册一次 */
    private static volatile boolean handlersRegistered;
    /** 各浏览器最近一次 getAudioParameters 报告的采样率（fork 的 onAudioStreamStarted 传 null 参数，只能在这里拿） */
    private static final Map<org.cef.browser.CefBrowser, Integer> AUDIO_RATES = new ConcurrentHashMap<>();
    /** 浏览器 → 所属网页路径（音频焦点判定用） */
    private static final Map<org.cef.browser.CefBrowser, String> AUDIO_OWNERS = new ConcurrentHashMap<>();
    /** 当前打开预览的网页（只有它出声；WebScreen init/removed 时更新） */
    private static volatile String AUDIO_FOCUS;
    /** 待应答的认证请求（同一时间最多一个，新的会取消旧的） */
    private static volatile org.cef.callback.CefAuthCallback PENDING_AUTH;

    private McefBridge() {
    }

    /**
     * 注册全局 CEF handler（幂等，首次建浏览器前调用）：
     * <ul>
     * <li>【弹窗】 CinemaMod java-cef fork 的 OnBeforePopup 在 OSR 模式下第一行就
     * return true（原生层直接取消弹窗，Java 回调永远不会被调用），因此
     * target=_blank / window.open 无法在 handler 层拦截。这里注册 LifeSpanHandler
     * 只是兜底；真正的解决方案是下面的【JS 注入】。</li>
     * <li>【JS 注入】每次主框架加载完成后注入脚本：重写 window.open 就地导航 +
     * 捕获阶段拦截 target=_blank 的 &lt;a&gt; 就地打开。B 站等站点的链接大多
     * 是 target=_blank，不注入就「点了没反应」。</li>
     * <li>【音频路由】OSR 不挂 CefAudioHandler 没有声音。注意 fork 的
     * onAudioStreamStarted 会传 null 的 CefAudioParameters（独立测试实锤），
     * 采样率只能在 getAudioParameters 里缓存。</li>
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
                        // fork 原生层在 OSR 下根本不会进来；万一进来了就照旧就地打开
                        if (browser != null && targetUrl != null && !targetUrl.isEmpty()
                                && !targetUrl.startsWith("javascript:")
                                && !targetUrl.startsWith("about:blank")) {
                            try {
                                browser.loadURL(targetUrl);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    }
                });
                cc.addAudioHandler(new org.cef.handler.CefAudioHandler() {
                    @Override
                    public boolean getAudioParameters(org.cef.browser.CefBrowser browser,
                                                      org.cef.misc.CefAudioParameters parameters) {
                        if (parameters != null) {
                            AUDIO_RATES.put(browser, parameters.sampleRate);
                        }
                        return true; // 接受 CEF 提供的参数（通常 44.1/48kHz 立体声）
                    }

                    @Override
                    public void onAudioStreamStarted(org.cef.browser.CefBrowser browser,
                                                     org.cef.misc.CefAudioParameters parameters,
                                                     int channels) {
                        // fork 缺陷：这里 parameters 为 null，采样率用 getAudioParameters 缓存的值
                        int rate = parameters != null ? parameters.sampleRate
                                : AUDIO_RATES.getOrDefault(browser, 48000);
                        WebAudio.start(browser, rate, channels);
                    }

                    @Override
                    public void onAudioStreamPacket(org.cef.browser.CefBrowser browser,
                                                    float[] data, int framesPerChannel, long pts) {
                        WebAudio.feed(browser, data, framesPerChannel, pts);
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
                // 【认证登录】HTTP 401 Basic/Digest 认证：fork 的 OSR 模式弹不出原生登录框，
                // 页面会直接显示 401。getAuthCredentials 会委派到 addRequestHandler 注册的
                // handler（javap 核实），这里弹一个 MC 内的账号/密码界面，异步 Continue。
                cc.addRequestHandler(new org.cef.handler.CefRequestHandlerAdapter() {
                    @Override
                    public boolean getAuthCredentials(org.cef.browser.CefBrowser browser, String requestedUrl,
                                                      boolean isProxy, String host, int port, String realm,
                                                      String scheme, org.cef.callback.CefAuthCallback callback) {
                        String what = (isProxy ? "代理认证" : "网站登录") + " · " + host
                                + (port > 0 ? ":" + port : "")
                                + (realm != null && !realm.isEmpty() ? "（" + realm + "）" : "");
                        // 同一时间只保留一个待认证请求；新的来了先取消旧的，避免悬挂
                        org.cef.callback.CefAuthCallback prev = PENDING_AUTH;
                        if (prev != null) {
                            try {
                                prev.cancel();
                            } catch (Throwable ignored) {
                            }
                        }
                        PENDING_AUTH = callback;
                        Minecraft.getInstance().execute(() -> {
                            try {
                                net.minecraft.client.gui.screens.Screen cur = Minecraft.getInstance().screen;
                                Minecraft.getInstance().setScreen(new WebAuthScreen(what, cur, cred -> {
                                    PENDING_AUTH = null;
                                    if (cred == null) {
                                        callback.cancel();
                                    } else {
                                        callback.Continue(cred[0], cred[1]);
                                    }
                                }));
                            } catch (Throwable t) {
                                FlapDisplayPlus.LOGGER.warn("[Web] 打开登录界面失败: {}", t.toString());
                                PENDING_AUTH = null;
                                callback.cancel();
                            }
                        });
                        return true;
                    }
                });
                handlersRegistered = true;
                FlapDisplayPlus.LOGGER.info("[Web] CEF 全局 handler 已注册");
            } catch (Throwable t) {
                // 注册失败不阻塞视频画面；音频/弹窗退化为基础行为
                FlapDisplayPlus.LOGGER.warn("[Web] CEF handler 注册失败（弹窗与声音可能异常）: {}", t.toString());
                handlersRegistered = true;
            }
            // JS 注入必须挂在 MCEFClient 上：CefClient.addLoadHandler 是「先到先得」，
            // MCEF 自己的 MCEFClient 构造器已占坑，直接挂 handle 会被忽略。
            try {
                MCEF.getClient().addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
                    @Override
                    public void onLoadEnd(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame,
                                          int httpStatusCode) {
                        if (frame != null && frame.isMain()) {
                            try {
                                browser.executeJavaScript(POPUP_BYPASS_JS, browser.getURL(), 1);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                FlapDisplayPlus.LOGGER.info("[Web] 弹窗绕过 JS 注入已挂载");
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[Web] JS 注入挂载失败（target=_blank 链接将无法点击）: {}", t.toString());
            }
        }
    }

    /**
     * 弹窗绕过脚本：OSR 模式下 CEF fork 会静默丢弃所有弹窗（window.open / target=_blank），
     * 只能在页面侧把它们改写成「就地导航」。捕获阶段监听，先于站点自身逻辑执行。
     */
    private static final String POPUP_BYPASS_JS =
            "(function(){" +
                    "if(window.__fdpWebPatched)return;window.__fdpWebPatched=1;" +
                    "try{var _o=window.open;window.open=function(u){try{if(u)location.href=String(u);}catch(e){}return null;};}catch(e){}" +
                    "document.addEventListener('click',function(ev){" +
                    "try{if(ev.defaultPrevented)return;" +
                    "var a=ev.target;while(a&&a.nodeType===1&&a.tagName!=='A')a=a.parentElement;" +
                    "if(a&&a.tagName==='A'){" +
                    "var t=(a.getAttribute('target')||'').toLowerCase();" +
                    "var h=a.getAttribute('href')||'';" +
                    "if((t==='_blank'||t==='blank')&&h&&h.indexOf('javascript:')!==0){" +
                    "ev.preventDefault();ev.stopPropagation();location.href=a.href;}}" +
                    "}catch(e){}},true);" +
                    "})();";

    /**
     * CEF 浮点 PCM → Java Sound 播放（每浏览器一个播放状态）。
     *
     * 【音质三坑】（v1.0.2 修复，对应「声音糊 / 音调不对 / 杂音多」）：
     * 1. 通道数不能信 onAudioStreamStarted 的 channels 参数——用真实数据形状校准：
     *    交错 PCM 下 channels = data.length / framesPerChannel，对不上就换线重开。
     *    通道数错了会让消费速率 ≠ 生产速率 → 背压/欠载 → 音调漂移 + 咔啦杂音。
     * 2. 采样率不能只信 getAudioParameters——用 pts（纳秒时间轴）实测真实采样率，
     *    偏差超过 2% 就校准一次（每条流只校准一次，防 pts 异常导致反复抖动）。
     * 3. 缓冲加大到 ~200ms 并在背压下阻塞写（write 天然背压），避免欠载爆音。
     *
     * 【声音开关】只有网页预览界面（WebScreen）开着的那一个网页出声：
     * 非焦点浏览器的包直接丢弃并关线（保留参数，重新预览时无缝恢复）。
     */
    private static final class WebAudio {
        private static final Map<org.cef.browser.CefBrowser, State> STATES = new ConcurrentHashMap<>();

        private static final class State {
            volatile int rate;
            volatile int channels;
            javax.sound.sampled.SourceDataLine line;
            /** 采样率是否已用 pts 实测校准过（每条流只校准一次） */
            volatile boolean calibrated;
            /** 采样率测量窗口：首包 pts（纳秒）与累计帧数 */
            long windowFirstPts = -1;
            long windowFrames;
        }

        static void start(org.cef.browser.CefBrowser browser, int sampleRate, int channels) {
            State st = STATES.computeIfAbsent(browser, k -> new State());
            st.rate = sampleRate > 0 ? sampleRate : 48000;
            st.channels = channels <= 0 ? 2 : Math.min(channels, 2);
            st.calibrated = false;
            st.windowFirstPts = -1;
            st.windowFrames = 0;
            openLine(st);
            FlapDisplayPlus.LOGGER.info("[Web] 音频开始: {}Hz x{}ch", st.rate, st.channels);
        }

        private static void openLine(State st) {
            closeLine(st);
            try {
                javax.sound.sampled.AudioFormat fmt =
                        new javax.sound.sampled.AudioFormat(st.rate, 16, st.channels, true, false);
                javax.sound.sampled.SourceDataLine line =
                        javax.sound.sampled.AudioSystem.getSourceDataLine(fmt);
                line.open(fmt, Math.max(16384, st.rate * st.channels * 2 / 5)); // ~200ms 缓冲
                line.start();
                st.line = line;
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[Web] 音频线打开失败: {}", t.toString());
                st.line = null;
            }
        }

        private static void closeLine(State st) {
            javax.sound.sampled.SourceDataLine line = st.line;
            st.line = null;
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Throwable ignored) {
                }
            }
        }

        static void feed(org.cef.browser.CefBrowser browser, float[] data, int framesPerChannel, long pts) {
            State st = STATES.get(browser);
            if (st == null || framesPerChannel <= 0 || data.length == 0) {
                return;
            }
            // 【声音开关】预览没开 / 开的不是这个网页 → 丢包关线（保留参数可恢复）
            String focus = AUDIO_FOCUS;
            String owner = AUDIO_OWNERS.get(browser);
            if (focus == null || !focus.equals(owner)) {
                if (st.line != null) {
                    closeLine(st);
                }
                return;
            }
            // 重新进入预览后恢复播放线（start 事件不会再发）
            if (st.line == null) {
                openLine(st);
                if (st.line == null) {
                    return;
                }
            }
            // 【通道数自校准】以真实数据形状为准（交错 PCM：channels = 总样本 / 每通道帧数）
            if (data.length % framesPerChannel == 0) {
                int actualCh = data.length / framesPerChannel;
                if (actualCh >= 1 && actualCh <= 8 && actualCh != st.channels) {
                    FlapDisplayPlus.LOGGER.info("[Web] 声道数校准: {} → {}（以实际数据为准）",
                            st.channels, actualCh);
                    st.channels = actualCh;
                    openLine(st);
                    if (st.line == null) {
                        return;
                    }
                }
            }
            int channels = st.channels;
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
                st.line.write(out, 0, out.length);
            } catch (Throwable ignored) {
            }
            // 【采样率校准】pts 为纳秒时间轴：rate = 窗口内总帧数 / 窗口时长。
            // 每条流只校准一次，防止 pts 语义异常时反复换线。
            if (!st.calibrated && pts > 0) {
                if (st.windowFirstPts < 0) {
                    st.windowFirstPts = pts;
                    st.windowFrames = framesPerChannel;
                } else {
                    st.windowFrames += framesPerChannel;
                    long dpts = pts - st.windowFirstPts;
                    if (dpts >= 400_000_000L && st.windowFrames > 0) { // 0.4s 窗口
                        long measured = st.windowFrames * 1_000_000_000L / dpts;
                        if (measured >= 16000 && measured <= 192000
                                && Math.abs(measured - st.rate) * 50 > st.rate) {
                            FlapDisplayPlus.LOGGER.info("[Web] 采样率校准: {} → {}Hz（pts 实测）",
                                    st.rate, measured);
                            st.rate = (int) measured;
                            st.calibrated = true;
                            openLine(st);
                        }
                        st.windowFirstPts = -1;
                        st.windowFrames = 0;
                    }
                }
            }
        }

        /** 流停止（页面暂停/切走）：关线但保留参数，供重新预览时恢复 */
        static void stop(org.cef.browser.CefBrowser browser) {
            State st = STATES.get(browser);
            if (st != null) {
                closeLine(st);
            }
        }

        /** 浏览器销毁：彻底清理 */
        static void dispose(org.cef.browser.CefBrowser browser) {
            State st = STATES.remove(browser);
            if (st != null) {
                closeLine(st);
            }
        }
    }

    /** WebScreen 打开预览：该网页获得音频焦点（唯一出声者） */
    static void previewOpened(String webPath) {
        AUDIO_FOCUS = webPath;
    }

    /** WebScreen 关闭/被替换：释放音频焦点（声音立刻停） */
    static void previewClosed(String webPath) {
        if (webPath == null || webPath.equals(AUDIO_FOCUS)) {
            AUDIO_FOCUS = null;
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
            // ★ 焦点是鼠标事件的开关：OSR 浏览器不 setFocus 时 CEF 会静默丢弃
            //   全部鼠标点击（键盘不受影响）。独立实测确认，务必创建后立刻聚焦。
            b.setFocus(true);
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
        AUDIO_OWNERS.put(e.browser, webPath);
        FlapDisplayPlus.LOGGER.info("[Web] 打开网页: {} → {}", webPath, url);
        return e;
    }

    // ===== 帧上传（渲染线程） =====

    /** NativeImage.pixels 字段（反射缓存；NeoForge 1.21.1 运行时为 mojmap 官方名，拿不到走逐像素兜底） */
    private static volatile java.lang.reflect.Field PIXELS_FIELD;
    private static volatile boolean PIXELS_FIELD_TRIED;

    private static long pixelsPointer(NativeImage img) {
        if (!PIXELS_FIELD_TRIED) {
            synchronized (McefBridge.class) {
                if (!PIXELS_FIELD_TRIED) {
                    try {
                        java.lang.reflect.Field f = NativeImage.class.getDeclaredField("pixels");
                        f.setAccessible(true);
                        PIXELS_FIELD = f;
                    } catch (Throwable ignored) {
                    }
                    PIXELS_FIELD_TRIED = true;
                }
            }
        }
        java.lang.reflect.Field f = PIXELS_FIELD;
        if (f == null) {
            return 0;
        }
        try {
            return f.getLong(img);
        } catch (Throwable t) {
            return 0;
        }
    }

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
                long ptr = pixelsPointer(img);
                if (ptr != 0) {
                    // 快路径：直写 NativeImage 的本地内存（底层与 setPixelRGBA 同一布局）
                    java.nio.IntBuffer ib = org.lwjgl.system.MemoryUtil.memIntBuffer(ptr, w * h);
                    ib.put(px);
                } else {
                    for (int y = 0; y < h; y++) {
                        int row = y * w;
                        for (int x = 0; x < w; x++) {
                            img.setPixelRGBA(x, y, px[row + x]);
                        }
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
                AUDIO_OWNERS.remove(e.browser);
                AUDIO_RATES.remove(e.browser);
                WebAudio.dispose(e.browser);
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
     * 【键码铁律】直接透传 GLFW keyCode + 真实 scancode，不做任何映射。
     * Windows 原生层（java-cef fork，独立实测）完全无视 keyCode：键盘身份由
     * scancode 经 MapVirtualKey 推导，特殊键（回车/退格/方向键等）由
     * keyChar=(char)keyCode 匹配 GLFW_KEY_* 常量表得到；
     * Linux 原生层则直接把 keyCode 当 GLFW 键码查表。
     * 两种平台的正确输入都是「原样透传」，之前的 VK 映射在 Windows 上无效、
     * 在 Linux 上反而有害，已撤销。
     */
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
            e.browser.setFocus(true); // MCEF ExampleScreen 同款：鼠标事件后重新聚焦
        }
    }

    static void mouseRelease(String webPath, int x, int y, int button) {
        Entry e = BROWSERS.get(webPath);
        if (e != null && e.browser != null) {
            e.browser.sendMouseRelease(x, y, button);
            e.browser.setFocus(true);
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
