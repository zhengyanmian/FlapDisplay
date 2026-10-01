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

    /**
     * 配置过期时间（毫秒）：服务端持续发 MediaDisplayPacket 刷新，停发即认为已移除。
     *
     * 【2026-09-27 修正】原值 5000 与服务端的刷新节奏贴得太近，一旦服务端因任何原因
     * 少发一两个包（幂等去重、丢包、区块卸载），画面就会当着玩家的面消失几秒再回来。
     * 过期机制只该用于「链接器被拆 / 玩家走远」这类**永久性**失效，因此放宽到 30 秒，
     * 并保证服务端有 1 秒级心跳（见 FlapDisplayMediaSource.HEARTBEAT_MS）作为主保障。
     * 真正的立即清除走 remove()（收到空路径的包），不依赖这个超时。
     *
     * 【2026-09-27 二次修正】30 秒又太长：用户反馈「拆掉方块后视频/图片还在显示」，
     * 若拆的是【显示链接器】，就没有任何包会再发过来，只能等过期 —— 30 秒的残留太刺眼。
     * 现降到 8 秒：服务端心跳是 1.5 秒，8 秒 = 容忍连续丢 5 个包，余量充足；
     * 同时「拆链接器」最迟 8 秒自动恢复原版显示。
     * 另外两条更快的路径：① 拆翻牌 → 客户端每刻核对方块存在性（MediaManager.tick）；
     * ② 拆源/拆链接器 → 服务端 BreakEvent 主动发清空包（ServerBlockEvents）。
     */
    private static final long EXPIRE_MS = 8000;

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
            // 【ESC 暂停不判过期】单机暂停时集成服务器一起停，服务端心跳（1.5s）随之中断，
            // 8 秒后这里的过期判定就会把「仍然挂着」的媒体当成已移除：
            // 关掉网页浏览器（页面状态全丢）→ 回游戏重建 = 页面从头加载（用户实测「暂停 7 秒
            // 后所有网页被重置」）。视频同理会被重播。暂停期间只把时间戳向后推，恢复后重新计时。
            if (MediaManager.isGameMenuPaused()) {
                TIMESTAMPS.put(pos, System.currentTimeMillis());
                return INFO.get(pos);
            }
            // 链接器已移除/停发：清理失效配置，翻牌恢复原版字符显示。
            // 【孤儿音频修复】过期清除同样必须顺手停掉不再被引用的视频播放器，
            // 否则「画面没了、声音还在」（此前清空/拆链接器漏声的路径之一）。
            MediaInfo mi = INFO.get(pos);
            remove(pos);
            if (mi != null) {
                MediaManager.stopVideoIfUnreferenced(mi.mediaPath);
            }
            return null;
        }
        return INFO.get(pos);
    }

    /**
     * 读取当前已登记的配置，**不触发**过期判定。
     *
     * 供收包侧做「状态是否真的变化」判断用：get() 带过期语义，在同一 tick 内
     * 刚写入就被判定过期属于误伤，这里只做纯读。
     */
    public static MediaInfo peek(BlockPos pos) {
        return pos == null ? null : INFO.get(pos);
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
