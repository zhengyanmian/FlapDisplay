/*
 * FfmpegAutoDownloader.java
 *
 * FFmpeg 解码器缺失时自动下载（借鉴 Functional TVs & Boomboxes 的做法；
 * npmmirror 的 ffmpeg-static 包本身在首次使用时还会下载 yt-dlp/Deno 做校验，我们直接取其 Windows x64 构建）。
 *
 * - 触发：MediaManager.tick 每刻调用 ensureDownloaded()（幂等、探测走缓存，开销可忽略）。
 * - 下载源：npmmirror（registry.npmmirror.com）镜像的 ffmpeg-static b6.0 win32-x64，
 *   gzip 压缩约 29MB → 解压后约 81MB，落盘到 gameDirectory/ffmpeg.exe（模组首选探测位）。
 * - 失败不影响游戏与播放：静默退回内置 jcodec 解码器，本次会话不再重试。
 * - 下载完成后 invalidateLocation()，正在用 jcodec 的视频会在下一轮循环自动切到 FFmpeg。
 */
package com.flapdisplayplus.client;

import com.flapdisplayplus.FlapDisplayPlus;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

final class FfmpegAutoDownloader {

    private static final String URL =
            "https://registry.npmmirror.com/-/binary/ffmpeg-static/b6.0/ffmpeg-win32-x64.gz";
    /** 下载最小字节数校验（真实包约 29MB 压缩） */
    private static final long MIN_BYTES = 10_000_000L;

    private static volatile boolean started;

    private FfmpegAutoDownloader() {
    }

    /** 在聊天框提示（下载线程发起，切回主线程执行；任何异常静默忽略，不影响下载） */
    private static void chat(String text, ChatFormatting color) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.gui == null) {
                return;
            }
            mc.execute(() -> mc.gui.getChat().addMessage(
                    Component.literal("[翻牌万象] ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(text).withStyle(color))));
        } catch (Throwable ignored) {
        }
    }

    /** 幂等触发：没有 ffmpeg 才启动一次后台下载线程；有则什么都不做 */
    static void ensureDownloaded() {
        if (started) {
            return;
        }
        if (FfmpegPipeDecoder.isAvailable()) {
            started = true;
            return;
        }
        // 进入世界后才触发：主菜单时 mc.gui 为 null，聊天提示会被静默丢掉
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        synchronized (FfmpegAutoDownloader.class) {
            if (started) {
                return;
            }
            started = true;
        }
        Thread t = new Thread(FfmpegAutoDownloader::run, "FdpFfmpegDownloader");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        File tmp = null;
        try {
            if (FfmpegPipeDecoder.locate() != null) {
                return; // 双重检查（竞态期间别人可能已放置）
            }
            File gameDir = MinecraftGameDir.get();
            if (gameDir == null) {
                return;
            }
            File target = new File(gameDir, "ffmpeg.exe");
            tmp = new File(gameDir, "ffmpeg.exe.downloading");
            FlapDisplayPlus.LOGGER.info(
                    "[Media] 未检测到 FFmpeg 解码器，开始自动下载（约29MB，npmmirror 镜像）→ {}",
                    target.getAbsolutePath());
            chat("未检测到 FFmpeg 解码器，开始自动下载（约29MB）…", ChatFormatting.YELLOW);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "flapdisplayplus")
                    .GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                FlapDisplayPlus.LOGGER.warn("[Media] FFmpeg 下载失败：HTTP {}（不影响播放）", resp.statusCode());
                chat("FFmpeg 下载失败：HTTP " + resp.statusCode() + "（不影响播放）", ChatFormatting.RED);
                return;
            }
            long total = 0;
            long nextLog = 0;
            try (InputStream in = new GZIPInputStream(resp.body());
                 OutputStream out = Files.newOutputStream(tmp.toPath())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total >= nextLog) {
                        FlapDisplayPlus.LOGGER.info("[Media] FFmpeg 下载中… {}MB", total >> 20);
                        nextLog += 5L * 1024 * 1024;
                    }
                }
            }
            if (total < MIN_BYTES) {
                FlapDisplayPlus.LOGGER.warn("[Media] FFmpeg 下载不完整（{}字节），放弃（不影响播放）", total);
                chat("FFmpeg 下载不完整，已放弃（不影响播放）", ChatFormatting.RED);
                Files.deleteIfExists(tmp.toPath());
                return;
            }
            // 目标可能存在残缺文件：先删再原子改名
            Files.deleteIfExists(target.toPath());
            try {
                Files.move(tmp.toPath(), target.toPath());
            } catch (Throwable moveErr) {
                // Windows 偶发 rename 失败：退回复制
                Files.copy(tmp.toPath(), target.toPath());
                Files.deleteIfExists(tmp.toPath());
            }
            FfmpegPipeDecoder.invalidateLocation();
            FlapDisplayPlus.LOGGER.info(
                    "[Media] FFmpeg 解码器下载完成（{}MB）。正在播放的视频将在下一轮循环自动切换到 FFmpeg 后端",
                    total >> 20);
            chat("FFmpeg 下载完成（" + (total >> 20) + "MB），视频画质将自动提升", ChatFormatting.GREEN);
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[Media] FFmpeg 自动下载失败（不影响播放，仍用内置解码器）: {}",
                    t.toString());
            chat("FFmpeg 自动下载失败（不影响播放）", ChatFormatting.RED);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp.toPath());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 游戏目录（独立小类避免在非客户端环境类加载 Minecraft） */
    private static final class MinecraftGameDir {
        static File get() {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                return mc != null ? mc.gameDirectory : null;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
