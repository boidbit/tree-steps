package com.boidbit.treesteps;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Home screen widgets: today's tree (drawn by ArtRenderer, like the wallpaper) with the step count.
 * Small (2×2) shows the tree and steps, medium (4×2) adds distance and calories, large (4×4) adds the week.
 * Android refreshes them every 30 minutes; new step readings also refresh them every 100 steps.
 */
public class TreeWidget extends AppWidgetProvider {
    static final int SMALL = 0, MEDIUM = 1, LARGE = 2;
    private static final Class<?>[] KINDS = { TreeWidget.class, TreeWidgetMedium.class, TreeWidgetLarge.class };
    private static final int STEP_REFRESH = 100;
    private static int lastSteps = -STEP_REFRESH;

    int size() { return SMALL; }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) render(c, m, id, size());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        render(c, m, id, size());
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int k = 0; k < KINDS.length; k++)
            for (int id : m.getAppWidgetIds(new ComponentName(c, KINDS[k]))) render(c, m, id, k);
    }

    static void stepsChanged(Context c, int steps) {
        if (Math.abs(steps - lastSteps) < STEP_REFRESH) return;
        lastSteps = steps;
        updateAll(c);
    }

    /** How many Tree Steps widgets are on the home screen, of any size. */
    static int placed(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int n = 0;
        for (Class<?> k : KINDS) n += m.getAppWidgetIds(new ComponentName(c, k)).length;
        return n;
    }

    /** Asks the launcher to add a widget of this size (the launcher shows its own confirmation). */
    static boolean pin(Context c, int size) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.isRequestPinAppWidgetSupported()
            && m.requestPinAppWidget(new ComponentName(c, KINDS[Math.max(0, Math.min(LARGE, size))]), null, null);
    }

    private static void render(Context c, AppWidgetManager m, int id, int size) {
        Bundle o = m.getAppWidgetOptions(id);
        float density = c.getResources().getDisplayMetrics().density;
        int wDp = Math.max(110, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size == SMALL ? 160 : 320));
        int hDp = Math.max(110, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size == LARGE ? 320 : 160));
        int maxPx = size == SMALL ? 420 : 560;          // keeps the bitmaps well under the RemoteViews size limit
        float scale = Math.min(1f, maxPx / (Math.max(wDp, hDp) * density));
        int w = Math.max(1, Math.round(wDp * density * scale)), h = Math.max(1, Math.round(hDp * density * scale));

        int steps = StepStore.shownSteps(c), goal = StepStore.goal(c), streak = StepStore.streak(c);
        long now = System.currentTimeMillis();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        new ArtRenderer().draw(new Canvas(bmp), w, h, StepStore.seed(c), StepStore.design(c), steps / (double) goal,
            now, StepStore.skyMode(c), 0, null, streak, StepStore.wilt(c, now));

        NumberFormat nf = NumberFormat.getIntegerInstance();
        RemoteViews rv = new RemoteViews(c.getPackageName(),
            size == SMALL ? R.layout.tree_widget : size == MEDIUM ? R.layout.tree_widget_medium : R.layout.tree_widget_large);
        rv.setImageViewBitmap(R.id.widget_art, bmp);
        if (size == SMALL) {
            rv.setTextViewText(R.id.widget_steps, nf.format(steps) + " / " + nf.format(goal));
        } else {
            double km = steps * StepStore.prefs(c).getInt("height_cm", 170) * 0.415 / 100 / 1000;
            long kcal = Math.round(steps * 0.04 * StepStore.prefs(c).getInt("weight_kg", 70) / 70.0);
            rv.setTextViewText(R.id.widget_steps, nf.format(steps));
            rv.setTextViewText(R.id.widget_goal, "of " + nf.format(goal) + " steps");
            rv.setProgressBar(R.id.widget_progress, 100, (int) Math.min(100, steps * 100L / goal), false);
            rv.setTextViewText(R.id.widget_stats, String.format(Locale.getDefault(), "%.1f km · %s kcal", km, nf.format(kcal))
                + (streak > 0 ? " · " + streak + "-day streak" : ""));
            if (size == LARGE) rv.setImageViewBitmap(R.id.widget_week, week(c, steps, goal, Math.round(w * 0.9f), Math.round(64 * density * scale)));
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, rv);
    }

    /* the last seven days as bars (today on the right, green where the goal was reached), with day letters */
    private static Bitmap week(Context c, int todaySteps, int goal, int w, int h) {
        Map<String, Integer> byDay = new HashMap<>();
        for (String[] d : StepStore.history(c)) {
            try { byDay.put(d[0], Integer.parseInt(d[1])); } catch (NumberFormatException ignored) {}
        }
        int[] steps = new int[7];
        String[] letters = new String[7];
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat key = new SimpleDateFormat("yyyyMMdd", Locale.US), day = new SimpleDateFormat("EEEEE", Locale.getDefault());
        int max = goal;
        for (int i = 6; i >= 0; i--) {
            steps[i] = i == 6 ? todaySteps : byDay.containsKey(key.format(cal.getTime())) ? byDay.get(key.format(cal.getTime())) : 0;
            letters[i] = day.format(cal.getTime());
            max = Math.max(max, steps[i]);
            cal.add(Calendar.DATE, -1);
        }
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float label = h * 0.26f, chartH = h - label * 1.5f, slot = w / 7f, bw = slot * 0.46f;
        p.setTextSize(label);
        p.setTextAlign(Paint.Align.CENTER);
        float goalY = chartH * (1 - goal / (float) max);
        p.setColor(0x40FFFFFF);
        cv.drawRect(0, goalY, w, goalY + Math.max(1, h / 60f), p);
        for (int i = 0; i < 7; i++) {
            float cx = slot * (i + 0.5f), bh = Math.max(bw * 0.5f, chartH * steps[i] / (float) max);
            p.setColor(steps[i] >= goal ? 0xFF9BE07A : i == 6 ? 0xE6FFFFFF : 0x80FFFFFF);
            cv.drawRoundRect(new RectF(cx - bw / 2, chartH - bh, cx + bw / 2, chartH), bw / 2, bw / 2, p);
            p.setColor(i == 6 ? 0xFFFFFFFF : 0xB3FFFFFF);
            p.setTypeface(i == 6 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            cv.drawText(letters[i], cx, h - label * 0.2f, p);
        }
        return b;
    }
}
