package os.angel.store;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Gets installer results; opens the system confirmation screen when the user must approve. */
public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(confirm); }
            return;
        }
        Main m = Main.instance;
        if (m != null) m.onInstallResult(i.getStringExtra("pkg"), st == PackageInstaller.STATUS_SUCCESS, String.valueOf(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
    }
}
