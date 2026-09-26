package com.flapdisplayplus.music.mixin;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.flapdisplayplus.music.config.Config;
import com.flapdisplayplus.music.network.SeekMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 暂停续播 Mixin：拦截 CD 播放机的「从头播放」逻辑。
 *
 * 原理：
 * Net Music 的 setPlayToClient() 会在异步回调里调用 setCurrentTime(songTime*20+64)，
 * 把剩余时间重置为整首歌的时长——这就是「暂停后继续播放从头开始」的根因。
 *
 * 这里在 setPlayToClient() 开头检测是否为「暂停续播」（未播放且处于歌曲中途），
 * 若是，则把下一次 setCurrentTime() 的重置拦截掉，让剩余时间保持暂停时的位置，
 * 并把续播起始位置通过 SeekMessage 发给客户端，让客户端声音也从暂停点继续。
 */
@Mixin(TileEntityMusicPlayer.class)
public abstract class TileEntityMusicPlayerMixin {

    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    /** 标记下一次 setCurrentTime() 是否为「从头播放的重置」，需要被拦截 */
    @Unique
    private volatile boolean netmusicdisplay$resuming = false;

    @Shadow
    public abstract boolean isPlay();

    @Shadow
    public abstract int getCurrentTime();

    /**
     * 拦截 tickTime()：暂停时冻结剩余时间，不再递减。
     *
     * 根因：Net Music 的 tickTime() 无条件递减 currentTime，不检查 isPlay。
     * 导致暂停后（isPlay=false）剩余时间仍在走，歌词/时间显示继续跳，
     * 而客户端音频已停——表现为「歌停了但时间还在走」。
     */
    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void netmusicdisplay$freezeWhenPaused(CallbackInfo ci) {
        if (!isPlay()) {
            ci.cancel();
        }
    }

    /**
     * 在 setPlayToClient() 开头判断本次是否为暂停续播。
     * 条件是：暂停续播开关开启、当前未播放、且剩余时间处于 (0, 总时长) 区间。
     * 若是续播，立即把起始位置发给客户端（SeekMessage 先于 MusicToClientMessage 到达）。
     *
     * 【2026-09-27 修正 total 的算法】
     * 原版链路的真实基准（javap 验证 TileEntityMusicPlayer / NetMusicSound 字节码）：
     *   lambda$setPlayToClient$0  → setCurrentTime(songTime * 20 + 64)   ← +64 是余量
     *   每个 tick 都无条件 tickTime() 把 currentTime 减 1（到 0 停）
     *   时间耗尽后 tick() 里再 setPlayToClient(songInfo) 触发重新播放
     *   NetMusicSound.tickTimes  = timeSecond * 20                       ← 无 +64
     *
     * 所以「暂停瞬间正在播到第几秒」= (songTime*20 + 64) - currentTime，
     * 不能再拿 total 当歌曲总长去算 seek —— 那会多算 64 tick（3.2 秒），
     * 表现为续播位置偏后（+3.2 秒）且靠近结尾时干脆判成「没在播」而不续播。
     */
    @Inject(method = "setPlayToClient", at = @At("HEAD"))
    private void netmusicdisplay$detectResume(ItemMusicCD.SongInfo info, CallbackInfo ci) {
        if (!isPauseResumeEnabled()) {
            netmusicdisplay$resuming = false;
            return;
        }
        int songTicks = info.songTime * 20;   // 歌曲真实长度（tick），与 NetMusicSound.tickTimes 同源
        int total = songTicks + 64;           // 原版给 currentTime 的初始值（含 64 tick 余量）
        int current = getCurrentTime();
        int played = total - current;         // 暂停时已经播放到的位置（tick）
        // 未在播放且处于歌曲中途 → 视为续播，拦截即将发生的重置
        boolean resuming = !isPlay() && current > 0 && current < total
                && played > 0 && played < songTicks;
        netmusicdisplay$resuming = resuming;
        LOGGER.info("[NetMusicDisplay] detectResume: isPlay={} current={} total={} playedTick={} ({}.{}秒) resuming={}",
                isPlay(), current, total, played, played / 20, played % 20, resuming);
        if (resuming) {
            LOGGER.info("[NetMusicDisplay] 续播：startTick={} ({}秒)", played, played / 20.0);
            netmusicdisplay$sendSeek(played);
        }
    }

    /**
     * 拦截「从头播放」的剩余时间重置。
     * 仅在检测到续播时生效，取消本次重置，保持暂停位置不变。
     */
    @Inject(method = "setCurrentTime", at = @At("HEAD"), cancellable = true)
    private void netmusicdisplay$blockTimeReset(int time, CallbackInfo ci) {
        if (netmusicdisplay$resuming) {
            LOGGER.info("[NetMusicDisplay] blockTimeReset: 拦截 setCurrentTime({}), 保持 currentTime={}", time, getCurrentTime());
            ci.cancel();
            netmusicdisplay$resuming = false;
        }
    }

    /** 把续播起始位置发给附近玩家（强转 BlockEntity 拿 level/pos，避免 @Shadow 父类成员） */
    @Unique
    private void netmusicdisplay$sendSeek(int startTick) {
        try {
            BlockEntity be = (BlockEntity) (Object) this;
            Level lvl = be.getLevel();
            if (lvl == null) {
                return;
            }
            BlockPos pos = be.getBlockPos();
            NetworkHandler.sendToNearby(lvl, pos, new SeekMessage(pos, startTick));
        } catch (Exception e) {
            LOGGER.error("[NetMusicDisplay] 发送续播位置失败", e);
        }
    }

    /** 安全读取暂停续播开关：配置未加载/读取异常时按默认开启处理，绝不崩溃 */
    @Unique
    private static boolean isPauseResumeEnabled() {
        try {
            return Config.PAUSE_RESUME.get();
        } catch (Throwable t) {
            return true;
        }
    }
}
