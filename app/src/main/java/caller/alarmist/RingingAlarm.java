package caller.alarmist;

import android.app.PendingIntent;
import android.util.Log;

/**
 * Created by Ben on 11/04/2016.
 */
public class RingingAlarm {
    private boolean active;
    private PendingIntent snoozeIntent;
    private PendingIntent dismissIntent;
    private int id;
    private static boolean LOGGING = false;

    public RingingAlarm(int id, PendingIntent snooze, PendingIntent dismiss) {
        this.id = id;
        this.snoozeIntent = snooze;
        this.dismissIntent = dismiss;
        this.active = true;
    }

    public void dismiss() {
        if(!active) return;
        try {
            dismissIntent.send();
            if(LOGGING) Log.v("RA", "We have a dismiss command");
        } catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
        }
        active = false;
    }

    public void snooze() {
        if(!active) return;
        try {
            snoozeIntent.send();
            if(LOGGING) Log.v("RA", "We have a snooze command");
        } catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
        }
        active = false;
    }

    public boolean isActive() {
        return active;
    }
    
    public int getId() {
        return id;
    }

}
