/*
 * VideoPlayer.java
 *
 * 原生流式视频播放器（客户端）：
 * - 【视频线程】FrameGrab 顺序逐帧解码 → 按播放时钟节流 → 主线程把像素
 *   写入同一张 DynamicTexture 并 upload（纹理对象复用，任意时长内存恒定）。
 * - 【音频线程】MP4Demuxer 读 AAC/PCM 音轨 → AACDecoder(JAAD) 解码为 PCM16
 *   → javax.sound.sampled.SourceDataLine 播放；有音轨时以音频线为【主时钟】，
 *   无音轨/静音时用墙钟，保证画面与声音同步。
 * - 循环播放：视频线程与音频线程在各自 EOF 处会合（屏障）重置时钟后重开。
 * - 无人观看自动暂停：3 秒无渲染访问 → 暂停解码与音频线；再次访问恢复。
 *
 * 注意：
 * - 纯 Java H.264 解码速度可能慢于实时，此时视频会自动降速播放（保持音画同步优先）。
 * - AAC 解码失败（JAAD 不支持的 profile）时自动退化为无声播放，不影响画面。
 */
package com.flapdisplayplus.client;

import com.flapdisplayplus.FlapDisplayPlus;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.AudioFormat;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.AudioBuffer;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.scale.AWTUtil;

import com.flapdisplayplus.config.Config;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class VideoPlayer {

    private static final AtomicLong NEXT_ID = new AtomicLong(System.nanoTime());

    // 输出纹理长边上限 / 显示帧率上限 改为从 Config 读取（见 uploadFrame / videoLoop），
    // 方便在「卡顿 ↔ 清晰度」之间按需调整，无需改代码重编译。
    /** 切换走后，播放器暂停闲置超过此时长（ms）则由 tick 彻底停止并移除（防泄漏） */
    private static final long IDLE_STOP_MS = 8000;

    private final String path;
    private final File file;
    private final long id = NEXT_ID.incrementAndGet();

    /**
     * 网络媒体的流式下载器（本地文件播放时为 null）。
     * 非空且尚未下载完时，用 StreamingFileChannel 打开通道：读取超出已下载范围会
     * 等待数据到位而不是 EOF，从而支持边下边播。下载完成后自动退回普通通道。
     */
    private final com.flapdisplayplus.net.StreamDownloader netDl;

    /** 纹理（整段视频共用一张，像素原地刷新） */
    private volatile ResourceLocation textureLoc;
    private DynamicTexture texture;
    private NativeImage nativeImage;
    private final Object pixelLock = new Object();

    private volatile boolean stopped;

    /** 暂停状态（无渲染访问 3 秒后进入） */
    private volatile boolean paused;
    private volatile long lastAccessMs = System.currentTimeMillis();
    private final Object pauseLock = new Object();

    /** 音频线（主时钟来源） */
    private SourceDataLine line;
    /** 音频线时钟基准（每次重启后捕获，使 getClockMs 归零） */
    private volatile long lineBaseMs;
    /** 墙钟基准（无音轨/静音时使用） */
    private volatile long wallEpochMs = System.currentTimeMillis();
    /** 是否启用声音（构造时按配置决定，切换配置时整播放器重启） */
    private final boolean soundOn;

    /** 是否存在可用音轨（探测结果） */
    private volatile boolean audioUsable;

    /** 循环屏障会合方数（1=仅视频线程，2=视频+音频线程） */
    private volatile int barrierParties = 1;
    private int barrierWaiting;
    private final Object barrierLock = new Object();

    private final Thread videoThread;
    private final Thread audioThread;

    private volatile double frameDurMs = 33.33;
    private volatile int width;
    private volatile int height;

    VideoPlayer(String path, File file, boolean soundOn) {
        this(path, file, soundOn, null);
    }

    VideoPlayer(String path, File file, boolean soundOn, com.flapdisplayplus.net.StreamDownloader netDl) {
        this.path = path;
        this.file = file;
        this.soundOn = soundOn;
        this.netDl = netDl;
        videoThread = new Thread(this::videoLoop, "FdpVideo-" + id);
        videoThread.setDaemon(true);
        videoThread.start();
        // 音频探测+播放线程（只有开了声音才启动；探测失败自动退出）
        audioThread = new Thread(this::audioLoop, "FdpVideoAudio-" + id);
        audioThread.setDaemon(true);
        if (soundOn) {
            audioThread.start();
        }
        FlapDisplayPlus.LOGGER.info("[VideoPlayer] 启动: {} 声音={}", path, soundOn);
    }

    /** 渲染线程每帧调用：返回当前帧纹理（null=首帧未就绪） */
    public ResourceLocation getFrame() {
        lastAccessMs = System.currentTimeMillis();
        if (paused) {
            resume();
        }
        return textureLoc;
    }

    public int getTextureWidth() {
        return width;
    }

    public int getTextureHeight() {
        return height;
    }

    public void stop() {
        stopped = true;
        paused = false;
        if (videoThread != null) {
            videoThread.interrupt();
        }
        if (audioThread != null) {
            audioThread.interrupt();
        }
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        synchronized (barrierLock) {
            barrierLock.notifyAll();
        }
        closeLine();
        Minecraft.getInstance().execute(() -> {
            if (textureLoc != null) {
                try {
                    Minecraft.getInstance().getTextureManager().release(textureLoc);
                } catch (Throwable ignored) {
                }
                textureLoc = null;
            }
        });
    }

    private void closeLine() {
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================ 视频线程 ============================

    /**
     * 打开媒体通道：网络媒体仍在下载时用流式通道（读超出已下载范围会等待而非 EOF，
     * 实现边下边播）；下载完成或本地文件则走普通文件通道。
     */
    private SeekableByteChannel openChannel() throws java.io.IOException {
        com.flapdisplayplus.net.StreamDownloader dl = netDl;
        if (dl != null && !dl.isFinished()) {
            return com.flapdisplayplus.net.StreamingFileChannel.open(file, dl, dl.total());
        }
        return NIOUtils.readableChannel(file);
    }

    private void videoLoop() {
        while (!stopped) {
            SeekableByteChannel ch = null;
            try {
                ch = openChannel();
                FrameGrab grab = FrameGrab.createFrameGrab(ch);
                try {
                    double dur = grab.getVideoTrack().getMeta().getTotalDuration();
                    int total = grab.getVideoTrack().getMeta().getTotalFrames();
                    if (dur > 0 && total > 0) {
                        frameDurMs = dur * 1000.0 / total;
                    }
                } catch (Throwable ignore) {
                }
                Picture pic;
                long frameIdx = 0;
                long lastShownPts = -1;
                long minGap = 1000L / Math.max(1, Config.MEDIA_VIDEO_FPS.get());
                while (!stopped && (pic = grab.getNativeFrame()) != null) {
                    long ptsMs = (long) (frameIdx * frameDurMs);
                    frameIdx++;
                    waitUntil(ptsMs);
                    if (stopped) {
                        break;
                    }
                    // 源帧率高于 TARGET_FPS 时抽帧显示：跳过的帧不重绘（画面重复），
                    // 但 pts 仍按音频时钟推进，时间轴保持对齐，CPU 随抽帧比例下降。
                    if (ptsMs - lastShownPts >= minGap) {
                        uploadFrame(pic);
                        lastShownPts = ptsMs;
                    }
                }
                if (stopped) {
                    break;
                }
                // EOF：与音频线程会合重置时钟后重头播放
                loopBarrier();
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[VideoPlayer] 视频循环异常: {} {} —— 仅支持 MP4/M4V/MOV 容器 + H.264 编码"
                        + "（avi/mkv/H.265/AV1 不支持，jcodec 无对应解码器）", path, t.toString());
                sleepQuiet(500);
            } finally {
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /** 等到播放时钟到达 ptsMs（期间处理暂停） */
    private void waitUntil(long ptsMs) {
        while (!stopped) {
            maybePause();
            if (paused) {
                synchronized (pauseLock) {
                    try {
                        pauseLock.wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            long clock = clockMs();
            long remain = ptsMs - clock;
            if (remain <= 1) {
                return;
            }
            sleepQuiet(Math.min(remain, 40));
        }
    }

    /** 无人观看（3 秒无渲染访问）→ 暂停 */
    private void maybePause() {
        if (!paused && System.currentTimeMillis() - lastAccessMs > 3000) {
            synchronized (this) {
                if (!paused) {
                    paused = true;
                    SourceDataLine l = line;
                    if (l != null) {
                        try {
                            l.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                    FlapDisplayPlus.LOGGER.info("[VideoPlayer] 暂停（无人观看）: {}", path);
                }
            }
        }
    }

    private void resume() {
        synchronized (this) {
            paused = false;
            wallEpochMs = System.currentTimeMillis() - Math.max(0, audioClockSafe());
            SourceDataLine l = line;
            if (l != null && soundOn) {
                try {
                    l.start();
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    /** 若音频线可用，返回其时钟（用于墙钟衔接） */
    private long audioClockSafe() {
        SourceDataLine l = line;
        if (l != null && soundOn && audioUsable) {
            try {
                return Math.max(0, l.getMicrosecondPosition() / 1000 - lineBaseMs);
            } catch (Throwable ignored) {
            }
        }
        return clockMs();
    }

    /** 播放时钟（毫秒，每轮循环归零）。有音轨且未静音时以音频线位置为准 */
    private synchronized long clockMs() {
        SourceDataLine l = line;
        if (soundOn && audioUsable && l != null) {
            try {
                long pos = l.getMicrosecondPosition() / 1000;
                return Math.max(0, pos - lineBaseMs);
            } catch (Throwable ignored) {
            }
        }
        return Math.max(0, System.currentTimeMillis() - wallEpochMs);
    }

    // ============================ 帧上传（主线程） ============================

    /**
     * 解码并上传一帧：先按 Config 的纹理上限在【YUV 平面层】把 Picture 降采样到目标尺寸，
     * 再 AWTUtil 转 BufferedImage。这样从不生成原生分辨率（如 1080p≈8MB）的 BufferedImage，
     * 彻底消除之前每帧巨量内存分配导致的 GC 卡顿；YUV 逐平面盒式平均不影响配色
     * （已用红/蓝渐变图离线验证：降采样后仍正确，无串色）。
     * 最后主线程写入复用的 DynamicTexture 并 upload（LINEAR 过滤，避免拉伸块状模糊）。
     */
    private void uploadFrame(Picture pic) {
        int cap = Config.MEDIA_VIDEO_MAX_DIM.get();
        int sw = pic.getWidth();
        int sh = pic.getHeight();
        int tw = sw, th = sh;
        if (sw > cap || sh > cap) {
            double r = Math.min((double) cap / sw, (double) cap / sh);
            tw = Math.max(1, (int) (sw * r));
            th = Math.max(1, (int) (sh * r));
        }
        BufferedImage bi;
        if (tw != sw || th != sh) {
            // 解码即降采样：在 YUV 平面层完成，避免原生分辨率大缓冲
            try {
                bi = AWTUtil.toBufferedImage(scalePictureYuv(pic, tw, th));
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[VideoPlayer] YUV 降采样失败, 回退 Graphics2D: {}", path, t);
                BufferedImage full = AWTUtil.toBufferedImage(pic);
                bi = full == null ? null : scaleBuffered(full, tw, th);
            }
        } else {
            bi = AWTUtil.toBufferedImage(pic);
        }
        if (bi == null) {
            return;
        }
        int bw = bi.getWidth();
        int bh = bi.getHeight();
        int[] argb = bi.getRGB(0, 0, bw, bh, null, 0, bw);
        final int[] fsrc = argb;
        final int fw = bw, fh = bh;
        Minecraft.getInstance().execute(() -> writeTexture(fsrc, fw, fh));
    }

    /** 主线程：把 ARGB int[] 写入复用的纹理（配色 packing 与原 fillPixels 一致） */
    private void writeTexture(int[] argb, int w, int h) {
        if (stopped) {
            return;
        }
        try {
            synchronized (pixelLock) {
                if (nativeImage == null
                        || nativeImage.getWidth() != w
                        || nativeImage.getHeight() != h) {
                    nativeImage = new NativeImage(w, h, false);
                    texture = new DynamicTexture(nativeImage);
                    ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                            .register("flapdisplayplus/video_" + id, texture);
                    textureLoc = loc;
                    // LINEAR 过滤：纹理被拉伸到翻牌显示面时平滑插值，消除块状模糊。
                    // setFilter 内部 bind + texParameter，须在渲染线程（此处即主线程回调）。
                    texture.setFilter(true, false);
                    width = w;
                    height = h;
                    MediaManager.onVideoSize(path, width, height);
                }
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int c = argb[y * w + x];
                        int a = (c >>> 24) & 0xFF;
                        int r = (c >>> 16) & 0xFF;
                        int g = (c >>> 8) & 0xFF;
                        int b = c & 0xFF;
                        nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }
                texture.upload();
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.error("[VideoPlayer] 上传帧失败: {}", path, t);
        }
    }

    /**
     * 在 YUV 平面层把 Picture 等比盒式降采样到 tw×th（仅缩小）。
     * 逐平面（Y/U/V 各自独立）做整型盒式平均：YUV420 的三个平面各自缩小不影响
     * 色度对齐的正确性（U/V 本来就是半分辨率子采样）；配色已离线验证无串色。
     * 用途：视频帧在转 BufferedImage【之前】先降到目标尺寸，避免生成原生分辨率
     * 的大缓冲（1080p≈8MB/帧）——这是之前视频卡顿（GC churn）的根因。
     */
    private static Picture scalePictureYuv(Picture src, int tw, int th) {
        // 用源自身的 ColorSpace（视频常为 YUV420j 全域），避免 YUV420j→YUV420 的范围解读偏差
        Picture dst = Picture.create(tw, th, src.getColor());
        int planes = Math.min(3, Math.min(src.getData().length, dst.getData().length));
        for (int plane = 0; plane < planes; plane++) {
            boxAvg(src.getPlaneData(plane), src.getPlaneWidth(plane), src.getPlaneHeight(plane),
                    dst.getPlaneData(plane), dst.getPlaneWidth(plane), dst.getPlaneHeight(plane));
        }
        return dst;
    }

    /** 单平面整型盒式平均下采样（0-255 值域，源/目标步长即各自 planeWidth） */
    private static void boxAvg(byte[] s, int sw, int sh, byte[] d, int dw, int dh) {
        for (int dy = 0; dy < dh; dy++) {
            int sy0 = (dy * sh) / dh;
            int sy1 = ((dy + 1) * sh) / dh;
            if (sy1 <= sy0) {
                sy1 = sy0 + 1;
            }
            for (int dx = 0; dx < dw; dx++) {
                int sx0 = (dx * sw) / dw;
                int sx1 = ((dx + 1) * sw) / dw;
                if (sx1 <= sx0) {
                    sx1 = sx0 + 1;
                }
                long sum = 0;
                int cnt = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int row = sy * sw;
                    for (int sx = sx0; sx < sx1; sx++) {
                        sum += (s[row + sx] & 0xFF);
                        cnt++;
                    }
                }
                d[dy * dw + dx] = (byte) (sum / cnt);
            }
        }
    }

    /** Graphics2D 双线性缩放兜底（YUV 降采样异常时用，配色由 Java2D 保证） */
    private static BufferedImage scaleBuffered(BufferedImage src, int tw, int th) {
        BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return scaled;
    }

    /** 整型盒式下采样：避免 BufferedImage/Graphics2D 的逐帧分配（降 GC 压力） */
    private static int[] downscaleBox(int[] src, int sw, int sh, int dw, int dh) {
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int sy0 = (y * sh) / dh;
            int sy1 = ((y + 1) * sh) / dh;
            if (sy1 <= sy0) {
                sy1 = sy0 + 1;
            }
            for (int x = 0; x < dw; x++) {
                int sx0 = (x * sw) / dw;
                int sx1 = ((x + 1) * sw) / dw;
                if (sx1 <= sx0) {
                    sx1 = sx0 + 1;
                }
                int r = 0, g = 0, b = 0, a = 0;
                int cnt = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int c = src[sy * sw + sx];
                        a += (c >>> 24) & 0xFF;
                        r += (c >>> 16) & 0xFF;
                        g += (c >>> 8) & 0xFF;
                        b += c & 0xFF;
                        cnt++;
                    }
                }
                dst[y * dw + x] = (((a / cnt) & 0xFF) << 24)
                        | (((r / cnt) & 0xFF) << 16)
                        | (((g / cnt) & 0xFF) << 8)
                        | ((b / cnt) & 0xFF);
            }
        }
        return dst;
    }

    // ============================ 音频线程 ============================

    private void audioLoop() {
        // 探测音轨：文件里没有音轨则整体退出（纯无声视频）
        while (!stopped) {
            SeekableByteChannel ch = null;
            try {
                ch = openChannel();
                MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(ch);
                List<DemuxerTrack> audioTracks = demuxer.getAudioTracks();
                if (audioTracks.isEmpty()) {
                    audioUsable = false;
                    barrierParties = 1;
                    return; // 无音轨：音频线程退出，视频线程单独循环
                }
                audioUsable = true;
                barrierParties = 2;
                DemuxerTrack track = audioTracks.get(0);
                org.jcodec.common.Codec codec = track.getMeta().getCodec();

                boolean isAac = codec == org.jcodec.common.Codec.AAC;
                // PCM：jcodec 的 PCM-in-MP4 封装器常把 getCodec() 返回 null，
                // 因此非 AAC 一律按裸 PCM（音频包内即 PCM16 字节）处理。
                boolean isPcm = !isAac;
                if (!isAac && !isPcm) {
                    FlapDisplayPlus.LOGGER.warn("[VideoPlayer] 音轨编码不支持({}), 静音播放: {}",
                            codec, path);
                    audioUsable = false;
                    barrierParties = 1;
                    return;
                }
                org.jcodec.codecs.aac.AACDecoder aacDecoder = null;
                if (isAac) {
                    try {
                        ByteBuffer codecPrivate = track.getMeta().getCodecPrivate();
                        aacDecoder = new org.jcodec.codecs.aac.AACDecoder(codecPrivate);
                    } catch (Throwable t) {
                        FlapDisplayPlus.LOGGER.warn("[VideoPlayer] AAC 初始化失败, 静音播放: {} {}", path, t.toString());
                        audioUsable = false;
                        barrierParties = 1;
                        return;
                    }
                }

                Packet pkt;
                while (!stopped && (pkt = track.nextFrame()) != null) {
                    maybePause();
                    if (paused) {
                        synchronized (pauseLock) {
                            try {
                                pauseLock.wait(500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                    if (stopped) {
                        break;
                    }
                    byte[] pcm;
                    AudioFormat fmt = null;
                    if (isAac) {
                        try {
                            AudioBuffer ab = aacDecoder.decodeFrame(pkt.getData(), ByteBuffer.allocate(1 << 16));
                            if (ab != null) {
                                fmt = ab.getFormat();
                                pcm = toPcm16LE(ab);
                            } else {
                                continue;
                            }
                        } catch (Throwable t) {
                            // 单帧解码失败跳过，不中断整轨
                            continue;
                        }
                    } else {
                        ByteBuffer d = pkt.getData();
                        pcm = new byte[d.remaining()];
                        d.get(pcm);
                        try {
                            org.jcodec.common.AudioCodecMeta meta = track.getMeta().getAudioCodecMeta();
                            fmt = org.jcodec.common.AudioFormat.MONO_S16_LE(meta.getSampleRate());
                            if (meta.getChannelCount() == 2) {
                                fmt = org.jcodec.common.AudioFormat.STEREO_S16_LE(meta.getSampleRate());
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                    ensureLineOpen(fmt);
                    SourceDataLine l = line;
                    if (l != null) {
                        l.write(pcm, 0, pcm.length);
                    }
                }
                if (stopped) {
                    break;
                }
                // 播完一轮：放空缓冲、与视频线程会合
                SourceDataLine l = line;
                if (l != null) {
                    try {
                        l.drain();
                    } catch (Throwable ignored) {
                    }
                }
                loopBarrier();
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[VideoPlayer] 音频循环异常: {} {}", path, t.toString());
                audioUsable = false;
                barrierParties = 1;
                sleepQuiet(500);
                return; // 音频线程退出，视频线程降级墙钟单独循环
            } finally {
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /** 打开/复用 SourceDataLine（按解码出的 PCM 格式） */
    private void ensureLineOpen(AudioFormat jfmt) {
        if (line != null || jfmt == null) {
            return;
        }
        try {
            int ch = Math.max(1, Math.min(2, jfmt.getChannels()));
            float sr = jfmt.getSampleRate() > 0 ? jfmt.getSampleRate() : 44100f;
            javax.sound.sampled.AudioFormat jf =
                    new javax.sound.sampled.AudioFormat(sr, 16, ch, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, jf);
            if (!AudioSystem.isLineSupported(info)) {
                FlapDisplayPlus.LOGGER.warn("[VideoPlayer] 音频线不支持 {}Hz/{}ch, 静音: {}", sr, ch, path);
                audioUsable = false;
                barrierParties = 1;
                return;
            }
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            // 小缓冲降低播放延迟，使 line 时钟更接近实际播出位置，改善音画同步
            l.open(jf, 16384);
            synchronized (this) {
                line = l;
            }
            l.start();
            lineBaseMs = l.getMicrosecondPosition() / 1000;
            FlapDisplayPlus.LOGGER.info("[VideoPlayer] 音频开启 {}Hz/{}ch: {}", sr, ch, path);
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[VideoPlayer] 音频线打开失败, 静音: {} {}", path, t.toString());
            audioUsable = false;
            barrierParties = 1;
        }
    }

    /** AudioBuffer(任意位深/端序) → PCM16 小端字节 */
    private static byte[] toPcm16LE(AudioBuffer ab) {
        ByteBuffer data = ab.getData().duplicate();
        AudioFormat f = ab.getFormat();
        int sampleBytes = Math.max(1, f.getSampleSizeInBits() / 8);
        int channels = Math.max(1, f.getChannels());
        boolean be = f.isBigEndian();
        int n = data.remaining() / (sampleBytes * channels) * channels;
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v;
            if (sampleBytes == 1) {
                v = ((data.get() & 0xFF) - 128) << 8;
            } else if (sampleBytes == 2) {
                int b1 = data.get() & 0xFF;
                int b2 = data.get() & 0xFF;
                v = be ? ((b1 << 8) | b2) : ((b2 << 8) | b1);
                v = (short) v;
            } else {
                // 24/32 位：按端序取最高 2 字节 → 16 位
                byte[] tmp = new byte[sampleBytes];
                data.get(tmp);
                int i1 = be ? tmp[0] : tmp[sampleBytes - 1];
                int i2 = be ? tmp[1] : tmp[sampleBytes - 2];
                v = (short) ((i1 << 8) | (i2 & 0xFF));
            }
            out[i * 2] = (byte) v;
            out[i * 2 + 1] = (byte) (v >> 8);
        }
        return out;
    }

    // ============================ 循环屏障 ============================

    /**
     * 视频/音频线程在 EOF 处会合，全部到齐后统一重置时钟。
     * 超时 5 秒（比如另一线程异常退出）则单方重置，避免死等。
     */
    private void loopBarrier() {
        synchronized (barrierLock) {
            barrierWaiting++;
            int parties = barrierParties;
            if (barrierWaiting >= parties) {
                resetClocks();
                barrierWaiting = 0;
                barrierLock.notifyAll();
                return;
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (!stopped && barrierWaiting > 0 && System.currentTimeMillis() < deadline) {
                try {
                    barrierLock.wait(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (barrierWaiting > 0) {
                // 超时兜底：单方重置
                resetClocks();
                barrierWaiting = 0;
            }
        }
    }

    /** 重置两个时钟基准，使下一轮从 0 开始 */
    private void resetClocks() {
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.start();
            } catch (Throwable ignored) {
            }
            lineBaseMs = l.getMicrosecondPosition() / 1000;
        }
        wallEpochMs = System.currentTimeMillis();
    }

    // ============================ 工具 ============================

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 切换走后闲置超时判定（供 MediaManager.tick 清理，防泄漏/僵尸复播） */
    public boolean isIdleExpired() {
        return paused && (System.currentTimeMillis() - lastAccessMs > IDLE_STOP_MS);
    }
}
