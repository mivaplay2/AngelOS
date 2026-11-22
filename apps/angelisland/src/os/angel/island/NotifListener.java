package os.angel.island;

import android.app.Notification;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.List;

/** Feeds notifications and the active media session to the island. */
public class NotifListener extends NotificationListenerService {
    MediaSessionManager msm; MediaController current;
    final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { push(); }
        @Override public void onMetadataChanged(MediaMetadata m) { push(); }
        @Override public void onSessionDestroyed() { pick(null); }
    };
    final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;

    @Override public void onListenerConnected() {
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(this, NotifListener.class);
        try { msm.addOnActiveSessionsChangedListener(sessions, me); pick(msm.getActiveSessions(me)); } catch (SecurityException ignored) { }
    }

    @Override public void onListenerDisconnected() {
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
    }

    void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (best == null || (s != null && s.getState() == PlaybackState.STATE_PLAYING)) best = c;
        }
        if (current != null) current.unregisterCallback(cb);
        current = best;
        if (current != null) current.registerCallback(cb);
        push();
    }

    void push() {
        IslandService s = IslandService.instance;
        if (s == null) return;
        if (current == null) { s.media(null, "", "", null, false); return; }
        MediaMetadata m = current.getMetadata();
        PlaybackState st = current.getPlaybackState();
        String title = m == null ? "" : String.valueOf(m.getText(MediaMetadata.METADATA_KEY_TITLE));
        CharSequence a = m == null ? null : m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        Bitmap art = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        s.media(current, title, a == null ? "" : a.toString(), art, playing);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        IslandService s = IslandService.instance;
        if (s == null) return;
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (pkg.equals(getPackageName()) || pkg.equals("android") || pkg.equals("com.android.systemui")) return;
        if (n.category != null && (n.category.equals(Notification.CATEGORY_TRANSPORT) || n.category.equals(Notification.CATEGORY_PROGRESS))) return;
        Bundle x = n.extras;
        CharSequence t = x.getCharSequence(Notification.EXTRA_TITLE), b = x.getCharSequence(Notification.EXTRA_TEXT);
        if (t == null && b == null) return;
        Drawable icon = null;
        try { icon = getPackageManager().getApplicationIcon(pkg); } catch (Exception ignored) { }
        s.notif(t == null ? "" : t.toString(), b == null ? "" : b.toString(), icon);
    }
}
