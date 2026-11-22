"""Flash-mode recovery screen: rendered on the Mac, pushed into the phone's framebuffer.

mtkfb: 720x1560 visible, 736 px stride, 3 buffers, RGBA byte order.
"""
import gzip, math, subprocess, time
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

W, H, STRIDE, BUFS, BUF_LINES = 720, 1560, 736, 3, 1568
ADB = str(Path.home() / 'Desktop/platform-tools/adb')
BG = (8, 8, 9); FG = (241, 239, 232); DIM = (42, 42, 45); MUTED = (128, 126, 120); FAINT = (70, 69, 66)
OK = (120, 200, 160)
CX, CY, R = 360, 560, 128          # same ring geometry as the boot logo

def _font(name, size, index=0):
    for p in [f'/System/Library/Fonts/{name}', f'/System/Library/Fonts/Supplemental/{name}']:
        if Path(p).exists(): return ImageFont.truetype(p, size, index=index)
    return ImageFont.load_default()
SANS = lambda s: _font('SFNS.ttf', s)
MONO = lambda s: _font('SFNSMono.ttf', s)

def render(title, subtitle, progress=None, log=(), footer='adb reboot  ·  выход в систему', badge='FLASH MODE'):
    S = 2
    im = Image.new('RGB', (W * S, H * S), BG); d = ImageDraw.Draw(im)
    # top bar
    d.text((48 * S, 70 * S), badge, font=MONO(22 * S), fill=MUTED)
    d.text((W * S - 48 * S, 70 * S), 'CPH1923 · MT6765', font=MONO(22 * S), fill=FAINT, anchor='ra')
    # ring: dim track + bright progress arc with the satellite dot at its head
    cx, cy, r, w = CX * S, CY * S, R * S, 13 * S
    box = (cx - r, cy - r, cx + r, cy + r)
    if progress is None:
        d.ellipse(box, outline=FG, width=w); ang = -45
    else:
        d.ellipse(box, outline=DIM, width=w)
        ang = -90 + 360 * max(0.0, min(1.0, progress))
        if progress > 0: d.arc(box, -90, ang, fill=FG, width=w)
        d.text((cx, cy), f'{int(progress * 100)}%', font=SANS(64 * S), fill=FG, anchor='mm')
    a = math.radians(ang); dx, dy = cx + r * math.cos(a), cy + r * math.sin(a); dot, halo = 26 * S, 12 * S
    d.ellipse((dx - dot - halo, dy - dot - halo, dx + dot + halo, dy + dot + halo), fill=BG)
    d.ellipse((dx - dot, dy - dot, dx + dot, dy + dot), fill=FG)
    # titles
    d.text((cx, 790 * S), title, font=SANS(46 * S), fill=FG, anchor='mm')
    d.text((cx, 846 * S), subtitle, font=SANS(26 * S), fill=MUTED, anchor='mm')
    # log console
    top, left, lh = 930, 48, 30
    d.line((left * S, (top - 20) * S, (W - left) * S, (top - 20) * S), fill=DIM, width=S)
    lines = list(log)[-17:]
    for i, line in enumerate(lines):
        age = len(lines) - 1 - i
        col = FG if age == 0 else (OK if line.endswith(' ok') and age < 3 else (MUTED if age < 6 else FAINT))
        d.text((left * S, (top + i * lh) * S), line[:46], font=MONO(19 * S), fill=col)
    d.text((cx, (H - 56) * S), footer, font=SANS(20 * S), fill=FAINT, anchor='mm')
    return im.resize((W, H), Image.LANCZOS)

def fb_bytes(im):
    # The display scans 720-px rows (no padding); each of the 3 buffers starts every
    # 736*1568*4 bytes (the driver's virtual layout), so pad each buffer to that size.
    b = im.convert('RGBA').tobytes('raw', 'RGBA')
    buf = b + b'\0' * (STRIDE * 4 * BUF_LINES - len(b))
    return buf * BUFS

def fb_gz(im): return gzip.compress(fb_bytes(im), 1)

class Screen:
    """Live screen on the phone. Silently does nothing if the phone can't be drawn on."""
    def __init__(self, title='Прошивка', subtitle='Не отключайте кабель'):
        self.title, self.subtitle, self.log, self.p, self.t0, self.last = title, subtitle, [], 0.0, time.time(), 0
        self.ok = True
        try: subprocess.run([ADB, 'shell', 'echo 1500 > /sys/class/leds/lcd-backlight/brightness'], capture_output=True, timeout=10)
        except Exception: self.ok = False
    def line(self, text, push=True):
        self.log.append(f'[{time.time() - self.t0:7.2f}] {text}')
        if push: self.push()
    def push(self, force=False):
        if not self.ok or (not force and time.time() - self.last < 0.25): return
        self.last = time.time()
        data = fb_gz(render(self.title, self.subtitle, self.p, self.log))
        tmp = Path(__file__).resolve().parents[1] / 'work/.frame.gz'; tmp.write_bytes(data)
        r = subprocess.run([ADB, 'push', str(tmp), '/tmp/frame.gz'], capture_output=True)
        if not r.returncode:
            r = subprocess.run([ADB, 'shell', 'kill -STOP $(pidof recovery) 2>/dev/null; zcat /tmp/frame.gz > /dev/graphics/fb0'], capture_output=True)
        if r.returncode: self.ok = False
    def done(self, title, subtitle):
        if title == 'Готово' or title == 'Уже актуально': self.p = 1.0
        self.title, self.subtitle = title, subtitle; self.push(force=True)

if __name__ == '__main__':
    out = Path(__file__).resolve().parents[1] / 'work/recui'; out.mkdir(exist_ok=True)
    render('Режим прошивки', 'Подключите к компьютеру', None,
           ['flashmode v1 · recovery ready', 'adbd: root  selinux: permissive', 'system: /dev/block/mmcblk0p38 (unmounted)', 'waiting for host ...']).save(out / 'idle.png')
    demo = ['compare cph1923-v3.img -> v4.img', '8 MiB changed in 8 pieces', 'verify base @ 0x30c00000 ok',
            'write 0x30c00000 +1M  sha 65ec0d86 ok', 'write 0x31000000 +1M  sha 9a1f2c4e ok', 'write 0x4e800000 +1M  sha feb435c2 ok',
            'write 0x4ec00000 +1M  sha 1b77ad09 ...']
    render('Прошивка system', 'Не отключайте кабель', 0.45, demo).save(out / 'flashing.png')
    print(out)

def demo():
    """Animate the flashing screen on the phone without writing anything."""
    import hashlib, random
    s = Screen('Прошивка system', 'Демо · ничего не записывается')
    for l in ['host connected · fastflash v1', 'target /dev/block/mmcblk0p38', 'compare cph1923-v3.img -> v4.img', '24 MiB changed in 9 pieces']:
        s.line(l); time.sleep(0.3)
    offs = sorted(random.sample(range(200, 4700), 24))
    for i, o in enumerate(offs):
        h = hashlib.sha256(str(o).encode()).hexdigest()[:8]
        s.p = (i + 1) / len(offs); s.line(f'write 0x{o << 20:08x} +1M  sha {h} ok'); time.sleep(0.15)
    s.line('sync · 24 MiB in 6s · all verified ok', push=False)
    s.done('Готово', 'adb reboot — запустить систему')

if __name__ == '__main__' and len(__import__('sys').argv) > 1 and __import__('sys').argv[1] == 'demo':
    demo()
