package com.flapdisplayplus.music.arm;

import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * CD 播放机的动力臂交互点。
 *
 * 关键点：覆盖 getHandler() 直接返回 CD 播放机的物品栏（ItemStackHandler），
 * 这样机械臂的 insert / extract 就会自动操作 CD 播放机第 0 格的唱片。
 * CD 播放机只有 1 格（放唱片），所以 getSlotCount() 返回 1。
 */
public class MusicPlayerArmInteractionPoint extends ArmInteractionPoint {

    public MusicPlayerArmInteractionPoint(ArmInteractionPointType type, Level level, BlockPos pos, BlockState state) {
        super(type, level, pos, state);
    }

    /**
     * 返回 CD 播放机的物品栏处理器，供机械臂放入/取出唱片。
     * 直接复用 Net Music 自带的 getPlayerInv()（返回 ItemStackHandler，实现 IItemHandler）。
     */
    @Override
    protected IItemHandler getHandler(ArmBlockEntity arm) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof TileEntityMusicPlayer musicPlayer) {
            return musicPlayer.getPlayerInv();
        }
        return null;
    }
}
