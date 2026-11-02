#!/usr/bin/env python3
"""校验 PKCS12 keystore 口令 —— 从文件尾反向定位 MAC 结构"""
import hashlib, sys

sys.set_int_max_str_digits(100000)
KS = "/root/libs/rcb.keystore"
d = open(KS, "rb").read()


def parse_tlv(buf, i):
    t = buf[i]; i += 1
    l = buf[i]; i += 1
    if l & 0x80:
        n = l & 0x7F
        l = int.from_bytes(buf[i:i + n], "big"); i += n
    return t, l, i, i + l


def walk(buf, i, end, depth=0, out=None):
    if out is None:
        out = []
    while i < end:
        t = buf[i]
        hdr = i
        try:
            t, l, cstart, cend = parse_tlv(buf, i)
        except Exception:
            return out
        if cend > end:
            return out
        out.append((depth, hdr, t, l, cstart, cend))
        if t in (0x30, 0xA0, 0xA1, 0x31):
            walk(buf, cstart, cend, depth + 1, out)
        i = cend
    return out


t, l, i, top_end = parse_tlv(d, 0)
items = walk(d, i, top_end)
print("total tlvs:", len(items))

mac = salt = iters = None
for idx, (depth, hdr, t, l, cs, ce) in enumerate(items):
    if t == 0x04 and (ce - cs) == 32:
        got_salt = None
        for j in range(idx + 1, min(idx + 4, len(items))):
            d2, h2, t2, l2, cs2, ce2 = items[j]
            if t2 == 0x04 and got_salt is None:
                got_salt = d[cs2:ce2]
            elif t2 == 0x02 and got_salt is not None:
                mac = d[cs:ce]
                salt = got_salt
                iters = int.from_bytes(d[cs2:ce2], "big")
                break
        if mac:
            break

print("mac   =", mac.hex() if mac else None)
print("salt  =", salt.hex() if salt else None, "len", len(salt) if salt else 0)
print("iters =", iters)


def check(pw):
    u = pw.encode("utf-16-be") + b"\x00\x00" + salt
    k = hashlib.sha256(u).digest()
    for _ in range(iters - 1):
        k = hashlib.sha256(k).digest()
    return k == mac


cands = ["123456", "android", "rcb", "12345678", "password", "000000", "rcb123",
         "111111", "abc123", "qwerty", "rcbbpass", "rcbypass", "bypass", "888888",
         "admin", "rcb123456", "RCB123456", "1234", "1234567890", "a123456",
         "rcb2024", "rcb2025", "112233", "5201314", "123123", "654321", "666666",
         "999999", "test", "test123", "rcb1234", "rcb@123", "RCB@123", "Rcb123456",
         "rcb_pass", "rcbpass", "changeit", "changeme", "secret", "mykey",
         "keystore", "lsposed", "xposed", "ReverseCharging", "reverse", "charging",
         "oplus", "coloros", "oneplus", "operit", "Operit", "123456789", "Aa123456",
         "a12345", "admin123", "root", "toor", "iloveyou", "1", "12", "123", "",
         "rcb2023", "rcb2026", "rcb.bypass", "com.rcb.bypass", "rcb_bypass",
         "RCBpass", "rcbPass", "pass", "passw0rd", "P@ssw0rd", "qwer1234",
         "1qaz2wsx", "abc123456", "aaaaaa", "0000", "1111", "2024", "2025",
         "rcb520", "520520", "battery", "wireless", "threshold", "rcbkey",
         "rcbKey", "RCBkey", "key", "rcb.keystore", "rcbkeystore"]

if mac is None:
    print("MAC 定位失败")
elif len(sys.argv) > 1:
    for pw in sys.argv[1:]:
        print(("OK   " if check(pw) else "fail ") + repr(pw))
else:
    found = False
    for pw in cands:
        if check(pw):
            print("FOUND PASSWORD:", repr(pw))
            found = True
            break
    if not found:
        print("not in candidate list")