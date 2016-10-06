package caller.alarmist;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Calendar.DAY_OF_WEEK;

//See https://android.googlesource.com/platform/packages/apps/DeskClock/+/master/src/com/android/deskclock/alarms/AlarmNotifications.java

public class AlarmClockWatcher extends NotificationListenerService {
    private static final boolean LOGGING = false;
    public static final UUID WATCHAPP_UUID = UUID.fromString("5cfa8e91-5c31-4287-9b57-e0f14ae72b34");
    static final short RECURSION_WEEKDAYS = 2;
    static final short RECURSION_EVERYDAY = 3;
    static final int KEY_NEW_ALARM = 2;
    static final String DESK_CLOCK_GOOGLE = "com.google.android.deskclock";
    static final String DESK_CLOCK = "com.android.deskclock";
    public static final String EXTRA_SETTINGS_BIND = "BINDING_FROM_SETTINGS";
    private static final String TAG = "AlarmistNLS";
    private static final int KEY_WATCH_APP_HAS_STARTED = 7;
    private static final int KEY_SEND_NEXT_ALARM = 1;
    private static final int KEY_HOUR = 21;
    private static final int KEY_MINUTE = 22;
    private static final int KEY_RECURSION = 23;
    static final short RECURSION_NONE = 0;
    static final short RECURSION_WEEKLY = 1;
    private final FinalInt tId = new FinalInt();
    private final PebbleKit.PebbleNackReceiver pebbleNackReceiver = new PebbleKit.PebbleNackReceiver(WATCHAPP_UUID) {
        @Override
        public void receiveNack(Context context, int transactionId) {
            if(LOGGING)Log.e(TAG, "Nacked " + transactionId);
        }
    };
    private Map<String, RingingAlarm> dismissAlarm;
    private Alarm nextAlarm;
    private Alarm unconfirmedNotificationAlarm;
    public boolean hasRetrievedExistingNotifications = false;
    private boolean sendingLocked = false;
    private Map<Long, Alarm> alarmByTrigger = new HashMap<>(8);
    private final BroadcastReceiver alarmManagerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkAlarmManager();
            if(LOGGING)Log.i(TAG, nextAlarm == null ? "no alarm" : nextAlarm.toString());
        }
    };
    private final BroadcastReceiver snoozeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if(LOGGING) Log.i(TAG, "We have a snooze command");
            String key = intent.getStringExtra(RingingAlarm.SNOOZE_INTENT_EXTRA);
            if(dismissAlarm.containsKey(key)) {
                final RingingAlarm ringingAlarm = dismissAlarm.get(key);
                ringingAlarm.snooze(ctx);
                ringingAlarm.destroy(ctx, dismissAlarm);
                updateAlarm(null);
            }
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
            if(LOGGING)Log.d(TAG, "Message from Pebble");

            refreshNotifications(null);

            if(isKeyOK(KEY_WATCH_APP_HAS_STARTED, data)) {
                if(LOGGING)Log.d(TAG, "Pebble app says it has started");
                final String[] currentIds = dismissAlarm.keySet().toArray(new String[dismissAlarm.size()]);
                for(String key : currentIds) {
                    final RingingAlarm ringingAlarm = dismissAlarm.get(key);
                    if(LOGGING)Log.d(TAG, "Found notification to show");
                    String newNotificationKey = ringingAlarm.showNotification(context);
                    // If was waiting for app to start to show notification
                    if(newNotificationKey != null) {
                        dismissAlarm.put(newNotificationKey, ringingAlarm);
                    }
                }
            } else if (isKeyOK(KEY_SEND_NEXT_ALARM, data)) {
                PebbleKit.sendAckToPebble(context, transactionId);
                checkAlarmManager();
                if (nextAlarm != null) {
                    sendUpcomingAlarmUpdateToPebble(context);
                }
            } else if (isKeyOK(Alarm.KEY_CAN_DISMISS, data)) {
                PebbleKit.sendAckToPebble(context, transactionId);
                if(LOGGING)Log.i(TAG, "Dismissing alarm...");
                if (nextAlarm != null && nextAlarm.dismiss != null
                        && data.getInteger(Alarm.KEY_TIME) == nextAlarm.pebbleTime()) {
                    if(LOGGING)Log.i(TAG, "Dismissing alarm from Pebble");
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
                if(LOGGING)Log.i(TAG, "New alarm...");
                final int hour = data.getInteger(KEY_HOUR).intValue();
                final int min = data.getInteger(KEY_MINUTE).intValue();
                final short recursion = data.getInteger(KEY_RECURSION).shortValue();
                setAlarm(hour, min, recursion);
            }
        }
    };

    public void setAlarm(int hour, int min, short recursion) {
        Intent setAlarm = new Intent(AlarmClock.ACTION_SET_ALARM);
        setAlarm.putExtra(AlarmClock.EXTRA_HOUR, hour);
        setAlarm.putExtra(AlarmClock.EXTRA_MINUTES, min);
        if(LOGGING)Log.i(TAG, String.format("%d:%d rep %s", hour, min, recursion));
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
        setAlarm.putExtra(AlarmClock.EXTRA_MESSAGE, String.format("Alarmist: %s", getString(R.string.alarm_alert_wake_up)));
        setAlarm.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        setAlarm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(setAlarm);
    }

    public void setTimer(int seconds) {
        Intent setTimer = new Intent(AlarmClock.ACTION_SET_TIMER);
        setTimer.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
        setTimer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(setTimer);
    }

    final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if(LOGGING)Log.d(TAG, String.format("Pref changed: %s", key));
            always_notify = sharedPreferences.getBoolean(getString(R.string.pref_key_notif_always), false);
        }
    };
    private SharedPreferences sharedPreferences;

    private void updateAlarm(Alarm alarm) {
        if(alarm != null)
            alarmByTrigger.put(alarm.time, alarm);

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo nextClock = alarmManager.getNextAlarmClock();
        if (nextClock != null) {
            final PendingIntent alarmIntent = nextClock.getShowIntent();
            final String creatorPackage = alarmIntent != null
                    ? alarmIntent.getCreatorPackage()
                    : null;
            if (creatorPackage != null && (creatorPackage.equals(DESK_CLOCK) || creatorPackage.equals(DESK_CLOCK_GOOGLE))) {
                final long triggerTime = nextClock.getTriggerTime();

                if(alarmByTrigger.containsKey(triggerTime)) {
                    nextAlarm = alarmByTrigger.get(triggerTime);
                } else {
                    // triggerTime has changed
                    // we must respect the triggerTime
                    nextAlarm = new Alarm(triggerTime);
                }
            } else {
                if(LOGGING)Log.e(TAG, "Another app is messing with the alarm clock: " + (creatorPackage == null ? "?" : creatorPackage));
                nextAlarm = alarm;
            }
        } else {
            nextAlarm = null;
        }

        if(LOGGING) {
            if (nextAlarm != null) {
                Log.i(TAG, "Next alarm is now " + nextAlarm);
            } else
                Log.i(TAG, "No next alarm");
        }

        sendUpcomingAlarmUpdateToPebble(this);
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
            } else if (message != null && message.equals(getString(R.string.timer_times_up))) {
                if(notification.actions != null && notification.actions.length > 0) {
                    if(notification.actions[0].title.equals(getString(R.string.timer_stop)) ||
                            notification.actions[0].title.equals(getString(R.string.timer_stop_all)))
                        return AlarmState.TIMER_END;
                    else
                        return AlarmState.UNKNOWN;
                }
            } else if (notification.actions != null && notification.actions.length > 0) {
                if (notification.actions.length == 2
                        && notification.actions[0].title.equals(getString(R.string.alarm_alert_snooze_text))
                        && notification.actions[1].title.equals(getString(R.string.alarm_alert_dismiss_text))) {
                    return AlarmState.RINGING;
                } else if (notification.actions.length == 1 && notification.actions[0].title.equals(getString(R.string.alarm_alert_dismiss_text))) {
                    return AlarmState.SNOOZED;
                }
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

    private void sendUpcomingAlarmUpdateToPebble(Context context) {
        if (!sendingLocked && PebbleKit.isWatchConnected(context)) {
            tId.increment();
            if (nextAlarm != null) {
                final PebbleDictionary pebbleTuples = nextAlarm.toPebbleDict();
                if(LOGGING)Log.d(TAG, "Sending alarm to Pebble: " + tId.getValue() + " : " + nextAlarm);
                PebbleKit.sendDataToPebbleWithTransactionId(context, WATCHAPP_UUID, pebbleTuples, tId.getValue());
            } else {
                if(LOGGING)Log.v(TAG, "Sending no alarm to Pebble: " + tId.getValue());
                final PebbleDictionary empty = new PebbleDictionary();
                empty.addInt8(KEY_SEND_NEXT_ALARM, (byte) 0);
                PebbleKit.sendDataToPebbleWithTransactionId(context, WATCHAPP_UUID, empty, tId.getValue());
            }
        }
    }

    @Override
    public void onCreate() {
        if(LOGGING)Log.d(TAG, "Alarmist created");
        dismissAlarm = new HashMap<>();
        this.registerReceiver(alarmManagerReceiver, new IntentFilter("android.app.action.NEXT_ALARM_CLOCK_CHANGED"));
        this.registerReceiver(snoozeReceiver, new IntentFilter(RingingAlarm.SNOOZE_INTENT_ACTION));
        PebbleKit.registerReceivedDataHandler(this, pebbleDataReceiver);
        PebbleKit.registerReceivedNackHandler(this, pebbleNackReceiver);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
        super.onCreate();
    }

    private final IBinder settingsBinder = new SettingsBinder();
    public class SettingsBinder extends Binder {
        AlarmClockWatcher getService() {
            // Return this instance of LocalService so clients can call public methods
            return AlarmClockWatcher.this;
        }
    }
    @Override
    public IBinder onBind(Intent intent) {
        if(LOGGING)Log.v(TAG, "Alarmist bound");
        if(intent.hasExtra(EXTRA_SETTINGS_BIND))
            return settingsBinder;
        return super.onBind(intent);
    }

    @Override
    public void onDestroy() {
        if(LOGGING)Log.d(TAG, "Alarmist destroyed");
        ReceiverHelper.tryUnregister(this, alarmManagerReceiver);
        ReceiverHelper.tryUnregister(this, snoozeReceiver);
        ReceiverHelper.tryUnregister(this, pebbleDataReceiver);
        ReceiverHelper.tryUnregister(this, pebbleNackReceiver);
        try {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        } catch (IllegalArgumentException ignored) {}
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
        if(LOGGING)Log.v(TAG, "**********  onNotificationPosted " + packageName);

        if(!hasRetrievedExistingNotifications) sendingLocked = true;

        if (packageName.equals(DESK_CLOCK) || packageName.equals(DESK_CLOCK_GOOGLE)) {
            final Notification notification = sbn.getNotification();
            final CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            final CharSequence message = notification.extras.getCharSequence(Notification.EXTRA_TEXT);

            if(LOGGING) {
                Log.i(TAG, String.format("++ %d\t[%s]\t[%s]\t%s", sbn.getId(), title, message, packageName));
                if(notification.actions != null) {
                    for (Notification.Action action : notification.actions) {
                        Log.d(TAG, String.format("Notification has action: '%s'", action.title));
                    }
                }
            }
            if(message == null) return;

            AlarmState type = alarmType(notification);
            if (type == AlarmState.UPCOMING) {// Upcoming alarm
                onUpcomingAlarmNotification(sbn, notification, message);
            } else if (type == AlarmState.SNOOZED) {
                onSnoozedAlarmNotification(sbn, notification, title, message);
            } else if (type == AlarmState.RINGING) {
                onRingingAlarmNotification(notification);
            } else if (type == AlarmState.TIMER_END) {
                onTimerEndNotification(notification);
            }
        }

        refreshNotifications(sbn);
    }

    private void onRingingAlarmNotification(Notification notification) {
        if (always_notify || isLocked()) {
            RingingAlarm ringingAlarm = new RingingAlarm(
                    (int) (System.currentTimeMillis() + 1), RingingAlarm.AlarmType.ALARM, notification, tId);
            ringingAlarm.launchApp(this);
            dismissAlarm.put(ringingAlarm.getAlarmKeyBeforeNotificationSent(), ringingAlarm);
            updateAlarm(null);
        } else if (LOGGING) Log.i(TAG, "oRAN: Not starting Pebble app (just sending notification)");
    }

    private void onTimerEndNotification(Notification notification) {
        if (always_notify || isLocked()) {
            RingingAlarm ringingAlarm = new RingingAlarm(
                    (int) (System.currentTimeMillis() + 1), RingingAlarm.AlarmType.TIMER, notification, tId);
            ringingAlarm.launchApp(this);
            dismissAlarm.put(ringingAlarm.getAlarmKeyBeforeNotificationSent(), ringingAlarm);
            updateAlarm(null);
        } else if (LOGGING) Log.i(TAG, "oTEN: Not starting Pebble app (just sending notification)");
    }

    private void onSnoozedAlarmNotification(StatusBarNotification sbn, Notification notification, CharSequence title, CharSequence message) {
        final String alarmTitle = title.toString();
        final String replace = getString(R.string.alarm_alert_snooze_until, "(.+)");
        Pattern snoozePattern = Pattern.compile(replace);
        final Matcher matcher = snoozePattern.matcher(message);
        if (matcher.find()) {
            final String formattedTime = matcher.group(1);
            final Date alarmTime = parseDate(formattedTime);
            if (alarmTime != null) {
                int minutes = Math.round(((float) (alarmTime.getTime() - new Date().getTime())) / (1000 * 60));
                if(LOGGING)Log.d(TAG, String.format("Snooze for %d mins, until %s %s", minutes, alarmTime.toString(), alarmTitle));
                Alarm alarm = new Alarm(alarmTime.getTime(), alarmTitle, AlarmState.SNOOZED);
                alarm.dismiss = notification.actions[0].actionIntent;
                sbnIdToTriggerTime.put(sbn.getId(), alarm.time);
                updateAlarm(alarm);
            }
        }
    }

    private void onUpcomingAlarmNotification(StatusBarNotification sbn, Notification notification, CharSequence message) {
        final String[] parts = message.toString().split(" - ", 2);
        final String formattedTime = parts[0];
        String alarmTitle = null;
        if (parts.length > 1)
            alarmTitle = parts[1];
        final Date alarmTime = parseDate(formattedTime);
        if (alarmTime != null) {
            if(LOGGING)Log.d(TAG, String.format("Upcoming at %s named '%s'", alarmTime.toString(), alarmTitle != null ? alarmTitle : ""));
            Alarm alarm = new Alarm(alarmTime.getTime(), alarmTitle, AlarmState.UPCOMING);
            alarm.dismiss = notification.actions[0].actionIntent;
            unconfirmedNotificationAlarm = alarm;
            sbnIdToTriggerTime.put(sbn.getId(), alarm.time);
            updateAlarm(alarm);
        }
    }

    private boolean isLocked() {
        return ((KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        final String packageName = sbn.getPackageName();
        if(LOGGING)Log.v(TAG, "********** onNotificationRemoved " + packageName);
        final Notification notification = sbn.getNotification();
        final CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        final CharSequence message = notification.extras.getCharSequence(Notification.EXTRA_TEXT);

        if(LOGGING) {
            Log.i(TAG, String.format("-- %d\t[%s]\t[%s]\t%s", sbn.getId(), title, message, packageName));
            if(notification.actions != null) {
                for (Notification.Action action : notification.actions) {
                    Log.d(TAG, String.format("Notification had action: '%s'", action.title));
                }
            }
        }
        if(message == null || title == null) return;

        if (packageName.equals(DESK_CLOCK) || packageName.equals(DESK_CLOCK_GOOGLE)) {
            AlarmState type = alarmType(notification);
            if(LOGGING)Log.v(TAG, "********** oNR " + type.toString());
            if (type == AlarmState.RINGING) {
                // dismiss Pebble notification if alarm is dismissed
                final String key = RingingAlarm.alarmKeyPreNotification(title, message, notification.when);
                if(dismissAlarm.containsKey(key)) {
                    if(LOGGING)Log.v(TAG, "********** oNR dismissAlarm");
                    final RingingAlarm ringingAlarm = dismissAlarm.get(key);
                    ringingAlarm.destroy(this, dismissAlarm);
                }
            } else if(type == AlarmState.TIMER_END) {
                final String key = RingingAlarm.alarmKeyPreNotification(title, message, notification.when);
                if(dismissAlarm.containsKey(key)) {
                    if(LOGGING)Log.v(TAG, "********** oNR dismissTimer");
                    final RingingAlarm ringingAlarm = dismissAlarm.get(key);
                    ringingAlarm.destroy(this, dismissAlarm);
                }
            } else if(type == AlarmState.SNOOZED || type == AlarmState.UPCOMING) {
                if(sbnIdToTriggerTime.containsKey(sbn.getId()))
                    sbnIdToTriggerTime.remove(sbn.getId());
            }
        } else if (packageName.equals(this.getPackageName())) {
            // dismiss alarm if Pebble notification dismissed
            final String key = notification.when + "";
            if(dismissAlarm.containsKey(key)) {
                if(LOGGING)Log.i(TAG, "DISMISSING ALARM VIA P.INTENT");
                final RingingAlarm ringingAlarm = dismissAlarm.get(key);
                ringingAlarm.dismiss(this);
                ringingAlarm.destroy(this, dismissAlarm);
                toast(String.format(getString(R.string.from_pebble), getString(R.string.alarm_is_dismissed, title)));
            }
        }

        if(LOGGING) {
            for (String dak : dismissAlarm.keySet()) {
                Log.d(TAG, dak + " in dismissAlarm");
            }
        }

        refreshNotifications(null);
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
        sendUpcomingAlarmUpdateToPebble(this);
    }

    public enum AlarmState {
        UNKNOWN,
        UPCOMING,
        SNOOZED,
        RINGING,
        TIMER_END
    }

}
