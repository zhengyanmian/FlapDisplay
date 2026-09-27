/*
 * MediaCalibrationCommand.java
 *
 * 媒体叠加层【游戏内实时校准】指令（纯客户端，进世界后即可用）。
 *
 * 为什么需要它：媒体叠加层与翻牌面板的亚像素对齐受「像素坐标系（1 单位 = 1/32 方块）、
 * 视角、面板几何」共同影响，靠改配置文件 + 重启游戏来试值太慢。这里把全部自由度
 * 暴露成指令，改一个数就能立刻看到效果，满意后 /fdpcal save 落盘。
 *
 * 用法：
 *   /fdpcal                          查看当前值
 *   /fdpcal show                     查看当前值
 *   /fdpcal x   <值>                 整体左右平移（正数向右；+n/-n 为相对）
 *   /fdpcal y   <值>                 整体上下平移（正数向下）
 *   /fdpcal inset <值>               四边统一内缩（负数外扩）
 *   /fdpcal ix  <值>                 左右内缩（负数外扩，默认 -0.5）
 *   /fdpcal iy  <值>                 上下内缩（默认 2.5）
 *   /fdpcal z   <值>                 ★深度（默认 -0.49 = 与面板正面共面）
 *   /fdpcal reset                    恢复默认值
 *   /fdpcal save                     写入 config/flapdisplayplus-common.toml
 *
 * 单位统一为 1/32 方块（≈0.5 像素）：2 单位 ≈ 1 像素。
 *
 * 【2026-09-27 关键澄清：哪些旋钮能修什么】
 *   x / y / inset / ix / iy  = 正向（平面内）平移与缩放。
 *       只能修「正面平视时边缘对不齐」。它们【结构上不可能】修掉
 *       「斜着看时媒体与方块错开」——因为那是深度差造成的视差，
 *       偏移量 = 深度差 × tan(视角)，随视角变化，平移是常量，永远追不上。
 *   z = 唯一能修侧面视差的旋钮。
 *       基准：0 = 翻牌字符平面（面板正面前方 0.5 单位，Create 为防共面闪烁而抬高）；
 *             -0.5 = 与面板可见正面共面 ⇒ 视差恒为 0（默认 -0.49）。
 *       旧版本把 z 允许范围限死在 0.0~0.5，调不到负值，所以侧面差距根本修不了。
 */
package com.flapdisplayplus.client;

import com.flapdisplayplus.FlapDisplayPlus;
import com.flapdisplayplus.config.Config;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = FlapDisplayPlus.MODID, value = Dist.CLIENT)
public final class MediaCalibrationCommand {

    private static final int DEF_OFFSET_X = 0;
    private static final int DEF_OFFSET_Y = 0;
    private static final int DEF_INSET = 0;
    private static final double DEF_INSET_X = -0.5;
    private static final double DEF_INSET_Y = 2.5;
    private static final double DEF_Z = -0.49;

    private MediaCalibrationCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("fdpcal")
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("show").executes(ctx -> show(ctx.getSource())))
                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())))
                .then(Commands.literal("save").executes(ctx -> save(ctx.getSource())))
                .then(knob("x"))
                .then(knob("y"))
                .then(knob("inset"))
                .then(knob("ix"))
                .then(knob("iy"))
                .then(knob("z")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> knob(String name) {
        return Commands.literal(name).then(
                Commands.argument("value", StringArgumentType.word())
                        .executes(ctx -> apply(ctx.getSource(), name, StringArgumentType.getString(ctx, "value"))));
    }

    private static int apply(CommandSourceStack src, String key, String raw) {
        boolean relative = raw.startsWith("+") || raw.startsWith("-");
        String num = relative ? raw.substring(1) : raw;
        double v;
        try {
            v = Double.parseDouble(num);
        } catch (NumberFormatException e) {
            src.sendFailure(Component.literal("数值无法解析: " + raw + "（示例: 2 / -3 / +1 / 0.5）"));
            return 0;
        }
        if (raw.startsWith("-")) {
            v = -v;
        }

        String msg;
        switch (key) {
            case "x" -> {
                int nv = (int) clampD(relative ? Config.MEDIA_MEDIA_OFFSET_X.get() + v : v, -32, 32);
                Config.MEDIA_MEDIA_OFFSET_X.set(nv);
                msg = "offsetX = " + nv;
            }
            case "y" -> {
                int nv = (int) clampD(relative ? Config.MEDIA_MEDIA_OFFSET_Y.get() + v : v, -32, 32);
                Config.MEDIA_MEDIA_OFFSET_Y.set(nv);
                msg = "offsetY = " + nv;
            }
            case "inset" -> {
                int nv = (int) clampD(relative ? Config.MEDIA_MEDIA_INSET.get() + v : v, -16, 16);
                Config.MEDIA_MEDIA_INSET.set(nv);
                msg = "inset = " + nv;
            }
            case "ix" -> {
                double nv = clampD(relative ? Config.MEDIA_INSET_X.get() + v : v, -16.0, 16.0);
                Config.MEDIA_INSET_X.set(nv);
                msg = String.format("insetX = %.2f", nv);
            }
            case "iy" -> {
                double nv = clampD(relative ? Config.MEDIA_INSET_Y.get() + v : v, -16.0, 16.0);
                Config.MEDIA_INSET_Y.set(nv);
                msg = String.format("insetY = %.2f", nv);
            }
            case "z" -> {
                double nv = clampD(relative ? Config.MEDIA_Z_OFFSET.get() + v : v, -2.0, 0.5);
                Config.MEDIA_Z_OFFSET.set(nv);
                msg = String.format("zOffset = %.3f", nv);
            }
            default -> {
                src.sendFailure(Component.literal("未知参数: " + key));
                return 0;
            }
        }
        src.sendSuccess(() -> Component.literal("§b[翻牌校准] §f" + msg + " §7（/fdpcal save 保存）"), false);
        return 1;
    }

    private static int show(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal(String.format(
                "§b[翻牌校准]§f offsetX=%d offsetY=%d inset=%d | insetX=%.2f insetY=%.2f z=%.3f",
                Config.MEDIA_MEDIA_OFFSET_X.get(), Config.MEDIA_MEDIA_OFFSET_Y.get(),
                Config.MEDIA_MEDIA_INSET.get(),
                Config.MEDIA_INSET_X.get(), Config.MEDIA_INSET_Y.get(), Config.MEDIA_Z_OFFSET.get())), false);
        src.sendSuccess(() -> Component.literal(
                "§7单位 = 1/32 方块（2 单位 ≈ 1 像素）。"), false);
        src.sendSuccess(() -> Component.literal(
                "§7· x / y / inset / ix / iy §8→ 只修【正面平视】时的边缘对齐。"), false);
        src.sendSuccess(() -> Component.literal(
                "§7· z §8→ §f斜着看时媒体与方块错开（半像素差距）只能靠它修：§f-0.5 = 与面板正面共面（视差 0）。"), false);
        return 1;
    }

    private static int reset(CommandSourceStack src) {
        Config.MEDIA_MEDIA_OFFSET_X.set(DEF_OFFSET_X);
        Config.MEDIA_MEDIA_OFFSET_Y.set(DEF_OFFSET_Y);
        Config.MEDIA_MEDIA_INSET.set(DEF_INSET);
        Config.MEDIA_INSET_X.set(DEF_INSET_X);
        Config.MEDIA_INSET_Y.set(DEF_INSET_Y);
        Config.MEDIA_Z_OFFSET.set(DEF_Z);
        src.sendSuccess(() -> Component.literal("§b[翻牌校准]§f 已恢复默认值"), false);
        return show(src);
    }

    private static int save(CommandSourceStack src) {
        try {
            Config.SPEC.save();
        } catch (Throwable t) {
            src.sendFailure(Component.literal("保存失败: " + t));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§b[翻牌校准]§f 已写入配置文件"), false);
        return 1;
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
