package caller.alarmist;

import android.app.PendingIntent;

import com.getpebble.android.kit.util.PebbleDictionary;

import java.util.Date;

import caller.alarmist.AlarmClockWatcher.AlarmState;

/**
 * Created by Ben on 29/12/2015.
 */
public class Alarm {
    private static final int KEY_NAME = 182;
    public static final int KEY_TIME = 196;
    public static final int KEY_CAN_DISMISS = 116;

    public String title;
    public final long time;
    public AlarmState state;
    // --Commented out by Inspection (30/12/2015 20:57):public PendingIntent snooze;
    public PendingIntent dismiss;

    public Alarm(long t) {
        this.time = t;
        this.state = AlarmState.UNKNOWN;
    }

    public Alarm(long t, String name, AlarmState as) {
        this(t);
        this.title = name;
        this.state = as;
    }

    @Override
    public String toString() {
        return String.format("%s : %s %s", title, new Date(time), state);
    }

    public PebbleDictionary toPebbleDict() {
        PebbleDictionary dict = new PebbleDictionary();
        if(title != null)
            dict.addString(KEY_NAME, title);
        dict.addInt32(KEY_TIME, pebbleTime());
        dict.addInt8(KEY_CAN_DISMISS, dismiss != null ? (byte)1 : 0);
        return dict;
    }

    public int pebbleTime() {
        return (int) (time / 1000 / 60);
    }
}
