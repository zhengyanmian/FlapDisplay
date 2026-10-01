package com.flapdisplayplus.music.search.qqmusic;

import com.flapdisplayplus.music.MusicCompat;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.search.IMusicSearchSource;
import com.flapdisplayplus.music.search.SearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * QQ 音乐搜索源。
 *
 * CD URL 使用伪协议 qqmusic:{songmid}——真实播放地址（带 vkey）有时效性，
 * 不能写死进 CD。由 NetMusicDisplay 注册的 QQMusicUrlResolver 在播放时
 * 实时调用 GetVkeyServer 换取播放地址，保证 CD 永久有效。
 */
public class QQMusicSearchSource implements IMusicSearchSource {

    public static final String URL_PREFIX = "qqmusic:";

    @Override
    public String getPlatformId() {
        return "qqmusic";
    }

    @Override
    public String getDisplayName() {
        return "QQ音乐";
    }

    @Override
    public CompletableFuture<List<SearchResult>> search(String keyword, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<QQMusicApi.QQSong> songs = QQMusicApi.search(keyword);
                List<SearchResult> results = new ArrayList<>();
                int n = Math.min(songs.size(), limit);
                for (int i = 0; i < n; i++) {
                    QQMusicApi.QQSong song = songs.get(i);
                    SearchResult r = new SearchResult(
                            getPlatformId(),
                            song.songmid,
                            song.title,
                            song.artist,
                            song.durationSec,
                            URL_PREFIX + song.songmid,
                            song.mediaMid,
                            song.vip
                    );
                    results.add(r);
                    // 预填缓存：保证刻录机拦截制作时 QqSearchCache.get(mid) 一定命中，
                    // 不依赖 selectResult 的单独 put（避免任何单点失败导致整条链路挂掉）。
                    QqSearchCache.put(r);
                }
                return results;
            } catch (Exception e) {
                MusicCompat.LOGGER.error("[QQ搜索] 搜索失败: " + keyword, e);
                return List.of();
            }
        });
    }

    /** 从 CD URL（qqmusic:{mid}）提取 songmid；非 QQ 音乐返回 null */
    public static String extractSongmid(String cdUrl) {
        if (cdUrl != null && cdUrl.startsWith(URL_PREFIX)) {
            return cdUrl.substring(URL_PREFIX.length());
        }
        return null;
    }
}
