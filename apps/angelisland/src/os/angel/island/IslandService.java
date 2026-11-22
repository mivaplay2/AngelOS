package os.angel.island;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/** Hosts the island window above the status bar (only accessibility overlays may draw there). */
public class IslandService extends AccessibilityService implements IslandView.Host {
    static IslandService instance;
    final Handler ui = new Handler(Looper.getMainLooper());
    WindowManager wm; WindowManager.LayoutParams lp; IslandView view;
    MediaController media; String mediaTitle = "", mediaArtist = ""; Bitmap mediaArt; boolean mediaPlaying;
    final Runnable settle = this::settle;

    final BroadcastReceiver power = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_POWER_CONNECTED.equals(i.getAction())) {
                Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int lvl = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                show("charge", "Зарядка", lvl + "%", null, IslandView.COMPACT, 3500);
                view.right = lvl + "%";
            }
        }
    };

    @Override protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        view = new IslandView(this, this);
        try {   // size the idle island to the real camera cutout
            android.view.DisplayCutout cut = wm.getDefaultDisplay().getCutout();
            if (cut != null && !cut.getBoundingRectTop().isEmpty()) {
                view.cutW = cut.getBoundingRectTop().width() / view.d; view.cutH = cut.getBoundingRectTop().height() / view.d;
                view.w = view.tw = view.idleW(); view.h = view.th = view.idleH();
            }
        } catch (Exception ignored) { }
        lp = new WindowManager.LayoutParams(
                (int) view.idleW(), (int) view.idleH(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = 0;   // attached to the top edge so the camera sits inside the island
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.setTitle("AngelIsland");
        wm.addView(view, lp);
        IntentFilter f = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        registerReceiver(power, f);
        applyOrientation(getResources().getConfiguration());
    }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); applyOrientation(c); }
    void applyOrientation(Configuration c) {
        if (view != null) view.setVisibility(c.orientation == Configuration.ORIENTATION_LANDSCAPE ? View.GONE : View.VISIBLE);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        instance = null;
        try { unregisterReceiver(power); } catch (Exception ignored) { }
        if (view != null) try { wm.removeView(view); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** Show an activity on the island for a while, then fall back to music or idle. */
    void show(String kind, String l1, String l2, Drawable icon, int mode, long ms) {
        ui.post(() -> {
            if (view == null) return;
            view.kind = kind; view.line1 = l1; view.line2 = l2; view.icon = icon; view.art = null;
            view.right = l2;
            view.morph(mode);
            ui.removeCallbacks(settle);
            if (ms > 0) ui.postDelayed(settle, ms);
        });
    }

    void notif(String l1, String l2, Drawable icon) { show("notif", l1, l2, icon, IslandView.EXPANDED, 4200); }

    void media(MediaController c, String title, String artist, Bitmap art, boolean playing) {
        ui.post(() -> {
            media = c; mediaTitle = title; mediaArtist = artist; mediaArt = art; mediaPlaying = playing;
            if (view == null) return;
            boolean busy = view.kind.equals("notif") || view.kind.equals("charge");
            if (view.kind.equals("media") || !busy) settle();
        });
    }

    void settle() {
        if (view == null) return;
        if (media != null && (mediaPlaying || view.kind.equals("media") && view.mode == IslandView.EXPANDED)) {
            boolean keepExpanded = view.kind.equals("media") && view.mode == IslandView.EXPANDED;
            view.kind = "media"; view.line1 = mediaTitle; view.line2 = mediaArtist; view.art = mediaArt; view.playing = mediaPlaying; view.icon = null;
            if (!keepExpanded) view.morph(IslandView.COMPACT); else view.invalidate();
        } else {
            view.kind = ""; view.morph(IslandView.IDLE);
        }
    }

    @Override public void onSizeWanted(int w, int h) {
        if (lp == null || view == null) return;
        if (lp.width == w && lp.height == h) return;
        lp.width = w; lp.height = h;
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
    }

    @Override public void onTap() {
        ui.removeCallbacks(settle);
        if (view.kind.equals("media")) {
            if (view.mode == IslandView.EXPANDED) view.morph(IslandView.COMPACT);
            else { view.morph(IslandView.EXPANDED); ui.postDelayed(() -> { if (view.mode == IslandView.EXPANDED) view.morph(IslandView.COMPACT); }, 8000); }
        } else if (view.mode == IslandView.IDLE) {
            if (media != null) { settle(); view.morph(IslandView.EXPANDED); return; }
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int lvl = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            boolean chg = b != null && b.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING;
            String time = new java.text.SimpleDateFormat("HH:mm · EEE, d MMMM", new java.util.Locale("ru")).format(new java.util.Date());
            show("info", time, "Заряд " + lvl + "%" + (chg ? " · заряжается" : "") + "  ·  Angel OS", null, IslandView.EXPANDED, 4500);
        } else settle();
    }

    @Override public void onControl(int which) {
        if (media == null) return;
        MediaController.TransportControls t = media.getTransportControls();
        if (which == 0) t.skipToPrevious(); else if (which == 2) t.skipToNext(); else if (mediaPlaying) t.pause(); else t.play();
    }
}
