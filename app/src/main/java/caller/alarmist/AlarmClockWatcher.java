package caller.alarmist;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.AlarmClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Calendar.DAY_OF_WEEK;

//See https://android.googlesource.com/platform/packages/apps/DeskClock/+/master/src/com/android/deskclock/alarms/AlarmNotifications.java

public class AlarmClockWatcher extends NotificationListenerService {
    public static final UUID WATCHAPP_UUID = UUID.fromString("5cfa8e91-5c31-4287-9b57-e0f14ae72b34");
    static final short RECURSION_WEEKDAYS = 2;
    static final short RECURSION_EVERYDAY = 3;
    static final int KEY_NEW_ALARM = 2;
    static final String DESK_CLOCK = "com.google.android.deskclock";
    private static final String TAG = "AlarmistNLS";
    private static final int KEY_VIBRATION = 0;
    private static final byte VIBRATION_CANCEL = 0;
    private static final byte VIBRATION_START = 1;
    private static final int KEY_SEND_NEXT_ALARM = 1;
    private static final int KEY_HOUR = 21;
    private static final int KEY_MINUTE = 22;
    private static final int KEY_RECURSION = 23;
    private static final short RECURSION_NONE = 0;
    private static final short RECURSION_WEEKLY = 1;
    private final FinalInt tId = new FinalInt();
    private final PebbleKit.PebbleNackReceiver pebbleNackReceiver = new PebbleKit.PebbleNackReceiver(WATCHAPP_UUID) {
        @Override
        public void receiveNack(Context context, int transactionId) {
            Log.e(TAG, "Nacked " + transactionId);
        }
    };
    private List<PendingIntent> dismissAlarm;
    private Alarm nextAlarm;
    private Alarm unconfirmedNotificationAlarm;
    private boolean hasRetrievedExistingNotifications = false;
    private boolean sendingLocked = false;
    private Map<Long, Alarm> alarmByTrigger = new HashMap<>(8);
    private final BroadcastReceiver alarmManagerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkAlarmManager();
            Log.i(TAG, nextAlarm == null ? "no alarm" : nextAlarm.toString());
        }
    };
    private Map<Integer, Long> sbnIdToTriggerTime = new HashMap<>(8);
    private boolean always_notify = false;
    private final PebbleKit.PebbleDataReceiver pebbleDataReceiver = new PebbleKit.PebbleDataReceiver(WATCHAPP_UUID) {
        private boolean isKeyOK(int key, PebbleDictionary data) {
            try {
                return data.getInteger(key) == 1L;
            } catch (NullPointerException ignored) {
                return false;
            }
        }

        @Override
        public void receiveData(Context context, int transactionId, PebbleDictionary data) {
            Log.d(TAG, "Message from Pebble");

            refreshNotifications(null);

            if (isKeyOK(KEY_SEND_NEXT_ALARM, data)) {
                PebbleKit.sendAckToPebble(context, transactionId);
                checkAlarmManager();
                if (nextAlarm != null) {
                    sendAlarmUpdateToPebble(context);
                }
            } else if (isKeyOK(Alarm.KEY_CAN_DISMISS, data)) {
                PebbleKit.sendAckToPebble(context, transactionId);
                Log.i(TAG, "Dismissing alarm...");
                if (nextAlarm != null && nextAlarm.dismiss != null
                        && data.getInteger(Alarm.KEY_TIME) == nextAlarm.pebbleTime()) {
                    Log.i(TAG, "Dismissing alarm from Pebble");
                    try {
                        toast(getString(R.string.alarm_is_dismissed,
                                nextAlarm.title == null ? "" : nextAlarm.title) + " from Pebble");
                        nextAlarm.dismiss.send();
                    } catch (PendingIntent.CanceledException e) {
                        e.printStackTrace();
                    }
                }
            } else if (isKeyOK(KEY_NEW_ALARM, data)) {
                PebbleKit.sendAckToPebble(context, transactionId);
                Log.i(TAG, "New alarm...");
                Intent setAlarm = new Intent(AlarmClock.ACTION_SET_ALARM);
                final int hour = data.getInteger(KEY_HOUR).intValue();
                setAlarm.putExtra(AlarmClock.EXTRA_HOUR, hour);
                final int min = data.getInteger(KEY_MINUTE).intValue();
                setAlarm.putExtra(AlarmClock.EXTRA_MINUTES, min);
                final short recursion = data.getInteger(KEY_RECURSION).shortValue();
                Log.i(TAG, hour + ":" + min + " rep " + recursion);
                if (recursion != RECURSION_NONE) {
                    final ArrayList<Integer> days = new ArrayList<>(7);
                    if (recursion == RECURSION_EVERYDAY) {
                        for (int i = 1; i < 8; i++) {
                            //Calendar Saturday = 7
                            days.add(i);
                        }
                    } else {
                        Calendar now = Calendar.getInstance();
                        now.setTime(new Date());
                        final boolean tomorrow = now.get(Calendar.HOUR_OF_DAY) > hour || (now.get(Calendar.HOUR_OF_DAY) == hour && now.get(Calendar.MINUTE) > min);
                        if (tomorrow)
                            now.add(Calendar.DAY_OF_WEEK, 1);
                        if (recursion == RECURSION_WEEKLY) {
                            days.add(now.get(Calendar.DAY_OF_WEEK));
                        } else if (recursion == RECURSION_WEEKDAYS) {
                            if (now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY || now.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) {
                                //Weekend
                                days.add(Calendar.SUNDAY);
                                days.add(Calendar.SATURDAY);
                            } else {
                                //Weekdays
                                days.add(Calendar.MONDAY);
                                days.add(Calendar.TUESDAY);
                                days.add(Calendar.WEDNESDAY);
                                days.add(Calendar.THURSDAY);
                                days.add(Calendar.FRIDAY);
                            }
                        }
                    }
                    setAlarm.putExtra(AlarmClock.EXTRA_DAYS, days);
                }
                setAlarm.putExtra(AlarmClock.EXTRA_MESSAGE, "Alarmist: " + getString(R.string.alarm_alert_wake_up));
                setAlarm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(setAlarm);
            }
        }
    };
    final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            Log.d(TAG, "Pref changed: " + key);
            always_notify = sharedPreferences.getBoolean(getString(R.string.pref_notif_always), false);
        }
    };
    private SharedPreferences sharedPreferences;

    private void updateAlarm(Alarm alarm) {
        if(alarm != null)
            alarmByTrigger.put(alarm.time, alarm);

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo nextClock = alarmManager.getNextAlarmClock();
        if (nextClock != null) {
            final String creatorPackage = nextClock.getShowIntent().getCreatorPackage();
            if (creatorPackage != null && creatorPackage.equals(DESK_CLOCK)) {
                final long triggerTime = nextClock.getTriggerTime();

                if(alarmByTrigger.containsKey(triggerTime)) {
                    nextAlarm = alarmByTrigger.get(triggerTime);
                } else {
                    // triggerTime has changed
                    // we must respect the triggerTime
                    nextAlarm = new Alarm(triggerTime);
                }
            } else {
                Log.e(TAG, "Another app is messing with the alarm clock: " + creatorPackage);
                nextAlarm = alarm;
            }
        } else {
            nextAlarm = null;
        }

        if (nextAlarm != null) {
            Log.i(TAG, "Next alarm is now " + nextAlarm);
        }
        else
            Log.i(TAG, "No next alarm");

        sendAlarmUpdateToPebble(this);
    }

    public void checkAlarmManager() {
        updateAlarm(null);
    }

    private AlarmState alarmType(Notification notification) {
        final CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        final CharSequence message = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title != null) {
            if (title.equals(getString(R.string.alarm_alert_predismiss_title))) {
                return AlarmState.UPCOMING;
            } else if (notification.actions.length == 2
                    && notification.actions[0].title.equals(getString(R.string.alarm_alert_snooze_text))
                    && notification.actions[1].title.equals(getString(R.string.alarm_alert_dismiss_text))) {
                return AlarmState.RINGING;
            } else if (notification.actions.length == 1 && notification.actions[0].title.equals(getString(R.string.alarm_alert_dismiss_text))) {
                return AlarmState.SNOOZED;
            }
        }
        return AlarmState.UNKNOWN;
    }

    private Date parseDate(String formattedTime) {
        final String skeleton = DateFormat.is24HourFormat(this) ? "EHm" : "Ehma";
        final String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.getDefault());
        try {
            Calendar now = Calendar.getInstance();
            now.setTime(new Date());
            Calendar parsedDayAndTime = Calendar.getInstance();
            parsedDayAndTime.setTime(format.parse(formattedTime));
            // parsing "Tue 09:23" --> Jan 06 1970, so move to the next / current Tuesday
            if (now.get(DAY_OF_WEEK) == parsedDayAndTime.get(DAY_OF_WEEK)) {
                parsedDayAndTime.set(Calendar.DAY_OF_YEAR, now.get(Calendar.DAY_OF_YEAR));
                parsedDayAndTime.set(Calendar.YEAR, now.get(Calendar.YEAR));
                if (now.getTimeInMillis() > parsedDayAndTime.getTimeInMillis()) {
                    now.add(Calendar.DAY_OF_MONTH, 7);
                }
            } else {
                now.add(Calendar.DAY_OF_MONTH, parsedDayAndTime.get(DAY_OF_WEEK) - now.get(DAY_OF_WEEK));
            }
            now.set(Calendar.HOUR_OF_DAY, parsedDayAndTime.get(Calendar.HOUR_OF_DAY));
            now.set(Calendar.MINUTE, parsedDayAndTime.get(Calendar.MINUTE));
            now.set(Calendar.SECOND, parsedDayAndTime.get(Calendar.SECOND));
            now.set(Calendar.MILLISECOND, parsedDayAndTime.get(Calendar.MILLISECOND));
            return now.getTime();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void sendAlarmUpdateToPebble(Context context) {
        if (!sendingLocked && PebbleKit.isWatchConnected(context)) {
            tId.increment();
            if (nextAlarm != null) {
                final PebbleDictionary pebbleTuples = nextAlarm.toPebbleDict();
                Log.d(TAG, "Sending alarm to Pebble: " + tId.getValue() + " : " + nextAlarm);
                PebbleKit.sendDataToPebbleWithTransactionId(context, WATCHAPP_UUID, pebbleTuples, tId.getValue());
            } else {
                Log.v(TAG, "Sending no alarm to Pebble: " + tId.getValue());
                final PebbleDictionary empty = new PebbleDictionary();
                empty.addInt8(KEY_SEND_NEXT_ALARM, (byte) 0);
                PebbleKit.sendDataToPebbleWithTransactionId(context, WATCHAPP_UUID, empty, tId.getValue());
            }
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "Alarmist running");
        dismissAlarm = new ArrayList<>(5);
        IntentFilter filter = new IntentFilter("android.app.action.NEXT_ALARM_CLOCK_CHANGED");
        this.registerReceiver(alarmManagerReceiver, filter);
        PebbleKit.registerReceivedDataHandler(this, pebbleDataReceiver);
        PebbleKit.registerReceivedNackHandler(this, pebbleNackReceiver);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "Alarmist bound");
        return super.onBind(intent);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(alarmManagerReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(pebbleDataReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(pebbleNackReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    private void toast(String msg) {
        final String message = msg;
        new Handler(AlarmClockWatcher.this.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(AlarmClockWatcher.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        final String packageName = sbn.getPackageName();
        Log.v(TAG, "**********  onNotificationPosted " + packageName);

        if(!hasRetrievedExistingNotifications) sendingLocked = true;

        if (packageName.equals(DESK_CLOCK)) {
            final Notification notification = sbn.getNotification();
            final CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            final CharSequence message = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            Log.i(TAG, "ID :" + sbn.getId() + "\t["
                    + title
                    + "]\t[" + message + "]\t" + packageName);
            AlarmState type = alarmType(notification);
            if (type == AlarmState.UPCOMING) {// Upcoming alarm
                final String[] parts = message.toString().split(" - ", 2);
                final String formattedTime = parts[0];
                String alarmTitle = null;
                if (parts.length > 1)
                    alarmTitle = parts[1];
                final Date alarmTime = parseDate(formattedTime);
                if (alarmTime != null) {
                    Log.d(TAG, "Upcoming at " + alarmTime.toString() + (alarmTitle != null ? " named " + alarmTitle : ""));
                    Alarm alarm = new Alarm(alarmTime.getTime(), alarmTitle, type);
                    alarm.dismiss = notification.actions[0].actionIntent;
                    unconfirmedNotificationAlarm = alarm;
                    sbnIdToTriggerTime.put(sbn.getId(), alarm.time);
                    updateAlarm(alarm);
                }
            } else if (type == AlarmState.SNOOZED) {
                final String alarmTitle = title.toString();
                final String replace = getString(R.string.alarm_alert_snooze_until, "(.+)");
                Pattern snoozePattern = Pattern.compile(replace);
                final Matcher matcher = snoozePattern.matcher(message);
                if (matcher.find()) {
                    final String formattedTime = matcher.group(1);
                    final Date alarmTime = parseDate(formattedTime);
                    if (alarmTime != null) {
                        int minutes = Math.round(((float) (alarmTime.getTime() - new Date().getTime())) / (1000 * 60));
                        Log.d(TAG, "Snooze for " + minutes + " mins, until " + alarmTime.toString() + " " + alarmTitle);
                        Alarm alarm = new Alarm(alarmTime.getTime(), alarmTitle, type);
                        alarm.dismiss = notification.actions[0].actionIntent;
                        sbnIdToTriggerTime.put(sbn.getId(), alarm.time);
                        updateAlarm(alarm);
                    }
                }
            } else if (type == AlarmState.RINGING) {
                NotificationManager notify = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                final Notification.WearableExtender wearableExtender = new Notification.WearableExtender()
                        .addAction(new Notification.Action(R.drawable.stat_notify_alarm,
                                notification.actions[0].title,
                                notification.actions[0].actionIntent));
                notify.notify(dismissAlarm.size(), new Notification.Builder(this)
                        .setContentTitle(title)
                        .setContentText("Alarm '" + title + "' is ringing on your phone: " + message)
                        .setPriority(Notification.PRIORITY_MIN)
                        .extend(wearableExtender)
                        .setSmallIcon(R.drawable.stat_notify_alarm).build());
                dismissAlarm.add(notification.actions[1].actionIntent);
                if (always_notify || isLocked()) {
                    Log.i(TAG, "Starting Pebble app");
                    PebbleKit.startAppOnPebble(this, WATCHAPP_UUID);
                    PebbleDictionary data = new PebbleDictionary();
                    data.addInt8(KEY_VIBRATION, VIBRATION_START);
                    PebbleKit.sendDataToPebbleWithTransactionId(this, WATCHAPP_UUID, data, tId.increment());
                } else
                    Log.i(TAG, "Not starting Pebble app (just sending notification)");
                updateAlarm(null);
            }
        }

        refreshNotifications(sbn);
    }

    private boolean isLocked() {
        return ((KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        final String packageName = sbn.getPackageName();
        Log.i(TAG, "********** onNotificationRemoved " + packageName);
        final Notification n = sbn.getNotification();
        final CharSequence message = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        Log.i(TAG, "ID :" + sbn.getId() + "\t" + message + "\t" + sbn.getPackageName());

        if (packageName.equals(DESK_CLOCK)) {
            AlarmState type = alarmType(n);
            if (type == AlarmState.RINGING) {
                // dismiss Pebble notification if alarm is dismissed
                if (dismissAlarm.size() == 1) {
                    dismissAlarm.clear();
                    NotificationManager notify = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    notify.cancel(0);
                    cancelPebbleVibration();
                }
            } else if(type == AlarmState.SNOOZED || type == AlarmState.UPCOMING) {
                if(sbnIdToTriggerTime.containsKey(sbn.getId()))
                    sbnIdToTriggerTime.remove(sbn.getId());
            }
        } else if (packageName.equals(this.getPackageName())) {
            // dismiss alarm if Pebble notification dismissed
            if (dismissAlarm.size() > sbn.getId()) {
                try {
                    dismissAlarm.remove(sbn.getId()).send();
                    cancelPebbleVibration();
                    final CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                    toast(getString(R.string.alarm_is_dismissed, title == null ? "" : title) + " from Pebble");
                } catch (PendingIntent.CanceledException e) {
                    e.printStackTrace();
                }
            }
        }

        refreshNotifications(null);
    }

    private void cancelPebbleVibration() {
        tId.increment();
        Log.d(TAG, "Cancelling Pebble vibration: " + tId.getValue());
        final PebbleDictionary data = new PebbleDictionary();
        data.addInt8(KEY_VIBRATION, VIBRATION_CANCEL);
        PebbleKit.sendDataToPebbleWithTransactionId(this, WATCHAPP_UUID, data, tId.getValue());
    }

    private void refreshNotifications(StatusBarNotification except) {
        if(hasRetrievedExistingNotifications) return;
        hasRetrievedExistingNotifications = true;
        sendingLocked = true;
        final StatusBarNotification[] activeNotifications = getActiveNotifications();
        if (activeNotifications != null) {
            for (StatusBarNotification asbn : activeNotifications) {
                if (except == null || asbn.getId() != except.getId())
                    this.onNotificationPosted(asbn);
            }
        }
        sendingLocked = false;
        sendAlarmUpdateToPebble(this);
    }

    public enum AlarmState {
        UNKNOWN,
        UPCOMING,
        SNOOZED,
        RINGING
    }

}
