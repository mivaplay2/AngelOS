"""Minimal Android boot image v1 + newc cpio helpers (keeps owners/modes exactly)."""
import gzip, struct

def unpack(data):
    assert data[:8] == b'ANDROID!'
    ks, ka, rs, ra, ss, sa, ta, ps, hv = struct.unpack('<9I', data[8:44])
    assert hv == 1 and ss == 0
    rd_sz, rd_off = struct.unpack('<IQ', data[1632:1644])
    pad = lambda n: (n + ps - 1) // ps * ps
    k0 = ps; r0 = k0 + pad(ks); d0 = r0 + pad(rs)
    return dict(header=data[:ps], page=ps, kernel=data[k0:k0 + ks], ramdisk=data[r0:r0 + rs],
                recovery_dtbo=data[d0:d0 + rd_sz])

def pack(img, ramdisk):
    ps = img['page']; h = bytearray(img['header'])
    struct.pack_into('<I', h, 16, len(ramdisk))
    struct.pack_into('<I', h, 1632, len(img['recovery_dtbo']))
    # recovery_dtbo offset = page-aligned position in the image
    pad = lambda b: b + b'\0' * ((-len(b)) % ps)
    body = pad(img['kernel']) + pad(ramdisk)
    struct.pack_into('<Q', h, 1636, ps + len(body))
    struct.pack_into('<20s', h, 576, b'\0' * 20)   # id/sha1 field is not checked by MTK LK
    return bytes(h) + body + pad(img['recovery_dtbo'])

def cpio_read(raw):
    out = []; o = 0
    while True:
        h = raw[o:o + 110]; assert h[:6] == b'070701', h[:6]
        f = [int(h[6 + 8 * i:14 + 8 * i], 16) for i in range(13)]
        ino, mode, uid, gid, nlink, mtime, fsz, dmaj, dmin, rmaj, rmin, nsz, chk = f
        name = raw[o + 110:o + 110 + nsz - 1].decode(); o += 110 + nsz; o = (o + 3) // 4 * 4
        body = raw[o:o + fsz]; o += fsz; o = (o + 3) // 4 * 4
        if name == 'TRAILER!!!': return out
        out.append(dict(name=name, mode=mode, uid=uid, gid=gid, mtime=mtime, rmaj=rmaj, rmin=rmin, data=body))

def cpio_write(entries):
    b = bytearray()
    def rec(i, e):
        name = e['name'].encode() + b'\0'
        fields = [i, e['mode'], e['uid'], e['gid'], 1, e['mtime'], len(e['data']), 0, 0, e['rmaj'], e['rmin'], len(name), 0]
        b.extend(b'070701' + b''.join(b'%08X' % x for x in fields) + name)
        b.extend(b'\0' * ((-len(b)) % 4)); b.extend(e['data']); b.extend(b'\0' * ((-len(b)) % 4))
    for i, e in enumerate(entries, 1): rec(i, e)
    rec(0, dict(name='TRAILER!!!', mode=0, uid=0, gid=0, mtime=0, rmaj=0, rmin=0, data=b''))
    return bytes(b)

def ramdisk_entries(img): return cpio_read(gzip.decompress(img['ramdisk']))
def ramdisk_bytes(entries): return gzip.compress(cpio_write(entries), 9, mtime=0)
