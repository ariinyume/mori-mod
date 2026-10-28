#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MoriMod 出包核验 · 确定性核对（内容级，忽略 APK 签名块）。

为什么不能直接比 APK 文件的 sha256：
  release 包走 apksigner 的 **v2/v3 签名**，签名块里含密码学随机量
  （PSS 随机 salt / ECDSA 随机 k），因此**同一份源码两次构建出的
  APK 字节必然不同** —— 这是 Android 构建的常态，不是缺陷。
  实测：两个 APK 长度完全相同（7,204,898），全部 49 个 zip 条目的
  **内容与元数据逐一相同**，唯一差异是中央目录之前的
  APK Signing Block（4959 字节）。

所以正确的确定性判据是「**除签名块外逐字节一致**」：
  1) 条目名集合相同；
  2) 每个条目的解压内容 sha256 相同；
  3) 每个条目的元数据（时间戳/压缩方式/权限）相同；
  4) 中央目录（条目顺序/偏移/大小）相同。

用法: python tools/verify_determinism.py <apkA> <apkB>
退出码 0 = 内容级确定性成立。
"""
import hashlib
import struct
import sys
import zipfile


def u16(b, o):
    return struct.unpack("<H", b[o:o + 2])[0]


def cd_bytes(path):
    """取中央目录原始字节（不含前置的 APK Signing Block）。"""
    d = open(path, "rb").read()
    i = d.rfind(b"PK\x05\x06")
    if i < 0:
        raise SystemExit("[FATAL] 不是合法 zip: " + path)
    cd_size = struct.unpack("<I", d[i + 12:i + 16])[0]
    cd_off = struct.unpack("<I", d[i + 16:i + 20])[0]
    return d[cd_off:cd_off + cd_size]


def report(label, cond, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + label + ((" -> " + detail) if detail else ""))
    return cond


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    a, b = sys.argv[1], sys.argv[2]
    za, zb = zipfile.ZipFile(a), zipfile.ZipFile(b)
    ia = {i.filename: i for i in za.infolist()}
    ib = {i.filename: i for i in zb.infolist()}

    ok = True
    print("A =", a.rsplit("\\", 1)[-1])
    print("B =", b.rsplit("\\", 1)[-1])
    print("-" * 62)

    only_a = sorted(set(ia) - set(ib))
    only_b = sorted(set(ib) - set(ia))
    ok &= report("条目集合一致（%d 项）" % len(ia),
                 not only_a and not only_b,
                 "onlyA=%s onlyB=%s" % (only_a, only_b))

    diff_content, diff_meta = [], []
    for n in sorted(set(ia) & set(ib)):
        ha = hashlib.sha256(za.read(n)).hexdigest()
        hb = hashlib.sha256(zb.read(n)).hexdigest()
        if ha != hb:
            diff_content.append(n)
        ma, mb = ia[n], ib[n]
        if (ma.date_time, ma.compress_type, ma.external_attr, ma.internal_attr, ma.create_system) != \
           (mb.date_time, mb.compress_type, mb.external_attr, mb.internal_attr, mb.create_system):
            diff_meta.append(n)
    ok &= report("每个条目内容 sha256 一致", not diff_content, str(diff_content[:6]))
    ok &= report("每个条目元数据（时间戳/压缩/权限）一致", not diff_meta, str(diff_meta[:6]))

    ca, cb = cd_bytes(a), cd_bytes(b)
    ok &= report("中央目录字节一致（条目顺序/偏移/大小）", ca == cb,
                 "%d vs %d bytes" % (len(ca), len(cb)))

    print("-" * 62)
    print("  NOTE  两包整体 sha256 不同属预期：release 的 v2/v3 签名块含随机量。")
    print("        内容级一致 + apksigner verify 通过 ⇒ 确定性成立。")
    print("=" * 62)
    print("DET-PASS=%d DET-FAIL=%d" % (4 if ok else 0, 0 if ok else 1))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
