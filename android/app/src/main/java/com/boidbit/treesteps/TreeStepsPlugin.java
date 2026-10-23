package com.boidbit.treesteps;

import android.Manifest;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** Bridge for Tree3D.html: real steps, set-as-wallpaper, and opening the live wallpaper picker. */
@CapacitorPlugin(
    name = "TreeSteps",
    permissions = { @Permission(alias = "activity", strings = { Manifest.permission.ACTIVITY_RECOGNITION }) }
)
public class TreeStepsPlugin extends Plugin {

    private Sensor stepSensor() {
        SensorManager sm = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        return sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
    }

    private boolean hasStepAccess() {
        return Build.VERSION.SDK_INT < 29 || getPermissionState("activity") == PermissionState.GRANTED;
    }

    /** Asks for "Physical activity" permission and turns on real steps for the app and wallpaper. */
    @PluginMethod
    public void enableSteps(PluginCall call) {
        if (stepSensor() == null) {
            JSObject r = new JSObject();
            r.put("granted", false);
            r.put("reason", "nosensor");
            call.resolve(r);
            return;
        }
        if (hasStepAccess()) {
            stepsDecided(call);
        } else {
            requestPermissionForAlias("activity", call, "stepsPermissionResult");
        }
    }

    @PermissionCallback
    private void stepsPermissionResult(PluginCall call) {
        stepsDecided(call);
    }

    private void stepsDecided(PluginCall call) {
        boolean ok = hasStepAccess();
        StepStore.prefs(getContext()).edit().putBoolean("use_real", ok).apply();
        JSObject r = new JSObject();
        r.put("granted", ok);
        if (!ok) r.put("reason", "permission");
        call.resolve(r);
    }

    /** Reads today's steps. Falls back to the last known value if the sensor stays quiet. */
    @PluginMethod
    public void getSteps(PluginCall call) {
        final Context ctx = getContext();
        JSObject r = new JSObject();
        final Sensor sensor = stepSensor();
        if (sensor == null || !hasStepAccess() || !StepStore.useRealSteps(ctx)) {
            r.put("available", false);
            r.put("reason", sensor == null ? "nosensor" : !hasStepAccess() ? "permission" : "off");
            call.resolve(r);
            return;
        }
        final SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] done = { false };
        final SensorEventListener[] holder = new SensorEventListener[1];
        final Runnable[] timeout = new Runnable[1];

        holder[0] = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                finish(StepStore.record(ctx, e.values[0]), true);
            }

            @Override
            public void onAccuracyChanged(Sensor s, int a) {}

            private void finish(int steps, boolean fresh) {
                if (done[0]) return;
                done[0] = true;
                sm.unregisterListener(this);
                main.removeCallbacks(timeout[0]);
                JSObject out = new JSObject();
                out.put("available", true);
                out.put("steps", steps);
                out.put("fresh", fresh);
                call.resolve(out);
            }
        };
        timeout[0] = () -> {
            if (done[0]) return;
            done[0] = true;
            sm.unregisterListener(holder[0]);
            JSObject out = new JSObject();
            out.put("available", true);
            out.put("steps", StepStore.lastKnown(ctx));
            out.put("fresh", false);
            call.resolve(out);
        };
        sm.registerListener(holder[0], sensor, SensorManager.SENSOR_DELAY_NORMAL, main);
        main.postDelayed(timeout[0], 2500);
    }

    /** Stores the tree look so the live wallpaper draws the same tree. */
    @PluginMethod
    public void saveSettings(PluginCall call) {
        android.content.SharedPreferences.Editor e = StepStore.prefs(getContext()).edit();
        Integer seed = call.getInt("seed");
        String pal = call.getString("pal");
        Integer manual = call.getInt("manualSteps");
        Boolean useReal = call.getBoolean("useReal");
        if (useReal != null) e.putBoolean("use_real", useReal);
        if (seed != null) e.putInt("seed", seed);
        if (pal != null) e.putString("pal", pal);
        if (manual != null) e.putInt("manual_steps", manual);
        e.apply();
        call.resolve();
    }

    /** Sets a still image (a JPEG/PNG data URL) as the home screen, lock screen or both. */
    @PluginMethod
    public void setWallpaper(PluginCall call) {
        final String image = call.getString("image");
        final String target = call.getString("target", "both");
        if (image == null) {
            call.reject("No image");
            return;
        }
        new Thread(() -> {
            try {
                String b64 = image.contains(",") ? image.substring(image.indexOf(',') + 1) : image;
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) {
                    call.reject("Could not read the image");
                    return;
                }
                WallpaperManager wm = WallpaperManager.getInstance(getContext());
                if (Build.VERSION.SDK_INT >= 24) {
                    int flags = "home".equals(target) ? WallpaperManager.FLAG_SYSTEM
                        : "lock".equals(target) ? WallpaperManager.FLAG_LOCK
                        : WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
                    wm.setBitmap(bmp, null, true, flags);
                } else {
                    wm.setBitmap(bmp);
                }
                call.resolve();
            } catch (Exception ex) {
                call.reject("Could not set wallpaper: " + ex.getMessage());
            }
        }).start();
    }

    /** Opens the system screen to apply the Tree Steps live wallpaper. */
    @PluginMethod
    public void openLiveWallpaper(PluginCall call) {
        Context ctx = getContext();
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(ctx, TreeWallpaperService.class));
        try {
            getActivity().startActivity(i);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            try {
                getActivity().startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
                call.resolve();
            } catch (ActivityNotFoundException e2) {
                call.reject("This phone has no live wallpaper picker");
            }
        }
    }
}
