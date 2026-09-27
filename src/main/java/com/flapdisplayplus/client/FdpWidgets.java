/*
 * FdpWidgets.java
 *
 * 翻牌万象统一控件库：把「Create 木质风格」收敛到一处，供全部界面复用。
 *
 * 背景（2026-08-28 重构）：
 * 此前项目里有**两套并存的 GUI 体系** ——
 *   - client/CuckooClockMediaScreen 用自绘的 FdpButton（暗木底 + 描金边框）
 *   - music/client/gui/ 下 4 个 Screen 全用原版 Button（灰色）
 * 结果是从布谷鸟时钟界面点进音乐配置时，按钮风格突然变化。
 * FdpButton 的类注释当时已写「与布谷鸟时钟媒体界面 / 配置界面风格统一」，
 * 但实际并未在任何配置界面使用过。
 *
 * 本类提供：
 *   - 配色常量（WOOD_* / BORDER_* / TEXT_*）：单一数据源，杜绝各文件硬编码 0xFF2F2216 之类
 *   - panel(...)：木质面板（外框 + 底色 + 四边描线），替代各处手写的 4 行 fill/hLine/vLine
 *   - toggle(...)：绑定 ModConfigSpec.BooleanValue 的开关按钮（自动 set + save + 刷新文案）
 *   - cycle(...)：绑定 ModConfigSpec.EnumValue 的循环切换按钮
 *   - infoLine(...)：说明文字的统一字号与缩进
 */
package com.flapdisplayplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public final class FdpWidgets {

    private FdpWidgets() {
    }

    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");

    // ===================== 配色（Create 木质风格，单一数据源）=====================

    /** 面板最外层暗边 */
    public static final int WOOD_DARK = 0xFF2A1D13;
    /** 面板底色 */
    public static final int WOOD_BG = 0xFF3D2B1F;
    /** 按钮/格子底色 */
    public static final int WOOD_CELL = 0xFF2F2216;
    /** 悬停时的亮底色 */
    public static final int WOOD_HOVER = 0xFF3D2B1F;
    /** 禁用底色 */
    public static final int WOOD_DISABLED = 0xFF1F1710;
    /** 普通边框 */
    public static final int BORDER = 0xFF5C4430;
    /** 高亮/悬停边框（描金） */
    public static final int BORDER_HL = 0xFFC9A86A;
    /** 禁用边框 */
    public static final int BORDER_DISABLED = 0xFF3A2E20;
    /** 主文字（浅米色） */
    public static final int TEXT = 0xFFE8D5AB;
    /** 次要文字（暗米色） */
    public static final int TEXT_DIM = 0xFFB09A72;
    /** 强调文字（金色，用于标题/提示） */
    public static final int TEXT_HL = 0xFFC9A86A;
    /** 错误文字 */
    public static final int TEXT_ERROR = 0xFFE06C6C;
    /** 成功文字 */
    public static final int TEXT_OK = 0xFF8FBF6A;

    // ===================== 绘制工具 =====================

    /**
     * 木质面板：外框暗边 + 底色 + 四边描线。
     *
     * 替代这类手写代码（原 CuckooClockMediaScreen 与各处重复了多次）：
     * <pre>
     * graphics.fill(left - 4, top - 4, left + w + 4, top + h + 4, 0xFF2A1D13);
     * graphics.fill(left, top, left + w, top + h, 0xFF3D2B1F);
     * graphics.hLine(left, left + w - 1, top, 0xFF5C4430);
     * ... 共 6 行
     * </pre>
     */
    public static void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x - 4, y - 4, x + w + 4, y + h + 4, WOOD_DARK);
        graphics.fill(x, y, x + w, y + h, WOOD_BG);
        outline(graphics, x, y, w, h, BORDER);
    }

    /** 四边描线（格子、按钮等的边框） */
    public static void outline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.hLine(x, x + w - 1, y, color);
        graphics.hLine(x, x + w - 1, y + h - 1, color);
        graphics.vLine(x, y, y + h - 1, color);
        graphics.vLine(x + w - 1, y, y + h - 1, color);
    }

    /** 面板内的居中标题（默认木质风格米色） */
    public static void title(GuiGraphics graphics, String text, int centerX, int y) {
        title(graphics, text, centerX, y, TEXT);
    }

    /** 面板内的居中标题（自定义颜色） */
    public static void title(GuiGraphics graphics, String text, int centerX, int y, int color) {
        graphics.drawCenteredString(Minecraft.getInstance().font, text, centerX, y, color);
    }

    /** 面板内的居中说明文字 */
    public static void centeredNote(GuiGraphics graphics, String text, int centerX, int y, int color) {
        graphics.drawCenteredString(Minecraft.getInstance().font, text, centerX, y, color);
    }

    // ===================== 配置绑定控件 =====================

    /**
     * 只读的实时文字控件（如「每页 20 首」）。
     * 用 Widget 而非在 render 里手绘，是为了让它随界面生命周期自动增删，
     * 且可以挂在任意位置不依赖 TextFlow 光标。
     */
    public static net.minecraft.client.gui.components.AbstractWidget statText(
            int x, int y, int w, java.util.function.Supplier<String> text) {
        return new net.minecraft.client.gui.components.AbstractWidget(
                x, y, w, 20, Component.empty()) {
            @Override
            protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                String s = text.get();
                int tw = Minecraft.getInstance().font.width(s);
                graphics.drawString(Minecraft.getInstance().font, s,
                        getX() + (getWidth() - tw) / 2, getY() + 6, 0xFFA0A0A0);
            }

            @Override
            protected void updateWidgetNarration(
                    net.minecraft.client.gui.narration.NarrationElementOutput narration) {
                // 纯展示控件，无需叙述
            }
        };
    }

    /**
     * 布尔开关按钮（绑定 ModConfigSpec.BooleanValue）。
     * 点击 → 取反 → 写入 SPEC → 落盘 → 刷新按钮文案。
     * 【2026-09-28】改用游戏自带 Button 渲染（原 FdpButton 木质风格仅保留在媒体界面）。
     *
     * 替代 ConfigScreen 里重复的 addBoolRow 模式。
     */
    public static net.minecraft.client.gui.components.Button toggle(int x, int y, int w, int h,
                                   ModConfigSpec.BooleanValue val,
                                   Function<Boolean, String> labelOf,
                                   Runnable afterChange) {
        return net.minecraft.client.gui.components.Button.builder(
                        Component.literal(labelOf.apply(val.get())), b -> {
                    val.set(!val.get());
                    save();
                    b.setMessage(Component.literal(labelOf.apply(val.get())));
                    if (afterChange != null) {
                        afterChange.run();
                    }
                }).bounds(x, y, w, h).build();
    }

    /** 布尔开关的简化形式（开/关 两字文案） */
    public static net.minecraft.client.gui.components.Button toggle(int x, int y, int w, int h,
                                                                    ModConfigSpec.BooleanValue val) {
        return toggle(x, y, w, h, val, v -> v ? "开" : "关", null);
    }

    /**
     * 枚举循环切换按钮（绑定 ModConfigSpec.EnumValue）。
     * 点击 → 取下一个枚举值（环形）→ 写入 SPEC → 落盘 → 刷新文案。
     * 【2026-09-28】改用游戏自带 Button 渲染。
     *
     * 替代 ConfigScreen 里重复的 addEnumRow 模式。
     */
    public static <T extends Enum<T>> net.minecraft.client.gui.components.Button cycle(int x, int y, int w, int h,
                                                      ModConfigSpec.EnumValue<T> val,
                                                      Function<T, String> labelOf,
                                                      Runnable afterChange) {
        return net.minecraft.client.gui.components.Button.builder(
                        Component.literal(labelOf.apply(val.get())), b -> {
                    T[] all = val.get().getDeclaringClass().getEnumConstants();
                    List<T> list = Arrays.asList(all);
                    T next = list.get((list.indexOf(val.get()) + 1) % all.length);
                    val.set(next);
                    save();
                    b.setMessage(Component.literal(labelOf.apply(next)));
                    if (afterChange != null) {
                        afterChange.run();
                    }
                }).bounds(x, y, w, h).build();
    }

    /** 保存配置（统一异常处理，避免每个界面各写一遍 try/catch） */
    public static void save() {
        try {
            com.flapdisplayplus.config.Config.SPEC.save();
            com.flapdisplayplus.music.config.Config.SPEC.save();
        } catch (Exception e) {
            LOGGER.error("[配置] 保存失败", e);
        }
    }

    /** 从字符串列表里循环选值的按钮（用于帧率/清晰度这类离散档位）。
     * 【2026-09-28】改用游戏自带 Button 渲染。 */
    public static net.minecraft.client.gui.components.Button cycleList(int x, int y, int w, int h,
                                      ModConfigSpec.IntValue val,
                                      int[] options,
                                      Function<Integer, String> labelOf,
                                      Runnable afterChange) {
        return net.minecraft.client.gui.components.Button.builder(
                        Component.literal(labelOf.apply(val.get())), b -> {
                    int cur = val.get();
                    int idx = 0;
                    for (int i = 0; i < options.length; i++) {
                        if (options[i] == cur) {
                            idx = i;
                            break;
                        }
                    }
                    int next = options[(idx + 1) % options.length];
                    val.set(next);
                    save();
                    b.setMessage(Component.literal(labelOf.apply(next)));
                    if (afterChange != null) {
                        afterChange.run();
                    }
                }).bounds(x, y, w, h).build();
    }
}
