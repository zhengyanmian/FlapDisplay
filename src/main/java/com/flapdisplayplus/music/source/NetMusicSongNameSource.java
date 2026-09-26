package com.flapdisplayplus.music.source;

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
 * 自定义显示数据源：读取 Net Music CD 播放机里当前唱片的名字。
 *
 * 工作流程：
 * 1. 显示链接器（Display Link）被玩家放置并指向某个方块时，Create 会调用该方块的「数据源」。
 * 2. provideLine() 里我们拿到链接器指向的方块位置（即 CD 播放机），
 *    从它的物品栏第 0 格取出 CD 物品，读出 SongInfo 里的 songName（歌曲名）和 artists（歌手）。
 * 3. 返回的文本会显示在翻牌显示器 / 霓虹灯管 / 牌子上。
 *
 * 注意：歌词（lyricRecord）在 Net Music 中是「仅客户端」字段，服务端拿不到，
 * 所以这里只显示歌曲名（服务端 CD 物品 NBT 里就有），无法显示实时歌词行。
 */
public class NetMusicSongNameSource extends SingleLineDisplaySource {

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        DisplayLinkBlockEntity gatherer = context.blockEntity();
        BlockPos sourcePos = gatherer.getSourcePosition();
        BlockEntity be = context.level().getBlockEntity(sourcePos);

        // 链接器指向的是不是 Net Music 的 CD 播放机？
        if (be instanceof TileEntityMusicPlayer musicPlayer) {
            ItemStack cd = musicPlayer.getPlayerInv().getStackInSlot(0);
            if (!cd.isEmpty()) {
                ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
                if (info != null && info.songName != null && !info.songName.isEmpty()) {
                    MutableComponent line = Component.literal(info.songName);
                    // 附上歌手，格式：歌曲名 - 歌手A / 歌手B
                    if (info.artists != null && !info.artists.isEmpty()) {
                        line = line.append(Component.literal(" - " + String.join(" / ", info.artists)));
                    }
                    // 暂停时加暂停符号提示（根据配置）
                    if (!musicPlayer.isPlay() && Config.PAUSE_SYMBOL_ENABLED.get()) {
                        line = line.append(Component.literal(" ■"));
                    }
                    return line;
                }
            }
        }

        // 没有唱片 / 不是 CD 播放机 → 显示空行
        return EMPTY_LINE;
    }

    @Override
    protected String getTranslationKey() {
        return "netmusic_song_name";
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        // 允许玩家在显示链接器 GUI 里加自定义前缀标签
        return true;
    }
}
