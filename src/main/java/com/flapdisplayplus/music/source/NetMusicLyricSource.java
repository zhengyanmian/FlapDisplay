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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 自定义显示数据源：显示 Net Music CD 播放机的当前歌词行。
 *
 * 工作流程：
 * 1. 从 CD 物品的 SongInfo.songUrl 提取网易云歌曲 ID
 * 2. 通过 LyricCache 异步获取歌词（首次请求时从网易云 API 拉取，之后缓存）
 * 3. 根据 CD 播放机的播放进度（currentTime）计算已播放 tick 数
 * 4. 在歌词时间轴中查找当前 tick 对应的歌词行
 *
 * 注意事项：
 * - 只有网易云歌曲才有歌词（URL 格式 ?id=数字.mp3）
 * - 歌词获取是异步的，第一次会显示"加载歌词中..."，几秒后才能显示
 * - 服务端进度和客户端实际播放可能有几秒偏差，但歌词显示足够用
 */
public class NetMusicLyricSource extends SingleLineDisplaySource {
    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");
    private static volatile boolean firstCallLogged = false;

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
                if (!firstCallLogged) {
                    LOGGER.warn("[LyricSource] SongInfo is null or incomplete. info={}, songUrl={}, songName={}",
                            info, info != null ? info.songUrl : "null", info != null ? info.songName : "null");
                }
                return EMPTY_LINE;
            }

            // 首次调用时记录详细信息，帮助排查
            if (!firstCallLogged) {
                firstCallLogged = true;
                LOGGER.info("[LyricSource] First call diagnostic:");
                LOGGER.info("[LyricSource]   songName={}", info.songName);
                LOGGER.info("[LyricSource]   songUrl={}", info.songUrl);
                LOGGER.info("[LyricSource]   songTime={}s", info.songTime);
                LOGGER.info("[LyricSource]   isPlay={}", musicPlayer.isPlay());
                LOGGER.info("[LyricSource]   currentTime={}", musicPlayer.getCurrentTime());
                LOGGER.info("[LyricSource]   level.isClientSide={}", context.level().isClientSide());
                LOGGER.info("[LyricSource]   sourcePos={}", sourcePos);
            }

            // 按 URL 获取歌词（自动分发网易云 / QQ音乐 等平台）
            LyricRecord record = LyricCache.getLyricByUrl(info.songUrl, info.songName);
            if (record == null) {
                return Component.literal("加载歌词中...");
            }

            // 计算已播放 ticks（暂停时 currentTime 不变，playedTicks 也冻结）
            int totalTicks = info.songTime * 20 + 64;
            int playedTicks = totalTicks - musicPlayer.getCurrentTime();
            if (playedTicks < 0) playedTicks = 0;

            // 没在播放：根据配置显示冻结歌词或 ~
            if (!musicPlayer.isPlay()) {
                if (Config.SHOW_LYRIC_WHEN_PAUSED.get()) {
                    String line = LyricCache.getCurrentLyricLine(record, playedTicks);
                    if (line != null && !line.isEmpty()) {
                        return Component.literal(line);
                    }
                }
                return Component.literal("~");
            }

            // 查找当前歌词行
            String line = LyricCache.getCurrentLyricLine(record, playedTicks);
            if (line == null || line.isEmpty()) {
                return Component.literal("~");
            }

            return Component.literal(line);
        }

        return EMPTY_LINE;
    }

    @Override
    protected String getTranslationKey() {
        return "netmusic_lyric";
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return false;
    }
}
