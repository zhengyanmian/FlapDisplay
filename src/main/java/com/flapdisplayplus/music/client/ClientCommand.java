package com.flapdisplayplus.music.client;

import com.mojang.brigadier.CommandDispatcher;
import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.music.MusicNetIntegration;
import com.flapdisplayplus.music.client.gui.LoginScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 客户端指令：打开登录界面。
 *
 * /netmusicdisplay gui —— 打开游戏内登录界面（扫码/邮箱/手机验证码）。
 * 仅在检测到网络音乐机（Net Music）时可用，未安装则提示。
 */
@EventBusSubscriber(modid = FlapDisplayPlus.MODID, value = Dist.CLIENT)
public class ClientCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("netmusicdisplay")
                        .then(Commands.literal("gui")
                                .executes(ctx -> {
                                    // 软联动守卫：未安装网络音乐机时提示
                                    if (!MusicNetIntegration.isNetMusicLoaded()) {
                                        ctx.getSource().sendFailure(
                                                Component.literal("未安装网络音乐机（Net Music），音乐联动功能不可用"));
                                        return 0;
                                    }
                                    // 确保在客户端主线程打开界面
                                    Minecraft.getInstance().execute(
                                            () -> Minecraft.getInstance().setScreen(new LoginScreen()));
                                    return 1;
                                }))
        );
    }
}
