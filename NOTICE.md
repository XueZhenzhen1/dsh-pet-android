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

**这些素材的版权属于 dsh-pet 的作者（或它的上游素材来源），不是本项目的产物。**

上游的分发方式（截至 2026-11）：

- **GitHub 仓库里没有这些文件**。`assets/` 目录只放了 `screenshots`；
  `.gitignore` 明确排除了素材处理链的中间产物（`step01~04/`）与视频源（`video/*.mp4`），
  视频源托管在该仓库的 **GitHub Releases（assets-videos）**。
- **它们随 npm 包分发**：`dsh-pet` 的 `package.json` 的 `files` 字段包含
  `assets/webm`、`assets/memes`、`assets/pic`、`assets/fonts`、`assets/sound`、
  `assets/main-character.png`，装完约 75 MB / 1207 个文件。
  也就是说：**代码开源在 GitHub，成品素材走 npm 发布**，两者都受 MIT 覆盖。

因此：

- `npm i dsh-pet` 之后，素材在 `node_modules/dsh-pet/assets/`，本项目只**读取本机这一份**，
  用 `tools/convert-anims.py` 转成本机能播的动图 WebP，产物落在 `~/.dsh/dsh-pet/pic/anim/`。
- **本仓库不包含任何 dsh-pet 素材**。如果你把转换后的 WebP、表情图、立绘打进自己的
  APK 再分发，那些文件仍然属于 dsh-pet 的素材，必须随包保留 MIT 声明与版权行。
- MIT 只覆盖插件作者有权授权的部分。若某个角色形象本身来自第三方（画师、游戏、AI 生成），
  插件作者的 MIT **不能替上游清权**。要商用或公开发布带角色的成品，请自行确认素材来源。
  本仓库因此只带 `tools/make-placeholder-art.py` 用代码画的占位美术。

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
