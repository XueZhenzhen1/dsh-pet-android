#!/usr/bin/env bash
# 完整版构建：把 art/ 里的角色美术临时覆盖到 res/，编译，然后把 res/ 原样还原。
#
# 和 build.sh 的区别只有一个——美术。工具链环境变量完全一致：
#   JDK_DIR / BT_DIR / ANDROID_JAR / PYTHON / KS / KS_PASS / KS_ALIAS
#
# 例：
#   ./tools/build-full.sh
#   ART=~/my-art ./tools/build-full.sh        # 换一套美术
#
# 产物：build/apk/dsh-pet-full.apk
#
# 注意：art/ 里的图是 CC BY-NC-SA 4.0（署名 · 非商业 · 相同方式共享）。
# 分发带这套美术的 APK 时，署名与协议说明不能省，详见 art/README.md。
set -euo pipefail
cd "$(dirname "$0")/.."

ART="${ART:-art}"
[ -d "$ART" ] || { echo "没有美术目录：$ART"; exit 1; }

# 备份 res，构建结束（无论成功失败）都还原，避免真美术被误提交。
# 用仓库内的相对路径：Windows 上对 /tmp 这种绝对路径调用 rm 会被安全策略拦掉，
# 备份目录留在仓库里反而更稳（已被 .gitignore 排除）。
BAK=".res-backup"
rm -rf "$BAK"
mkdir -p "$BAK"
cp -a res/. "$BAK/"
restore() {
  rm -rf res
  mkdir -p res
  cp -a "$BAK/." res/
  rm -rf "$BAK"
}
trap restore EXIT

echo "[full] 覆盖美术：$ART"
cp -a "$ART/." res/

./build.sh

OUT="build/apk/dsh-pet.apk"
FULL="build/apk/dsh-pet-full.apk"
[ -f "$OUT" ] || { echo "构建没有产出 $OUT"; exit 1; }
cp "$OUT" "$FULL"
echo "[full] 产物：$FULL  $(stat -c '%s' "$FULL" 2>/dev/null || wc -c < "$FULL") B"
echo "[full] res/ 已还原为占位美术"
