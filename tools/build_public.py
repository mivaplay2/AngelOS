"""Build a complete Angel OS system image in one pass (public, Google-free base).

usage: build_public.py PUBLISHER.img.xz OUT.img VERSION_CODE "Angel OS 1.0" [SERVER_URL]

1. core: unpack the publisher GSI, grow it to the system partition, apply the tested
   CPH1923 fixes from build_image.py (sigcont SELinux patch checked against the vendor
   policy, rw-system.sh keymaster fix, Wi-Fi/touch overlays, wallpaper helper)
2. Angel layers: boot animation, overlays, system apps + privapp permissions, zram,
   branding and build properties.
"""
import lzma, shutil, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from image_utils import Ext4, digest, expand_sparse
import json

ROOT = Path(__file__).resolve().parents[1]; W = ROOT / 'work'
E2 = Path('/usr/local/opt/e2fsprogs/sbin')
PROFILE = json.loads((ROOT / 'evidence/tested-profile.json').read_text())
VENDOR = W / 'device/vendor-stock.bin'
SECILC = W / 'tools/secilc'
OVERLAYS = ['AngelVersion', 'AngelAccent', 'AngelFramework', 'AngelRecents', 'AngelShape', 'AngelShade', 'NavHandle',
            'AngelBrandLauncher', 'AngelBrandDevice', 'AngelBrandSettings', 'AngelBrandPlatform', 'AngelBrandParts', 'AngelBrandSetup']
SYSAPPS = [('/system/priv-app/AngelStore', 'AngelStore'), ('/system/priv-app/AngelUpdate', 'AngelUpdate'),
           ('/system/app/AngelIsland', 'AngelIsland'), ('/system/app/AngelIcons', 'AngelIcons')]

def run(*a, **k): return subprocess.run([str(x) for x in a], check=True, **k)

def core(publisher, out, b):
    sparse = b / 'publisher.sparse.img'
    with lzma.open(publisher, 'rb') as i, sparse.open('wb') as o: shutil.copyfileobj(i, o, 8 << 20)
    expand_sparse(sparse, out); sparse.unlink()
    with out.open('r+b') as f: f.truncate(PROFILE['partitions']['system']['bytes'])
    run(E2 / 'resize2fs', out)
    r = subprocess.run([str(E2 / 'e2fsck'), '-fy', '-E', 'unshare_blocks', str(out)], capture_output=True, text=True)
    assert r.returncode in (0, 1), 'unshare failed'
    fs = Ext4(out, E2, b / 'ext4'); vendor = Ext4(VENDOR, E2, b / 'vendor')
    plat = fs.extract('/system/etc/selinux/plat_sepolicy.cil', b / 'plat.cil')
    run('patch', '--batch', '--fuzz=0', plat, ROOT / 'patches/selinux-sigcont.patch')
    cils = [plat, fs.extract('/system/etc/selinux/mapping/28.0.cil', b / 'map28.cil'),
            fs.extract('/system/system_ext/etc/selinux/system_ext_sepolicy.cil', b / 'ext.cil'),
            vendor.extract('/etc/selinux/plat_pub_versioned.cil', b / 'vpub.cil'),
            vendor.extract('/etc/selinux/vendor_sepolicy.cil', b / 'vendor.cil')]
    run(SECILC.resolve(), '-m', '-M', 'true', '-G', '-N', '-c', '30', *cils, '-o', b / 'checked.policy', '-f', b / 'checked.contexts')
    fs.put(plat, '/system/etc/selinux/plat_sepolicy.cil', replace=True)
    helper = fs.extract('/system/bin/rw-system.sh', b / 'rw-system.sh')
    run('patch', '--batch', '--fuzz=0', helper, ROOT / 'patches/rw-system.patch'); run('bash', '-n', helper)
    fs.put(helper, '/system/bin/rw-system.sh', replace=True)
    for name in ['OppoWifiCompat', 'OppoTouchDefaults']:
        d = '/system/product/overlay/' + name; fs.mkdir(d)
        fs.put(ROOT / 'assets' / (name + '.apk'), d + '/' + name + '.apk',
               context='u:object_r:vendor_overlay_file:s0' if name == 'OppoTouchDefaults' else 'u:object_r:system_file:s0')
    return fs

def angel(fs, b, code, name, server):
    fs.put(W / 'bootanim/bootanimation.zip', '/system/media/bootanimation.zip', replace=True)
    # Angel wallpapers become the system defaults (seeded on first boot by the repo's helper)
    d = '/system/etc/cph-wallpaper-defaults'; fs.mkdir(d)
    for f, src in [('wallpaper', 'angel-home.png'), ('wallpaper_orig', 'angel-home.png'),
                   ('wallpaper_lock', 'angel-lock.png'), ('wallpaper_lock_orig', 'angel-lock.png')]:
        fs.put(W / 'wallpapers' / src, f'{d}/{f}')
    fs.put(ROOT / 'assets/wallpaper-defaults/wallpaper_info.xml', f'{d}/wallpaper_info.xml')
    fs.put(ROOT / 'assets/cph-wallpaper-defaults.sh', '/system/bin/cph-wallpaper-defaults.sh', mode='0100755', gid=2000, context='u:object_r:phhsu_exec:s0')
    fs.put(ROOT / 'assets/cph-wallpaper-defaults.rc', '/system/etc/init/cph-wallpaper-defaults.rc')
    for n in OVERLAYS:
        dd = f'/system/product/overlay/{n}'; fs.mkdir(dd); fs.put(W / 'overlays' / f'{n}.apk', f'{dd}/{n}.apk')
    for dd, n in SYSAPPS:
        fs.mkdir(dd); fs.put(W / 'apps' / f'{n}.apk', f'{dd}/{n}.apk')
    fs.put(W / 'sysapps/privapp-permissions-angel.xml', '/system/etc/permissions/privapp-permissions-angel.xml')
    fs.mkdir('/system/etc/angel'); fs.put(W / 'sysapps/angel/ota_key.pem', '/system/etc/angel/ota_key.pem')   # verifies signed OTAs
    zf = b / 'fstab.angel-zram'; zf.write_text('/dev/block/zram0  none  swap  defaults  zramsize=1073741824,max_comp_streams=8\n')
    fs.put(zf, '/system/etc/fstab.angel-zram')
    rc = b / 'angel-os.rc'
    rc.write_text('# Angel OS: memory tuning (stock firmware enabled zram; the GSI does not)\n'
                  'on property:sys.boot_completed=1\n    write /sys/block/zram0/comp_algorithm lz4\n'
                  '    swapon_all /system/etc/fstab.angel-zram\n    write /proc/sys/vm/swappiness 100\n')
    fs.put(rc, '/system/etc/init/angel-os.rc')
    def edit(path, changes, add=()):
        p = fs.extract(path, b / path.strip('/').replace('/', '_')); out = []
        for l in p.read_text().splitlines():
            k = l.split('=', 1)[0]
            if k in changes: l = f'{k}={changes[k]}'
            if k in dict(add): continue
            out.append(l)
        out += [f'{k}={v}' for k, v in add]
        p.write_text('\n'.join(out) + '\n'); fs.put(p, path, replace=True)
    edit('/system/build.prop',
         {'ro.build.display.id': f'{name} ({__import__("time").strftime("%Y.%m.%d")})', 'ro.lineage.display.version': name,
          'ro.product.system.brand': 'Angel', 'ro.product.system.model': 'Angel OS', 'ro.product.system.name': 'angel'},
         add=[('ro.angel.version_code', str(code)), ('ro.angel.server', server)])
    edit('/system/product/build.prop', {'ro.product.product.model': 'OPPO A1k', 'ro.product.product.brand': 'Angel'})
    edit('/system/system_ext/build.prop', {'ro.product.system_ext.model': 'OPPO A1k', 'ro.product.system_ext.brand': 'Angel'})

def main(publisher, out, code, name, server):   # your own Angel server URL, e.g. https://your-server:8787
    publisher, out = Path(publisher), Path(out); assert not out.exists(), 'output exists'
    b = out.with_suffix('.build'); shutil.rmtree(b, ignore_errors=True); b.mkdir()
    fs = core(publisher, out, b)
    angel(fs, b, int(code), name, server)
    r = subprocess.run([str(E2 / 'e2fsck'), '-fn', str(out)], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout[-800:]
    print('built', out, digest(out))

if __name__ == '__main__':
    main(*sys.argv[1:6])
