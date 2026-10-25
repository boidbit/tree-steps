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
 * Live wallpaper: the illustrated tree (ArtRenderer) grows with today's steps. Two update modes
 * (Settings in the app): "Live" keeps the step counter listening while the wallpaper is on screen;
 * an interval (5-60 min) only reads steps when the wallpaper becomes visible and then every N
 * minutes, to save battery. Tilt and home-page swipes add a little parallax. Nothing runs while
 * the wallpaper is hidden.
 */
public class TreeWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new TreeEngine();
    }

    private class TreeEngine extends Engine implements SensorEventListener {
        private static final int LABEL_STEP = 10;                // live mode: redraw after this many new steps
        private static final long SKY_TICK_MS = 10 * 60 * 1000;  // live mode: the sky follows the clock in 10-minute steps

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ArtRenderer renderer = new ArtRenderer();
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

        private int seed, goal, steps, intervalMin;
        private String pal, skyMode, labelStyle, labelPos, labelSize;
        private boolean realSteps, tiltOn;
        private boolean visible, sensorsOn;
        private int width, height;

        private double gx;                             // filtered sideways gravity
        private double pageOffset = 0.5;
        private double par, targetPar;                 // parallax, -1.5..1.5

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensors != null) {
                accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                stepCounter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            }
            loadSettings();
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
                loadSettings();
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
            updateTarget();
        }

        private void stopAll() {
            stopSensors();
            handler.removeCallbacks(drawRunner);
            handler.removeCallbacks(tick);
        }

        /* ---------- settings and steps ---------- */

        private boolean liveMode() { return intervalMin <= 0; }

        private long tickMs() { return liveMode() ? SKY_TICK_MS : intervalMin * 60_000L; }

        private void loadSettings() {
            Context c = TreeWallpaperService.this;
            seed = StepStore.seed(c);
            pal = StepStore.palette(c);
            goal = StepStore.goal(c);
            realSteps = StepStore.useRealSteps(c) && StepStore.hasPermission(c) && stepCounter != null;
            intervalMin = StepStore.prefs(c).getInt("interval_min", 15);
            tiltOn = StepStore.tilt(c);
            skyMode = StepStore.skyMode(c);
            labelStyle = StepStore.labelStyle(c);
            labelPos = StepStore.labelPos(c);
            labelSize = StepStore.labelSize(c);
            steps = realSteps ? StepStore.lastKnown(c) : StepStore.manualSteps(c);
            if (!tiltOn) gx = 0;
            updateTarget();
            par = targetPar;
        }

        /** Interval mode: one sensor read, then redraw. */
        private void refreshSteps() {
            StepStore.readOnce(TreeWallpaperService.this, (s, fresh) -> {
                steps = s;
                if (visible) drawFrame();
            });
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
                if (Math.abs(s - steps) >= LABEL_STEP) {
                    steps = s;
                    drawFrame();
                }
                return;
            }
            gx = gx * 0.85 + e.values[0] * 0.15;
            updateTarget();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        /* ---------- parallax and drawing ---------- */

        private void updateTarget() {
            double tiltX = tiltOn ? Math.max(-1, Math.min(1, -gx / 6.0)) : 0;
            double n = Math.max(-1.5, Math.min(1.5, tiltX + (pageOffset - 0.5) * 1.5));
            if (Math.abs(n - targetPar) > 0.01) {
                targetPar = n;
                if (visible) {
                    handler.removeCallbacks(drawRunner);
                    handler.post(drawRunner);
                }
            }
        }

        private void animate() {
            par += (targetPar - par) * 0.2;
            drawFrame();
            if (visible && Math.abs(targetPar - par) > 0.003) handler.postDelayed(drawRunner, 33);
        }

        private void drawFrame() {
            if (!visible || width == 0 || height == 0) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = Build.VERSION.SDK_INT >= 26 ? holder.lockHardwareCanvas() : holder.lockCanvas();
                if (c != null) {
                    double hour;
                    if ("sunset".equals(skyMode)) {
                        hour = 18.3;
                    } else {
                        Calendar now = Calendar.getInstance();
                        hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
                    }
                    ArtRenderer.Label label = realSteps && !"off".equals(labelStyle)
                        ? new ArtRenderer.Label(labelStyle, labelPos, labelSize, steps, goal) : null;
                    renderer.draw(c, width, height, seed, pal, steps / (double) Math.max(1, goal), hour, par, label);
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
