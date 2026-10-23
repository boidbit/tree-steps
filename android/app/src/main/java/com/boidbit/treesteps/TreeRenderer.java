package com.boidbit.treesteps;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

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

    /**
     * @param fitY height of the fully grown tree for this seed (keeps framing steady while it grows)
     * @param fitR horizontal radius of the fully grown tree
     */
    void draw(Canvas c, int W, int H, TreeModel m, float fitY, float fitR, double az, double el) {
        double[][] pal = m.pal;
        focal = (H / 2.0) / Math.tan(VFOV / 2);
        scx = W / 2.0;
        scy = H * 0.58;
        double R = Math.max(focal * fitY / (0.56 * H), focal * 2 * fitR / (0.9 * W)) + fitR * 0.6;
        R = Math.max(R, 6);
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

        double[] horizonC = TreeModel.mix(pal[3], pal[4], 0.35);
        float horizonY = (float) (scy - focal * Math.tan(el));

        /* sky */
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, horizonY,
            new int[] { TreeModel.argb(pal[0]), TreeModel.argb(TreeModel.mix(pal[1], pal[2], 0.5)), TreeModel.argb(horizonC) },
            new float[] { 0f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, Math.max(horizonY, 0), paint);
        paint.setShader(null);

        /* stars and moon (directions only, so they sit at infinity) */
        for (int i = 0; i < STARS; i++) {
            double a = TreeModel.hash(m.seed, 50000 + i) * Math.PI * 2, b = Math.acos(1 - TreeModel.hash(m.seed, 51000 + i) * 0.9);
            if (projectDir(Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a))) {
                if (projY > horizonY - 4) continue;
                paint.setColor(0xffffffff);
                paint.setAlpha((int) (60 + TreeModel.hash(m.seed, 52000 + i) * 150));
                c.drawCircle(projX, projY, (float) (1 + TreeModel.hash(m.seed, 53000 + i) * 1.6), paint);
            }
        }
        paint.setAlpha(255);
        if (projectDir(45, 52, -100)) {
            float mr = W * 0.06f;
            int glowC = TreeModel.argb(pal[4]);
            paint.setShader(new RadialGradient(projX, projY, mr * 6, (glowC & 0x00ffffff) | 0x80000000, glowC & 0x00ffffff, Shader.TileMode.CLAMP));
            c.drawCircle(projX, projY, mr * 6, paint);
            paint.setShader(null);
            paint.setColor(TreeModel.argb(TreeModel.mix(pal[4], new double[] { 255, 255, 255 }, 0.4)));
            c.drawCircle(projX, projY, mr, paint);
        }

        /* distant hills, sliding with the camera angle */
        double[] hillC = TreeModel.mix(TreeModel.mix(pal[1], pal[0], 0.35), horizonC, 0.25);
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
            TreeModel.argb(TreeModel.mix(TreeModel.mix(pal[1], pal[0], 0.55), horizonC, 0.2)),
            TreeModel.argb(TreeModel.mix(pal[0], new double[] { 0, 0, 0 }, 0.35)), Shader.TileMode.CLAMP));
        c.drawRect(0, horizonY, W, H, paint);
        paint.setShader(null);

        /* soft shadow under the tree */
        path.reset();
        for (int i = 0; i <= 24; i++) {
            double a = i / 24.0 * Math.PI * 2;
            if (!project(Math.cos(a) * 2.2, 0, Math.sin(a) * 2.2)) continue;
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
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int s = 0; s < n; s++) {
            int i = (int) (keys[s] & 0xfffff);
            float z = pz[i];
            if (z < 0.2f) continue;
            float shade = (float) Math.max(0.68, Math.min(1.12, 1.0 - (z - centerZ) / spanZ * 0.45));
            if (i < m.nb) {
                if (!project(m.bx0[i], m.by0[i], m.bz0[i])) continue;
                float x0 = projX, y0 = projY;
                if (!project(m.bx1[i], m.by1[i], m.bz1[i])) continue;
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(shadeColor(m.bcol[i], shade));
                paint.setStrokeWidth((float) Math.max(1, focal * m.bw[i] * 1.72 / z));
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
            double gx = grassX(m, j), gz = grassZ(m, j), gh = 0.12 + TreeModel.hash(m.seed, 22000 + j) * 0.3;
            if (!project(gx, 0, gz)) continue;
            float x0 = projX, y0 = projY;
            double lean = (TreeModel.hash(m.seed, 23000 + j) - 0.5) * 0.12;
            if (!project(gx + lean, gh, gz)) continue;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((float) Math.max(1, focal * 0.03 / z));
            paint.setColor(shadeColor(TreeModel.argb(TreeModel.mix(TreeModel.mix(pal[0], pal[1], 0.7), pal[2],
                TreeModel.hash(m.seed, 26000 + j) * 0.35)), shade));
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
