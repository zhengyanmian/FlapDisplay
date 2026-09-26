/*
 * DirectLinkResolver.java
 *
 * 直链解析器：处理本身就是可下载地址的输入（自己的服务器、图床、对象存储、CDN）。
 * 不需要任何外部工具，也不涉及站点解析。
 */
package com.flapdisplayplus.net;

import com.flapdisplayplus.client.MediaManager;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class DirectLinkResolver implements MediaResolver {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) FlapDisplayPlus/1.0";

    @Override
    public String name() {
        return "直链";
    }

    @Override
    public boolean canHandle(String input) {
        if (input == null) {
            return false;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    @Override
    public ResolvedMedia resolve(String input) throws Exception {
        String url = input.trim();
        String path = stripQuery(url);

        // 1) 扩展名能认出来：直接按扩展名判定类型
        if (MediaManager.isVideo(path)) {
            return new ResolvedMedia(url, fileNameOf(path), extOf(path), true, protocolOf(url), -1);
        }
        if (MediaManager.isImage(path)) {
            return new ResolvedMedia(url, fileNameOf(path), extOf(path), false, protocolOf(url), -1);
        }

        // 2) 无扩展名（很多图床/对象存储如此）：HEAD 探测 Content-Type
        ContentType ct = probeContentType(url);
        if (ct != null) {
            boolean video = ct.type != null && ct.type.startsWith("video");
            String ext = guessExt(ct.type);
            return new ResolvedMedia(url, fileNameOf(path), ext, video, protocolOf(url), -1);
        }

        // 3) 探测失败：按图片尝试。解码阶段有 magic 嗅探（WebP/PNG/JPEG）兜底，
        //    真不是图片会在解码时失败并给出明确提示。
        return new ResolvedMedia(url, fileNameOf(path), "", false, protocolOf(url), -1);
    }

    /** HEAD 请求拿 Content-Type（超时 8 秒，失败返回 null，不抛给上层） */
    private static ContentType probeContentType(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                return new ContentType(conn.getContentType(), conn.getContentLengthLong());
            }
        } catch (Throwable ignored) {
            // 有些服务器不支持 HEAD：交给上层按未知类型处理，不强求
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private static String guessExt(String contentType) {
        if (contentType == null) {
            return "";
        }
        String t = contentType.toLowerCase(Locale.ROOT);
        if (t.contains("mp4")) {
            return ".mp4";
        }
        if (t.contains("webm")) {
            return ".webm";
        }
        if (t.contains("webp")) {
            return ".webp";
        }
        if (t.contains("jpeg") || t.contains("jpg")) {
            return ".jpg";
        }
        if (t.contains("png")) {
            return ".png";
        }
        if (t.contains("gif")) {
            return ".gif";
        }
        return "";
    }

    private static String protocolOf(String url) {
        return url.toLowerCase(Locale.ROOT).startsWith("http://") ? "http" : "https";
    }

    /** 去掉 ?query 与 #fragment，仅用于扩展名/文件名判断 */
    static String stripQuery(String url) {
        String s = url;
        int h = s.indexOf('#');
        if (h > 0) {
            s = s.substring(0, h);
        }
        int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        return s;
    }

    static String extOf(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > 0 && dot > slash && dot < path.length() - 1) {
            return path.substring(dot).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            return path.substring(slash + 1);
        }
        return path;
    }

    private static final class ContentType {
        final String type;
        final long length;

        ContentType(String type, long length) {
            this.type = type;
            this.length = length;
        }
    }
}
