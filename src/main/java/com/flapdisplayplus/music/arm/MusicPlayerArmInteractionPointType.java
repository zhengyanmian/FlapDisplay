package com.flapdisplayplus.music.arm;

import com.github.tartaricacid.netmusic.init.InitBlocks;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 动力臂交互点类型：让 Create 的机械臂能识别 Net Music 的 CD 播放机。
 *
 * 参考 Create 内置的 JukeboxType（唱片机交互点），
 * 当机械臂指向 CD 播放机时，把它当作一个可放入/取出物品的目标，
 * 从而支持用机械臂自动放入唱片、取出唱片。
 */
public class MusicPlayerArmInteractionPointType extends ArmInteractionPointType {

    @Override
    public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
        // 只有 Net Music 的 CD 播放机方块才能创建交互点
        return state.is(InitBlocks.MUSIC_PLAYER.get());
    }

    @Override
    public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
        return new MusicPlayerArmInteractionPoint(this, level, pos, state);
    }
}
