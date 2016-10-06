package caller.alarmist;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;

import java.util.Map;
import java.util.UUID;

/**
 * Created by Ben on 11/04/2016.
 */
public class RingingAlarm {
    private static final String TAG = "AlarmistRingingAlarm";
    public static final UUID WATCHAPP_UUID = UUID.fromString("5cfa8e91-5c31-4287-9b57-e0f14ae72b34");
    private static boolean LOGGING = false;
    protected static final String SNOOZE_INTENT_ACTION = "caller.alarmist.SNOOZE_PEBBLE_ACTION";
    protected static final String SNOOZE_INTENT_EXTRA = "SNOOZE_PENDING";
    private static final int KEY_VIBRATION = 0;
    private static final byte VIBRATION_CANCEL = 0;
    private static final byte VIBRATION_START = 1;

    private final FinalInt tId;
    private RingingAlarmState state;
    private AlarmType type;
    private Notification deskClockNotification;
    private int id;
    private String notificationKey = null;
    private String preNotificationKey = null;

    public RingingAlarm(int id, AlarmType type, Notification deskClockNotification, FinalInt tId) {
        this.id = id;
        this.deskClockNotification = deskClockNotification;
        this.state = RingingAlarmState.INITIAL;
        this.type = type;
        this.tId = tId;

        final CharSequence title = deskClockNotification.extras.getCharSequence(Notification.EXTRA_TITLE);
        final CharSequence message = deskClockNotification.extras.getCharSequence(Notification.EXTRA_TEXT);
        preNotificationKey = alarmKeyPreNotification(title, message, deskClockNotification.when);
    }

    public RingingAlarm(int id, AlarmType type, Notification deskClockNotification, FinalInt tId, CharSequence title, CharSequence message) {
        this.id = id;
        this.deskClockNotification = deskClockNotification;
        this.state = RingingAlarmState.INITIAL;
        this.type = type;
        this.tId = tId;
        preNotificationKey = alarmKeyPreNotification(title, message, deskClockNotification.when);
    }

    public void launchApp(Context ctx) {
        if(this.state == RingingAlarmState.INITIAL || this.state == RingingAlarmState.LAUNCHING_VIBRATING_APP) {
            if(LOGGING)Log.i(TAG, "Starting Pebble app for alarm");
            PebbleKit.startAppOnPebble(ctx, WATCHAPP_UUID);
            PebbleDictionary data = new PebbleDictionary();
            data.addInt8(KEY_VIBRATION, VIBRATION_START);
            PebbleKit.sendDataToPebbleWithTransactionId(ctx, WATCHAPP_UUID, data, tId.increment());
            this.state = RingingAlarmState.LAUNCHING_VIBRATING_APP;
        }
    }

    public String getAlarmKeyAfterNotificationSent() {
        return notificationKey;
    }

    public String getAlarmKeyBeforeNotificationSent() {
        return preNotificationKey;
    }

    public static String alarmKeyPreNotification(CharSequence title, CharSequence message, long when) {
        return String.format("%s%s%d", message, title, when);
    }

    public String showNotification(Context ctx) {
        if(this.state == RingingAlarmState.LAUNCHING_VIBRATING_APP || this.state == RingingAlarmState.INITIAL) {
            final CharSequence title = deskClockNotification.extras.getCharSequence(Notification.EXTRA_TITLE);
            final CharSequence message = deskClockNotification.extras.getCharSequence(Notification.EXTRA_TEXT);
            Intent snoozeIntent = new Intent(SNOOZE_INTENT_ACTION);
            snoozeIntent.putExtra(SNOOZE_INTENT_EXTRA, preNotificationKey);
            PendingIntent snoozePending = PendingIntent.getBroadcast(ctx, id, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            final Notification.WearableExtender wearableExtender;
            if(type == AlarmType.ALARM) {
                wearableExtender = new Notification.WearableExtender()
                        .addAction(new Notification.Action(R.drawable.stat_notify_alarm,
                        deskClockNotification.actions[0].title, //Snooze
                        snoozePending)); //Snooze Intent
            } else if(deskClockNotification.actions.length == 2) {
                //Timer [STOP] ([ADD 1 MIN])
                wearableExtender = new Notification.WearableExtender()
                        .addAction(new Notification.Action(R.drawable.stat_notify_alarm,
                        deskClockNotification.actions[1].title, //Add 1 min
                        snoozePending)); //Snooze Intent
            } else {
                // Timer [DISMISS]
                wearableExtender = new Notification.WearableExtender();
            }

            final CharSequence contentText = type == AlarmType.ALARM
                    ? ctx.getString(R.string.alarm_ringing_pebble_msg, title, message)
                    : message;
            final Notification newNotification = new Notification.Builder(ctx)
                    .setContentTitle(title)
                    .setContentText(contentText)
                    .setPriority(Notification.PRIORITY_MIN)
                    .extend(wearableExtender)
                    .setSmallIcon(R.drawable.stat_notify_alarm).build();
            NotificationManager notificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(id, newNotification);
            this.state = RingingAlarmState.NOTIFICATION_SENT;
            this.notificationKey = newNotification.when + "";
            return this.notificationKey;
        }
        return null;
    }

    public void destroy(Context ctx) {
        state = RingingAlarmState.DESTROYING;
        NotificationManager notificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(id);
        cancelPebbleVibration(ctx);
        deskClockNotification = null;
    }

    public void destroy(Context ctx, Map<String, RingingAlarm> dismissAlarmMap) {
        if(state != RingingAlarmState.DESTROYING)
            destroy(ctx);
        dismissAlarmMap.remove(this.getAlarmKeyAfterNotificationSent());
        dismissAlarmMap.remove(this.getAlarmKeyBeforeNotificationSent());
    }

    private void cancelPebbleVibration(Context ctx) {
        tId.increment();
        if(LOGGING)Log.d(TAG, "Cancelling Pebble vibration: " + tId.getValue());
        final PebbleDictionary data = new PebbleDictionary();
        data.addInt8(KEY_VIBRATION, VIBRATION_CANCEL);
        PebbleKit.sendDataToPebbleWithTransactionId(ctx, WATCHAPP_UUID, data, tId.getValue());
    }

    private PendingIntent getOriginalSnoozeIntent() {
        if(type == AlarmType.ALARM)
            return deskClockNotification.actions[0].actionIntent;
        // Timer: [STOP] ([ADD 1 MIN])
        if(deskClockNotification.actions.length == 2)
            return deskClockNotification.actions[1].actionIntent;
        return null;
    }

    private PendingIntent getOriginalDismissIntent() {
        if(type == AlarmType.ALARM)
            return deskClockNotification.actions[1].actionIntent;
        // TIMER
        return deskClockNotification.actions[0].actionIntent;
    }

    public void dismiss(Context ctx) {
        if(!isActive()) return;
        try {
            getOriginalDismissIntent().send();
            if(LOGGING) Log.v(TAG, "Applying dismiss command");
        } catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
        }
        destroy(ctx);
    }

    public void snooze(Context ctx) {
        if(!isActive()) return;
        try {
            if(getOriginalSnoozeIntent() != null)
                getOriginalSnoozeIntent().send();
            if(LOGGING) Log.v(TAG, "Applying snooze command");
        } catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
        }
        destroy(ctx);
    }

    public RingingAlarmState getState() {
        return state;
    }

    public boolean isActive() {
        return state != RingingAlarmState.DESTROYING;
    }

    public int getId() {
        return id;
    }

    public enum RingingAlarmState {
        INITIAL,
        LAUNCHING_VIBRATING_APP,
        NOTIFICATION_SENT,
        DESTROYING
    }

    public enum AlarmType {
        ALARM,
        TIMER
    }

}
