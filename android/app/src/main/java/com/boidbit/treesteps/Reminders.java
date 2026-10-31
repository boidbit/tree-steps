package com.boidbit.treesteps;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.text.NumberFormat;
import java.util.Calendar;

/**
 * The evening reminder: once a day at the chosen hour, a nudge if today's goal isn't reached yet. Also a step
 * reading just before midnight, so the evening's last steps count toward that day rather than the next.
 */
final class Reminders {
    static final String ACTION = "com.boidbit.treesteps.REMIND";
    static final String DAY_END = "com.boidbit.treesteps.DAY_END";
    private static final String CHANNEL = "reminders";
    private static final int NOTIFICATION_ID = 1;

    private Reminders() {}

    /** Sets (or clears) the daily alarm from the current settings. Inexact, so Android can batch it. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, ReminderReceiver.class).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        scheduleDayEnd(c, am);
        if (!StepStore.remindOn(c)) return;
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, StepStore.remindHour(c));
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_MONTH, 1);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    /* between 11:45 and 11:55 PM (Android allows a 10 minute window without the exact-alarm permission) */
    private static void scheduleDayEnd(Context c, AlarmManager am) {
        PendingIntent pi = PendingIntent.getBroadcast(c, 8, new Intent(c, ReminderReceiver.class).setAction(DAY_END),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!StepStore.useRealSteps(c)) return;
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 23);
        next.set(Calendar.MINUTE, 45);
        next.set(Calendar.SECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_MONTH, 1);
        am.setWindow(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), 10 * 60_000L, pi);
    }

    static boolean canNotify(Context c) {
        return Build.VERSION.SDK_INT < 33
            || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Posts the reminder if real steps are on and today's goal is still ahead. */
    static void notifyIfBehind(Context c, int steps) {
        if (!StepStore.remindOn(c) || !StepStore.useRealSteps(c) || !canNotify(c)) return;
        int goal = StepStore.goal(c);
        if (steps >= goal) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT));
        }
        String design = StepStore.design(c);
        String tree = design.isEmpty() ? "tree" : Character.toUpperCase(design.charAt(0)) + design.substring(1);
        NumberFormat nf = NumberFormat.getIntegerInstance();
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tree)
            .setContentTitle(nf.format(goal - steps) + " steps to a full " + tree + " today")
            .setContentText("You're at " + nf.format(steps) + ". A short walk finishes today's tree.")
            .setContentIntent(open)
            .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(c).notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
            // permission was withdrawn between the check and the post
        }
    }
}
