/*
 * DisplayLinkBlockEntityMixin.java
 *
 * 显示链接器 BlockEntity 注入（服务端核心）：
 *
 * Create 的 updateGatheredData() 会执行 DisplaySource.getAll(level, sourcePos).contains(activeSource)，
 * 若 false 则把 activeSource 清空并不推送。因此必须：① 绑定与 setActiveSource 用同一实例
 * （见 ModDisplaySources）；② 每 tick 强制把指向布谷鸟时钟的链接器 activeSource 设为本源。
 *
 * 注入点放在 tick()（每 tick 无条件执行），打破「activeSource==null 时 Create 不调用
 * updateGatheredData」的死循环。设源后直接调用 public 的 updateGatheredData()（绕过
 * tickSource 的红石 POWERED 门槛），节流到每 20 tick 推送一次，实现「指向即显示、无需红石」。
 *
 * 【2026-08-28 重构】activeSource 先前通过 DisplayLinkReflection 反射读写，现已改为**直接字段访问**。
 * javap 在 create-1.21.1-6.0.10-280-slim.jar 上验证：
 *     public com.simibubi.create.api.behaviour.display.DisplaySource activeSource;
 * 该字段是 public，反射层（compat/DisplayLinkReflection.java，80 行）完全多余，已删除。
 * 教训：任何关于第三方 API 可见性的判断都必须在真实 jar 上 javap 验证，不要凭注释或记忆。
 */
package com.flapdisplayplus.mixin;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.ModDisplaySources;
import com.flapdisplayplus.network.MediaDisplayPacket;
import com.simibubi.create.content.kinetics.clock.CuckooClockBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayLinkBlockEntity.class)
public abstract class DisplayLinkBlockEntityMixin {

    /** 每个链接器自己的节流计数（tick 数），避免多个链接器共用计数器互相干扰 */
    @Unique
    private int flapdisplayplus$tickCounter = 0;

    /** 推送间隔（tick）：20 tick = 1 秒，必须与 FlapDisplayMediaSource.getPassiveRefreshTicks 同量级 */
    private static final int PUSH_INTERVAL_TICKS = 20;

    @Inject(method = "tick", at = @At("HEAD"))
    private void flapdisplayplus$autoSelectAndPush(CallbackInfo ci) {
        DisplayLinkBlockEntity self = (DisplayLinkBlockEntity) (Object) this;
        // 只在服务端执行（客户端 BE 不负责推送数据）
        if (self.getLevel() == null || self.getLevel().isClientSide()) {
            return;
        }
        BlockPos sourcePos = self.getSourcePosition();
        if (sourcePos == null) {
            return;
        }
        BlockEntity be = self.getLevel().getBlockEntity(sourcePos);
        if (!(be instanceof CuckooClockBlockEntity)) {
            return; // 指向的不是布谷鸟时钟：保持默认行为
        }

        // 【2026-09-27 修正】计数器每个链接器独立、且每 tick 只推进一次。
        // 此前用一个 static pushCounter，并且在两个分支里各自增一次 ⇒ 多个链接器
        // 互相拉扯相位，「每 20 tick 推送」实际会漂移成很久才推一次，媒体因此断流。
        boolean due = (this.flapdisplayplus$tickCounter++ % PUSH_INTERVAL_TICKS) == 0;

        // ===== 翻牌显示器无转速 → 清除图片 =====
        // 用户确认：需要转速的是【翻牌显示器】（FlapDisplay 是 KineticBlockEntity，靠动力
        // 转动翻牌），不是链接器也不是时钟。翻牌不转（getSpeed()==0）→ 不显示媒体。
        BlockPos targetPos = self.getTargetPosition();
        if (targetPos != null && self.getLevel().getBlockEntity(targetPos) instanceof FlapDisplayBlockEntity fbe) {
            if (fbe.getSpeed() == 0) {
                if (due) {
                    try {
                        if (self.getLevel() instanceof ServerLevel serverLevel) {
                            // 解析 controller 坐标（与 FlapDisplayMediaSource 一致）
                            FlapDisplayBlockEntity c = fbe.getController();
                            BlockPos renderPos = (c != null ? c : fbe).getBlockPos();
                            PacketDistributor.sendToPlayersNear(serverLevel, null,
                                    renderPos.getX() + 0.5, renderPos.getY() + 0.5, renderPos.getZ() + 0.5, 64.0,
                                    new MediaDisplayPacket(renderPos, "", "FIT"));
                        }
                    } catch (Throwable t) {
                        FlapDisplayPlus.LOGGER.warn("[DisplayLink] 链接器 {} 清除媒体失败", self.getBlockPos(), t);
                    }
                }
                return; // 翻牌不转：不设源、不推送
            }
        }

        // ===== 翻牌有转速：强制媒体显示源 + 推送 =====
        // 指向布谷鸟时钟的链接器 ⇒ 强制使用本模组媒体显示源（与注册实例一致）
        // activeSource 是 public 字段，直接读写
        if (self.activeSource != ModDisplaySources.FLAP_DISPLAY_MEDIA.get()) {
            self.activeSource = ModDisplaySources.FLAP_DISPLAY_MEDIA.get();
            FlapDisplayPlus.LOGGER.debug("[DisplayLink] 链接器 {} 已设为媒体显示源", self.getBlockPos());
        }
        // 兜底推送：官方被动刷新路径在 tickSource() 里还有一道「链接器被红石通电则 return」的
        // 门槛，通电时官方永远不会刷新。这里直接调 public 的 updateGatheredData() 绕过该门槛，
        // 保证「指向布谷鸟时钟就持续推送」，与是否通电无关。
        if (due) {
            try {
                self.updateGatheredData();
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[DisplayLink] 链接器 {} 推送失败", self.getBlockPos(), t);
            }
        }
    }
}
