package com.flapdisplayplus.music.client;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 续播位置传递（普通类，非 Mixin）。
 *
 * 关键：前两次音频续播失败，根因是 Mixin 类之间相互引用（静态字段 / 接口 cast）
 * 在 NeoForge 无 refmap 下会被错误映射。这里用普通类作为中转，规避该问题。
 *
 * 三条路径：
 * 1. 网络：服务端 SeekMessage 先到 → {@link #put(BlockPos, int)} 存入 Map；
 *    NetMusicSound 构造时 {@link #take(BlockPos)} 取出。
 * 2. 声音 seek：NetMusicSound 构造器写 {@link #pendingSeekTick}；
 *    NetMusicAudioStream 构造器（后台线程）读并清空。
 * 3. 【2026-09-27 新增】超时兜底：见 {@link #take(BlockPos)} 的说明。
 *
 * 【为什么要有超时兜底】
 * 客户端的声音可能被重建（例如 MediaRenderRegistry 过期导致渲染停摆、维度切换、
 * 音频线重开）。一旦 NetMusicSound 被重建而我们又已经 take() 消费掉续播位置，
 * 新建的声音拿不到位置 → tick 归 0 → 从头播，而服务端歌词已在中段
 * ⇒ 表现为「续播不回到暂停点，反而往后跳一大截」（歌词与声音错位）。
 *
 * 因此这里让记录带时间戳并保留 {@link #TTL_MS}，同一次续播在 TTL 内可被重复读取；
 * 由「服务端再次发 SeekMessage」或「TTL 自然过期」来结束这一次续播状态。
 */
public final class ResumeTracker {

    /** 一条续播记录的有效期（毫秒）。覆盖声音重建的窗口，又不会永久粘住。 */
    private static final long TTL_MS = 30000L;

    /** 待 seek 位置（已播放 tick）。NetMusicSound 构造器写，NetMusicAudioStream 构造器读，跨线程故 volatile */
    public static volatile int pendingSeekTick = 0;

    /** 播放机位置 → [起始 tick, 写入时间戳] */
    private static final Map<BlockPos, long[]> PENDING = new ConcurrentHashMap<>();

    private ResumeTracker() {
    }

    /** 存入续播位置 */
    public static void put(BlockPos pos, int startTick) {
        if (pos == null) {
            return;
        }
        if (startTick > 0) {
            PENDING.put(pos, new long[]{startTick, System.currentTimeMillis()});
        } else {
            // 明确的无续播：清掉旧记录，避免上次续播残留影响这次从头播放
            PENDING.remove(pos);
        }
    }

    /**
     * 取出续播位置（返回 0 表示没有）。
     *
     * 读取**不清除**，仅在 TTL 过期时视为无续播。这样声音被重建时仍能拿到同一位置；
     * 本次续播结束后由 TTL 或服务端新的 SeekMessage 收尾。
     */
    public static int take(BlockPos pos) {
        if (pos == null) {
            return 0;
        }
        long[] v = PENDING.get(pos);
        if (v == null) {
            return 0;
        }
        if (System.currentTimeMillis() - v[1] > TTL_MS) {
            PENDING.remove(pos);
            return 0;
        }
        return (int) v[0];
    }

    /** 清除某播放机的续播记录（歌曲切歌/停止时调用） */
    public static void clear(BlockPos pos) {
        if (pos != null) {
            PENDING.remove(pos);
        }
    }
}

