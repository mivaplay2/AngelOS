"""New system image = base image + overlay APKs in /system/product/overlay/<Name>/<Name>.apk

usage: add_overlays.py BASE.img NEW.img path/Name.apk [path/Other.apk ...]
Existing overlays with the same name are replaced (metadata kept).
"""
import subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from image_utils import Ext4, digest

E2 = Path('/usr/local/opt/e2fsprogs/sbin')

def main(base, new, apks):
    base, new = Path(base), Path(new)
    assert base.is_file() and not new.exists(), 'need existing base and a new output name'
    subprocess.run(['cp', '-c', str(base), str(new)], check=True)
    b = new.with_suffix('.build'); b.mkdir(exist_ok=True)
    fs = Ext4(new, E2, b / 'ext4')
    for apk in map(Path, apks):
        name = apk.stem; d = f'/system/product/overlay/{name}'; target = f'{d}/{name}.apk'
        exists = 'Type: regular' in subprocess.run([str(E2 / 'debugfs'), '-R', f'stat {target}', str(new)],
                                                   capture_output=True, text=True).stdout
        if not exists:
            dir_exists = 'Type: directory' in subprocess.run([str(E2 / 'debugfs'), '-R', f'stat {d}', str(new)],
                                                             capture_output=True, text=True).stdout
            if not dir_exists: fs.mkdir(d)
        fs.put(apk, target, replace=exists)
        assert digest(fs.extract(target, b / (name + '.chk'))) == digest(apk)
        print(('replaced ' if exists else 'added ') + target)
    r = subprocess.run([str(E2 / 'e2fsck'), '-fn', str(new)], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout[-800:]
    print('fs ok', new)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
