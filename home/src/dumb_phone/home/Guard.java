package dumb_phone.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps the phone usable all day: runs whenever the home screen shows.
 * - dumb_phone's key service (volume bar, voice key, green key) is switched back on if Android dropped it.
 * - If the keyboard setting points at a keyboard that is gone or disabled, the last working one comes back.
 * Needs WRITE_SECURE_SETTINGS, granted once over adb (setup.sh does it):
 *   adb shell pm grant dumb_phone.home android.permission.WRITE_SECURE_SETTINGS
 * Without it this does nothing.
 */
final class Guard {
    static final String SERVICE = "dumb_phone.home/dumb_phone.home.VolumeService";

    static void check(Context c) {
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            String on = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on == null) on = "";
            if (!(":" + on + ":").contains(":" + SERVICE + ":")) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, on.isEmpty() ? SERVICE : on + ":" + SERVICE);
                Log.w("dumb_phone-guard", "key service was off, turned it back on");
            }
            // the stock plug-in / low-battery sounds: off (our key service plays a softer chime, only when not on vibrate)
            if (Settings.Global.getInt(cr, "power_sounds_enabled", 1) != 0) Settings.Global.putInt(cr, "power_sounds_enabled", 0);
            if (Settings.Global.getInt(cr, "charging_sounds_enabled", 1) != 0) Settings.Global.putInt(cr, "charging_sounds_enabled", 0);   // the system's "charging started" sound (ignores vibrate)
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);

            SharedPreferences p = c.getSharedPreferences("guard", Context.MODE_PRIVATE);
            String def = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
            String ims = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
            if (ims == null) ims = "";
            boolean works = def != null && !def.isEmpty() && installed(c, def) && (":" + ims + ":").contains(":" + def + ":");
            if (works) {
                p.edit().putString("keyboard", def).apply();         // remember the last keyboard that worked
            } else {
                String good = p.getString("keyboard", null);
                if (good != null && installed(c, good)) {
                    if (!(":" + ims + ":").contains(":" + good + ":"))
                        Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, ims.isEmpty() ? good : ims + ":" + good);
                    Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, good);
                    Log.w("dumb_phone-guard", "keyboard was " + def + ", set back to " + good);
                }
            }
        } catch (SecurityException e) {
            Log.w("dumb_phone-guard", "no WRITE_SECURE_SETTINGS (run setup.sh): " + e);
        } catch (Exception e) {
            Log.w("dumb_phone-guard", "check failed", e);
        }
    }

    private static boolean installed(Context c, String component) {
        try { c.getPackageManager().getPackageInfo(component.substring(0, component.indexOf('/')), 0); return true; }
        catch (Exception e) { return false; }
    }
}
