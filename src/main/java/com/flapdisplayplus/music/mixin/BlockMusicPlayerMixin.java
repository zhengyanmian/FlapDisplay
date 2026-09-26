package com.flapdisplayplus.music.mixin;

import com.github.tartaricacid.netmusic.block.BlockMusicPlayer;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.flapdisplayplus.music.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 红石信号改进 Mixin：替换 CD 播放机的原版红石逻辑。
 *
 * 原版行为（EDGE_TOGGLE）：红石上升沿切换播放/暂停，下降沿什么都不做。
 * 这种「给一次信号切一次」的逻辑不直观，容易误触。
 *
 * 新增 CONTINUOUS 持续模式：
 * - 有红石信号 = 播放
 * - 无红石信号 = 暂停
 * 这是更符合直觉的「拉杆式」控制：拉下拉杆就放，推回去就停。
 */
@Mixin(BlockMusicPlayer.class)
public abstract class BlockMusicPlayerMixin {

    /**
     * 拦截原版 playerMusic()，在 CONTINUOUS 模式下用自定义逻辑替代。
     * EDGE_TOGGLE 模式（默认）保持原版行为不变。
     */
    @Inject(method = "playerMusic", at = @At("HEAD"), cancellable = true)
    private static void netmusicdisplay$redstoneLogic(Level level, BlockPos pos, boolean signal, CallbackInfo ci) {
        // 防御：config 未加载时走原版逻辑（世界加载早期方块邻居更新可能先于配置加载触发）
        if (!isContinuousMode()) {
            return; // 其他模式或配置未就绪：走原版逻辑
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TileEntityMusicPlayer player)) {
            return;
        }

        // 信号没变化则不做任何事
        if (signal == player.hasSignal()) {
            return;
        }

        if (signal) {
            // 信号变为 ON：如果没在播放就开播（续播由 TileEntityMusicPlayerMixin 处理）
            if (!player.isPlay()) {
                ItemStack cd = player.getPlayerInv().getStackInSlot(0);
                if (!cd.isEmpty()) {
                    ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
                    if (info != null) {
                        player.setPlayToClient(info);
                    }
                }
            }
        } else {
            // 信号变为 OFF：如果在播放就暂停
            if (player.isPlay()) {
                player.setPlay(false);
            }
        }

        player.setSignal(signal);
        player.markDirty();
        ci.cancel();
    }

    /**
     * 安全读取红石模式：配置未加载（世界加载早期）或读取异常时返回默认 EDGE_TOGGLE，
     * 保证走原版逻辑，绝不因配置未就绪崩溃。
     */
    private static boolean isContinuousMode() {
        try {
            return Config.REDSTONE_MODE.get() == Config.RedstoneMode.CONTINUOUS;
        } catch (Throwable t) {
            return false;
        }
    }
}
