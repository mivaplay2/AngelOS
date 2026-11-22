package os.angel.store;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main extends Activity {
    static final int BG = 0xFF08080A, CARD = 0xFF16161A, FIELD = 0xFF1C1C20, FG = 0xFFF1EFE8,
            MUTED = 0xFF8A8883, FAINT = 0xFF4A4946, PLAY = 0xFF8FD3A8, RU = 0xFF8FB4FF;
    static final String PLAY_PKG = "com.android.vending", RU_PKG = "ru.vk.store";

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newCachedThreadPool(), img = Executors.newFixedThreadPool(3);
    final LruCache<String, Bitmap> icons = new LruCache<>(80);
    final List<Stores.App> items = new ArrayList<>();
    EditText search; TextView status; RingView ring; ListView list; Adapter adapter; View empty;
    int generation = 0;

    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(BG); w.setNavigationBarColor(BG);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(18), dp(20), 0);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        RingView logo = new RingView(this, 2.2f); head.addView(logo, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView title = text("Angel Store", 22, FG, true); title.setPadding(dp(12), 0, 0, 0);
        head.addView(title);
        col.addView(head);

        search = new EditText(this);
        search.setHint(installed(PLAY_PKG) ? "Поиск в Google Play, RuStore и F-Droid" : "Поиск в RuStore и F-Droid"); search.setHintTextColor(FAINT); search.setTextColor(FG);
        search.setTextSize(16); search.setSingleLine(true); search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(18), dp(14), dp(18), dp(14));
        search.setBackground(round(FIELD, dp(16)));
        search.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { run(); return true; }
            return false;
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = dp(18);
        col.addView(search, sp);

        status = text("", 13, MUTED, false); status.setPadding(dp(4), dp(12), 0, dp(6));
        col.addView(status);

        FrameLayout body = new FrameLayout(this);
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(this); list.setDivider(new android.graphics.drawable.ColorDrawable(0)); list.setDividerHeight(dp(10)); list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false); list.setPadding(0, 0, 0, dp(24));
        adapter = new Adapter(); list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout e = new LinearLayout(this); e.setOrientation(LinearLayout.VERTICAL); e.setGravity(Gravity.CENTER);
        ring = new RingView(this, 1f); e.addView(ring, new LinearLayout.LayoutParams(dp(120), dp(120)));
        TextView t1 = text("Все магазины в одном", 20, FG, true); t1.setGravity(Gravity.CENTER); t1.setPadding(0, dp(28), 0, dp(6));
        TextView t2 = text("Ищи сразу в Google Play и RuStore.\nУстановка идёт через сам магазин.", 14, MUTED, false); t2.setGravity(Gravity.CENTER);
        e.addView(t1); e.addView(t2);
        empty = e; body.addView(e, new FrameLayout.LayoutParams(-1, -1));
        list.setVisibility(View.GONE);
        instance = this;
        sheet = new InstallSheet(this, new InstallSheet.Actions() {
            public void install(Stores.App a) { Main.this.install(a); }
            public void open(Stores.App a) { Intent l = getPackageManager().getLaunchIntentForPackage(a.pkg); if (l != null) startActivity(l); }
            public void store(Stores.App a, boolean play) { Main.this.open(a, play); }
        });
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        loadCatalog();
    }

    TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }
    static GradientDrawable round(int color, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(r); return g; }

    void loadCatalog() {
        status.setText("Приложения Angel");
        net.execute(() -> { try { List<Stores.App> c = Stores.catalog(); ui.post(() -> {
            if (!items.isEmpty() && !items.get(0).angel) return;
            items.clear(); items.addAll(c); adapter.notifyDataSetChanged();
            empty.setVisibility(View.GONE); list.setVisibility(View.VISIBLE); }); }
            catch (Throwable e) { ui.post(() -> status.setText("Каталог Angel недоступен")); } });
    }

    int installedCode(String pkg) {
        try { return getPackageManager().getPackageInfo(pkg, 0).versionCode; } catch (PackageManager.NameNotFoundException e) { return -1; }
    }

    static Main instance;
    InstallSheet sheet;
    String installingPkg;

    /** Download (GitHub / mirror / f-droid.org), verify, install; progress goes to the sheet. */
    void install(Stores.App a) {
        installingPkg = a.pkg;
        sheet.progress(0, "Готовлю…");
        net.execute(() -> {
            try {
                if (a.angel) { /* urls come from the catalog */ }
                else {
                    try { Stores.fdroidDirect(a); }
                    catch (Exception direct) { Stores.fdroidFetch(a); a.urls = new String[] { Stores.API + "/fdroid/" + a.file }; }
                }
                java.io.File f = new java.io.File(getCacheDir(), "dl.apk");
                final long[] lastUi = {0};
                String sha = Stores.download(a.urls, f, (done, total) -> {
                    long now = System.currentTimeMillis(); if (now - lastUi[0] < 120) return; lastUi[0] = now;
                    float p = total > 0 ? done / (float) total : 0;
                    String txt = total > 0 ? "Скачиваю " + (int) (p * 100) + "%  ·  " + InstallSheet.fmt(done) + " из " + InstallSheet.fmt(total) : "Скачиваю " + InstallSheet.fmt(done);
                    ui.post(() -> sheet.progress(p, txt));
                });
                if (a.sha256 != null && !sha.equals(a.sha256)) { f.delete(); ui.post(() -> sheet.done(false, "Файл повреждён — установка отменена")); return; }
                ui.post(() -> sheet.progress(-1, "Устанавливаю…"));
                android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
                android.content.pm.PackageInstaller.SessionParams sp = new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(a.pkg);
                int id = pi.createSession(sp);
                try (android.content.pm.PackageInstaller.Session s = pi.openSession(id)) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f);
                         java.io.OutputStream o = s.openWrite("base.apk", 0, f.length())) {
                        byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                        s.fsync(o);
                    }
                    Intent cb = new Intent(this, InstallReceiver.class).putExtra("pkg", a.pkg);
                    android.app.PendingIntent p = android.app.PendingIntent.getBroadcast(this, id, cb, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                    s.commit(p.getIntentSender());
                }
                f.delete();
            } catch (Throwable e) { android.util.Log.e("AngelStore", "install", e); ui.post(() -> sheet.done(false, "Не удалось: " + e.getMessage())); }
        });
    }

    /** Called by InstallReceiver with the system installer's verdict. */
    void onInstallResult(String pkg, boolean ok, String msg) {
        ui.post(() -> {
            if (sheet != null && sheet.app != null && pkg != null && pkg.equals(sheet.app.pkg)) sheet.done(ok, ok ? "Установлено" : "Ошибка установки: " + msg);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    void showSheet(Stores.App a) {
        if (a.card) { open(a, true); return; }
        String src = a.angel ? "Angel OS" : (a.inFd() && !a.inPlay() && !a.inRu() ? "F-Droid"
                : (a.inPlay() && a.inRu() ? "Google Play · RuStore" : a.inPlay() ? "Google Play" : a.inRu() ? "RuStore" : "F-Droid"));
        if (a.inFd() && (a.inPlay() || a.inRu())) src += " · F-Droid";
        int have = installedCode(a.pkg);
        String btn;
        if (a.angel) btn = have < 0 ? "Установить" : (have < a.versionCode ? "Обновить" : "Открыть");
        else if (have >= 0) btn = "Открыть";
        else if (a.inFd()) btn = "Установить";
        else btn = a.inPlay() && installed(PLAY_PKG) ? "Google Play" : "RuStore";
        sheet.show(a, a.icon == null ? null : icons.get(a.icon), src, btn);
    }

    @Override public void onBackPressed() { if (sheet != null && sheet.getVisibility() == View.VISIBLE) sheet.hide(); else super.onBackPressed(); }

    @Override protected void onResume() { super.onResume(); if (adapter != null) adapter.notifyDataSetChanged(); }

    void run() {
        String q = search.getText().toString().trim();
        if (q.isEmpty()) return;
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
        final int gen = ++generation;
        status.setText("Ищу «" + q + "»…"); ring.setSpinning(true);
        if (items.isEmpty()) { empty.setVisibility(View.VISIBLE); list.setVisibility(View.GONE); }
        final List<Stores.App>[] got = new List[3]; final boolean[] fin = new boolean[3]; final boolean[] shown = {false};
        Runnable finish = () -> {
            if (gen != generation || shown[0] || !(fin[0] && fin[1] && fin[2])) return;
            shown[0] = true;
            List<Stores.App> merged = Stores.merge(got[0] == null ? new ArrayList<>() : got[0], got[1] == null ? new ArrayList<>() : got[1], got[2] == null ? new ArrayList<>() : got[2]);
            boolean playOk = got[0] != null && !got[0].isEmpty();
            if (!playOk && installed(PLAY_PKG)) {   // play.google.com unreachable: hand the query to the Play app
                Stores.App c = new Stores.App(); c.card = true; c.query = q; c.playRank = 0;
                c.title = "Искать «" + q + "» в Google Play"; c.subtitle = "Откроется поиск в приложении Play Маркет";
                merged.add(0, c);
            }
            items.clear(); items.addAll(merged); adapter.notifyDataSetChanged(); list.setSelection(0);
            ring.setSpinning(false);
            int p = 0, r = 0, fdc = 0; for (Stores.App a : merged) { if (a.card) continue; if (a.inPlay()) p++; if (a.inRu()) r++; if (a.inFd()) fdc++; }
            String s = (!installed(PLAY_PKG) ? "" : (playOk ? "Google Play: " + p : "Google Play: через приложение") + "  ·  ") + "RuStore: " + (got[1] == null ? "—" : r) + "  ·  F-Droid: " + (got[2] == null ? "—" : fdc);
            status.setText(s);
            empty.setVisibility(merged.isEmpty() ? View.VISIBLE : View.GONE);
            list.setVisibility(merged.isEmpty() ? View.GONE : View.VISIBLE);
        };
        if (!installed(PLAY_PKG)) fin[0] = true;   // Google-free build: no Play results at all
        else net.execute(() -> { try { List<Stores.App> r = Stores.playApi(q); ui.post(() -> { got[0] = r; fin[0] = true; finish.run(); }); }
            catch (Throwable ex) { android.util.Log.w("AngelStore", "play: " + ex); ui.post(() -> { fin[0] = true; finish.run(); }); } });
        net.execute(() -> { try { List<Stores.App> r = Stores.rustore(q); ui.post(() -> { got[1] = r; fin[1] = true; finish.run(); }); }
            catch (Throwable ex) { android.util.Log.w("AngelStore", "rustore: " + ex); ui.post(() -> { fin[1] = true; finish.run(); }); } });
        net.execute(() -> { try { List<Stores.App> r = Stores.fdroid(q); ui.post(() -> { got[2] = r; fin[2] = true; finish.run(); }); }
            catch (Throwable ex) { ui.post(() -> { fin[2] = true; finish.run(); }); } });
        ui.postDelayed(() -> { fin[0] = true; fin[1] = true; fin[2] = true; finish.run(); }, 9000);   // never hang the screen
    }

    boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    void open(Stores.App a, boolean play) {
        Intent i;
        if (a.card) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?c=apps&q=" + Uri.encode(a.query)));
            if (installed(PLAY_PKG)) i.setPackage(PLAY_PKG);
            try { startActivity(i); } catch (ActivityNotFoundException ex) { status.setText("Play Маркет не найден"); }
            return;
        }
        if (play) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + a.pkg));
            if (installed(PLAY_PKG)) i.setPackage(PLAY_PKG);
        } else {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rustore.ru/catalog/app/" + a.pkg));
            if (installed(RU_PKG)) i.setPackage(RU_PKG);
            else status.setText("RuStore не установлен — открываю сайт");
        }
        try { startActivity(i); } catch (ActivityNotFoundException ex) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(play ? "https://play.google.com/store/apps/details?id=" + a.pkg : "https://www.rustore.ru/catalog/app/" + a.pkg)));
        }
    }

    Bitmap glyph;
    Bitmap playGlyph() {
        if (glyph != null) return glyph;
        int s = dp(58); glyph = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(glyph);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0x2E8FD3A8); c.drawRect(0, 0, s, s, p);
        p.setColor(PLAY); android.graphics.Path tri = new android.graphics.Path();
        tri.moveTo(s * 0.40f, s * 0.30f); tri.lineTo(s * 0.72f, s * 0.50f); tri.lineTo(s * 0.40f, s * 0.70f); tri.close();
        c.drawPath(tri, p); return glyph;
    }

    void loadIcon(String url, ImageView v) {
        v.setTag(url);
        Bitmap c = url == null ? null : icons.get(url);
        if (c != null) { v.setImageBitmap(c); return; }
        v.setImageDrawable(round(FIELD, dp(14)));
        if (url == null) return;
        img.execute(() -> {
            try {
                byte[] d = Stores.get(url);
                Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                if (bm == null) return;
                icons.put(url, bm);
                ui.post(() -> { if (url.equals(v.getTag())) v.setImageBitmap(bm); });
            } catch (Exception ignored) { }
        });
    }

    final class Adapter extends BaseAdapter {
        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int pos, View cv, ViewGroup parent) {
            Row r = cv instanceof Row ? (Row) cv : new Row(Main.this);
            r.bind(items.get(pos)); return r;
        }
    }

    final class Row extends LinearLayout {
        final ImageView icon = new ImageView(getContext());
        final TextView title = text("", 16, FG, true), sub = text("", 13, MUTED, false), meta = text("", 12, FAINT, false);
        final TextView bPlay = pill("Google Play", PLAY), bRu = pill("RuStore", RU), bFd = pill("F-Droid", 0xFFB7C7FF);
        Stores.App app; LayoutParams rp, fp;
        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(14), dp(14), dp(14));
            setBackground(round(CARD, dp(20)));
            setLayoutParams(new ListView.LayoutParams(-1, -2));
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new ViewOutlineProvider() { public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); } });
            addView(icon, new LayoutParams(dp(58), dp(58)));
            LinearLayout txt = new LinearLayout(c); txt.setOrientation(VERTICAL); txt.setPadding(dp(14), 0, 0, 0);
            title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
            sub.setMaxLines(1); sub.setEllipsize(TextUtils.TruncateAt.END);
            txt.addView(title); txt.addView(sub); txt.addView(meta);
            LinearLayout pills = new LinearLayout(c); pills.setPadding(0, dp(8), 0, 0);
            pills.addView(bPlay); rp = new LayoutParams(-2, -2); pills.addView(bRu, rp); fp = new LayoutParams(-2, -2); pills.addView(bFd, fp);
            txt.addView(pills);
            addView(txt, new LayoutParams(0, -2, 1));
            bPlay.setOnClickListener(v -> { if (!app.angel) open(app, true); else showSheet(app); });
            bRu.setOnClickListener(v -> open(app, false));
            bFd.setOnClickListener(v -> showSheet(app));
            setOnClickListener(v -> showSheet(app));
        }
        TextView pill(String s, int color) {
            TextView t = text(s, 12, color, true);
            t.setPadding(dp(12), dp(6), dp(12), dp(6));
            GradientDrawable g = round(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)), dp(12));
            t.setBackground(g); return t;
        }
        void bind(Stores.App a) {
            app = a;
            title.setText(a.title);
            sub.setText(a.subtitle == null ? "" : a.subtitle);
            String m = a.card ? "" : a.pkg; if (a.rating != null) m = "★ " + a.rating + "   " + m;
            if (!a.card && installed(a.pkg)) m = "Установлено   " + m;
            meta.setText(m);
            if (a.angel) {
                int have = installedCode(a.pkg);
                bPlay.setText(have < 0 ? "Установить" : (have < a.versionCode ? "Обновить" : "Открыть"));
                bPlay.setVisibility(VISIBLE); bRu.setVisibility(GONE); bFd.setVisibility(GONE);
                meta.setText(have < 0 ? "Angel OS  ·  " + (a.size / 1024) + " КБ" : "Angel OS  ·  установлено"); meta.setVisibility(VISIBLE);
                rp.leftMargin = 0; bRu.setLayoutParams(rp); loadIcon(a.icon, icon); return;
            }
            bPlay.setText("Google Play");
            bPlay.setVisibility(a.inPlay() ? VISIBLE : GONE);
            bRu.setVisibility(a.inRu() ? VISIBLE : GONE);
            rp.leftMargin = a.inPlay() ? dp(8) : 0; bRu.setLayoutParams(rp);
            bFd.setVisibility(a.inFd() ? VISIBLE : GONE); bFd.setText(installed(a.pkg) ? "Открыть" : "F-Droid");
            fp.leftMargin = (a.inPlay() || a.inRu()) ? dp(8) : 0; bFd.setLayoutParams(fp);
            if (a.card) { icon.setTag(null); icon.setImageBitmap(playGlyph()); } else loadIcon(a.icon, icon);
            meta.setVisibility(m.isEmpty() ? GONE : VISIBLE);
            ListView.LayoutParams lp = (ListView.LayoutParams) getLayoutParams();
            if (lp == null) setLayoutParams(new ListView.LayoutParams(-1, -2));
        }
    }

    /** The Angel OS mark: a ring with a satellite dot; the dot orbits while searching. */
    static final class RingView extends View {
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), cut = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float weight; boolean spinning; float angle = -45; long last;
        RingView(Context c, float weight) {
            super(c); this.weight = weight;
            ring.setStyle(Paint.Style.STROKE); ring.setColor(FG); dot.setColor(FG); cut.setColor(BG);
        }
        void setSpinning(boolean s) { spinning = s; last = System.currentTimeMillis(); if (!s) angle = -45; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.36f, cx = w / 2, cy = h / 2;
            ring.setStrokeWidth(r * 0.1f * weight);
            c.drawCircle(cx, cy, r, ring);
            if (spinning) { long now = System.currentTimeMillis(); angle += (now - last) * 0.36f; last = now; postInvalidateOnAnimation(); }
            double a = Math.toRadians(angle);
            float dx = cx + r * (float) Math.cos(a), dy = cy + r * (float) Math.sin(a), dr = r * 0.2f * (weight > 1 ? 1.25f : 1f);
            c.drawCircle(dx, dy, dr + r * 0.09f, cut);
            c.drawCircle(dx, dy, dr, dot);
        }
    }
}
