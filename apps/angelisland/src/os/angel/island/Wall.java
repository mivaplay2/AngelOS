package os.angel.island;

import android.app.Activity;
import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;

/** Sets Angel OS wallpapers: am start -n os.angel.island/.Wall --es home PATH --es lock PATH */
public class Wall extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        WallpaperManager wm = WallpaperManager.getInstance(this);
        try {
            String home = getIntent().getStringExtra("home"), lock = getIntent().getStringExtra("lock");
            if (home != null) { Bitmap bm = BitmapFactory.decodeFile(home); wm.setBitmap(bm, null, true, WallpaperManager.FLAG_SYSTEM); }
            if (lock != null) { Bitmap bm = BitmapFactory.decodeFile(lock); wm.setBitmap(bm, null, true, WallpaperManager.FLAG_LOCK); }
            Log.i("AngelWall", "wallpapers set");
        } catch (Exception e) { Log.e("AngelWall", "failed", e); }
        finish();
    }
}
