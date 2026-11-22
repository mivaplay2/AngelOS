"""Generate a boot animation matching the minimal ring+dot boot logo.

Frames are 400x480; bootanimation centers them on the 720x1560 screen, which puts
the ring center at y=740 -- exactly where it sits in the boot logo.
"""
import math, os, shutil, sys, zipfile
from PIL import Image, ImageDraw, ImageChops

OUT = os.path.dirname(os.path.abspath(__file__))
FW, FH, SS = 400, 480, 3          # frame size, supersampling
CX, CY = 200, 200                 # ring center inside frame
R, W, DOT, HALO = 128, 13, 26, 12  # same geometry as the logo
FG = (241, 239, 232)
FPS = 30
START = -45.0                     # dot angle in the logo (upper right), degrees, 0 = right, ccw negative = up

def ease_in(t):
    # starts at rest, ends at slope 4/3 so the speed matches part1 (48 frames vs 36/turn)
    return (5 / 3) * t * t - (2 / 3) * t ** 3
def ease_out(t): return 1 - (1 - t) ** 3

def frame(angle, trail=0.0, scale=1.0, alpha=1.0):
    S = SS
    im = Image.new('RGB', (FW * S, FH * S), (0, 0, 0))
    d = ImageDraw.Draw(im)
    fg = tuple(int(c * alpha) for c in FG)
    r, w, dot, halo = R * scale * S, W * scale * S, DOT * scale * S, HALO * scale * S
    cx, cy = CX * S, CY * S
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=fg, width=max(1, int(w)))
    a = math.radians(angle)
    # trail: comet tail on its own layer, merged with "lighter" so it never darkens the ring
    if trail > 0:
        layer = Image.new('RGB', im.size, (0, 0, 0)); ld = ImageDraw.Draw(layer)
        n = 18
        for i in range(n, 0, -1):
            ta = math.radians(angle - i * 3.4 * trail)
            k = (1 - i / (n + 1)) ** 1.6 * 0.6 * alpha
            tr = dot * (0.3 + 0.65 * (1 - i / n))
            tx, ty = cx + r * math.cos(ta), cy + r * math.sin(ta)
            ld.ellipse((tx - tr, ty - tr, tx + tr, ty + tr), fill=tuple(int(c * k) for c in FG))
        im = ImageChops.lighter(im, layer); d = ImageDraw.Draw(im)
    dx, dy = cx + r * math.cos(a), cy + r * math.sin(a)
    d.ellipse((dx - dot - halo, dy - dot - halo, dx + dot + halo, dy + dot + halo), fill=(0, 0, 0))
    d.ellipse((dx - dot, dy - dot, dx + dot, dy + dot), fill=fg)
    return im.resize((FW, FH), Image.LANCZOS)

def main():
    parts = {}
    # part0: from the static logo, dot accelerates through one full turn (1.6 s)
    n0 = 48
    parts['part0'] = [frame(START + 360 * ease_in(i / n0), trail=min(1, (i / n0) * 1.5)) for i in range(n0)]
    # part1: constant-speed orbit loop (1.2 s per turn), seamless with part0's end speed
    n1 = 36
    parts['part1'] = [frame(START + 360 * i / n1, trail=1) for i in range(n1)]
    # part2: after boot completes, ring grows slightly and fades (0.6 s)
    n2 = 18
    parts['part2'] = [frame(START, trail=0, scale=1 + 0.18 * ease_out(i / n2), alpha=1 - ease_out(i / n2)) for i in range(n2)]
    parts['part2'].append(Image.new('RGB', (FW, FH), (0, 0, 0)))

    build = os.path.join(OUT, 'build'); shutil.rmtree(build, ignore_errors=True)
    for name, frames in parts.items():
        os.makedirs(os.path.join(build, name))
        for i, im in enumerate(frames):
            im.save(os.path.join(build, name, f'{i:05d}.png'), optimize=True)
    desc = f"{FW} {FH} {FPS}\nc 1 0 part0\nc 0 0 part1\nc 1 0 part2\n"
    with open(os.path.join(build, 'desc.txt'), 'w') as f: f.write(desc)
    zp = os.path.join(OUT, 'bootanimation.zip')
    with zipfile.ZipFile(zp, 'w', zipfile.ZIP_STORED) as z:   # must be stored, not deflated
        z.write(os.path.join(build, 'desc.txt'), 'desc.txt')
        for name in parts:
            for fn in sorted(os.listdir(os.path.join(build, name))):
                z.write(os.path.join(build, name, fn), f'{name}/{fn}')
    # preview gif: logo hold, part0, two loops of part1, part2
    logo = frame(START)
    seq = [logo] * 15 + parts['part0'] + parts['part1'] * 2 + parts['part2'] + [parts['part2'][-1]] * 10
    seq[0].save(os.path.join(OUT, 'preview.gif'), save_all=True, append_images=seq[1:], duration=int(1000 / FPS), loop=0)
    print(zp, os.path.getsize(zp))

if __name__ == '__main__':
    main()
