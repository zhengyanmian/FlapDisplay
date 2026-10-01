package com.flapdisplayplus.music.arm;

import com.flapdisplayplus.FlapDisplayPlus;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 注册动力臂交互点类型。
 *
 * 让 Create 的机械臂（Mechanical Arm）能够识别 Net Music 的 CD 播放机，
 * 从而支持自动放入/取出唱片。
 *
 * 【Forge 移植说明】Create 6.0.8 Forge 的 ARM_INTERACTION_POINT_TYPE 注册表键为
 * "create:arm_interaction_point_type"，用 DeferredRegister + RegistryObject。
 */
public class ModArmInteractionPoints {
    public static final DeferredRegister<ArmInteractionPointType> ARM_INTERACTION_POINT_TYPES =
            DeferredRegister.create(new ResourceLocation("create", "arm_interaction_point_type"), FlapDisplayPlus.MODID);

    public static final RegistryObject<ArmInteractionPointType> MUSIC_PLAYER =
            ARM_INTERACTION_POINT_TYPES.register("music_player", MusicPlayerArmInteractionPointType::new);

    public static void register(IEventBus modBus) {
        ARM_INTERACTION_POINT_TYPES.register(modBus);
    }
}
