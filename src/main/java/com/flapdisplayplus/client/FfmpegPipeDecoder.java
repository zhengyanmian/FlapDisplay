/*
 * FfmpegPipeDecoder.java
 *
 * FFmpeg 子进程管道解码后端（可选，非必需）。
 * 【借鉴成熟模组的做法】Functional TVs & Boomboxes 与 MP4 Video Player 都是
 * 「纯 Java 解码器内置兜底 + FFmpeg 外部进程升级画质/性能」的双后端结构；
 * WebDisplays/MCEF 则是内嵌 Chromium（过重，不采用）。
 *
 * 工作方式：
 *   ffmpeg -ss <sec> -i <file> -an -f rawvideo -pix_fmt yuv420p -
 * 从 stdout 读定长原始 YUV420 帧（Y 平面 + 两个半尺寸色度平面），包装成
 * jcodec Picture 交给 VideoPlayer 现有的零分配转换管线。
 *
 * 优点（对比 jcodec）：
 * - 支持 H.264/H.265/AV1 与 mkv/avi/webm/flv 等任意容器（jcodec 仅 MP4+H.264）
 * - 解码速度：720p 实时无压力，1080p/4K 也远快于纯 Java → 画面不糊不卡
 * - 原生分辨率输出
 *
 * 部署（可选）：把 ffmpeg.exe 放到以下任一位置即自动启用，否则静默退回 jcodec：
 *   1. 游戏实例目录（gameDirectory/ffmpeg.exe）
 *   2. gameDirectory/config/flapdisplayplus/ffmpeg.exe
 *   3. PATH 环境变量里的 ffmpeg
 */
package com.flapdisplayplus.client;

import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FfmpegPipeDecoder {

    /** ffmpeg stderr 里的视频流信息行：…Video: h264 (High) …1280x720 …30 fps… */
    private static final Pattern VIDEO_STREAM =
            Pattern.compile("Stream #\\d+.*?: Video: .*?(\\d{2,5})x(\\d{2,5})");
    private static final Pattern FPS = Pattern.compile("(\\d+(?:\\.\\d+)?) fps");

    /** 探测缓存：null=未探测；""=未找到；否则 exe 绝对路径 */
    private static volatile String cachedLocation;

    /** 返回 ffmpeg 可执行文件路径（找不到返回 null）。结果缓存，进程生命周期内只探测一次 */
    static String locate() {
        String c = cachedLocation;
        if (c != null) {
            return c.isEmpty() ? null : c;
        }
        synchronized (FfmpegPipeDecoder.class) {
            if (cachedLocation == null) {
                String found = doLocate();
                cachedLocation = found == null ? "" : found;
            }
            c = cachedLocation;
        }
        return c.isEmpty() ? null : c;
    }

    /** 是否有 FFmpeg 后端可用（MediaManager 用它决定扩展名清单） */
    static boolean isAvailable() {
        return locate() != null;
    }

    private static String doLocate() {
        // 1) 游戏实例目录与 config 目录
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                File[] candidates = {
                        new File(mc.gameDirectory, "ffmpeg.exe"),
                        new File(new File(new File(mc.gameDirectory, "config"), "flapdisplayplus"), "ffmpeg.exe"),
                };
                for (File f : candidates) {
                    if (f.isFile()) {
                        return f.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        // 2) PATH
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                if (dir.isBlank()) {
                    continue;
                }
                File exe = new File(dir.trim(), "ffmpeg.exe");
                if (exe.isFile()) {
                    return exe.getAbsolutePath();
                }
            }
        }
        return null;
    }

    // ============================ 实例 ============================

    private final String exe;
    private final File file;

    private Process proc;
    private InputStream video;
    private Thread errDrainer;

    public final int width;
    public final int height;
    public final double fps;
    private final long frameBytes;
    private final byte[] yBuf;
    private final byte[] uBuf;
    private final byte[] vBuf;

    private FfmpegPipeDecoder(String exe, File file, int w, int h, double fps) {
        this.exe = exe;
        this.file = file;
        this.width = w;
        this.height = h;
        this.fps = fps;
        int cw = (w + 1) / 2;
        int ch = (h + 1) / 2;
        this.frameBytes = (long) w * h + 2L * cw * ch;
        this.yBuf = new byte[w * h];
        this.uBuf = new byte[cw * ch];
        this.vBuf = new byte[cw * ch];
    }

    /** 打开并解析视频流参数；失败返回 null（调用方退回 jcodec） */
    static FfmpegPipeDecoder open(File file, String exe) {
        try {
            FfmpegPipeDecoder d = new FfmpegPipeDecoder(exe, file, -1, -1, -1);
            // 第一次 start 负责解析宽高/fps（通过一个一次性的探测进程）
            int[] wh = new int[2];
            double[] fpsOut = new double[1];
            if (!probe(exe, file, wh, fpsOut)) {
                return null;
            }
            FfmpegPipeDecoder real = new FfmpegPipeDecoder(exe, file, wh[0], wh[1], fpsOut[0]);
            if (!real.start(0)) {
                real.close();
                return null;
            }
            return real;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 探测宽高与帧率（读 stderr 横幅，超时 8 秒） */
    private static boolean probe(String exe, File file, int[] wh, double[] fpsOut) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "-hide_banner", "-nostdin",
                    "-i", file.getAbsolutePath(), "-an", "-f", "null", "-");
            pb.redirectErrorStream(false);
            p = pb.start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
            long deadline = System.currentTimeMillis() + 8000;
            int w = -1, h = -1;
            double fps = -1;
            String line;
            while (System.currentTimeMillis() < deadline && (line = r.readLine()) != null) {
                if (w < 0) {
                    Matcher m = VIDEO_STREAM.matcher(line);
                    if (m.find()) {
                        w = Integer.parseInt(m.group(1));
                        h = Integer.parseInt(m.group(2));
                    }
                }
                if (fps < 0) {
                    Matcher m = FPS.matcher(line);
                    if (m.find()) {
                        fps = Double.parseDouble(m.group(1));
                    }
                }
                if (w > 0 && fps > 0) {
                    break;
                }
            }
            p.destroy();
            if (w > 0 && h > 0) {
                wh[0] = w;
                wh[1] = h;
                fpsOut[0] = fps > 0 ? fps : 30.0;
                return true;
            }
            return false;
        } catch (Throwable t) {
            if (p != null) {
                p.destroy();
            }
            return false;
        }
    }

    /** 启动（或以 -ss 重启）解码进程，stdout 为原始 yuv420p 视频流 */
    private boolean start(double startSec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(exe, "-hide_banner", "-nostdin",
                "-ss", String.valueOf(startSec),
                "-i", file.getAbsolutePath(),
                "-an", "-sn", "-dn",
                "-f", "rawvideo", "-pix_fmt", "yuv420p", "-");
        pb.redirectErrorStream(false);
        proc = pb.start();
        video = proc.getInputStream();
        // stderr 必须持续排空，否则缓冲区写满会卡死 ffmpeg
        final InputStream es = proc.getErrorStream();
        errDrainer = new Thread(() -> {
            byte[] sink = new byte[4096];
            try {
                while (es.read(sink) >= 0) {
                    // discard
                }
            } catch (IOException ignored) {
            }
        }, "FdpFfmpegErr-" + file.getName());
        errDrainer.setDaemon(true);
        errDrainer.start();
        return true;
    }

    /** 读取下一帧（阻塞）；EOF/进程死亡返回 null */
    public Picture nextFrame() {
        try {
            if (!readFully(video, yBuf) || !readFully(video, uBuf) || !readFully(video, vBuf)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return Picture.createPicture(width, height, new byte[][]{yBuf, uBuf, vBuf}, ColorSpace.YUV420);
    }

    /** 按音频时钟跳转（杀进程 + 带 -ss 重启）。失败返回 false（外层随后会因 EOF 重开） */
    public boolean seekTo(double sec) {
        try {
            closeProc();
            return start(Math.max(0.0, sec));
        } catch (Throwable t) {
            return false;
        }
    }

    public void close() {
        closeProc();
    }

    private void closeProc() {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
        if (errDrainer != null) {
            errDrainer.interrupt();
            errDrainer = null;
        }
        video = null;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }
}
