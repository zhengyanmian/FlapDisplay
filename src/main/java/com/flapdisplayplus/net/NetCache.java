/*
 * NetCache.java
 *
 * 网络媒体本地缓存：同一 URL 只下一次，之后直接复用本地文件。
 *
 * 文件命名：<url 的 SHA-1 前 20 位><扩展名>
 * 未完成下载用 .part 后缀，完成后重命名去掉，避免把半截文件当完整文件用。
 * 超出总大小上限时按「最久未访问」清理。
 */
package com.flapdisplayplus.net;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.config.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class NetCache {

    private NetCache() {
    }

    private static File baseDir;

    /** 初始化缓存目录（客户端启动时调用一次） */
    public static void init(File gameDirectory) {
        String cfg = "";
        try {
            cfg = Config.MEDIA_NET_CACHE_DIR.get();
        } catch (Throwable ignored) {
        }
        File dir;
        if (cfg != null && !cfg.trim().isEmpty()) {
            dir = new File(cfg.trim());
        } else {
            dir = new File(gameDirectory, "flap-media" + File.separator + "netcache");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            FlapDisplayPlus.LOGGER.warn("[NetCache] 缓存目录创建失败: {}", dir);
        }
        baseDir = dir;
        FlapDisplayPlus.LOGGER.info("[NetCache] 缓存目录: {}", dir);
    }

    public static File dir() {
        if (baseDir == null) {
            // 未初始化时的兜底（不应发生；init 在客户端启动时调用）
            baseDir = new File("flap-media" + File.separator + "netcache");
            baseDir.mkdirs();
        }
        return baseDir;
    }

    /**
     * URL → 缓存文件（固定路径）。
     *
     * 注意：这里刻意【不使用 .part 临时文件 + 重命名】的做法。
     * 重命名会让正在播放的文件路径失效（播放器持有旧句柄），而边下边播场景下
     * 下载可能正好在播放中完成。改为固定文件名 + 独立的 .complete 标记文件，
     * 路径全程稳定，播放与下载互不干扰。
     */
    public static File file(String url, String ext) {
        return new File(dir(), keyOf(url) + normalizeExt(ext));
    }

    /** 完整标记文件 */
    private static File completeMark(File f) {
        return new File(f.getParentFile(), f.getName() + ".complete");
    }

    /** 该缓存是否已完成（完整可用） */
    public static boolean isComplete(File f) {
        return f != null && f.isFile() && completeMark(f).isFile();
    }

    /** 标记下载完成 */
    public static synchronized void markComplete(File f) {
        try {
            File m = completeMark(f);
            if (!m.exists()) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(m)) {
                    out.write(new byte[]{1});
                }
            }
            trimIfNeeded();
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[NetCache] 写入完成标记失败: {}", t.toString());
        }
    }

    /** 已完成的缓存文件；不存在或未完成返回 null */
    public static File get(String url, String ext) {
        File f = file(url, ext);
        return isComplete(f) ? f : null;
    }

    /** 记录一次访问（更新 lastModified，供 LRU 使用） */
    public static void touch(File f) {
        if (f != null && f.isFile()) {
            long t = System.currentTimeMillis();
            if (!f.setLastModified(t)) {
                // 某些文件系统可能失败，忽略即可
            }
        }
    }

    /** 超过配置上限时，按最久未访问清理 */
    static synchronized void trimIfNeeded() {
        try {
            long maxMb = Config.MEDIA_NET_CACHE_MAX_MB.get();
            long maxBytes = maxMb * 1024L * 1024L;
            File dir = dir();
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            long total = 0;
            List<File> real = new ArrayList<>();
            for (File f : files) {
                if (f.isFile()) {
                    total += f.length();
                    real.add(f);
                }
            }
            if (total <= maxBytes) {
                return;
            }
            real.sort(Comparator.comparingLong(File::lastModified));
            for (File f : real) {
                if (total <= maxBytes) {
                    break;
                }
                long len = f.length();
                File mark = completeMark(f);
                if (f.delete()) {
                    total -= len;
                    if (mark.isFile()) {
                        mark.delete();
                    }
                }
            }
            FlapDisplayPlus.LOGGER.info("[NetCache] 缓存已清理至 {} MB 以内", maxMb);
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[NetCache] 清理失败: {}", t.toString());
        }
    }

    private static String normalizeExt(String ext) {
        if (ext == null || ext.isEmpty()) {
            return ".bin";
        }
        String e = ext.toLowerCase(Locale.ROOT);
        if (!e.startsWith(".")) {
            e = "." + e;
        }
        // 只保留字母数字，防止异常扩展名造成非法文件名
        StringBuilder sb = new StringBuilder();
        for (char c : e.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '.') {
                sb.append(c);
            }
        }
        return sb.length() <= 1 ? ".bin" : sb.toString();
    }

    /** URL 稳定哈希（SHA-1 前 20 位十六进制） */
    public static String keyOf(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10 && i < d.length; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            // 极端情况：退化成 hashCode，不影响功能
            return Integer.toHexString(url.hashCode());
        }
    }
}
