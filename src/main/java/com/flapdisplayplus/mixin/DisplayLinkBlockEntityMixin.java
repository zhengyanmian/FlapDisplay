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

    /**
     * 本链接器最后一次真正推送过媒体的显示带坐标。
     *
     * 【2026-09-27 新增】用于「源或目标失效时主动清除」：
     * 旧实现只在 provideLine 里清除，而 provideLine 可能根本不被调用
     * （链接器被红石通电时 Create 的 tickSource 会提前 return；源被拆后我们的
     * mixin 又直接 return）。结果客户端 MediaRenderRegistry 一直留着记录，
     * 表现为「拆掉方块后视频/图片还在显示」。
     */
    @Unique
    private BlockPos flapdisplayplus$pushedRenderPos;

    private static final int PUSH_INTERVAL_TICKS = 20;

    /**
     * 若之前推送过媒体，则发送一次「清空」包并忘记记录。
     * 只在确实推过的时候发，避免每 tick 刷包。
     */
    @Unique
    private void flapdisplayplus$clearIfPushed(DisplayLinkBlockEntity self, String why) {
        BlockPos rp = this.flapdisplayplus$pushedRenderPos;
        if (rp == null) {
            return;
        }
        this.flapdisplayplus$pushedRenderPos = null;
        try {
            if (self.getLevel() instanceof ServerLevel serverLevel) {
                PacketDistributor.sendToPlayersNear(serverLevel, null,
                        rp.getX() + 0.5, rp.getY() + 0.5, rp.getZ() + 0.5, 64.0,
                        new MediaDisplayPacket(rp, "", "FIT"));
                FlapDisplayPlus.LOGGER.debug("[DisplayLink] {} 已清除媒体: {}", self.getBlockPos(), why);
            }
        } catch (Throwable t) {
            FlapDisplayPlus.LOGGER.warn("[DisplayLink] {} 清除媒体失败: {}", self.getBlockPos(), why, t);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void flapdisplayplus$autoSelectAndPush(CallbackInfo ci) {
        DisplayLinkBlockEntity self = (DisplayLinkBlockEntity) (Object) this;
        // 只在服务端执行（客户端 BE 不负责推送数据）
        if (self.getLevel() == null || self.getLevel().isClientSide()) {
            return;
        }
        BlockPos sourcePos = self.getSourcePosition();
        if (sourcePos == null) {
            // 源坐标已为 null（布谷鸟时钟被拆后 Create 清空了绑定）：主动清除残留画面
            flapdisplayplus$clearIfPushed(self, "源坐标为 null（源被拆）");
            return;
        }
        BlockEntity be = self.getLevel().getBlockEntity(sourcePos);
        if (!(be instanceof CuckooClockBlockEntity)) {
            // 源不再是布谷鸟时钟（时钟被拆 / 改指向）：主动清除，避免画面残留
            flapdisplayplus$clearIfPushed(self, "源不再是布谷鸟时钟");
            return;
        }

        // 【2026-09-27 修正】计数器每个链接器独立、且每 tick 只推进一次。
        // 此前用一个 static pushCounter，并且在两个分支里各自增一次 ⇒ 多个链接器
        // 互相拉扯相位，「每 20 tick 推送」实际会漂移成很久才推一次，媒体因此断流。
        boolean due = (this.flapdisplayplus$tickCounter++ % PUSH_INTERVAL_TICKS) == 0;

        // ===== 目标必须是翻牌显示器 =====
        BlockPos targetPos = self.getTargetPosition();
        if (targetPos == null
                || !(self.getLevel().getBlockEntity(targetPos) instanceof FlapDisplayBlockEntity fbe)) {
            // 目标不是翻牌显示器（被拆 / 改指向）：主动清除，避免画面残留
            flapdisplayplus$clearIfPushed(self, "目标不再是翻牌显示器");
            return;
        }
        // 【2026-10-01 改：不再检查转速】
        // 旧实现在 fbe.getSpeed()==0 时发清空包（「翻牌不转就不显示媒体」）。用户要求取消：
        // 「断电即停止 都改为无应力」—— 媒体无应力（断电、停转、动力网络过载）也照常显示。
        // 于是这里只保留「目标必须仍是翻牌显示器」这一条失效检查。

        // ===== 指向布谷鸟时钟：强制媒体显示源 + 持续推送 =====
        // 指向布谷鸟时钟的链接器 ⇒ 强制使用本模组媒体显示源（与注册实例一致）
        // activeSource 是 public 字段，直接读写
        if (self.activeSource != ModDisplaySources.FLAP_DISPLAY_MEDIA.get()) {
            self.activeSource = ModDisplaySources.FLAP_DISPLAY_MEDIA.get();
            FlapDisplayPlus.LOGGER.debug("[DisplayLink] 链接器 {} 已设为媒体显示源", self.getBlockPos());
        }
        // 记录推送目标，供「源/目标失效时清除」使用
        try {
            FlapDisplayBlockEntity c = fbe.getController();
            this.flapdisplayplus$pushedRenderPos = (c != null ? c : fbe).getBlockPos();
        } catch (Throwable ignored) {
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
