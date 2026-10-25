package com.boidbit.treesteps;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;

/**
 * The illustrated wallpaper: layered hills at dusk with a seeded tree as the hero.
 * A line-for-line port of lib/art.js (the app draws the same picture), so keep the two in step:
 * same constants, same random streams (mulberry32 seeded per element type), same order of draws.
 */
final class ArtRenderer {
    private static final double TAU = Math.PI * 2;
    private static final int LEVELS = 6;

    /* per colour set: sky x5 (dusk), haze, near hills, foreground, trunk, rim light, leaves x3, blossom, sun */
    static final String[] NAMES = { "Sunset", "Ocean", "Forest", "Ember", "Orchid", "Slate" };
    private static final String[][] PALS = {
        { "#18234a", "#3d4c7f", "#d9826e", "#f4b878", "#f7d29a", "#e7a083", "#231a35", "#1a1428", "#1f1527", "#f7aa6e", "#6b2b4f", "#c8466a", "#f48a70", "#ffd0b5", "#fff0cf" },
        { "#0b1f3a", "#1d4e7a", "#4fa3b8", "#a9dccf", "#e3f4e8", "#8cc7c4", "#0f2a38", "#0b1e2a", "#13202b", "#bfeee0", "#1d5b6e", "#2f9aa0", "#7fd6c2", "#e8fff7", "#f2fff9" },
        { "#13261f", "#2e5442", "#8fb07a", "#e2d59a", "#f6ebc0", "#b9c28e", "#16261c", "#101c15", "#1d1a14", "#f0d68a", "#1f4d2c", "#3f8a45", "#9cc95a", "#fff3c4", "#fff6d8" },
        { "#140808", "#3e1414", "#a33a22", "#ee7a3a", "#ffcf87", "#d9774a", "#1e0c0c", "#150808", "#1a0b0b", "#ffb070", "#5a1414", "#b8321e", "#f2773a", "#ffe0a0", "#fff0c8" },
        { "#0c0826", "#2c1a63", "#7b3fb5", "#e25fa8", "#ffc2e0", "#c27bd0", "#1a0f33", "#120a26", "#170d28", "#ff9ad8", "#4a1f7a", "#a43ec0", "#ff7fc8", "#e8fffb", "#fff0fa" },
        { "#0f141d", "#2a3546", "#6b7a91", "#b9c4d3", "#e8edf3", "#a9b4c4", "#1a2130", "#121823", "#161b25", "#e6ecf5", "#3a4658", "#6f8199", "#b8c6d8", "#ffffff", "#ffffff" }
    };
    private static final int HAZE = 5, NEAR = 6, FG = 7, TRUNK = 8, RIM = 9, LEAF = 10, BLOOM = 13, SUN = 14;

    /* time of day: 0 night, 1 dawn, 2 day, 3 dusk (the colour set's own sky) */
    private static final double[] KEY_H = { 0, 5, 6.5, 8, 16.5, 18.3, 19.8, 24 };
    private static final int[] KEY_T = { 0, 0, 1, 2, 2, 3, 0, 0 };
    private static final String[][] TINT_SKY = {
        { "#03060f", "#081430", "#14244a", "#233863", "#2f4675" },
        { "#2a3a78", "#6a5a9a", "#d48aa0", "#f6b89a", "#fbe0c0" },
        { "#3f78c8", "#6aa0dc", "#a9cdeb", "#d6e9f4", "#eef6f8" }
    };
    private static final String[] TINT_HAZE = { "#3a4a72", "#e4b4b0", "#c9dbe6" };
    private static final String[] TINT_NEAR = { "#0a0f1e", "#2a2240", "#2b3b4a" };
    private static final double[] TINT_AMT = { 0.75, 0.6, 0.65 };
    private static final double[] TINT_LIGHT = { 0.55, 0.85, 1.0 };
    private static final double[] TINT_STARS = { 1, 0.3, 0 };

    /** How today's steps are shown (chosen in the app's settings). */
    static final class Label {
        final String style, pos, size;   // style: steps | goal | bar | percent; pos: tree | bottom; size: s | m | l
        final int steps, goal;

        Label(String style, String pos, String size, int steps, int goal) {
            this.style = style; this.pos = pos; this.size = size; this.steps = steps; this.goal = Math.max(1, goal);
        }
    }

    private static final class Seg {
        double x0, y0, cx, cy, x1, y1, w;
        int d;
    }

    private static final class Look {
        double[][] sky = new double[5][];
        double[] haze, near;
        double light, stars;
        boolean sun;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int treeSeed = Integer.MIN_VALUE;
    private ArrayList<Seg> segs;

    void draw(Canvas c, int W, int H, int seed, String palName, double growth, double hour, double par, Label label) {
        String[] p = PALS[0];
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equalsIgnoreCase(palName)) p = PALS[i];
        double k = H / 2400.0, sx = W / 1080.0, t = clamp01(growth);
        Look L = timeLook(p, hour);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);

        /* sky, stars, sun or moon */
        int[] skyCols = new int[5];
        for (int j = 0; j < 5; j++) skyCols[j] = argb(L.sky[j], 1);
        paint.setShader(new LinearGradient(0, 0, 0, (float) (1560 * k), skyCols, new float[] { 0f, 0.38f, 0.66f, 0.84f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);
        Rng rs = new Rng(11);
        for (int i = 0; i < 70; i++) {
            double stx = rs.next() * W, sty = rs.next() * 900 * k, sta = (0.2 + rs.next() * 0.5) * L.stars, str = (1.5 + rs.next() * 2) * k;
            if (sta > 0.01) { paint.setColor(argb(new double[] { 255, 255, 255 }, sta)); c.drawCircle((float) stx, (float) sty, (float) str, paint); }
        }
        double cx = 735 * sx + par * 6 * k, cy = 1330 * k;
        double[] sun = L.sun ? hex(p[SUN]) : new double[] { 243, 241, 230 };
        radial(c, W, H, cx, cy, 60 * k, 700 * k,
            new int[] { argb(sun, L.sun ? 0.7 : 0.35), argb(mix(sun, L.haze, 0.5), L.sun ? 0.25 : 0.1), argb(L.haze, 0) }, new double[] { 0, 0.3, 1 });
        paint.setColor(argb(sun, 1));
        c.drawCircle((float) cx, (float) cy, (float) ((L.sun ? 105 : 70) * k), paint);

        /* hills: far layers take the haze colour, near ones darken; mist between layers; near layers move more */
        Rng rr = new Rng(12);
        double[][] lay = { { 1390, 150, 1.0 }, { 1470, 140, 0.8 }, { 1560, 120, 0.6 }, { 1660, 110, 0.45 }, { 1760, 90, 0.3 } };
        for (int j = 0; j < lay.length; j++) {
            double[] l = lay[j];
            double ph0 = rr.next() * 10, ph1 = rr.next() * 10, ph2 = rr.next() * 10, ph3 = rr.next() * 10;
            double base = l[0] * k, amp = l[1] * k, rough = l[2], dx = par * (6 + j * 8) * k;
            paint.setColor(argb(scale(mix(L.haze, L.near, 0.18 + (j + 1) / 6.0 * 0.62), 0.75 + 0.25 * L.light), 1));
            path.reset(); path.moveTo(-40, H);
            for (int x = -40; x <= W + 40; x += 12) {
                double u = x / (double) W;
                double y = base - amp * (0.55 * Math.sin(u * 3.1 + ph0) + 0.3 * Math.sin(u * 7.3 + ph1) + rough * 0.18 * Math.sin(u * 19 + ph2) + rough * 0.08 * Math.sin(u * 41 + ph3));
                path.lineTo((float) (x + dx), (float) y);
            }
            path.lineTo(W + 40, H); path.close();
            c.drawPath(path, paint);
            float top = (float) (base - 40 * k);
            paint.setShader(new LinearGradient(0, top, 0, (float) (base + 120 * k), argb(L.haze, 0),
                argb(L.haze, (0.28 - j * 0.04) * (0.4 + 0.6 * L.light)), Shader.TileMode.CLAMP));
            c.drawRect(0, top, W, (float) (top + 160 * k), paint);
            paint.setShader(null);
        }

        /* birds by day */
        if (L.stars < 0.5) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth((float) (5 * k));
            paint.setColor(argb(scale(L.near, 1.2), 0.75));
            double[][] birds = { { 300, 1080, 26 }, { 360, 1040, 20 }, { 255, 1120, 16 } };
            for (double[] b : birds) {
                float bx = (float) (b[0] * sx + par * 10 * k), by = (float) (b[1] * k), bw = (float) (b[2] * k);
                path.reset(); path.moveTo(bx - bw, by);
                path.quadTo(bx - bw / 2, by - bw * 0.6f, bx, by); path.quadTo(bx + bw / 2, by - bw * 0.6f, bx + bw, by);
                c.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        /* foreground hill the tree stands on */
        double[] fg = scale(hex(p[FG]), 0.7 + 0.3 * L.light);
        double hillDx = par * 30 * k;
        paint.setColor(argb(fg, 1));
        path.reset(); path.moveTo(-40, H);
        for (int hx = -40; hx <= W + 40; hx += 12) path.lineTo(hx, (float) hillY(hx, hillDx, W, k));
        path.lineTo(W + 40, H); path.close();
        c.drawPath(path, paint);

        /* the tree */
        if (treeSeed != seed || segs == null) { treeSeed = seed; segs = makeTree(seed); }
        double P = 0.9 + t * (LEVELS + 0.25);                       // even at 0 steps there is a young plant
        double young = 1 + 0.9 * Math.pow(1 - t, 2);                 // saplings are drawn larger so they read
        double tk = k * 1.22 * young, bx0 = 545 * sx + par * 40 * k, by0 = 1815 * k;
        double thin = 0.5 + 0.5 * Math.min(1, t * 1.5);
        double[] trunk = scale(hex(p[TRUNK]), 0.8 + 0.2 * L.light), rim = hex(p[RIM]);
        ArrayList<double[]> clusters = new ArrayList<>();
        for (int si = 0; si < segs.size(); si++) {
            Seg s = segs.get(si);
            double gg = clamp01(P - s.d);
            if (gg <= 0) continue;
            double X0 = bx0 + s.x0 * tk, Y0 = by0 + s.y0 * tk, CX = bx0 + s.cx * tk, CY = by0 + s.cy * tk, X1 = bx0 + s.x1 * tk, Y1 = by0 + s.y1 * tk;
            // grow along the curve: split the quadratic at gg
            double qx = X0 + (CX - X0) * gg, qy = Y0 + (CY - Y0) * gg;
            double ex = (1 - gg) * (1 - gg) * X0 + 2 * (1 - gg) * gg * CX + gg * gg * X1, ey = (1 - gg) * (1 - gg) * Y0 + 2 * (1 - gg) * gg * CY + gg * gg * Y1;
            double w0 = s.w * tk * thin, w1 = w0 * (1 - 0.3 * gg);
            double dx = ex - X0, dy = ey - Y0, ln = Math.hypot(dx, dy);
            if (ln == 0) ln = 1;
            double nx = -dy / ln, ny = dx / ln, a = w0 / 2, b = w1 / 2, mm = (a + b) / 2;
            paint.setColor(argb(trunk, 1));
            path.reset();
            path.moveTo((float) (X0 + nx * a), (float) (Y0 + ny * a));
            path.quadTo((float) (qx + nx * mm), (float) (qy + ny * mm), (float) (ex + nx * b), (float) (ey + ny * b));
            path.lineTo((float) (ex - nx * b), (float) (ey - ny * b));
            path.quadTo((float) (qx - nx * mm), (float) (qy - ny * mm), (float) (X0 - nx * a), (float) (Y0 - ny * a));
            path.close();
            c.drawPath(path, paint);
            c.drawCircle((float) ex, (float) ey, (float) b, paint);
            if (s.d < 3 && L.light > 0.6) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth((float) (4 * k));
                paint.setColor(argb(rim, 0.55 * (L.light - 0.4)));
                path.reset();
                path.moveTo((float) (X0 + a * 0.76), (float) Y0);
                path.quadTo((float) (qx + mm * 0.6), (float) qy, (float) (ex + b * 0.76), (float) ey);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            // leaves sit on the outer twigs, and on whatever tip is growing right now (so a sapling has leaves)
            boolean frontier = s.d == LEVELS || P - (s.d + 1) < 0.35;
            if ((s.d >= Math.max(2, Math.floor(P) - 2) || frontier) && gg > 0.15) clusters.add(new double[] { ex, ey, si, gg });
        }

        double[][] leaf = new double[3][];
        for (int j = 0; j < 3; j++) leaf[j] = scale(hex(p[LEAF + j]), 0.55 + 0.45 * L.light);
        double leafy = 0.45 + 0.55 * t;
        double[] bloom = hex(p[BLOOM]);
        if (t >= 1) {                                                   // goal reached: a soft glow behind the crown
            radial(c, W, H, bx0, by0 - 640 * k, 40 * k, 600 * k, new int[] { argb(bloom, 0.28), argb(bloom, 0) }, new double[] { 0, 1 });
        }
        double[][] layers = { { 0, 1.0, -0.25, 0.25 }, { 1, 0.82, 0, 0 }, { 2, 0.5, 0.3, -0.3 } };
        for (double[] lyr : layers) {
            int li = (int) lyr[0];
            paint.setColor(argb(leaf[li], 1));
            for (double[] cl : clusters) {
                Rng r3 = new Rng((int) cl[2] * 31 + 7);
                double cs = cl[3] * leafy;
                for (int j = 0; j < 4; j++) {
                    double r = (34 + r3.next() * 34) * lyr[1] * cs * tk;
                    double ox = ((r3.next() - 0.5) * 70 + lyr[2] * 40) * cs * tk, oy = ((r3.next() - 0.5) * 60 + lyr[3] * 40) * cs * tk;
                    boolean skip = li == 2 && r3.next() < 0.45;
                    if (!skip) c.drawCircle((float) (cl[0] + ox), (float) (cl[1] + oy), (float) r, paint);
                }
            }
        }

        /* blossoms and falling petals arrive as the tree fills out */
        Rng rb = new Rng(5);
        long nb = Math.round(clusters.size() * 1.4 * clamp01((t - 0.25) / 0.75));
        paint.setColor(argb(bloom, 0.6 + 0.4 * L.light));
        for (int i = 0; i < nb; i++) {
            double[] cl = clusters.get((int) Math.floor(rb.next() * clusters.size()));
            double bxx = cl[0] + (rb.next() - 0.5) * 80 * tk, byy = cl[1] + (rb.next() - 0.5) * 60 * tk, br = (5 + rb.next() * 4) * k;
            c.drawCircle((float) bxx, (float) byy, (float) br, paint);
        }
        Rng rp = new Rng(6);
        long np = Math.round(26 * clamp01((t - 0.4) / 0.6));
        for (int i = 0; i < np; i++) {
            double px = (330 + rp.next() * 480) * sx + par * 40 * k, py = (1300 + rp.next() * 560) * k, rot = rp.next() * 3, pa = 0.5 + rp.next() * 0.4;
            paint.setColor(argb(leaf[2], pa));
            c.save();
            c.translate((float) px, (float) py);
            c.rotate((float) Math.toDegrees(rot));
            rect.set((float) (-11 * k), (float) (-5 * k), (float) (11 * k), (float) (5 * k));
            c.drawOval(rect, paint);
            c.restore();
        }

        /* grass tufts along the ridge */
        Rng rg = new Rng(7);
        paint.setColor(argb(fg, 1));
        for (int i = 0; i < 90; i++) {
            double gx = rg.next() * W, gh = (18 + rg.next() * 30) * k, lean = (rg.next() - 0.5) * 10 * k, gy = hillY(gx, hillDx, W, k) + 4 * k;
            path.reset();
            path.moveTo((float) (gx - 5 * k), (float) (gy + 6 * k));
            path.lineTo((float) (gx + lean), (float) (gy - gh));
            path.lineTo((float) (gx + 5 * k), (float) (gy + 6 * k));
            path.close();
            c.drawPath(path, paint);
        }

        if (label != null) drawLabel(c, W, k, label, L, p);
    }

    private void drawLabel(Canvas c, int W, double k, Label l, Look L, String[] p) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        int s = l.steps, goal = l.goal;
        String str;
        switch (l.style) {
            case "steps": str = nf.format(s) + " steps"; break;
            case "percent": str = Math.min(999, Math.round(s * 100.0 / goal)) + "% of goal"; break;
            case "bar": str = nf.format(s) + " / " + nf.format(goal); break;
            default: str = nf.format(s) + " / " + nf.format(goal) + " steps"; break;
        }
        double z = ("s".equals(l.size) ? 0.8 : "l".equals(l.size) ? 1.3 : 1) * k;
        boolean bar = "bar".equals(l.style);
        text.setTextSize((float) (44 * z));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        double tw = text.measureText(str), w = tw + 90 * z, h = (bar ? 116 : 92) * z, x = (W - w) / 2;
        double y = ("bottom".equals(l.pos) ? 2090 : 1950) * k;
        paint.setColor(argb(scale(L.near, 0.6), 0.55));
        rect.set((float) x, (float) y, (float) (x + w), (float) (y + h));
        c.drawRoundRect(rect, (float) (h / 2), (float) (h / 2), paint);
        text.setColor(0xffffffff);
        c.drawText(str, W / 2f, (float) (y + (bar ? 60 : 61) * z), text);
        if (bar) {
            double frac = Math.min(1, s / (double) goal);
            paint.setColor(0x38ffffff);
            rect.set((float) (x + 45 * z), (float) (y + 80 * z), (float) (x + w - 45 * z), (float) (y + 90 * z));
            c.drawRoundRect(rect, (float) (5 * z), (float) (5 * z), paint);
            paint.setColor(argb(hex(p[LEAF + 2]), 1));
            rect.set((float) (x + 45 * z), (float) (y + 80 * z), (float) (x + 45 * z + Math.max(10 * z, (w - 90 * z) * frac)), (float) (y + 90 * z));
            c.drawRoundRect(rect, (float) (5 * z), (float) (5 * z), paint);
        }
    }

    /* a seeded branching tree; the shape never depends on growth, so it stays the same tree all day */
    private static ArrayList<Seg> makeTree(int seed) {
        ArrayList<Seg> out = new ArrayList<>();
        Rng rng = new Rng(seed);
        branch(out, rng, 0, 0, (rng.next() - 0.5) * 0.08, 230, 44, 0);
        return out;
    }

    private static void branch(ArrayList<Seg> out, Rng rng, double x, double y, double ang, double len, double w, int d) {
        double bend = (rng.next() - 0.5) * len * 0.35;
        Seg s = new Seg();
        s.x0 = x; s.y0 = y;
        s.x1 = x + Math.sin(ang) * len; s.y1 = y - Math.cos(ang) * len;
        s.cx = (x + s.x1) / 2 + Math.cos(ang) * bend; s.cy = (y + s.y1) / 2 + Math.sin(ang) * bend;
        s.w = w; s.d = d;
        out.add(s);
        if (d >= LEVELS) return;
        int n = d < 2 ? 2 : (rng.next() < 0.3 ? 3 : 2);
        for (int i = 0; i < n; i++) {
            int side = n == 2 ? (i != 0 ? 1 : -1) : i - 1;
            double na = ang * 0.85 + side * (0.32 + rng.next() * 0.3) * 0.62 + (rng.next() - 0.5) * 0.15;
            double nl = len * (0.72 + rng.next() * 0.1);
            branch(out, rng, s.x1, s.y1, na, nl, w * 0.7, d + 1);
        }
    }

    private static double hillY(double x, double hillDx, int W, double k) {
        double u = (x - hillDx - 0.52 * W) / (0.54 * W);
        return 1805 * k + 125 * k * u * u;
    }

    /* the colour set's own sky is dusk; other times blend it toward fixed skies */
    private static Look look(String[] p, int type) {
        Look o = new Look();
        for (int i = 0; i < 5; i++) o.sky[i] = hex(p[i]);
        o.haze = hex(p[HAZE]); o.near = hex(p[NEAR]); o.light = 0.95; o.stars = 0.45;
        if (type == 3) return o;
        double amt = TINT_AMT[type];
        for (int i = 0; i < 5; i++) o.sky[i] = mix(o.sky[i], hex(TINT_SKY[type][i]), amt);
        o.haze = mix(o.haze, hex(TINT_HAZE[type]), amt * 0.85);
        o.near = mix(o.near, hex(TINT_NEAR[type]), amt * 0.6);
        o.light = TINT_LIGHT[type]; o.stars = TINT_STARS[type];
        return o;
    }

    private static Look timeLook(String[] p, double hour) {
        int i = 0;
        while (i < KEY_H.length - 2 && hour >= KEY_H[i + 1]) i++;
        double u = clamp01((hour - KEY_H[i]) / (KEY_H[i + 1] - KEY_H[i]));
        Look a = look(p, KEY_T[i]), b = look(p, KEY_T[i + 1]), o = new Look();
        for (int j = 0; j < 5; j++) o.sky[j] = mix(a.sky[j], b.sky[j], u);
        o.haze = mix(a.haze, b.haze, u); o.near = mix(a.near, b.near, u);
        o.light = a.light + (b.light - a.light) * u; o.stars = a.stars + (b.stars - a.stars) * u;
        o.sun = hour >= 6 && hour < 19;
        return o;
    }

    /** A radial gradient with an inner radius like canvas createRadialGradient(cx, cy, r0, cx, cy, r1). */
    private void radial(Canvas c, int W, int H, double cx, double cy, double r0, double r1, int[] cols, double[] stops) {
        int[] colors = new int[cols.length + 1];
        float[] pos = new float[cols.length + 1];
        colors[0] = cols[0]; pos[0] = 0;
        for (int i = 0; i < cols.length; i++) { colors[i + 1] = cols[i]; pos[i + 1] = (float) ((r0 + stops[i] * (r1 - r0)) / r1); }
        paint.setShader(new RadialGradient((float) cx, (float) cy, (float) r1, colors, pos, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);
    }

    /* ---------- small helpers (match lib/art.js) ---------- */

    /** mulberry32, identical to the JavaScript version. */
    private static final class Rng {
        private int a;

        Rng(int seed) { a = seed; }

        double next() {
            a += 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
        }
    }

    private static double[] hex(String h) {
        return new double[] { Integer.parseInt(h.substring(1, 3), 16), Integer.parseInt(h.substring(3, 5), 16), Integer.parseInt(h.substring(5, 7), 16) };
    }

    private static double[] mix(double[] a, double[] b, double t) {
        return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }

    private static double[] scale(double[] c, double k) { return new double[] { c[0] * k, c[1] * k, c[2] * k }; }

    private static int argb(double[] c, double alpha) {
        int a = (int) Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (ch(c[0]) << 16) | (ch(c[1]) << 8) | ch(c[2]);
    }

    private static int ch(double v) { return (int) Math.max(0, Math.min(255, v)); }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
}
