# -*- coding: utf-8 -*-
"""
把一张方形插画裁成安卓要用的全部图标（想换成自己的猫、自己的鱼都行）。

原图如果四角是黑的、或者带一圈描边/高光，直接当图标会在启动器里出现
「小方块贴在底色上」的观感。`--inset` 就是用来把那一圈裁掉的：
默认 0，按你原图的实际边距给（比如原图有 104px 的圆角黑边，就给 104）。

产出：
  res/mipmap-*/ic_launcher.png          48/72/96/144/192（满版方图）
  res/mipmap-*/ic_launcher_round.png    圆形裁切
  res/drawable-nodpi/ic_launcher_fg.png 自适应图标前景（108dp = 432px）
  res/drawable-nodpi/ic_avatar.png      菜单 / 首页用的小头像
  通知栏 ic_notify 不动：那是单色剪影，和插画无关

用法：
  python tools/make-icons.py --src 你的图.png [--inset 104] [--out res]
"""
import argparse
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MIPMAP = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def circle_mask(size, ss=4):
    m = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * ss - 1, size * ss - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="正方形插画（建议 1024×1024）")
    ap.add_argument("--inset", type=int, default=0, help="四边各裁掉多少像素（去掉圆角/描边）")
    ap.add_argument("--out", default=os.path.join(ROOT, "res"), help="输出到哪个 res 目录")
    a = ap.parse_args()

    src = Image.open(a.src).convert("RGB")
    w, h = src.size
    if a.inset * 2 >= min(w, h):
        raise SystemExit("--inset 太大：原图只有 %d×%d" % (w, h))
    full = src.crop((a.inset, a.inset, w - a.inset, h - a.inset))
    side = min(full.size)
    full = full.crop((0, 0, side, side)).resize((1024, 1024), Image.LANCZOS)

    for dens, px in MIPMAP.items():
        d = os.path.join(a.out, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        small = full.resize((px, px), Image.LANCZOS)
        small.save(os.path.join(d, "ic_launcher.png"))
        rnd = small.convert("RGBA")
        rnd.putalpha(circle_mask(px))
        rnd.save(os.path.join(d, "ic_launcher_round.png"))
        print("  mipmap-%s  %d" % (dens, px))

    nodpi = os.path.join(a.out, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)

    # 自适应图标前景：108dp 满版，不留背景环
    full.resize((432, 432), Image.LANCZOS).save(os.path.join(nodpi, "ic_launcher_fg.png"))

    # 头像：取上半部中央，圆形
    head = full.crop((int(1024 * 0.21), int(1024 * 0.17), int(1024 * 0.79), int(1024 * 0.75)))
    head = head.resize((192, 192), Image.LANCZOS).convert("RGBA")
    head.putalpha(circle_mask(192))
    head.save(os.path.join(nodpi, "ic_avatar.png"))
    print("done")


main()
