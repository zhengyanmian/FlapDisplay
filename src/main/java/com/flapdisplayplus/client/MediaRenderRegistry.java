/*
 * MediaRenderRegistry.java
 *
 * 客户端媒体渲染注册表：
 * 记录「翻牌显示器坐标 → 要显示的媒体配置」。数据由服务端通过 MediaDisplayPacket
 * 同步。FlapDisplayRenderer 的 Mixin 渲染每个翻牌时查询这里。
 */
package com.flapdisplayplus.client;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MediaRenderRegistry {

    private MediaRenderRegistry() {
    }

    /** 翻牌坐标 → 媒体配置 */
    private static final Map<BlockPos, MediaInfo> INFO = new ConcurrentHashMap<>();
    /** 翻牌坐标 → 最后更新时间戳（用于清理失效配置） */
    private static final Map<BlockPos, Long> TIMESTAMPS = new ConcurrentHashMap<>();
    /** 配置过期时间（毫秒）：显示链接器持续发 MediaDisplayPacket 刷新，停发即认为已移除 */
    private static final long EXPIRE_MS = 5000;

    public static void put(BlockPos pos, String mediaPath, String displayMode) {
        if (pos == null || mediaPath == null || mediaPath.isEmpty()) {
            INFO.remove(pos);
            TIMESTAMPS.remove(pos);
        } else {
            INFO.put(pos, new MediaInfo(mediaPath, displayMode));
            TIMESTAMPS.put(pos, System.currentTimeMillis());
        }
    }

    public static void remove(BlockPos pos) {
        INFO.remove(pos);
        TIMESTAMPS.remove(pos);
    }

    public static MediaInfo get(BlockPos pos) {
        Long t = TIMESTAMPS.get(pos);
        if (t != null && System.currentTimeMillis() - t > EXPIRE_MS) {
            // 链接器已移除/停发：清理失效配置，翻牌恢复原版字符显示
            remove(pos);
            return null;
        }
        return INFO.get(pos);
    }

    /** 当前注册表条目数（诊断用） */
    public static int size() {
        return INFO.size();
    }

    /** 所有已注册的翻牌坐标（诊断用） */
    public static java.util.Set<BlockPos> keys() {
        return INFO.keySet();
    }

    public static void clearAll() {
        INFO.clear();
        TIMESTAMPS.clear();
    }

    public static final class MediaInfo {
        public final String mediaPath;
        public final String displayMode;

        public MediaInfo(String mediaPath, String displayMode) {
            this.mediaPath = mediaPath;
            this.displayMode = displayMode;
        }
    }
}
