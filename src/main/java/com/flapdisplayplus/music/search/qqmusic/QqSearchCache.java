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

    /**
     * 【修复截断专用，勿删】用一个**被截断的 songmid 前缀**反查完整值。
     *
     * 背景：Net Music 刻录机输入框有 setMaxLength(19)，我们的标识 `qqmusic:{songmid}`
     * 是 22 字符，界面重建时会被静默切成「前缀 + 前 11 位 mid」。
     * 由于截断结果一定是完整 mid 的前缀，这里就能把完整值找回来。
     *
     * 用**前缀匹配**而不是等值：截断必然产生前缀，等值永远查不到。
     * 若命中多个（不同歌曲的 mid 恰好有前缀关系），返回最长的那一个，
     * 并记 warn 日志——这种情况极罕见，但静默选错比报错更难查。
     *
     * @param prefix 疑似被截断的 songmid（也接受完整值，完整值会被等值命中）
     * @return 完整的 songmid；查不到返回 null
     */
    public static String findFullMidByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        // 先精确命中（大部分情况下我们从没被截断，走这条）
        if (CACHE.containsKey(prefix)) {
            return prefix;
        }
        String best = null;
        for (String key : CACHE.keySet()) {
            // 只认「比候选更长且以它为前缀」的 key，即候选是它被截断的结果
            if (key.length() > prefix.length() && key.startsWith(prefix)) {
                if (best == null || key.length() > best.length()) {
                    best = key;
                }
            }
        }
        return best;
    }

    /** 调试用：返回当前缓存中的所有 key（逗号分隔） */
    public static String debugKeys() {
        return String.join(",", CACHE.keySet());
    }
}
