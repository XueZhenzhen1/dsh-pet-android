# 第三方组件与素材说明

本仓库（dsh-pet-android，以下简称「本项目」）的**代码**以 MIT 许可发布，见 [LICENSE](LICENSE)。
但它是上游桌面宠物插件 **dsh-pet** 的客户端，下面这些不属于本项目、也不由本项目授权：

---

## 1. dsh-pet（上游插件）

- 项目：https://github.com/PC2005-cloud/dsh-pet
- 包名：`dsh-pet`（npm）
- 许可：MIT License，Copyright (c) 2026 PC2005-cloud
- 本项目与它的关系：**通过 HTTP 接口通信的独立客户端**，不包含、不修改、也不再分发它的源码。

本项目用到的接口（均在 `/dsh-pet-7340` 前缀下）：

| 接口 | 用途 |
|---|---|
| `GET /state?since=&timeout=` | 长轮询说话、表情、动作、余额、天气 |
| `GET /config` | 动作分类、表情池、菜单结构 |
| `POST /anim` `/whisper` `/meme` `/sleep` `/wake` | 触发动作、说话、表情、睡觉 |
| `GET /pic/anim/<md5>.webp`、`GET /pic/memes/...` | 动作与表情的图片资源 |

> 接口是从插件运行时行为对齐的，没有抄它的实现代码。插件大版本升级后接口若变动，
> 本项目需要跟着改。

## 2. 素材（动作、表情、立绘、字体、音效）

**这些素材不是本项目的产物，也不由本项目授权。** 它们的授权与代码是分开的：

| 对象 | 授权 |
|---|---|
| dsh-pet 的**代码** | MIT |
| **素材**（动画 / 提示词 / 源视频） | 上游表述为「允许开源使用」，实际以角色原型的形象授权为准 |
| **角色形象**（大肥鱼 / 鲸鱼娘） | [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)：署名 · **非商业** · 相同方式共享 |

### 2.1 形象出处（上游 README 原文）

- 宠物形象是社区二创的 DeepSeek「大肥鱼」（鲸鱼娘），**不是 DeepSeek 官方形象**；
- 原型「溟月」出自 B 站 [@上善无形](https://space.bilibili.com/4456176)；
- DeepSeek 版（女仆鲸鱼娘）的设计出自 B 站 [@ZipZipPipe](https://space.bilibili.com/4168597)；
- 原作者声明原文：「大肥鱼是遵守 CC BY-NC-SA 4.0 协议的形象，**不可以以任何形式商业化**。」

### 2.2 素材是怎么造出来的

1. **形象**：上面那套二创设计。
2. **源视频**：用 AI 视频工具（上游说明为豆包）按 `prompts/桌面宠物 10 秒动作提示词.md` 的配方，
   一个动作生成一段 10 秒、16:9、纯绿幕（#00FF00）的视频，共 114 段。`video/` 不入 git，
   打包在 GitHub Releases 的 `assets-videos.zip` 里。
3. **抠像**：上游说明全部采用 Adobe PR 手工遮罩（自动 HSV 抠像脚本 `chroma_step02.py` 留作兜底），
   产出带 alpha 的 `.mov`，再转成 **VP9-alpha webm**（640×360），落到 `assets/webm/`。
4. **其它**：立绘 `main-character.png`、表情 27 张（`memes/`）、图标（`pic/`）、字体（3.9 MB）、音效。

### 2.3 分发方式

- GitHub 仓库里**没有**这些文件：`assets/` 只放 `screenshots`，`video/*.mp4` 与 `step01~04/` 都被 gitignore；
- 成品随 **npm 包**分发（`files` 字段含 `assets/webm`、`assets/memes`、`assets/pic`、
  `assets/fonts`、`assets/sound`、`assets/main-character.png`，约 75 MB / 1207 个文件）；
- macOS 用的 HEVC-alpha `.mov` 另发在 Release `assets-mov`。

### 2.4 对使用者的要求

- `npm i dsh-pet` 之后素材在 `node_modules/dsh-pet/assets/`，本项目只**读取本机这一份**，
  用 `tools/convert-anims.py` 转成本机能播的动图 WebP，产物落在 `~/.dsh/dsh-pet/pic/anim/`。
- **本仓库不含 dsh-pet 分发的那些文件**（`assets/webm`、`assets/memes`、`main-character.png`），
  只读取你本机已装的那一份。
- 但本仓库的 `art/` 目录带了一套**自己生成的二创美术**（图标与立绘），它按
  [CC BY-NC-SA 4.0](art/LICENSE) 发布，和代码的 MIT 是并列关系。App 内「关于」页
  保留了署名，这是协议要求，改动时别删。
- 若你把转换后的 WebP、表情、立绘打进 APK 再分发：这些文件仍受上面的形象授权约束——
  **必须署名（上游仓库 + 形象原作者）+ 不得商用 + 衍生物按相同协议共享**。
- 上游的「二创约定」：基于 dsh-pet 的衍生 / 改版 / 换皮作品，在 GitHub 仓库、产品官网等公开渠道
  须附上 <https://github.com/PC2005-cloud/dsh-pet>。
- 想商用（收费、投放、企业内分发）时这套素材**一条都过不去**，得自己出形象与素材，
  或另外取得 CC BY-NC-SA 之外的商业授权。
- 本仓库因此只带 `tools/make-placeholder-art.py` 用代码画的占位美术。

## 3. dsh / dsh-pocket

- `@restregular/dsh-pocket`：npm 包，负责把 dsh 的 3080 反代到 8790 并提供移动端页面。
  本项目只把它当服务端用，不打包它的代码。
- `dsh`：DeepSeek Harness，本项目的运行前提，同样不随本仓库分发。

## 4. 名称与商标

`dsh-pet` 是上游项目的名字。本仓库叫 `dsh-pet-android` 只是为了说明「它是 dsh-pet 的安卓客户端」，
**不代表上游作者、也没有获得任何形式的背书**。MIT 许可不包含商标授权，
如果你要 fork 后对外发布，建议改成自己的名字，避免让人误以为是官方版本。

---

如果你认为本仓库有任何内容侵犯了你的权利，请开 issue，我会立刻处理。
