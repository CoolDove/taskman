#!/usr/bin/env python3
"""生成启动图标 PNG（不依赖 Pillow，纯 zlib 手写 PNG）。

设计：深色圆角方块 + 三行「绿色方块 + 白色横条」，和桌面上的列表行一致。
"""
import os
import struct
import zlib

BG = (0x10, 0x16, 0x1F)
GREEN = (0x3D, 0xDC, 0x84)
WHITE = (0xE8, 0xED, 0xF5)

# 在 108x108 的设计稿里
SHAPES = [
    (24, 32, 11, 11, GREEN),
    (42, 34, 42, 8, WHITE),
    (24, 49, 11, 11, GREEN),
    (42, 51, 42, 8, WHITE),
    (24, 66, 11, 11, GREEN),
    (42, 68, 42, 8, WHITE),
]
DESIGN = 108.0
CORNER = 24.0  # 圆角半径（设计稿单位）

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 4  # 超采样倍数


def inside_round_rect(x, y, w, h, r):
    if x < 0 or y < 0 or x >= w or y >= h:
        return False
    cx = min(max(x, r), w - r)
    cy = min(max(y, r), h - r)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= r * r


def render(size):
    scale = size / DESIGN
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            r = g = b = a = 0
            # 背景覆盖率
            cover = 0
            for sy in range(SS):
                for sx in range(SS):
                    x = (px + (sx + 0.5) / SS) / scale
                    y = (py + (sy + 0.5) / SS) / scale
                    if inside_round_rect(x, y, DESIGN, DESIGN, CORNER):
                        cover += 1
            if cover:
                r, g, b = BG
                a = round(255 * cover / (SS * SS))
            # 前景图形
            for (sx0, sy0, sw, sh, color) in SHAPES:
                c = 0
                for sy in range(SS):
                    for sx in range(SS):
                        x = (px + (sx + 0.5) / SS) / scale
                        y = (py + (sy + 0.5) / SS) / scale
                        if sx0 <= x < sx0 + sw and sy0 <= y < sy0 + sh:
                            c += 1
                if c:
                    ca = c / (SS * SS)
                    r = round(r * (1 - ca) + color[0] * ca)
                    g = round(g * (1 - ca) + color[1] * ca)
                    b = round(b * (1 - ca) + color[2] * ca)
                    a = max(a, round(255 * ca))
            row += bytes((r, g, b, a))
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
        write_png(os.path.join(d, "ic_launcher.png"), size, render(size))
        print("mipmap-%s/ic_launcher.png  %dx%d" % (dpi, size, size))


if __name__ == "__main__":
    main()
