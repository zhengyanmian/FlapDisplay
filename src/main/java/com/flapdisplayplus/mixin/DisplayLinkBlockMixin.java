/*
 * DisplayLinkBlockMixin.java
 *
 * 显示链接器 GUI 替换（客户端）：
 *
 * 仅当「该显示链接器指向布谷鸟时钟」时，把打开原版 DisplayLinkScreen 的流程
 * 替换为打开我们的翻牌媒体配置 GUI（大页面选图 + 预览）。
 * 指向其他方块时，原版行为完全保留。
 *
 * 注入点选在 DisplayLinkBlock.displayScreen() —— 这是在 Screen 创建之前的
 * 最早入口，替换后原版 DisplayLinkScreen 根本不会被创建，也不会残留
 * Create ScreenOpener 的毛玻璃过渡层。
 */
package com.flapdisplayplus.mixin;

import com.flapdisplayplus.client.CuckooClockMediaScreen;
import com.simibubi.create.content.kinetics.clock.CuckooClockBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import net.createmod.catnip.gui.ScreenOpener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayLinkBlock.class)
public abstract class DisplayLinkBlockMixin {

    @Inject(method = "displayScreen", at = @At("HEAD"), cancellable = true, remap = false)
    private void flapdisplayplus$openMediaScreenForCuckoo(
            DisplayLinkBlockEntity be, Player player, CallbackInfo ci) {
        // 仅客户端处理（与服务端逻辑一致：displayScreen 内部也判断 LocalPlayer）
        if (!(player instanceof LocalPlayer)) {
            return;
        }
        if (be == null) {
            return;
        }
        // 取显示链接器指向的 source 方块
        BlockPos sourcePos = be.getSourcePosition();
        com.flapdisplayplus.FlapDisplayPlus.LOGGER.info("[LinkGUI] 右键链接器 {} source={}", be.getBlockPos(), sourcePos);
        if (sourcePos == null) {
            return;
        }
        BlockEntity sourceBe = Minecraft.getInstance().level.getBlockEntity(sourcePos);
        com.flapdisplayplus.FlapDisplayPlus.LOGGER.info("[LinkGUI] source 方块类型: {}",
                sourceBe == null ? "null" : sourceBe.getType().toString());
        if (!(sourceBe instanceof CuckooClockBlockEntity)) {
            return; // 不是布谷鸟时钟：原版 GUI 照常
        }

        // 是布谷鸟时钟：替换为我们的媒体配置界面
        // 用 Create 的 ScreenOpener 打开（与原版 DisplayLinkScreen 打开方式一致，
        // 渲染层级、毛玻璃过渡动画全部一致，不会出现自定义面板被模糊层盖住）
        ci.cancel();
        com.flapdisplayplus.FlapDisplayPlus.LOGGER.info("[LinkGUI] 已拦截：打开媒体界面");
        ScreenOpener.open(new CuckooClockMediaScreen(sourcePos));
    }
}
