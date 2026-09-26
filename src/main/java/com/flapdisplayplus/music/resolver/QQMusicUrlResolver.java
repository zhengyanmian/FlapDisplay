package com.flapdisplayplus.music.resolver;

import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.flapdisplayplus.music.search.qqmusic.QQMusicApi;
import com.flapdisplayplus.music.search.qqmusic.QQMusicSearchSource;

import java.util.concurrent.CompletableFuture;

/**
 * QQ 音乐播放地址解析器。
 *
 * 注册进 Net Music 的 MusicPlayResolverManager。CD 里存的是伪 URL
 * （qqmusic:{songmid}），播放时这里实时调用 GetVkeyServer 换取带 vkey 的
 * 真实播放地址（试听，免登录）。
 *
 * 优先级比 Net Music 默认解析器低——只在我们的 qqmusic: 前缀 URL 上命中，
 * 不影响网易云等其他来源。
 */
public class QQMusicUrlResolver implements IAsyncSongUrlResolver {

    @Override
    public boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        return songInfo != null && songInfo.songUrl != null
                && songInfo.songUrl.startsWith(QQMusicSearchSource.URL_PREFIX);
    }

    @Override
    public CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        String songmid = QQMusicSearchSource.extractSongmid(songInfo.songUrl);
        if (songmid == null) {
            return CompletableFuture.completedFuture(songInfo);
        }
        return QQMusicApi.getPlayUrl(songmid).thenApply(playUrl -> {
            if (playUrl == null) {
                return songInfo; // 换 URL 失败，保持原样（播放会失败，但不会崩）
            }
            // SongInfo 实现了 Cloneable，克隆后只改播放地址
            ItemMusicCD.SongInfo resolved = (ItemMusicCD.SongInfo) songInfo.clone();
            resolved.songUrl = playUrl;
            return resolved;
        });
    }

    @Override
    public int getPriority() {
        return -100; // 低于 Net Music 自带的解析器
    }
}
