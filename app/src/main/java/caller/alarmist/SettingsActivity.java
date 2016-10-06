package caller.alarmist;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Map;

/**
 * Created by Ben on 22/12/2015.
 */
public class SettingsActivity extends Activity {
    private static final String TAG = "AlarmistSettings";

    private static final Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {
            String stringValue = value.toString();

            final Context context = preference.getContext();
            if (preference instanceof SwitchPreference) {
                boolean shown = stringValue.equals("true");
                preference.setSummary(context.getString(shown ? R.string.show_app_drawer : R.string.hide_app_drawer));
                ComponentName componentName = new ComponentName(context, caller.alarmist.SettingsActivity.class); // activity which is first time open in manifest file which is declare as <category android:name="android.intent.category.LAUNCHER" />
                context.getPackageManager().setComponentEnabledSetting(componentName,
                        shown ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            } else {
                // For all other preferences, set the summary to the value's
                // simple string representation.
                preference.setSummary(stringValue);
            }
            return true;
        }
    };

    /**
     * Binds a preference's summary to its value. More specifically, when the
     * preference's value is changed, its summary (line of text below the
     * preference title) is updated to reflect the value. The summary is also
     * immediately updated upon calling this method. The exact display format is
     * dependent on the type of preference.
     *
     * @see #sBindPreferenceSummaryToValueListener
     */
    private static void bindPreferenceSummaryToValue(Preference preference, Map<String, ?> all) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);

        // Trigger the listener immediately with the preference's
        // current value.
        String key = preference.getKey();
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference, all.containsKey(key) ? all.get(key) : "");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Display the fragment as the main content.
        getFragmentManager().beginTransaction().replace(android.R.id.content,
                new PrefsFragment()).commit();
    }

    public static class PrefsFragment extends PreferenceFragment {
        static final String DESK_CLOCK_ACTIVITY_CLASS = "com.android.deskclock.DeskClock";

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.settings);

            PreferenceCategory prefCat=(PreferenceCategory)findPreference(getString(R.string.pref_settings_title));
            prefCat.setTitle(getString(R.string.app_name) + " " + getString(R.string.settings));

            final Map<String, ?> all = PreferenceManager.getDefaultSharedPreferences(getActivity()).getAll();
            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_show_icon)), all);

            findPreference(getString(R.string.pref_key_peb_appstore)).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
//                    Log.d("PREFS", "Opening Pebble appstore");
                    Intent appstoreIntent = new Intent(Intent.ACTION_VIEW);
                    appstoreIntent.setData(Uri.parse(getActivity().getString(R.string.appstore_deeplink)));
                    appstoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(appstoreIntent);
                    return true;
                }
            });

            findPreference(getString(R.string.pref_key_notif_access_perm)).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } else {
                        startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    }
                    return true;
                }
            });

            findPreference(getString(R.string.pref_key_test_notif)).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    WindowManager.LayoutParams params = getActivity().getWindow().getAttributes();
                    params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                    params.screenBrightness = 0;
                    getActivity().getWindow().setAttributes(params);
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            NotificationManager notify = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
                            notify.notify(3, new Notification.Builder(getActivity())
                                    .setContentTitle("Alarmist test")
                                    .setContentText("Alarmist can send notifications to your Pebble")
                                    .setPriority(Notification.PRIORITY_MAX)
                                    .setSmallIcon(R.drawable.stat_notify_alarm).build());
                        }
                    }, 1500);
                    return true;
                }
            });

            findPreference(getString(R.string.pref_key_test_open_app)).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                        final ComponentName component = new ComponentName(deskClockPackageName(), DESK_CLOCK_ACTIVITY_CLASS);
                        intent.setComponent(component);
                        getActivity().startActivity(intent);
                        findPreference(getText(R.string.pref_key_test_open_app)).setSummary(R.string.stock_alarm_exists);
                    } catch (PackageManager.NameNotFoundException ignored) {
                        Toast.makeText(getActivity(), R.string.stock_alarm_nonexist, Toast.LENGTH_LONG).show();
                    }
                    return true;
                }
            });

            findPreference(getString(R.string.pref_key_test_set_alarm)).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    if (mBound) {
                        Calendar calendar = Calendar.getInstance();
                        calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE)+1);
                        mService.setAlarm(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), AlarmClockWatcher.RECURSION_NONE);
                    } else {
                        Toast.makeText(getActivity(), R.string.notif_access_summary, Toast.LENGTH_LONG).show();
                    }
                    return true;
                }
            });

            findPreference(getString(R.string.pref_key_test_set_timer)).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    if (mBound) {
                        mService.setTimer(5);
                    } else {
                        Toast.makeText(getActivity(), R.string.notif_access_summary, Toast.LENGTH_LONG).show();
                    }
                    return true;
                }
            });

            SwitchPreference always = (SwitchPreference)findPreference(getString(R.string.pref_key_notif_always));
            always.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    updateSummaryForSetTimer((boolean) newValue);
                    return true;
                }
            });
            updateSummaryForSetTimer(always.isChecked());
        }

        private void updateSummaryForSetTimer(boolean alwaysIsChecked) {
            int summaryFormat = alwaysIsChecked
                    ? R.string.pref_set_test_timer_summary_ignore_screen
                    : R.string.pref_set_test_timer_summary;
            findPreference(getString(R.string.pref_key_test_set_timer)).setSummary(getString(summaryFormat));

            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) + 1);
            summaryFormat = alwaysIsChecked
                    ? R.string.pref_set_test_alarm_summary_ignore_screen
                    : R.string.pref_set_test_alarm_summary;
            findPreference(getString(R.string.pref_key_test_set_alarm)).setSummary(getString(summaryFormat,
                    SimpleDateFormat.getTimeInstance(DateFormat.SHORT).format(calendar.getTime())));
        }

        private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                SwitchPreference always = (SwitchPreference)findPreference(getString(R.string.pref_key_notif_always));
                updateSummaryForSetTimer(always.isChecked());
            }
        };

        private final BroadcastReceiver pebbleConnectedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                findPreference(getString(R.string.pref_key_test_notif)).setSummary(getString(R.string.pref_test_notif_summ));
            }
        };

        private final BroadcastReceiver pebbleDisconnectedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                findPreference(getString(R.string.pref_key_test_notif)).setSummary(R.string.pref_pebble_not_connected);
            }
        };

        @Override
        public void onStart() {
            super.onStart();
            final Activity context = getActivity();
            Log.d(TAG, "start");
            findPreference(getString(R.string.pref_key_notif_access_perm)).setSummary(context.getText(R.string.notif_access_summary));

            Intent intent = new Intent(context, AlarmClockWatcher.class);
            intent.putExtra(AlarmClockWatcher.EXTRA_SETTINGS_BIND, true);
            context.bindService(intent, mConnection, 0);

            try {
                deskClockPackageName();
                findPreference(getText(R.string.pref_key_test_open_app)).setSummary(R.string.stock_alarm_exists);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "No stock alarm clock");
                findPreference(getText(R.string.pref_key_test_open_app)).setSummary(getText(R.string.stock_alarm_nonexist));
                Toast.makeText(context, R.string.stock_alarm_nonexist, Toast.LENGTH_LONG).show();
            }
        }

        private String deskClockPackageName() throws PackageManager.NameNotFoundException {
            //Either DESK_CLOCK_GOOGLE or DESK_CLOCK
            Activity context = getActivity();
            ComponentName component = new ComponentName(AlarmClockWatcher.DESK_CLOCK_GOOGLE, DESK_CLOCK_ACTIVITY_CLASS);
            try {
                ActivityInfo aInfo = context.getPackageManager().getActivityInfo(component, PackageManager.GET_META_DATA);
                Log.d(TAG, AlarmClockWatcher.DESK_CLOCK_GOOGLE);
                return AlarmClockWatcher.DESK_CLOCK_GOOGLE;
            } catch (PackageManager.NameNotFoundException no_google) {
                component = new ComponentName(AlarmClockWatcher.DESK_CLOCK, DESK_CLOCK_ACTIVITY_CLASS);
                try {
                    ActivityInfo aInfo = context.getPackageManager().getActivityInfo(component, PackageManager.GET_META_DATA);
                    Log.d(TAG, AlarmClockWatcher.DESK_CLOCK);
                    return AlarmClockWatcher.DESK_CLOCK;
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "No stock alarm clock");
                    findPreference(getText(R.string.pref_key_test_open_app)).setSummary(getText(R.string.stock_alarm_nonexist));
                    Toast.makeText(context, R.string.stock_alarm_nonexist, Toast.LENGTH_LONG).show();
                    throw e; // unnecessary
                }
            }
        }

        @Override
        public void onResume() {
            tickReceiver.onReceive(null, null);
            final Activity context = getActivity();
            context.registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
            findPreference(getString(R.string.pref_key_test_notif)).setSummary(PebbleKit.isWatchConnected(context)
                    ? R.string.pref_test_notif_summ : R.string.pref_pebble_not_connected);
            PebbleKit.registerPebbleConnectedReceiver(context, pebbleConnectedReceiver);
            PebbleKit.registerPebbleDisconnectedReceiver(context, pebbleDisconnectedReceiver);
            if(mService != null) {
                try {
                    mService.getActiveNotifications();
                    findPreference(getString(R.string.pref_key_notif_access_perm)).setSummary(R.string.notification_access_already_ok);
                } catch (SecurityException ignored) { Log.d(TAG, "Denied Notification Access"); }
            }
            super.onResume();
        }

        @Override
        public void onPause() {
            final Activity activity = getActivity();
            ReceiverHelper.tryUnregister(activity, tickReceiver);
            ReceiverHelper.tryUnregister(activity, pebbleConnectedReceiver);
            ReceiverHelper.tryUnregister(activity, pebbleDisconnectedReceiver);
            super.onPause();
        }

        @Override
        public void onStop() {
            super.onStop();
            // Unbind from the service
            if (mBound) {
                getActivity().unbindService(mConnection);
                mBound = false;
            }
            Log.d(TAG, "stop");
        }

        private AlarmClockWatcher mService;
        private boolean mBound = false;
        private ServiceConnection mConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                // We've bound to LocalService, cast the IBinder and get LocalService instance
                AlarmClockWatcher.SettingsBinder binder = (AlarmClockWatcher.SettingsBinder) service;
                mService = binder.getService();
                try {
                    mService.getActiveNotifications();
                    Log.i(TAG, "Connected service");
                    findPreference(getString(R.string.pref_key_notif_access_perm)).setSummary(R.string.notification_access_already_ok);
                    mBound = true;
                } catch (SecurityException ignored) { Log.d(TAG, "Denied Notification Access"); }
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                mBound = false;
                Log.i(TAG, "Disconnected service");
                findPreference(getString(R.string.pref_key_notif_access_perm)).setSummary(getActivity().getText(R.string.notif_access_summary));
            }
        };
    }

}