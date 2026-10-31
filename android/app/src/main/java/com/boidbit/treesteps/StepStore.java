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
            saveDay(p, savedDay, dayTotal(p, savedDay));    // yesterday's total goes into the history
            base = (last >= 0 && raw >= last) ? last : 0;   // new day (counter reset to 0 if the phone rebooted)
        } else if (last >= 0 && raw < last) {
            base = base - last;                             // rebooted today: keep the steps already walked
        } else if (last >= 0 && inVehicle(c)) {
            base += raw - last;                             // in a car, bus or on a bike: these "steps" don't count
        }
        int steps = (int) Math.max(0, raw - base);
        int before = day.equals(savedDay) ? p.getInt("steps", 0) : 0;
        SharedPreferences.Editor e = p.edit().putString("day", day).putLong("base", base).putLong("last", raw).putInt("steps", steps);
        addToHour(p, e, day, Math.max(0, steps - before));
        noteMove(p, e, Math.max(0, steps - before));
        e.apply();
        int shown = today(c);
        TreeWidget.stepsChanged(c, shown);
        return shown;
    }

    /* ---------- moving vehicles (see VehicleWatch) ---------- */

    private static final long VEHICLE_MAX_MS = 4 * 3600_000L;   // a missed "left the vehicle" must not stop counting for good

    static boolean ignoreVehicles(Context c) { return prefs(c).getBoolean("ignore_vehicle", true); }

    static boolean inVehicle(Context c) {
        SharedPreferences p = prefs(c);
        return ignoreVehicles(c) && p.getBoolean("in_vehicle", false)
            && System.currentTimeMillis() - p.getLong("vehicle_since", 0) < VEHICLE_MAX_MS;
    }

    static void setInVehicle(Context c, boolean on) {
        prefs(c).edit().putBoolean("in_vehicle", on).putLong("vehicle_since", System.currentTimeMillis()).apply();
    }

    /* ---------- the thirsty tree: time since the last real walk ---------- */

    private static final int MOVE_STEPS = 50;                   // steps within ten minutes that count as a walk
    private static final long MOVE_WINDOW_MS = 10 * 60_000L;

    private static void noteMove(SharedPreferences p, SharedPreferences.Editor e, int delta) {
        if (delta <= 0) return;
        long now = System.currentTimeMillis();
        long start = p.getLong("move_start", 0);
        int acc = p.getInt("move_acc", 0);
        if (now - start > MOVE_WINDOW_MS) { start = now; acc = 0; }
        acc += delta;
        e.putLong("move_start", start).putInt("move_acc", acc);
        if (acc >= MOVE_STEPS) e.putLong("last_move", now);
    }

    /**
     * How wilted the tree looks, 0 to 1: nothing for two hours after a walk, then fully wilted two hours
     * later. Only between 8 AM and 9 PM, only with real steps, and never once today's goal is reached.
     */
    static double wilt(Context c, long now) {
        if (!useRealSteps(c) || !hasPermission(c) || today(c) >= goal(c)) return 0;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour < 8 || hour >= 21) return 0;
        cal.set(java.util.Calendar.HOUR_OF_DAY, 8);
        cal.set(java.util.Calendar.MINUTE, 0);
        long since = Math.max(prefs(c).getLong("last_move", 0), cal.getTimeInMillis());
        double idleMin = (now - since) / 60000.0;
        return Math.max(0, Math.min(1, (idleMin - 120) / 120));
    }

    /** Days in a row the goal was reached: the finished days before today, plus today once it is reached. */
    static int streak(Context c) {
        int goal = goal(c);
        java.util.Map<String, Integer> byDay = new java.util.HashMap<>();
        for (String[] d : history(c)) {
            try { byDay.put(d[0], Integer.parseInt(d[1])); } catch (NumberFormatException ignored) {}
        }
        int n = shownSteps(c) >= goal ? 1 : 0;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        for (int i = 1; i < HISTORY_DAYS; i++) {
            cal.add(java.util.Calendar.DATE, -1);
            Integer s = byDay.get(f.format(cal.getTime()));
            if (s != null && s >= goal) n++; else break;
        }
        return n;
    }

    /* ---------- Health Connect (see HealthSteps) ---------- */

    /** phone (the step counter) | health (Health Connect, with the step counter filling in between reads) */
    static boolean healthSource(Context c) { return "health".equals(prefs(c).getString("step_source", "phone")); }

    /** Stores today's total from Health Connect; steps the counter sees after this are added on top until the next read. */
    static synchronized void recordHealth(Context c, long total) {
        prefs(c).edit().putString("hc_day", today()).putInt("hc_steps", (int) total).putInt("hc_sensor", lastKnown(c)).apply();
        TreeWidget.stepsChanged(c, today(c));
    }

    /** Adds finished days from Health Connect to the history where they are higher than what the phone saved. */
    static synchronized void mergeHistory(Context c, java.util.Map<String, Long> days) {
        SharedPreferences p = prefs(c);
        java.util.Map<String, String> byDay = new java.util.TreeMap<>(java.util.Collections.reverseOrder());
        for (String e : p.getString("history", "").split(",")) {
            int i = e.indexOf(':');
            if (i > 0) byDay.put(e.substring(0, i), e);
        }
        String today = today();
        String tail = ":" + goal(c) + ":" + design(c) + ":" + seed(c);
        for (java.util.Map.Entry<String, Long> d : days.entrySet()) {
            if (d.getKey().equals(today) || d.getValue() <= 0) continue;
            String old = byDay.get(d.getKey());
            String[] f = old == null ? null : old.split(":");
            long had = 0;
            try { if (f != null) had = Long.parseLong(f[1]); } catch (NumberFormatException ignored) {}
            if (d.getValue() <= had) continue;
            StringBuilder e = new StringBuilder(d.getKey()).append(':').append(d.getValue());
            if (f != null && f.length > 2) for (int i = 2; i < f.length; i++) e.append(':').append(f[i]);
            else e.append(tail);
            byDay.put(d.getKey(), e.toString());
        }
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String e : byDay.values()) {
            if (n++ >= HISTORY_DAYS) break;
            if (out.length() > 0) out.append(',');
            out.append(e);
        }
        p.edit().putString("history", out.toString()).apply();
    }

    /** Today's steps from the step counter, or from Health Connect plus what the counter saw since the last read. */
    static int today(Context c) {
        SharedPreferences p = prefs(c);
        int sensor = lastKnown(c);
        if (!healthSource(c) || !today().equals(p.getString("hc_day", null))) return sensor;
        return p.getInt("hc_steps", 0) + Math.max(0, sensor - p.getInt("hc_sensor", 0));
    }

    /* yesterday's total for the history, from whichever source was in use */
    private static int dayTotal(SharedPreferences p, String day) {
        int steps = p.getInt("steps", 0);
        if ("health".equals(p.getString("step_source", "phone")) && day.equals(p.getString("hc_day", null)))
            steps = p.getInt("hc_steps", 0) + Math.max(0, steps - p.getInt("hc_sensor", 0));
        return steps;
    }

    private static final int HISTORY_DAYS = 60;

    /** History is kept as "yyyyMMdd:steps:goal:design:seed,..." newest first (older entries may stop after steps). */
    private static void saveDay(SharedPreferences p, String day, int steps) {
        StringBuilder out = new StringBuilder(day).append(':').append(steps).append(':').append(Math.max(1000, p.getInt("goal", 10000)))
            .append(':').append(p.getString("design", "banyan")).append(':').append(p.getInt("seed", 48213));
        int kept = 1;
        for (String e : p.getString("history", "").split(",")) {
            if (e.isEmpty() || e.startsWith(day + ":")) continue;
            if (kept++ >= HISTORY_DAYS) break;
            out.append(',').append(e);
        }
        p.edit().putString("history", out.toString()).apply();
    }

    /** Finished days, newest first, as {yyyyMMdd, steps, [goal, design, seed]}. Today is not included. */
    static java.util.List<String[]> history(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String e : prefs(c).getString("history", "").split(",")) {
            String[] f = e.split(":");
            if (f.length >= 2) out.add(f);
        }
        return out;
    }

    /* today's steps by hour of day: steps between two readings count toward the hour of the later one */
    private static void addToHour(SharedPreferences p, SharedPreferences.Editor e, String day, int delta) {
        int[] h = day.equals(p.getString("hours_day", "")) ? parseHours(p.getString("hours", "")) : new int[24];
        h[java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)] += delta;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 24; i++) { if (i > 0) out.append(','); out.append(h[i]); }
        e.putString("hours", out.toString()).putString("hours_day", day);
    }

    private static int[] parseHours(String s) {
        int[] h = new int[24];
        String[] f = s.split(",");
        for (int i = 0; i < 24 && i < f.length; i++) {
            try { h[i] = Integer.parseInt(f[i]); } catch (NumberFormatException ignored) {}
        }
        return h;
    }

    /** Today's steps for each hour, 0 to 23. */
    static int[] hours(Context c) {
        SharedPreferences p = prefs(c);
        return today().equals(p.getString("hours_day", "")) ? parseHours(p.getString("hours", "")) : new int[24];
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
            main.post(() -> cb.onSteps(today(c), false));
            return;
        }
        final boolean[] done = { false };
        final SensorEventListener[] listener = new SensorEventListener[1];
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            sm.unregisterListener(listener[0]);
            cb.onSteps(today(c), false);
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
    static boolean remindOn(Context c) { return prefs(c).getBoolean("remind_on", false); }
    static int remindHour(Context c) { return prefs(c).getInt("remind_hour", 19); }
    /** Steps shown on the wallpaper and widget: real steps when allowed, otherwise the preview value. */
    static int shownSteps(Context c) {
        return useRealSteps(c) && hasPermission(c) && (hasStepSensor(c) || healthSource(c)) ? today(c) : manualSteps(c);
    }
}
