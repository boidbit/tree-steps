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

/**
 * Live wallpaper: the 3D tree grows with today's steps, turns when you tilt the phone and
 * when you swipe between home-screen pages. It only redraws while something changes.
 */
public class TreeWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new TreeEngine();
    }

    private class TreeEngine extends Engine implements SensorEventListener {
        private static final double BASE_AZ = 0.5, BASE_EL = 0.14;
        private static final int STEP_REBUILD = 50;   // rebuild the tree after this many new steps

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final TreeRenderer renderer = new TreeRenderer();
        private final Runnable drawRunner = this::animate;
        private SensorManager sensors;
        private Sensor accel, stepCounter;

        private TreeModel model;
        private float fitY = 6, fitR = 3;
        private int seed, steps;
        private String pal;
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
            stopSensors();
            handler.removeCallbacks(drawRunner);
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                loadSettings(false);
                startSensors();
                drawFrame();
            } else {
                stopSensors();
                handler.removeCallbacks(drawRunner);
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
            stopSensors();
            handler.removeCallbacks(drawRunner);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep, int xPixels, int yPixels) {
            pageOffset = xOffset;
            updateTargets();
        }

        /* ---------- settings and steps ---------- */

        private void loadSettings(boolean force) {
            Context c = TreeWallpaperService.this;
            int newSeed = StepStore.seed(c);
            String newPal = StepStore.palette(c);
            int newSteps = currentSteps();
            boolean shapeChanged = force || model == null || newSeed != seed || !newPal.equals(pal);
            if (shapeChanged) {
                TreeModel full = new TreeModel(newSeed, newPal, 10000);
                fitY = Math.max(3, full.maxY); fitR = Math.max(1.5f, full.maxR);
            }
            if (shapeChanged || Math.abs(newSteps - steps) >= STEP_REBUILD || (newSteps == 0) != (steps == 0)) {
                seed = newSeed; pal = newPal; steps = newSteps;
                model = new TreeModel(seed, pal, steps);
            }
        }

        private int currentSteps() {
            Context c = TreeWallpaperService.this;
            if (StepStore.useRealSteps(c) && StepStore.hasPermission(c) && stepCounter != null) return StepStore.lastKnown(c);
            return StepStore.manualSteps(c);
        }

        private void startSensors() {
            if (sensors == null || sensorsOn) return;
            if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI, handler);
            Context c = TreeWallpaperService.this;
            if (stepCounter != null && StepStore.useRealSteps(c) && StepStore.hasPermission(c)) {
                sensors.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL, handler);
            }
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
                if (Math.abs(s - steps) >= STEP_REBUILD) {
                    steps = s;
                    model = new TreeModel(seed, pal, steps);
                    drawFrame();
                }
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
                if (c != null) renderer.draw(c, width, height, model, fitY, fitR, az, el);
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
