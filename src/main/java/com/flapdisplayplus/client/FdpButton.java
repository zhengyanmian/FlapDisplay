/*
 * FdpButton.java
 *
 * 翻牌万象自绘按钮：Create 木质风格（暗木底 + 描金边框），
 * 替换 Minecraft 默认按钮，与布谷鸟时钟媒体界面 / 配置界面风格统一。
 *
 * 视觉状态：
 * - 普通：暗木底 0xFF2F2216，边框 0xFF5C4430，文字 0xFFE8D5AB
 * - 悬停/聚焦：底色变亮 0xFF3D2B1F，边框金色 0xFFC9A86A，文字白色
 * - 禁用：更暗 0xFF1F1710，边框 0xFF3A2E20，文字灰色
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
            bg = 0xFF1F1710;
            border = 0xFF3A2E20;
            textColor = 0xFF777777;
        } else if (hovered) {
            bg = 0xFF3D2B1F;
            border = 0xFFC9A86A;
            textColor = 0xFFFFFFFF;
        } else {
            bg = 0xFF2F2216;
            border = 0xFF5C4430;
            textColor = 0xFFE8D5AB;
        }

        // 背景 + 边框
        graphics.fill(x, y, x + w, y + h, bg);
        graphics.hLine(x, x + w - 1, y, border);
        graphics.hLine(x, x + w - 1, y + h - 1, border);
        graphics.vLine(x, y, y + h - 1, border);
        graphics.vLine(x + w - 1, y, y + h - 1, border);

        // 文字居中
        Component msg = getMessage();
        int textW = Minecraft.getInstance().font.width(msg);
        int textX = x + (w - textW) / 2;
        int textY = y + (h - 8) / 2;
        graphics.drawString(Minecraft.getInstance().font, msg, textX, textY, textColor);
    }
}
