package caller.alarmist;

import android.content.BroadcastReceiver;
import android.content.ContextWrapper;

/**
 * Created by Ben on 02/09/2016.
 */
public final class ReceiverHelper {
    public static void tryUnregister(ContextWrapper ctx, BroadcastReceiver broadcastReceiver) {
        try {
            ctx.unregisterReceiver(broadcastReceiver);
        } catch (IllegalArgumentException ignored) {}
    }
}
