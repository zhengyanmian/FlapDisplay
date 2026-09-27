# -*- coding: utf-8 -*-
"""从用户提供的图片裁出翻牌板区域，生成模组 logo（128x128）"""
import io, sys
from PIL import Image
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

SRC = r'C:\Users\24508\.workbuddy\clipboard-images\clipboard-2026-09-27T18-57-08-525Z-3177e711.jpg'
OUT_128 = r'C:\Users\24508\WorkBuddy\2026-07-06-06-02-49\flap-display-plus\src\main\resources\flapdisplayplus_logo.png'
OUT_PREVIEW = r'C:\Users\24508\WorkBuddy\2026-07-06-06-02-49\flap-display-plus\build\icon-preview-512.png'

img = Image.open(SRC).convert('RGB')
w, h = img.size
print('source size:', w, h)
px = img.load()

# 找暗色卡片区域（亮度低）的包围盒：扫描中央区域
def brightness(p):
    return 0.299*p[0] + 0.587*p[1] + 0.114*p[2]

xs, ys = [], []
step = 4
for y in range(0, h, step):
    for x in range(0, w, step):
        if brightness(px[x, y]) < 90:  # 深色翻牌板像素
            xs.append(x); ys.append(y)

if not xs:
    print('no dark region found'); sys.exit(1)

x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
print('dark bbox:', x0, y0, x1, y1, '->', x1-x0, 'x', y1-y0)

# 圆角边内缩几个像素，避免残留蓝色底板
inset = 8
crop = img.crop((x0+inset, y0+inset, x1-inset+1, y1-inset+1))
print('cropped:', crop.size)

# 检查四角是否还有蓝色残留
cw, ch = crop.size
cpx = crop.load()
def is_blue(p):
    return p[2] > 150 and p[2] - p[0] > 40 and p[2] - p[1] > 30
corner = 12
blue_cnt = sum(1 for y in range(corner) for x in range(corner) if is_blue(cpx[x, y]))
print('blue pixels in top-left corner sample:', blue_cnt)

# 把残留蓝色像素替换为邻近深色（简单 flood：直接替换为 (17,17,17)）
def clean_blue(im):
    p = im.load()
    n = 0
    for y in range(im.height):
        for x in range(im.width):
            if is_blue(p[x, y]):
                p[x, y] = (17, 17, 17); n += 1
    return n

n = clean_blue(crop)
print('cleaned blue pixels:', n)

logo = crop.resize((128, 128), Image.LANCZOS)
logo.save(OUT_128)
print('logo saved:', OUT_128)

prev = crop.resize((512, 512), Image.LANCZOS)
prev.save(OUT_PREVIEW)
print('preview saved:', OUT_PREVIEW)
