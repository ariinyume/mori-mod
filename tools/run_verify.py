# -*- coding: utf-8 -*-
"""tools/run_verify.sh 的跨平台替代：出包 + 三层硬验证 + 确定性核对。

用法：python tools/run_verify.py [--skip-build]
"""
import glob
import hashlib
import io
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VARIANT = 'release'
SHOTS = os.path.join(ROOT, 'shots')


def _props():
    """读仓库根的 local.properties（不进仓库，见 .gitignore / example 模板）。"""
    p = os.path.join(ROOT, 'local.properties')
    d = {}
    if os.path.exists(p):
        for line in io.open(p, encoding='utf-8', errors='replace'):
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            k, v = line.split('=', 1)
            d[k.strip()] = v.strip()
    return d


_LP = _props()


def _opt(key, *envs):
    """local.properties（本机显式配置）→ 环境变量 → 空串。"""
    if _LP.get(key):
        return _LP[key]
    for e in envs:
        if os.environ.get(e):
            return os.environ[e]
    return ''


# 工具链：优先 local.properties，其次环境变量。整条链路都允许为空（用系统 PATH 里的 java）。
TC = _opt('MORIMOD_TOOLCHAIN')
JDK = _opt('MORIMOD_JDK', 'JAVA_HOME')
SDK = _opt('MORIMOD_ANDROID_SDK', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')
if not JDK and TC:
    _jdks = sorted(glob.glob(os.path.join(TC, 'jdk*')))
    JDK = _jdks[0] if _jdks else ''
if not SDK and TC:
    SDK = os.path.join(TC, 'android-sdk')

_GRADLE = io.open(os.path.join(ROOT, 'app', 'build.gradle'), encoding='utf-8',
                  errors='replace').read()
_TAG = re.search(r"appVersionName\s*=\s*'([^']+)'", _GRADLE)
_CODE = re.search(r"appVersionCode\s*=\s*(\d+)", _GRADLE)
APK = 'DLsiteMoriMod-%s-code%s-%s.apk' % (
    _TAG.group(1), _CODE.group(1), VARIANT)


def env():
    e = dict(os.environ)
    if JDK:
        e['JAVA_HOME'] = JDK
        e['PATH'] = os.path.join(JDK, 'bin') + os.pathsep + e.get('PATH', '')
    if SDK:
        e['ANDROID_HOME'] = SDK
        e['ANDROID_SDK_ROOT'] = SDK
    return e


def gradle(*args):
    cmd = [os.path.join(ROOT, 'gradlew.bat')] + list(args)
    return subprocess.run(cmd, cwd=ROOT, env=env(), capture_output=True,
                          text=True, errors='replace')


def build(tag):
    r = gradle('clean', 'assembleRelease', '--console=plain')
    out = (r.stdout or '') + (r.stderr or '')
    io.open(os.path.join(SHOTS, 'build_%s.txt' % tag), 'w', encoding='utf-8').write(out)
    return r.returncode == 0, out[-1500:]


def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for blk in iter(lambda: f.read(1 << 20), b''):
            h.update(blk)
    return h.hexdigest()


def dexdump():
    decm = os.path.join(ROOT, 'decm')
    dexm = os.path.join(ROOT, 'dexm')
    os.makedirs(decm, exist_ok=True)
    os.makedirs(dexm, exist_ok=True)
    z = zipfile.ZipFile(os.path.join(ROOT, 'dist', APK))
    n = 0
    for x in z.namelist():
        if x.endswith('.dex'):
            open(os.path.join(dexm, x), 'wb').write(z.read(x))
            n += 1
    print('  dex count', n)
    btroot = os.path.join(SDK, 'build-tools')
    if not SDK or not os.path.isdir(btroot):
        print('  [fatal] 找不到 build-tools：请设 ANDROID_HOME 或在 local.properties 里填 '
              'MORIMOD_ANDROID_SDK')
        sys.exit(2)
    bt = sorted(os.listdir(btroot))[-1]
    dd = os.path.join(btroot, bt, 'dexdump.exe')
    print('  dexdump:', dd)
    for f in sorted(os.listdir(dexm)):
        if not f.endswith('.dex'):
            continue
        with open(os.path.join(decm, f[:-4] + '.txt'), 'wb') as o:
            subprocess.run([dd, '-d', os.path.join(dexm, f)], stdout=o,
                           stderr=subprocess.DEVNULL)
        print('  %s -> %d bytes' % (f, os.path.getsize(os.path.join(decm, f[:-4] + '.txt'))))


def run(name, fn):
    print()
    print('#' * 12, name, '#' * 12)
    return fn()


def main():
    os.makedirs(SHOTS, exist_ok=True)
    rc = 0
    skip = '--skip-build' in sys.argv

    def step(t):
        print()
        print('#' * 12, t, '#' * 12)

    step('0. clean 构建（第 1 次）')
    if skip:
        print('  (跳过)')
    else:
        ok, tail = build('a')
        print(tail)
        if not ok:
            print('BUILD FAILED'); return 2

    step('1. 归档产物 + sha')
    src = os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', VARIANT, APK)
    dst = os.path.join(ROOT, 'dist', APK)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, 'wb').write(open(src, 'rb').read())
    a_sha = sha256(dst)
    print('  sha256', a_sha)
    print('  bytes ', os.path.getsize(dst))

    step('2. 重新解 dex + dexdump（从交付包）')
    dexdump()

    step('3. 三层验证')
    fails = []
    for script, marker in (('verify_apk.py', 'FAIL='), ('verify_dex.py', 'DEX-FAIL='),
                           ('verify_res.py', 'RES-FAIL=')):
        args = [sys.executable, '-u', os.path.join(ROOT, 'tools', script)]
        if script != 'verify_dex.py':
            args.append(dst)
        r = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, errors='replace')
        out = (r.stdout or '') + (r.stderr or '')
        io.open(os.path.join(SHOTS, script.replace('.py', '.txt')), 'w',
                encoding='utf-8').write(out)
        bad = [l for l in out.splitlines() if ' FAIL ' in l]
        print('  %-16s %s   fail-lines=%d' % (script, marker, len(bad)))
        for l in bad[:20]:
            print('     ', l[:160])
        if bad:
            fails.append(script)
            rc = 1

    if skip:
        print()
        print('===> 跳过确定性（--skip-build）')
        return rc

    step('4. 确定性：clean 构建（第 2 次）')
    ok, tail = build('b')
    if not ok:
        print(tail)
        return 2
    r = subprocess.run([sys.executable, '-u', os.path.join(ROOT, 'tools', 'verify_determinism.py'),
                        dst, src], cwd=ROOT, capture_output=True, text=True, errors='replace')
    out = (r.stdout or '') + (r.stderr or '')
    io.open(os.path.join(SHOTS, 'verify_determinism.txt'), 'w', encoding='utf-8').write(out)
    print(out[-800:])
    if 'DET-FAIL=0' not in out:
        rc = 1

    step('汇总')
    print('APK   :', dst)
    print('sha256:', a_sha)
    print('FAILS :', fails or 'none')
    print('===> ALL GREEN' if rc == 0 else '\n===> 有失败，见 shots/')
    return rc


if __name__ == '__main__':
    sys.exit(main())
