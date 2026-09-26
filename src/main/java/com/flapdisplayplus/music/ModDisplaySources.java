package com.flapdisplayplus.music;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.source.NetMusicAllInOneSource;
import com.flapdisplayplus.music.source.NetMusicDualLyricSource;
import com.flapdisplayplus.music.source.NetMusicLyricSource;
import com.flapdisplayplus.music.source.NetMusicPlayStatusSource;
import com.flapdisplayplus.music.source.NetMusicSongNameSource;
import com.flapdisplayplus.music.source.NetMusicTransLyricSource;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 负责把自定义的 DisplaySource 注册到 Create 的 DISPLAY_SOURCE 注册表。
 *
 * 注册了六个数据源（封面图文已废弃，封面走 FlapDisplayPlus 图片渲染管线）：
 * 单行（各需 1 个显示链接器）：
 * - netmusic_song_name：歌曲名 + 歌手
 * - netmusic_play_status：播放状态（▶ 播放中 / ■ 已停止）+ 剩余时间
 * - netmusic_lyric：当前原歌词行
 * - netmusic_trans_lyric：当前翻译歌词行
 * 多行（1 个链接器输出多行，省链接器）：
 * - netmusic_all_in_one：综合（状态+歌名 / 原歌词 / 翻译歌词），3 行
 * - netmusic_dual_lyric：原歌词 + 翻译歌词，2 行
 */
public class ModDisplaySources {
    public static final DeferredRegister<DisplaySource> DISPLAY_SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, FlapDisplayPlus.MODID);

    public static final DeferredHolder<DisplaySource, NetMusicSongNameSource> NETMUSIC_SONG_NAME =
            DISPLAY_SOURCES.register("netmusic_song_name", NetMusicSongNameSource::new);

    public static final DeferredHolder<DisplaySource, NetMusicPlayStatusSource> NETMUSIC_PLAY_STATUS =
            DISPLAY_SOURCES.register("netmusic_play_status", NetMusicPlayStatusSource::new);

    public static final DeferredHolder<DisplaySource, NetMusicLyricSource> NETMUSIC_LYRIC =
            DISPLAY_SOURCES.register("netmusic_lyric", NetMusicLyricSource::new);

    public static final DeferredHolder<DisplaySource, NetMusicTransLyricSource> NETMUSIC_TRANS_LYRIC =
            DISPLAY_SOURCES.register("netmusic_trans_lyric", NetMusicTransLyricSource::new);

    public static final DeferredHolder<DisplaySource, NetMusicAllInOneSource> NETMUSIC_ALL_IN_ONE =
            DISPLAY_SOURCES.register("netmusic_all_in_one", NetMusicAllInOneSource::new);

    public static final DeferredHolder<DisplaySource, NetMusicDualLyricSource> NETMUSIC_DUAL_LYRIC =
            DISPLAY_SOURCES.register("netmusic_dual_lyric", NetMusicDualLyricSource::new);

    public static void register(IEventBus modBus) {
        DISPLAY_SOURCES.register(modBus);
    }
}
