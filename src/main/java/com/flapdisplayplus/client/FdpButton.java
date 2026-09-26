/*
 * FdpButton.java
 *
 * 翻牌万象自绘按钮：Create 木质风格（暗木底 + 描金边框），
 * 替换 Minecraft 默认按钮，全模组界面统一风格。
 *
 * 视觉状态：
 * - 普通：暗木底 WOOD_CELL，边框 BORDER，文字 TEXT
 * - 悬停/聚焦：底色变亮 WOOD_HOVER，边框金色 BORDER_HL，文字白色
 * - 禁用：更暗 WOOD_DISABLED，边框 BORDER_DISABLED，文字灰色
 *
 * 配色常量统一由 FdpWidgets 提供（单一数据源），本类不再硬编码色值。
 */
package com.flapdisplayplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class FdpButton extends Button {

    public FdpButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    /** 便捷构造（配合 addRenderableWidget 使用） */
    public static FdpButton create(int x, int y, int width, int height, Component message, OnPress onPress) {
        return new FdpButton(x, y, width, height, message, onPress);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hovered = isHoveredOrFocused();

        int bg;
        int border;
        int textColor;
        if (!this.active) {
            bg = FdpWidgets.WOOD_DISABLED;
            border = FdpWidgets.BORDER_DISABLED;
            textColor = 0xFF777777;
        } else if (hovered) {
            bg = FdpWidgets.WOOD_HOVER;
            border = FdpWidgets.BORDER_HL;
            textColor = 0xFFFFFFFF;
        } else {
            bg = FdpWidgets.WOOD_CELL;
            border = FdpWidgets.BORDER;
            textColor = FdpWidgets.TEXT;
        }

        // 背景 + 边框（边框绘制统一走 FdpWidgets，保证与格子/面板一致）
        graphics.fill(x, y, x + w, y + h, bg);
        FdpWidgets.outline(graphics, x, y, w, h, border);

        // 文字居中
        Component msg = getMessage();
        int textW = Minecraft.getInstance().font.width(msg);
        int textX = x + (w - textW) / 2;
        int textY = y + (h - 8) / 2;
        graphics.drawString(Minecraft.getInstance().font, msg, textX, textY, textColor);
    }
}
