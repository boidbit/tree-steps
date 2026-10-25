package com.boidbit.treesteps;

import android.content.Context;
import android.graphics.Canvas;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.Calendar;

/**
 * Live wallpaper: the 3D tree grows with today's steps. Two update modes (Settings in the app):
 * "Live" keeps the step counter listening while the wallpaper is on screen; an interval (5-60 min)
 * only reads steps when the wallpaper becomes visible and then every N minutes, to save battery.
 * Tilt-to-look-around is optional. Nothing runs while the wallpaper is hidden.
 */
public class TreeWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new TreeEngine();
    }

    private class TreeEngine extends Engine implements SensorEventListener {
        private static final double BASE_AZ = 0.5, BASE_EL = 0.14;
        private static final int STEP_REBUILD = 50;              // live mode: rebuild the tree after this many new steps
        private static final int LABEL_STEP = 10;                // live mode: redraw the step count after this many
        private static final long SKY_TICK_MS = 10 * 60 * 1000;  // live mode: the sky follows the clock in 10-minute steps

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final TreeRenderer renderer = new TreeRenderer();
        private final Runnable drawRunner = this::animate;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!visible) return;
                if (!liveMode() && realSteps) refreshSteps(); else drawFrame();
                handler.postDelayed(this, tickMs());
            }
        };
        private SensorManager sensors;
        private Sensor accel, stepCounter;

        private TreeModel model;
        private float fullY = 6, fullR = 3;
        private int seed, goal, steps, modelSteps, intervalMin;
        private String pal, skyMode, labelStyle, labelPos, labelSize;
        private boolean realSteps, tiltOn;
        private boolean visible, sensorsOn;
        private int width, height;

        private double gx, gy = 7.5;                   // filtered gravity
        private double pageOffset = 0.5;
        private double az = BASE_AZ, el = BASE_EL, targetAz = BASE_AZ, targetEl = BASE_EL;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensors != null) {
                accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                stepCounter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            }
            loadSettings(true);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            stopAll();
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                loadSettings(false);
                startSensors();
                drawFrame();
                if (!liveMode() && realSteps) refreshSteps();   // e.g. right after unlocking
                handler.removeCallbacks(tick);
                handler.postDelayed(tick, tickMs());
            } else {
                stopAll();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w; height = h;
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            visible = false;
            stopAll();
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep, int xPixels, int yPixels) {
            pageOffset = xOffset;
            updateTargets();
        }

        private void stopAll() {
            stopSensors();
            handler.removeCallbacks(drawRunner);
            handler.removeCallbacks(tick);
        }

        /* ---------- settings and steps ---------- */

        private boolean liveMode() { return intervalMin <= 0; }

        private long tickMs() { return liveMode() ? SKY_TICK_MS : intervalMin * 60_000L; }

        private void loadSettings(boolean force) {
            Context c = TreeWallpaperService.this;
            int newSeed = StepStore.seed(c), newGoal = StepStore.goal(c);
            String newPal = StepStore.palette(c);
            realSteps = StepStore.useRealSteps(c) && StepStore.hasPermission(c) && stepCounter != null;
            intervalMin = StepStore.prefs(c).getInt("interval_min", 15);
            tiltOn = StepStore.tilt(c);
            skyMode = StepStore.skyMode(c);
            labelStyle = StepStore.labelStyle(c);
            labelPos = StepStore.labelPos(c);
            labelSize = StepStore.labelSize(c);
            if (!tiltOn) { gx = 0; gy = 7.5; updateTargets(); }

            boolean shapeChanged = force || model == null || newSeed != seed || !newPal.equals(pal);
            if (shapeChanged) {
                TreeModel full = new TreeModel(newSeed, newPal, 1, 1);
                fullY = Math.max(3, full.maxY); fullR = Math.max(1.5f, full.maxR);
            }
            steps = realSteps ? StepStore.lastKnown(c) : StepStore.manualSteps(c);
            if (shapeChanged || newGoal != goal || steps != modelSteps) {
                seed = newSeed; pal = newPal; goal = newGoal; modelSteps = steps;
                model = new TreeModel(seed, pal, modelSteps, goal);
            }
        }

        /** Interval mode: one sensor read, then redraw. */
        private void refreshSteps() {
            StepStore.readOnce(TreeWallpaperService.this, (s, fresh) -> {
                applySteps(s, 1, 1);
                if (visible) drawFrame();
            });
        }

        /** Updates the shown count and rebuilds the tree when enough steps have been added. */
        private boolean applySteps(int s, int rebuildEvery, int labelEvery) {
            boolean changed = false;
            if (Math.abs(s - modelSteps) >= rebuildEvery) {
                modelSteps = s;
                model = new TreeModel(seed, pal, s, goal);
                changed = true;
            }
            if (Math.abs(s - steps) >= labelEvery || changed) {
                steps = s;
                changed = true;
            }
            return changed;
        }

        private void startSensors() {
            if (sensors == null || sensorsOn) return;
            if (tiltOn && accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI, handler);
            if (liveMode() && realSteps) sensors.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL, handler);
            sensorsOn = true;
        }

        private void stopSensors() {
            if (sensors != null && sensorsOn) sensors.unregisterListener(this);
            sensorsOn = false;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                int s = StepStore.record(TreeWallpaperService.this, e.values[0]);
                if (applySteps(s, STEP_REBUILD, LABEL_STEP)) drawFrame();
                return;
            }
            gx = gx * 0.85 + e.values[0] * 0.15;
            gy = gy * 0.85 + e.values[1] * 0.15;
            updateTargets();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        /* ---------- camera and drawing ---------- */

        private void updateTargets() {
            double tiltX = Math.max(-1, Math.min(1, -gx / 9.81));
            double tiltY = Math.max(-1, Math.min(1, gy / 9.81 - 0.75));
            double nAz = BASE_AZ + tiltX * 0.6 + (pageOffset - 0.5) * 1.0;
            double nEl = Math.max(0.02, Math.min(0.45, BASE_EL + tiltY * 0.35));
            if (Math.abs(nAz - targetAz) > 0.008 || Math.abs(nEl - targetEl) > 0.008) {
                targetAz = nAz; targetEl = nEl;
                if (visible) {
                    handler.removeCallbacks(drawRunner);
                    handler.post(drawRunner);
                }
            }
        }

        private void animate() {
            az += (targetAz - az) * 0.18;
            el += (targetEl - el) * 0.18;
            drawFrame();
            if (visible && (Math.abs(targetAz - az) > 0.002 || Math.abs(targetEl - el) > 0.002)) {
                handler.postDelayed(drawRunner, 33);
            }
        }

        private void drawFrame() {
            if (!visible || model == null || width == 0 || height == 0) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = Build.VERSION.SDK_INT >= 26 ? holder.lockHardwareCanvas() : holder.lockCanvas();
                if (c != null) {
                    // frame between today's tree and the full-grown one: a small tree fills more of the
                    // screen, but growth through the day is still visible
                    float fitY = model.maxY * 0.6f + fullY * 0.4f + 0.3f;
                    float fitR = model.maxR * 0.6f + fullR * 0.4f + 0.3f;
                    double hour;
                    if ("sunset".equals(skyMode)) {
                        hour = 18.3;
                    } else {
                        Calendar now = Calendar.getInstance();
                        hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
                    }
                    TreeRenderer.Label label = realSteps && !"off".equals(labelStyle)
                        ? new TreeRenderer.Label(labelStyle, labelPos, labelSize, steps, goal) : null;
                    double restAz = BASE_AZ + (pageOffset - 0.5) * 1.0;
                    renderer.draw(c, width, height, model, fitY, fitR, az, el, restAz, hour, label);
                }
            } catch (RuntimeException ignored) {
                // surface went away mid-frame; the next visibility change redraws
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (RuntimeException ignored) {}
                }
            }
        }
    }
}
