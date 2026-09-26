/*
 * NetMusicAudioStreamMixin.java
 *
 * 给 Net Music 的 NetMusicAudioStream 注入「按时间 seek」能力，用于暂停续播。
 *
 * ============================ 背景（全部经 javap 字节码验证） ============================
 *
 * NetMusicAudioStream 构造器的关键步骤：
 *   AudioInputStream raw = AudioStreamHandlerManager.handle(url);       // mp3 原始流
 *   AudioFormat pcmFmt   = getTargetPCMAudioFormat(raw.getFormat());    // 目标 PCM 格式
 *   AudioInputStream mid = AudioSystem.getAudioInputStream(pcmFmt, raw);// 转码（javazoom）
 *   this.stream = AudioSystem.getAudioInputStream(pcmFmtFinal, mid);    // 立体声重排
 *   pumpBuffers(4);                                                     // 预读 4 个缓冲
 *
 * 所以 seek 必须作用在 **this.stream**（解码后的 PCM 流）上，且必须在 pumpBuffers(4) 之前，
 * 否则前四个缓冲区已经是 0 位置的音频，表现为「先从头播再突然跳」。
 *
 * ---------------------------- 为什么不能信 stream.skip() ----------------------------
 *
 * 底层解码器是 javazoom 的 DecodedMpegAudioInputStream，其 skip 有两处致命问题：
 *
 * 1) 长度估算不可靠时直接返回 -1：
 *      public long skip(long len) {
 *          if (byteslength > 0 && frameslength > 0) { ... }
 *          return -1L;            // ← 网络流/无 Xing 头的 mp3 经常走这里
 *      }
 *
 * 2) 单位错配（最隐蔽、最危险）：
 *      skip(len) 内部把 len 换算成「MPEG 帧数」→ 调 skipFrames()；
 *      而 skipFrames() 返回的是它走过的 **MP3 压缩字节数**（累加 header.calculate_framesize()），
 *      却被 skip() 原样当作「已跳过的 PCM 字节数」返回。
 *      mp3 压缩比约 10:1 ⇒ 返回值比真实跳过的 PCM 字节小一个数量级，
 *      调用方按返回值判断「还差多少」就会再补读一截 ⇒ seek 严重偏后。
 *      这正是「续播不回到暂停点、往后跳一大截」的直接原因。
 *
 * ---------------------------- 因此本类的做法 ----------------------------
 *
 * 完全不用 stream.skip()，改为「按解码后 PCM 字节数精确 read-丢弃」。
 * 换算基于 getFormat() 的真实 PCM 格式，不依赖 mp3 头部的估算：
 *
 *      目标字节 = 秒数 × 采样率 × 每帧字节数      （帧=样本×声道，frameSize 已含声道）
 *
 * 纯 javazoom 解码约 200 倍实时，跳 60 秒 ≈ 0.3 秒，可接受；
 * 且为一次性开销（只在续播那一次 <init> 里做），不影响播放期性能。
 */
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

@Mixin(NetMusicAudioStream.class)
public abstract class NetMusicAudioStreamMixin {

    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    @Shadow
    @Final
    private AudioInputStream stream;

    /**
     * 在 pumpBuffers(4) 之前 seek。
     *
     * 注入目标选在「构造器内第一次 INVOKE pumpBuffers」之前：
     * 此时 this.stream 已赋值（第 128 字节码位置写入），流位置仍在 0，
     * 尚未有任何音频进入 audioDataQueue —— 正是唯一的正确时机。
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
     * 按 tick 精确 seek：换算成解码后 PCM 字节数，用 read-丢弃推进。
     *
     * 刻意不使用 stream.skip()，原因见文件头注释（单位错配 + 可能返回 -1）。
     */
    @Unique
    private void netmusicdisplay$seek(int tick) {
        try {
            AudioFormat format = this.stream.getFormat();
            int frameSize = format.getFrameSize();       // 每帧字节（样本字节 × 声道）
            float frameRate = format.getFrameRate();     // 每秒帧数（= 采样率）
            if (frameSize <= 0 || frameRate <= 0) {
                LOGGER.warn("[NetMusicDisplay] seek 跳过：PCM 格式不可用 frameSize={} frameRate={}", frameSize, frameRate);
                return;
            }

            long targetBytes = (long) ((tick / 20.0) * frameRate) * frameSize;
            // 对齐到整帧，避免在帧中间截断导致后续解码出现半个样本
            targetBytes = (targetBytes / frameSize) * frameSize;

            long startTime = System.currentTimeMillis();
            long done = 0;
            byte[] buf = new byte[65536];
            while (done < targetBytes) {
                int want = (int) Math.min(buf.length, targetBytes - done);
                // 同样对齐到整帧，保证每次 read 都是完整帧
                want = (want / frameSize) * frameSize;
                if (want <= 0) {
                    break;
                }
                int n = this.stream.read(buf, 0, want);
                if (n <= 0) {
                    // 流已到末尾（歌曲比预期短）：无法继续推进，接受当前位置
                    LOGGER.warn("[NetMusicDisplay] seek 提前结束：读到流末尾 done={}/{} 字节", done, targetBytes);
                    break;
                }
                done += n;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            double actualSec = (double) done / frameSize / frameRate;
            LOGGER.info("[NetMusicDisplay] seek 完成: 目标tick={} ({}秒) 实际跳过={}字节 ({}秒) 耗时={}ms frameRate={} frameSize={}",
                    tick, tick / 20.0, done, String.format("%.3f", actualSec), elapsed, frameRate, frameSize);
        } catch (Exception e) {
            LOGGER.error("[NetMusicDisplay] seek 失败，退化为从头播放", e);
        }
    }
}
