package com.flapdisplayplus.music.mixin;

import com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream;
import com.flapdisplayplus.music.client.ResumeTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.net.URL;

/**
 * 给 Net Music 的 NetMusicAudioStream 注入 seek 能力。
 *
 * NetMusicAudioStream 内部持有转码成 PCM 的 AudioInputStream（final stream 字段），
 * 音频数据由 loadAudioData() 懒加载（第一次 read 时才在异步线程读 stream）。
 *
 * 续播位置通过普通类 ResumeTracker.pendingSeekTick 传递：
 * NetMusicSound 构造时写入，本类构造完成后（首次 read 之前）消费并 seek。
 */
@Mixin(NetMusicAudioStream.class)
public abstract class NetMusicAudioStreamMixin {

    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    @Shadow
    @Final
    private AudioInputStream stream;

    /**
     * 在 pumpBuffers(4) 之前 seek：NetMusicAudioStream 构造器在 pumpBuffers(4) 里
     * 就从 this.stream 的 0 位置同步读取并解码入队；若 seek 放到 <init> RETURN
     * （pumpBuffers 之后），第一批缓冲区已是 0 位置音频，seek 后才跳到目标位置，
     * 表现为「先从头播再突然跳到当前歌词位置」。
     * 故必须在 pumpBuffers 之前、且 this.stream 已赋值之后执行 seek。
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/github/tartaricacid/netmusic/client/audio/NetMusicAudioStream;pumpBuffers(I)V",
            shift = At.Shift.BEFORE))
    private void netmusicdisplay$applySeek(URL url, CallbackInfo ci) {
        if (this.stream == null) {
            return;
        }
        int seek = ResumeTracker.pendingSeekTick;
        if (seek > 0) {
            ResumeTracker.pendingSeekTick = 0;
            netmusicdisplay$seek(seek);
        }
    }

    /**
     * 把底层音频流 seek 到指定 tick。
     * 先用 skip 快速跳（底层 DecodedMpegAudioInputStream 支持 skipFrames 快速跳帧），
     * 若 skip 不足再用 read 精确补偿。
     * 换算：字节数 = (tick / 20 秒) × 采样率 × 每帧字节数。
     */
    @Unique
    private void netmusicdisplay$seek(int tick) {
        try {
            AudioFormat format = this.stream.getFormat();
            int frameSize = format.getFrameSize();
            float frameRate = format.getFrameRate();
            if (frameSize <= 0 || frameRate <= 0) {
                return;
            }
            long targetBytes = (long) ((tick / 20.0) * frameRate * frameSize);
            long startTime = System.currentTimeMillis();

            // 先 skip（可能快速跳帧）
            long skipped = 0;
            try {
                skipped = this.stream.skip(targetBytes);
            } catch (Exception ignored) {
            }

            // skip 不足则 read 补偿
            long readCompensate = 0;
            long remaining = targetBytes - skipped;
            if (remaining > 0) {
                byte[] buf = new byte[65536];
                while (remaining > 0) {
                    int n = this.stream.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n <= 0) {
                        break;
                    }
                    remaining -= n;
                    readCompensate += n;
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOGGER.info("[NetMusicDisplay] seek: tick={} targetPcm={} skip返回={} read补偿={} 耗时={}ms frameRate={} frameSize={}",
                    tick, targetBytes, skipped, readCompensate, elapsed, frameRate, frameSize);
        } catch (Exception e) {
            LOGGER.error("[NetMusicDisplay] seek 失败，退化为从头播放", e);
        }
    }
}
