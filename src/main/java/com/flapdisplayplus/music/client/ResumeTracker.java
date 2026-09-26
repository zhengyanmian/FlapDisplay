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
 * 两条路径：
 * 1. 网络：服务端 SeekMessage 先到 → {@link #put(BlockPos, int)} 存入 Map；
 *    NetMusicSound 构造时 {@link #take(BlockPos)} 取出。
 * 2. 声音 seek：NetMusicSound 构造器写 {@link #pendingSeekTick}；
 *    NetMusicAudioStream 构造器（后台线程）读并清空。
 */
public final class ResumeTracker {

    /** 待 seek 位置（已播放 tick）。NetMusicSound 构造器写，NetMusicAudioStream 构造器读，跨线程故 volatile */
    public static volatile int pendingSeekTick = 0;

    /** 播放机位置 → 起始 tick（SeekMessage 先于 MusicToClientMessage 到达） */
    private static final Map<BlockPos, Integer> PENDING = new ConcurrentHashMap<>();

    private ResumeTracker() {
    }

    /** 存入续播位置 */
    public static void put(BlockPos pos, int startTick) {
        if (startTick > 0) {
            PENDING.put(pos, startTick);
        }
    }

    /** 取出并清除续播位置（返回 0 表示没有） */
    public static int take(BlockPos pos) {
        Integer v = PENDING.remove(pos);
        return v == null ? 0 : v;
    }
}
