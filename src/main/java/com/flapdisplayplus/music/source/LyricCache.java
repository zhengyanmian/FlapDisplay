package com.flapdisplayplus.music.source;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricParser;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.google.gson.JsonObject;
import com.flapdisplayplus.music.search.qqmusic.QQMusicApi;
import com.flapdisplayplus.music.search.qqmusic.QQMusicSearchSource;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词缓存管理器。
 *
 * Net Music 的歌词原本只在客户端获取（从网易云 API 拉取 LRC 歌词，解析成 LyricRecord）。
 * 但 Create 的显示链接器是服务端方块系统，服务端拿不到客户端的歌词。
 *
 * 解决方案：服务端自己也去调歌词 API，拿到歌词后缓存起来。
 * - 第一次请求时异步获取（不阻塞服务端 tick）
 * - 后续请求直接从缓存读取
 * - 根据 CD 播放机的播放进度（currentTime）计算当前歌词行
 *
 * 支持多平台（按 CD 里的 songUrl 分发）：
 * - 网易云：music.163.com/...?id=xxx.mp3
 * - QQ 音乐：qqmusic:{songmid}（或解析后的 qqmusic.qq.com 播放 URL）
 *
 * 歌词时间轴单位是 tick（50ms），和 Minecraft tick 一致（1 秒 = 20 tick）。
 */
public class LyricCache {
    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    /** 歌词缓存：平台:歌曲标识 -> 歌词加载 Future */
    private static final ConcurrentHashMap<String, CompletableFuture<LyricRecord>> CACHE = new ConcurrentHashMap<>();

    /** 网易云歌曲 URL 格式：https://music.163.com/song/media/outer/url?id=123456.mp3 */
    private static final Pattern SONG_ID_PATTERN = Pattern.compile("^.*?\\?id=(\\d+)\\.mp3$");

    /**
     * 从 songUrl 提取网易云歌曲 ID。
     *
     * 【性能约束】本方法在服务端 tick 热路径上被调用（每个显示源每 5 秒一次，
     * 一首歌播放期间会被调用上万次）。因此：
     *  - 命中成功路径**不打日志**（曾经每次都打 info，导致服务端控制台每秒刷 2 行）
     *  - 失败路径的日志按 URL 去重，同一首歌只提示一次
     * 详细诊断请临时把日志级别调到 debug。
     *
     * @return 歌曲 ID，如果不是网易云歌曲返回 -1
     */
    public static long extractSongId(String songUrl) {
        if (songUrl == null || songUrl.isEmpty()) {
            return -1;
        }
        Matcher matcher = SONG_ID_PATTERN.matcher(songUrl);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                warnOnce(songUrl, "Failed to parse song ID from URL: " + songUrl);
                return -1;
            }
        }
        warnOnce(songUrl, "URL does not match NetEase pattern: " + songUrl);
        return -1;
    }

    /** 已警告过的 URL（避免热路径上重复打印同一条警告） */
    private static final java.util.Set<String> WARNED_URLS = ConcurrentHashMap.newKeySet();

    /** 同一 URL 只警告一次（上限 256 条，防止长期运行内存增长） */
    private static void warnOnce(String url, String message) {
        if (WARNED_URLS.size() < 256 && WARNED_URLS.add(url)) {
            LOGGER.warn("[LyricCache] {}", message);
        }
    }

    /**
     * 按 CD 的 songUrl 获取歌词（非阻塞，多平台分发）。
     *
     * @param songUrl   CD 里的歌曲 URL（网易云 / qqmusic:{songmid} / QQ 播放地址）
     * @param songName  歌曲名（用于歌词第一行补全）
     * @return LyricRecord，如果还在加载或获取失败返回 null
     */
    public static LyricRecord getLyricByUrl(String songUrl, String songName) {
        if (songUrl == null || songUrl.isEmpty()) {
            return null;
        }
        // QQ 音乐：qqmusic:{songmid}
        String qqmid = QQMusicSearchSource.extractSongmid(songUrl);
        if (qqmid != null) {
            return getOrFetch("qqmusic:" + qqmid, () -> fetchQQLyric(qqmid, songName));
        }
        // 网易云：?id=xxx.mp3
        long songId = extractSongId(songUrl);
        if (songId < 0) {
            return null;
        }
        return getOrFetch("netease:" + songId, () -> fetchNeteaseLyric(songId, songName));
    }

    /**
     * 获取网易云歌词（兼容旧调用）。
     *
     * @param songId   网易云歌曲 ID
     * @param songName 歌曲名
     * @return LyricRecord，如果还在加载或获取失败返回 null
     */
    public static LyricRecord getLyric(long songId, String songName) {
        if (songId < 0) return null;
        return getOrFetch("netease:" + songId, () -> fetchNeteaseLyric(songId, songName));
    }

    /**
     * 通用缓存获取：首次异步拉取，失败结果不缓存（下次重试）。
     *
     * 【性能约束】本方法同样在服务端 tick 热路径上。失败结果会被移除以便重试，
     * 而每次失败都会记一条日志 —— 若歌曲确实没有歌词，就会变成「每 5 秒一条」的永久刷屏。
     * 因此失败日志改为按 key 去重（每首歌只报一次，但仍保持重试行为不变）。
     */
    private static LyricRecord getOrFetch(String key, java.util.function.Supplier<LyricRecord> fetcher) {
        CompletableFuture<LyricRecord> future = CACHE.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(fetcher)
        );
        if (future.isDone()) {
            try {
                LyricRecord result = future.get();
                if (result == null) {
                    CACHE.remove(key, future);
                    warnOnce("fetch-null:" + key,
                            "Lyric fetch returned null, will retry. Key: " + key);
                }
                return result;
            } catch (Exception e) {
                CACHE.remove(key, future);
                // 异常对象保留完整堆栈（只报一次），便于定位真实故障
                if (ERRORED_KEYS.add(key)) {
                    LOGGER.error("[LyricCache] Exception getting lyric result, removed from cache. Key: " + key, e);
                }
                return null;
            }
        }
        return null;
    }

    /** 已报过异常的 key（异常含堆栈，代价高，单独去重） */
    private static final java.util.Set<String> ERRORED_KEYS = ConcurrentHashMap.newKeySet();

    /**
     * 拉取并解析网易云歌词（在异步线程中执行）。
     *
     * 【性能约束】本方法在「取不到歌词」时会被反复重试（见 getOrFetch），
     * 因此**所有日志都走 warnOnce 按 key 去重**，否则一首无歌词的歌会永久刷屏。
     */
    private static LyricRecord fetchNeteaseLyric(long songId, String songName) {
        String key = "netease:" + songId;
        try {
            if (NetMusic.NET_EASE_WEB_API == null) {
                warnOnce(key, "NET_EASE_WEB_API is null! NetMusic mod may not be fully initialized.");
                return null;
            }
            String json = NetMusic.NET_EASE_WEB_API.lyric(songId);
            if (json == null || json.isEmpty()) {
                warnOnce(key, "API returned empty response for song ID: " + songId);
                return null;
            }
            LyricRecord record = LyricParser.parseLyric(json, songName);
            if (record == null) {
                warnOnce(key, "LyricParser returned null for song ID: " + songId);
            }
            return record;
        } catch (Exception e) {
            if (ERRORED_KEYS.add(key)) {
                LOGGER.error("[LyricCache] Failed to fetch NetEase lyric for song ID: " + songId, e);
            }
            return null;
        }
    }

    /**
     * 拉取并解析 QQ 音乐歌词（在异步线程中执行；QQ 接口返回 base64 的 LRC，
     * QQMusicApi.getLyric 已解码成明文，这里转成网易云格式喂 LyricParser）。
     * 日志同样按 key 去重（理由见 fetchNeteaseLyric）。
     *
     * 翻译歌词：QQ 的 PlayLyricInfo 与主歌词同接口返回 trans 字段
     * （2026-08-28 实测接口通；中文歌通常无翻译，故为空是正常情况而非故障）。
     */
    private static LyricRecord fetchQQLyric(String songmid, String songName) {
        String key = "qqmusic:" + songmid;
        try {
            String lrc = QQMusicApi.getLyric(songmid);
            if (lrc == null) {
                warnOnce(key, "QQ API returned empty lyric for songmid: " + songmid);
                return null;
            }
            // 翻译歌词：拿不到不影响主歌词，静默降级
            String transLrc = QQMusicApi.getTransLyric(songmid);

            JsonObject root = new JsonObject();
            root.addProperty("code", 200);
            JsonObject lrcObj = new JsonObject();
            lrcObj.addProperty("lyric", lrc);
            root.add("lrc", lrcObj);
            JsonObject tlyric = new JsonObject();
            tlyric.addProperty("lyric", transLrc == null ? "" : transLrc);
            root.add("tlyric", tlyric);
            LyricRecord record = LyricParser.parseLyric(root.toString(), songName);
            if (record == null) {
                warnOnce(key, "LyricParser returned null for QQ songmid: " + songmid);
            }
            return record;
        } catch (Exception e) {
            if (ERRORED_KEYS.add(key)) {
                LOGGER.error("[LyricCache] Failed to fetch QQ lyric for songmid: " + songmid, e);
            }
            return null;
        }
    }

    /**
     * 线程安全地查找当前原歌词行（只读，不修改 LyricRecord 数据）。
     *
     * 注意：LyricRecord.updateCurrentLine() 是破坏性操作（会移除已过的歌词行），
     * 在多线程环境下使用会导致数据损坏。这里用只读遍历替代。
     *
     * @param record      歌词记录
     * @param playedTicks 已播放的 tick 数
     * @return 当前原歌词行文本，如果没有匹配的返回 null
     */
    public static String getCurrentLyricLine(LyricRecord record, int playedTicks) {
        if (record == null) return null;
        return findCurrentLine(record.getLyrics(), playedTicks);
    }

    /**
     * 线程安全地查找当前翻译歌词行（只读，不修改 LyricRecord 数据）。
     *
     * @param record      歌词记录
     * @param playedTicks 已播放的 tick 数
     * @return 当前翻译歌词行文本；如果该歌曲没有翻译歌词返回 null
     */
    public static String getCurrentTransLyricLine(LyricRecord record, int playedTicks) {
        if (record == null) return null;
        Int2ObjectSortedMap<String> transLyrics = record.getTransLyrics();
        if (transLyrics == null || transLyrics.isEmpty()) return null;
        return findCurrentLine(transLyrics, playedTicks);
    }

    /**
     * 判断歌词记录是否包含翻译歌词。
     */
    public static boolean hasTranslation(LyricRecord record) {
        return record != null && record.getTransLyrics() != null && !record.getTransLyrics().isEmpty();
    }

    /**
     * 通用：在按 tick 升序排列的歌词 map 中，查找 <= playedTicks 的最大 key 对应的歌词行。
     * 只读遍历，不修改数据，线程安全。
     */
    private static String findCurrentLine(Int2ObjectSortedMap<String> lyrics, int playedTicks) {
        if (lyrics == null || lyrics.isEmpty()) return null;
        String result = null;
        for (Int2ObjectMap.Entry<String> entry : lyrics.int2ObjectEntrySet()) {
            if (entry.getIntKey() <= playedTicks) {
                result = entry.getValue();
            } else {
                break; // 后面的 key 都更大，不需要继续
            }
        }
        return result;
    }
}
