# 鱼球 · dsh-pet-android

把电脑上的 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 宠物搬到手机桌面——不是网页里的一只，
而是浮在桌面和其它 App 之上的系统悬浮球：点一下开菜单，长按摸摸，双击直接进 dsh。

> 这是一个**客户端**：宠物本体、动作素材、说话内容都在电脑上的 dsh + dsh-pet 插件里。
> 手机端负责渲染、交互，以及在本机把动作真正播出来。

- Android 8.0+（minSdk 26，targetSdk 34）
- 不依赖 Gradle、不用 AndroidX，全部控件自绘
- 单个 APK 约 260 KB，无第三方运行时依赖

---

## 它能做什么

| 能力 | 说明 |
|---|---|
| 系统悬浮球 | `TYPE_APPLICATION_OVERLAY`，可拖动、吸边、半收、左右甩到对侧 |
| 动作 | 电脑上的 200+ 个动作名，映射到 114 段动画；**手机本机播同一段素材**，同时转发给电脑那只一起演 |
| 表情 | 插件里的表情池，点开即出图，磁盘缓存，离线也能看 |
| 说话 | 长按摸摸 / 菜单里选台词，先出本机台词再被 AI 那句替换，不转圈等 |
| 状态 | 余额、天气、在做的事，跟电脑端同一份 `/state` |
| 进 dsh | 双击打开 pocket 的移动页面（WebView，隐藏其中的网页宠物） |
| 保活 | 前台服务 + 电池白名单 + JobScheduler 看门狗 + 无障碍守护，被清理后能自己回来 |

设计细节（每个手势该发生什么、失败怎么提示）写在 [INTERACTION.md](INTERACTION.md)。

---

## 你需要先有的东西（电脑端）

手机端只是壳，电脑上这三样缺一不可：

1. **dsh**：`dsh web`，本地 `127.0.0.1:3080`。
2. **dsh-pet 插件**：装进 dsh 的 web profile，插件路由是 `/dsh-pet-7340`。
3. **@restregular/dsh-pocket**：把 3080 反代到 `0.0.0.0:8790`，给手机一个入口（也提供移动端 UI）。

```bash
dsh plugin --profile web add dsh-pet
dsh plugin --profile web add @restregular/dsh-pocket
dsh web --no-open
```

> `--no-open` 是必须的：不带它，`dsh web` 会去调系统浏览器（某些策略下会卡在启动阶段，
> 进程活着但一个端口都不监听）。

手机怎么连到电脑：**Tailscale**（跨网络、免公网端口）或同一个局域网。验证一下：

```bash
curl "http://<电脑地址>:8790/dsh-pet-7340/state?since=0&timeout=1000&token=<pocket token>"
# 期望 200，JSON 里有 pets.main
```

pocket 的 token 在 `~/.dsh/pocket/state.json` 的 `token` 字段。

---

## 构建

没有 Gradle。`build.sh` 直接把四个工具串起来：

```
aapt2 compile/link → javac → d8 → zip(classes.dex) → zipalign → apksigner
```

需要 JDK 17+、Android build-tools 34（`aapt2`、`lib/d8.jar`、`zipalign`、`apksigner`）、
`platforms/android-34/android.jar`，以及 python3（只用来往 zip 里塞 dex）。

```bash
JDK_DIR=$HOME/jdk-17 \
BT_DIR=$ANDROID_HOME/build-tools/34.0.0 \
ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar \
./build.sh
# → build/apk/dsh-pet.apk
```

| 环境变量 | 作用 | 默认 |
|---|---|---|
| `JDK_DIR` | JDK 根目录 | `$JAVA_HOME` |
| `BT_DIR` | build-tools 目录 | `toolchain/bt/android-*` |
| `ANDROID_JAR` | android.jar | `toolchain/plat/*/android.jar` |
| `PYTHON` | python 解释器 | `python3` / `python` |
| `KS` / `KS_PASS` / `KS_ALIAS` | 签名用 keystore | `release.jks` / 随机生成 |

`toolchain/` 不进仓库（一套 JDK 就 300MB），自己指向本机 SDK 即可。

**签名**：第一次构建会在仓库根目录生成 `release.jks`，口令随机打印在终端里——请自己存好，
换了口令等于换了签名，覆盖安装会冲突。`.gitignore` 已经把 `*.jks` 排除，别提交。

---

## 安装与首次配置

1. 装 APK，打开后点「让鱼球出来」，授予**悬浮窗**权限（必须）和**通知**权限（前台服务需要）。
2. 展开首页的「电脑地址」，填：
   - 地址：`http://100.x.x.x:8790`（Tailscale 里电脑的地址）
   - 令牌：pocket 的 token（也可以直接把地址写成 `http://…:8790/?token=xxxx`，会自动拆出来）
3. 点「保存并重连」，顶部胶囊变「在线」就成了。

想让它更不容易被系统杀掉，再打开首页「保活」卡里的三件事：电池优化白名单、
无障碍守护、重启后恢复。国产 ROM 还需要在系统设置里给它「自启动 / 后台常驻」权限。

### 连不上时按这个顺序看

| 提示 | 含义 | 怎么办 |
|---|---|---|
| `电脑上的 8790 端口没开` | dsh 没在跑 | 电脑上起 `dsh web --no-open` |
| `6 秒没连上` | Tailscale 断了 / 电脑睡了 | 看电脑托盘里的 Tailscale 是不是 Connected |
| `HTTP 401/403` | token 不对 | 重新从 `~/.dsh/pocket/state.json` 复制 |
| `HTTP 404` | 插件没装/没启用 | `dsh plugin --profile web add dsh-pet` |
| `通道已通但宠物没上线` | dsh 页面还没渲染出宠物 | 在电脑浏览器打开 dsh，等鱼球出现 |
| `还没填电脑地址` | 首次安装未配置 | 展开「电脑地址」填上 |

---

## 动作动画是怎么来的（这段是重点）

电脑上的动作是 **VP9 + alpha 的 webm**。Chromium 播得好好的，安卓不行：
`MediaCodec` 解 VP9 时**不输出 alpha 通道**，同一段素材在手机上是一块黑底。

所以走了一条离线转码 + 本机播放的路：

```
dsh-pet/assets/webm/<动作名>.webm        640×360, VP9+alpha, ~10s
   │  tools/convert-anims.py             ffmpeg 解 alpha
   │                                     → 按不透明像素包围盒裁切
   │                                     → 统一画幅 0.68 宽高比、高 260px
   │                                     → 逐帧 WebP（12fps）
   ▼
~/.dsh/dsh-pet/pic/anim/<md5(名字)[:12]>.webp
   │  GET /dsh-pet-7340/pic/anim/<hash>.webp
   ▼
手机 filesDir/anim/<hash>.webp → AnimatedImageDrawable → 画到悬浮球上
```

几个踩过的坑，写在这里省得你再踩：

- **画幅必须统一**。不统一的话，「蝴蝶蜜蜂环绕头顶」这种包围盒 369×199 的段落会把角色压小一半，
  下一段又弹回来，看起来像每段各是一只不同的宠物。
- **文件名用 `md5(动作名)[:12]`**，两端同一种算法，不需要清单文件对齐。算 md5 时记得
  `b & 0xff`——Java 的 byte 有符号，不掩码会输出 `ffffffab` 这种。
- **API < 28 没有 `AnimatedImageDrawable`**，自动降级成程序化形变（挤压、旋转、跳），
  幅度刻意做大，不让用户觉得「点了没反应」。
- 素材不随仓库分发。首次点某个动作会现下载（局域网 0.3~1s），之后是本机文件；
  打开动作列表时预取当前分类前 6 段，流量网络下不预取。

先转一遍：

```bash
python tools/convert-anims.py --jobs 4     # 增量：已有的跳过
python tools/convert-anims.py --only 吃白饭 --force
```

---

## 保活是怎么做的

系统清理后台时，单靠前台服务不够，这里叠了四层：

1. **前台服务**（`specialUse` 类型）+ 常驻通知；
2. **电池优化白名单**：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；
3. **JobScheduler 看门狗**：15 分钟一次的持久作业，检查悬浮球还在不在（带前台服务启动豁免）；
4. **无障碍守护**（可选）：`canRetrieveWindowContent=false`，不读屏幕内容，只每 30 秒看一眼本 App 的窗口还在不在。

另外还有 `onTaskRemoved` 里 1 秒后发广播复活、`BootReceiver` 处理开机 / 覆盖安装。
`Prefs.KEY_DESIRED` 区分「用户想要」和「当前在跑」，用户主动收起时不会被看门狗硬拉起来。

---

## 目录结构

```
AndroidManifest.xml        权限、悬浮窗服务、无障碍服务、开机广播
build.sh                   aapt2 → javac → d8 → zipalign → apksigner
src/local/dsh/pet/
  MainActivity.java        首页：状态、快捷动作、连接设置、保活开关
  PetService.java          前台服务：悬浮窗、长按/单击/双击、指令分发
  PetView.java             自绘悬浮球：待机呼吸、程序化形变、真实动画帧
  PetSync.java             /state 长轮询、配置与表情缓存
  AnimLib.java             动作动图的 md5 命名、下载、磁盘缓存、预取
  PetMenu.java             功能菜单（说话/表情/动作/余额/天气/设置）
  WebActivity.java         pocket 移动页面的 WebView
  Keeper.java / PetWatchdog.java / PetGuard.java / BootReceiver.java   保活四件套
  Ui.java / Glyph.java     自绘设计系统与 18 个线性图标
res/                       图标、立绘、无障碍配置、文案
tools/convert-anims.py     webm(VP9+alpha) → 逐帧 WebP
tools/make-icons.py        把自己的方形插画裁成全套图标
tools/make-placeholder-art.py  仓库自带的占位美术（纯代码画，无版权问题）
```

---

## 已知限制

- **只在 Android 14 / API 34 上验证过**，MIUI、ColorOS、HarmonyOS 的后台策略差异较大，
  自启动和悬浮窗可能需要手动放行。
- **明文 HTTP**：手机与电脑之间走 Tailscale 或局域网，没有 TLS。token 是唯一的门槛，
  不要把 8790 暴露到公网。
- **接口对齐 dsh-pet 0.4.x** 的 `/dsh-pet-7340`（`/state`、`/config`、`/anim`、`/whisper`、
  `/pic/*`）。插件大版本变了可能要跟着改。
- 立绘和图标是**占位美术**（`tools/make-placeholder-art.py` 画的），想换成自己的图：
  `python tools/make-icons.py --src 你的图.png --inset 0`。

## 参与

Issue 和 PR 都欢迎。改交互行为前请先改 [INTERACTION.md](INTERACTION.md)——那份文档是
「用户摸一下该发生什么」的唯一事实来源，代码跟着它走。

## 许可证

[MIT](LICENSE)。

动作素材、表情、宠物设定来自 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet)（MIT，
Copyright © 2026 PC2005-cloud），本仓库不重复分发这些资源。移动端入口由
[@restregular/dsh-pocket](https://www.npmjs.com/package/@restregular/dsh-pocket) 提供。

---

### English TL;DR

An Android overlay client for the **dsh-pet** plugin (a desktop pet living in the dsh web UI).
It puts the pet on top of your launcher as a floating ball, plays the same animation clips
locally (VP9-alpha WebM is transcoded to animated WebP because Android's MediaCodec drops the
alpha channel), and mirrors speech / expressions / actions from the desktop over
`dsh-pocket`. Requires dsh + dsh-pet + dsh-pocket on a PC reachable via Tailscale or LAN.
No Gradle, no AndroidX — `./build.sh` builds a ~260 KB APK with plain SDK tools.
MIT licensed. Placeholder art only; bring your own character.
