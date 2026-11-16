#!/usr/bin/env python3
"""生成启动图标 PNG（不依赖 Pillow，纯 zlib 手写 PNG）。

设计跟新版亮色界面一致：淡紫蓝渐变底（左上一点紫光）+ 白色卡片 + 紫→粉渐变焦点环，
卡内左边一个渐变图标块、右边两行文字条，就是应用里「居中高亮的任务卡片」的缩影。

同时产出两套：
  1. 传统整图 ic_launcher.png（squircle 自带圆角，给不支持自适应图标的系统兜底）
  2. 自适应图标的 ic_launcher_background.png / ic_launcher_foreground.png
     （背景满铺渐变，前景只有卡片层并缩进 72dp 安全区内，交给系统裁形状）

坐标都在 108x108 设计稿单位里，缩放后 4x 超采样抗锯齿。
改完跑 `python tools/make_icon.py` 重生成。
"""
import os
import struct
import zlib

# ---- 配色（对应 Theme.java 亮色版）----
BG_TOP = (0xF1, 0xF4, 0xFE)
BG_BOTTOM = (0xCF, 0xDA, 0xF3)
GLOW = (0xA9, 0x8B, 0xFF)
RING_A = (0x7B, 0x4D, 0xE8)
RING_B = (0xE0, 0x55, 0xC8)
CARD = (0xFF, 0xFF, 0xFF)
BAR = (0xB9, 0xA6, 0xEE)

DESIGN = 108.0
BG_R = 26.0            # 传统整图的背景圆角

# 主卡片：横版，和界面里卡片（0.42 屏宽 × 0.8 倍高）比例接近
CARD_W, CARD_H, CARD_R = 56.0, 44.0, 12.0
CARD_X = (DESIGN - CARD_W) / 2
CARD_Y = (DESIGN - CARD_H) / 2

RING_GAP = 2.0         # 环和卡片之间的空隙
RING_W = 3.0           # 环的粗细

# 卡内：左边渐变图标块，右边两行「文字条」
TILE_W, TILE_H, TILE_R = 20.0, 20.0, 6.0
TILE_X = CARD_X + 7.0
TILE_Y = (DESIGN - TILE_H) / 2

BAR_H, BAR_R = 4.0, 2.0
BAR1_W, BAR1_Y = 17.0, 45.0
BAR2_W, BAR2_Y = 11.0, 53.0
BAR_X = TILE_X + TILE_W + 5.0

# 自适应图标前景要缩进 72dp 安全区：卡片+环一起缩到 0.82 倍
FG_SCALE = 0.82

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 4  # 超采样倍数


# ---------------------------------------------------------------- 几何 / 颜色
def inside_round_rect(x, y, rx, ry, w, h, r):
    if x < rx or y < ry or x >= rx + w or y >= ry + h:
        return False
    cx = min(max(x, rx + r), rx + w - r)
    cy = min(max(y, ry + r), ry + h - r)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= r * r


def lerp(a, b, t):
    t = 0.0 if t < 0 else (1.0 if t > 1 else t)
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


def over(dst, src, sa):
    """straight-alpha 的 over 合成。"""
    dr, dg, db, da = dst
    out_a = sa + da * (1.0 - sa)
    if out_a <= 0.0001:
        return (0.0, 0.0, 0.0, 0.0)
    k = da * (1.0 - sa)
    return ((src[0] * sa + dr * k) / out_a,
            (src[1] * sa + dg * k) / out_a,
            (src[2] * sa + db * k) / out_a,
            out_a)


# ---------------------------------------------------------------- 采样
def background(x, y, rounded):
    """渐变底 + 左上紫光。rounded=True 用于传统整图，False 用于自适应背景层。"""
    if rounded:
        if not inside_round_rect(x, y, 0, 0, DESIGN, DESIGN, BG_R):
            return (0.0, 0.0, 0.0, 0.0)
    t = (x * 0.45 + y * 0.55) / DESIGN
    bg = lerp(BG_TOP, BG_BOTTOM, t)
    gx, gy = x - DESIGN * 0.18, y - DESIGN * 0.08
    gr = ((gx * gx + gy * gy) ** 0.5) / (DESIGN * 0.85)
    if gr < 1.0:
        bg = lerp(bg, GLOW, 0.45 * (1.0 - gr) * (1.0 - gr))
    return (bg[0], bg[1], bg[2], 1.0)


def sample(x, y, mode):
    """mode: legacy（整图）/ bg（自适应背景层）/ fg（自适应前景层）。"""
    if mode in ("legacy", "bg"):
        col = background(x, y, rounded=(mode == "legacy"))
        if mode == "bg":
            return col
    else:
        col = (0.0, 0.0, 0.0, 0.0)

    if mode == "fg":
        # 以前景层中心为基准整体缩放
        mid = DESIGN / 2.0
        x = (x - mid) / FG_SCALE + mid
        y = (y - mid) / FG_SCALE + mid

    # 1) 焦点环：卡片外 RING_GAP ~ RING_GAP+RING_W 的一圈，紫 → 粉
    ring_out = RING_GAP + RING_W
    in_outer = inside_round_rect(x, y, CARD_X - ring_out, CARD_Y - ring_out,
                                 CARD_W + ring_out * 2, CARD_H + ring_out * 2,
                                 CARD_R + ring_out)
    in_inner = inside_round_rect(x, y, CARD_X - RING_GAP, CARD_Y - RING_GAP,
                                 CARD_W + RING_GAP * 2, CARD_H + RING_GAP * 2,
                                 CARD_R + RING_GAP)
    if in_outer and not in_inner:
        t = ((x - CARD_X) + (y - CARD_Y)) / (CARD_W + CARD_H)
        col = over(col, lerp(RING_A, RING_B, t), 1.0)

    # 2) 主卡片
    if inside_round_rect(x, y, CARD_X, CARD_Y, CARD_W, CARD_H, CARD_R):
        col = over(col, CARD, 1.0)

    # 3) 卡内渐变图标块
    if inside_round_rect(x, y, TILE_X, TILE_Y, TILE_W, TILE_H, TILE_R):
        t = ((x - TILE_X) + (y - TILE_Y)) / (TILE_W + TILE_H)
        col = over(col, lerp(RING_A, RING_B, t), 1.0)

    # 4) 卡内两行「文字条」
    if (inside_round_rect(x, y, BAR_X, BAR1_Y, BAR1_W, BAR_H, BAR_R)
            or inside_round_rect(x, y, BAR_X, BAR2_Y, BAR2_W, BAR_H, BAR_R)):
        col = over(col, BAR, 1.0)

    return col


def render(size, mode):
    scale = size / DESIGN
    inv = 1.0 / SS
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            r = g = b = a = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    x = (px + (sx + 0.5) * inv) / scale
                    y = (py + (sy + 0.5) * inv) / scale
                    sr, sg, sb, sa = sample(x, y, mode)
                    r += sr * sa
                    g += sg * sa
                    b += sb * sa
                    a += sa
            n = SS * SS
            if a > 0:
                row += bytes((round(r / a), round(g / a), round(b / a),
                              round(255 * a / n)))
            else:
                row += b"\x00\x00\x00\x00"
        rows.append(bytes(row))
    return rows


def write_png(path, size, rows):
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                        "app", "src", "main", "res")
    for dpi, size in SIZES.items():
        d = os.path.join(root, "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        jobs = (("ic_launcher.png", "legacy"),
                ("ic_launcher_background.png", "bg"),
                ("ic_launcher_foreground.png", "fg"))
        for name, mode in jobs:
            write_png(os.path.join(d, name), size, render(size, mode))
        print("mipmap-%s/  ic_launcher + background + foreground (%dpx)" % (dpi, size))


if __name__ == "__main__":
    main()
