package os.angel.island;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** The island: a black pill that morphs between idle, compact and expanded content. */
final class IslandView extends View {
    interface Host { void onSizeWanted(int w, int h); void onTap(); void onControl(int which); }

    static final int IDLE = 0, COMPACT = 1, EXPANDED = 2;
    static final int FG = 0xFFF1EFE8, MUTED = 0xFF9A988F, GREEN = 0xFF7BD88F;

    final float d = getResources().getDisplayMetrics().density;
    final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Host host;

    // current animated geometry
    float w, h, r; float tw, th; ValueAnimator anim;
    int mode = IDLE;
    // content
    String kind = "";            // "charge", "media", "notif"
    String line1 = "", line2 = "", right = "";
    Drawable icon; Bitmap art; boolean playing; int level;
    long t0 = System.currentTimeMillis();

    IslandView(Context c, Host host) {
        super(c); this.host = host;
        pill.setColor(0xFF000000);
        title.setColor(FG); title.setTextSize(14 * d); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        body.setColor(MUTED); body.setTextSize(12.5f * d);
        w = tw = idleW(); h = th = idleH();
    }

    float cutW = 107, cutH = 26;   // dp, replaced with the display's real top cutout
    float idleW() { return (cutW + 6) * d; }
    float idleH() { return (cutH + 4) * d; }
    float contentTop() { return (cutH + 6) * d; }   // expanded content starts below the camera

    void morph(int newMode) {
        mode = newMode;
        float nw, nh;
        if (newMode == IDLE) { nw = idleW(); nh = idleH(); }
        else if (newMode == COMPACT) { nw = Math.max(idleW() + 96 * d, 220 * d); nh = idleH(); }
        else { nw = Math.min(getResources().getDisplayMetrics().widthPixels - 16 * d, 344 * d); nh = contentTop() + (kind.equals("media") ? 118 * d : 62 * d); }
        final float sw = w, sh = h; tw = nw; th = nh;
        host.onSizeWanted((int) Math.max(nw, sw), (int) Math.max(nh, sh));
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(newMode == EXPANDED ? 520 : 420);
        anim.setInterpolator(new OvershootInterpolator(newMode == IDLE ? 0.6f : 1.1f));
        anim.addUpdateListener(a -> { float f = (float) a.getAnimatedValue(); w = sw + (tw - sw) * f; h = sh + (th - sh) * f; invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { host.onSizeWanted((int) tw, (int) th); }
        });
        anim.start();
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, top = 0;
        float left = cx - w / 2, rr = Math.min(h / 2, 15 * d);
        if (h > 40 * d) rr = 28 * d;
        c.drawRoundRect(new RectF(left, -rr, left + w, top + h), rr, rr, pill);   // flat top hidden under the bezel
        RectF box = new RectF(left, top, left + w, top + h);
        float reveal = Math.max(0, Math.min(1, (w - idleW()) / (60 * d)));   // fade content in as the pill grows
        if (reveal <= 0.01f || kind.isEmpty() || mode == IDLE) return;
        int alpha = (int) (255 * reveal);
        if (mode == COMPACT) drawCompact(c, box, alpha); else drawExpanded(c, box, alpha);
        if (kind.equals("media") && playing) postInvalidateOnAnimation();
    }

    void drawCompact(Canvas c, RectF b, int alpha) {
        float pad = 10 * d, s = b.height() - 10 * d, y = b.top + 5 * d;
        // left: icon / art / bolt
        if (kind.equals("charge")) drawBolt(c, b.left + pad + s / 2, b.centerY(), s * 0.42f, alpha);
        else if (kind.equals("media") && art != null) drawArt(c, new RectF(b.left + pad, y, b.left + pad + s, y + s), 6 * d, alpha);
        else if (icon != null) { icon.setBounds((int) (b.left + pad), (int) y, (int) (b.left + pad + s), (int) (y + s)); icon.setAlpha(alpha); icon.draw(c); }
        // right: percent / equalizer
        if (kind.equals("media")) drawBars(c, b.right - pad - 18 * d, b.centerY(), alpha);
        else {
            title.setAlpha(alpha); title.setColor(kind.equals("charge") ? GREEN : FG); title.setAlpha(alpha);
            float tw = title.measureText(right);
            c.drawText(right, b.right - pad - tw, b.centerY() + title.getTextSize() * 0.35f, title);
            title.setColor(FG);
        }
    }

    void drawExpanded(Canvas c, RectF b, int alpha) {
        float pad = 16 * d, s = kind.equals("media") ? 56 * d : 44 * d, y = b.top + contentTop();
        RectF ic = new RectF(b.left + pad, y, b.left + pad + s, y + s);
        if (kind.equals("media") && art != null) drawArt(c, ic, 12 * d, alpha);
        else if (kind.equals("charge")) drawBolt(c, ic.centerX(), ic.centerY(), s * 0.36f, alpha);
        else if (kind.equals("info")) drawRing(c, ic.centerX(), ic.centerY(), s * 0.34f, alpha);
        else if (icon != null) { icon.setBounds((int) ic.left, (int) ic.top, (int) ic.right, (int) ic.bottom); icon.setAlpha(alpha); icon.draw(c); }
        float tx = ic.right + 12 * d, avail = b.right - pad - tx;
        title.setAlpha(alpha); body.setAlpha(alpha);
        c.drawText(TextUtils.ellipsize(line1, title, avail, TextUtils.TruncateAt.END).toString(), tx, y + 18 * d, title);
        c.drawText(TextUtils.ellipsize(line2, body, avail, TextUtils.TruncateAt.END).toString(), tx, y + 38 * d, body);
        if (kind.equals("media")) {   // controls row
            float cy = b.bottom - 30 * d, cx = b.centerX(), gap = 64 * d;
            ink.setColor(FG); ink.setAlpha(alpha);
            drawPrev(c, cx - gap, cy); drawPlayPause(c, cx, cy); drawNext(c, cx + gap, cy);
        }
    }

    void drawRing(Canvas c, float cx, float cy, float r, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.16f); p.setColor(FG); p.setAlpha(alpha);
        c.drawCircle(cx, cy, r, p);
        double a = Math.toRadians(-45); float dx = cx + r * (float) Math.cos(a), dy = cy + r * (float) Math.sin(a);
        ink.setColor(0xFF000000); c.drawCircle(dx, dy, r * 0.34f, ink); ink.setColor(FG); ink.setAlpha(alpha); c.drawCircle(dx, dy, r * 0.22f, ink);
    }

    void drawArt(Canvas c, RectF r, float rad, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix(); float sc = Math.max(r.width() / art.getWidth(), r.height() / art.getHeight());
        m.setScale(sc, sc); m.postTranslate(r.left, r.top); sh.setLocalMatrix(m);
        p.setShader(sh); p.setAlpha(alpha); c.drawRoundRect(r, rad, rad, p);
    }

    void drawBolt(Canvas c, float cx, float cy, float s, int alpha) {
        Path p = new Path();
        p.moveTo(cx + s * 0.15f, cy - s); p.lineTo(cx - s * 0.55f, cy + s * 0.12f); p.lineTo(cx - s * 0.02f, cy + s * 0.12f);
        p.lineTo(cx - s * 0.18f, cy + s); p.lineTo(cx + s * 0.55f, cy - s * 0.15f); p.lineTo(cx + s * 0.02f, cy - s * 0.15f); p.close();
        ink.setColor(GREEN); ink.setAlpha(alpha); c.drawPath(p, ink);
    }

    void drawBars(Canvas c, float x, float cy, int alpha) {
        ink.setColor(FG); ink.setAlpha(alpha);
        float t = (System.currentTimeMillis() - t0) / 1000f;
        for (int i = 0; i < 4; i++) {
            float amp = playing ? (0.35f + 0.65f * (float) Math.abs(Math.sin(t * (3.1 + i * 1.3) + i))) : 0.25f;
            float bh = 14 * d * amp, bx = x + i * 5 * d;
            c.drawRoundRect(new RectF(bx, cy - bh / 2, bx + 3 * d, cy + bh / 2), 1.5f * d, 1.5f * d, ink);
        }
    }

    void drawPrev(Canvas c, float cx, float cy) {
        float s = 9 * d; Path p = new Path();
        p.moveTo(cx + s, cy - s); p.lineTo(cx - s * 0.2f, cy); p.lineTo(cx + s, cy + s); p.close(); c.drawPath(p, ink);
        c.drawRect(cx - s * 0.9f, cy - s, cx - s * 0.5f, cy + s, ink);
    }
    void drawNext(Canvas c, float cx, float cy) {
        float s = 9 * d; Path p = new Path();
        p.moveTo(cx - s, cy - s); p.lineTo(cx + s * 0.2f, cy); p.lineTo(cx - s, cy + s); p.close(); c.drawPath(p, ink);
        c.drawRect(cx + s * 0.5f, cy - s, cx + s * 0.9f, cy + s, ink);
    }
    void drawPlayPause(Canvas c, float cx, float cy) {
        float s = 12 * d;
        if (playing) { c.drawRoundRect(new RectF(cx - s * 0.7f, cy - s, cx - s * 0.2f, cy + s), 2 * d, 2 * d, ink);
                       c.drawRoundRect(new RectF(cx + s * 0.2f, cy - s, cx + s * 0.7f, cy + s), 2 * d, 2 * d, ink); }
        else { Path p = new Path(); p.moveTo(cx - s * 0.6f, cy - s); p.lineTo(cx + s, cy); p.lineTo(cx - s * 0.6f, cy + s); p.close(); c.drawPath(p, ink); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        if (mode == EXPANDED && kind.equals("media") && e.getY() > h - 56 * d) {
            float cx = getWidth() / 2f, x = e.getX(), gap = 64 * d;
            host.onControl(x < cx - gap / 2 ? 0 : (x > cx + gap / 2 ? 2 : 1));
            return true;
        }
        host.onTap();
        return true;
    }
}
