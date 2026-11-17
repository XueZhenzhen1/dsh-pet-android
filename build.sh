#!/usr/bin/env bash
# 不依赖 Gradle / AndroidX 的精简构建：aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner
#
# 需要的三样东西（可以用环境变量指过去，不设就在 toolchain/ 下找）：
#   JDK 17+          JDK_DIR       e.g. /opt/jdk-17
#   Android build-tools BT_DIR     34.x，里面要有 aapt2 / d8.jar / zipalign / apksigner
#   平台 android.jar  ANDROID_JAR  android-34 的 android.jar
#
# 例：
#   JDK_DIR=$HOME/jdk17 BT_DIR=$ANDROID_HOME/build-tools/34.0.0 \
#   ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

# ---------- 定位工具 ----------
first_dir() { for d in "$@"; do [ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return 0; }; done; return 1; }
first_file() { for f in "$@"; do [ -n "$f" ] && [ -f "$f" ] && { echo "$f"; return 0; }; done; return 1; }

JDK="${JDK_DIR:-$(first_dir "${JAVA_HOME:-}" toolchain/jdk/jdk-* || true)}"
BT="${BT_DIR:-$(first_dir toolchain/bt/android-* || true)}"
AJAR="${ANDROID_JAR:-$(first_file toolchain/plat/*/android.jar || true)}"

: "${JDK:?找不到 JDK，请设置 JDK_DIR 或 JAVA_HOME}"
: "${BT:?找不到 build-tools，请设置 BT_DIR（里面要有 aapt2 与 lib/d8.jar）}"
: "${AJAR:?找不到 android.jar，请设置 ANDROID_JAR（android-34 的 android.jar）}"

if [ -x "$JDK/bin/java.exe" ]; then            # Windows
  JAVA="$JDK/bin/java.exe"; JAVAC="$JDK/bin/javac.exe"; KEYTOOL="$JDK/bin/keytool.exe"
  AAPT2="$BT/aapt2.exe"; ZIPALIGN="$BT/zipalign.exe"; APKSIGNER="$BT/apksigner.bat"
else                                            # Linux / macOS
  JAVA="$JDK/bin/java"; JAVAC="$JDK/bin/javac"; KEYTOOL="$JDK/bin/keytool"
  AAPT2="$BT/aapt2"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
fi
D8JAR="$BT/lib/d8.jar"
[ -x "$JAVA" ] && [ -x "$JAVAC" ] || { echo "JDK 不完整：$JDK"; exit 1; }
[ -x "$AAPT2" ] || { echo "没有 aapt2：$AAPT2"; exit 1; }
[ -f "$D8JAR" ] || { echo "没有 d8.jar：$D8JAR"; exit 1; }

# apksigner / keytool 是 java 程序，必须能找到 java：同时给 Unix 和 Windows 两种形式的 JAVA_HOME
JDK_UX="$(cd "$JDK" && pwd)"
export PATH="$JDK_UX/bin:$PATH"
if command -v cygpath >/dev/null 2>&1; then
  export JAVA_HOME="$(cygpath -w "$JDK_UX")"
else
  export JAVA_HOME="$JDK_UX"
fi

# 打包 classes.dex 这一步用 Python 写 zip（比再引一个 jar 轻）
PY="${PYTHON:-$(command -v python3 || command -v python || true)}"
: "${PY:?找不到 python，请设置 PYTHON}"

OUT="build"
KS="${KS:-release.jks}"
KS_ALIAS="${KS_ALIAS:-dsh-pet}"
if [ -z "${KS_PASS:-}" ]; then                   # 没给口令就现场生成，别把口令写进仓库
  KS_PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-20)"
  echo "未设置 KS_PASS，本次用随机口令：$KS_PASS（请自行保存；换口令等于换签名）"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "[1/7] 编译资源"
"$AAPT2" compile --dir res -o "$OUT/res.zip"

echo "[2/7] 链接清单与资源"
"$AAPT2" link \
  -o "$OUT/apk/base.apk" \
  -I "$AJAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --java "$OUT/gen" \
  "$OUT/res.zip"

echo "[3/7] 编译 Java"
find src "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 --release 11 -nowarn \
  -classpath "$AJAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "[4/7] 转成 dex"
# shellcheck disable=SC2046
"$JAVA" -cp "$D8JAR" com.android.tools.r8.D8 \
  --min-api 26 --lib "$AJAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

echo "[5/7] 打包 classes.dex"
"$PY" - "$OUT" <<'PYEOF'
import shutil, sys, zipfile
out = sys.argv[1]
shutil.copyfile(f"{out}/apk/base.apk", f"{out}/apk/unsigned.apk")
with zipfile.ZipFile(f"{out}/apk/unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{out}/dex/classes.dex", "classes.dex")
print("   classes.dex 已写入")
PYEOF

echo "[6/7] 对齐"
"$ZIPALIGN" -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

echo "[7/7] 签名"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=dsh pet android, OU=dev, O=dev, L=dev, ST=dev, C=CN" 2>&1 | tail -2
fi

if [ -f "$APKSIGNER" ] || command -v "$APKSIGNER" >/dev/null 2>&1; then
  "$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$OUT/apk/dsh-pet.apk" "$OUT/apk/aligned.apk"
  "$APKSIGNER" verify --print-certs "$OUT/apk/dsh-pet.apk" | head -4
  ls -lh "$OUT/apk/dsh-pet.apk"
  echo "完成：$PWD/$OUT/apk/dsh-pet.apk"
else
  echo "没找到 apksigner，已产出未签名包：$PWD/$OUT/apk/aligned.apk"
  echo "自行签名：apksigner sign --ks my.jks --out dsh-pet.apk $OUT/apk/aligned.apk"
fi
