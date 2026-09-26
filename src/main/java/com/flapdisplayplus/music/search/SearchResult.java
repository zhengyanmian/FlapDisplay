package com.flapdisplayplus.music.search;

/**
 * 搜索结果（跨平台统一结构）。
 *
 * @param platform     搜索源 ID（netease / qqmusic / kugou ...）
 * @param songId       平台内歌曲标识（网易云数字 ID / QQ 音乐 songmid / 酷狗 hash）
 * @param title        歌曲名
 * @param artist       歌手名（多个用 / 分隔）
 * @param durationSec  时长（秒）
 * @param cdUrl        写入 CD 的 URL（可为平台伪协议，如 qqmusic:{songmid}，播放时由 resolver 实时解析）
 */
public record SearchResult(
        String platform,
        String songId,
        String title,
        String artist,
        int durationSec,
        String cdUrl,
        String mediaMid
) {
    /** 写入刻录机名字框的显示名：歌名 - 歌手 */
    public String displayName() {
        String artistText = (artist == null || artist.isBlank()) ? "未知歌手" : artist;
        return title + " - " + artistText;
    }

    /** 时长格式化 mm:ss */
    public String durationText() {
        int m = durationSec / 60;
        int s = durationSec % 60;
        return m + ":" + (s < 10 ? "0" + s : s);
    }
}
