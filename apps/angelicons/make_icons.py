"""Angel Icons: ivory line glyphs on graphite, one accent detail each. Renders adaptive-icon foregrounds."""
import math, os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'res')
S = 4; N = 432                      # 108dp at xxxhdpi; safe zone radius ~132 px
FG = (241, 239, 232, 255)
K = 1.32                            # glyph scale (≈55% of the visible tile)
W = 15 * S                          # line weight
C = N * S / 2

ACC = dict(green=(123, 216, 143, 255), blue=(120, 170, 255, 255), red=(255, 107, 107, 255),
           amber=(255, 196, 92, 255), violet=(176, 150, 255, 255), teal=(92, 214, 200, 255))

def canvas():
    im = Image.new('RGBA', (N * S, N * S), (0, 0, 0, 0)); return im, ImageDraw.Draw(im)
def P(x, y): return (C + x * K * S, C + y * K * S)
def box(x0, y0, x1, y1): return (*P(x0, y0), *P(x1, y1))
def line(d, pts, col=FG, w=W): d.line([P(*p) for p in pts], fill=col, width=w, joint='curve'); [dot(d, p, w / S / 2 / K, col) for p in (pts[0], pts[-1])]
def dot(d, p, r, col=FG): x, y = P(*p); r = r * K; d.ellipse((x - r * S, y - r * S, x + r * S, y + r * S), fill=col)
def rrect(d, b, r, col=FG, w=W, fill=None): d.rounded_rectangle(box(*b), radius=r * K * S, outline=col, width=w, fill=fill)
def circ(d, cx, cy, r, col=FG, w=W, fill=None): d.ellipse(box(cx - r, cy - r, cx + r, cy + r), outline=col, width=w, fill=fill)
def arc(d, cx, cy, r, a0, a1, col=FG, w=W): d.arc(box(cx - r, cy - r, cx + r, cy + r), a0, a1, fill=col, width=w)

def phone(d):
    # handset: a smooth thick arc with rounded ends
    cx, cy, r = 34, -34, 74
    arc(d, cx, cy, r, 82, 188, col=ACC['green'], w=24 * S)
    for a in (82, 188):
        x, y = cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))
        dot(d, (x, y), 17, ACC['green'])
def messages(d):
    rrect(d, (-62, -50, 62, 34), 30)
    d.polygon([P(-30, 30), P(-44, 60), P(-6, 32)], fill=FG)
    dot(d, (40, -46), 13, ACC['blue'])
def contacts(d):
    circ(d, 0, -22, 26)
    arc(d, 0, 58, 54, 200, 340)
    dot(d, (44, -46), 11, ACC['teal'])
def camera(d):
    rrect(d, (-64, -38, 64, 50), 18)
    line(d, [(-26, -38), (-16, -54), (16, -54), (26, -38)])
    circ(d, 0, 6, 24); dot(d, (44, -22), 8, ACC['amber'])
def globe(d):
    circ(d, 0, 0, 58)
    d.ellipse(box(-24, -58, 24, 58), outline=FG, width=W - 6 * S)
    line(d, [(-56, 0), (56, 0)], w=W - 6 * S)
    dot(d, (40, -40), 11, ACC['blue'])
def folder(d):
    rrect(d, (-64, -40, 64, 50), 14)
    line(d, [(-64, -26), (-64, -46), (-18, -46), (-6, -32), (64, -32)])
    line(d, [(-30, 14), (30, 14)], col=ACC['amber'])
def gear(d):
    circ(d, 0, 0, 38)
    for i in range(8):
        a = math.radians(i * 45)
        line(d, [(40 * math.cos(a), 40 * math.sin(a)), (56 * math.cos(a), 56 * math.sin(a))], w=W + 12 * S)
    circ(d, 0, 0, 14, col=ACC['violet'])
def sim(d):
    d.polygon([P(-40, -60), P(20, -60), P(44, -36), P(44, 60), P(-40, 60)], outline=FG, width=W)
    rrect(d, (-18, -10, 22, 34), 6, col=ACC['amber'])
def bag(d):
    rrect(d, (-54, -26, 54, 58), 16); arc(d, 0, -26, 28, 180, 360); dot(d, (0, 18), 12, ACC['green'])
def calc(d):
    rrect(d, (-58, -64, 58, 64), 22)
    w = W - 4 * S
    line(d, [(-38, -28), (-14, -28)], w=w); line(d, [(-26, -40), (-26, -16)], w=w)
    line(d, [(14, -28), (38, -28)], w=w)
    line(d, [(-36, 18), (-16, 38)], w=w); line(d, [(-16, 18), (-36, 38)], w=w)
    line(d, [(14, 22), (38, 22)], col=ACC['amber'], w=w); line(d, [(14, 36), (38, 36)], col=ACC['amber'], w=w)
def clock(d):
    circ(d, 0, 0, 60)
    line(d, [(0, 0), (0, -36)]); line(d, [(0, 0), (28, 16)], col=ACC['amber']); dot(d, (0, 0), 10)
def keep(d):
    d.polygon([P(-46, -60), P(22, -60), P(46, -36), P(46, 60), P(-46, 60)], outline=FG, width=W)
    line(d, [(22, -60), (22, -36), (46, -36)])
    line(d, [(-24, -4), (24, -4)], w=W - 4 * S); line(d, [(-24, 22), (10, 22)], w=W - 4 * S)
    dot(d, (-24, -34), 9, ACC['amber'])
def sliders(d):
    for x, y in ((-36, 14), (0, -24), (36, 30)):
        line(d, [(x, -56), (x, 56)], w=W - 6 * S)
        dot(d, (x, y), 17, ACC['violet'] if x == 0 else FG)
def note(d):
    line(d, [(14, 34), (14, -56), (52, -44)])
    d.ellipse(box(-36, 14, 16, 58), fill=FG)
    dot(d, (52, -44), 11, ACC['red'])
def calendar(d):
    rrect(d, (-60, -48, 60, 60), 20)
    line(d, [(-60, -16), (60, -16)]); line(d, [(-30, -64), (-30, -40)]); line(d, [(30, -64), (30, -40)])
    for i, (x, y) in enumerate([(-30, 12), (0, 12), (30, 12), (-30, 38), (0, 38)]):
        dot(d, (x, y), 9, ACC['red'] if i == 4 else FG)
def compass(d):
    circ(d, 0, 0, 58)
    d.polygon([P(0, -38), P(14, 0), P(0, 38), P(-14, 0)], fill=FG)
    d.polygon([P(0, -38), P(14, 0), P(-14, 0)], fill=ACC['red'])
def gallery(d):
    rrect(d, (-62, -50, 62, 50), 18)
    d.polygon([P(-46, 34), P(-12, -6), P(14, 20), P(28, 6), P(48, 34)], fill=FG)
    dot(d, (30, -24), 11, ACC['amber'])
def mic(d):
    rrect(d, (-24, -62, 24, 18), 24)
    arc(d, 0, 0, 44, 20, 160); line(d, [(0, 44), (0, 62)])
    dot(d, (40, -46), 11, ACC['red'])

ICONS = {
    'ic_dialer': (phone, ['com.google.android.dialer/com.google.android.dialer.extensions.GoogleDialtactsActivity', 'com.android.dialer/com.android.dialer.main.impl.MainActivity']),
    'ic_messages': (messages, ['com.google.android.apps.messaging/com.google.android.apps.messaging.ui.ConversationListActivity', 'com.android.messaging/com.android.messaging.ui.conversationlist.ConversationListActivity']),
    'ic_contacts': (contacts, ['com.google.android.contacts/com.android.contacts.activities.PeopleActivity', 'com.android.contacts/com.android.contacts.activities.PeopleActivity']),
    'ic_camera': (camera, ['com.android.camera2/com.android.camera.CameraLauncher']),
    'ic_chrome': (globe, ['com.android.chrome/com.google.android.apps.chrome.Main']),
    'ic_files': (folder, ['com.android.documentsui/com.android.documentsui.LauncherActivity']),
    'ic_settings': (gear, ['com.android.settings/com.android.settings.Settings']),
    'ic_sim': (sim, ['com.android.stk/com.android.stk.StkMain']),
    'ic_store': (bag, ['com.android.vending/com.android.vending.AssetBrowserActivity']),
    'ic_calculator': (calc, ['com.google.android.calculator/com.android.calculator2.Calculator', 'com.android.calculator2/com.android.calculator2.Calculator']),
    'ic_clock': (clock, ['com.google.android.deskclock/com.android.deskclock.DeskClock', 'com.android.deskclock/com.android.deskclock.DeskClock']),
    'ic_gallery': (gallery, ['com.android.gallery3d/com.android.gallery3d.app.GalleryActivity']),
    'ic_keep': (keep, ['com.google.android.keep/com.google.android.keep.activities.BrowseActivity']),
    'ic_audiofx': (sliders, ['org.lineageos.audiofx/org.lineageos.audiofx.activity.ActivityMusic']),
    'ic_music': (note, ['org.lineageos.eleven/org.lineageos.eleven.ui.activities.HomeActivity']),
    'ic_calendar': (calendar, ['org.lineageos.etar/com.android.calendar.AllInOneActivity']),
    'ic_browser': (compass, ['org.lineageos.jelly/org.lineageos.jelly.MainActivity']),
    'ic_recorder': (mic, ['org.lineageos.recorder/org.lineageos.recorder.RecorderActivity']),
}

def main():
    fgdir = os.path.join(OUT, 'drawable-xxxhdpi'); os.makedirs(fgdir, exist_ok=True)
    dr = os.path.join(OUT, 'drawable'); os.makedirs(dr, exist_ok=True)
    xml = os.path.join(OUT, 'xml'); os.makedirs(xml, exist_ok=True)
    with open(os.path.join(dr, 'angel_bg.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <gradient android:angle="270" android:startColor="#FF22222A" android:endColor="#FF0C0C0F"/>\n</shape>\n')
    sheet = Image.new('RGBA', (6 * 150, 4 * 150), (40, 40, 44, 255))
    for i, (name, (fn, comps)) in enumerate(ICONS.items()):
        im, d = canvas(); fn(d)
        fg = im.resize((N, N), Image.LANCZOS); fg.save(os.path.join(fgdir, name + '_fg.png'))
        with open(os.path.join(dr, name + '.xml'), 'w') as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    f'    <background android:drawable="@drawable/angel_bg"/>\n    <foreground android:drawable="@drawable/{name}_fg"/>\n</adaptive-icon>\n')
        # preview tile: gradient bg + squircle mask
        bg = Image.new('RGBA', (N, N)); bd = ImageDraw.Draw(bg)
        for y in range(N):
            t = y / N; c = tuple(int(a * (1 - t) + b * t) for a, b in zip((34, 34, 42), (12, 12, 15))) + (255,)
            bd.line((0, y, N, y), fill=c)
        bg.alpha_composite(fg)
        tile = bg.crop((54, 54, N - 54, N - 54)).resize((128, 128), Image.LANCZOS)
        m = Image.new('L', (128, 128), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, 127, 127), radius=36, fill=255)
        sheet.paste(tile, ((i % 6) * 150 + 11, (i // 6) * 150 + 11), m)
    sheet.save(os.path.join(os.path.dirname(OUT), 'preview.png'))
    with open(os.path.join(xml, 'appfilter.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
        for name, (_, comps) in ICONS.items():
            for c in comps: f.write(f'    <item component="ComponentInfo{{{c}}}" drawable="{name}"/>\n')
        f.write('</resources>\n')
    print(len(ICONS), 'icons')

if __name__ == '__main__':
    main()
