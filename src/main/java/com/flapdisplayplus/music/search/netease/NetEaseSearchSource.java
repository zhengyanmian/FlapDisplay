package com.flapdisplayplus.music.search.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.flapdisplayplus.music.search.IMusicSearchSource;
import com.flapdisplayplus.music.search.SearchResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 网易云搜索源。
 *
 * 完全自包含：自带 HttpClient（不依赖 Net Music 的 NetWorker，后者强制绑定
 * ConfigProxySelector 代理，在客户端环境下会导致请求失败），自带 Referer/UA。
 *
 * 使用网易云公开明文搜索接口 GET /api/search/get/web（无需加密），解析歌曲列表。
 *
 * cdUrl 回填的是「纯数字歌曲 ID」而非分享链接：原版刻录机的制作按钮
 * (CDBurnerMenuScreen.handleCraftButton) 只用 ID_REG = ^\d{4,}$ 做 matches()
 * 全串匹配，或 DJ_ID_REG = ^dj/(\d+)$，完整 URL 一律匹配失败并提示「音乐ID错误」。
 * 回填纯数字后由原版 MusicListManage.get163Song(id) 自行解析播放地址、歌名、时长，
 * 播放与歌词链路无需额外适配。
 */
public class NetEaseSearchSource implements IMusicSearchSource {

    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://music.163.com/";

    @Override
    public String getPlatformId() {
        return "netease";
    }

    @Override
    public String getDisplayName() {
        return "网易云音乐";
    }

    @Override
    public CompletableFuture<List<SearchResult>> search(String keyword, int limit) {
        String url = "https://music.163.com/api/search/get/web?s="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&type=1&limit=" + limit;
        LOGGER.info("[搜索|网易云] 请求: {}", url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Referer", REFERER)
                .header("User-Agent", UA)
                .GET()
                .build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    LOGGER.info("[搜索|网易云] 响应码: {}, 响应长度: {}",
                            resp.statusCode(), resp.body() == null ? 0 : resp.body().length());
                    List<SearchResult> list = parse(resp.body());
                    LOGGER.info("[搜索|网易云] 解析到 {} 首", list.size());
                    return list;
                })
                .exceptionally(ex -> {
                    LOGGER.error("[搜索|网易云] 请求失败", ex);
                    return List.of();
                });
    }

    private List<SearchResult> parse(String body) {
        List<SearchResult> list = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return list;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("result")) {
                LOGGER.warn("[搜索|网易云] 响应无 result 字段: {}", body.substring(0, Math.min(200, body.length())));
                return list;
            }
            JsonObject result = root.getAsJsonObject("result");
            if (!result.has("songs")) {
                return list;
            }
            JsonArray songs = result.getAsJsonArray("songs");
            for (JsonElement el : songs) {
                JsonObject song = el.getAsJsonObject();
                long id = song.get("id").getAsLong();
                String title = song.has("name") ? song.get("name").getAsString() : "未知歌曲";
                int durationSec = song.has("duration") ? (int) (song.get("duration").getAsLong() / 1000) : 0;
                String artist = extractArtist(song);
                // 关键：原版刻录机 handleCraftButton 只认「纯数字 ID」(ID_REG = ^\d{4,}$，
                // 用的是 matches() 全串匹配) 或「dj/数字」(DJ_ID_REG)，
                // 完整分享链接一律匹配失败 → 直接提示 music_id_error 且不做唱片。
                // 因此这里回填纯数字 ID，由原版 MusicListManage.get163Song(id) 解析真实
                // 播放地址与歌名，完整复用原版制作链路。
                String url = String.valueOf(id);
                list.add(new SearchResult(getPlatformId(), String.valueOf(id), title, artist, durationSec, url, ""));
            }
        } catch (Exception e) {
            LOGGER.error("[搜索|网易云] 解析异常", e);
        }
        return list;
    }

    private String extractArtist(JsonObject song) {
        try {
            JsonArray artists = song.getAsJsonArray("artists");
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : artists) {
                JsonObject artist = el.getAsJsonObject();
                if (sb.length() > 0) {
                    sb.append("/");
                }
                sb.append(artist.get("name").getAsString());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
