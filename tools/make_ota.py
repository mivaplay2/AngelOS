"""Build an Angel OS delta update and register it in server/repo/ota/ota.json.

usage: make_ota.py BASE.img NEW.img FROM_CODE TO_CODE "Angel OS 1.1" "changelog text"
Payload = the 1 MiB chunks that differ; manifest lines: "<mib> <count> <sha256_new> <sha256_old>".
"""
import gzip, hashlib, io, json, sys, tarfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from recui import render, fb_gz

ROOT = Path(__file__).resolve().parents[1]; OUT = ROOT / 'server/repo/ota'
CH = 1 << 20; MAXRUN = 16

def sha(b): return hashlib.sha256(b).hexdigest()

def main(base, new, frm, to, name, notes):
    base, new, frm, to = Path(base), Path(new), int(frm), int(to)
    assert base.stat().st_size == new.stat().st_size
    OUT.mkdir(parents=True, exist_ok=True)
    tag = f'{frm}_{to}'
    payload = OUT / f'payload_{tag}.bin'; manifest = OUT / f'manifest_{tag}.txt'; frames = OUT / f'frames_{tag}.tar'
    runs = []; cur = None
    with open(base, 'rb') as fb, open(new, 'rb') as fn:
        i = 0
        while True:
            a, b = fb.read(CH), fn.read(CH)
            if not a: break
            if a != b:
                if cur and cur[0] + cur[1] == i and cur[1] < MAXRUN: cur[1] += 1
                else: cur = [i, 1]; runs.append(cur)
            i += 1
    lines = []
    with open(base, 'rb') as fb, open(new, 'rb') as fn, open(payload, 'wb') as out:
        for s, n in runs:
            fb.seek(s * CH); fn.seek(s * CH); old, nw = fb.read(n * CH), fn.read(n * CH)
            out.write(nw); lines.append(f'{s} {n} {sha(nw)} {sha(old)}')
    manifest.write_text('\n'.join(lines) + '\n')
    # progress screens for the recovery (framebuffer format, gzip)
    tf = io.BytesIO()
    with tarfile.open(fileobj=tf, mode='w') as t:
        def add(fname, im):
            data = fb_gz(im); ti = tarfile.TarInfo(fname); ti.size = len(data); t.addfile(ti, io.BytesIO(data))
        add('verify.gz', render('Проверка системы', 'Обновление до ' + name, 0.0, ['angel ota · проверка текущей системы ...'], footer='Не выключайте телефон', badge='ANGEL UPDATE'))
        for p in range(0, 101, 20):
            add(f'p{p}.gz', render('Установка обновления', name, p / 100, [f'angel ota · запись {p}%'], footer='Не выключайте телефон', badge='ANGEL UPDATE'))
        add('done.gz', render('Готово', 'Запускаю ' + name, 1.0, ['angel ota · всё проверено ok'], footer='', badge='ANGEL UPDATE'))
        add('error.gz', render('Обновление отменено', 'Система не изменена', None, ['angel ota · система не совпала, запись не начиналась'], footer='', badge='ANGEL UPDATE'))
    frames.write_bytes(tf.getvalue())
    def item(p): d = p.read_bytes(); return {'file': p.name, 'size': len(d), 'sha256': sha(d)}
    idx_p = OUT / 'ota.json'
    idx = json.loads(idx_p.read_text()) if idx_p.exists() else {'deltas': {}}
    if to >= idx.get('latest', 0): idx.update(latest=to, versionName=name, changelog=notes)
    entry = {'to': to, 'payload': item(payload), 'manifest': item(manifest), 'frames': item(frames)}
    msg = f"angel-ota {frm} {to} payload={entry['payload']['sha256']} manifest={entry['manifest']['sha256']} frames={entry['frames']['sha256']}"
    import base64, subprocess
    sig = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(ROOT / 'work/keys/ota_ec.pem')], input=msg.encode(), capture_output=True, check=True).stdout
    entry['sig'] = base64.b64encode(sig).decode()
    idx['deltas'][str(frm)] = entry
    idx_p.write_text(json.dumps(idx, ensure_ascii=False, indent=1))
    print(f'{len(runs)} pieces, {sum(n for _, n in runs)} MiB payload, frames {frames.stat().st_size // 1024} KB')

if __name__ == '__main__':
    main(*sys.argv[1:7])
