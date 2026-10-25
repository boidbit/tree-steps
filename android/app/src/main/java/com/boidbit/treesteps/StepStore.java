package com.boidbit.treesteps;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

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
            saveDay(p, savedDay, p.getInt("steps", 0));     // yesterday's total goes into the history
            base = (last >= 0 && raw >= last) ? last : 0;   // new day (counter reset to 0 if the phone rebooted)
        } else if (last >= 0 && raw < last) {
            base = base - last;                             // rebooted today: keep the steps already walked
        }
        int steps = (int) Math.max(0, raw - base);
        p.edit().putString("day", day).putLong("base", base).putLong("last", raw).putInt("steps", steps).apply();
        return steps;
    }

    private static final int HISTORY_DAYS = 60;

    /** History is kept as "yyyyMMdd:steps,..." newest first. */
    private static void saveDay(SharedPreferences p, String day, int steps) {
        StringBuilder out = new StringBuilder(day).append(':').append(steps);
        int kept = 1;
        for (String e : p.getString("history", "").split(",")) {
            if (e.isEmpty() || e.startsWith(day + ":")) continue;
            if (kept++ >= HISTORY_DAYS) break;
            out.append(',').append(e);
        }
        p.edit().putString("history", out.toString()).apply();
    }

    /** Finished days, newest first, as {yyyyMMdd, steps}. Today is not included. */
    static java.util.List<String[]> history(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String e : prefs(c).getString("history", "").split(",")) {
            int i = e.indexOf(':');
            if (i > 0) out.add(new String[] { e.substring(0, i), e.substring(i + 1) });
        }
        return out;
    }

    static String todayKey() { return today(); }

    /** Today's steps from the last reading, without touching the sensor. */
    static int lastKnown(Context c) {
        SharedPreferences p = prefs(c);
        return today().equals(p.getString("day", null)) ? p.getInt("steps", 0) : 0;
    }

    interface StepsCallback {
        void onSteps(int steps, boolean fresh);
    }

    /**
     * Reads the step counter once and reports today's steps on the main thread. The counter sends
     * its current value when a listener registers; if it stays quiet, the last known value is used.
     */
    static void readOnce(Context c, StepsCallback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        final Sensor sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null || !hasPermission(c)) {
            main.post(() -> cb.onSteps(lastKnown(c), false));
            return;
        }
        final boolean[] done = { false };
        final SensorEventListener[] listener = new SensorEventListener[1];
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            sm.unregisterListener(listener[0]);
            cb.onSteps(lastKnown(c), false);
        };
        listener[0] = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (done[0]) return;
                done[0] = true;
                sm.unregisterListener(this);
                main.removeCallbacks(timeout);
                cb.onSteps(record(c, e.values[0]), true);
            }

            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };
        sm.registerListener(listener[0], sensor, SensorManager.SENSOR_DELAY_NORMAL, main);
        main.postDelayed(timeout, 2500);
    }

    static boolean hasStepSensor(Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    /* ---------- settings shared with Tree3D.html (keys match the page's settings object) ---------- */

    static int seed(Context c) { return prefs(c).getInt("seed", 48213); }
    static String palette(Context c) { return prefs(c).getString("pal", "Sunset"); }
    /** banyan | oak | sakura | maple | pine | jacaranda (see ArtRenderer) */
    static String design(Context c) { return prefs(c).getString("design", "banyan"); }
    static boolean useRealSteps(Context c) { return prefs(c).getBoolean("use_real", false); }
    static int manualSteps(Context c) { return prefs(c).getInt("manual_steps", 6000); }
    static int goal(Context c) { return Math.max(1000, prefs(c).getInt("goal", 10000)); }
    /** off | steps | goal | bar | percent */
    static String labelStyle(Context c) { return prefs(c).getString("label_style", "goal"); }
    /** tree | bottom */
    static String labelPos(Context c) { return prefs(c).getString("label_pos", "tree"); }
    /** s | m | l */
    static String labelSize(Context c) { return prefs(c).getString("label_size", "m"); }
    static int intervalMin(Context c) { return Math.max(1, prefs(c).getInt("interval_min", 15)); }
    static boolean tilt(Context c) { return prefs(c).getBoolean("tilt", false); }
    /** time (follow the sun) | dawn | day | sunset | night */
    static String skyMode(Context c) { return prefs(c).getString("sky_mode", "time"); }
}
