/*
 * YtDlpResolver.java
 *
 * 站点链接解析器：调用用户本机的 yt-dlp 把「网页链接」解析成可下载直链。
 *
 * 为什么这样做（而不是在模组里解析站点）：
 * 1. 合规：模组内不实现任何站点的签名计算、防盗链参数构造或付费内容绕过逻辑；
 *    站点适配全部由 yt-dlp 完成，责任与维护都在上游。
 * 2. 可维护：站点规则几周一变，跟着 yt-dlp 升级即可，不必改模组。
 * 3. 安全：用 ProcessBuilder 传【参数数组】，不经 shell 拼接，杜绝命令注入。
 *
 * 只调用 yt-dlp 的信息提取（-j），不落盘、不下载。
 */
package com.flapdisplayplus.net;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.client.MediaManager;
import com.flapdisplayplus.config.Config;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class YtDlpResolver implements MediaResolver {

    /** 读取外部进程输出的上限，防止异常输出撑爆内存 */
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    @Override
    public String name() {
        return "yt-dlp";
    }

    @Override
    public boolean canHandle(String input) {
        // 交给管理器：只有直链解析器（扩展名/Content-Type 能认出）处理不了的 http(s) 才走到这里
        if (input == null) {
            return false;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    @Override
    public ResolvedMedia resolve(String input) throws Exception {
        String url = input.trim();
        String exe = resolveExecutable();
        if (exe == null) {
            throw new IllegalStateException("未找到 yt-dlp。请安装后重试（winget install yt-dlp），"
                    + "或在配置 media.ytdlpPath 里填写完整路径。");
        }

        int timeoutSec = Config.MEDIA_RESOLVER_TIMEOUT_SEC.get();
        // -f 优先挑单一 mp4：jcodec 只能解 MP4 容器，避免拿到需要合并的分离流
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-j");
        cmd.add("--no-warnings");
        cmd.add("--no-playlist");
        cmd.add("-f");
        cmd.add("best[ext=mp4]/best");
        cmd.add(url);

        FlapDisplayPlus.LOGGER.debug("[Resolver] 调用: {}", String.join(" ", cmd));
        ProcResult r = run(cmd, timeoutSec);

        if (r.timedOut) {
            throw new IllegalStateException("解析超时（" + timeoutSec + "秒），站点响应过慢或网络不通。");
        }
        if (r.exitCode != 0) {
            String msg = firstLines(r.err, 3);
            throw new IllegalStateException("yt-dlp 解析失败（退出码 " + r.exitCode + "）"
                    + (msg.isEmpty() ? "" : "：" + msg));
        }

        String line = firstNonEmptyLine(r.out);
        if (line == null) {
            throw new IllegalStateException("yt-dlp 未返回有效信息（可能该链接不受支持或需要登录）。");
        }

        JsonObject o;
        try {
            o = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 yt-dlp 输出：" + line.substring(0, Math.min(120, line.length())));
        }

        String direct = str(o, "url");
        if (direct == null || direct.isEmpty()) {
            throw new IllegalStateException("yt-dlp 未给出直链（该站点可能只提供分片流，需要 ffmpeg 合并）。");
        }

        String title = str(o, "title");
        String ext = str(o, "ext");
        if (ext != null && !ext.isEmpty() && !ext.startsWith(".")) {
            ext = "." + ext;
        }
        String protocol = str(o, "protocol");
        int duration = -1;
        try {
            if (o.has("duration") && !o.get("duration").isJsonNull()) {
                duration = (int) Math.round(o.get("duration").getAsDouble());
            }
        } catch (Throwable ignored) {
        }

        // 扩展名判定复用 MediaManager 的统一清单（避免两处格式定义不同步）
        boolean video = true;
        if (ext != null && !ext.isEmpty()) {
            video = MediaManager.isVideo("clip" + ext);
        }

        // 分片流：当前版本解不了，明确告知（后续可加 m3u8 合并）
        String p = protocol == null ? "" : protocol.toLowerCase(Locale.ROOT);
        if (p.contains("m3u8") || p.contains("dash")) {
            throw new IllegalStateException("该站点只提供分片流（" + protocol + "），"
                    + "当前版本尚不支持，需先用 ffmpeg 合并为 MP4。");
        }

        return new ResolvedMedia(direct, title, ext == null ? "" : ext, video,
                protocol == null ? "https" : protocol, duration);
    }

    /** 解析 yt-dlp 可执行文件路径：配置优先，其次 PATH 候选（包级可见，供管理器做可用性探测） */
    static String resolveExecutable() {
        String cfg = "";
        try {
            cfg = Config.MEDIA_YTDLP_PATH.get();
        } catch (Throwable ignored) {
        }
        if (cfg != null && !cfg.trim().isEmpty()) {
            File f = new File(cfg.trim());
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
            FlapDisplayPlus.LOGGER.warn("[Resolver] 配置的 yt-dlp 路径无效: {}", cfg);
            return null;
        }
        // 未配置：按常见名字探测（Windows 需要带 .exe）
        String[] candidates = {"yt-dlp", "yt-dlp.exe", "youtube-dl", "youtube-dl.exe"};
        for (String c : candidates) {
            if (isOnPath(c)) {
                return c;
            }
        }
        return null;
    }

    /** 粗暴但够用：用系统 where/which 判断，避免自己遍历 PATH 的编码坑 */
    private static boolean isOnPath(String name) {
        try {
            String cmd = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "where" : "which";
            Process p = new ProcessBuilder(cmd, name)
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 启动进程并限时收集输出（参数数组传参，不经 shell） */
    private static ProcResult run(List<String> cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tout = new Thread(() -> readAll(p.getInputStream(), out), "yt-dlp-out");
        Thread terr = new Thread(() -> readAll(p.getErrorStream(), err), "yt-dlp-err");
        tout.setDaemon(true);
        terr.setDaemon(true);
        tout.start();
        terr.start();

        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        ProcResult r = new ProcResult();
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(3, TimeUnit.SECONDS);
            r.timedOut = true;
        }
        r.exitCode = p.isAlive() ? -1 : p.exitValue();
        tout.join(3000);
        terr.join(3000);
        r.out = out.toString();
        r.err = err.toString();
        return r;
    }

    private static void readAll(InputStream in, StringBuilder sb) {
        try (InputStream s = in) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = s.read(buf)) > 0) {
                total += n;
                if (total > MAX_OUTPUT_BYTES) {
                    break;
                }
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String firstNonEmptyLine(String s) {
        if (s == null) {
            return null;
        }
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return null;
    }

    private static String firstLines(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (count > 0) {
                sb.append(" | ");
            }
            sb.append(t);
            if (++count >= max) {
                break;
            }
        }
        return sb.toString();
    }

    private static String str(JsonObject o, String key) {
        try {
            if (o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final class ProcResult {
        int exitCode = -1;
        boolean timedOut;
        String out = "";
        String err = "";
    }
}
