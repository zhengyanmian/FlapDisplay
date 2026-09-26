package com.flapdisplayplus.music.search.qqmusic;

import com.flapdisplayplus.music.search.SearchResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QQ 搜索结果缓存。搜索点选时把结果按 songmid 暂存，刻录机「制作」拦截时
 * 直接读取歌名/时长，避免制作流程里再做一次网络请求卡住客户端主线程。
 */
public final class QqSearchCache {

    private static final Map<String, SearchResult> CACHE = new ConcurrentHashMap<>();

    private QqSearchCache() {
    }

    public static void put(SearchResult result) {
        if (result != null && result.songId() != null) {
            CACHE.put(result.songId(), result);
        }
    }

    public static SearchResult get(String songmid) {
        return CACHE.get(songmid);
    }

    /** 调试用：返回当前缓存中的所有 key（逗号分隔） */
    public static String debugKeys() {
        return String.join(",", CACHE.keySet());
    }
}
