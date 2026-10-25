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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The illustrated wallpaper: a seeded tree as the hero in one of several designs (scene x tree kind
 * x colours), lit by the sun's real position for the phone's time zone, with the moon's phase at night.
 * A line-for-line port of lib/art.js (the app draws the same picture), so keep the two in step:
 * same constants, same random streams (mulberry32 seeded per element), same order of draws.
 */
final class ArtRenderer {
    private static final double TAU = Math.PI * 2, D2R = Math.PI / 180;

    /* ---------- designs: scene + tree kind + colours (trees from around the world) ---------- */
    private static final class Design {
        final String scene, tree, flower, flower2, fall, trunk;
        final String[] leaf;

        Design(String scene, String tree, String[] leaf, String flower, String flower2, String fall, String trunk) {
            this.scene = scene; this.tree = tree; this.leaf = leaf; this.flower = flower; this.flower2 = flower2; this.fall = fall; this.trunk = trunk;
        }
    }

    private static final Map<String, Design> DESIGNS = new HashMap<>();
    private static final Map<String, String[]> SCENES = new HashMap<>();   // far hills, near hills, ground, grass
    static {
        DESIGNS.put("oak", new Design("hills", "broad", new String[] { "#1f4a2a", "#3f7f3c", "#86b84e" }, "", "", "#86b84e", "#4a3626"));
        DESIGNS.put("sakura", new Design("lake", "broad", new String[] { "#a8406a", "#e07a9e", "#f8bfd2" }, "#fff2f6", "", "#f8bfd2", "#4b3436"));
        DESIGNS.put("maple", new Design("autumn", "broad", new String[] { "#7e2618", "#cf5228", "#f39a3a" }, "", "", "#f39a3a", "#3e2a22"));
        DESIGNS.put("pine", new Design("snow", "pine", new String[] { "#173630", "#2a5546", "#4f826a" }, "", "", "#ffffff", "#3e2e26"));
        DESIGNS.put("jacaranda", new Design("fields", "umbrella", new String[] { "#2c5a2a", "#4d8a3a", "#8fbf52" }, "#8e62d6", "#c3a2f0", "#b48ae8", "#4a3524"));
        DESIGNS.put("banyan", new Design("hills", "banyan", new String[] { "#22462a", "#3c7038", "#78a64a" }, "", "", "#78a64a", "#5c4838"));
        SCENES.put("hills", new String[] { "#a9c3cf", "#4f7a5a", "#5f8f45", "#4a7a33" });
        SCENES.put("lake", new String[] { "#a3bccf", "#56798c", "#6b8f4a", "#557a38" });
        SCENES.put("fields", new String[] { "#b7c7a0", "#7f9c54", "#d8b246", "#a8872c" });
        SCENES.put("snow", new String[] { "#d9e4ef", "#9fb3c9", "#eef3f8", "#c9d6e4" });
        SCENES.put("autumn", new String[] { "#c7b394", "#9a6a3e", "#b9783a", "#93552a" });
    }

    /* ---------- the sun's real path from the phone's time zone ---------- */
    private static final Map<String, double[]> TZ = new HashMap<>();
    static {
        Object[][] z = {
            { "Asia/Kolkata", 22.5, 79.0 }, { "Asia/Calcutta", 22.5, 79.0 }, { "Asia/Karachi", 28.0, 68.0 }, { "Asia/Dhaka", 23.7, 90.4 },
            { "Asia/Kathmandu", 27.7, 85.3 }, { "Asia/Colombo", 7.0, 80.0 }, { "Asia/Dubai", 25.2, 55.3 }, { "Asia/Riyadh", 24.7, 46.7 },
            { "Asia/Singapore", 1.35, 103.8 }, { "Asia/Jakarta", -6.2, 106.8 }, { "Asia/Bangkok", 13.75, 100.5 }, { "Asia/Shanghai", 31.0, 114.0 },
            { "Asia/Tokyo", 35.7, 139.7 }, { "Europe/London", 51.5, -0.1 }, { "Europe/Paris", 48.9, 2.35 }, { "Europe/Berlin", 52.5, 13.4 },
            { "Europe/Moscow", 55.75, 37.6 }, { "Africa/Cairo", 30.0, 31.2 }, { "Africa/Lagos", 6.5, 3.4 }, { "Africa/Nairobi", -1.3, 36.8 },
            { "America/New_York", 40.7, -74.0 }, { "America/Chicago", 41.9, -87.6 }, { "America/Denver", 39.7, -105.0 }, { "America/Los_Angeles", 34.0, -118.2 },
            { "America/Sao_Paulo", -23.5, -46.6 }, { "Australia/Sydney", -33.9, 151.2 }
        };
        for (Object[] e : z) TZ.put((String) e[0], new double[] { (Double) e[1], (Double) e[2] });
    }

    private static final String[] SKY_DAY = { "#3d7fd1", "#5d9be0", "#8fbde9", "#bfdaf0", "#e2eff5" };
    private static final String[] SKY_EVE = { "#2b3a6e", "#5a5a92", "#d98a7a", "#f5b880", "#fbd9a0" };
    private static final String[] SKY_MORN = { "#2d4078", "#6b6aa6", "#d99aac", "#f6c4a4", "#fde3c4" };
    private static final String[] SKY_NIGHT = { "#02040c", "#060d24", "#0e1a3a", "#182a52", "#22375f" };
    private static final double[] WARM = hex("#ff9a5c"), NIGHTC = hex("#101c3c"), WHITE = { 255, 255, 255 };
    /* fixed looks {elevation, azimuth} for when the user picks a style instead of following the sun */
    private static final Map<String, double[]> SKY_STYLES = new HashMap<>();
    static {
        SKY_STYLES.put("dawn", new double[] { 5, 105 });
        SKY_STYLES.put("day", new double[] { 48, 160 });
        SKY_STYLES.put("sunset", new double[] { 4, 255 });
        SKY_STYLES.put("night", new double[] { -25, 300 });
    }

    /** How today's steps are shown (chosen in the app's settings). */
    static final class Label {
        final String style, pos, size;   // style: steps | goal | bar | percent; pos: tree | bottom; size: s | m | l
        final int steps, goal;

        Label(String style, String pos, String size, int steps, int goal) {
            this.style = style; this.pos = pos; this.size = size; this.steps = steps; this.goal = Math.max(1, goal);
        }
    }

    private static final class Seg {
        double x0, y0, cx, cy, x1, y1, w, r;
        int d;
    }

    /* per-frame lighting, shared by the helpers */
    private double light, wG, wN;
    private double[] haze;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private String treeKey;
    private ArrayList<Seg> segs;
    private int levels;
    private double lenGrow;

    /**
     * @param growth steps / goal
     * @param nowMs  current time; the sun is placed from it and the phone's time zone
     * @param skyStyle "time" to follow the real sun, or a fixed "dawn" | "day" | "sunset" | "night"
     */
    void draw(Canvas c, int W, int H, int seed, String designId, double growth, long nowMs, String skyStyle, double par, Label label) {
        Design dz = DESIGNS.get(designId);
        if (dz == null) dz = DESIGNS.get("banyan");
        String[] sc = SCENES.get(dz.scene);
        double k = H / 2400.0, sx = W / 1080.0, t = clamp01(growth);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);

        /* ---- where the sun is, and what light that gives ---- */
        double[] sun, pl;
        double[] fixed = SKY_STYLES.get(skyStyle);
        if (fixed != null) { sun = fixed; pl = new double[] { 20, 0 }; }
        else {
            TimeZone tz = TimeZone.getDefault();
            pl = TZ.get(tz.getID());
            if (pl == null) pl = new double[] { 20, tz.getOffset(nowMs) / 60000.0 / 4 };   // unknown zone: longitude from the UTC offset
            sun = sunPos(nowMs, pl[0], pl[1]);
        }
        wN = smooth(-2, -10, sun[0]);
        double wD = smooth(6, 22, sun[0]);
        wG = Math.max(0, 1 - wN - wD);
        boolean eve = sun[1] > 180;
        double gShare = wG / Math.max(1e-6, wG + wD);
        double[][] sky = new double[5][];
        for (int s = 0; s < 5; s++) sky[s] = mix(mix(hex(SKY_DAY[s]), hex((eve ? SKY_EVE : SKY_MORN)[s]), gShare), hex(SKY_NIGHT[s]), wN);
        haze = mix(mix(hex("#cfe0ea"), hex("#f0b896"), gShare), hex("#2a3858"), wN);
        light = 1 - 0.18 * wG - 0.58 * wN;

        /* sky and stars */
        double horizon = 1390 * k;
        int[] skyCols = new int[5];
        for (int s = 0; s < 5; s++) skyCols[s] = argb(sky[s], 1);
        paint.setColor(0xff000000);   // a shader is multiplied by the paint alpha, so make it opaque
        paint.setShader(new LinearGradient(0, 0, 0, (float) (1560 * k), skyCols, new float[] { 0f, 0.38f, 0.66f, 0.84f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);
        double stars = wN + (eve ? 0.25 * wG : 0);
        Rng rs = new Rng(11);
        for (int i = 0; i < 90; i++) {
            double stx = rs.next() * W, sty = rs.next() * 1000 * k, sta = (0.2 + rs.next() * 0.6) * stars, str = (1.4 + rs.next() * 2.2) * k;
            if (sta > 0.01) { paint.setColor(argb(WHITE, sta)); c.drawCircle((float) stx, (float) sty, (float) str, paint); }
        }

        /* sun along its arc (east on the left when you face the equator), or the moon with its phase */
        double face = pl[0] >= 0 ? 1 : -1;
        double sunX = W * (0.5 - 0.42 * Math.sin(sun[1] * D2R) * face) + par * 6 * k;
        double sunY = Math.max(820 * k, horizon + 40 * k - (sun[0] / 65) * (horizon + 40 * k - 820 * k));
        double[] sunC = mix(hex("#fff6dc"), hex("#ffd29a"), wG);
        if (sun[0] > -4) {
            radial(c, W, H, sunX, sunY, 50 * k, (500 + 300 * wG) * k,
                new int[] { argb(sunC, 0.55 + 0.25 * wG), argb(mix(sunC, haze, 0.5), 0.18 + 0.15 * wG), argb(haze, 0) }, new double[] { 0, 0.35, 1 });
            paint.setColor(argb(sunC, 1));
            c.drawCircle((float) sunX, (float) sunY, (float) (88 * k), paint);
        }
        double moonA = smooth(-1, -8, sun[0]);
        if (moonA > 0.02) {
            double mp = moonPhase(nowMs), mx = 0.82 * W + par * 6 * k, my = 720 * k, mr = 58 * k;
            radial(c, W, H, mx, my, mr, mr * 5, new int[] { argb(new double[] { 220, 230, 255 }, 0.22 * moonA), argb(new double[] { 220, 230, 255 }, 0) }, new double[] { 0, 1 });
            paint.setColor(argb(new double[] { 40, 50, 80 }, 0.6 * moonA));
            c.drawCircle((float) mx, (float) my, (float) mr, paint);
            // lit part: the half-disc on the lit side plus a half-ellipse whose width follows the phase
            double cw = Math.cos(mp * TAU) * mr;
            boolean waxing = mp < 0.5, anti = waxing ? cw > 0 : cw < 0;
            paint.setColor(argb(new double[] { 244, 240, 226 }, moonA));
            path.reset();
            rect.set((float) (mx - mr), (float) (my - mr), (float) (mx + mr), (float) (my + mr));
            path.arcTo(rect, -90, waxing ? 180 : -180, true);
            rect.set((float) (mx - Math.abs(cw)), (float) (my - mr), (float) (mx + Math.abs(cw)), (float) (my + mr));
            path.arcTo(rect, 90, anti ? -180 : 180, false);
            path.close();
            c.drawPath(path, paint);
        }

        /* ---- scenery ---- */
        double[] far = hex(sc[0]), near = hex(sc[1]);
        Rng rr = new Rng(12);
        if (dz.scene.equals("snow")) {
            // snowy peaks: mountain body, then a white cap over each summit
            double[][] mts = { { 1330, 330, 0.45 }, { 1470, 260, 0.3 }, { 1600, 200, 0.15 } };
            double[][] mcol = { far, mix(far, near, 0.5), near };
            for (int q = 0; q < 3; q++) {
                double[] m = mts[q];
                ArrayList<double[]> pk = new ArrayList<>();
                double x = -80, dx = par * (6 + q * 10) * k;
                while (x < W + 160) { pk.add(new double[] { x, m[0] * k - (0.35 + rr.next() * 0.65) * m[1] * k }); x += (150 + rr.next() * 140) * sx; }
                paint.setColor(argb(hazeMix(mcol[q], m[2]), 1));
                path.reset(); path.moveTo(-80, H);
                for (int j = 0; j < pk.size(); j++) {
                    path.lineTo((float) (pk.get(j)[0] + dx), (float) pk.get(j)[1]);
                    if (j < pk.size() - 1) path.lineTo((float) ((pk.get(j)[0] + pk.get(j + 1)[0]) / 2 + dx), (float) (m[0] * k + 40 * k));
                }
                path.lineTo((float) (pk.get(pk.size() - 1)[0] + dx), H); path.close();
                c.drawPath(path, paint);
                paint.setColor(argb(hazeMix(WHITE, m[2] * 0.6), 1));
                for (int j = 0; j < pk.size(); j++) {
                    double cap = (60 + rr.next() * 50) * k, px0 = pk.get(j)[0] + dx, py0 = pk.get(j)[1];
                    path.reset(); path.moveTo((float) px0, (float) py0);
                    path.lineTo((float) (px0 + cap * 0.9), (float) (py0 + cap)); path.lineTo((float) (px0 + cap * 0.3), (float) (py0 + cap * 0.8));
                    path.lineTo((float) px0, (float) (py0 + cap * 1.1)); path.lineTo((float) (px0 - cap * 0.4), (float) (py0 + cap * 0.75)); path.lineTo((float) (px0 - cap * 0.9), (float) (py0 + cap));
                    path.close();
                    c.drawPath(path, paint);
                }
            }
        } else {
            double[][] lay = dz.scene.equals("lake") ? new double[][] { { 1390, 150, 1.0 }, { 1470, 120, 0.8 } }
                : new double[][] { { 1390, 150, 1.0 }, { 1470, 140, 0.8 }, { 1560, 120, 0.6 }, { 1660, 110, 0.45 } };
            for (int q = 0; q < lay.length; q++) {
                double[] l = lay[q];
                double f = q / (double) Math.max(1, lay.length - 1);
                paint.setColor(argb(hazeMix(mix(far, near, f), 0.55 * (1 - f) + 0.08), 1));
                ridge(c, rr, l[0] * k, l[1] * k, l[2], W, H, par * (6 + q * 8) * k);
                float top = (float) (l[0] * k - 40 * k);
                paint.setColor(0xff000000);
                paint.setShader(new LinearGradient(0, top, 0, (float) (l[0] * k + 120 * k), argb(haze, 0), argb(haze, 0.22 - q * 0.04), Shader.TileMode.CLAMP));
                c.drawRect(0, top, W, (float) (top + 160 * k), paint);
                paint.setShader(null);
            }
            if (dz.scene.equals("lake")) {
                // water reflecting the sky, with ripples and the sun's glitter path
                double wy0 = 1500 * k, wy1 = 1900 * k;
                Rng rw = new Rng(13);
                paint.setColor(0xff000000);
                paint.setShader(new LinearGradient(0, (float) wy0, 0, (float) wy1, argb(mix(sky[3], haze, 0.3), 1), argb(mix(sky[1], NIGHTC, 0.25), 1), Shader.TileMode.CLAMP));
                c.drawRect(0, (float) wy0, W, H, paint);
                paint.setShader(null);
                for (int i = 0; i < 40; i++) {
                    double ry = wy0 + Math.pow(rw.next(), 1.3) * (wy1 - wy0), rx = rw.next() * W, rl = (40 + rw.next() * 120) * k, ra = (0.08 + 0.12 * rw.next()) * (0.4 + 0.6 * light);
                    paint.setColor(argb(WHITE, ra));
                    c.drawRect((float) rx, (float) ry, (float) (rx + rl), (float) (ry + 3 * k), paint);
                }
                if (sun[0] > -2) {
                    paint.setColor(argb(sunC, 0.35));
                    for (int i = 0; i < 14; i++) {
                        double gw = (90 - i * 3) * k * (0.6 + rw.next() * 0.8), gox = (rw.next() - 0.5) * 20 * k, gy = wy0 + 10 * k + i * 26 * k;
                        c.drawRect((float) (sunX - gw / 2 + gox), (float) gy, (float) (sunX + gw / 2 + gox), (float) (gy + 5 * k), paint);
                    }
                }
            }
            if (dz.scene.equals("fields")) {
                // two field bands with rows of crops curving across them
                double[] fy = { 1600, 1700 };
                for (int q = 0; q < 2; q++) {
                    double[] base = q == 1 ? hex(sc[2]) : mix(hex(sc[2]), near, 0.35);
                    paint.setColor(argb(lit(base), 1));
                    path.reset(); path.moveTo(-40, H);
                    for (int xx = -40; xx <= W + 40; xx += 20) path.lineTo(xx, (float) fieldY(fy[q], q, xx, W, k));
                    path.lineTo(W + 40, H); path.close();
                    c.drawPath(path, paint);
                    stroke(argb(lit(scale(base, 0.82)), 0.6), 3 * k);
                    for (int rI = 1; rI < 7; rI++) {
                        path.reset();
                        for (int xx = -40; xx <= W + 40; xx += 20) {
                            float yy = (float) (fieldY(fy[q], q, xx, W, k) + rI * (16 + rI * 4) * k);
                            if (xx == -40) path.moveTo(xx, yy); else path.lineTo(xx, yy);
                        }
                        c.drawPath(path, paint);
                    }
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }

        /* foreground hill the tree stands on */
        double[] ground = hex(sc[2]);
        double hillDx = par * 30 * k;
        paint.setColor(0xff000000);
        paint.setShader(new LinearGradient(0, (float) (1780 * k), 0, H, argb(lit(mix(ground, WHITE, 0.12)), 1), argb(lit(scale(ground, 0.62)), 1), Shader.TileMode.CLAMP));
        path.reset(); path.moveTo(-40, H);
        for (int hx = -40; hx <= W + 40; hx += 12) path.lineTo(hx, (float) hillY(hx, hillDx, W, k));
        path.lineTo(W + 40, H); path.close();
        c.drawPath(path, paint);
        paint.setShader(null);

        /* ---- the tree ---- */
        String key = seed + "|" + dz.tree;
        if (!key.equals(treeKey) || segs == null) { treeKey = key; makeTree(seed, dz.tree); }
        double size = 0.62 + 0.38 * Math.pow(t, 0.8);                // a seedling starts small and close to the ground
        double tk = k * 1.22 * size, bx0 = 545 * sx + par * 40 * k, by0 = 1815 * k;
        double[] trunk = lit(hex(dz.trunk)), bark = scale(trunk, 0.7);
        double sunSide = sunX > bx0 ? 1 : -1;
        double[] rim = mix(lit(mix(hex(dz.trunk), hex("#ffe2b8"), 0.55)), WARM, 0.3 * wG);
        double[][] leaf = new double[3][];
        for (int j = 0; j < 3; j++) leaf[j] = lit(hex(dz.leaf[j]));
        if (dz.tree.equals("pine")) drawPine(c, tk, bx0, by0, t, trunk, leaf, dz.scene.equals("snow"), sunSide, seed);
        else {
            int L = levels;
            double P = 0.9 + t * (L + 0.25), thin = 0.5 + 0.5 * Math.min(1, t * 1.5);
            boolean banyan = dz.tree.equals("banyan");
            ArrayList<double[]> clusters = new ArrayList<>();
            ArrayList<Limb> geo = new ArrayList<>();
            for (int si = 0; si < segs.size(); si++) {
                Seg s = segs.get(si);
                double gg = clamp01(P - s.d);
                if (gg <= 0) continue;
                double X0 = bx0 + s.x0 * tk, Y0 = by0 + s.y0 * tk, CX = bx0 + s.cx * tk, CY = by0 + s.cy * tk, X1 = bx0 + s.x1 * tk, Y1 = by0 + s.y1 * tk;
                // grow along the curve: split the quadratic at gg
                Limb g = new Limb();
                g.s = s; g.si = si; g.gg = gg; g.X0 = X0; g.Y0 = Y0;
                g.qx = X0 + (CX - X0) * gg; g.qy = Y0 + (CY - Y0) * gg;
                g.ex = (1 - gg) * (1 - gg) * X0 + 2 * (1 - gg) * gg * CX + gg * gg * X1;
                g.ey = (1 - gg) * (1 - gg) * Y0 + 2 * (1 - gg) * gg * CY + gg * gg * Y1;
                double w0 = s.w * tk * thin, w1 = w0 * (1 - 0.3 * gg);
                double dx = g.ex - X0, dy = g.ey - Y0, ln = Math.hypot(dx, dy);
                if (ln == 0) ln = 1;
                g.a = w0 / 2; g.b = w1 / 2; g.nx = -dy / ln; g.ny = dx / ln;
                geo.add(g);
                // leaves sit on the outer twigs, and on whatever tip is growing right now (so a young plant has leaves)
                boolean frontier = s.d == L || P - (s.d + 1) < 0.35;
                if ((s.d >= Math.max(2, Math.floor(P) - 2) || frontier) && gg > 0.15) clusters.add(new double[] { g.ex, g.ey, si, gg });
            }
            // soft shadow on the ground under the crown
            double shR = (120 + 260 * t) * tk;
            c.save();
            c.translate((float) bx0, (float) (by0 + 8 * k));
            c.scale(1, 0.16f);
            paint.setColor(0xff000000);
            paint.setShader(new RadialGradient(0, 0, (float) shR, argb(new double[] { 10, 16, 10 }, 0.35 * light), argb(new double[] { 10, 16, 10 }, 0), Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, (float) shR, paint);
            paint.setShader(null);
            c.restore();
            // far side of the crown goes behind the branches, so some branches cross in front of leaves
            drawCanopy(c, tk, clusters, leaf, t, sunSide, dz, true);
            for (Limb g : geo) drawLimb(c, k, g, trunk, bark, rim, sunSide, banyan);
            if (banyan) drawRoots(c, k, tk, geo, t, trunk, bark, hillDx, W);
            drawCanopy(c, tk, clusters, leaf, t, sunSide, dz, false);
            drawBlooms(c, k, tk, clusters, leaf, t, dz);
            double young = clamp01(1 - t / 0.35);                         // seedling leaves fade as real branches take over
            if (young > 0.01 && !geo.isEmpty())
                drawSeedling(c, k, tk, geo.get(0), new double[][] { lit(hex("#4f8a3a")), lit(hex("#8cc35a")) }, young, sunSide, lit(mix(ground, hex("#5a3c24"), 0.6)));
        }

        /* falling petals / leaves, or snow */
        double[] fall = lit(hex(dz.fall));
        Rng rp = new Rng(6);
        boolean snowing = dz.tree.equals("pine");
        long np = snowing ? 70 : Math.round(26 * clamp01((t - 0.4) / 0.6));
        for (int i = 0; i < np; i++) {
            double px = snowing ? rp.next() * W : (330 + rp.next() * 480) * sx + par * 40 * k;
            double py = (snowing ? 300 + rp.next() * 1700 : 1300 + rp.next() * 560) * k, rot = rp.next() * 3, pa = 0.5 + rp.next() * 0.4;
            paint.setColor(argb(fall, pa));
            if (snowing) c.drawCircle((float) px, (float) py, (float) ((2 + rot) * k), paint);
            else leafShape(c, px, py, 11 * k, rot);
        }

        /* grass tufts along the ridge */
        Rng rg = new Rng(7);
        double[] grass = lit(hex(sc[3]));
        for (int i = 0; i < 110; i++) {
            double gx = rg.next() * W, gh = (16 + rg.next() * 30) * k, lean = (rg.next() - 0.5) * 12 * k, gy = hillY(gx, hillDx, W, k) + 4 * k, shade = 0.8 + rg.next() * 0.4;
            paint.setColor(argb(scale(grass, shade), 1));
            path.reset();
            path.moveTo((float) (gx - 5 * k), (float) (gy + 6 * k));
            path.lineTo((float) (gx + lean), (float) (gy - gh));
            path.lineTo((float) (gx + 5 * k), (float) (gy + 6 * k));
            path.close();
            c.drawPath(path, paint);
        }

        if (label != null) drawLabel(c, W, k, label, leaf);
    }

    /** One grown limb, ready to draw. */
    private static final class Limb {
        Seg s;
        int si;
        double gg, X0, Y0, qx, qy, ex, ey, a, b, nx, ny;
    }

    /* a tapered, bent limb; off shifts it sideways and twist pushes its bend (both in half-widths), wf scales its width */
    private void taper(Canvas c, Limb g, double off, double twist, double wf) {
        double nx = g.nx, ny = g.ny, a = g.a * wf, b = g.b * wf, mm = (a + b) / 2;
        double ox = nx * g.a * off, oy = ny * g.a * off, tx = nx * g.a * twist, ty = ny * g.a * twist;
        path.reset();
        path.moveTo((float) (g.X0 + ox + nx * a), (float) (g.Y0 + oy + ny * a));
        path.quadTo((float) (g.qx + ox + tx + nx * mm), (float) (g.qy + oy + ty + ny * mm), (float) (g.ex + ox * 0.5 + nx * b), (float) (g.ey + oy * 0.5 + ny * b));
        path.lineTo((float) (g.ex + ox * 0.5 - nx * b), (float) (g.ey + oy * 0.5 - ny * b));
        path.quadTo((float) (g.qx + ox + tx - nx * mm), (float) (g.qy + oy + ty - ny * mm), (float) (g.X0 + ox - nx * a), (float) (g.Y0 + oy - ny * a));
        path.close();
        c.drawPath(path, paint);
    }

    /* trunk and branches: banyan trunks are three fused, twisting strands; thick limbs get a lit edge and bark grooves */
    private void drawLimb(Canvas c, double k, Limb g, double[] trunk, double[] bark, double[] rim, double sunSide, boolean banyan) {
        Seg s = g.s;
        double side = g.nx * sunSide >= 0 ? 1 : -1;
        paint.setStyle(Paint.Style.FILL);
        if (banyan && s.d <= 1) {
            paint.setColor(argb(scale(trunk, side > 0 ? 0.85 : 1.08), 1)); taper(c, g, -0.45, 0.5, 0.55);
            paint.setColor(argb(scale(trunk, side > 0 ? 1.08 : 0.85), 1)); taper(c, g, 0.45, -0.4, 0.55);
            paint.setColor(argb(trunk, 1)); taper(c, g, 0, 0.3, 0.6);
        } else {
            paint.setColor(argb(trunk, 1)); taper(c, g, 0, 0, 1);
        }
        paint.setColor(argb(trunk, 1));
        c.drawCircle((float) g.ex, (float) g.ey, (float) g.b, paint);
        if (s.d >= (banyan ? 3 : 2)) return;
        double mm = (g.a + g.b) / 2;
        stroke(argb(rim, 0.7), Math.max(2 * k, g.a * 0.3));
        curve(c, g.X0 + g.nx * g.a * 0.72 * side, g.Y0 + g.ny * g.a * 0.72 * side, g.qx + g.nx * mm * 0.72 * side, g.qy + g.ny * mm * 0.72 * side,
            g.ex + g.nx * g.b * 0.72 * side, g.ey + g.ny * g.b * 0.72 * side);
        Rng rb = new Rng(g.si * 97 + 3);
        int n = banyan ? 14 : 8;
        for (int i = 0; i < n; i++) {
            double off = (rb.next() - 0.5) * 1.5, u0 = rb.next() * 0.75, u1 = Math.min(1, u0 + 0.12 + rb.next() * 0.3);
            boolean dark = rb.next() < 0.65;
            stroke(dark ? argb(bark, 0.65) : argb(rim, 0.28), Math.max(1.2 * k, g.a * (dark ? 0.07 : 0.05)));
            path.reset();
            for (int q = 0; q <= 4; q++) {
                double u = u0 + (u1 - u0) * q / 4, A = (1 - u) * (1 - u), B = 2 * (1 - u) * u, C = u * u, hw = g.a + (g.b - g.a) * u;
                float px = (float) (A * g.X0 + B * g.qx + C * g.ex + g.nx * hw * off), py = (float) (A * g.Y0 + B * g.qy + C * g.ey + g.ny * hw * off);
                if (q == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /* banyan aerial roots: thin ones hang from the branches, a few thicken into pillar roots as the tree matures */
    private void drawRoots(Canvas c, double k, double tk, ArrayList<Limb> geo, double t, double[] trunk, double[] bark, double hillDx, int W) {
        for (Limb g : geo) {
            Seg s = g.s;
            if (g.gg < 1) continue;
            double u = 0.45 + s.r * 0.45, A = (1 - u) * (1 - u), B = 2 * (1 - u) * u, C = u * u;
            double x0 = A * g.X0 + B * g.qx + C * g.ex, y0 = A * g.Y0 + B * g.qy + C * g.ey, y1 = hillY(x0, hillDx, W, k) + 6 * k;
            if (s.d == 1 && s.r < (t - 0.5) * 1.6) {
                double w = (10 + s.r * 10) * tk;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(argb(scale(trunk, 0.95), 1));
                path.reset();
                path.moveTo((float) (x0 - w * 0.3), (float) y0); path.lineTo((float) (x0 + w * 0.3), (float) y0);
                path.lineTo((float) (x0 + w * 0.55), (float) y1); path.lineTo((float) (x0 - w * 0.55), (float) y1);
                path.close();
                c.drawPath(path, paint);
                stroke(argb(bark, 0.6), Math.max(1, w * 0.08));
                path.reset(); path.moveTo((float) x0, (float) y0); path.lineTo((float) (x0 + w * 0.1), (float) y1);
                c.drawPath(path, paint);
            } else if (s.d >= 2 && s.d <= 4 && s.r < t * 0.95) {
                double ye = y0 + (y1 - y0) * (0.55 + 0.45 * clamp01(t * 1.2 - s.r));     // young roots don't reach the ground yet
                stroke(argb(scale(trunk, 1.12), 0.85), (2 + s.r * 3) * k);
                curve(c, x0, y0, x0 + (s.r - 0.5) * 24 * k, (y0 + ye) / 2, x0 + (s.r - 0.5) * 10 * k, ye);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /* crown. back = the far side, drawn before the branches: a darker, slightly higher mass per cluster.
       front = dark inner mass, mid leaves around it, light leaves on the sun's side. */
    private void drawCanopy(Canvas c, double tk, ArrayList<double[]> clusters, double[][] leaf, double t, double sunSide, Design dz, boolean back) {
        double leafy = 0.45 + 0.55 * t, wide = dz.tree.equals("banyan") ? 0.58 : 0.42;
        paint.setStyle(Paint.Style.FILL);
        if (back) {
            paint.setColor(argb(mix(scale(leaf[0], 0.8), haze, 0.12), 1));
            for (double[] cl : clusters) {
                Rng r3 = new Rng((int) cl[2] * 31 + 5);
                double s = cl[3] * leafy;
                for (int j = 0; j < 2; j++) {
                    double cx = cl[0] + (r3.next() - 0.5) * 70 * s * tk, cy = cl[1] - (14 + r3.next() * 20) * s * tk, cr = (38 + r3.next() * 26) * s * tk;
                    c.drawCircle((float) cx, (float) cy, (float) cr, paint);
                }
            }
            return;
        }
        paint.setColor(argb(leaf[0], 1));
        for (double[] cl : clusters) {
            Rng r3 = new Rng((int) cl[2] * 31 + 7);
            double s = cl[3] * leafy;
            for (int j = 0; j < 3; j++) {
                double cx = cl[0] + (r3.next() - 0.5) * 60 * s * tk, cy = cl[1] + (r3.next() - 0.3) * 40 * s * tk, cr = (32 + r3.next() * 24) * s * tk;
                c.drawCircle((float) cx, (float) cy, (float) cr, paint);
            }
        }
        paint.setColor(argb(leaf[1], 1));
        for (double[] cl : clusters) {
            Rng r3 = new Rng((int) cl[2] * 31 + 11);
            double s = cl[3] * leafy, R = 50 * s * tk;
            c.drawCircle((float) (cl[0] + sunSide * 6 * s * tk), (float) (cl[1] - 10 * s * tk), (float) (R * 0.62), paint);
            for (int j = 0; j < 13; j++) {
                double a = r3.next() * TAU, dd = (0.45 + r3.next() * 0.6) * R, ll = (12 + r3.next() * 8) * s * tk, la = a + (r3.next() - 0.5) * 0.7;
                leafShape(c, cl[0] + Math.cos(a) * dd, cl[1] + Math.sin(a) * dd * 0.85 - 8 * s * tk, ll, la, wide);
            }
        }
        paint.setColor(argb(leaf[2], 1));
        for (double[] cl : clusters) {
            Rng r3 = new Rng((int) cl[2] * 31 + 13);
            double s = cl[3] * leafy, R = 46 * s * tk;
            for (int j = 0; j < 7; j++) {
                double a = -Math.PI / 2 + sunSide * (0.2 + r3.next() * 1.0) + (r3.next() - 0.5) * 0.5;
                double dd = (0.25 + r3.next() * 0.65) * R, ll = (10 + r3.next() * 6) * s * tk, la = a + (r3.next() - 0.5) * 0.8;
                leafShape(c, cl[0] + Math.cos(a) * dd, cl[1] + Math.sin(a) * dd - 10 * s * tk, ll, la, wide);
            }
        }
    }

    /* a young plant: a mound of soil at the foot and pairs of fresh green leaves (green, light) along the stem */
    private void drawSeedling(Canvas c, double k, double tk, Limb g, double[][] green, double young, double sunSide, double[] soil) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb(soil, young));
        rect.set((float) (g.X0 - 60 * k), (float) (g.Y0 + 4 * k - 15 * k), (float) (g.X0 + 60 * k), (float) (g.Y0 + 4 * k + 15 * k));
        c.drawOval(rect, paint);
        double dirA = Math.atan2(g.ey - g.Y0, g.ex - g.X0);
        double[][] pairs = { { 0.5, 0.8 }, { 0.78, 1.0 } };
        for (int i = 0; i < 2; i++) {
            double u = pairs[i][0], A = (1 - u) * (1 - u), B = 2 * (1 - u) * u, C = u * u;
            double px = A * g.X0 + B * g.qx + C * g.ex, py = A * g.Y0 + B * g.qy + C * g.ey;
            double len = 58 * tk * pairs[i][1] * (0.55 + 0.45 * young);
            for (int side = -1; side <= 1; side += 2) {
                double ang = dirA + side * (1.05 - i * 0.15), cx = px + Math.cos(ang) * len * 0.95, cy = py + Math.sin(ang) * len * 0.95;
                paint.setColor(argb(green[0], young));
                leafShape(c, cx, cy, len, ang, 0.5);
                if (side == sunSide) {
                    paint.setColor(argb(green[1], young));
                    leafShape(c, cx - Math.cos(ang) * len * 0.1, cy - Math.sin(ang) * len * 0.1 - 2 * k, len * 0.6, ang, 0.38);
                }
            }
        }
    }

    /* flowers (blossoms or jacaranda bunches), or figs ripening on a mature banyan */
    private void drawBlooms(Canvas c, double k, double tk, ArrayList<double[]> clusters, double[][] leaf, double t, Design dz) {
        if (clusters.isEmpty()) return;
        Rng rb = new Rng(5);
        paint.setStyle(Paint.Style.FILL);
        if (dz.tree.equals("banyan")) {
            long nf = Math.round(clusters.size() * 0.45 * clamp01((t - 0.6) / 0.4));
            double[] fig = hex("#a8402c"), shine = hex("#f08a6a");
            for (int i = 0; i < nf; i++) {
                double[] cl = clusters.get((int) Math.floor(rb.next() * clusters.size()));
                double fx = cl[0] + (rb.next() - 0.5) * 70 * tk, fy = cl[1] + (rb.next() - 0.4) * 50 * tk, fr = (3 + rb.next() * 2) * k;
                paint.setColor(argb(scale(fig, 0.6 + 0.4 * light), 1));
                c.drawCircle((float) fx, (float) fy, (float) fr, paint);
                paint.setColor(argb(shine, 0.7 * light));
                c.drawCircle((float) (fx - fr * 0.3), (float) (fy - fr * 0.3), (float) (fr * 0.35), paint);
            }
            return;
        }
        if (dz.flower.isEmpty()) return;
        double[] fl = hex(dz.flower);
        boolean bunches = dz.tree.equals("umbrella");
        long nb = Math.round(clusters.size() * (bunches ? 2.2 : 1.3) * clamp01((t - 0.25) / 0.75));
        for (int i = 0; i < nb; i++) {
            double[] cl = clusters.get((int) Math.floor(rb.next() * clusters.size()));
            double fx = cl[0] + (rb.next() - 0.5) * 80 * tk, fy = cl[1] + (rb.next() - 0.65) * 60 * tk, fr = (4 + rb.next() * 3) * k;
            if (bunches) {
                // jacaranda: little bunches of flowers on top of the crown, in two shades
                paint.setColor(argb(mix(fl, hex(dz.flower2), rb.next()), 0.6 + 0.4 * light));
                for (int q = 0; q < 4; q++) {
                    double qa = q / 4.0 * TAU + rb.next();
                    c.save();
                    c.translate((float) (fx + Math.cos(qa) * fr), (float) (fy + Math.sin(qa) * fr));
                    c.rotate((float) Math.toDegrees(qa));
                    rect.set((float) (-fr * 1.3), (float) (-fr * 0.7), (float) (fr * 1.3), (float) (fr * 0.7));
                    c.drawOval(rect, paint);
                    c.restore();
                }
            } else {
                // five-petal blossom with a darker heart
                paint.setColor(argb(fl, 0.65 + 0.35 * light));
                for (int q = 0; q < 5; q++) {
                    double pa = q / 5.0 * TAU;
                    c.drawCircle((float) (fx + Math.cos(pa) * fr * 0.9), (float) (fy + Math.sin(pa) * fr * 0.9), (float) (fr * 0.65), paint);
                }
                paint.setColor(argb(leaf[0], 0.8));
                c.drawCircle((float) fx, (float) fy, (float) (fr * 0.35), paint);
            }
        }
    }

    /* pine: a straight trunk with tiers of jagged boughs; snow settles on the tiers in the snow scene */
    private void drawPine(Canvas c, double tk, double bx0, double by0, double t, double[] trunk, double[][] leaf, boolean snowy, double sunSide, int seed) {
        int tiers = 4 + (int) Math.round(5 * t);
        double grow = 0.35 + 0.65 * t, H0 = 980 * tk * grow, w0 = 34 * tk;
        Rng rz = new Rng(seed * 7 + 3);
        paint.setColor(argb(trunk, 1));
        path.reset();
        path.moveTo((float) (bx0 - w0 / 2), (float) by0); path.lineTo((float) (bx0 - w0 * 0.15), (float) (by0 - H0));
        path.lineTo((float) (bx0 + w0 * 0.15), (float) (by0 - H0)); path.lineTo((float) (bx0 + w0 / 2), (float) by0);
        path.close();
        c.drawPath(path, paint);
        for (int i = 0; i < tiers; i++) {
            double u = i / (double) (tiers - 1), y = by0 - H0 * (0.18 + 0.8 * u), hw = (300 - 250 * u) * tk * grow, th = (190 - 80 * u) * tk * (0.5 + 0.5 * grow);
            double[][] pts = new double[9][];
            pts[0] = new double[] { bx0, y - th };
            for (int q = 0; q <= 7; q++) pts[q + 1] = new double[] { bx0 - hw + q / 7.0 * hw * 2, y + (q % 2 == 1 ? -th * 0.18 : 0) + (rz.next() - 0.5) * 10 * tk };
            // dark whole tier, then the lit half and a narrower highlight on the sun's side
            for (int lc = 0; lc < 3; lc++) {
                paint.setColor(argb(leaf[lc], 1));
                path.reset();
                path.moveTo((float) pts[0][0], (float) pts[0][1]);
                if (lc == 0) {
                    for (int p = 1; p < pts.length; p++) path.lineTo((float) pts[p][0], (float) pts[p][1]);
                } else {
                    double narrow = lc == 2 ? 0.45 : 1, drop = lc == 2 ? 0.7 : 1;
                    for (int p = 1; p < pts.length; p++) {
                        if ((pts[p][0] - bx0) * sunSide >= -2) path.lineTo((float) (bx0 + (pts[p][0] - bx0) * narrow), (float) (pts[0][1] + (pts[p][1] - pts[0][1]) * drop));
                    }
                    path.lineTo((float) bx0, (float) (y - th * (lc == 2 ? 0.4 : 0.05)));
                }
                path.close();
                c.drawPath(path, paint);
            }
            if (snowy) {
                paint.setColor(argb(mix(WHITE, leaf[2], 0.15), 0.6 + 0.4 * light));
                path.reset();
                path.moveTo((float) pts[0][0], (float) pts[0][1]);
                path.lineTo((float) (bx0 - hw * 0.55), (float) (y - th * 0.38)); path.lineTo((float) (bx0 - hw * 0.2), (float) (y - th * 0.5));
                path.lineTo((float) (bx0 + hw * 0.15), (float) (y - th * 0.36)); path.lineTo((float) (bx0 + hw * 0.5), (float) (y - th * 0.42));
                path.close();
                c.drawPath(path, paint);
            }
        }
    }

    private void drawLabel(Canvas c, int W, double k, Label l, double[][] leaf) {
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
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x730a0e18);
        rect.set((float) x, (float) y, (float) (x + w), (float) (y + h));
        c.drawRoundRect(rect, (float) (h / 2), (float) (h / 2), paint);
        text.setColor(0xffffffff);
        c.drawText(str, W / 2f, (float) (y + (bar ? 60 : 61) * z), text);
        if (bar) {
            double frac = Math.min(1, s / (double) goal);
            paint.setColor(0x38ffffff);
            rect.set((float) (x + 45 * z), (float) (y + 80 * z), (float) (x + w - 45 * z), (float) (y + 90 * z));
            c.drawRoundRect(rect, (float) (5 * z), (float) (5 * z), paint);
            paint.setColor(argb(mix(leaf[2], WHITE, 0.2), 1));
            rect.set((float) (x + 45 * z), (float) (y + 80 * z), (float) (x + 45 * z + Math.max(10 * z, (w - 90 * z) * frac)), (float) (y + 90 * z));
            c.drawRoundRect(rect, (float) (5 * z), (float) (5 * z), paint);
        }
    }

    /* ---------- tree skeletons; the shape never depends on growth, so it stays the same tree all day ---------- */
    private void makeTree(int seed, String kind) {
        segs = new ArrayList<>();
        Rng rng = new Rng(seed);
        double spread = kind.equals("umbrella") ? 0.9 : kind.equals("banyan") ? 1.15 : 0.62;
        double up = kind.equals("umbrella") ? 0.78 : kind.equals("banyan") ? 0.55 : 0.9;   // how much branches keep pointing up
        levels = kind.equals("banyan") ? 5 : 6;
        lenGrow = kind.equals("banyan") ? 0.8 : 0.72;      // banyan limbs stay long: a wide, low crown
        double ang0 = (rng.next() - 0.5) * 0.08;
        branch(rng, 0, 0, ang0, kind.equals("banyan") ? 165 : 230, kind.equals("banyan") ? 80 : 44, 0, spread, up);
    }

    private void branch(Rng rng, double x, double y, double ang, double len, double w, int d, double spread, double up) {
        double bend = (rng.next() - 0.5) * len * 0.35;
        Seg s = new Seg();
        s.x0 = x; s.y0 = y;
        s.x1 = x + Math.sin(ang) * len; s.y1 = y - Math.cos(ang) * len;
        s.cx = (x + s.x1) / 2 + Math.cos(ang) * bend; s.cy = (y + s.y1) / 2 + Math.sin(ang) * bend;
        s.w = w; s.d = d; s.r = rng.next();
        segs.add(s);
        if (d >= levels) return;
        int n = d < 2 ? 2 : (rng.next() < 0.3 ? 3 : 2);
        for (int i = 0; i < n; i++) {
            int side = n == 2 ? (i != 0 ? 1 : -1) : i - 1;
            double na = ang * up + side * (0.32 + rng.next() * 0.3) * spread + (rng.next() - 0.5) * 0.15;
            double nl = len * (lenGrow + rng.next() * 0.1);
            branch(rng, s.x1, s.y1, na, nl, w * 0.7, d + 1, spread, up);
        }
    }

    /* ---------- drawing helpers ---------- */

    private void ridge(Canvas c, Rng rng, double baseY, double amp, double rough, int W, int H, double dx) {
        double ph0 = rng.next() * 10, ph1 = rng.next() * 10, ph2 = rng.next() * 10, ph3 = rng.next() * 10;
        path.reset(); path.moveTo(-40, H);
        double lastX = 0;
        for (int x = -40; x <= W + 40; x += 12) {
            double u = x / (double) W;
            double y = baseY - amp * (0.55 * Math.sin(u * 3.1 + ph0) + 0.3 * Math.sin(u * 7.3 + ph1) + rough * 0.18 * Math.sin(u * 19 + ph2) + rough * 0.08 * Math.sin(u * 41 + ph3));
            path.lineTo((float) (x + dx), (float) y);
            lastX = x;
        }
        path.lineTo((float) (lastX + dx), H); path.close();
        c.drawPath(path, paint);
    }

    /** An almond-shaped leaf centred on (x, y), pointing along ang. */
    private void leafShape(Canvas c, double x, double y, double len, double ang) { leafShape(c, x, y, len, ang, 0.42); }

    /** wide is the leaf's width as a fraction of its length. */
    private void leafShape(Canvas c, double x, double y, double len, double ang, double wide) {
        double cs = Math.cos(ang), sn = Math.sin(ang), w = len * wide;
        path.reset();
        path.moveTo((float) (x - cs * len), (float) (y - sn * len));
        path.quadTo((float) (x - sn * w), (float) (y + cs * w), (float) (x + cs * len), (float) (y + sn * len));
        path.quadTo((float) (x + sn * w), (float) (y - cs * w), (float) (x - cs * len), (float) (y - sn * len));
        path.close();
        c.drawPath(path, paint);
    }

    private void stroke(int color, double width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth((float) width);
        paint.setColor(color);
    }

    private void curve(Canvas c, double x0, double y0, double cx, double cy, double x1, double y1) {
        path.reset();
        path.moveTo((float) x0, (float) y0);
        path.quadTo((float) cx, (float) cy, (float) x1, (float) y1);
        c.drawPath(path, paint);
    }

    /** A radial gradient with an inner radius, like canvas createRadialGradient(cx, cy, r0, cx, cy, r1). */
    private void radial(Canvas c, int W, int H, double cx, double cy, double r0, double r1, int[] cols, double[] stops) {
        int[] colors = new int[cols.length + 1];
        float[] pos = new float[cols.length + 1];
        colors[0] = cols[0]; pos[0] = 0;
        for (int i = 0; i < cols.length; i++) { colors[i + 1] = cols[i]; pos[i + 1] = (float) ((r0 + stops[i] * (r1 - r0)) / r1); }
        paint.setColor(0xff000000);
        paint.setShader(new RadialGradient((float) cx, (float) cy, (float) r1, colors, pos, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);
    }

    private static double hillY(double x, double hillDx, int W, double k) {
        double u = (x - hillDx - 0.52 * W) / (0.54 * W);
        return 1805 * k + 125 * k * u * u;
    }

    private static double fieldY(double y0, int q, double x, int W, double k) {
        return y0 * k + Math.sin(x / W * 3 + q * 2) * 30 * k;
    }

    private double[] lit(double[] col) { return mix(mix(scale(col, light), WARM, 0.16 * wG), NIGHTC, 0.35 * wN); }

    private double[] hazeMix(double[] col, double f) { return mix(lit(col), haze, f); }

    /* ---------- sun and moon (match lib/art.js) ---------- */

    /** {elevation, azimuth from north} in degrees, NOAA's low-precision formulas. */
    static double[] sunPos(long ms, double lat, double lon) {
        double n = ms / 86400000.0 + 2440587.5 - 2451545.0;
        double L = (280.460 + 0.9856474 * n) % 360, g = ((357.528 + 0.9856003 * n) % 360) * D2R;
        double lam = (L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) * D2R, eps = (23.439 - 0.0000004 * n) * D2R;
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam)), dec = Math.asin(Math.sin(eps) * Math.sin(lam));
        double gmst = (18.697374558 + 24.06570982441908 * n) % 24, ha = ((gmst + lon / 15) * 15) * D2R - ra;
        double la = lat * D2R;
        double el = Math.asin(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha));
        double az = Math.atan2(-Math.sin(ha), Math.tan(dec) * Math.cos(la) - Math.sin(la) * Math.cos(ha));
        return new double[] { el / D2R, (az / D2R + 360) % 360 };
    }

    /** 0 = new moon, 0.5 = full moon. */
    static double moonPhase(long ms) {
        double p = ((ms - 947182440000L) / 86400000.0 / 29.530588853) % 1;
        return p < 0 ? p + 1 : p;
    }

    /* ---------- small helpers ---------- */

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

    private static double smooth(double a, double b, double v) {
        double u = clamp01((v - a) / (b - a));
        return u * u * (3 - 2 * u);
    }
}
