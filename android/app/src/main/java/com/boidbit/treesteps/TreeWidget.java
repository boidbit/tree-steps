package com.boidbit.treesteps;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.text.NumberFormat;

/**
 * Home screen widget: today's tree (drawn by ArtRenderer, like the wallpaper) and the step count.
 * Android refreshes it every 30 minutes; new step readings also refresh it every 100 steps.
 */
public class TreeWidget extends AppWidgetProvider {
    private static final int MAX_PX = 420;          // keeps the bitmap well under the RemoteViews size limit
    private static final int STEP_REFRESH = 100;
    private static int lastSteps = -STEP_REFRESH;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) render(c, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        render(c, m, id);
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, TreeWidget.class))) render(c, m, id);
    }

    static void stepsChanged(Context c, int steps) {
        if (Math.abs(steps - lastSteps) < STEP_REFRESH) return;
        lastSteps = steps;
        updateAll(c);
    }

    private static void render(Context c, AppWidgetManager m, int id) {
        Bundle o = m.getAppWidgetOptions(id);
        float density = c.getResources().getDisplayMetrics().density;
        int wDp = Math.max(110, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160));
        int hDp = Math.max(110, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160));
        float scale = Math.min(1f, MAX_PX / (Math.max(wDp, hDp) * density));
        int w = Math.max(1, Math.round(wDp * density * scale)), h = Math.max(1, Math.round(hDp * density * scale));

        int steps = StepStore.shownSteps(c), goal = StepStore.goal(c);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        new ArtRenderer().draw(new Canvas(bmp), w, h, StepStore.seed(c), StepStore.design(c), steps / (double) goal,
            System.currentTimeMillis(), StepStore.skyMode(c), 0, null);

        NumberFormat nf = NumberFormat.getIntegerInstance();
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.tree_widget);
        rv.setImageViewBitmap(R.id.widget_art, bmp);
        rv.setTextViewText(R.id.widget_steps, nf.format(steps) + " / " + nf.format(goal));
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, rv);
    }
}
