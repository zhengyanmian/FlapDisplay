/*
 * MediaManager.java
 *
 * 客户端媒体帧管理器：
 * - 按「文件路径 → 纹理」缓存多张图片（PNG/JPG/BMP），
 *   每台布谷鸟时钟可以独立选图，各自缓存自己的纹理。
 * - GIF 动图：多帧预解码（后台线程）→ 每帧上传为纹理 →
 *   渲染时按动画时间取当前帧（getFrame），循环播放。
 * - 异步加载：首次请求某路径时后台解码，完成后更新缓存；
 *   渲染 Mixin 通过 getFrame(path, time) 非阻塞获取当前帧。
 *
 * 后续扩展：MP4 视频（JCodec shade）、WebP 动图（TwelveMonkeys shade）都在这里接入。
 */
package com.flapdisplayplus.client;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.config.Config;
import com.flapdisplayplus.net.MediaResolverManager;
import com.flapdisplayplus.net.NetMediaManager;
import com.flapdisplayplus.net.StreamDownloader;
import com.mojang.blaze3d.platform.NativeImage;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.scale.AWTUtil;
import java.awt.Canvas;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MediaManager {

    private MediaManager() {
    }

    /** 路径 → 已上传的静态纹理（或动图首帧） */
    private static final Map<String, ResourceLocation> TEXTURES = new ConcurrentHashMap<>();
    /** 路径 → 图片宽高（用于 FIT/COVER 比例计算） */
    private static final Map<String, int[]> DIMENSIONS = new ConcurrentHashMap<>();
    /** 路径 → 动图数据（GIF 多帧） */
    private static final Map<String, AnimData> ANIMS = new ConcurrentHashMap<>();
    /** 路径 → 原生流式视频播放器（任意时长实时解码，内存恒定） */
    private static final Map<String, VideoPlayer> VIDEOS = new ConcurrentHashMap<>();
    /** 正在加载中的路径（避免重复解码） */
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    /** 加载失败的路径（避免反复请求） */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    /** 动图最大帧数（内存保护） */
    private static final int MAX_FRAMES = 120;

    /** 视频是否播放声音（读取全局配置，切换时重启播放器） */
    public static boolean isVideoSoundEnabled() {
        try {
            return com.flapdisplayplus.config.Config.MEDIA_VIDEO_SOUND.get();
        } catch (Throwable t) {
            return true; // 配置未加载时默认有声
        }
    }

    /** 切换视频声音开关（写入配置并重启所有视频播放器，下一轮渲染自动重建） */
    public static void setVideoSound(boolean enabled) {
        try {
            com.flapdisplayplus.config.Config.MEDIA_VIDEO_SOUND.set(enabled);
            com.flapdisplayplus.config.Config.SPEC.save();
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[Media] 保存视频声音配置失败", t);
        }
        for (VideoPlayer vp : VIDEOS.values()) {
            vp.stop();
        }
        VIDEOS.clear();
        FlapDisplayPlus.LOGGER.info("[Media] 视频声音已切换: {}", enabled);
    }

    /** VideoPlayer 首帧就绪后回填尺寸（供 FIT/COVER 比例计算与 GUI 缩略图） */
    static void onVideoSize(String path, int w, int h) {
        DIMENSIONS.put(path, new int[]{w, h});
    }

    /** 立即停止并移除单个视频播放器（切换源 / 世界事件时调用），彻底释放音频线与纹理 */
    public static void stopVideo(String path) {
        if (path == null) {
            return;
        }
        VideoPlayer vp = VIDEOS.remove(path);
        if (vp != null) {
            vp.stop();
            FlapDisplayPlus.LOGGER.info("[Media] 停止视频播放器: {}", path);
        }
    }

    /** 停止并移除全部视频播放器（退出世界 / 断线 / 资源重载时调用，防止音频线程残留） */
    public static void stopAllVideos() {
        if (VIDEOS.isEmpty()) {
            return;
        }
        for (VideoPlayer vp : VIDEOS.values()) {
            vp.stop();
        }
        VIDEOS.clear();
        FlapDisplayPlus.LOGGER.info("[Media] 已停止全部视频播放器");
    }

    /**
     * 客户端每刻调用：
     * 1) 方块存在性核对：翻牌显示器被拆除后立即注销并停掉其媒体（视频声音随之立即停止）。
     * 2) 【2026-09-27 新增】游戏菜单（ESC）暂停：暂停所有视频播放器（画面冻结 + 立即静音），
     *    关闭菜单后恢复。借鉴成熟视频模组的通用行为。
     * 3) 已 stop() 的残留实例清理（防 VIDEOS Map 泄漏）。
     */
    public static void tick() {
        sweepRemovedDisplays();
        if (VIDEOS.isEmpty()) {
            return;
        }
        boolean menuPaused = isGameMenuPaused();
        if (menuPaused != lastMenuPaused) {
            lastMenuPaused = menuPaused;
            for (VideoPlayer vp : VIDEOS.values()) {
                vp.setMenuPaused(menuPaused);
            }
        }
        for (java.util.Iterator<java.util.Map.Entry<String, VideoPlayer>> it =
                 VIDEOS.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, VideoPlayer> e = it.next();
            if (e.getValue().isStopped()) {
                it.remove();
            }
        }
    }

    /** 上一刻的游戏菜单暂停状态（用于边沿触发暂停/恢复） */
    private static boolean lastMenuPaused = false;

    /** 游戏是否处于暂停菜单（仅单人暂停场景；联机不需要暂停视频） */
    private static boolean isGameMenuPaused() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isPaused();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 核对注册表中每个翻牌坐标处是否仍有翻牌 BE；已被拆除/结构断开的立即注销。
     *
     * 关键：必须先用 isLoaded(pos) 区分「区块未加载（玩家走远）」与「方块真被移除」，
     * 否则玩家一走出加载范围就会误删配置（旧的 30s 过期注释里记过这个坑）。
     */
    private static void sweepRemovedDisplays() {
        if (MediaRenderRegistry.size() == 0) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (BlockPos pos : new java.util.ArrayList<>(MediaRenderRegistry.keys())) {
            try {
                if (!level.isLoaded(pos)) {
                    continue; // 区块未加载：不能判定为已拆除
                }
                if (level.getBlockEntity(pos) instanceof FlapDisplayBlockEntity) {
                    continue; // 还在
                }
                MediaRenderRegistry.MediaInfo mi = MediaRenderRegistry.peek(pos);
                MediaRenderRegistry.remove(pos);
                FlapDisplayPlus.LOGGER.info("[Media] 翻牌已被拆除，注销媒体显示: {}", pos);
                if (mi != null) {
                    stopVideoIfUnreferenced(mi.mediaPath);
                }
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.debug("[Media] 方块核对异常 {}", pos, t);
            }
        }
    }

    /** 若该媒体路径已不再被任何注册项引用，则停止其视频播放器（音频随之停止） */
    private static void stopVideoIfUnreferenced(String mediaPath) {
        if (mediaPath == null || mediaPath.isEmpty()) {
            return;
        }
        for (BlockPos p : new java.util.ArrayList<>(MediaRenderRegistry.keys())) {
            MediaRenderRegistry.MediaInfo other = MediaRenderRegistry.peek(p);
            if (other != null && mediaPath.equals(other.mediaPath)) {
                return; // 仍被别的翻牌引用
            }
        }
        stopVideoByMediaPath(mediaPath);
    }

    /** 按「媒体输入路径」（网络链接/本地文件）停止对应的实时媒体播放器 */
    public static void stopVideoByMediaPath(String mediaPath) {
        if (mediaPath == null || mediaPath.isEmpty()) {
            return;
        }
        String local;
        try {
            local = localPath(mediaPath);
        } catch (Throwable t) {
            local = null;
        }
        if (local == null) {
            local = mediaPath; // 兜底：本地输入时 key 即路径本身
        }
        stopVideo(local);
    }

    /** 图片宽度（未加载返回 0） */
    public static int getTextureWidth(String path) {
        int[] d = DIMENSIONS.get(path);
        return d == null ? 0 : d[0];
    }

    /** 图片高度（未加载返回 0） */
    public static int getTextureHeight(String path) {
        int[] d = DIMENSIONS.get(path);
        return d == null ? 0 : d[1];
    }

    // ============================ 格式支持清单 ============================
    // 集中维护，避免扩展名判断散落各处后不同步（曾导致列表里能选、实际解不了的格式）。

    /**
     * 可播放的视频容器扩展名。
     * 基础清单 = jcodec 真正能解的封装（MP4 系；编码需 H.264）。
     * 注意：jcodec 0.2.5 【没有 AVI demuxer】，avi 曾出现在选择列表里但必然解码失败，已移除。
     * 【2026-09-27】检测到 FFmpeg 后端时（见 FfmpegPipeDecoder），额外开放 mkv/avi/webm/flv/wmv/ts
     * （H.265/AV1 等编码也随之支持）——与成熟视频模组「纯 Java 兜底 + FFmpeg 升级」的结构一致。
     */
    private static final String[] VIDEO_EXTENSIONS = {".mp4", ".m4v", ".mov"};

    /** 仅在 FFmpeg 后端可用时可播放的容器 */
    private static final String[] FFMPEG_VIDEO_EXTENSIONS = {".mkv", ".avi", ".webm", ".flv", ".wmv", ".ts"};

    /** 支持的静态图 / 动图扩展名（WebP 由 TwelveMonkeys 解码，含被错命名为 .png 的 WebP） */
    private static final String[] IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"};

    /** 是否是可播放的视频（按扩展名判断） */
    public static boolean isVideo(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        if (FfmpegPipeDecoder.isAvailable()) {
            for (String ext : FFMPEG_VIDEO_EXTENSIONS) {
                if (lower.endsWith(ext)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否是支持的图片/动图（按扩展名判断） */
    public static boolean isImage(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 媒体选择界面用的扩展名清单（图片 + 视频；FFmpeg 可用时含 mkv/avi 等额外容器） */
    public static String[] supportedExtensions() {
        String[] video = VIDEO_EXTENSIONS;
        if (FfmpegPipeDecoder.isAvailable()) {
            video = new String[VIDEO_EXTENSIONS.length + FFMPEG_VIDEO_EXTENSIONS.length];
            System.arraycopy(VIDEO_EXTENSIONS, 0, video, 0, VIDEO_EXTENSIONS.length);
            System.arraycopy(FFMPEG_VIDEO_EXTENSIONS, 0, video, VIDEO_EXTENSIONS.length, FFMPEG_VIDEO_EXTENSIONS.length);
        }
        String[] all = new String[IMAGE_EXTENSIONS.length + video.length];
        System.arraycopy(IMAGE_EXTENSIONS, 0, all, 0, IMAGE_EXTENSIONS.length);
        System.arraycopy(video, 0, all, IMAGE_EXTENSIONS.length, video.length);
        return all;
    }

    /**
     * 获取媒体当前帧纹理（非阻塞）。
     * 视频走 VideoPlayer 实时流式解码（任意时长完整播放，循环，带声音）；
     * 动图按动画时间循环取帧；静态图返回固定纹理。首次请求触发异步加载返回 null。
     */
    public static ResourceLocation getFrame(String path, long animTimeMs) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String local = localPath(path);
        if (local == null) {
            return null;
        }
        if (isVideo(local)) {
            // 预览/缩略图：仅取静音首帧静态纹理，绝不起出声的播放器。
            // 打开配置界面时为每个视频取首帧也不会造成多视频同时出声。
            return getVideoThumbnail(local);
        }
        AnimData a = ANIMS.get(local);
        if (a != null && a.frames.size() > 1) {
            if (a.totalDelayMs <= 0) {
                return a.frames.get(0);
            }
            long t = animTimeMs % a.totalDelayMs;
            long acc = 0;
            for (int i = 0; i < a.delaysMs.size(); i++) {
                acc += a.delaysMs.get(i);
                if (t < acc) {
                    return a.frames.get(i);
                }
            }
            return a.frames.get(a.frames.size() - 1);
        }
        ResourceLocation loc = TEXTURES.get(local);
        if (loc != null) {
            return loc;
        }
        if (!LOADING.contains(local) && !FAILED.contains(local)) {
            LOADING.add(local);
            loadAsync(local);
        }
        return null;
    }

    /**
     * 把「可能是网络链接的路径」解析成本地文件路径。
     *
     * 网络媒体：触发后台解析 + 流式下载（幂等，重复调用不会重复下载），
     * 返回缓存文件路径；解析/下载尚未产出文件时返回 null（界面显示"下载中"）。
     * 本地文件：原样返回。
     *
     * 这样下游所有缓存（TEXTURES/ANIMS/VIDEOS）仍以本地路径为 key，管线不用改。
     */
    private static String localPath(String path) {
        if (!MediaResolverManager.isNetworkInput(path)) {
            return path;
        }
        NetMediaManager.Task t = NetMediaManager.acquire(path);
        if (t.state == NetMediaManager.State.FAILED) {
            return null;
        }
        return t.file == null ? null : t.file.getAbsolutePath();
    }

    /** 网络媒体的流式下载器（仅下载中有意义，用于边下边播）；本地文件返回 null */
    private static StreamDownloader downloaderFor(String originalPath) {
        if (!MediaResolverManager.isNetworkInput(originalPath)) {
            return null;
        }
        NetMediaManager.Task t = NetMediaManager.get(originalPath);
        if (t == null || t.downloader == null) {
            return null;
        }
        return t.state == NetMediaManager.State.DOWNLOADING ? t.downloader : null;
    }

    /**
     * 游戏内渲染用：获取视频当前播放帧（启动/复用真正会出声的视频播放器）。
     * 仅被渲染 Mixin 调用（对应某个布谷鸟时钟选中的视频），绝不会被 GUI 缩略图触发。
     */
    public static ResourceLocation getVideoFrame(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String local = localPath(path);
        if (local == null) {
            return null;
        }
        if (!isVideo(local)) {
            return getFrame(local, System.currentTimeMillis());
        }
        VideoPlayer vp = VIDEOS.get(local);
        if (vp == null || vp.isStopped()) {
            vp = null;
            if (!FAILED.contains(local)) {
                try {
                    File f = new File(local);
                    if (f.isFile()) {
                        // 网络视频把下载器交给播放器：读取超出已下载范围时等待而非 EOF，
                        // 实现边下边播（本地视频 downloader 为 null，走普通通道）
                        vp = new VideoPlayer(path, f, isVideoSoundEnabled(), downloaderFor(path));
                        VIDEOS.put(local, vp);
                    } else {
                        FlapDisplayPlus.LOGGER.warn("[Media] 视频文件不存在: {}", local);
                        FAILED.add(local);
                    }
                } catch (Throwable t) {
                    FlapDisplayPlus.LOGGER.warn("[Media] 视频播放器创建失败: {} {}", local, t.toString());
                    FAILED.add(local);
                }
            }
        }
        if (vp == null) {
            return null;
        }
        // 新建/已存在的播放器同步游戏菜单暂停状态（幂等；清空后立刻重选视频的恢复路径也覆盖到）
        vp.setMenuPaused(isGameMenuPaused());
        return vp.getFrame();
    }

    /**
     * 视频缩略图（静音首帧静态纹理）。供 GUI 网格预览使用，不创建/不启动播放器，不会出声。
     * 异步解码第一帧并上传为静态纹理；未就绪返回 null。
     */
    public static ResourceLocation getVideoThumbnail(String path) {
        ResourceLocation loc = TEXTURES.get(path);
        if (loc != null) {
            return loc;
        }
        if (!LOADING.contains(path) && !FAILED.contains(path)) {
            LOADING.add(path);
            loadVideoThumbnailAsync(path);
        }
        return null;
    }

    private static void loadVideoThumbnailAsync(String path) {
        CompletableFuture.runAsync(() -> {
            File f = new File(path);
            if (!f.isFile()) {
                FlapDisplayPlus.LOGGER.warn("[Media] 视频文件不存在: {}", path);
                LOADING.remove(path);
                FAILED.add(path);
                return;
            }
            SeekableByteChannel ch = null;
            try {
                ch = NIOUtils.readableChannel(f);
                FrameGrab grab = FrameGrab.createFrameGrab(ch);
                org.jcodec.common.model.Picture pic = grab.getNativeFrame();
                if (pic == null) {
                    FlapDisplayPlus.LOGGER.warn("[Media] 视频首帧解码失败: {}", path);
                    LOADING.remove(path);
                    FAILED.add(path);
                    return;
                }
                BufferedImage bi = AWTUtil.toBufferedImage(pic);
                if (bi == null) {
                    FlapDisplayPlus.LOGGER.warn("[Media] 视频首帧转换失败: {}", path);
                    LOADING.remove(path);
                    FAILED.add(path);
                    return;
                }
                final BufferedImage scaled = scaleDown(bi, Config.MEDIA_IMAGE_MAX_DIM.get());
                Minecraft.getInstance().execute(() -> {
                    try {
                        ResourceLocation loc = upload(scaled);
                        TEXTURES.put(path, loc);
                        DIMENSIONS.put(path, new int[]{scaled.getWidth(), scaled.getHeight()});
                        LOADING.remove(path);
                        FlapDisplayPlus.LOGGER.info("[Media] 视频缩略图已加载: {} ({}x{})",
                                path, scaled.getWidth(), scaled.getHeight());
                    } catch (Exception e) {
                        FlapDisplayPlus.LOGGER.error("[Media] 视频缩略图上传动失败: {}", path, e);
                        LOADING.remove(path);
                        FAILED.add(path);
                    }
                });
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[Media] 视频缩略图解码失败: {} {}", path, t.toString());
                LOADING.remove(path);
                FAILED.add(path);
            } finally {
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        });
    }

    /** 获取静态纹理（动图返回首帧，视频返回静音首帧缩略图），用于 GUI 预览 */
    public static ResourceLocation getTexture(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return getFrame(path, System.currentTimeMillis());
    }

    private static void loadAsync(String path) {
        CompletableFuture.runAsync(() -> {
            File f = new File(path);
            if (!f.isFile()) {
                FlapDisplayPlus.LOGGER.warn("[Media] 文件不存在: {}", path);
                LOADING.remove(path);
                FAILED.add(path);
                return;
            }
            String lower = path.toLowerCase();
            try {
                // GIF 动图：多帧解析（视频已由 VideoPlayer 实时流式播放，不经此处）
                if (lower.endsWith(".gif")) {
                    if (tryLoadGif(path, f)) {
                        return;
                    }
                }
                // 静态图：多解码器兜底（重点修复部分 PNG 用 Java ImageIO 解码返回 null 的问题）
                BufferedImage img = loadImageRobust(f);
                if (img == null) {
                    FlapDisplayPlus.LOGGER.warn("[Media] 无法解码（可能格式不支持）: {}", path);
                    logImageDiagnostics(path, f);
                    LOADING.remove(path);
                    FAILED.add(path);
                    return;
                }
                img = scaleDown(img, Config.MEDIA_IMAGE_MAX_DIM.get());
                final BufferedImage finalImg = img;
                Minecraft.getInstance().execute(() -> {
                    try {
                        ResourceLocation loc = upload(finalImg);
                        TEXTURES.put(path, loc);
                        DIMENSIONS.put(path, new int[]{finalImg.getWidth(), finalImg.getHeight()});
                        LOADING.remove(path);
                        FlapDisplayPlus.LOGGER.info("[Media] 已加载: {} ({}x{})",
                                path, finalImg.getWidth(), finalImg.getHeight());
                    } catch (Exception e) {
                        FlapDisplayPlus.LOGGER.error("[Media] 上传纹理失败: {}", path, e);
                        LOADING.remove(path);
                        FAILED.add(path);
                    }
                });
            } catch (Exception e) {
                FlapDisplayPlus.LOGGER.error("[Media] 读取失败: {}", path, e);
                LOADING.remove(path);
                FAILED.add(path);
            }
        });
    }

    /** 尝试按 GIF 动图加载（多帧）。成功返回 true */
    private static boolean tryLoadGif(String path, File f) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(f)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int n = reader.getNumImages(true);
                if (n <= 1) {
                    return false; // 静态 GIF，走普通流程
                }
                int count = Math.min(n, MAX_FRAMES);
                List<BufferedImage> frames = new ArrayList<>(count);
                List<Long> delays = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    BufferedImage fi = reader.read(i);
                    frames.add(scaleDown(fi, Config.MEDIA_IMAGE_MAX_DIM.get()));
                    delays.add(getGifDelayMs(reader, i));
                }
                Minecraft.getInstance().execute(() -> uploadAnim(path, frames, delays));
                return true;
            } finally {
                reader.dispose();
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[Media] GIF 解析失败, 按静态图处理: {}", path, t.toString());
            return false;
        }
    }

    /** 读取 GIF 帧延迟（GraphicControlExtension.delayTime，单位 1/100 秒） */
    private static long getGifDelayMs(ImageReader reader, int index) {
        try {
            var meta = reader.getImageMetadata(index);
            if (meta == null) {
                return 100;
            }
            var root = (IIOMetadataNode) meta.getAsTree(meta.getNativeMetadataFormatName());
            for (int i = 0; i < root.getLength(); i++) {
                var node = (IIOMetadataNode) root.item(i);
                if ("GraphicControlExtension".equals(node.getNodeName())) {
                    String d = node.getAttribute("delayTime");
                    if (d != null && !d.isEmpty()) {
                        long ms = Long.parseLong(d) * 10;
                        return ms > 0 ? ms : 100;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 100;
    }

    /** 主线程上传全部动画帧 */
    private static void uploadAnim(String path, List<BufferedImage> frames, List<Long> delays) {
        try {
            AnimData a = new AnimData();
            long total = 0;
            for (int i = 0; i < frames.size(); i++) {
                ResourceLocation loc = upload(frames.get(i));
                a.frames.add(loc);
                long d = Math.max(20, delays.get(i));
                a.delaysMs.add(d);
                total += d;
            }
            a.totalDelayMs = total;
            BufferedImage first = frames.get(0);
            a.width = first.getWidth();
            a.height = first.getHeight();
            ANIMS.put(path, a);
            DIMENSIONS.put(path, new int[]{a.width, a.height});
            LOADING.remove(path);
            FlapDisplayPlus.LOGGER.info("[Media] 已加载动图: {} ({}帧, 循环{}ms)",
                    path, frames.size(), a.totalDelayMs);
        } catch (Exception e) {
            FlapDisplayPlus.LOGGER.error("[Media] 上传动图失败: {}", path, e);
            LOADING.remove(path);
            FAILED.add(path);
        }
    }

    /** 上传 BufferedImage 为 DynamicTexture，返回纹理位置（LINEAR 过滤，拉伸显示时平滑） */
    private static ResourceLocation upload(BufferedImage img) {
        NativeImage nativeImage = new NativeImage(img.getWidth(), img.getHeight(), true);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                nativeImage.setPixelRGBA(x, y, argbToRgba(img.getRGB(x, y)));
            }
        }
        DynamicTexture tex = new DynamicTexture(nativeImage);
        ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("flapdisplayplus/media_" + System.nanoTime(), tex);
        // LINEAR 过滤：翻牌显示面与纹理分辨率不一致时平滑插值，消除块状模糊。
        // 调用方均在 Minecraft.execute（渲染线程）里，满足 setFilter 的线程断言。
        tex.setFilter(true, false);
        return loc;
    }

    /**
     * ARGB(int) -> NativeImage 期望的 RGBA int。
     * NativeImage.setPixelRGBA 用 memPutInt（小端）写入，而 GL_RGBA 期望内存字节序 R,G,B,A：
     *   int 必须 = (A<<24)|(B<<16)|(G<<8)|R（0xAABBGGRR）
     * 之前写成 (R<<24)|(G<<16)|(B<<8)|A 导致通道错位：橙(255,140,0) 显示成品红(255,0,140)。
     */
    private static int argbToRgba(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /** 等比缩小到 maxSize 以内（长边） */
    private static BufferedImage scaleDown(BufferedImage src, int maxSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSize && h <= maxSize) {
            return src;
        }
        double ratio = Math.min((double) maxSize / w, (double) maxSize / h);
        int nw = Math.max(1, (int) (w * ratio));
        int nh = Math.max(1, (int) (h * ratio));
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return scaled;
    }

    /**
     * 静态图健壮解码：依次尝试三套解码器，任一成功即返回。
     * 1) Minecraft 原生 STB 解码（NativeImage.read）——对游戏常见 PNG/JPG 兼容性最佳，
     *    也是 MC 自身纹理加载路径，能处理部分 Java ImageIO 拒绝的 PNG。
     * 2) JDK 内置 ImageIO——覆盖最广的通用格式。
     * 3) AWT Toolkit 平台原生解码——能处理上面两者都失败的个别 PNG（如特殊色彩类型/ICC）。
     */
    private static BufferedImage loadImageRobust(File f) {
        // 0) WebP（常被错命名为 png/jpg）：按 magic 嗅探后用 TwelveMonkeys 直接解码。
        //    JDK 内置 ImageIO 不支持 WebP，且本模组排除了 TwelveMonkeys 的 SPI 服务文件
        //    （避免多 jar 的 META-INF/services 合并冲突），故代码直接实例化 reader。
        //    WebPImageReader 为包级可见，经 WebPImageReaderSpi.createReaderInstance() 取出公开父类实例。
        try (InputStream snif = Files.newInputStream(f.toPath())) {
            byte[] sig = new byte[12];
            if (snif.read(sig) >= 12
                    && sig[0] == 'R' && sig[1] == 'I' && sig[2] == 'F' && sig[3] == 'F'
                    && sig[8] == 'W' && sig[9] == 'E' && sig[10] == 'B' && sig[11] == 'P') {
                WebPImageReaderSpi spi = new WebPImageReaderSpi();
                try (ImageInputStream iis = ImageIO.createImageInputStream(f)) {
                    ImageReader reader = spi.createReaderInstance(null);
                    reader.setInput(iis);
                    BufferedImage webp = reader.read(0);
                    reader.dispose();
                    if (webp != null) {
                        return webp;
                    }
                }
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.debug("[Media] WebP 解码失败, 回退: {}", f.getName(), t);
        }
        // 1) NativeImage (STB)
        try (InputStream in = Files.newInputStream(f.toPath())) {
            NativeImage ni = NativeImage.read(in);
            if (ni != null) {
                BufferedImage bi = nativeImageToBufferedImage(ni);
                ni.close();
                return bi;
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.debug("[Media] NativeImage 解码失败, 回退: {}", f.getName(), t);
        }
        // 2) ImageIO
        try {
            BufferedImage img = ImageIO.read(f);
            if (img != null) {
                return img;
            }
        } catch (Throwable ignore) {
        }
        // 3) AWT Toolkit（平台原生解码器）
        try {
            Image awt = java.awt.Toolkit.getDefaultToolkit().createImage(f.getAbsolutePath());
            BufferedImage out = toBufferedImage(awt);
            if (out != null) {
                return out;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** NativeImage(0xAABBGGRR) → BufferedImage(0xAARRGGBB) */
    private static BufferedImage nativeImageToBufferedImage(NativeImage ni) {
        int w = ni.getWidth();
        int h = ni.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = ni.getPixelRGBA(x, y);
                int a = (c >>> 24) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int r = c & 0xFF;
                bi.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return bi;
    }

    /** 用 MediaTracker 强制解码 AWT Image 为 BufferedImage（处理平台解码器的异步加载） */
    private static BufferedImage toBufferedImage(Image img) {
        if (img == null) {
            return null;
        }
        MediaTracker tracker = new MediaTracker(new Canvas());
        tracker.addImage(img, 0);
        try {
            tracker.waitForAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int w = img.getWidth(null);
        int h = img.getHeight(null);
        if (w <= 0 || h <= 0) {
            return null;
        }
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics g = bi.getGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return bi;
    }

    /** 解码失败时读取 PNG IHDR（宽高/位深/色彩类型），便于定位为何该 PNG 无法显示 */
    private static void logImageDiagnostics(String path, File f) {
        if (!path.toLowerCase().endsWith(".png")) {
            return;
        }
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] head = new byte[33];
            int n = in.read(head);
            if (n >= 26 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                int width = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16)
                        | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
                int height = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16)
                        | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
                int bitDepth = head[24] & 0xFF;
                int colorType = head[25] & 0xFF;
                FlapDisplayPlus.LOGGER.warn(
                        "[Media] PNG 诊断 {}: {}x{} bitDepth={} colorType={} (0=灰 2=RGB 3=调色板 4=灰+α 6=RGBA)",
                        f.getName(), width, height, bitDepth, colorType);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 资源重载时清空全部缓存（停止所有视频播放器与网页图流） */
    public static void clearCache() {
        TEXTURES.clear();
        DIMENSIONS.clear();
        ANIMS.clear();
        for (VideoPlayer vp : VIDEOS.values()) {
            vp.stop();
        }
        VIDEOS.clear();
        LOADING.clear();
        FAILED.clear();
    }

    /** 动图数据：帧纹理 + 每帧延迟 */
    public static final class AnimData {
        public final List<ResourceLocation> frames = new ArrayList<>();
        public final List<Long> delaysMs = new ArrayList<>();
        public long totalDelayMs = 0;
        public int width = 0;
        public int height = 0;
    }
}
