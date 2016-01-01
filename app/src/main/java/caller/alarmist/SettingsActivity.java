package caller.alarmist;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.util.Log;

import java.util.Map;

/**
 * Created by Ben on 22/12/2015.
 */
public class SettingsActivity extends Activity {

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

        }
    }

}