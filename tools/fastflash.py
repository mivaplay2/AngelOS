"""Fast system flashing through the flash-mode recovery (no BROM).

Writes only the 1 MiB chunks that differ between the image currently on the phone
(--base, default from work/state.json) and the new image, verifying before and after.

usage: fastflash.py NEW_IMAGE [--base CURRENT_IMAGE] [--full-verify]
The phone must be in our recovery:  adb reboot recovery
"""
import argparse, hashlib, json, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from recui import Screen

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path.home() / 'Desktop/platform-tools/adb')
DEV = '/dev/block/mmcblk0p38'          # system, from the verified GPT profile
SIZE = 5033164800
CH = 1 << 20
STATE = ROOT / 'work/state.json'
MAXRUN = 32                            # chunks per transfer

def adb(*a, inp=None, out=True):
    r = subprocess.run([ADB, *a], input=inp, capture_output=out)
    if r.returncode: raise RuntimeError(f'adb {a[0]} failed: {r.stderr.decode(errors="replace")[-300:]}')
    return r.stdout

def remote_sha(start, n):
    o = adb('shell', f'dd if={DEV} bs={CH} skip={start} count={n} 2>/dev/null | sha256sum').decode()
    return o.split()[0]

def main():
    p = argparse.ArgumentParser(); p.add_argument('image', type=Path); p.add_argument('--base', type=Path)
    p.add_argument('--full-verify', action='store_true')
    p.add_argument('--force', action='store_true', help='write base->new diff without checking the phone matches base; use with --full-verify')
    p.add_argument('--scan', action='store_true', help='hash every MiB on the phone instead of trusting a base image'); a = p.parse_args()
    state = json.loads(STATE.read_text()) if STATE.exists() else {}
    new = a.image.resolve(); base = new if a.scan else (a.base or Path(state.get('system_image', ''))).resolve()
    assert base.is_file(), 'Unknown current image; pass --base or --scan'
    assert new.stat().st_size == SIZE == base.stat().st_size, 'Image size must equal the system partition'
    if adb('get-state').strip() != b'recovery': sys.exit('Phone is not in flash-mode recovery: adb reboot recovery')
    assert adb('shell', 'cat /sys/fs/selinux/enforce').strip() == b'0'
    assert DEV.encode() not in adb('shell', 'cat /proc/mounts'), 'system is mounted'

    scr = Screen('Прошивка system', 'Не отключайте кабель')
    scr.line('host connected · fastflash v1')
    scr.line(f'target {DEV}')
    def fail(msg):
        scr.done('Ошибка', 'Система не изменена' if 'nothing written' in msg else 'Смотри лог на компьютере'); scr.line(msg); sys.exit(msg)
    print(f'Comparing {base.name} -> {new.name} ...', flush=True)
    scr.line(f'compare {base.name} -> {new.name}')
    runs = []; cur = None
    if a.scan:
        print('Hashing every MiB on the phone ...', flush=True); scr.line('scan: hashing system on the phone')
        n = SIZE // CH
        out = adb('shell', f'i=0; while [ $i -lt {n} ]; do dd if={DEV} bs={CH} skip=$i count=1 2>/dev/null | sha256sum; i=$((i+1)); done').decode().split()
        phone = out[0::2]; assert len(phone) == n, len(phone)
        with open(new, 'rb') as fn:
            for i in range(n):
                if hashlib.sha256(fn.read(CH)).hexdigest() != phone[i]:
                    if cur and cur[0] + cur[1] == i and cur[1] < MAXRUN: cur[1] += 1
                    else: cur = [i, 1]; runs.append(cur)
    with open(base, 'rb') as fb, open(new, 'rb') as fn:
        for i in range(SIZE // CH + (SIZE % CH > 0)):
            if fb.read(CH) != fn.read(CH):
                if cur and cur[0] + cur[1] == i and cur[1] < MAXRUN: cur[1] += 1
                else: cur = [i, 1]; runs.append(cur)
    total = sum(n for _, n in runs)
    print(f'{total} MiB changed in {len(runs)} pieces', flush=True)
    scr.line(f'{total} MiB changed in {len(runs)} pieces')
    if not runs: print('Nothing to write'); scr.p = 1.0; scr.done('Уже актуально', 'Нечего записывать'); return

    def local(f, s, n):
        with open(f, 'rb') as fh: fh.seek(s * CH); return fh.read(n * CH)
    print('Checking the phone matches the base image ...', flush=True)
    todo = []
    for s, n in runs:
        r = remote_sha(s, n)
        scr.line(f'verify base @ 0x{s * CH:08x}  sha {r[:8]}', push=False); scr.push()
        if r == hashlib.sha256(local(new, s, n)).hexdigest(): continue      # already written earlier
        if not (a.scan or a.force) and r != hashlib.sha256(local(base, s, n)).hexdigest():
            fail(f'Phone differs from {base.name} at MiB {s}; nothing written')
        todo.append((s, n))
    print(f'{len(runs) - len(todo)} pieces already up to date', flush=True)
    runs = todo; total = sum(n for _, n in runs) or 1

    t0 = time.time(); done = 0
    for s, n in runs:
        data = local(new, s, n)
        # push first (adb verifies the transfer), then write locally on the phone.
        # exec-in is not used: adbd can kill dd before it has read the end of the stream.
        tmp = ROOT / 'work/.ff-chunk.bin'; tmp.write_bytes(data)
        adb('push', str(tmp), '/tmp/ff.bin')
        adb('shell', f'dd if=/tmp/ff.bin of={DEV} bs={CH} seek={s} conv=notrunc,fsync 2>/dev/null && sync && rm /tmp/ff.bin')
        want = hashlib.sha256(data).hexdigest()
        if remote_sha(s, n) == want: pass
        else: fail(f'Readback mismatch at MiB {s}')
        done += n; print(f'  written {done}/{total} MiB ({done * 100 // total}%)', flush=True)
        scr.p = done / total; scr.line(f'write 0x{s * CH:08x} +{n}M  sha {want[:8]} ok')
    adb('shell', 'sync')
    print(f'Done in {time.time() - t0:.0f}s, every piece verified', flush=True)
    scr.line(f'sync · {done} MiB in {time.time() - t0:.0f}s · all verified ok', push=False)
    scr.done('Готово', 'adb reboot — запустить систему')
    if a.full_verify:
        print('Full partition hash check ...', flush=True)
        h = adb('shell', f'sha256sum {DEV}').split()[0].decode()
        assert h == hashlib.sha256(new.read_bytes()).hexdigest(), 'Full hash mismatch'
        print('Full hash OK')
    state['system_image'] = str(new); state['updated'] = time.strftime('%Y-%m-%d %H:%M')
    STATE.write_text(json.dumps(state, indent=2))

if __name__ == '__main__':
    main()
