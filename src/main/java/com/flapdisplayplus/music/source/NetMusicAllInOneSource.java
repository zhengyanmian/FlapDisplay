package com.flapdisplayplus.music.source;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.flapdisplayplus.music.config.Config;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 综合数据源：一个显示链接器同时输出多行，减少所需链接器数量。
 *
 * 输出行：
 *   行1：▶ 歌曲名 [剩余时间]  或  ■ 歌曲名（未播放）
 *   行2：当前原歌词行（播放中）
 *   行3：当前翻译歌词行（播放中，有翻译才显示）
 *
 * 直接继承 DisplaySource（而非 SingleLineDisplaySource），通过 provideText 返回多行 List。
 * 翻牌显示器至少需要 3 行高才能完整显示。
 */
public class NetMusicAllInOneSource extends DisplaySource {

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        List<MutableComponent> lines = new ArrayList<>();

        DisplayLinkBlockEntity gatherer = context.blockEntity();
        BlockPos sourcePos = gatherer.getSourcePosition();
        BlockEntity be = context.level().getBlockEntity(sourcePos);

        if (!(be instanceof TileEntityMusicPlayer musicPlayer)) {
            return lines;
        }

        ItemStack cd = musicPlayer.getPlayerInv().getStackInSlot(0);
        if (cd.isEmpty()) {
            return lines;
        }

        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null || info.songName == null || info.songName.isEmpty()) {
            return lines;
        }

        // 计算时间（暂停时 currentTime 冻结，playedTicks 也恒定）
        int totalTicks = info.songTime * 20 + 64;
        int playedTicks = totalTicks - musicPlayer.getCurrentTime();
        if (playedTicks < 0) playedTicks = 0;
        int totalSeconds = totalTicks / 20;
        int playedSeconds = playedTicks / 20;
        int playedMin = playedSeconds / 60;
        int playedSec = playedSeconds % 60;
        int totalMin = totalSeconds / 60;
        int totalSec = totalSeconds % 60;

        // 歌词部分（先获取，播放/暂停都需要；按 URL 自动分发平台）
        LyricRecord record = LyricCache.getLyricByUrl(info.songUrl, info.songName);

        // 行1：播放状态 + 歌曲名 + 时间
        if (musicPlayer.isPlay()) {
            int remainingTicks = musicPlayer.getCurrentTime();
            int remainingSeconds = Math.max(0, remainingTicks / 20);
            int min = remainingSeconds / 60;
            int sec = remainingSeconds % 60;
            lines.add(Component.literal(String.format("\u25B6 %s [%d:%02d]", info.songName, min, sec)));
        } else {
            // 暂停状态
            if (Config.SHOW_PAUSE_TIME.get()) {
                lines.add(Component.literal(String.format("\u25A0 %s [%d:%02d / %d:%02d]",
                        info.songName, playedMin, playedSec, totalMin, totalSec)));
            } else {
                lines.add(Component.literal("\u25A0 " + info.songName));
            }
            // 暂停时根据配置决定是否显示歌词
            if (!Config.SHOW_LYRIC_WHEN_PAUSED.get()) {
                return lines;
            }
        }

        // 歌词显示
        if (record == null) {
            lines.add(Component.literal("\u52A0\u8F7D\u6B4C\u8BCD\u4E2D..."));
            return lines;
        }

        // 行2：原歌词
        String originLine = LyricCache.getCurrentLyricLine(record, playedTicks);
        lines.add(Component.literal((originLine != null && !originLine.isEmpty()) ? originLine : "~"));

        // 行3：翻译歌词（有翻译才加，节省行数）
        if (LyricCache.hasTranslation(record)) {
            String transLine = LyricCache.getCurrentTransLyricLine(record, playedTicks);
            lines.add(Component.literal((transLine != null && !transLine.isEmpty()) ? transLine : "~"));
        }

        return lines;
    }

    @Override
    protected String getTranslationKey() {
        return "netmusic_all_in_one";
    }
}
