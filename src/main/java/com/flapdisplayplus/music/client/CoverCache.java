package com.flapdisplayplus.music.client;

import com.github.tartaricacid.netmusic.NetMusic;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 唱片封面缓存（仅客户端）。
 *
 * 工作流程：
 * 1. 从网易云 API 获取歌曲详情，解析封面 URL（al.picUrl）
 * 2. 异步下载封面图片
 * 3. 解码为 NativeImage，注册为 DynamicTexture，缓存起来
 *
 * 渲染器（CDCoverRenderer）通过 getTexture(songId) 获取封面纹理。
 * 首次请求时触发异步下载，返回 null（渲染默认 CD 纹理），下载完成后下次渲染生效。
 */
public class CoverCache {
    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");
    private static final String MODID = "netmusicdisplay";

    /** 歌曲 ID -> 封面纹理位置 */
    private static final Map<Long, ResourceLocation> LOCATIONS = new ConcurrentHashMap<>();
    /** 正在加载中的歌曲 ID（避免重复下载） */
    private static final Set<Long> LOADING = ConcurrentHashMap.newKeySet();
    /** 加载失败的歌曲 ID（避免反复请求） */
    private static final Set<Long> FAILED = ConcurrentHashMap.newKeySet();

    /**
     * 获取封面纹理位置（非阻塞）。
     * 如果纹理尚未加载，触发异步下载并返回 null。
     *
     * @param songId 网易云歌曲 ID
     * @return 封面纹理位置，未加载完成返回 null
     */
    public static ResourceLocation getLocation(long songId) {
        ResourceLocation loc = LOCATIONS.get(songId);
        if (loc != null) {
            return loc;
        }
        // 未加载且未失败，触发异步加载
        if (songId >= 0 && !LOADING.contains(songId) && !FAILED.contains(songId)) {
            LOADING.add(songId);
            loadAsync(songId);
        }
        return null;
    }

    private static void loadAsync(long songId) {
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 获取封面 URL
                String coverUrl = resolveCoverUrl(songId);
                if (coverUrl == null || coverUrl.isEmpty()) {
                    LOGGER.warn("[CoverCache] No cover URL for song ID: {}", songId);
                    LOADING.remove(songId);
                    FAILED.add(songId);
                    return;
                }

                // 2. 下载图片字节
                byte[] bytes = download(coverUrl);
                if (bytes == null || bytes.length == 0) {
                    LOGGER.warn("[CoverCache] Empty cover image for song ID: {}", songId);
                    LOADING.remove(songId);
                    FAILED.add(songId);
                    return;
                }

                // 3. 回到主线程解码 + 注册纹理
                Minecraft.getInstance().execute(() -> registerTexture(songId, bytes));
            } catch (Exception e) {
                LOGGER.error("[CoverCache] Failed to load cover for song ID: " + songId, e);
                LOADING.remove(songId);
                FAILED.add(songId);
            }
        });
    }

    private static void registerTexture(long songId, byte[] bytes) {
        try {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                NativeImage image = NativeImage.read(in);
                DynamicTexture texture = new DynamicTexture(image);
                ResourceLocation location = new ResourceLocation(MODID, "cover/" + songId);
                Minecraft.getInstance().getTextureManager().register(location, texture);
                LOCATIONS.put(songId, location);
                LOGGER.info("[CoverCache] Registered cover texture for song ID: {}", songId);
            }
        } catch (Exception e) {
            LOGGER.error("[CoverCache] Failed to register cover texture for song ID: " + songId, e);
        } finally {
            LOADING.remove(songId);
        }
    }

    /**
     * 从网易云歌曲详情 API 解析封面 URL。
     * 返回 JSON 结构：{ "songs": [ { "al": { "picUrl": "..." } } ] }
     */
    private static String resolveCoverUrl(long songId) throws Exception {
        if (NetMusic.NET_EASE_WEB_API == null) {
            return null;
        }
        String json = NetMusic.NET_EASE_WEB_API.song(songId);
        if (json == null || json.isEmpty()) {
            return null;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray songs = root.getAsJsonArray("songs");
        if (songs == null || songs.isEmpty()) {
            return null;
        }
        JsonElement song = songs.get(0);
        if (!song.isJsonObject()) {
            return null;
        }
        JsonObject al = song.getAsJsonObject().getAsJsonObject("al");
        if (al == null) {
            return null;
        }
        JsonElement picUrl = al.get("picUrl");
        return picUrl != null ? picUrl.getAsString() : null;
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestMethod("GET");
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }
}
