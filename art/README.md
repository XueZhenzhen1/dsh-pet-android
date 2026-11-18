# art/ — 角色美术（CC BY-NC-SA 4.0）

这里的图是**完整版**用的真美术，和 `res/` 里的占位美术二选一：
`tools/build-full.sh` 会把本目录覆盖到 `res/` 再编译，编完自动还原，不会污染仓库里的占位图。

## 署名与授权

角色是社区二创的 DeepSeek「大肥鱼」（鲸鱼娘），**不是 DeepSeek 官方形象**：

- 原型「溟月」：B 站 [@上善无形](https://space.bilibili.com/4456176)
- DeepSeek 版（女仆鲸鱼娘）设计：B 站 [@ZipZipPipe](https://space.bilibili.com/4168597)
- 上游插件：dsh-pet <https://github.com/PC2005-cloud/dsh-pet>
- 本目录的图：在上述形象基础上用 AI 工具生成 / 重绘

**授权：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)**
（署名 · 非商业 · 相同方式共享）。原作者声明：
「大肥鱼是遵守 CC BY-NC-SA 4.0 协议的形象，**不可以以任何形式商业化**。」

也就是说：可以自由使用、修改、再分发，但必须署名、不能商用、衍生作品沿用同一协议。

## 文件对应关系

| 文件 | 用途 |
|---|---|
| `mipmap-*/ic_launcher.png` | 传统桌面图标（mdpi ~ xxxhdpi 五档） |
| `mipmap-*/ic_launcher_round.png` | 圆形图标 |
| `drawable-nodpi/ic_launcher_fg.png` | adaptive icon 前景（背景色见 `res/values/colors.xml` 的 `ic_launcher_bg`） |
| `drawable-nodpi/ic_avatar.png` | 设置页头像 |
| `drawable-nodpi/pet_main.png` | 悬浮球立绘：透明底、角色贴底居中。只是**离线兜底**，联网后用的是电脑端的 `main-character.png` |
| `drawable-*/ic_notify.png` | 通知栏剪影（单色，系统会再染色） |

## 想换成自己的角色

改 `tools/build-full.sh` 的 `ART` 变量指向你自己的目录，或者直接替换本目录里的同名文件。
角色形象一换，上面这套 CC 署名义务就不再适用——那时请改成你自己的授权说明。
