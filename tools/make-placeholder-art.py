# -*- coding: utf-8 -*-
"""
生成仓库自带的占位美术（不依赖任何第三方插画）。

开源版不能把「某个角色」的立绘和图标一起发出去，所以这里用纯代码画一只
通用的小水母/气泡鱼：一个半透明气泡身体、两只眼睛、一条小尾巴。
你完全可以跑 tools/make-icons.py 换成自己的图，或者直接改这个脚本里的颜色。

产出（覆盖 res/ 下的同名文件）：
  mipmap-*/ic_launcher.png         48/72/96/144/192
  mipmap-*/ic_launcher_round.png   圆形
  drawable-nodpi/ic_launcher_fg.png  自适应前景 432
  drawable-nodpi/ic_avatar.png     头像 192
  drawable-*/ic_notify.png         通知栏剪影 24/36/48/72
  drawable-nodpi/pet_main.png      悬浮球里的立绘 512×781

用法：python tools/make-placeholder-art.py
"""
import os

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "res")

SS = 4  # 超采样倍数：先画大图再缩小，边缘才不锯齿

MIPMAP = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
NOTIFY = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72}

DEEP = (10, 46, 126)      # 深海底色（和 res/values/colors.xml 的 ic_launcher_bg 一致）
BODY = (238, 250, 255)    # 气泡身体
BODY_EDGE = (150, 214, 246)
INK = (23, 44, 74)        # 眼睛
BLUSH = (255, 160, 170)
FIN = (126, 206, 244)


def vertical_gradient(size, top, bottom):
    """竖直线性渐变，先 1px 宽再拉伸，省内存。"""
    w, h = size
    g = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        g.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return g.resize((w, h), Image.BILINEAR)


def circle_mask(size, ss=4):
    m = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * ss - 1, size * ss - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def draw_ball(d, cx, cy, r, detailed=True):
    """画身体：一个圆 + 两条侧鳍 + 顶上小触须。d 是超采样画布上的 Draw。"""
    # 侧鳍（半透明蓝）
    d.ellipse([cx - r * 1.16, cy - r * 0.12, cx - r * 0.84, cy + r * 0.44], fill=FIN + (255,))
    d.ellipse([cx + r * 0.84, cy - r * 0.12, cx + r * 1.16, cy + r * 0.44], fill=FIN + (255,))
    # 触须
    d.line([(cx - r * 0.18, cy - r * 0.96), (cx - r * 0.30, cy - r * 1.34)],
           fill=FIN + (255,), width=max(2, int(r * 0.10)))
    d.ellipse([cx - r * 0.42, cy - r * 1.50, cx - r * 0.18, cy - r * 1.26], fill=FIN + (255,))
    # 身体
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=BODY + (255,),
              outline=BODY_EDGE + (255,), width=max(2, int(r * 0.06)))
    if not detailed:
        return
    # 高光
    d.ellipse([cx - r * 0.62, cy - r * 0.72, cx - r * 0.14, cy - r * 0.30],
              fill=(255, 255, 255, 200))
    # 眼睛
    er = r * 0.15
    for sx in (-0.34, 0.34):
        d.ellipse([cx + r * sx - er, cy + r * 0.02 - er * 1.25,
                   cx + r * sx + er, cy + r * 0.02 + er * 1.25], fill=INK + (255,))
        d.ellipse([cx + r * sx - er * 0.34, cy + r * 0.02 - er * 0.9,
                   cx + r * sx + er * 0.16, cy + r * 0.02 - er * 0.3],
                  fill=(255, 255, 255, 235))
    # 腮红 + 嘴
    for sx in (-0.60, 0.60):
        d.ellipse([cx + r * sx - r * 0.13, cy + r * 0.34 - r * 0.07,
                   cx + r * sx + r * 0.13, cy + r * 0.34 + r * 0.07],
                  fill=BLUSH + (140,))
    d.arc([cx - r * 0.13, cy + r * 0.22, cx + r * 0.13, cy + r * 0.44],
          start=20, end=160, fill=INK + (255,), width=max(2, int(r * 0.05)))


def icon_square(px):
    """满版方形图标：深海底 + 居中气泡鱼 + 一圈水光。"""
    n = px * SS
    img = vertical_gradient((n, n), (18, 78, 168), DEEP).convert("RGBA")
    d = ImageDraw.Draw(img, "RGBA")
    # 斜向水光带
    band = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    bd = ImageDraw.Draw(band, "RGBA")
    bd.polygon([(0, n * 0.62), (n, n * 0.28), (n, n * 0.52), (0, n * 0.86)], fill=(255, 255, 255, 26))
    img.alpha_composite(band)
    draw_ball(d, n * 0.5, n * 0.56, n * 0.26)
    return img.resize((px, px), Image.LANCZOS)


def icon_fg(px=432):
    """自适应图标前景：透明底，图案收在中间 66%（启动器会裁切）。"""
    n = px * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    draw_ball(ImageDraw.Draw(img, "RGBA"), n * 0.5, n * 0.54, n * 0.21)
    return img.resize((px, px), Image.LANCZOS)


def avatar(px=192):
    n = px * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse([0, 0, n - 1, n - 1], fill=(20, 74, 158, 255))
    draw_ball(d, n * 0.5, n * 0.54, n * 0.30)
    img = img.resize((px, px), Image.LANCZOS)
    img.putalpha(circle_mask(px))
    return img


def notify_master(master=192):
    """通知栏单色剪影：气泡 + 尾巴，24 网格手画（系统只看 alpha）。"""
    n = Image.new("RGBA", (master, master), (0, 0, 0, 0))
    d = ImageDraw.Draw(n)
    s = master / 24.0
    cx, cy, r = 10.2 * s, 12.6 * s, 7.0 * s
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 255))
    d.polygon([(cx + r * 0.9, cy), (22.4 * s, 8.4 * s), (22.4 * s, 16.8 * s)],
              fill=(255, 255, 255, 255))
    d.ellipse([4.2 * s, 4.0 * s, 6.6 * s, 6.4 * s], fill=(255, 255, 255, 255))
    return n


def pet_main(w=512, h=781):
    """悬浮球立绘：透明底，角色贴底居中（PetView 按底边中点对齐）。

    角色要占满画布高度的 2/3 左右——占太小的话，在 260dp 的悬浮窗里会缩成一个小点。
    """
    n_w, n_h = w * SS, h * SS
    img = Image.new("RGBA", (n_w, n_h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    cx = n_w * 0.5
    # 天线顶到身体底 = 2.5r：用 94% 的画布高反推半径，既占满又不被裁。
    # 地面阴影由 PetView 自己画，这里不要再加光晕，否则底边看起来像被切掉。
    r = min(n_w * 0.375, n_h * 0.94 / 2.5)
    cy = n_h * 0.95 - r
    draw_ball(d, cx, cy, r)
    return img.resize((w, h), Image.LANCZOS)


def main():
    for dens, px in MIPMAP.items():
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        sq = icon_square(px)
        sq.convert("RGB").save(os.path.join(d, "ic_launcher.png"))
        rnd = sq.copy()
        rnd.putalpha(circle_mask(px))
        rnd.save(os.path.join(d, "ic_launcher_round.png"))
        print("  mipmap-%s  %d" % (dens, px))

    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    icon_fg(432).save(os.path.join(nodpi, "ic_launcher_fg.png"))
    avatar(192).save(os.path.join(nodpi, "ic_avatar.png"))
    pet_main(512, 781).save(os.path.join(nodpi, "pet_main.png"))
    print("  drawable-nodpi  fg/avatar/pet_main")

    master = notify_master()
    for dens, px in NOTIFY.items():
        d = os.path.join(RES, "drawable-" + dens)
        os.makedirs(d, exist_ok=True)
        master.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_notify.png"))
        print("  drawable-%s/ic_notify  %d" % (dens, px))
    print("done")


main()
