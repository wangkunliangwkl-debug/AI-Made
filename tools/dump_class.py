#!/usr/bin/env python3
# 反汇编指定类的全部方法
import struct, sys

dex = sys.argv[1]
target = sys.argv[2]
data = open(dex, 'rb').read()

s_size, s_off = struct.unpack_from('<II', data, 0x38)
t_size, t_off = struct.unpack_from('<II', data, 0x40)
f_size, f_off = struct.unpack_from('<II', data, 0x50)
m_size, m_off = struct.unpack_from('<II', data, 0x58)
c_size, c_off = struct.unpack_from('<II', data, 0x60)


def uleb(b, o):
    r = 0
    s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7f) << s
        if not x & 0x80:
            break
        s += 7
    return r, o


def rs(i):
    o = struct.unpack_from('<I', data, s_off + i * 4)[0]
    n, p = uleb(data, o)
    e = data.index(b'\x00', p)
    return data[p:e].decode('utf-8', 'replace')


def tn(i):
    return rs(struct.unpack_from('<I', data, t_off + i * 4)[0])


# 指令长度表
L = {}
for op in [0x00,0x01,0x04,0x07,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f,0x10,0x11,0x12,
           0x1d,0x1e,0x1f,0x20,0x21,0x27,0x28,0xfe,0xff]:
    L[op] = 1
for op in [0x02,0x05,0x08,0x13,0x15,0x16,0x19,0x1a,0x1c,0x22,0x23,0x29,
           0x32,0x33,0x34,0x35,0x36,0x37,0x38,0x39,0x3a,0x3b,0x3c,0x3d,
           0x52,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0x5b,0x5c,0x5d,
           0x5e,0x5f,0x60,0x61,0x62,0x63,0x64,0x65,0x66,0x67,0x68,0x69,
           0x6a,0x6b,0x6c,0x6d]:
    L[op] = 2
for op in [0x03,0x06,0x09,0x14,0x17,0x24,0x25,0x26,0x2a,0x2b,0x2c,
           0x6e,0x6f,0x70,0x71,0x72,0x74,0x75,0x76,0x77,0x78]:
    L[op] = 3
L[0x18] = 5
L[0x1b] = 3


for c in range(c_size):
    co = c_off + c * 32
    cn = tn(struct.unpack_from('<I', data, co)[0])
    if cn != target:
        continue
    cdo = struct.unpack_from('<I', data, co + 24)[0]
    if cdo == 0:
        print('no class data')
        continue
    p = cdo
    sf, p = uleb(data, p)
    inf, p = uleb(data, p)
    dm, p = uleb(data, p)
    vm, p = uleb(data, p)

    print('=== FIELDS ===')
    fi = 0
    for _ in range(sf + inf):
        d, p = uleb(data, p)
        a, p = uleb(data, p)
        fi += d
        fo = f_off + fi * 8
        ci2, ti2, ni2 = struct.unpack_from('<HHI', data, fo)
        print('  %s %s : %s' % (hex(a), rs(ni2), tn(ti2)))

    print('=== METHODS ===')
    mi = 0
    for _ in range(dm + vm):
        d, p = uleb(data, p)
        a, p = uleb(data, p)
        code, p = uleb(data, p)
        mi += d
        mo = m_off + mi * 8
        ci2, pi2, ni2 = struct.unpack_from('<HHI', data, mo)
        nm = rs(ni2)
        if code == 0:
            print('  [abstract] %s' % nm)
            continue
        isz = struct.unpack_from('<I', data, code + 12)[0]
        io = code + 16
        print('--- %s (units=%d) ---' % (nm, isz))
        pc = 0
        while pc < isz:
            o = io + pc * 2
            u = struct.unpack_from('<H', data, o)[0]
            op = u & 0xff
            aa = (u >> 8) & 0xff
            if op == 0x1a:
                si = struct.unpack_from('<H', data, o + 2)[0]
                print('  %04x const-string v%d, %r' % (pc, aa, rs(si)))
                pc += 2
            elif op == 0x1b:
                si = struct.unpack_from('<I', data, o + 2)[0]
                print('  %04x const-str-jumbo v%d, %r' % (pc, aa, rs(si)))
                pc += 3
            elif op == 0x12:
                v = struct.unpack_from('<b', data, o + 1)[0]
                print('  %04x const/4 v%d, %d' % (pc, aa, v))
                pc += 1
            elif op == 0x13:
                v = struct.unpack_from('<h', data, o + 2)[0]
                print('  %04x const/16 v%d, %d' % (pc, aa, v))
                pc += 2
            elif op == 0x14:
                v = struct.unpack_from('<i', data, o + 2)[0]
                print('  %04x const v%d, %d' % (pc, aa, v))
                pc += 3
            elif op in (0x6e,0x6f,0x70,0x71,0x72,0x74,0x75,0x76,0x77,0x78):
                mi2 = struct.unpack_from('<H', data, o + 2)[0]
                mo2 = m_off + mi2 * 8
                ci3, pi3, ni3 = struct.unpack_from('<HHI', data, mo2)
                print('  %04x invoke %s->%s' % (pc, tn(ci3), rs(ni3)))
                pc += 3
            elif 0x52 <= op <= 0x6d:
                fi2 = struct.unpack_from('<H', data, o + 2)[0]
                fo = f_off + fi2 * 8
                ci3, ti3, ni3 = struct.unpack_from('<HHI', data, fo)
                print('  %04x fld %s->%s' % (pc, tn(ci3), rs(ni3)))
                pc += 2
            elif op == 0x0e:
                print('  %04x return-void' % pc)
                pc += 1
            elif op in (0x0f, 0x10, 0x11):
                print('  %04x return v%d' % (pc, aa))
                pc += 1
            elif 0x32 <= op <= 0x3d:
                v = struct.unpack_from('<h', data, o + 2)[0]
                print('  %04x if_%02x -> %+d' % (pc, op, v))
                pc += 2
            elif 0x28 <= op <= 0x2a:
                v = struct.unpack_from('<h', data, o + 2)[0]
                print('  %04x goto -> %+d' % (pc, v))
                pc += 2
            else:
                ln = L.get(op, 1)
                print('  %04x op_%02x' % (pc, op))
                pc += ln