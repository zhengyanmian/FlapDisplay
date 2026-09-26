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
 * 双行歌词数据源：原歌词 + 翻译歌词各一行，一个链接器搞定两种歌词。
 *
 * 输出行：
 *   行1：当前原歌词行
 *   行2：当前翻译歌词行（无翻译时显示"无翻译歌词"）
 *
 * 翻牌显示器至少需要 2 行高。
 * 配合"播放状态"数据源使用，只需 2 个显示链接器即可显示全部信息。
 */
public class NetMusicDualLyricSource extends DisplaySource {

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
        if (info == null || info.songUrl == null || info.songName == null) {
            return lines;
        }

        // 按 URL 获取歌词（自动分发网易云 / QQ音乐 等平台）
        LyricRecord record = LyricCache.getLyricByUrl(info.songUrl, info.songName);
        if (record == null) {
            lines.add(Component.literal("\u52A0\u8F7D\u6B4C\u8BCD\u4E2D..."));
            return lines;
        }

        // 计算已播放 ticks（暂停时冻结）
        int totalTicks = info.songTime * 20 + 64;
        int playedTicks = totalTicks - musicPlayer.getCurrentTime();
        if (playedTicks < 0) playedTicks = 0;

        // 没在播放：根据配置显示冻结歌词或 ~
        if (!musicPlayer.isPlay()) {
            if (Config.SHOW_LYRIC_WHEN_PAUSED.get()) {
                String originLine = LyricCache.getCurrentLyricLine(record, playedTicks);
                lines.add(Component.literal((originLine != null && !originLine.isEmpty()) ? originLine : "~"));
                if (LyricCache.hasTranslation(record)) {
                    String transLine = LyricCache.getCurrentTransLyricLine(record, playedTicks);
                    lines.add(Component.literal((transLine != null && !transLine.isEmpty()) ? transLine : "~"));
                } else {
                    lines.add(Component.literal("\u65E0\u7FFB\u8BD1\u6B4C\u8BCD"));
                }
            } else {
                lines.add(Component.literal("~"));
                lines.add(Component.literal("~"));
            }
            return lines;
        }

        // 行1：原歌词
        String originLine = LyricCache.getCurrentLyricLine(record, playedTicks);
        lines.add(Component.literal((originLine != null && !originLine.isEmpty()) ? originLine : "~"));

        // 行2：翻译歌词
        if (LyricCache.hasTranslation(record)) {
            String transLine = LyricCache.getCurrentTransLyricLine(record, playedTicks);
            lines.add(Component.literal((transLine != null && !transLine.isEmpty()) ? transLine : "~"));
        } else {
            lines.add(Component.literal("\u65E0\u7FFB\u8BD1\u6B4C\u8BCD"));
        }

        return lines;
    }

    @Override
    protected String getTranslationKey() {
        return "netmusic_dual_lyric";
    }
}
