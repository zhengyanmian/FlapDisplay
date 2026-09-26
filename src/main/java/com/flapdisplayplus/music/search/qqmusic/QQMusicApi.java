/*
 * QQMusicApi.java
 *
 * QQ 音乐公开 API（免登录可用）。
 *
 * 搜索 / 播放 URL 解析逻辑移植自开源项目 NetMusicCanNeedQQ
 * (https://github.com/Yincmewy/NetMusicCanNeedQQ) 原作者 Yincmewy（BSD-3-Clause）。
 * 主要变化：HTTP 层改为调用本模组的 HttpUtil（统一走 Net Music 的代理设置），
 * 并补充了歌词接口。
 *
 * - 搜索：GET c.y.qq.com/soso/fcgi-bin/client_search_cp（老接口，2026 实测可用；
 *        新版 musicu.fcg 的桌面搜索协议已返回空列表）
 * - 歌词：GET c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg（nobase64 明文 LRC）
 * - 播放换 vkey：POST u.y.qq.com/cgi-bin/musicu.fcg（vkey.GetVkeyServer）
 *        注意：vkey 在未登录时对所有歌曲返回空 purl（版权/鉴权限制 104003/101404），
 *        需实现 QQ 登录拿到 cookie 后才能换到真实可播放地址。
 */
package com.flapdisplayplus.music.search.qqmusic;

import com.flapdisplayplus.music.MusicNetIntegration;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.flapdisplayplus.music.HttpUtil;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.data.SongInfoData;
import com.flapdisplayplus.music.qq.QqCredentialManager;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class QQMusicApi {

    /** 搜索接口：老版 client_search_cp（2026 实测可用；新版 musicu.fcg 的桌面搜索协议返回空列表） */
    private static final String SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
    /** 详情 / vkey 换真实播放地址接口 */
    private static final String API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String DEFAULT_SIP = "http://ws.stream.qqmusic.qq.com/";

    private static final FileCandidate[] QUALITY_CANDIDATES = new FileCandidate[]{
            new FileCandidate("F000", "flac"),
            new FileCandidate("M800", "mp3"),
            new FileCandidate("M500", "mp3"),
            new FileCandidate("RS02", "mp3"),
            new FileCandidate("C600", "m4a"),
            new FileCandidate("C400", "m4a"),
            new FileCandidate("C200", "m4a"),
            new FileCandidate("C100", "m4a")
    };

    private QQMusicApi() {
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Referer", "https://y.qq.com/");
        // 已登录则附带登录 cookie，换到的 vkey 才能返回真实可播放地址
        String cookie = QqCredentialManager.getEffectiveCookie();
        if (!cookie.isEmpty()) {
            h.put("Cookie", cookie);
        }
        return h;
    }

    /** QQ 单首歌的搜索结果（不含播放 URL，刻录时才换 vkey） */
    public static final class QQSong {
        public final String songmid;
        public final String title;
        public final String artist;
        public final int durationSec;
        public final boolean vip;
        public final String mediaMid;

        public QQSong(String songmid, String title, String artist, int durationSec, boolean vip, String mediaMid) {
            this.songmid = songmid;
            this.title = title;
            this.artist = artist;
            this.durationSec = durationSec;
            this.vip = vip;
            this.mediaMid = mediaMid;
        }
    }

    // ============ 搜索 ============

    public static List<QQSong> search(String query) throws Exception {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        String url = SEARCH_URL + "?p=1&n=10&w=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&new_json=1&format=json";
        String response = HttpUtil.get(url, baseHeaders());
        try {
            JsonObject tree = JsonParser.parseString(response).getAsJsonObject();
            if (tree.has("code") && tree.get("code").getAsInt() != 0) {
                return Collections.emptyList();
            }
            JsonArray list = tree.getAsJsonObject("data")
                    .getAsJsonObject("song")
                    .getAsJsonArray("list");
            List<QQSong> results = new ArrayList<>();
            for (JsonElement el : list) {
                JsonObject song = el.getAsJsonObject();
                String mid = song.get("mid").getAsString();
                String name = song.get("name").getAsString();
                boolean vip = song.has("pay") && song.getAsJsonObject("pay").has("pay_play")
                        && song.getAsJsonObject("pay").get("pay_play").getAsInt() == 1;
                JsonArray singers = song.getAsJsonArray("singer");
                StringBuilder singer = new StringBuilder();
                for (JsonElement s : singers) {
                    if (singer.length() > 0) {
                        singer.append("/");
                    }
                    singer.append(s.getAsJsonObject().get("name").getAsString());
                }
                int interval = song.has("interval") ? song.get("interval").getAsInt() : 0;
                String mediaMid = "";
                if (song.has("file")) {
                    JsonObject file = song.getAsJsonObject("file");
                    if (file.has("media_mid")) {
                        mediaMid = file.get("media_mid").getAsString();
                    } else if (file.has("strMediaMid")) {
                        mediaMid = file.get("strMediaMid").getAsString();
                    }
                }
                results.add(new QQSong(mid, name, singer.toString(), interval, vip, mediaMid));
            }
            return results;
        } catch (RuntimeException e) {
            MusicNetIntegration.LOGGER.error("[QQ搜索] 解析失败: " + response, e);
            return Collections.emptyList();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ============ 解析真实播放 URL ============

    /**
     * 根据 songmid 异步解析出可播放的真实 URL（带 vkey 的试听地址）。
     * 由播放链路上的 QQMusicUrlResolver 调用，失败返回 null。
     */
    public static CompletableFuture<String> getPlayUrl(String songmid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return resolvePlayUrl(songmid);
            } catch (Exception e) {
                MusicNetIntegration.LOGGER.error("[QQ解析] 获取播放地址失败: " + songmid, e);
                return null;
            }
        });
    }

    /**
     * 同步版：根据 songmid 解析出可播放的真实 URL（阻塞当前线程）。
     * 供刻录机 QQ 制作时调用——在 Render 线程同步请求 vkey 接口拿真实地址。
     * 失败返回 null（通常是因为未登录 QQ，vkey 返回空 purl / error 104003）。
     */
    public static String getPlayUrlSync(String songmid) {
        try {
            return resolvePlayUrl(songmid);
        } catch (Exception e) {
            MusicNetIntegration.LOGGER.error("[QQ解析] 同步获取播放地址失败: " + songmid, e);
            return null;
        }
    }

    /**
     * 根据 songmid 解析出可播放的真实 URL + 歌名 + 时长。
     * 通过 vkey.GetVkeyServer 换取 purl，拼接 sip 得到完整地址。
     */
    public static SongInfoData resolveSong(String songmid) throws Exception {
        if (songmid == null || songmid.isBlank()) {
            return null;
        }
        TrackInfo info = getTrackInfoByMid(songmid);
        String playUrl = resolvePlayUrl(songmid);
        if (playUrl == null) {
            return null;
        }
        SongInfoData data = new SongInfoData();
        data.songUrl = playUrl;
        data.songName = info.songName;
        data.songTime = info.interval;
        data.vip = info.vip;
        return data;
    }

    /** 解析出可播放的真实 URL（不带歌名/时长），失败返回 null */
    private static String resolvePlayUrl(String songmid) throws Exception {
        // 诊断：cookie 状态
        String cookie = QqCredentialManager.getEffectiveCookie();
        MusicNetIntegration.LOGGER.info("[QQ解析] resolvePlayUrl 开始 mid={} cookie长度={}", songmid, cookie.length());

        // 优先从搜索缓存取 media_mid（搜索接口直接返回，无需再调详情接口）
        String mediaMid = null;
        com.flapdisplayplus.music.search.SearchResult cached = QqSearchCache.get(songmid);
        MusicNetIntegration.LOGGER.info("[QQ解析] 缓存查询 mid={} → cached={} durationSec={} mediaMid={}",
                songmid, cached != null,
                cached != null ? cached.durationSec() : -1,
                cached != null ? cached.mediaMid() : "N/A");
        if (cached != null && cached.mediaMid() != null && !cached.mediaMid().isBlank()) {
            mediaMid = cached.mediaMid();
            MusicNetIntegration.LOGGER.info("[QQ解析] 使用搜索缓存 media_mid：{} -> {}", songmid, mediaMid);
        }
        // 缓存没有则 fallback 调详情接口
        if (mediaMid == null || mediaMid.isBlank()) {
            TrackInfo info = getTrackInfoByMid(songmid);
            mediaMid = (info.mediaMid == null || info.mediaMid.isBlank()) ? songmid : info.mediaMid;
            MusicNetIntegration.LOGGER.info("[QQ解析] fallback 详情接口 media_mid={}", mediaMid);
        }

        JsonObject vkeyData = requestVkeyData(songmid, mediaMid);

        // 诊断：vkey 原始响应摘要
        int sipCount = vkeyData.has("sip") ? vkeyData.getAsJsonArray("sip").size() : 0;
        int midurlinfoCount = vkeyData.has("midurlinfo") ? vkeyData.getAsJsonArray("midurlinfo").size() : 0;
        MusicNetIntegration.LOGGER.info("[QQ解析] vkey 响应: sip.size={} midurlinfo.size={}", sipCount, midurlinfoCount);

        String baseUrl = resolveBaseUrl(vkeyData);
        String purl = selectBestPurl(vkeyData.getAsJsonArray("midurlinfo"));
        if (purl == null || purl.isBlank()) {
            // 打印每个 midurlinfo 的详细信息便于排查
            if (vkeyData.has("midurlinfo")) {
                JsonArray arr = vkeyData.getAsJsonArray("midurlinfo");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject info = arr.get(i).getAsJsonObject();
                    String filename = info.has("filename") && !info.get("filename").isJsonNull()
                            ? info.get("filename").getAsString() : "?";
                    String purlVal = info.has("purl") && !info.get("purl").isJsonNull()
                            ? info.get("purl").getAsString() : "null";
                    if (purlVal.length() > 60) purlVal = purlVal.substring(0, 60);
                    if (purlVal.isBlank()) purlVal = "空";
                    int errcode = info.has("errcode") && !info.get("errcode").isJsonNull()
                            ? info.get("errcode").getAsInt() : -1;
                    MusicNetIntegration.LOGGER.warn("[QQ解析] midurlinfo[{}]: filename={} purl={} errcode={}",
                            i, filename, purlVal, errcode);
                }
            }
            return null;
        }
        return baseUrl + purl;
    }

    /**
     * 仅取歌曲时长（秒），不需要 vkey/登录（getTrackInfoByMid 只拉详情）。
     * 供刻录机 QQ 制作时做时长兜底：若搜索缓存时长为 0 或缓存未命中，
     * 同步补查一次真实时长，避免 SongInfoData 因 songTime=0 被判非法而做不出唱片。
     * 失败返回 0。
     */
    public static int getDurationSec(String songmid) {
        QQSong detail = getSongDetail(songmid);
        return detail != null ? detail.durationSec : 0;
    }

    /**
     * 同步获取单首 QQ 歌曲的元数据（歌名、歌手、时长、VIP）。
     * 不需要 vkey/登录。失败返回 null。
     * 供刻录机在搜索缓存未命中时补取完整信息。
     */
    public static QQSong getSongDetail(String songmid) {
        if (songmid == null || songmid.isBlank()) {
            return null;
        }
        try {
            TrackInfo info = getTrackInfoByMid(songmid);
            if (info.interval <= 0 && info.songName.isEmpty()) {
                // API 返回了空结果（可能 songmid 无效或已下架）
                MusicNetIntegration.LOGGER.warn("[QQ解析] 歌曲详情为空（可能已下架）：mid={}", songmid);
                return null;
            }
            return new QQSong(songmid, info.songName, "", info.interval, info.vip, "");
        } catch (Exception e) {
            MusicNetIntegration.LOGGER.error("[QQ解析] 获取歌曲详情失败: " + songmid, e);
            return null;
        }
    }

    private static TrackInfo getTrackInfoByMid(String mid) {
        try {
            String body = "{\"req_1\":{\"module\":\"music.pf_song_detail_svr\","
                    + "\"method\":\"get_song_detail\","
                    + "\"param\":{\"song_mid\":\"" + escapeJson(mid) + "\",\"song_id\":0},"
                    + "\"loginUin\":\"0\",\"comm\":{\"uin\":\"0\",\"format\":\"json\",\"ct\":24,\"cv\":0}}}";
            String response = HttpUtil.postJson(API_URL, body, baseHeaders());
            JsonObject tree = JsonParser.parseString(response).getAsJsonObject();
            JsonObject trackInfo = tree.getAsJsonObject("req_1")
                    .getAsJsonObject("data")
                    .getAsJsonObject("track_info");
            String name = trackInfo.get("name").getAsString();
            int interval = trackInfo.get("interval").getAsInt();
            boolean vip = false;
            if (trackInfo.has("pay")) {
                JsonObject pay = trackInfo.getAsJsonObject("pay");
                if (pay.has("pay_play")) {
                    vip = pay.get("pay_play").getAsInt() == 1;
                }
            }
            String mediaMid = "";
            if (trackInfo.has("file")) {
                JsonObject file = trackInfo.getAsJsonObject("file");
                if (file.has("media_mid")) {
                    mediaMid = file.get("media_mid").getAsString();
                }
            }
            return new TrackInfo(name, interval, mediaMid, vip);
        } catch (Exception e) {
            MusicNetIntegration.LOGGER.error("[QQ解析] 获取歌曲详情失败: " + mid, e);
            return new TrackInfo("", 0, "", false);
        }
    }

    private static JsonObject requestVkeyData(String songMid, String mediaMid) throws Exception {
        JsonArray filenameList = new JsonArray();
        JsonArray songMidList = new JsonArray();
        JsonArray songTypeList = new JsonArray();
        for (FileCandidate c : QUALITY_CANDIDATES) {
            filenameList.add(c.buildFilename(mediaMid));
            songMidList.add(songMid);
            songTypeList.add(0);
        }
        JsonObject param = new JsonObject();
        param.add("filename", filenameList);
        param.addProperty("guid", "10000");
        param.add("songmid", songMidList);
        param.add("songtype", songTypeList);
        param.addProperty("uin", "0");
        param.addProperty("loginflag", 1);
        param.addProperty("platform", "20");

        JsonObject req = new JsonObject();
        req.addProperty("module", "vkey.GetVkeyServer");
        req.addProperty("method", "CgiGetVkey");
        req.add("param", param);

        JsonObject comm = new JsonObject();
        comm.addProperty("uin", "0");
        comm.addProperty("format", "json");
        comm.addProperty("ct", 24);
        comm.addProperty("cv", 0);

        JsonObject body = new JsonObject();
        body.add("req_1", req);
        body.addProperty("loginUin", "0");
        body.add("comm", comm);

        String response = HttpUtil.postJson(API_URL, body.toString(), baseHeaders());
        // 诊断：转储 vkey 原始响应（截断至 2000 字符）
        String dump = response.length() > 2000 ? response.substring(0, 2000) : response;
        MusicNetIntegration.LOGGER.info("[QQ解析] vkey 原始响应: {}", dump);
        JsonObject tree = JsonParser.parseString(response).getAsJsonObject();
        if (tree.has("code") && tree.get("code").getAsInt() != 0) {
            throw new RuntimeException("vkey 请求失败");
        }
        return tree.getAsJsonObject("req_1").getAsJsonObject("data");
    }

    private static String resolveBaseUrl(JsonObject data) {
        if (data != null && data.has("sip")) {
            JsonArray sip = data.getAsJsonArray("sip");
            if (sip != null && !sip.isEmpty()) {
                String value = sip.get(0).getAsString();
                if (value != null && !value.isBlank()) {
                    return value.endsWith("/") ? value : value + "/";
                }
            }
        }
        return DEFAULT_SIP;
    }

    private static String selectBestPurl(JsonArray midurlinfo) {
        if (midurlinfo == null) {
            return "";
        }
        for (int i = 0; i < midurlinfo.size(); i++) {
            JsonObject info = midurlinfo.get(i).getAsJsonObject();
            if (info != null && info.has("purl")) {
                String purl = info.get("purl").getAsString();
                if (purl != null && !purl.isBlank()) {
                    return purl;
                }
            }
        }
        return "";
    }

    // ============ 歌词 ============

    /** 返回明文 LRC 文本；失败返回 null */
    public static String getLyric(String songmid) {
        try {
            String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + songmid
                    + "&format=json&nobase64=1&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8"
                    + "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0";
            String body = HttpUtil.get(url, baseHeaders());
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("retcode") && root.get("retcode").getAsInt() != 0) {
                return null;
            }
            if (root.has("lyric")) {
                String lyric = root.get("lyric").getAsString();
                return lyric.isBlank() ? null : lyric;
            }
        } catch (Exception e) {
            MusicNetIntegration.LOGGER.error("[QQ歌词] 获取失败: " + songmid, e);
        }
        return null;
    }

    // ============ 内部类型 ============

    private static final class FileCandidate {
        private final String prefix;
        private final String extension;

        FileCandidate(String prefix, String extension) {
            this.prefix = prefix;
            this.extension = extension;
        }

        String buildFilename(String mediaMid) {
            return prefix + mediaMid + "." + extension;
        }
    }

    private static final class TrackInfo {
        final String songName;
        final int interval;
        final String mediaMid;
        final boolean vip;

        TrackInfo(String songName, int interval, String mediaMid, boolean vip) {
            this.songName = songName;
            this.interval = interval;
            this.mediaMid = mediaMid;
            this.vip = vip;
        }
    }
}
