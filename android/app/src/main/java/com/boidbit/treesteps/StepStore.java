package com.boidbit.treesteps;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shared state between the app and the live wallpaper: the tree settings, and "steps walked today"
 * worked out from the phone's step counter (which only reports total steps since the last reboot).
 */
final class StepStore {
    private static final String PREFS = "tree_steps";

    private StepStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasPermission(Context c) {
        return Build.VERSION.SDK_INT < 29
            || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** Records a raw step-counter reading and returns today's steps. */
    static synchronized int record(Context c, float rawValue) {
        long raw = (long) rawValue;
        SharedPreferences p = prefs(c);
        String day = today();
        String savedDay = p.getString("day", null);
        long last = p.getLong("last", -1);
        long base = p.getLong("base", raw);
        if (savedDay == null) {
            base = raw;                                     // first reading ever: count from now
        } else if (!day.equals(savedDay)) {
            base = (last >= 0 && raw >= last) ? last : 0;   // new day (counter reset to 0 if the phone rebooted)
        } else if (last >= 0 && raw < last) {
            base = base - last;                             // rebooted today: keep the steps already walked
        }
        int steps = (int) Math.max(0, raw - base);
        p.edit().putString("day", day).putLong("base", base).putLong("last", raw).putInt("steps", steps).apply();
        return steps;
    }

    /** Today's steps from the last reading, without touching the sensor. */
    static int lastKnown(Context c) {
        SharedPreferences p = prefs(c);
        return today().equals(p.getString("day", null)) ? p.getInt("steps", 0) : 0;
    }

    static int seed(Context c) { return prefs(c).getInt("seed", 48213); }
    static String palette(Context c) { return prefs(c).getString("pal", "Sunset"); }
    static boolean useRealSteps(Context c) { return prefs(c).getBoolean("use_real", false); }
    static int manualSteps(Context c) { return prefs(c).getInt("manual_steps", 6000); }
}
