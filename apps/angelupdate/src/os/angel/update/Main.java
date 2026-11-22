package os.angel.update;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import org.json.JSONObject;

/**
 * Angel OS system updates. Asks our server for a delta from the running version,
 * downloads it into /cache/recovery/angel-ota, verifies it, then reboots into the
 * Angel recovery, which checks the system, applies the changed chunks and boots back.
 */
public class Main extends Activity {
    /** Angel OS server, set per build in /system/build.prop (ro.angel.server). */
    static final String API = sysprop("ro.angel.server", "");
    static String sysprop(String k, String def) {
        try { String v = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, k, def); return v.isEmpty() ? def : v; }
        catch (Exception e) { return def; }
    }
    static final File DIR = new File("/cache/recovery/angel-ota");
    static final int BG = 0xFF08080A, CARD = 0xFF16161A, FG = 0xFFF1EFE8, MUTED = 0xFF8A8883, GREEN = 0xFF7BD88F;

    final Handler ui = new Handler(Looper.getMainLooper());
    TextView title, info, notes, button; Ring ring; JSONObject offer; boolean busy;

    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    static String prop(String k, String def) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, k, def); }
        catch (Exception e) { return def; }
    }
    static int current() { try { return Integer.parseInt(prop("ro.angel.version_code", "0")); } catch (NumberFormatException e) { return 0; } }
    static String currentName() { return prop("ro.lineage.display.version", "Angel OS"); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL); col.setBackgroundColor(BG); col.setPadding(dp(24), dp(56), dp(24), dp(24));
        ring = new Ring(this); col.addView(ring, new LinearLayout.LayoutParams(dp(140), dp(140)));
        title = text(currentName(), 26, FG, true); title.setPadding(0, dp(28), 0, dp(6)); col.addView(title);
        info = text("Сборка " + current(), 15, MUTED, false); col.addView(info);
        notes = text("", 14, FG, false); notes.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(20)); notes.setBackground(g);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.topMargin = dp(28); notes.setVisibility(View.GONE);
        col.addView(notes, np);
        View spacer = new View(this); col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        button = text("Проверить обновления", 16, 0xFF0B0B0D, true); button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(FG); bg.setCornerRadius(dp(26)); button.setBackground(bg);
        button.setPadding(0, dp(16), 0, dp(16)); col.addView(button, new LinearLayout.LayoutParams(-1, -2));
        button.setOnClickListener(v -> onButton());
        setContentView(col);
        check();
    }

    TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    void onButton() {
        if (busy) return;
        if (offer == null) check(); else download();
    }

    void say(String status, String btn) { ui.post(() -> { info.setText(status); if (btn != null) button.setText(btn); }); }

    void check() {
        busy = true; ring.spin(true); say("Проверяю обновления…", "Проверяю…");
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(new String(get(API + "/api/ota?current=" + current()), "UTF-8"));
                ui.post(() -> {
                    busy = false; ring.spin(false);
                    if (!o.optBoolean("available")) { offer = null; info.setText("Сборка " + current() + " · установлена последняя версия"); button.setText("Проверить снова"); notes.setVisibility(View.GONE); return; }
                    offer = o;
                    info.setText("Доступно: " + o.optString("versionName") + " · " + (o.optLong("size") / 1048576 + 1) + " МБ");
                    notes.setText(o.optString("changelog")); notes.setVisibility(View.VISIBLE);
                    button.setText("Скачать и установить");
                });
            } catch (Throwable e) { ui.post(() -> { busy = false; ring.spin(false); info.setText("Сервер обновлений недоступен"); button.setText("Повторить"); }); }
        }).start();
    }

    void download() {
        busy = true; ring.spin(true);
        new Thread(() -> {
            try {
                if (!DIR.exists() && !DIR.mkdirs()) throw new IllegalStateException("no /cache access");
                for (File f : DIR.listFiles()) f.delete();
                String[][] files = { { "payload", "payload.bin" }, { "manifest", "manifest.txt" }, { "frames", "frames.tar" } };
                long total = offer.getLong("size"), doneBytes = 0;
                for (String[] f : files) {
                    JSONObject item = offer.getJSONObject(f[0]);
                    File dst = new File(DIR, f[1]);
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    HttpURLConnection c = (HttpURLConnection) new URL(API + "/ota/" + item.getString("file")).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(30000);
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dst)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n); md.update(buf, 0, n); doneBytes += n;
                            final int pct = (int) (doneBytes * 100 / Math.max(1, total));
                            ring.progress = pct / 100f; say("Скачиваю… " + pct + "%", "Скачиваю…");
                        }
                        out.getFD().sync();
                    }
                    StringBuilder hex = new StringBuilder(); for (byte x : md.digest()) hex.append(String.format("%02x", x));
                    if (!hex.toString().equals(item.getString("sha256"))) throw new SecurityException("checksum " + f[1]);
                }
                verifySignature(offer);   // refuses packages not signed by this build's key
                try (FileOutputStream m = new FileOutputStream(new File(DIR, "apply"))) {
                    m.write((offer.getInt("from") + " " + offer.getInt("versionCode") + "\n").getBytes()); m.getFD().sync();
                }
                say("Готово. Перезагрузка для установки…", "Перезагрузка…");
                Thread.sleep(1500);
                ((PowerManager) getSystemService(POWER_SERVICE)).reboot("recovery");
            } catch (Throwable e) {
                for (File f : DIR.listFiles() == null ? new File[0] : DIR.listFiles()) f.delete();
                ui.post(() -> { busy = false; ring.spin(false); ring.progress = -1; info.setText("Ошибка: " + e.getMessage()); button.setText("Повторить"); });
            }
        }).start();
    }

    /** The update must be signed with the key whose public half ships in this system. */
    static void verifySignature(JSONObject o) throws Exception {
        String pem = new String(java.nio.file.Files.readAllBytes(new File("/system/etc/angel/ota_key.pem").toPath()), "UTF-8")
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        java.security.PublicKey key = java.security.KeyFactory.getInstance("EC")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(pem, android.util.Base64.DEFAULT)));
        String msg = "angel-ota " + o.getInt("from") + " " + o.getInt("versionCode")
                + " payload=" + o.getJSONObject("payload").getString("sha256")
                + " manifest=" + o.getJSONObject("manifest").getString("sha256")
                + " frames=" + o.getJSONObject("frames").getString("sha256");
        java.security.Signature v = java.security.Signature.getInstance("SHA256withECDSA");
        v.initVerify(key); v.update(msg.getBytes("UTF-8"));
        if (o.isNull("sig") || !v.verify(android.util.Base64.decode(o.getString("sig"), android.util.Base64.DEFAULT)))
            throw new SecurityException("подпись обновления неверна — установка отменена");
    }

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toByteArray();
        } finally { c.disconnect(); }
    }

    /** Angel ring: spins while busy, fills as a progress arc while downloading. */
    static final class Ring extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean spinning; float angle = -45, progress = -1; long last;
        Ring(android.content.Context c) { super(c); p.setStyle(Paint.Style.STROKE); dot.setColor(FG); }
        void spin(boolean s) { spinning = s; last = System.currentTimeMillis(); postInvalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), r = w * 0.36f, cx = w / 2, cy = getHeight() / 2f;
            p.setStrokeWidth(r * 0.1f);
            if (progress >= 0) {
                p.setColor(0xFF2A2A2D); c.drawCircle(cx, cy, r, p);
                p.setColor(FG); c.drawArc(cx - r, cy - r, cx + r, cy + r, -90, 360 * progress, false, p);
                angle = -90 + 360 * progress;
            } else { p.setColor(FG); c.drawCircle(cx, cy, r, p); }
            if (spinning && progress < 0) { long now = System.currentTimeMillis(); angle += (now - last) * 0.36f; last = now; }
            if (spinning) postInvalidateOnAnimation();
            double a = Math.toRadians(angle); float dx = cx + r * (float) Math.cos(a), dy = cy + r * (float) Math.sin(a);
            dot.setColor(BG); c.drawCircle(dx, dy, r * 0.3f, dot); dot.setColor(FG); c.drawCircle(dx, dy, r * 0.2f, dot);
        }
    }
}
