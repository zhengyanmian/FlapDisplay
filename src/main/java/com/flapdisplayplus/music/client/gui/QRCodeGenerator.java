package com.flapdisplayplus.music.client.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.mojang.blaze3d.platform.NativeImage;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成工具。
 *
 * 用 ZXing 把字符串（网易云扫码登录 URL）编码成二维码，转为 Minecraft 的 NativeImage，
 * 再配合 DynamicTexture 在登录界面里渲染。
 */
public class QRCodeGenerator {

    /**
     * 生成二维码图片。
     *
     * @param content 二维码内容（URL）
     * @param size    图片尺寸（像素，正方形）
     * @return 二维码 NativeImage（黑白）
     */
    public static NativeImage generate(String content, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

        NativeImage image = new NativeImage(size, size, false);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                // true = 黑（0xFF000000），false = 白（0xFFFFFFFF）
                int argb = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                image.setPixelRGBA(x, y, argb);
            }
        }
        return image;
    }
}
