#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把插件里的动作素材（VP9 + alpha 的 webm）转成安卓能直接播的动图 WebP。

为什么需要这一步：
    桌面端靠 Chromium 的 <video> 播 VP9-alpha，安卓的 MediaCodec 不输出 alpha 通道，
    同一个 webm 在手机上只能解出一块黑底。所以动作素材要在 PC 上先转成
    逐帧带 alpha 的动图 WebP，手机用 AnimatedImageDrawable 播。

为什么不上限时长：
    这些动作平均 10 秒，截断会让鱼在动作做到一半突然弹回站姿。
    宁可一段多几百 KB，也不要一个「卡掉一半」的动作。

产物：
    <out>/<md5(名字)[:12]>.webp   —— 按角色包围盒裁切、缩到 MAX_H，文件名由动作名决定
    <out>/index.json              —— {名字: {f, ms, w, h, kb}}，人工排查用

手机上用同名算法算 md5，所以两边不需要额外协议。

用法：
    python tools/convert-anims.py                 # 全量（增量：已有的跳过）
    python tools/convert-anims.py --only 吃白饭    # 只转一段
    python tools/convert-anims.py --list          # 只列名字和哈希
    python tools/convert-anims.py --force         # 全部重转
"""
import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import time

from PIL import Image

DEFAULT_SRC = os.path.expanduser(
    "~/.dsh/profiles/web/node_modules/dsh-pet/assets/webm"
)
DEFAULT_OUT = os.path.expanduser("~/.dsh/dsh-pet/pic/anim")

# 悬浮球实际显示是 78dp 宽、119dp 高，xxhdpi 下约 234×357 px。
# 高度 210 已经够清，再高只是白占带宽和手机存储。
FPS = 12
MAX_H = 260
QUALITY = 58
ALPHA_QUALITY = 60
# 统一输出画幅：角色一律放在 0.68 比例的方块里（和静态立绘 512:781 = 0.656 基本一致）。
# 不统一的话，带特效的段（比如「蝴蝶蜜蜂环绕」包围盒 369×199）贴上去会把鱼压小一半，
# 换下一段又弹回原尺寸——看起来就像每段动画各是一个角色。
CLIP_ASPECT = 0.68
ALPHA_FLOOR = 24          # alpha 低于这个当作透明：有损编码会在全画面留 alpha=1 的噪声
PAD = 3


def find_ffmpeg():
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass
    for p in ("ffmpeg", r"C:\ffmpeg\bin\ffmpeg.exe"):
        try:
            subprocess.run([p, "-version"], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=True)
            return p
        except Exception:
            continue
    raise SystemExit("找不到 ffmpeg：pip install imageio-ffmpeg")


def digest(name):
    return hashlib.md5(name.encode("utf-8")).hexdigest()[:12]


def frames_of(im):
    while True:
        yield im.convert("RGBA")
        try:
            im.seek(im.tell() + 1)
        except EOFError:
            return


def bbox_of(frames):
    """所有帧 alpha 的并集包围盒——角色在动作里会移动，只按首帧裁会切到手脚。"""
    lo = [10 ** 9, 10 ** 9, -1, -1]
    for f in frames:
        a = f.getchannel("A").point(lambda v: 255 if v > ALPHA_FLOOR else 0)
        b = a.getbbox()
        if b:
            lo[0] = min(lo[0], b[0])
            lo[1] = min(lo[1], b[1])
            lo[2] = max(lo[2], b[2])
            lo[3] = max(lo[3], b[3])
    if lo[2] < 0:
        return None
    return lo


def convert(ff, src, dst):
    # 直接读 ffmpeg 的 stdout：不落临时文件，也就不用管这个环境里被接管了的删除操作
    r = subprocess.run(
        [ff, "-hide_banner", "-loglevel", "error",
         "-c:v", "libvpx-vp9", "-i", src,
         "-vf", "scale=-1:%d:flags=lanczos,fps=%d" % (MAX_H, FPS),
         "-c:v", "libwebp_anim", "-lossless", "0", "-q:v", str(QUALITY),
         "-loop", "0", "-an", "-f", "webp", "-"],
        capture_output=True)
    if r.returncode != 0 or not r.stdout:
        raise RuntimeError((r.stderr or b"").decode("utf-8", "ignore")[:200])

    im = Image.open(io.BytesIO(r.stdout))
    frames = list(frames_of(im))
    if not frames:
        return None
    box = bbox_of(frames)
    if box is None:
        return None
    w0, h0 = frames[0].size
    box = [max(0, box[0] - PAD), max(0, box[1] - PAD),
           min(w0, box[2] + PAD), min(h0, box[3] + PAD)]
    cw, ch = box[2] - box[0], box[3] - box[1]

    # 把包围盒收进固定画幅：高度照用，宽度按 CLIP_ASPECT 居中取/补，
    # 宽出来的部分（飘在两侧的特效）裁掉，窄的部分补透明——角色本身一点不动。
    target_w = ch * CLIP_ASPECT
    cx = (box[0] + box[2]) / 2.0
    left_f = cx - target_w / 2.0
    canvas_w = max(1, int(round(target_w)))
    scale = min(1.0, float(MAX_H) / max(1, ch))
    nw, nh = max(1, round(canvas_w * scale)), max(1, round(ch * scale))

    out = []
    for f in frames:
        canvas = Image.new("RGBA", (canvas_w, ch), (0, 0, 0, 0))
        canvas.paste(f.crop(tuple(box)), (int(round(box[0] - left_f)), 0))
        out.append(canvas.resize((nw, nh), Image.LANCZOS))

    dur = int(round(1000.0 / FPS))
    buf = io.BytesIO()
    out[0].save(buf, format="WEBP", save_all=True, append_images=out[1:],
                duration=dur, loop=0, quality=QUALITY,
                alpha_quality=ALPHA_QUALITY, method=4)
    data = buf.getvalue()
    with open(dst, "wb") as fh:
        fh.write(data)
    return {"f": os.path.basename(dst), "ms": dur * len(out), "w": nw, "h": nh,
            "frames": len(out), "kb": round(len(data) / 1024.0, 1)}


def _worker(job):
    """多进程工人。必须是模块级函数——Windows 的 spawn 不认闭包。"""
    src, dst, name, force, ff = job
    if os.path.exists(dst) and not force:
        return name, {"skipped": True, "kb": os.path.getsize(dst) / 1024.0}
    try:
        info = convert(ff, os.path.join(src, name + ".webm"), dst)
    except Exception as e:
        return name, {"error": str(e)}
    if info is None:
        return name, {"error": "empty"}
    return name, info


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--only", default=None)
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--jobs", type=int, default=1, help="并行进程数（VP9 软件解码很吃 CPU）")
    args = ap.parse_args()

    if not os.path.isdir(args.src):
        raise SystemExit("素材目录不存在：%s" % args.src)
    names = sorted(f[:-5] for f in os.listdir(args.src) if f.lower().endswith(".webm"))
    if args.only:
        names = [n for n in names if args.only in n]
    if args.list:
        for n in names:
            print("%s\t%s" % (digest(n), n))
        return
    if not names:
        raise SystemExit("没有可转的素材")

    ff = find_ffmpeg()
    os.makedirs(args.out, exist_ok=True)
    index_path = os.path.join(args.out, "index.json")
    index = {}
    if os.path.exists(index_path):
        try:
            with io.open(index_path, encoding="utf-8") as fh:
                index = json.load(fh)
        except Exception:
            index = {}

    jobs = [(args.src, os.path.join(args.out, digest(n) + ".webp"), n, args.force, ff)
            for n in names]
    t0 = time.time()
    stat = {"done": 0, "mb": 0.0}

    def handle(n, info):
        stat["done"] += 1
        stat["mb"] += info.get("kb", 0) / 1024.0
        if info.get("skipped"):
            print("[%3d/%3d] 跳过 %s（已有）" % (stat["done"], len(names), n), flush=True)
            return
        if info.get("error"):
            print("[%3d/%3d] 失败 %s：%s" % (stat["done"], len(names), n, info["error"]), flush=True)
            return
        index[n] = info
        print("[%3d/%3d] %s -> %s %dx%d %d帧 %.0fKB  累计%.1fMB  %.0fs"
              % (stat["done"], len(names), n, info["f"], info["w"], info["h"],
                 info["frames"], info["kb"], stat["mb"], time.time() - t0), flush=True)

    if args.jobs > 1:
        from multiprocessing import Pool
        with Pool(args.jobs) as pool:
            for n, info in pool.imap_unordered(_worker, jobs, chunksize=1):
                handle(n, info)
    else:
        for job in jobs:
            n, info = _worker(job)
            handle(n, info)

    with io.open(index_path, "w", encoding="utf-8") as fh:
        json.dump(index, fh, ensure_ascii=False, indent=1, sort_keys=True)
    print("完成：%d 段，共 %.1f MB，用时 %.0fs"
          % (len(index), stat["mb"], time.time() - t0), flush=True)


if __name__ == "__main__":
    main()
