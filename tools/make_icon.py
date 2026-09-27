# -*- coding: utf-8 -*-
"""清理 AI 水印 + 裁剪居中 + 生成模组 logo（128x128）与预览图"""
from PIL import Image, ImageFilter
import os

SRC = r"C:\Users\24508\WorkBuddy\2026-07-06-06-02-49\generated-images\Square_Minecraft_mod_icon__ste_2026-09-27T13-32-24.png"
OUT_DIR = r"C:\Users\24508\WorkBuddy\2026-07-06-06-02-49\flap-display-plus\src\main\resources"
PREVIEW = r"C:\Users\24508\WorkBuddy\2026-07-06-06-02-49\flap-display-plus\build\icon-preview-512.png"

img = Image.open(SRC).convert("RGB")
w, h = img.size  # 1024x1024
print("src", img.size)

# ---- 1. 水印修补：右下角水印区域用「左下角镜像」覆盖（框架纹理对称，视觉无痕）----
# 水印大致在 x 850..1024, y 945..1024（留些余量）
patch_w, patch_h = 200, 110
sx0, sy0 = w - patch_w, h - patch_h           # 右下目标区
# 源：左下角同尺寸区域，水平镜像
src_patch = img.crop((0, h - patch_h, patch_w, h)).transpose(Image.FLIP_LEFT_RIGHT)
img.paste(src_patch, (sx0, sy0))
# 轻微模糊补丁边缘，避免生硬接缝（只对补丁区域及其 6px 边缘做高斯混合）
region = img.crop((sx0 - 6, sy0 - 6, w, h)).filter(ImageFilter.GaussianBlur(1.2))
img.paste(region, (sx0 - 6, sy0 - 6))

# ---- 2. 裁掉外围深色留白，取中心内容（图标外围有一圈纯背景）----
# 扫描非背景内容包围盒：背景是极暗的深绿，亮度阈值法
gray = img.convert("L")
# 用阈值 26 找内容（边框外是近黑背景 ~10-18）
mask = gray.point(lambda p: 255 if p > 26 else 0)
bbox = mask.getbbox()
print("content bbox", bbox)
if bbox:
    # 稍微内收 2px 去除阈值噪点边缘
    x0, y0, x1, y1 = bbox
    pad = 2
    x0, y0, x1, y1 = max(0, x0 + pad), max(0, y0 + pad), min(w, x1 - pad), min(h, y1 - pad)
    # 取正方形（内容本身接近正方形）
    side = min(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    half = side // 2
    img_sq = img.crop((cx - half, cy - half, cx + half, cy + half))
else:
    img_sq = img
print("square", img_sq.size)

# ---- 3. 输出 ----
img_sq.save(PREVIEW.replace("icon-preview-512.png", "icon-clean-1024.png"))
img_sq.resize((512, 512), Image.LANCZOS).save(PREVIEW)
logo128 = img_sq.resize((128, 128), Image.LANCZOS)
logo128.save(os.path.join(OUT_DIR, "flapdisplayplus_logo.png"))
print("saved logo 128 ->", os.path.join(OUT_DIR, "flapdisplayplus_logo.png"))
print("saved preview 512 ->", PREVIEW)
