package com.flapdisplayplus.music.mixin;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暂停续播 Mixin：拦截 CD 播放机的「从头播放」逻辑。
 *
 * ============================ 原版链路（javap 逐条验证） ============================
 *
 *   setPlayToClient(info)
 *     └─ MusicPlayResolverManager.resolve(info)            // 异步解析真实音频地址
 *        .thenAcceptAsync(lambda$setPlayToClient$0, server) // ★ 在服务端线程池里执行
 *             ├─ setCurrentTime(info.songTime * 20 + 64)   // ← 把「剩余时间」重置为整首歌
 *             ├─ this.isPlay = true
 *             └─ sendToNearby(MusicToClientMessage(...))    // 通知客户端开始播放
 *
 *   每 tick：tickTime() 无条件把 currentTime 减 1（减到 0 为止）
 *   归零后 tick() 里再 setPlayToClient(...) → 循环播放
 *
 *   客户端 NetMusicSound.tickTimes = timeSecond * 20（整首歌 tick 数），无 +64
 *
 * 「暂停后继续播放会从头开始」的根因就是那行 setCurrentTime(songTime*20+64)。
 *
 * ============================ 本类的做法与两个坑 ============================
 *
 * 【坑一：+64 不是歌曲长度】
 *   currentTime 的初值是 songTime*20 + 64，其中 +64 只是原版留的余量，
 *   歌曲真实长度是 songTime*20（与客户端 tickTimes 同源）。
 *   因此「暂停时已播到第几 tick」= (songTime*20 + 64) - currentTime。
 *   早期版本误把 (songTime*20+64) 当作总长去算 seek 起点，多算了 64 tick ≈ 3.2 秒，
 *   表现为续播位置偏后；且越接近结尾越容易判成「没在播」而完全不续播。
 *
 * 【坑二：不能用一个实例字段当「本次是续播」的标志】
 *   setCurrentTime() 是在 resolve(...) 的**异步回调**里调用的（服务端线程池），
 *   与我们检测续播的时刻相隔任意长；若同一玩家/同一方块在此期间再次触发播放，
 *   标志就会被覆盖。早期版本用 `private volatile boolean resuming` 单字段，属于竞态。
 *   改为按「方块坐标 → 该次续播的起始 tick」的 Map，回调里按坐标取用。
 */
@Mixin(TileEntityMusicPlayer.class)
public abstract class TileEntityMusicPlayerMixin {

    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    /**
     * 方块坐标 → 本次待拦截的「从头播放」重置所对应的续播起始 tick。
     *
     * key 用 BlockPos 而非实例字段，规避 setPlayToClient 的异步回调竞态；
     * value 为 -1 表示「本次确实要拦截重置，但起点是 0（从头）」这种边界情况不使用——
     * 只在真的续播（tick > 0）时放入，取用后立即移除。
     */
    @Unique
    private static final Map<BlockPos, Integer> netmusicdisplay$pendingResume = new ConcurrentHashMap<>();

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
    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true, remap = false)
    private void netmusicdisplay$freezeWhenPaused(CallbackInfo ci) {
        if (!isPlay()) {
            ci.cancel();
        }
    }

    /**
     * 在 setPlayToClient() 开头判断本次是否为暂停续播，并把起始位置发给客户端。
     *
     * 条件：暂停续播开关开启、当前未播放、且已播位置处于 (0, 歌曲长度) 之间。
     * 若是续播：记入 pendingResume（供异步回调里的 setCurrentTime 拦截使用），
     * 并立即发 SeekMessage —— 它必须早于 MusicToClientMessage 到达客户端，
     * 否则 NetMusicSound 构造时读不到续播位置。
     */
    @Inject(method = "setPlayToClient", at = @At("HEAD"), remap = false)
    private void netmusicdisplay$detectResume(ItemMusicCD.SongInfo info, CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        BlockPos pos = be.getBlockPos();

        if (!isPauseResumeEnabled()) {
            netmusicdisplay$pendingResume.remove(pos);
            return;
        }

        int songTicks = info.songTime * 20;   // 歌曲真实长度（tick），与客户端 tickTimes 同源
        int total = songTicks + 64;           // 原版赋给 currentTime 的初值（含 64 tick 余量）
        int current = getCurrentTime();
        int played = total - current;         // 暂停时已播放到的位置（tick）

        boolean resuming = !isPlay() && current > 0 && current < total
                && played > 0 && played < songTicks;

        LOGGER.info("[NetMusicDisplay] detectResume: pos={} isPlay={} current={} total={} playedTick={} ({}.{}秒) resuming={}",
                pos, isPlay(), current, total, played, played / 20, played % 20, resuming);

        if (resuming) {
            netmusicdisplay$pendingResume.put(pos, played);
            LOGGER.info("[NetMusicDisplay] 续播：startTick={} ({}秒)", played, played / 20.0);
            netmusicdisplay$sendSeek(pos, played);
        } else {
            netmusicdisplay$pendingResume.remove(pos);
        }
    }

    /**
     * 拦截「从头播放」的剩余时间重置。
     * 仅在本次续播已登记时生效，取消本次重置，保持暂停位置不变。
     *
     * 注意：本方法可能在服务端线程池里被调用（resolve 的异步回调），
     * 所以状态放在按坐标索引的 Map 里，而不是实例字段。
     */
    @Inject(method = "setCurrentTime", at = @At("HEAD"), cancellable = true, remap = false)
    private void netmusicdisplay$blockTimeReset(int time, CallbackInfo ci) {
        BlockPos pos = ((BlockEntity) (Object) this).getBlockPos();
        Integer pending = netmusicdisplay$pendingResume.remove(pos);
        if (pending != null) {
            LOGGER.info("[NetMusicDisplay] blockTimeReset: 拦截 setCurrentTime({})，保持续播位置 {} tick",
                    time, pending);
            ci.cancel();
        }
    }

    /** 把续播起始位置发给附近玩家（强转 BlockEntity 拿 level/pos，避免 @Shadow 父类成员） */
    @Unique
    private void netmusicdisplay$sendSeek(BlockPos pos, int startTick) {
        try {
            BlockEntity be = (BlockEntity) (Object) this;
            Level lvl = be.getLevel();
            if (lvl == null) {
                return;
            }
            // 【Forge 移植】NetMusic 的 NetworkHandler.sendToNearby 只能发它自己通道里
            // 注册过的包；SeekMessage 注册在本模组的 music 通道，必须自己发。
            // NEAR 定向包：发给同一维度内以 pos 为中心 64 格内的所有玩家（与 NetMusic 行为一致）。
            if (lvl instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.flapdisplayplus.music.network.ModNetwork.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.NEAR.with(() ->
                                new net.minecraftforge.network.PacketDistributor.TargetPoint(
                                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 64.0,
                                        serverLevel.dimension())),
                        new SeekMessage(pos, startTick));
            }
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
