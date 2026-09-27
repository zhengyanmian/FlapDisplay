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
 * - 暂停 = 游戏菜单（ESC）暂停，由 MediaManager 每刻驱动；清空/拆方块立即硬停，无自动续播。
 *
 * 注意：
 * - 纯 Java H.264 解码速度可能慢于实时，此时视频会自动降速播放（宁可慢放也绝不冻结），
 *   并周期性按音频时钟 seek 重同步，避免音画无限漂移。
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
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.scale.AWTUtil;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.RgbToBgr;
import org.jcodec.scale.Transform;

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

    /**
     * 【2026-09-27 重写 —— 修「有声音但画面卡住不动」】
     * 旧逻辑靠「落后 > 350ms 就 continue 丢帧，连续丢满 3000ms 才强制显示一帧」保同步，
     * 但 seek 成功时会把丢帧计时器 dropRunStart 归零，而 seek 最快每 RESYNC_MIN_INTERVAL_MS
     * (1200ms) 一次 —— 1200 < 3000，于是「强制显示」这道防冻结兜底【永远无法触发】。
     * 结果：只要单帧解码耗时超过约 (1000/视频帧率 + 350)ms（例如本机那个 142MB 的 720p
     * 长视频，纯 Java H.264 解码远慢于实时），每一帧都会走 continue 分支 → 画面永远停在
     * 上一次显示的帧上不再刷新，而独立的音频线程照常播放 —— 正是用户报的
     * 「有声音、有画面，但画面卡住不动」。
     * 现在改为：显示节流用【墙钟】而不是 pts，落后时依然照常推进画面（宁可慢放也绝不冻结）；
     * 只有落后到 RESYNC_HARD_LATE_MS 才做一次「按音频时钟 seek 重同步」，防止音画无限漂移。
     */
    private static final long RESYNC_HARD_LATE_MS = 2500;

    /** 两次重同步 seek 之间的最小间隔，避免频繁 seek 把 IO 打满 */
    private static final long RESYNC_MIN_INTERVAL_MS = 1200;

    private final String path;
    private final File file;
    private final long id = NEXT_ID.incrementAndGet();

    /**
     * 网络媒体的流式下载器（本地文件播放时为 null）。
     * 非空且尚未下载完时，用 StreamingFileChannel 打开通道：读取超出已下载范围会
     * 等待数据到位而不是 EOF，从而支持边下边播。下载完成后自动退回普通通道。
     */
    private final com.flapdisplayplus.net.StreamDownloader netDl;

    // ===== 纹理三重缓冲 =====
    // 【2026-09-27 重写 —— 卡顿主因】
    // 旧实现：后台线程解码 → 把 int[] 丢回主线程 → **主线程** 逐像素 setPixelRGBA
    //         （w×h 次，1024×576≈59 万次/帧）→ 主线程被压垮 → 整体掉帧。
    // 现实现：逐像素填充改在**视频线程**完成（NativeImage 只是堆外内存，可安全多线程读写），
    //         主线程每帧只做一次 texture.upload()。用 3 个缓冲轮转，保证「后台在写的那一块
    //         永远不是主线程正在读的那一块」，因此无需加锁也不会撕裂。
    private static final int NBUFS = 3;

    /** 当前展示的纹理（渲染线程读） */
    private volatile ResourceLocation textureLoc;
    /**
     * 三套缓冲/纹理。★ imgs 与 nbuf 必须是 volatile：
     * 二者由【渲染线程】在 promoteStaged 里赋值，却由【视频线程】在 uploadFrame 里读取，
     * 而这两个线程之间没有共同持有的锁（promoteStaged / getFrame 都不是 synchronized）。
     * 非 volatile 时视频线程可能一直读到陈旧的 nbuf==0，于是再也不填充新帧、
     * 画面永久停在首帧 —— 与「有声音但画面卡住不动」的症状完全一致。
     * 让 nbuf 为 volatile 后：promoteStaged 里的 volatile 写(nbuf) 充当 release，
     * uploadFrame 里的 volatile 读充当 acquire，正好为 imgs 的赋值建立可见性。
     */
    private volatile NativeImage[] imgs;
    private DynamicTexture[] texs;
    private ResourceLocation[] locs;
    private volatile int nbuf;
    /** 当前展示的缓冲下标（-1 = 尚未初始化）。volatile：视频线程据此选下一块， 必须可见 */
    private volatile int front = -1;
    /** 后台已填好、等待渲染线程提升的缓冲下标（-1 = 无） */
    private volatile int staged = -1;

    /** 首帧载荷：纹理只能在渲染线程注册，故首帧由后台把数据交过来、渲染线程建缓冲 */
    private volatile int[] initArgb;
    private volatile int initW;
    private volatile int initH;

    private volatile boolean stopped;

    /**
     * 最近一次渲染访问（getFrame）的时间戳。
     * 注意这【不是】暂停机制（自动暂停续播已按需求移除）——
     * MediaManager 据此回收「画面已消失却还在出声」的孤儿播放器（硬停，不续播）。
     */
    private volatile long lastRenderAccessMs = System.currentTimeMillis();

    /** 供 MediaManager.tick 做孤儿回收判断 */
    public long lastRenderAccessMs() {
        return lastRenderAccessMs;
    }

    /**
     * 暂停状态（唯一来源 = 游戏菜单暂停，由 MediaManager 每刻驱动）。
     * 【2026-09-27 移除「无人观看 3 秒自动暂停/回来续播」】用户明确不需要视频的暂停续播：
     * 旧逻辑在方块拆除后要等 3 秒才停声，且与 stop() 竞态时会诱发「声音停了又复活」。
     */
    private volatile boolean paused;
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

    // ===== 【2026-09-27】零分配转换管线（仅视频线程访问，无需同步）=====
    // 旧路径每帧分配：AWTUtil.toBufferedImage 的 BufferedImage(720p≈2.7MB) + getRGB 的
    // int[](≈3.7MB) + 降采样 Picture —— 25fps 时 GC 垃圾 ≈150MB/s，G1 回收造成的
    // 停顿正是长视频「一顿一顿」的元凶之一。
    // 新路径：ColorUtil.getTransform(YUV→RGB) + RgbToBgr（与 AWTUtil.toBufferedImage
    // 内部完全同源，已离线验证逐像素 0 差异），全部目标缓冲预分配复用，
    // 每帧 0 堆分配。crop 非空的罕见封装退回旧路径兜底。
    private Picture scaledBuf;
    private Picture bgrBuf;
    private Transform colorTransform;
    private final RgbToBgr bgrSwap = new RgbToBgr();

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

    /** 渲染线程每帧调用：返回当前帧纹理（null=首帧未就绪）。暂停由 MediaManager 驱动，这里不再自动恢复 */
    public ResourceLocation getFrame() {
        lastRenderAccessMs = System.currentTimeMillis();
        promoteStaged();
        return textureLoc;
    }

    /** 游戏菜单暂停/恢复（MediaManager 每刻驱动；幂等）。暂停 = 冻结画面 + 立即静音 */
    public void setMenuPaused(boolean p) {
        if (stopped || p == paused) {
            return;
        }
        synchronized (this) {
            if (stopped) {
                return;
            }
            paused = p;
            SourceDataLine l = line;
            if (l != null) {
                try {
                    if (p) {
                        // 先把当前播放位置定格到墙钟，恢复时两钟仍一致
                        wallEpochMs = System.currentTimeMillis() - clockMs();
                        l.stop();
                        l.flush();
                    } else {
                        l.start();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    public int getTextureWidth() {
        return width;
    }

    public int getTextureHeight() {
        return height;
    }

    /**
     * 硬停（【2026-09-27 重写语义】清空/拆方块后必须立即无声）：
     * 顺序关键 —— 先置 stopped 再关音频线：旧实现先 interrupt 后关线，且音频线程在
     * write 阻塞期间 interrupt 无效；更糟的是若音频线程刚从 pauseLock.wait 醒来，
     * 可能重新走到 ensureLineOpen 把【已关闭的线重新打开】→ 声音复活。
     * 现在：stopped 置位后 ensureLineOpen / resetClocks 一律拒绝动作，声音不可能复活。
     */
    public void stop() {
        stopped = true;
        paused = false;
        closeLine();
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        synchronized (barrierLock) {
            barrierLock.notifyAll();
        }
        if (videoThread != null) {
            videoThread.interrupt();
        }
        if (audioThread != null) {
            audioThread.interrupt();
        }
        // 纹理必须在渲染线程释放（会 close 掉 NativeImage 并 delete GL 纹理）
        Minecraft.getInstance().execute(() -> {
            ResourceLocation[] ls = locs;
            textureLoc = null;
            if (ls != null) {
                for (ResourceLocation l : ls) {
                    if (l == null) {
                        continue;
                    }
                    try {
                        Minecraft.getInstance().getTextureManager().release(l);
                    } catch (Throwable ignored) {
                    }
                }
            }
            // 兜底：万一有缓冲没被 release 覆盖到，直接关掉其 NativeImage
            if (imgs != null) {
                for (NativeImage im : imgs) {
                    if (im != null) {
                        try {
                            im.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            locs = null;
            imgs = null;
            texs = null;
            nbuf = 0;
            front = -1;
            staged = -1;
            initArgb = null;
        });
    }

    /** 是否已被 stop()（MediaManager 用它把残留的已停实例从 VIDEOS 表清掉） */
    public boolean isStopped() {
        return stopped;
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
            // ===== FFmpeg 管道解码后端（可选，借鉴 Functional TVs / MP4 Video Player 的成熟做法）=====
            // 找得到 ffmpeg.exe 就用它：支持 H.264/H.265/AV1 + mkv/avi/webm 等任意容器，
            // 解码速度远快于纯 Java（720p 实时无压力 → 不糊不卡）；找不到则退回 jcodec。
            FfmpegPipeDecoder fdec = tryOpenFfmpeg();
            if (fdec != null) {
                FlapDisplayPlus.LOGGER.info("[VideoPlayer] FFmpeg 解码后端 {}x{} @{}fps: {}",
                        fdec.width, fdec.height, (int) fdec.fps, path);
                try {
                    ffmpegLoop(fdec);
                } catch (Throwable t) {
                    FlapDisplayPlus.LOGGER.warn("[VideoPlayer] FFmpeg 循环异常: {} {}", path, t.toString());
                } finally {
                    fdec.close();
                }
                if (stopped) {
                    break;
                }
                loopBarrier();
                continue;
            }
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
                long minGap = 1000L / Math.max(1, Config.MEDIA_VIDEO_FPS.get());
                long lastResyncMs = 0;
                // 上一帧「显示」的墙钟时刻（不是 pts！）。这是修「画面卡住不动」的关键：
                // 即使解码慢到追不上音频时钟，也照样按 1000/fps 的间隔把画面推下去。
                long lastShownWallMs = 0;
                // FFmpeg 自动下载完成后主动切换后端（每 5 秒查一次；isAvailable 走缓存，开销可忽略）
                boolean switchToFfmpeg = false;
                long lastFfCheck = 0;
                while (!stopped && (pic = grab.getNativeFrame()) != null) {
                    long nowP = System.currentTimeMillis();
                    if (nowP - lastFfCheck > 5000) {
                        lastFfCheck = nowP;
                        if (FfmpegPipeDecoder.isAvailable()) {
                            switchToFfmpeg = true;
                            break;
                        }
                    }
                    long ptsMs = (long) (frameIdx * frameDurMs);
                    frameIdx++;
                    waitUntil(ptsMs);
                    if (stopped) {
                        break;
                    }
                    long nowMs = System.currentTimeMillis();
                    long late = clockMs() - ptsMs;
                    // ===== 音画同步保护：只有落后到「硬阈值」才 seek 重同步 =====
                    // 注意这里【不再丢帧】：解码慢时宁可慢放，也绝不能让画面冻结。
                    // 重同步本身仍按 RESYNC_MIN_INTERVAL_MS 限流，避免频繁 seek 打满 IO。
                    if (late > RESYNC_HARD_LATE_MS && nowMs - lastResyncMs > RESYNC_MIN_INTERVAL_MS) {
                        lastResyncMs = nowMs;
                        double sec = Math.max(0.0, clockMs() / 1000.0);
                        try {
                            grab.seekToSecondPrecise(sec);
                            // 手里这帧是 seek 之前的旧帧，丢弃；下一轮取靠近音频时钟的一帧
                            frameIdx = (long) (sec * 1000.0 / Math.max(1e-6, frameDurMs));
                            lastShownWallMs = 0;
                            FlapDisplayPlus.LOGGER.debug(
                                    "[VideoPlayer] 解码滞后 {}ms，已按音频时钟重同步到 {}s: {}", late, sec, path);
                            continue;
                        } catch (Throwable t) {
                            // 流式通道/不支持的封装可能 seek 失败：忽略，继续按墙钟节流显示
                            FlapDisplayPlus.LOGGER.debug("[VideoPlayer] 跳帧对齐失败(忽略): {}", t.toString());
                        }
                    }
                    // 显示节流：按【墙钟】限流到 media.videoFps（源帧率更高时抽帧显示）。
                    // 解码快 → 与 waitUntil 配合正常实时播放；解码慢 → 变成慢放而非冻结。
                    if (nowMs - lastShownWallMs >= minGap) {
                        uploadFrame(pic);
                        lastShownWallMs = nowMs;
                    }
                }
                if (stopped) {
                    break;
                }
                if (switchToFfmpeg) {
                    // 不走 loopBarrier（音频线程还在播），直接回外层重试 FFmpeg 后端；
                    // 时钟衔接由音画同步的 seek 重同步自愈
                    continue;
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

    /** FFmpeg 解码后端打开（失败/不存在返回 null → 走 jcodec） */
    private FfmpegPipeDecoder tryOpenFfmpeg() {
        try {
            String exe = FfmpegPipeDecoder.locate();
            if (exe != null) {
                return FfmpegPipeDecoder.open(file, exe);
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * FFmpeg 管道解码主循环（结构与 jcodec 路径一致：PTS 节流 + 硬滞后 seek 重同步 + 墙钟显示）。
     * 【2026-09-27】只在「本帧会被显示」时才做颜色转换（displayFps 通常低于源帧率，
     * 1920 级别每省一帧就省十几毫秒 CPU）；readFrame 与转换都在本线程，零堆分配。
     */
    private void ffmpegLoop(FfmpegPipeDecoder dec) {
        frameDurMs = dec.fps > 0 ? 1000.0 / dec.fps : 33.33;
        long minGap = 1000L / Math.max(1, Config.MEDIA_VIDEO_FPS.get());
        long lastResyncMs = 0;
        long lastShownWallMs = 0;
        long frameIdx = 0;
        while (!stopped) {
            if (!dec.readFrame()) {
                return; // EOF → 外层 barrier 会合后重开
            }
            long ptsMs = (long) (frameIdx * frameDurMs);
            frameIdx++;
            waitUntil(ptsMs);
            if (stopped) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            long late = clockMs() - ptsMs;
            if (late > RESYNC_HARD_LATE_MS && nowMs - lastResyncMs > RESYNC_MIN_INTERVAL_MS) {
                lastResyncMs = nowMs;
                double sec = Math.max(0.0, clockMs() / 1000.0);
                if (dec.seekTo(sec)) {
                    frameIdx = (long) (sec * 1000.0 / Math.max(1e-6, frameDurMs));
                    lastShownWallMs = 0;
                    continue;
                }
                // seek 失败：解码器已死，readFrame 会返回 false → 外层重开
            }
            if (nowMs - lastShownWallMs >= minGap) {
                uploadFfmpegFrame(dec);
                lastShownWallMs = nowMs;
            }
        }
    }

    /**
     * FFmpeg 帧上传：解码器单遍转换进预分配打包缓冲（0 分配），再走与
     * uploadFrame 相同的三缓冲暂存。仅视频线程调用。
     */
    private void uploadFfmpegFrame(FfmpegPipeDecoder dec) {
        int cap = Config.MEDIA_VIDEO_MAX_DIM.get();
        int sw = dec.width;
        int sh = dec.height;
        int tw = sw, th = sh;
        if (sw > cap || sh > cap) {
            double r = Math.min((double) cap / sw, (double) cap / sh);
            tw = Math.max(1, (int) (sw * r));
            th = Math.max(1, (int) (sh * r));
        }
        if (fastW != tw || fastH != th || fastArgb == null) {
            fastArgb = new int[tw * th];
            fastW = tw;
            fastH = th;
        }
        dec.convertLastInto(fastArgb, tw, th);
        // 首帧：纹理要在渲染线程注册，把数据交给渲染线程建缓冲（复制一份，本缓冲继续复用）
        if (nbuf == 0) {
            if (initArgb == null && staged < 0) {
                initW = tw;
                initH = th;
                initArgb = fastArgb.clone();
            }
            return;
        }
        // 上一帧还没被渲染线程取走 → 丢这一帧（宁可丢帧也不排队，防止画面永久滞后）
        if (staged >= 0) {
            return;
        }
        int b = (front + 1) % NBUFS;
        fillPacked(imgs[b], fastArgb, tw, th);
        staged = b;
    }

    /** FFmpeg 单遍转换产出的打包缓冲（仅视频线程访问） */
    private int[] fastArgb;
    private int fastW = -1;
    private int fastH = -1;

    /** 等到播放时钟到达 ptsMs（期间处理菜单暂停） */
    private void waitUntil(long ptsMs) {
        while (!stopped) {
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

    // ============================ 帧上传 ============================
    // 分工：视频线程做「解码 + 降采样 + 逐像素填充」；渲染线程只做「upload + 换纹理」。
    // 这样主线程每帧的固定开销从 O(w×h) 降到 O(1)。

    /**
     * 解码并填充一帧到后台缓冲（**视频线程**执行）。
     * 【2026-09-27 重写为零分配管线】先按 Config 的纹理上限在【YUV 平面层】降采样
     * （复用缓冲），再用 jcodec Transform 转 BGR（复用缓冲），最后直接把 BGR 字节
     * 打包写进轮转的 NativeImage —— 全程 0 堆分配（首帧打包除外）。
     * 转换链路与 AWTUtil.toBufferedImage 内部完全同源，已在真实视频上验证逐像素 0 差异。
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
        // crop 非空的封装（极少见）退回旧 AWTUtil 路径（它自带 crop 处理）
        if (pic.getCrop() != null) {
            legacyUpload(pic, tw, th);
            return;
        }
        Picture src = pic;
        if (tw != sw || th != sh) {
            if (scaledBuf == null || scaledBuf.getWidth() != tw || scaledBuf.getHeight() != th) {
                scaledBuf = Picture.create(tw, th, pic.getColor());
            }
            boxAvgInto(pic, scaledBuf);
            src = scaledBuf;
        }
        if (bgrBuf == null || bgrBuf.getWidth() != tw || bgrBuf.getHeight() != th) {
            bgrBuf = Picture.create(tw, th, ColorSpace.BGR);
        }
        if (colorTransform == null) {
            colorTransform = ColorUtil.getTransform(pic.getColor(), ColorSpace.RGB);
        }
        colorTransform.transform(src, bgrBuf);
        bgrSwap.transform(bgrBuf, bgrBuf);
        byte[] px = bgrBuf.getPlaneData(0);

        // 尚未初始化：把首帧交给渲染线程去建纹理（纹理注册必须是渲染线程）
        if (nbuf == 0) {
            if (initArgb == null && staged < 0) {
                int[] a = new int[tw * th];
                packBgr(px, a, tw, th);
                initW = tw;
                initH = th;
                initArgb = a;
            }
            return;
        }
        // 上一帧还没被渲染线程取走 → 直接丢这一帧。
        // 这是「防音画越拖越远」的关键：宁可丢帧也不排队，排队会让画面永久滞后于声音。
        if (staged >= 0) {
            return;
        }
        // 轮转：后台要写的那一块永远不是渲染线程正在读的 front（3 缓冲保证）
        int b = (front + 1) % NBUFS;
        fillFromBgr(imgs[b], px, tw, th);
        staged = b;
    }

    /** 旧路径（crop 封装兜底）：AWTUtil 转 BufferedImage 再逐像素填充 */
    private void legacyUpload(Picture pic, int tw, int th) {
        BufferedImage bi;
        if (tw != pic.getWidth() || th != pic.getHeight()) {
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
        if (nbuf == 0) {
            if (initArgb == null && staged < 0) {
                initW = bw;
                initH = bh;
                initArgb = argb;
            }
            return;
        }
        if (staged >= 0) {
            return;
        }
        int b = (front + 1) % NBUFS;
        fillPixels(imgs[b], argb, bw, bh);
        staged = b;
    }

    /** BGR 平面字节（jcodec -128 偏置）→ NativeImage 打包格式，零中间分配 */
    private static void fillFromBgr(NativeImage img, byte[] bgr, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int o = row * 3;
            for (int x = 0; x < w; x++, o += 3) {
                int b = (bgr[o] + 128) & 0xFF;
                int g = (bgr[o + 1] + 128) & 0xFF;
                int r = (bgr[o + 2] + 128) & 0xFF;
                img.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }
    }

    /** BGR 平面字节 → (A<<24)|(B<<16)|(G<<8)|R 打包数组（首帧给渲染线程用） */
    private static void packBgr(byte[] bgr, int[] out, int w, int h) {
        for (int i = 0, o = 0; i < w * h; i++, o += 3) {
            int b = (bgr[o] + 128) & 0xFF;
            int g = (bgr[o + 1] + 128) & 0xFF;
            int r = (bgr[o + 2] + 128) & 0xFF;
            out[i] = 0xFF000000 | (b << 16) | (g << 8) | r;
        }
    }

    /** 在 YUV 平面层做盒式降采样，写入预分配的复用目标（无新分配） */
    private static void boxAvgInto(Picture src, Picture dst) {
        int planes = Math.min(3, Math.min(src.getData().length, dst.getData().length));
        for (int plane = 0; plane < planes; plane++) {
            boxAvg(src.getPlaneData(plane), src.getPlaneWidth(plane), src.getPlaneHeight(plane),
                    dst.getPlaneData(plane), dst.getPlaneWidth(plane), dst.getPlaneHeight(plane));
        }
    }

    /** 渲染线程：把后台填好的缓冲提升为当前帧（只做 upload，不做逐像素） */
    private void promoteStaged() {
        // ① 首次：注册 NBUFS 套纹理并上传首帧
        if (nbuf == 0) {
            int[] a = initArgb;
            if (a == null) {
                return;
            }
            initArgb = null;
            int w = initW;
            int h = initH;
            imgs = new NativeImage[NBUFS];
            texs = new DynamicTexture[NBUFS];
            locs = new ResourceLocation[NBUFS];
            for (int i = 0; i < NBUFS; i++) {
                imgs[i] = new NativeImage(w, h, false);
                texs[i] = new DynamicTexture(imgs[i]);
                locs[i] = Minecraft.getInstance().getTextureManager()
                        .register("flapdisplayplus/video_" + id + "_" + i, texs[i]);
                // LINEAR 过滤：纹理被拉伸到翻牌显示面时平滑插值，消除块状模糊。
                texs[i].setFilter(true, false);
            }
            // 首帧数据已是 (A<<24)|(B<<16)|(G<<8)|R 打包格式（零分配管线产出），直接写入
            fillPacked(imgs[0], a, w, h);
            texs[0].upload();
            nbuf = NBUFS;
            front = 0;
            textureLoc = locs[0];
            width = w;
            height = h;
            MediaManager.onVideoSize(path, w, h);
            return;
        }
        // ② 常规：提升后台填好的那一块
        int s = staged;
        if (s < 0) {
            return;
        }
        staged = -1;
        try {
            texs[s].upload();
            textureLoc = locs[s];
            front = s;
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.error("[VideoPlayer] 上传帧失败: {}", path, t);
        }
    }

    /**
     * 逐像素写入 NativeImage（视频线程执行）。
     * NativeImage 本质是堆外内存，不涉及 GL 调用，因此可在任意线程读写；
     * 前提是同一块缓冲不会有并发读（由 NBUFS 轮转保证）。
     * 配色 packing 与 MediaManager 一致：setPixelRGBA 走 memPutInt(小端)，GL_RGBA 期望内存序 R,G,B,A，
     * 故 int 必须为 (A<<24)|(B<<16)|(G<<8)|R。
     */
    private static void fillPixels(NativeImage img, int[] argb, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = argb[row + x];
                int a = (c >>> 24) & 0xFF;
                int r = (c >>> 16) & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int b = c & 0xFF;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
    }

    /** 写入已按 (A<<24)|(B<<16)|(G<<8)|R 打包好的像素（零分配管线首帧用） */
    private static void fillPacked(NativeImage img, int[] packed, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                img.setPixelRGBA(x, y, packed[row + x]);
            }
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

    /** 单平面整型盒式平均下采样（0-255 值域，源/目标步长即各自 planeWidth）。FfmpegPipeDecoder 复用 */
    static void boxAvg(byte[] s, int sw, int sh, byte[] d, int dw, int dh) {
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
                    // 菜单暂停：真正挂起 —— 必须是 while 循环而非单次 wait(500)。
                    // 旧写法 wait 超时后【不等 paused 变 false 就继续解码写包】，
                    // 暂停期间音频线被反复喂数据（若线是运行态则直接出声）。
                    while (paused && !stopped) {
                        synchronized (pauseLock) {
                            if (paused && !stopped) {
                                try {
                                    pauseLock.wait(500);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
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
                        // stop()/暂停期间绝不写入：写进已停的线只是缓存，恢复时会先播出
                        // 这批过期数据（爆音/复活感）；写进刚被 stop 的运行态线同理。
                        if (stopped || paused) {
                            continue;
                        }
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

    /**
     * 打开/复用 SourceDataLine（按解码出的 PCM 格式）。stop()/暂停期间拒绝打开或启动 ——
     * 「声音复活」与「暂停期间出声」的共同封堵点：暂停时打开的线保持 stopped，
     * 由 setMenuPaused(false) 统一 start。
     */
    private void ensureLineOpen(AudioFormat jfmt) {
        if (stopped || paused || line != null || jfmt == null) {
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
            // 64KB ≈ 370ms@44.1kHz/stereo：缓冲太小时（曾用 16KB≈93ms），GC 或渲染卡顿
            // 超过 93ms 就会 line underrun → 音频「咔哒/卡顿」。加大缓冲根治。
            l.open(jf, 65536);
            synchronized (this) {
                line = l;
            }
            lineBaseMs = l.getMicrosecondPosition() / 1000;
            if (!paused) {
                l.start();
            }
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
            while (!stopped && barrierWaiting > 0) {
                // 暂停期间超时截止时间持续顺延：视频线程不能在对方（被菜单暂停的
                // 音频线程）没到场时单方重置从头播，否则恢复后音画错位
                if (paused) {
                    deadline = System.currentTimeMillis() + 5000;
                } else if (System.currentTimeMillis() >= deadline) {
                    break;
                }
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

    /**
     * 重置两个时钟基准，使下一轮从 0 开始。stop() 后禁止动作（防止把已关闭的线重新 start）。
     * 暂停期间：只停线/清缓冲/重置时钟基准，【绝不 start】—— 否则 ESC 菜单里声音会自己复活；
     * 恢复由 setMenuPaused(false) 统一 start，此时两线程都从头开始，时钟基准一致。
     */
    private void resetClocks() {
        if (stopped) {
            return;
        }
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                if (!paused) {
                    l.start();
                }
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
}
