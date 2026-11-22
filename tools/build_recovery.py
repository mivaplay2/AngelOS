"""Build the flash-mode recovery: stock CPH1923 recovery with root adb enabled."""
import hashlib, struct, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from bootimg import unpack, pack, ramdisk_entries, ramdisk_bytes

ROOT = Path(__file__).resolve().parents[1]
src = ROOT / 'work/device/recovery-stock.bin'
out = ROOT / 'work/recovery/recovery-flashmode.img'
out.parent.mkdir(exist_ok=True)

img = unpack(src.read_bytes())
entries = ramdisk_entries(img)
# adb in the flash-mode recovery accepts ONLY the builder's computer: its adb public key is baked in
# (ANGEL_ADB_KEY, default ~/.android/adbkey.pub); every other host is refused.
import os
ADB_KEY = Path(os.environ.get('ANGEL_ADB_KEY', str(Path.home() / '.android/adbkey.pub'))).read_bytes()
props = {'ro.adb.secure': '1', 'ro.secure': '0', 'ro.debuggable': '1', 'persist.sys.usb.config': 'adb'}
for e in entries:
    if e['name'] == 'prop.default':
        lines = e['data'].decode().splitlines(); seen = set()
        for i, l in enumerate(lines):
            k = l.split('=', 1)[0]
            if k in props: lines[i] = f'{k}={props[k]}'; seen.add(k)
        lines += [f'{k}={v}' for k, v in props.items() if k not in seen]
        e['data'] = ('\n'.join(lines) + '\n').encode()
# --- custom UI: our idle screen instead of the Oppo recovery UI ---
# The Oppo UI must start first: it programs the display (736-px pitch, RGBA). Then we freeze it.
RECUI_SH = """#!/sbin/sh
# flash-mode idle screen
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do pidof recovery >/dev/null && break; sleep 0.5; done
sleep 3
kill -STOP $(pidof recovery) 2>/dev/null
# the frozen Oppo UI never clears its boot-recovery command; clear it so 'adb reboot' boots Android
dd if=/dev/zero of=/dev/block/by-name/para bs=2048 count=1 conv=notrunc 2>/dev/null; sync
echo 1500 > /sys/class/leds/lcd-backlight/brightness
zcat /res/recui/idle.fb.gz > /dev/graphics/fb0
# an over-the-air update downloaded by Angel Update is waiting in /cache
/sbin/angel-ota.sh
"""
ANGEL_OTA_SH = r"""#!/sbin/sh
# Angel OS OTA: verify the running system, apply changed 1 MiB chunks from /cache, verify, boot.
grep -q " /cache " /proc/mounts || mount -t ext4 /dev/block/by-name/cache /cache
D=/cache/recovery/angel-ota
[ -f $D/apply ] || exit 0
SYS=/dev/block/by-name/system
F=/tmp/angel-frames; mkdir -p $F; tar -xf $D/frames.tar -C $F 2>/dev/null
show() { [ -f $F/$1.gz ] && zcat $F/$1.gz > /dev/graphics/fb0; }
hash() { dd if=$SYS bs=1048576 skip=$1 count=$2 2>/dev/null | sha256sum | cut -d' ' -f1; }
boot() { sync; sleep 2; reboot || echo b > /proc/sysrq-trigger; }
echo 1500 > /sys/class/leds/lcd-backlight/brightness
show verify
total=0
while read mib cnt new old; do
  total=$((total + cnt)); cur=$(hash $mib $cnt)
  if [ "$cur" != "$old" ] && [ "$cur" != "$new" ]; then show error; rm -rf $D; sleep 6; boot; exit 1; fi
done < $D/manifest.txt
off=0; done_=0; last=-1
while read mib cnt new old; do
  dd if=$D/payload.bin of=$SYS bs=1048576 skip=$off seek=$mib count=$cnt conv=notrunc,fsync 2>/dev/null; sync
  if [ "$(hash $mib $cnt)" != "$new" ]; then show error; exit 1; fi   # keep the package; stay in recovery for repair
  off=$((off + cnt)); done_=$((done_ + cnt)); p=$((done_ * 100 / total / 20 * 20))
  [ "$p" != "$last" ] && show p$p && last=$p
done < $D/manifest.txt
show done; rm -rf $D; boot
"""
import time as _t
names = {e['name']: e for e in entries}
idle = (ROOT / 'work/recui/idle.fb.gz').read_bytes()
tmpl = names['etc/recovery.fstab']
if 'res/recui' not in names:
    entries.append(dict(tmpl, name='res/recui', mode=0o40755, data=b''))
    entries.append(dict(tmpl, name='res/recui/idle.fb.gz', mode=0o100644, data=idle))
    entries.append(dict(tmpl, name='sbin/recui.sh', mode=0o100750, data=RECUI_SH.encode()))
else:
    names['res/recui/idle.fb.gz']['data'] = idle
names = {e['name']: e for e in entries}
names['sbin/recui.sh']['data'] = RECUI_SH.encode()
if 'adb_keys' not in names:
    entries.append(dict(names['etc/recovery.fstab'], name='adb_keys', mode=0o100644, data=b''))
    names = {e['name']: e for e in entries}
names['adb_keys']['data'] = ADB_KEY
if 'sbin/angel-ota.sh' not in names:
    entries.append(dict(names['sbin/recui.sh'], name='sbin/angel-ota.sh', data=b''))
    names = {e['name']: e for e in entries}
names['sbin/angel-ota.sh']['data'] = ANGEL_OTA_SH.encode()
rc = names['init.rc']['data'].decode()
if 'service recui' not in rc:
    assert rc.count('service recovery /sbin/recovery\n') == 1
    rc += '\nservice recui /sbin/recui.sh\n    user root\n    oneshot\n    disabled\n    seclabel u:r:recovery:s0\n\non property:init.svc.adbd=running\n    start recui\n'
    names['init.rc']['data'] = rc.encode()
h = bytearray(img['header'])
cmd = h[64:64 + 512].split(b'\0')[0]
if b'androidboot.selinux=' not in cmd: cmd += b' androidboot.selinux=permissive'
h[64:64 + 512] = cmd.ljust(512, b'\0'); img['header'] = bytes(h)
data = pack(img, ramdisk_bytes(entries))
assert len(data) <= 33554432, 'recovery partition is 32 MiB'
# self-check: unpack again and confirm props + kernel untouched
chk = unpack(data); assert chk['kernel'] == img['kernel'] and chk['recovery_dtbo'] == img['recovery_dtbo']
p = {e['name']: e for e in ramdisk_entries(chk)}['prop.default']['data'].decode()
assert all(f'{k}={v}' in p for k, v in props.items())
out.write_bytes(data)
print(out, len(data), hashlib.sha256(data).hexdigest())
