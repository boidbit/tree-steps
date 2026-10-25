package com.boidbit.treesteps;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Arrays;

/**
 * Draws a TreeModel on a Canvas with a simple perspective camera orbiting the tree.
 * Everything (branches, leaves, blossoms, grass) is depth-sorted and painted back to front,
 * so turning the camera shows the tree from a genuinely different side.
 */
final class TreeRenderer {
    private static final double VFOV = Math.toRadians(48);
    private static final int GRASS = 420, STARS = 140, FLIES = 36;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private long[] keys = new long[0];
    private float[] pz = new float[0];

    // camera basis, updated per frame
    private double cxw, cyw, czw, rx, ry, rz, ux, uy, uz, fxw, fyw, fzw, focal, scx, scy;

    /* sky keyframes through the day: hour, sky type, light on the ground and tree, star brightness */
    private static final double[] KEY_HOURS = { 0, 5, 6.5, 8, 16.5, 18.3, 19.8, 24 };
    private static final int NIGHT = 0, DAWN = 1, DAY = 2, DUSK = 3;
    private static final int[] KEY_SKY = { NIGHT, NIGHT, DAWN, DAY, DAY, DUSK, NIGHT, NIGHT };
    private static final double[] KEY_LIGHT = { 0.55, 0.55, 0.82, 1.08, 1.08, 0.92, 0.55, 0.55 };
    private static final double[] KEY_STARS = { 1, 1, 0.25, 0, 0, 0.5, 1, 1 };
    private static final double[][][] FIXED_SKY = {
        { { 5, 8, 22 }, { 13, 24, 56 }, { 35, 48, 90 } },            // night
        { { 42, 58, 120 }, { 199, 125, 158 }, { 255, 200, 154 } },   // dawn
        { { 47, 111, 208 }, { 111, 168, 232 }, { 207, 230, 245 } },  // day
        null                                                          // dusk: the colour set's own sunset
    };

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * @param fitY height used to frame the camera (TreeWallpaperService blends today's tree with the full one)
     * @param fitR horizontal radius used to frame the camera
     * @param restAz camera angle without tilt (only home-page swipes), anchors the sun and moon
     * @param hour local time as hours (0-24), picks the sky
     * @param label text under the tree, or null for none
     */
    void draw(Canvas c, int W, int H, TreeModel m, float fitY, float fitR, double az, double el, double restAz,
              double hour, String label) {
        double[][] pal = m.pal;
        focal = (H / 2.0) / Math.tan(VFOV / 2);
        scx = W / 2.0;
        scy = H * 0.58;
        double R = Math.max(focal * fitY / (0.56 * H), focal * 2 * fitR / (0.9 * W)) + fitR * 0.6;
        R = Math.max(R, 3.5);

        /* time of day */
        int ki = 0;
        while (ki < KEY_HOURS.length - 2 && hour >= KEY_HOURS[ki + 1]) ki++;
        double ku = TreeModel.clamp01((hour - KEY_HOURS[ki]) / (KEY_HOURS[ki + 1] - KEY_HOURS[ki]));
        double[][] skyA = skyColours(KEY_SKY[ki], pal), skyB = skyColours(KEY_SKY[ki + 1], pal);
        double[] skyTop = TreeModel.mix(skyA[0], skyB[0], ku), skyMid = TreeModel.mix(skyA[1], skyB[1], ku);
        double light = KEY_LIGHT[ki] + (KEY_LIGHT[ki + 1] - KEY_LIGHT[ki]) * ku;
        double starAlpha = KEY_STARS[ki] + (KEY_STARS[ki + 1] - KEY_STARS[ki]) * ku;
        boolean sun = hour >= 6.3 && hour < 18.2;
        double tx = 0, ty = fitY * 0.5, tz = 0;
        cxw = tx + R * Math.sin(az) * Math.cos(el);
        cyw = ty + R * Math.sin(el);
        czw = tz + R * Math.cos(az) * Math.cos(el);
        fxw = tx - cxw; fyw = ty - cyw; fzw = tz - czw;
        double fl = Math.sqrt(fxw * fxw + fyw * fyw + fzw * fzw);
        fxw /= fl; fyw /= fl; fzw /= fl;
        rx = -fzw; ry = 0; rz = fxw;                               // forward x up
        double rl = Math.sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl;
        ux = ry * fzw - rz * fyw; uy = rz * fxw - rx * fzw; uz = rx * fyw - ry * fxw;  // right x forward

        double[] horizonC = TreeModel.mix(skyA[2], skyB[2], ku);
        float horizonY = (float) (scy - focal * Math.tan(el));

        /* sky */
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, horizonY,
            new int[] { TreeModel.argb(skyTop), TreeModel.argb(skyMid), TreeModel.argb(horizonC) },
            new float[] { 0f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, Math.max(horizonY, 0), paint);
        paint.setShader(null);

        /* stars, then the sun by day or the moon (directions only, so they sit at infinity) */
        if (starAlpha > 0.02) {
            for (int i = 0; i < STARS; i++) {
                double a = TreeModel.hash(m.seed, 50000 + i) * Math.PI * 2, b = Math.acos(1 - TreeModel.hash(m.seed, 51000 + i) * 0.9);
                if (projectDir(Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a))) {
                    if (projY > horizonY - 4) continue;
                    paint.setColor(0xffffffff);
                    paint.setAlpha((int) ((60 + TreeModel.hash(m.seed, 52000 + i) * 150) * starAlpha));
                    c.drawCircle(projX, projY, (float) (1 + TreeModel.hash(m.seed, 53000 + i) * 1.6), paint);
                }
            }
            paint.setAlpha(255);
        }
        // sun/moon sit a little right of the resting view (restAz, which follows home-page swipes) and
        // only drift a third as far as the camera when you tilt, so they always stay on screen
        double skyAz = restAz - 0.11 + (az - restAz) * 0.35;
        if (projectDir(-Math.sin(skyAz), 0.13, -Math.cos(skyAz))) {
            float mr = W * (sun ? 0.07f : 0.06f);
            double[] discC = sun ? new double[] { 255, 243, 196 } : TreeModel.mix(pal[4], new double[] { 255, 255, 255 }, 0.4);
            int glowC = sun ? 0xfffff0c0 : TreeModel.argb(pal[4]);
            paint.setShader(new RadialGradient(projX, projY, mr * 6, (glowC & 0x00ffffff) | (sun ? 0x99000000 : 0x80000000), glowC & 0x00ffffff, Shader.TileMode.CLAMP));
            c.drawCircle(projX, projY, mr * 6, paint);
            paint.setShader(null);
            paint.setColor(TreeModel.argb(discC));
            c.drawCircle(projX, projY, mr, paint);
        }

        /* distant hills, sliding with the camera angle */
        double[] hillC = scale(TreeModel.mix(TreeModel.mix(pal[1], pal[0], 0.35), horizonC, 0.25), light);
        paint.setColor(TreeModel.argb(hillC));
        path.reset(); path.moveTo(0, H);
        for (int x = 0; x <= W + 8; x += 8) {
            double u = x / (double) W * 3 + az * 1.6;
            double h = (Math.sin(u * 2.1 + m.seed % 7) * 0.5 + Math.sin(u * 4.7 + 1.3) * 0.3 + Math.sin(u * 9.3) * 0.12 + 1) * 0.5;
            path.lineTo(x, (float) (horizonY - h * H * 0.05));
        }
        path.lineTo(W, H); path.close();
        c.drawPath(path, paint);

        /* ground */
        paint.setShader(new LinearGradient(0, horizonY, 0, H,
            TreeModel.argb(scale(TreeModel.mix(TreeModel.mix(pal[1], pal[0], 0.55), horizonC, 0.2), light)),
            TreeModel.argb(scale(TreeModel.mix(pal[0], new double[] { 0, 0, 0 }, 0.35), light)), Shader.TileMode.CLAMP));
        c.drawRect(0, horizonY, W, H, paint);
        paint.setShader(null);

        /* soft shadow under the tree */
        path.reset();
        double shadowR = Math.max(0.5, Math.min(2.2, m.maxR * 0.8 + 0.3));
        for (int i = 0; i <= 24; i++) {
            double a = i / 24.0 * Math.PI * 2;
            if (!project(Math.cos(a) * shadowR, 0, Math.sin(a) * shadowR)) continue;
            if (i == 0) path.moveTo(projX, projY); else path.lineTo(projX, projY);
        }
        path.close();
        paint.setColor(0x55000000);
        c.drawPath(path, paint);

        /* everything that needs depth sorting */
        int n = m.nb + m.nl + m.nbl + GRASS;
        if (keys.length < n) { keys = new long[n]; pz = new float[n]; }
        int k = 0;
        for (int i = 0; i < m.nb; i++, k++) {
            double mx = (m.bx0[i] + m.bx1[i]) / 2, my = (m.by0[i] + m.by1[i]) / 2, mz = (m.bz0[i] + m.bz1[i]) / 2;
            pz[k] = (float) depth(mx, my, mz);
        }
        for (int i = 0; i < m.nl; i++, k++) pz[k] = (float) depth(m.lx[i], m.ly[i], m.lz[i]);
        for (int i = 0; i < m.nbl; i++, k++) pz[k] = (float) depth(m.fx[i], m.fy[i], m.fz[i]);
        for (int i = 0; i < GRASS; i++, k++) pz[k] = (float) depth(grassX(m, i), 0, grassZ(m, i));
        for (int i = 0; i < n; i++) keys[i] = ((10_000_000L - (long) (pz[i] * 1000)) << 20) | i;
        Arrays.sort(keys, 0, n);

        double centerZ = R, spanZ = Math.max(1.5, fitR * 2);
        double treeLight = 0.72 + 0.28 * light;                // keep the tree readable at night
        double thin = 0.45 + 0.55 * Math.min(1, m.t * 2);      // young trees get a slim trunk, not a stump
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int s = 0; s < n; s++) {
            int i = (int) (keys[s] & 0xfffff);
            float z = pz[i];
            if (z < 0.2f) continue;
            float shade = (float) (treeLight * Math.max(0.68, Math.min(1.12, 1.0 - (z - centerZ) / spanZ * 0.45)));
            if (i < m.nb) {
                if (!project(m.bx0[i], m.by0[i], m.bz0[i])) continue;
                float x0 = projX, y0 = projY;
                if (!project(m.bx1[i], m.by1[i], m.bz1[i])) continue;
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(shadeColor(m.bcol[i], shade));
                paint.setStrokeWidth((float) Math.max(1, focal * m.bw[i] * 1.72 * thin / z));
                c.drawLine(x0, y0, projX, projY, paint);
                continue;
            }
            paint.setStyle(Paint.Style.FILL);
            int j = i - m.nb;
            if (j < m.nl) {
                if (!project(m.lx[j], m.ly[j], m.lz[j])) continue;
                float r = (float) (focal * m.ls[j] / z);
                float a = r, b = r * 0.55f;
                if (m.lvert[j]) { float tmp = a; a = b; b = tmp; }
                oval.set(projX - a, projY - b, projX + a, projY + b);
                paint.setColor(shadeColor(m.lcol[j], shade));
                c.drawOval(oval, paint);
                continue;
            }
            j -= m.nl;
            if (j < m.nbl) {
                if (!project(m.fx[j], m.fy[j], m.fz[j])) continue;
                paint.setColor(m.bloomColor);
                c.drawCircle(projX, projY, (float) Math.max(1, focal * m.fs[j] / z), paint);
                continue;
            }
            j -= m.nbl;
            if (z < R * 0.55) continue;                    // blades right under the camera look like rain streaks
            double gx = grassX(m, j), gz = grassZ(m, j), gh = 0.06 + TreeModel.hash(m.seed, 22000 + j) * 0.16;
            if (!project(gx, 0, gz)) continue;
            float x0 = projX, y0 = projY;
            double lean = (TreeModel.hash(m.seed, 23000 + j) - 0.5) * 0.08;
            if (!project(gx + lean, gh, gz)) continue;
            if (Math.abs(projY - y0) > H * 0.025) continue;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((float) Math.max(1, focal * 0.022 / z));
            int gc = shadeColor(TreeModel.argb(TreeModel.mix(TreeModel.mix(pal[0], pal[1], 0.7), pal[2],
                TreeModel.hash(m.seed, 26000 + j) * 0.35)), shade);
            paint.setColor((gc & 0x00ffffff) | 0xb4000000);
            c.drawLine(x0, y0, projX, projY, paint);
        }

        /* fireflies once the tree is nearly grown */
        if (m.t > 0.6) {
            int count = (int) ((m.t - 0.6) / 0.4 * FLIES);
            paint.setStyle(Paint.Style.FILL);
            int fc = TreeModel.argb(pal[4]) & 0x00ffffff;
            for (int i = 0; i < count; i++) {
                double a = TreeModel.hash(m.seed, 34000 + i) * Math.PI * 2, rr = 0.8 + TreeModel.hash(m.seed, 35000 + i) * 6;
                if (!project(Math.cos(a) * rr, 0.6 + TreeModel.hash(m.seed, 36000 + i) * 5, Math.sin(a) * rr)) continue;
                float r = (float) (focal * 0.09 / projZ);
                paint.setColor(fc | 0x40000000); c.drawCircle(projX, projY, r * 2.4f, paint);
                paint.setColor(fc | 0xe0000000); c.drawCircle(projX, projY, r * 0.8f, paint);
            }
        }

        /* today's steps, just under the tree */
        if (label != null && project(0, 0, 0)) {
            textPaint.setColor(0xf2ffffff);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(W * 0.046f);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setShadowLayer(W * 0.012f, 0, W * 0.003f, 0x99000000);
            float labelY = Math.min(projY + H * 0.065f, H * 0.82f);
            c.drawText(label, W / 2f, labelY, textPaint);
        }
    }

    private static double[][] skyColours(int type, double[][] pal) {
        if (type == DUSK) return new double[][] { pal[0], TreeModel.mix(pal[1], pal[2], 0.5), TreeModel.mix(pal[3], pal[4], 0.35) };
        return FIXED_SKY[type];
    }

    private static double[] scale(double[] c, double k) {
        return new double[] { c[0] * k, c[1] * k, c[2] * k };
    }

    /* ---------- projection ---------- */
    private float projX, projY, projZ;

    private double depth(double x, double y, double z) {
        return (x - cxw) * fxw + (y - cyw) * fyw + (z - czw) * fzw;
    }

    private boolean project(double x, double y, double z) {
        double dx = x - cxw, dy = y - cyw, dz = z - czw;
        double zc = dx * fxw + dy * fyw + dz * fzw;
        if (zc < 0.2) return false;
        projX = (float) (scx + focal * (dx * rx + dy * ry + dz * rz) / zc);
        projY = (float) (scy - focal * (dx * ux + dy * uy + dz * uz) / zc);
        projZ = (float) zc;
        return true;
    }

    private boolean projectDir(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        x /= l; y /= l; z /= l;
        double zc = x * fxw + y * fyw + z * fzw;
        if (zc < 0.05) return false;
        projX = (float) (scx + focal * (x * rx + y * ry + z * rz) / zc);
        projY = (float) (scy - focal * (x * ux + y * uy + z * uz) / zc);
        return true;
    }

    private static double grassX(TreeModel m, int i) {
        double a = TreeModel.hash(m.seed, 20000 + i) * Math.PI * 2, r = 1.0 + Math.pow(TreeModel.hash(m.seed, 21000 + i), 0.8) * 13;
        return Math.cos(a) * r;
    }

    private static double grassZ(TreeModel m, int i) {
        double a = TreeModel.hash(m.seed, 20000 + i) * Math.PI * 2, r = 1.0 + Math.pow(TreeModel.hash(m.seed, 21000 + i), 0.8) * 13;
        return Math.sin(a) * r;
    }

    private static int shadeColor(int c, float s) {
        int r = Math.min(255, (int) (((c >> 16) & 0xff) * s));
        int g = Math.min(255, (int) (((c >> 8) & 0xff) * s));
        int b = Math.min(255, (int) ((c & 0xff) * s));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
