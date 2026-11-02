#!/usr/bin/env python3
# 定位 dex 中引用指定字符串的方法
import struct, sys, io

TARGET = sys.argv[2] if len(sys.argv) > 2 else "wireless_reverse_battery_level_threshold"
path = sys.argv[1] if len(sys.argv) > 1 else "classes.dex"
data = open(path, "rb").read()

# dex header
string_ids_size, string_ids_off = struct.unpack_from("<II", data, 0x38)
type_ids_size, type_ids_off = struct.unpack_from("<II", data, 0x40)
proto_ids_size, proto_ids_off = struct.unpack_from("<II", data, 0x48)
field_ids_size, field_ids_off = struct.unpack_from("<II", data, 0x50)
method_ids_size, method_ids_off = struct.unpack_from("<II", data, 0x58)
class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)

def uleb128(buf, off):
    result = 0; shift = 0
    while True:
        b = buf[off]; off += 1
        result |= (b & 0x7f) << shift
        if not (b & 0x80): break
        shift += 7
    return result, off

def read_string(idx):
    if idx >= string_ids_size: return None
    off = struct.unpack_from("<I", data, string_ids_off + idx * 4)[0]
    n, p = uleb128(data, off)
    end = data.index(b"\x00", p)
    return data[p:end].decode("utf-8", "replace")

def type_name(tidx):
    if tidx >= type_ids_size: return None
    sidx = struct.unpack_from("<I", data, type_ids_off + tidx * 4)[0]
    return read_string(sidx)

# 1. 找到目标字符串的 index
target_idx = None
for i in range(string_ids_size):
    s = read_string(i)
    if s == TARGET or (s and TARGET in s and len(s) < 80):
        print("STRING[%d] = %r" % (i, s))
        if s == TARGET: target_idx = i
if target_idx is None:
    print("target string not found")
    sys.exit(1)
print("TARGET STRING INDEX =", target_idx)

# 2. 扫描所有 class_defs 的 code, 找 const-string 指令
def iter_string_refs_in_code(code_off, code_units):
    """扫描指令流, 返回 (pc, string_idx) 列表"""
    refs = []
    pc = 0
    while pc < code_units:
        off = code_off + pc * 2
        unit = struct.unpack_from("<H", data, off)[0]
        op = unit & 0xff
        if op == 0x1a:  # const-string vAA, string@BBBB
            sidx = struct.unpack_from("<H", data, off + 2)[0]
            refs.append((pc, sidx))
            pc += 2
        elif op == 0x1b:  # const-string/jumbo vAA, string@BBBBBBBB
            sidx = struct.unpack_from("<I", data, off + 2)[0]
            refs.append((pc, sidx))
            pc += 3
        elif op in (0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09):  # move*
            pc += 1 if op == 0x02 else 2
        elif op == 0x00:
            # nop, 可能是 payload; 简单跳过
            pc += 1
        else:
            # 粗略估算长度（非精确, 但足以找到 const-string）
            fmt = OPCODE_LEN.get(op, 1)
            pc += fmt
    return refs

# dalvik 指令长度表（单位: 16bit code units）
OPCODE_LEN = {}
for op in [0x00,0x01,0x04,0x07,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f,0x10,0x11,0x12,0x13,
           0x1d,0x1e,0x1f,0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27,0x28,0x29,
           0xfe,0xff]: OPCODE_LEN[op] = 1
for op in [0x02,0x05,0x08,0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23,
           0x29,0x2d,0x2e,0x2f,0x30,0x31,0x32,0x33,0x34,0x35,0x36,0x37,0x38,
           0x39,0x3a,0x3b,0x3c,0x3d,0x3e,0x3f,0x40,0x41,0x42,0x43,0x44,
           0x45,0x46,0x47,0x48,0x49,0x4a,0x4b,0x4c,0x4d,0x4e,0x4f,0x50,
           0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0x5b,0x5c,
           0x5d,0x5e,0x5f,0x60,0x61,0x62,0x63,0x64,0x65,0x66,0x67,0x68,
           0x69,0x6a,0x6b,0x6c,0x6d,0x6e,0x6f,0x70,0x71,0x72]: OPCODE_LEN[op] = 1
# 2-unit 指令
for op in [0x03,0x06,0x09,0x14,0x17,0x1b,0x24,0x25,0x26,0x2a,0x2b,0x2c,
           0x74,0x75,0x76,0x77,0x78,0x79,0x7a,0x7b,0x7c,0x7d,0x7e,0x7f,
           0x80,0x81,0x82,0x83,0x84,0x85,0x86,0x87,0x88,0x89,0x8a,0x8b,
           0x8c,0x8d,0x8e,0x8f,0x90,0x91,0x92,0x93,0x94,0x95,0x96,0x97,
           0x98,0x99,0x9a,0x9b,0x9c,0x9d,0x9e,0x9f,0xa0,0xa1,0xa2,0xa3,
           0xa4,0xa5,0xa6,0xa7,0xa8,0xa9,0xaa,0xab,0xac,0xad,0xae,0xaf,
           0xb0,0xb1,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xb9,0xba,0xbb,
           0xbc,0xbd,0xbe,0xbf,0xc0,0xc1,0xc2,0xc3,0xc4,0xc5,0xc6,0xc7,
           0xc8,0xc9,0xca,0xcb,0xcc,0xcd,0xce,0xcf,0xd0,0xd1,0xd2,0xd3,
           0xd4,0xd5,0xd6,0xd7,0xd8,0xd9,0xda,0xdb,0xdc,0xdd,0xde,0xdf,
           0xe0,0xe1,0xe2,0xe3,0xe4,0xe5,0xe6,0xe7,0xe8,0xe9,0xea,0xeb,
           0xec,0xed,0xee,0xef,0xf0,0xf1,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,
           0xf8,0xf9,0xfa,0xfb,0xfc,0xfd]: OPCODE_LEN[op] = 2
for op in [0x18, 0x1b, 0x71]: pass
OPCODE_LEN[0x18] = 5  # const-wide (4 units + opcode = 5)
OPCODE_LEN[0x1b] = 3  # const-string/jumbo = 3 units
OPCODE_LEN[0x24] = 3  # filled-new-array/range
OPCODE_LEN[0x25] = 3
OPCODE_LEN[0x03] = 2
OPCODE_LEN[0x06] = 2
OPCODE_LEN[0x09] = 2

def class_name(cidx):
    cdef_off = class_defs_off + cidx * 32
    tidx = struct.unpack_from("<I", data, cdef_off)[0]
    return type_name(tidx)

def method_name(midx):
    off = method_ids_off + midx * 8
    class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", data, off)
    return type_name(class_idx), read_string(name_idx)

hits = []
for c in range(class_defs_size):
    cdef_off = class_defs_off + c * 32
    class_data_off = struct.unpack_from("<I", data, cdef_off + 24)[0]
    if class_data_off == 0: continue
    p = class_data_off
    sf, p = uleb128(data, p)
    inf, p = uleb128(data, p)
    dm, p = uleb128(data, p)
    vm, p = uleb128(data, p)
    # 跳过字段
    for _ in range(sf + inf):
        _, p = uleb128(data, p)
        _, p = uleb128(data, p)
    # 方法
    midx = 0
    for _ in range(dm + vm):
        diff, p = uleb128(data, p)
        acc, p = uleb128(data, p)
        code_off, p = uleb128(data, p)
        midx += diff
        if code_off == 0: continue
        # code_item
        regs, ins, outs, tries = struct.unpack_from("<HHHH", data, code_off)
        insns_size = struct.unpack_from("<I", data, code_off + 12)[0]
        insns_off = code_off + 16
        pc = 0
        while pc < insns_size:
            off = insns_off + pc * 2
            unit = struct.unpack_from("<H", data, off)[0]
            op = unit & 0xff
            if op == 0x1a:
                sidx = struct.unpack_from("<H", data, off + 2)[0]
                if sidx == target_idx:
                    cn, mn = method_name(midx)
                    hits.append((cn, mn, pc))
                pc += 2
            elif op == 0x1b:
                sidx = struct.unpack_from("<I", data, off + 2)[0]
                if sidx == target_idx:
                    cn, mn = method_name(midx)
                    hits.append((cn, mn, pc))
                pc += 3
            else:
                pc += OPCODE_LEN.get(op, 1)

print("=== HITS(%d) ===" % len(hits))
seen = set()
for cn, mn, pc in hits:
    k = (cn, mn)
    if k in seen: continue
    seen.add(k)
    print("%s -> %s  (pc=%d)" % (cn, mn, pc))
