/*
 * ResolvedMedia.java
 *
 * 解析结果：把一个「用户粘贴的链接」解析成可以下载的直链 + 元信息。
 */
package com.flapdisplayplus.net;

/**
 * 解析后的可下载媒体描述。
 *
 * <p>protocol 很重要：jcodec 只能解 MP4 这类单文件容器，若站点返回 HLS(m3u8)
 * 或 DASH 分片流，下载器需要额外合并处理，不能当成普通文件下载。
 */
public final class ResolvedMedia {

    /** 可直接下载的直链 */
    public final String directUrl;
    /** 标题（可能为空） */
    public final String title;
    /** 扩展名，含点，如 ".mp4"；未知时为空串 */
    public final String ext;
    /** 是否视频（false 表示图片/动图） */
    public final boolean video;

    /**
     * 传输协议：
     * "http"/"https" —— 单文件直链，下载器可直接边下边播；
     * "m3u8"/"dash"  —— 分片流，需先合并成单文件才能解码（当前版本尚不支持）。
     */
    public final String protocol;

    /** 时长（秒），未知为 -1 */
    public final int durationSec;

    public ResolvedMedia(String directUrl, String title, String ext, boolean video,
                         String protocol, int durationSec) {
        this.directUrl = directUrl;
        this.title = title;
        this.ext = ext == null ? "" : ext;
        this.video = video;
        this.protocol = protocol == null ? "https" : protocol;
        this.durationSec = durationSec;
    }

    /** 展示名：优先标题，其次文件名，最后直链 */
    public String displayName() {
        if (title != null && !title.isEmpty()) {
            return title;
        }
        String u = directUrl;
        int q = u.indexOf('?');
        if (q > 0) {
            u = u.substring(0, q);
        }
        int slash = u.lastIndexOf('/');
        if (slash >= 0 && slash < u.length() - 1) {
            return u.substring(slash + 1);
        }
        return u;
    }

    @Override
    public String toString() {
        return "ResolvedMedia{" + displayName() + ", ext=" + ext + ", protocol=" + protocol
                + ", video=" + video + "}";
    }
}
