package com.boidbit.treesteps;

import android.Manifest;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;

/** Bridge for Tree3D.html: real steps and history, settings, reminders, sharing, and the live wallpaper picker. */
@CapacitorPlugin(
    name = "TreeSteps",
    permissions = {
        @Permission(alias = "activity", strings = { Manifest.permission.ACTIVITY_RECOGNITION }),
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
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
        r.put("design", StepStore.design(c));
        r.put("manualSteps", StepStore.manualSteps(c));
        r.put("useReal", StepStore.useRealSteps(c) && hasStepAccess());
        r.put("goal", StepStore.goal(c));
        r.put("labelStyle", StepStore.labelStyle(c));
        r.put("labelPos", StepStore.labelPos(c));
        r.put("labelSize", StepStore.labelSize(c));
        r.put("intervalMin", StepStore.prefs(c).getInt("interval_min", 15));
        r.put("tilt", StepStore.tilt(c));
        r.put("skyMode", StepStore.skyMode(c));
        r.put("remindOn", StepStore.remindOn(c) && Reminders.canNotify(c));
        r.put("remindHour", StepStore.remindHour(c));
        r.put("heightCm", StepStore.prefs(c).getInt("height_cm", 170));
        r.put("weightKg", StepStore.prefs(c).getInt("weight_kg", 70));
        r.put("hasSensor", StepStore.hasStepSensor(c));
        call.resolve(r);
    }

    /** Finished days (newest first) plus today's key, for the week chart and the streak. */
    @PluginMethod
    public void getHistory(PluginCall call) {
        JSArray days = new JSArray();
        Context c = getContext();
        for (String[] d : StepStore.history(c)) {
            JSObject o = new JSObject();
            try {
                o.put("day", d[0]);
                o.put("steps", Integer.parseInt(d[1]));
                o.put("goal", d.length > 2 ? Integer.parseInt(d[2]) : StepStore.goal(c));
                o.put("design", d.length > 3 ? d[3] : StepStore.design(c));
                o.put("seed", d.length > 4 ? Integer.parseInt(d[4]) : StepStore.seed(c));
            } catch (NumberFormatException e) {
                continue;
            }
            days.put(o);
        }
        JSArray hours = new JSArray();
        for (int h : StepStore.hours(c)) hours.put(h);
        JSObject r = new JSObject();
        r.put("today", StepStore.todayKey());
        r.put("days", days);
        r.put("hours", hours);
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
        putString(e, call, "design", "design");
        putString(e, call, "labelStyle", "label_style");
        putString(e, call, "labelPos", "label_pos");
        putString(e, call, "labelSize", "label_size");
        putString(e, call, "skyMode", "sky_mode");
        putBool(e, call, "useReal", "use_real");
        putBool(e, call, "tilt", "tilt");
        putBool(e, call, "remindOn", "remind_on");
        putInt(e, call, "remindHour", "remind_hour");
        putInt(e, call, "heightCm", "height_cm");
        putInt(e, call, "weightKg", "weight_kg");
        e.apply();
        Reminders.schedule(getContext());
        TreeWidget.updateAll(getContext());
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

    /** Asks for notification permission (Android 13+) and turns on the evening reminder. */
    @PluginMethod
    public void enableReminders(PluginCall call) {
        if (Reminders.canNotify(getContext())) remindersDecided(call);
        else requestPermissionForAlias("notifications", call, "notificationsResult");
    }

    @PermissionCallback
    private void notificationsResult(PluginCall call) {
        remindersDecided(call);
    }

    private void remindersDecided(PluginCall call) {
        boolean ok = Reminders.canNotify(getContext());
        StepStore.prefs(getContext()).edit().putBoolean("remind_on", ok).apply();
        Reminders.schedule(getContext());
        JSObject r = new JSObject();
        r.put("granted", ok);
        call.resolve(r);
    }

    /** Shares an image (a JPEG data URL) with optional text through Android's share sheet. */
    @PluginMethod
    public void shareImage(PluginCall call) {
        String image = call.getString("image");
        String text = call.getString("text", "");
        if (image == null) {
            call.reject("No image");
            return;
        }
        try {
            byte[] bytes = Base64.decode(image.substring(image.indexOf(',') + 1), Base64.DEFAULT);
            File dir = new File(getContext().getCacheDir(), "share");
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("no cache folder");
            File file = new File(dir, "tree.jpg");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Share your tree").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(chooser);
            call.resolve();
        } catch (Exception ex) {
            call.reject("Could not share: " + ex.getMessage());
        }
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
