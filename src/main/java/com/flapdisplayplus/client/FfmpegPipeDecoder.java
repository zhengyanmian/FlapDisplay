/*
 * FfmpegPipeDecoder.java
 *
 * FFmpeg 子进程管道解码后端（可选，非必需）。
 * 【借鉴成熟模组的做法】Functional TVs & Boomboxes 与 MP4 Video Player 都是
 * 「纯 Java 解码器内置兜底 + FFmpeg 外部进程升级画质/性能」的双后端结构。
 *
 * 工作方式：
 *   ffmpeg -ss <sec> -i <file> -an -f rawvideo -pix_fmt yuv420p -
 * 从 stdout 读定长原始 YUV420 帧（Y 平面 + 两个半尺寸色度平面）。
 *
 * 【2026-09-27 重写 —— 修颜色错乱 + 转换提速】
 * - 颜色根因（离线逐像素实验实锤）：ffmpeg 输出的 yuv420p 是【标准无偏置】色度
 *   （128=中性），而 jcodec 内部 YUV420 的色度平面带【-128 偏置】（0=中性）。
 *   把 ffmpeg 数据包成 jcodec Picture 再走 ColorUtil 变换，色度整体偏移 128 →
 *   游戏里颜色大面积炸成橙/绿/青（平均逐像素误差 123/255）。
 * - 现改为自写单遍 LUT 转换：标准有限范围 YUV→RGB 矩阵，HD(高>=600) 用 BT.709、
 *   SD 用 BT.601（实验对比 ffmpeg 官方 rgb24：本视频 709 平均误差 0.60，601 为 11.58）。
 * - 同时把旧「jcodec 变换 + RgbToBgr + 逐像素填充」四遍链合并为一遍直接产出
 *   NativeImage 打包格式 (A<<24|B<<16|G<<8|R)，全程复用预分配缓冲，0 堆分配。
 *   CPU 开销约为旧链路的 1/3 —— 这是 1920 级别视频卡顿的主因之一。
 *
 * 部署（可选）：把 ffmpeg.exe 放到以下任一位置即自动启用（须 >1MB，防误判残file），
 * 否则 FfmpegAutoDownloader 会自动下载，再退回 jcodec：
 *   1. 游戏实例目录（gameDirectory/ffmpeg.exe）
 *   2. gameDirectory/config/flapdisplayplus/ffmpeg.exe
 *   3. PATH 环境变量里的 ffmpeg
 */
package com.flapdisplayplus.client;

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

    /** 自动下载完成后调用：清探测缓存，下一次播放重新探测 */
    static void invalidateLocation() {
        cachedLocation = null;
    }

    /** 是否有 FFmpeg 后端可用（MediaManager 用它决定扩展名清单） */
    static boolean isAvailable() {
        return locate() != null;
    }

    private static String doLocate() {
        // 1) 游戏实例目录与 config 目录（>1MB 防误判残缺文件）
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                File[] candidates = {
                        new File(mc.gameDirectory, "ffmpeg.exe"),
                        new File(new File(new File(mc.gameDirectory, "config"), "flapdisplayplus"), "ffmpeg.exe"),
                };
                for (File f : candidates) {
                    if (f.isFile() && f.length() > 1_000_000L) {
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
                if (exe.isFile() && exe.length() > 1_000_000L) {
                    return exe.getAbsolutePath();
                }
            }
        }
        return null;
    }

    // ============================ 颜色 LUT ============================
    // 有限范围 YUV→RGB（Y:16-235, Cb/Cr:16-240）。索引 = 无符号原始字节（0-255）。
    // MAT[0] = BT.601（SD），MAT[1] = BT.709（HD）。行序：{lum, cbB, cbG, crR, crG}

    private static final float[][] MAT = buildMats();

    private static float[][] buildMats() {
        float[][] m = new float[2][5 * 256];
        // 601
        fillMat(m[0], 1.164f, 1.596f, 0.392f, 0.813f, 2.018f);
        // 709
        fillMat(m[1], 1.164f, 1.793f, 0.213f, 0.533f, 2.112f);
        return m;
    }

    private static void fillMat(float[] t, float ky, float kr, float kbG, float krG, float kbB) {
        for (int i = 0; i < 256; i++) {
            t[i] = ky * (i - 16);               // lum
            t[256 + i] = kbB * (i - 128);       // cbB
            t[512 + i] = -kbG * (i - 128);      // cbG（对 G 的贡献为负）
            t[768 + i] = kr * (i - 128);        // crR
            t[1024 + i] = -krG * (i - 128);     // crG
        }
    }

    // ============================ 实例 ============================

    private final String exe;
    private final File file;
    /** 原始视频高度（决定 601/709 矩阵选择，不随缩放变化） */
    private final int srcHeight;

    private Process proc;
    private InputStream video;
    private Thread errDrainer;

    public final int width;
    public final int height;
    public final double fps;
    private final int cw;
    private final int ch;
    private final byte[] yBuf;
    private final byte[] uBuf;
    private final byte[] vBuf;

    /** 降采样缓冲（tw/th 变化时重建） */
    private byte[] scY;
    private byte[] scU;
    private byte[] scV;
    private int scW = -1;
    private int scH = -1;

    private FfmpegPipeDecoder(String exe, File file, int w, int h, double fps) {
        this.exe = exe;
        this.file = file;
        this.width = w;
        this.height = h;
        this.srcHeight = h;
        this.fps = fps;
        this.cw = (w + 1) / 2;
        this.ch = (h + 1) / 2;
        this.yBuf = new byte[w * h];
        this.uBuf = new byte[cw * ch];
        this.vBuf = new byte[cw * ch];
    }

    /** 打开并解析视频流参数；失败返回 null（调用方退回 jcodec） */
    static FfmpegPipeDecoder open(File file, String exe) {
        try {
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

    /** 读取下一帧原始平面（阻塞，不转换）；EOF/进程死亡返回 false */
    public boolean readFrame() {
        try {
            return readFully(video, yBuf) && readFully(video, uBuf) && readFully(video, vBuf);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 把【最近一次 readFrame 的帧】直接转成 NativeImage 打包格式写入 out
     * ((A<<24)|(B<<16)|(G<<8)|R)。tw/th 小于原生尺寸时做盒式降采样。
     * 全程复用预分配缓冲，0 堆分配。仅视频线程调用（无并发）。
     */
    public void convertLastInto(int[] out, int tw, int th) {
        byte[] y = yBuf;
        byte[] u = uBuf;
        byte[] v = vBuf;
        int cwUse = this.cw;
        if (tw != width || th != height) {
            int scw = (tw + 1) / 2;
            int sch = (th + 1) / 2;
            if (scW != tw || scH != th) {
                scY = new byte[tw * th];
                scU = new byte[scw * sch];
                scV = new byte[scw * sch];
                scW = tw;
                scH = th;
            }
            VideoPlayer.boxAvg(yBuf, width, height, scY, tw, th);
            VideoPlayer.boxAvg(uBuf, cw, ch, scU, scw, sch);
            VideoPlayer.boxAvg(vBuf, cw, ch, scV, scw, sch);
            y = scY;
            u = scU;
            v = scV;
            cwUse = scw;
        }
        convert(out, y, u, v, tw, th, cwUse);
    }

    /** 有限范围 YUV420 → 打包 ARGB（NativeImage.setPixelRGBA 内存序）。色度半分辨率按 (px>>1,py>>1) 取样 */
    private void convert(int[] out, byte[] y, byte[] u, byte[] v, int tw, int th, int cwS) {
        // 矩阵按【原始视频高度】选：HD(>=600)=BT.709，SD=BT.601（离线对比 ffmpeg 官方 rgb24 选定）
        float[] mat = MAT[srcHeight >= 600 ? 1 : 0];
        int pos = 0;
        for (int py = 0; py < th; py++) {
            int cRow = (py >> 1) * cwS;
            for (int px = 0; px < tw; px++, pos++) {
                int cIdx = cRow + (px >> 1);
                int uu = (u[cIdx] & 0xFF) + 256;
                int vv = (v[cIdx] & 0xFF) + 768;
                int yy = y[pos] & 0xFF;
                int r = (int) (mat[yy] + mat[vv]);
                int g = (int) (mat[yy] + mat[256 + uu] + mat[256 + vv]);
                int b = (int) (mat[yy] + mat[uu]);
                if (r < 0) {
                    r = 0;
                } else if (r > 255) {
                    r = 255;
                }
                if (g < 0) {
                    g = 0;
                } else if (g > 255) {
                    g = 255;
                }
                if (b < 0) {
                    b = 0;
                } else if (b > 255) {
                    b = 255;
                }
                out[pos] = 0xFF000000 | (b << 16) | (g << 8) | r;
            }
        }
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
