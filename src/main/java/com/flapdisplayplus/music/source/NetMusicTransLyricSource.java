package com.flapdisplayplus.music.source;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.flapdisplayplus.music.config.Config;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 自定义显示数据源：显示 Net Music CD 播放机的当前【翻译歌词】行。
 *
 * 与 NetMusicLyricSource（原歌词）几乎一样，区别：
 * - 使用 LyricCache.getCurrentTransLyricLine() 读取翻译歌词
 * - 如果该歌曲没有翻译歌词（纯中文歌或网易云未提供翻译），显示"无翻译歌词"
 *
 * 适用场景：英语、日语等外语歌，网易云提供了中文翻译时，用这个数据源显示翻译。
 */
public class NetMusicTransLyricSource extends SingleLineDisplaySource {

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        DisplayLinkBlockEntity gatherer = context.blockEntity();
        BlockPos sourcePos = gatherer.getSourcePosition();
        BlockEntity be = context.level().getBlockEntity(sourcePos);

        if (be instanceof TileEntityMusicPlayer musicPlayer) {
            ItemStack cd = musicPlayer.getPlayerInv().getStackInSlot(0);
            if (cd.isEmpty()) {
                return EMPTY_LINE;
            }

            ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
            if (info == null || info.songUrl == null || info.songName == null) {
                return EMPTY_LINE;
            }

            // 按 URL 获取歌词（自动分发网易云 / QQ音乐 等平台）
            LyricRecord record = LyricCache.getLyricByUrl(info.songUrl, info.songName);
            if (record == null) {
                return Component.literal("加载歌词中...");
            }

            // 该歌曲没有翻译歌词（纯中文歌或平台未提供翻译）
            if (!LyricCache.hasTranslation(record)) {
                return Component.literal("无翻译歌词");
            }

            // 计算已播放 ticks（暂停时冻结）
            int totalTicks = info.songTime * 20 + 64;
            int playedTicks = totalTicks - musicPlayer.getCurrentTime();
            if (playedTicks < 0) playedTicks = 0;

            // 没在播放：根据配置显示冻结翻译歌词或 ~
            if (!musicPlayer.isPlay()) {
                if (Config.SHOW_LYRIC_WHEN_PAUSED.get()) {
                    String line = LyricCache.getCurrentTransLyricLine(record, playedTicks);
                    if (line != null && !line.isEmpty()) {
                        return Component.literal(line);
                    }
                }
                return Component.literal("~");
            }

            // 查找当前翻译歌词行
            String line = LyricCache.getCurrentTransLyricLine(record, playedTicks);
            if (line == null || line.isEmpty()) {
                return Component.literal("~");
            }

            return Component.literal(line);
        }

        return EMPTY_LINE;
    }

    @Override
    protected String getTranslationKey() {
        return "netmusic_trans_lyric";
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return false;
    }
}
