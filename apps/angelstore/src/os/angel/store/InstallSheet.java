package os.angel.store;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bottom sheet: app details and a single action that morphs into a progress ring. */
final class InstallSheet extends FrameLayout {
    interface Actions { void install(Stores.App a); void open(Stores.App a); void store(Stores.App a, boolean play); }

    static final int CARD = 0xFF141417, FG = 0xFFF1EFE8, MUTED = 0xFF8A8883, FAINT = 0xFF4A4946, GREEN = 0xFF7BD88F, DARK = 0xFF0B0B0D;
    final float d = getResources().getDisplayMetrics().density;
    final LinearLayout card = new LinearLayout(getContext());
    final ImageView icon = new ImageView(getContext());
    final TextView title = t(24, FG, true), sub = t(14, MUTED, false), meta = t(13, FAINT, false), desc = t(15, FG, false);
    final TextView button = t(17, DARK, true), status = t(14, MUTED, false);
    final Progress ring = new Progress(getContext());
    final LinearLayout action = new LinearLayout(getContext());
    final Actions actions;
    Stores.App app; boolean busy;

    InstallSheet(Context c, Actions actions) {
        super(c); this.actions = actions;
        setBackgroundColor(0x99000000); setAlpha(0f); setVisibility(GONE);
        setOnClickListener(v -> { if (!busy) hide(); });
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(px(24), px(14), px(24), px(28));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD);
        bg.setCornerRadii(new float[]{px(30), px(30), px(30), px(30), 0, 0, 0, 0}); card.setBackground(bg);
        card.setClickable(true);
        View grip = new View(c); GradientDrawable g = new GradientDrawable(); g.setColor(0xFF3A3A3E); g.setCornerRadius(px(3)); grip.setBackground(g);
        card.addView(grip, new LinearLayout.LayoutParams(px(40), px(5)));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP); icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() { public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(24)); } });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(96), px(96)); ip.topMargin = px(22); card.addView(icon, ip);
        title.setPadding(0, px(16), 0, px(4)); card.addView(title); card.addView(sub);
        meta.setPadding(0, px(8), 0, 0); card.addView(meta);
        desc.setPadding(px(4), px(18), px(4), px(6)); desc.setLineSpacing(0, 1.2f); card.addView(desc);
        // action area: big pill button, or progress ring + status while busy
        action.setGravity(Gravity.CENTER); action.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, px(60)); ap.topMargin = px(22);
        card.addView(action, ap);
        button.setGravity(Gravity.CENTER);
        GradientDrawable pb = new GradientDrawable(); pb.setColor(FG); pb.setCornerRadius(px(30)); button.setBackground(pb);
        button.setOnClickListener(v -> onAction());
        action.addView(button, new LinearLayout.LayoutParams(-1, -1));
        ring.setVisibility(GONE); status.setVisibility(GONE); status.setPadding(px(14), 0, 0, 0);
        action.addView(ring, new LinearLayout.LayoutParams(px(44), px(44)));
        action.addView(status, new LinearLayout.LayoutParams(-2, -2));
        LayoutParams cp = new LayoutParams(-1, -2, Gravity.BOTTOM); addView(card, cp);
    }

    int px(float v) { return (int) (v * d); }
    TextView t(float size, int color, boolean bold) {
        TextView v = new TextView(getContext()); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v;
    }

    void show(Stores.App a, android.graphics.Bitmap bm, String source, String btn) {
        app = a; busy = false;
        if (bm != null) icon.setImageBitmap(bm); else icon.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xFF1C1C20));
        title.setText(a.title); sub.setText(source);
        StringBuilder m = new StringBuilder();
        if (a.rating != null) m.append(a.rating.startsWith("v") ? a.rating : "★ " + a.rating);
        if (a.size > 0) m.append(m.length() > 0 ? "  ·  " : "").append(fmt(a.size));
        m.append(m.length() > 0 ? "  ·  " : "").append(a.pkg);
        meta.setText(m); desc.setText(a.subtitle == null ? "" : a.subtitle); desc.setVisibility(a.subtitle == null || a.subtitle.isEmpty() ? GONE : VISIBLE);
        idle(btn);
        setVisibility(VISIBLE);
        card.setTranslationY(px(420)); animate().alpha(1f).setDuration(180).start();
        card.animate().translationY(0).setInterpolator(new DecelerateInterpolator(2.2f)).setDuration(320).start();
    }

    void hide() {
        card.animate().translationY(card.getHeight()).setDuration(220).start();
        animate().alpha(0f).setDuration(220).withEndAction(() -> setVisibility(GONE)).start();
    }

    static String fmt(long b) { return b >= 1048576 ? String.format(java.util.Locale.US, "%.1f МБ", b / 1048576f) : (b / 1024) + " КБ"; }

    void idle(String btn) {
        busy = false; button.setText(btn); button.setVisibility(VISIBLE); ring.setVisibility(GONE); status.setVisibility(GONE);
    }

    void onAction() {
        String b = button.getText().toString();
        if (b.equals("Открыть")) { actions.open(app); hide(); }
        else if (b.equals("Google Play")) { actions.store(app, true); hide(); }
        else if (b.equals("RuStore")) { actions.store(app, false); hide(); }
        else actions.install(app);
    }

    /** progress: 0..1 while downloading, -1 while the system installs. */
    void progress(float p, String text) {
        busy = true; button.setVisibility(GONE); ring.setVisibility(VISIBLE); status.setVisibility(VISIBLE);
        ring.set(p); status.setText(text);
    }

    void done(boolean ok, String text) {
        busy = false;
        if (ok) { ring.success(); status.setText(text); postDelayed(() -> idle("Открыть"), 900); }
        else { idle("Повторить"); status.setVisibility(VISIBLE); status.setText(text); action.removeView(status); card.addView(status); }
    }

    /** Ring: determinate arc while downloading, orbiting dot while installing, check mark on success. */
    static final class Progress extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); float value = 0, shown = 0, angle = -90; boolean ok; long last;
        Progress(Context c) { super(c); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        void set(float v) {
            if (v >= 0 && value >= 0) { ValueAnimator a = ValueAnimator.ofFloat(shown, v); a.setDuration(200); a.addUpdateListener(x -> { shown = (float) x.getAnimatedValue(); invalidate(); }); a.start(); }
            value = v; ok = false; last = System.currentTimeMillis(); invalidate();
        }
        void success() { ok = true; shown = 1; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), r = w * 0.4f, cx = w / 2, cy = getHeight() / 2f;
            p.setStrokeWidth(w * 0.09f); p.setColor(0xFF2A2A2D); p.setStyle(Paint.Style.STROKE); c.drawCircle(cx, cy, r, p);
            if (ok) {
                p.setColor(GREEN); c.drawCircle(cx, cy, r, p);
                Path k = new Path(); k.moveTo(cx - r * 0.42f, cy); k.lineTo(cx - r * 0.1f, cy + r * 0.32f); k.lineTo(cx + r * 0.46f, cy - r * 0.3f);
                c.drawPath(k, p); return;
            }
            p.setColor(FG);
            if (value >= 0) c.drawArc(cx - r, cy - r, cx + r, cy + r, -90, 360 * shown, false, p);
            else {   // installing: the Angel dot orbits
                long now = System.currentTimeMillis(); angle += (now - last) * 0.4f; last = now;
                c.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 70, false, p); postInvalidateOnAnimation();
            }
        }
    }
}
