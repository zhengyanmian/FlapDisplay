/*
 * QQMusicApi.java
 *
 * QQ 音乐公开 API。
 *
 * 搜索 / 播放 URL 解析逻辑移植自开源项目 NetMusicCanNeedQQ
 * (https://github.com/Yincmewy/NetMusicCanNeedQQ) 原作者 Yincmewy（BSD-3-Clause）。
 * 主要变化：HTTP 层改为调用本模组的 HttpUtil（统一走 Net Music 的代理设置）。
 *
 * 【2026-08-28 全接口实测后的重要修正】
 * 旧注释「old 搜索接口 2026 实测可用」「未登录时 vkey 对所有歌曲返回空 purl」两条结论
 * 均已被实测推翻，原因分别是：
 *   1. 旧搜索接口 client_search_cp 现在返回 HTTP 500（彻底失效）；
 *   2. 上一次误判是因为搜索全空（拿不到 media_mid）+ 只试了 VIP 歌曲（pay_play=1），
 *      而 VIP 歌曲未登录本来就只能拿试听档。
 *
 * 实测确认（未登录状态）：
 * - 搜索：POST u.y.qq.com/cgi-bin/musicu.fcg
 *         method=DoSearchForQQMusicDesktop  module=music.search.SearchCgiService
 *         comm{ct:19, cv:1859, uin:0}，结果读 req.data.body.song.list
 *         返回含 mid / name / file.media_mid / interval / singer / pay.pay_play
 *         以及 size_flac / size_320mp3 等各档体积，可用于判断可用音质。
 * - 播放：POST u.y.qq.com/cgi-bin/musicu.fcg
 *         module=vkey.GetVkeyServer method=CgiGetVkey
 *         comm{ct:24, cv:0}，外层 key 为 req_1，结果读 req_1.data.midurlinfo
 *         未登录时免费歌曲可拿到 M500(320k)/C400/RS02 的真实 purl；
 *         F000(FLAC)/M800/C600 返回 104003（需 VIP）。
 *         实测返回的地址支持 Range 请求（206），对边下边播管线友好。
 * - 歌词：POST u.y.qq.com/cgi-bin/musicu.fcg
 *         module=music.musichallSong.PlayLyricInfo method=GetPlayLyricInfo
 *         返回 base64 编码的 LRC，解密后为明文（含 [ti:]/[ar:]）。
 *
 * 关键协议细节（错一个就返回空列表）：
 * - 搜索用 u.y.qq.com（不是 u6.y），且必须带 Referer: https://y.qq.com/
 * - 搜索 comm 是 ct=19/cv=1859，请求体为单层 {comm, req}
 * - vkey  comm 是 ct=24/cv=0，请求体外层为 req_1
 * - media_mid 与 songmid 是两个不同的值，vkey 的 filename 用 media_mid 拼，
 *   歌词/详情用 songmid，务必都保留。
 */
package com.flapdisplayplus.music.search.qqmusic;

import com.flapdisplayplus.music.MusicCompat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.flapdisplayplus.music.HttpUtil;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.data.SongInfoData;
import com.flapdisplayplus.music.qq.QqCredentialManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class QQMusicApi {

    /** 统一走 y.qq.com 的 musicu.fcg 网关（搜索 / vkey / 歌词三合一） */
    private static final String API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String DEFAULT_SIP = "http://ws.stream.qqmusic.qq.com/";

    /**
     * 音质档位表，按未登录可用性分两段。
     *
     * 实测（未登录）：
     * - M500 / C400 / RS02 → result=0，返回真实 purl（免费歌曲）
     * - F000 / M800 / C600  → result=104003（需 VIP）
     * - VIP 歌曲（pay_play=1）未登录只有 RS02 可用
     *
     * 因此顺序刻意把「未登录可用档」放前面，保证任何情况下都能尽快命中可播地址；
     * 已登录时前置的高档位会自然命中，无需额外分支。
     */
    private static final FileCandidate[] QUALITY_CANDIDATES = new FileCandidate[]{
            new FileCandidate("M500", "mp3"),  // 320kbps，免费可用，首选
            new FileCandidate("C400", "m4a"),  // 128k，免费可用
            new FileCandidate("RS02", "mp3"),  // 试听档，免费/VIP 兜底
            new FileCandidate("F000", "flac"), // 无损，需 VIP
            new FileCandidate("M800", "mp3"),  // 需 VIP
            new FileCandidate("C600", "m4a"),  // 需 VIP
            new FileCandidate("C200", "m4a"),
            new FileCandidate("C100", "m4a")
    };

    private QQMusicApi() {
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");
        h.put("Referer", "https://y.qq.com/");
        // 已登录则附带登录 cookie。注意：未登录也能搜索与播放（320k），
        // cookie 只用于解锁 FLAC 无损与 VIP 歌曲全曲，属于可选增强。
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

    /**
     * 搜索 QQ 音乐。
     *
     * 走 musicu.fcg 的 DoSearchForQQMusicDesktop（2026-08-28 实测 code:0 且有数据）。
     * 注意方法名必须是 DoSearchForQQMusicDesktop——曾误用 DoSearchForMusicDesktop
     * 导致返回空列表，从而误判「新接口不可用」，此处勿改。
     *
     * 未登录即可搜索。返回结果直接带 media_mid，调用方应缓存它，
     * 这样换 vkey 时无需再调一次详情接口。
     *
 * 特殊返回：req.code=2001 表示**本次**返回空列表，成因有二（都属间歇性，冷却/重试即恢复）：
 *   ① 连续快速请求触发服务端限流；② 个别关键词被曲库过滤。
 * 实测同一关键词先 2001、几分钟后返回 30 条正常数据，故不可当作「接口故障」处理。
     */
    public static List<QQSong> search(String query) throws Exception {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        JsonObject comm = new JsonObject();
        comm.addProperty("ct", "19");
        comm.addProperty("cv", "1859");
        comm.addProperty("uin", "0");

        JsonObject param = new JsonObject();
        param.addProperty("grp", 1);
        param.addProperty("num_per_page", 30);
        param.addProperty("page_num", 1);
        param.addProperty("query", query);
        param.addProperty("search_type", 0);

        JsonObject req = new JsonObject();
        req.addProperty("method", "DoSearchForQQMusicDesktop");
        req.addProperty("module", "music.search.SearchCgiService");
        req.add("param", param);

        JsonObject body = new JsonObject();
        body.add("comm", comm);
        body.add("req", req);

        String response = HttpUtil.postJson(API_URL, body.toString(), baseHeaders());

        try {
            JsonObject tree = JsonParser.parseString(response).getAsJsonObject();
            if (tree.has("code") && tree.get("code").getAsInt() != 0) {
                MusicCompat.LOGGER.warn("[QQ搜索] 外层 code 非 0: {}", response);
                return Collections.emptyList();
            }
            JsonObject reqObj = tree.getAsJsonObject("req");
            if (reqObj == null) {
                return Collections.emptyList();
            }
            // req.code = 2001：本次返回空列表。
            //
            // 【实测行为，勿误判】2001 有两种成因，且都是**间歇性**的：
            //   ① 连续快速请求触发服务端限流（例如反复点搜索按钮）→ 冷却后自动恢复；
            //   ② 个别关键词被曲库/版权策略过滤。
            // 实测证据：同一关键词「周杰伦」先返回 2001，几分钟后重跑返回 30 条完整数据；
            // 且「周杰伦/晴天/Vicetone/Nevada/钢琴/古筝」批量重跑全部正常。
            // 因此这里**只记 info 日志并返回空列表，不要抛异常**——
            // 上层只需提示「无结果」，用户再点一次搜索通常就好了。
            if (reqObj.has("code") && reqObj.get("code").getAsInt() == 2001) {
                MusicCompat.LOGGER.info("[QQ搜索] 本次返回空（code 2001，多为限流或曲库过滤，可重试）: {}", query);
                return Collections.emptyList();
            }
            JsonObject songObj = reqObj.getAsJsonObject("data")
                    .getAsJsonObject("body")
                    .getAsJsonObject("song");
            if (songObj == null) {
                return Collections.emptyList();
            }
            JsonArray list = songObj.getAsJsonArray("list");
            if (list == null) {
                return Collections.emptyList();
            }

            List<QQSong> results = new ArrayList<>();
            for (JsonElement el : list) {
                JsonObject song = el.getAsJsonObject();
                String mid = song.has("mid") ? song.get("mid").getAsString() : "";
                if (mid.isEmpty()) {
                    continue;
                }
                String name = song.has("name") ? song.get("name").getAsString() : "";
                boolean vip = song.has("pay")
                        && song.getAsJsonObject("pay").has("pay_play")
                        && song.getAsJsonObject("pay").get("pay_play").getAsInt() == 1;

                StringBuilder singer = new StringBuilder();
                if (song.has("singer")) {
                    for (JsonElement s : song.getAsJsonArray("singer")) {
                        if (singer.length() > 0) {
                            singer.append("/");
                        }
                        singer.append(s.getAsJsonObject().get("name").getAsString());
                    }
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
            MusicCompat.LOGGER.debug("[QQ搜索] query={} 命中 {} 首", query, results.size());
            return results;
        } catch (RuntimeException e) {
            MusicCompat.LOGGER.error("[QQ搜索] 解析失败: " + response, e);
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
     *
     * 注意：这是唯一的对外解析入口，刻意不提供同步版本——
     * 曾经存在过一个 getPlayUrlSync，设计意图是「供刻录机在 Render 线程同步请求
     * vkey」，这会在渲染线程阻塞网络 IO，是明确的性能反模式，已删除。
     * 需要同步结果的场景请改用后台线程预取 + 缓存命中。
     */
    public static CompletableFuture<String> getPlayUrl(String songmid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return resolvePlayUrl(songmid);
            } catch (Exception e) {
                MusicCompat.LOGGER.error("[QQ解析] 获取播放地址失败: " + songmid, e);
                return null;
            }
        });
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
        String cookie = QqCredentialManager.getEffectiveCookie();
        MusicCompat.LOGGER.debug("[QQ解析] resolvePlayUrl 开始 mid={} cookie长度={}", songmid, cookie.length());

        // 优先从搜索缓存取 media_mid。搜索结果本身就带 media_mid，用它可省掉
        // 一次详情接口往返（详情接口 music.pf_song_detail_svr 也常不稳定）。
        String mediaMid = null;
        com.flapdisplayplus.music.search.SearchResult cached = QqSearchCache.get(songmid);
        if (cached != null && cached.mediaMid() != null && !cached.mediaMid().isBlank()) {
            mediaMid = cached.mediaMid();
            MusicCompat.LOGGER.debug("[QQ解析] 用搜索缓存 media_mid：{} -> {}", songmid, mediaMid);
        }
        // 缓存未命中才查详情接口。注意 media_mid 与 songmid 不同，
        // 但详情接口取不到时退回用 songmid 仍有机会成功，故此处只是兜底。
        if (mediaMid == null || mediaMid.isBlank()) {
            TrackInfo info = getTrackInfoByMid(songmid);
            mediaMid = (info.mediaMid == null || info.mediaMid.isBlank()) ? songmid : info.mediaMid;
            MusicCompat.LOGGER.debug("[QQ解析] 详情接口兜底 media_mid={}", mediaMid);
        }

        JsonObject vkeyData = requestVkeyData(songmid, mediaMid);
        String baseUrl = resolveBaseUrl(vkeyData);
        String purl = selectBestPurl(vkeyData.getAsJsonArray("midurlinfo"));
        if (purl == null || purl.isBlank()) {
            logVkeyFailure(vkeyData);
            return null;
        }
        return baseUrl + purl;
    }

    /**
     * 全部档位都没换到 purl 时输出诊断日志。
     * 常见原因：VIP 歌曲且未登录（只有 RS02 可用）、歌曲已下架、版权方限制。
     */
    private static void logVkeyFailure(JsonObject vkeyData) {
        if (vkeyData == null || !vkeyData.has("midurlinfo")) {
            return;
        }
        JsonArray arr = vkeyData.getAsJsonArray("midurlinfo");
        for (int i = 0; i < arr.size(); i++) {
            JsonObject info = arr.get(i).getAsJsonObject();
            String filename = info.has("filename") && !info.get("filename").isJsonNull()
                    ? info.get("filename").getAsString() : "?";
            int result = info.has("result") && !info.get("result").isJsonNull()
                    ? info.get("result").getAsInt() : -1;
            // 104003 表示该档位需要 VIP；这不是错误，只是档位不可用
            MusicCompat.LOGGER.debug("[QQ解析] 档位不可用: filename={} result={}", filename, result);
        }
        MusicCompat.LOGGER.info("[QQ解析] 所有档位均未换到播放地址（VIP 歌曲需登录，或歌曲已下架）");
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
                MusicCompat.LOGGER.warn("[QQ解析] 歌曲详情为空（可能已下架）：mid={}", songmid);
                return null;
            }
            return new QQSong(songmid, info.songName, "", info.interval, info.vip, "");
        } catch (Exception e) {
            MusicCompat.LOGGER.error("[QQ解析] 获取歌曲详情失败: " + songmid, e);
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
            MusicCompat.LOGGER.error("[QQ解析] 获取歌曲详情失败: " + mid, e);
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

    /**
     * 获取明文 LRC 歌词；失败或无词返回 null。
     *
     * 2026-08-28 实测改用官方现役接口 music.musichallSong.PlayLyricInfo.GetPlayLyricInfo
     * （旧的 fcg_query_lyric_new.fcg 仍可用但已非官方主推）。
     * 该接口返回 base64 编码的 LRC，需解码后使用；未登录即可获取。
     *
     * 实测：《晴天》返回 68 行，含 [ti:]/[ar:] 元信息；纯音乐返回
     * 「此歌曲为没有填词的纯音乐，请您欣赏」一行，这种情况按无词处理更合适。
     */
    public static String getLyric(String songmid) {
        if (songmid == null || songmid.isBlank()) {
            return null;
        }
        try {
            JsonObject param = new JsonObject();
            param.addProperty("songMID", songmid);
            param.addProperty("songID", 0);
            param.addProperty("format", "json");

            JsonObject req = new JsonObject();
            req.addProperty("module", "music.musichallSong.PlayLyricInfo");
            req.addProperty("method", "GetPlayLyricInfo");
            req.add("param", param);

            JsonObject comm = new JsonObject();
            comm.addProperty("ct", 24);
            comm.addProperty("cv", 0);

            JsonObject body = new JsonObject();
            body.add("comm", comm);
            body.add("req_1", req);

            String response = HttpUtil.postJson(API_URL, body.toString(), baseHeaders());
            JsonObject tree = JsonParser.parseString(response).getAsJsonObject();
            JsonObject lyricObj = tree.getAsJsonObject("req_1");
            if (lyricObj == null || !lyricObj.has("data")) {
                return null;
            }
            JsonObject data = lyricObj.getAsJsonObject("data");
            if (data == null || !data.has("lyric")) {
                return null;
            }
            String raw = data.get("lyric").getAsString();
            if (raw == null || raw.isBlank()) {
                return null;
            }

            // 接口返回 base64 编码，解码后才是明文 LRC
            String lyric;
            try {
                lyric = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // 少数情况返回的就是明文，直接用
                lyric = raw;
            }

            // 纯音乐提示不算歌词，返回 null 让上层走「无歌词」分支
            if (lyric.contains("此歌曲为没有填词的纯音乐")) {
                return null;
            }
            return lyric.isBlank() ? null : lyric;
        } catch (Exception e) {
            MusicCompat.LOGGER.error("[QQ歌词] 获取失败: " + songmid, e);
        }
        return null;
    }

    /** 获取翻译歌词（明文 LRC）；无翻译返回 null */
    public static String getTransLyric(String songmid) {
        if (songmid == null || songmid.isBlank()) {
            return null;
        }
        try {
            JsonObject param = new JsonObject();
            param.addProperty("songMID", songmid);
            param.addProperty("songID", 0);
            param.addProperty("format", "json");

            JsonObject req = new JsonObject();
            req.addProperty("module", "music.musichallSong.PlayLyricInfo");
            req.addProperty("method", "GetPlayLyricInfo");
            req.add("param", param);

            JsonObject comm = new JsonObject();
            comm.addProperty("ct", 24);
            comm.addProperty("cv", 0);

            JsonObject body = new JsonObject();
            body.add("comm", comm);
            body.add("req_1", req);

            String response = HttpUtil.postJson(API_URL, body.toString(), baseHeaders());
            JsonObject tree = JsonParser.parseString(response).getAsJsonObject();
            JsonObject lyricObj = tree.getAsJsonObject("req_1");
            if (lyricObj == null || !lyricObj.has("data")) {
                return null;
            }
            JsonObject data = lyricObj.getAsJsonObject("data");
            if (data == null || !data.has("trans")) {
                return null;
            }
            String raw = data.get("trans").getAsString();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String trans;
            try {
                trans = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                trans = raw;
            }
            return trans.isBlank() ? null : trans;
        } catch (Exception e) {
            MusicCompat.LOGGER.error("[QQ歌词] 获取翻译失败: " + songmid, e);
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
