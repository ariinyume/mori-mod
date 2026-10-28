#!/usr/bin/env bash
# MoriMod 出包 → 三层硬验证 流水线（离线、可复跑）
#
#   0) clean 构建
#   1) 归档产物 + 记录 sha
#   2) 从**交付包**重新解出 dex / 重新 dexdump（防残留 → 假 PASS）
#   3) 三层验证：verify_apk.py / verify_dex.py / verify_res.py
#   4) 确定性核对：再 clean 构建一次，两次 sha 必须一致
#
# 用法: bash tools/run_verify.sh
# 退出码 0 = 全绿（含确定性）
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"

# ── 本机工具链（不进仓库）：local.properties 优先，其次环境变量 ────────────
#    local.properties 里可写：MORIMOD_TOOLCHAIN / MORIMOD_JDK / MORIMOD_ANDROID_SDK
#    模板见 gradle.local.properties.example
cfg() {
  [ -f "$REPO/local.properties" ] || return 1
  v=$(grep -m1 "^$1=" "$REPO/local.properties" 2>/dev/null | cut -d= -f2-)
  [ -n "${v:-}" ] && { printf '%s' "$v"; return 0; }
  return 1
}
TC="$(cfg MORIMOD_TOOLCHAIN || printf '')"
JDK="$(cfg MORIMOD_JDK || printf '%s' "${JAVA_HOME:-}")"
[ -n "$JDK" ] || { [ -n "$TC" ] && JDK="$(ls -d "$TC"/jdk* 2>/dev/null | head -1)"; }
SDK="$(cfg MORIMOD_ANDROID_SDK || printf '%s' "${ANDROID_HOME:-}")"
[ -n "$SDK" ] || { [ -n "$TC" ] && SDK="$TC/android-sdk"; }
[ -n "${JDK:-}" ] && export JAVA_HOME="$JDK"
[ -n "${SDK:-}" ] && { export ANDROID_HOME="$SDK"; export ANDROID_SDK_ROOT="$SDK"; }

PY="${PYTHON:-python}"
# ⚠️ 版本号来自 app/build.gradle，不要手改这一行（改了就会验错包 / 验到旧包）
APKNAME="DLsiteMoriMod-$(grep -oP "(?<=appVersionName = ')[^']+" app/build.gradle | head -1)-code$(grep -oP '(?<=appVersionCode = )\d+' app/build.gradle | head -1)-release.apk"
VARIANT="release"
SHOTS="$REPO/shots"
RC=0

cd "$REPO" || exit 2
mkdir -p dist "$SHOTS" dexm decm

step() { echo; echo "########## $* ##########"; }
build() {
  ( cd "$REPO" && ./gradlew clean assemble${VARIANT^} --console=plain 2>&1 | tail -4 )
}

step "0. clean 构建（第 1 次）"
build | tee "$SHOTS/build_a.txt" || RC=1

step "1. 归档产物 + sha"
cp "app/build/outputs/apk/$VARIANT/$APKNAME" "dist/$APKNAME"
sha256sum "dist/$APKNAME" | tee dist/BUILD_A.sha256
stat -c "bytes=%s" "dist/$APKNAME"

step "2. 重新解 dex + dexdump（从交付包，防残留）"
rm -rf dexm decm; mkdir -p dexm decm
"$PY" -c "
import zipfile,pathlib,sys
z=zipfile.ZipFile('dist/$APKNAME')
n=0
for x in z.namelist():
    if x.endswith('.dex'):
        pathlib.Path('dexm',x).write_bytes(z.read(x)); n+=1
print('dex count',n)
"
# ⚠️ build-tools 版本不能写死：项目已升到 36.0.0，写死 34.找不到时会静默跳过 dexdump
#    ⇒ decm/ 为空 ⇒ 第二层「看起来全绿其实没跑」。按版本号取最高。
DD=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)dexdump.exe
echo "  dexdump: $DD"
for f in dexm/*.dex; do
  b=$(basename "$f" .dex)
  "$DD" -d "$f" > "decm/$b.txt" 2>/dev/null
  echo "  dexdump $b -> $(stat -c %s "decm/$b.txt") bytes"
done

step "3.1 第一层：APK 结构 / 版本号 / 常量池"
"$PY" -u tools/verify_apk.py "dist/$APKNAME" | tee "$SHOTS/verify_apk.txt" | tail -4
grep -q "FAIL=0" "$SHOTS/verify_apk.txt" || RC=1

step "3.2 第二层：dex 签名 / 调用链 / 常量折叠"
"$PY" -u tools/verify_dex.py | tee "$SHOTS/verify_dex.txt" | tail -4
grep -q "DEX-FAIL=0" "$SHOTS/verify_dex.txt" || RC=1

step "3.3 第三层：资源层（主题 / mipmap / adaptive-icon）"
"$PY" -u tools/verify_res.py "dist/$APKNAME" | tee "$SHOTS/verify_res.txt" | tail -4
grep -q "RES-FAIL=0" "$SHOTS/verify_res.txt" || RC=1

step "4. 确定性：clean 构建（第 2 次）+ 内容级比对"
build | tee "$SHOTS/build_b.txt" || RC=1
sha256sum "app/build/outputs/apk/$VARIANT/$APKNAME" | tee dist/BUILD_B.sha256
# ⚠️ 不能直接比 APK 整体 sha：release 走 apksigner 的 v2/v3 签名，
#    签名块含密码学随机量（PSS salt / ECDSA k）⇒ 同源码两次构建字节必不同。
#    实测两包长度与全部 49 个条目完全一致，唯一差异就是中央目录前的签名块。
#    故确定性判据 =「除签名块外逐字节一致」（条目内容 + 元数据 + 中央目录）。
if [ "$VARIANT" = "release" ]; then
  "$PY" -u tools/verify_determinism.py "dist/$APKNAME" \
      "app/build/outputs/apk/$VARIANT/$APKNAME" | tee "$SHOTS/verify_determinism.txt"
  grep -q "DET-FAIL=0" "$SHOTS/verify_determinism.txt" || RC=1
  if grep -q "DET-FAIL=0" "$SHOTS/verify_determinism.txt"; then
    echo "DETERMINISM MATCH (content-level,签名块除外)"
  else
    echo "DETERMINISM MISMATCH"; RC=1
  fi
else
  A=$(cut -d' ' -f1 dist/BUILD_A.sha256)
  B=$(cut -d' ' -f1 dist/BUILD_B.sha256)
  if [ "$A" = "$B" ]; then
    echo "DETERMINISM MATCH  $A"
  else
    echo "DETERMINISM MISMATCH  A=$A  B=$B"; RC=1
  fi
fi

step "汇总"
echo "APK   : dist/$APKNAME"
echo "sha256: $(cut -d' ' -f1 dist/BUILD_A.sha256)"
echo "字节数: $(stat -c %s "dist/$APKNAME")"
echo "日志  : shots/*.txt"
[ $RC -eq 0 ] && echo "===> ALL GREEN" || echo "===> 有失败项，见 shots/"
exit $RC
