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
 * activeSource 字段通过 DisplayLinkReflection 安全反射读写（只用 getDeclaredField）。
 */
package com.flapdisplayplus.mixin;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.ModDisplaySources;
import com.flapdisplayplus.compat.DisplayLinkReflection;
import com.flapdisplayplus.network.MediaDisplayPacket;
import com.simibubi.create.content.kinetics.clock.CuckooClockBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayLinkBlockEntity.class)
public abstract class DisplayLinkBlockEntityMixin {

    /** 全局推送节流计数器：每 20 tick（约 1 秒）全体链接器推送一次 */
    private static int pushCounter = 0;

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

        // ===== 翻牌显示器无转速 → 清除图片 =====
        // 用户确认：需要转速的是【翻牌显示器】（FlapDisplay 是 KineticBlockEntity，靠动力
        // 转动翻牌），不是链接器也不是时钟。翻牌不转（getSpeed()==0）→ 不显示媒体。
        BlockPos targetPos = self.getTargetPosition();
        if (targetPos != null && self.getLevel().getBlockEntity(targetPos) instanceof FlapDisplayBlockEntity fbe) {
            if (fbe.getSpeed() == 0) {
                if ((pushCounter++ % 20) == 0) {
                    try {
                        if (self.getLevel() instanceof ServerLevel serverLevel) {
                            // 解析 controller 坐标（与 FlapDisplayMediaSource 一致）
                            BlockPos renderPos = targetPos;
                            FlapDisplayBlockEntity c = fbe.getController();
                            renderPos = (c != null ? c : fbe).getBlockPos();
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
        if (DisplayLinkReflection.getActiveSource(self) != ModDisplaySources.FLAP_DISPLAY_MEDIA.get()) {
            DisplayLinkReflection.setActiveSource(self, ModDisplaySources.FLAP_DISPLAY_MEDIA.get());
            FlapDisplayPlus.LOGGER.info("[DisplayLink] 链接器 {} 已设为媒体显示源", self.getBlockPos());
        }
        // 节流：每 20 tick 主动推送一次（绕过 tickSource 的 POWERED 红石要求）
        if ((pushCounter++ % 20) == 0) {
            try {
                self.updateGatheredData();
            } catch (Throwable t) {
                FlapDisplayPlus.LOGGER.warn("[DisplayLink] 链接器 {} 推送失败", self.getBlockPos(), t);
            }
        }
    }
}
