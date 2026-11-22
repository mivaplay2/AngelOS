"""v4 = v3 + Angel OS branding + persistent zram."""
import subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from image_utils import Ext4, digest

ROOT = Path(__file__).resolve().parents[1]; W = ROOT / 'work'
E2 = Path('/usr/local/opt/e2fsprogs/sbin')
src, img = W / 'cph1923-v3.img', W / 'cph1923-v4.img'
assert not img.exists(), 'v4 exists'
subprocess.run(['cp', '-c', str(src), str(img)], check=True)
b = W / 'cph1923-v4.build'; b.mkdir(exist_ok=True)
fs = Ext4(img, E2, b / 'ext4')

def edit_prop(path, changes):
    local = fs.extract(path, b / (path.strip('/').replace('/', '_')))
    lines = local.read_text().splitlines(); seen = set()
    for i, l in enumerate(lines):
        k = l.split('=', 1)[0]
        if k in changes: lines[i] = f'{k}={changes[k]}'; seen.add(k)
    missing = set(changes) - seen; assert not missing, (path, missing)
    local.write_text('\n'.join(lines) + '\n'); fs.put(local, path, replace=True)

edit_prop('/system/build.prop', {
    'ro.build.display.id': 'Angel OS 1.0 (2026.11.22)',
    'ro.lineage.display.version': 'Angel OS 1.0',
})
edit_prop('/system/product/build.prop', {'ro.product.product.model': 'OPPO A1k'})
edit_prop('/system/system_ext/build.prop', {'ro.product.system_ext.model': 'OPPO A1k'})

# persistent zram through init's own swapon_all (fs_mgr does mkswap + swapon in the init domain)
fstab = b / 'fstab.angel-zram'
fstab.write_text('/dev/block/zram0  none  swap  defaults  zramsize=1073741824,max_comp_streams=8\n')
fs.put(fstab, '/system/etc/fstab.angel-zram')
rc = b / 'angel-os.rc'
rc.write_text('# Angel OS: memory tuning (stock ColorOS enabled zram; this GSI did not)\n'
              'on property:sys.boot_completed=1\n'
              '    write /sys/block/zram0/comp_algorithm lz4\n'
              '    swapon_all /system/etc/fstab.angel-zram\n'
              '    write /proc/sys/vm/swappiness 100\n')
fs.put(rc, '/system/etc/init/angel-os.rc')

d = '/system/product/overlay/AngelVersion'; fs.mkdir(d)
fs.put(W / 'overlays/AngelVersion.apk', d + '/AngelVersion.apk')

for path in ['/system/build.prop', '/system/etc/init/angel-os.rc', d + '/AngelVersion.apk']:
    print(path, fs.metadata(path))
r = subprocess.run([str(E2 / 'e2fsck'), '-fn', str(img)], capture_output=True, text=True)
assert r.returncode == 0, r.stdout[-800:]
print('fs ok', img)
