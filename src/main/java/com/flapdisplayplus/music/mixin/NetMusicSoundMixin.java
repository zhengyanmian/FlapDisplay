package com.flapdisplayplus.music.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.flapdisplayplus.music.client.ResumeTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URL;

/**
 * 客户端声音 Mixin：续播位置 + 暂停停止。
 *
 * 两个功能：
 * 1. 构造时从 ResumeTracker 取出续播起始 tick，设置 tick 初值，
 *    并把起始位置写入 ResumeTracker.pendingSeekTick 供 NetMusicAudioStream 消费。
 * 2. tick() 时检查方块实体的 isPlay，若已暂停则把 tick 设到结尾之后，
 *    让原版 tick() 的 stop() 逻辑自然触发，停止声音和粒子。
 *
 * 第 2 点的根因：Net Music 的 setPlay(false) 只设置服务端 isPlay 字段，
 * 客户端的 NetMusicSound 仍在声音管理器中继续 tick（吐粒子+继续播放）。
 * 原版靠 tickTime() 递减 currentTime 到 <16 触发 tick() 停止逻辑，
 * 但我们的 TileEntityMusicPlayerMixin 冻结了 tickTime()，导致停止逻辑永不触发。
 * 后果：暂停后旧声音不停 → 粒子残留 + 下次续播旧声音盖住新声音 → 歌词对不上。
 *
 * 关键修复（hasPlayed 闸门）：
 * 续播时新声音刚创建的那一帧，客户端 isPlay 同步尚未到达（仍为 false），
 * 若此时无条件杀声音，会把“本该续播的新声音”误杀。
 * 用 hasPlayed 区分：只有“曾经播放过、且现在 isPlay=false”的声音才停
 * （即暂停前的旧声音）；续播新声音 hasPlayed=false，不被误杀。
 */
@Mixin(NetMusicSound.class)
public abstract class NetMusicSoundMixin {

    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    @Shadow
    private int tick;

    @Shadow
    @Final
    private BlockPos pos;

    @Shadow
    @Final
    private int tickTimes;

    /** 该声音是否曾经在 isPlay=true 的状态下 tick 过（用于区分旧声音/续播新声音） */
    @Unique
    private boolean netmusicdisplay$hasPlayed = false;

    /** 防止重复记录“已停止”日志 */
    @Unique
    private boolean netmusicdisplay$killed = false;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void netmusicdisplay$initResume(BlockPos pos, URL url, int timeSecond, LyricRecord record, CallbackInfo ci) {
        int startTick = ResumeTracker.take(pos);
        // this.tick 是「已过 tick」计数，原版从 0 起；续播时初值设为已播放位置，
        // 使歌词行（lyricRecord.updateCurrentLine(tick)）也对齐到暂停点。
        // tickTimes = timeSecond * 20 = 整首歌 tick 数（javap 验证），与原版 stop 判定同源。
        if (startTick > 0) {
            this.tick = startTick;
        }
        // 写入待 seek 位置供 NetMusicAudioStream 消费（含 0，清掉上次残留）
        ResumeTracker.pendingSeekTick = startTick;
        LOGGER.info("[NetMusicDisplay] 创建声音 pos={} tickTimes={} startTick={} ({}秒)",
                pos, this.tickTimes, startTick, startTick / 20.0);
    }

    /**
     * 暂停时停止客户端声音。
     *
     * 检查方块实体的 isPlay，若已暂停则把 tick 设到 tickTimes + 51，
     * 让原版 tick() 的 tick++ → 检查 tick > tickTimes + 50 → stop() 逻辑自然触发。
     * 不 cancel，让原版代码继续跑（它会执行 stop() + 清理 lyricRecord）。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void netmusicdisplay$stopWhenPaused(CallbackInfo ci) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        BlockEntity be = level.getBlockEntity(this.pos);
        if (!(be instanceof TileEntityMusicPlayer player)) {
            return;
        }
        if (player.isPlay()) {
            // 正在播放：标记“曾经播放过”。续播新声音在 isPlay 同步到达前会短暂 isPlay=false，
            // 但此时 hasPlayed 仍为 false，不会被误杀。
            netmusicdisplay$hasPlayed = true;
            return;
        }
        // 已暂停：只有“曾经播放过”的声音才是暂停前的旧声音，需要停止；
        // 续播新声音（hasPlayed=false）即使在 isPlay 同步延迟期间看到 isPlay=false 也不杀。
        if (netmusicdisplay$hasPlayed && !netmusicdisplay$killed) {
            netmusicdisplay$killed = true;
            this.tick = this.tickTimes + 51;
            LOGGER.info("[NetMusicDisplay] 暂停停止声音 pos={} tick={}/{}", this.pos, this.tick, this.tickTimes);
        }
    }
}
