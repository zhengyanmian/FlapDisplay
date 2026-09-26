/*
 * QrCodeRenderer.java
 *
 * 把登录二维码 PNG 字节渲染为 Minecraft 纹理（移植自 NetMusicCanNeedQQ，原作者 Yincmewy，BSD-3-Clause）。
 */
package com.flapdisplayplus.music.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@OnlyIn(Dist.CLIENT)
public final class QrCodeRenderer {
    private static final Logger LOGGER = LogManager.getLogger("NetMusicDisplay");
    private static final ResourceLocation TEXTURE_ID = ResourceLocation.fromNamespaceAndPath("netmusicdisplay", "qr_login");

    private DynamicTexture texture;
    private boolean registered;
    private int imgWidth;
    private int imgHeight;

    public boolean load(byte[] pngData) {
        release();
        try {
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(pngData));
            if (buffered == null) return false;

            imgWidth = buffered.getWidth();
            imgHeight = buffered.getHeight();
            NativeImage nativeImage = new NativeImage(imgWidth, imgHeight, false);

            for (int y = 0; y < imgHeight; y++) {
                for (int x = 0; x < imgWidth; x++) {
                    int argb = buffered.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }

            texture = new DynamicTexture(nativeImage);
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
            registered = true;
            return true;
        } catch (Exception e) {
            LOGGER.error("[QQ登录] 二维码图像加载失败", e);
            return false;
        }
    }

    public void render(GuiGraphics graphics, int x, int y, int size) {
        if (!registered || texture == null) return;
        graphics.blit(TEXTURE_ID, x, y, 0, 0, size, size, size, size);
    }

    public void release() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
            registered = false;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    public boolean isLoaded() {
        return registered && texture != null;
    }
}
