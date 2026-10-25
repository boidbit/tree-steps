package com.boidbit.treesteps;

import android.Manifest;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** Bridge for Tree3D.html: real steps, the settings the wallpaper reads, and the live wallpaper picker. */
@CapacitorPlugin(
    name = "TreeSteps",
    permissions = { @Permission(alias = "activity", strings = { Manifest.permission.ACTIVITY_RECOGNITION }) }
)
public class TreeStepsPlugin extends Plugin {

    private boolean hasStepAccess() {
        return Build.VERSION.SDK_INT < 29 || getPermissionState("activity") == PermissionState.GRANTED;
    }

    /** Asks for "Physical activity" permission and turns on real steps for the app and wallpaper. */
    @PluginMethod
    public void enableSteps(PluginCall call) {
        if (!StepStore.hasStepSensor(getContext())) {
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

    /** Reads today's steps once (falls back to the last known value if the sensor stays quiet). */
    @PluginMethod
    public void getSteps(PluginCall call) {
        Context ctx = getContext();
        if (!StepStore.hasStepSensor(ctx) || !hasStepAccess() || !StepStore.useRealSteps(ctx)) {
            JSObject r = new JSObject();
            r.put("available", false);
            r.put("reason", !StepStore.hasStepSensor(ctx) ? "nosensor" : !hasStepAccess() ? "permission" : "off");
            call.resolve(r);
            return;
        }
        StepStore.readOnce(ctx, (steps, fresh) -> {
            JSObject r = new JSObject();
            r.put("available", true);
            r.put("steps", steps);
            r.put("fresh", fresh);
            call.resolve(r);
        });
    }

    /** Everything the settings screen shows, as stored for the wallpaper. */
    @PluginMethod
    public void getSettings(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        r.put("seed", StepStore.seed(c));
        r.put("pal", StepStore.palette(c));
        r.put("manualSteps", StepStore.manualSteps(c));
        r.put("useReal", StepStore.useRealSteps(c) && hasStepAccess());
        r.put("goal", StepStore.goal(c));
        r.put("labelStyle", StepStore.labelStyle(c));
        r.put("labelPos", StepStore.labelPos(c));
        r.put("labelSize", StepStore.labelSize(c));
        r.put("intervalMin", StepStore.prefs(c).getInt("interval_min", 15));
        r.put("tilt", StepStore.tilt(c));
        r.put("skyMode", StepStore.skyMode(c));
        r.put("hasSensor", StepStore.hasStepSensor(c));
        call.resolve(r);
    }

    /** Stores any of the settings (same names as getSettings) so the live wallpaper picks them up. */
    @PluginMethod
    public void saveSettings(PluginCall call) {
        SharedPreferences.Editor e = StepStore.prefs(getContext()).edit();
        putInt(e, call, "seed", "seed");
        putInt(e, call, "manualSteps", "manual_steps");
        putInt(e, call, "goal", "goal");
        putInt(e, call, "intervalMin", "interval_min");
        putString(e, call, "pal", "pal");
        putString(e, call, "labelStyle", "label_style");
        putString(e, call, "labelPos", "label_pos");
        putString(e, call, "labelSize", "label_size");
        putString(e, call, "skyMode", "sky_mode");
        putBool(e, call, "useReal", "use_real");
        putBool(e, call, "tilt", "tilt");
        e.apply();
        call.resolve();
    }

    private static void putInt(SharedPreferences.Editor e, PluginCall call, String key, String pref) {
        Integer v = call.getInt(key);
        if (v != null) e.putInt(pref, v);
    }

    private static void putString(SharedPreferences.Editor e, PluginCall call, String key, String pref) {
        String v = call.getString(key);
        if (v != null) e.putString(pref, v);
    }

    private static void putBool(SharedPreferences.Editor e, PluginCall call, String key, String pref) {
        Boolean v = call.getBoolean(key);
        if (v != null) e.putBoolean(pref, v);
    }

    /** Whether Tree Steps is the current live wallpaper. */
    @PluginMethod
    public void wallpaperStatus(PluginCall call) {
        WallpaperInfo info = WallpaperManager.getInstance(getContext()).getWallpaperInfo();
        JSObject r = new JSObject();
        r.put("active", info != null && info.getComponent().equals(new ComponentName(getContext(), TreeWallpaperService.class)));
        call.resolve(r);
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
