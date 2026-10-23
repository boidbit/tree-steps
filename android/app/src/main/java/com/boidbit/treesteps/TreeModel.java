package com.boidbit.treesteps;

/**
 * The 3D tree, built with the same hashing and growth rules as Tree3D.html, so a seed gives the
 * same tree in the app and on the wallpaper. Only positions, sizes and colours are kept here;
 * TreeRenderer projects and draws them.
 */
final class TreeModel {
    static final String[] PAL_NAMES = { "Sunset", "Ocean", "Forest", "Ember", "Orchid", "Slate" };
    private static final String[][] PAL_HEX = {
        { "#140b2b", "#4a1d6b", "#b5306b", "#ff6a4d", "#ffc36b" },
        { "#04121f", "#06375a", "#0f7a9c", "#35c1c4", "#bff3e2" },
        { "#07120c", "#10381f", "#2c7a43", "#8cc152", "#f1e9a6" },
        { "#0d0707", "#3a0f10", "#8f1d1d", "#e5532d", "#ffd08a" },
        { "#0b0620", "#2a0f5c", "#6d28d9", "#e040a0", "#5ef2e0" },
        { "#0c0f14", "#232b38", "#4a5668", "#8d9bb0", "#e6ebf2" }
    };
    private static final double TAU = Math.PI * 2;
    private static final double DETAIL = 0.6;
    private static final int BARK_MAX = 4000, LEAF_MAX = 14000, BLOOM_MAX = 2500;

    final int seed;
    final double t;                       // growth 0..1 (steps / 10000)
    final double[][] pal;

    int nb, nl, nbl;
    final float[] bx0 = new float[BARK_MAX], by0 = new float[BARK_MAX], bz0 = new float[BARK_MAX];
    final float[] bx1 = new float[BARK_MAX], by1 = new float[BARK_MAX], bz1 = new float[BARK_MAX];
    final float[] bw = new float[BARK_MAX];
    final int[] bcol = new int[BARK_MAX];
    final float[] lx = new float[LEAF_MAX], ly = new float[LEAF_MAX], lz = new float[LEAF_MAX], ls = new float[LEAF_MAX];
    final boolean[] lvert = new boolean[LEAF_MAX];
    final int[] lcol = new int[LEAF_MAX];
    final float[] fx = new float[BLOOM_MAX], fy = new float[BLOOM_MAX], fz = new float[BLOOM_MAX], fs = new float[BLOOM_MAX];
    float maxY, maxR;

    private final int levels;
    private final double P;
    private final double[] wood, twig;

    TreeModel(int seed, String palName, int steps) {
        this.seed = seed;
        this.t = clamp01(steps / 10000.0);
        this.pal = palette(palName);
        levels = (int) Math.round(6 + DETAIL * 3);
        P = 0.3 + t * (levels + 0.2);
        wood = mix(new double[] { 62, 42, 34 }, pal[1], 0.28);
        twig = mix(new double[] { 96, 70, 52 }, pal[3], 0.18);

        double[] dir = norm(new double[] { (hash(seed, 777) - 0.5) * 0.06, 1, (hash(seed, 778) - 0.5) * 0.06 });
        branch(new double[] { 0, 0, 0 }, dir, 1.5, 0.17, 0, 1);
        addLeaves();
    }

    private void branch(double[] p0, double[] dir, double len, double w, int depth, int id) {
        double g = clamp01(P - depth);
        if (g <= 0 || nb >= BARK_MAX) return;
        double l2 = len * g;
        double[] p1 = { p0[0] + dir[0] * l2, p0[1] + dir[1] * l2, p0[2] + dir[2] * l2 };
        bx0[nb] = (float) p0[0]; by0[nb] = (float) p0[1]; bz0[nb] = (float) p0[2];
        bx1[nb] = (float) p1[0]; by1[nb] = (float) p1[1]; bz1[nb] = (float) p1[2];
        bw[nb] = (float) (w * (0.55 + 0.45 * g));
        bcol[nb] = argb(mix(wood, twig, clamp01(depth / (double) Math.max(1, levels - 1))));
        branchDepth[nb] = depth; branchId[nb] = id; branchG[nb] = (float) g;
        nb++;
        maxY = Math.max(maxY, (float) p1[1]);
        maxR = Math.max(maxR, (float) Math.sqrt(p1[0] * p1[0] + p1[2] * p1[2]));
        if (g < 1 || depth >= levels - 1) return;

        int n = 2 + (depth > 0 && hash(seed, id * 16 + 9) < 0.1 + DETAIL * 0.3 ? 1 : 0);
        double[] ref = Math.abs(dir[1]) > 0.95 ? new double[] { 1, 0, 0 } : new double[] { 0, 1, 0 };
        double[] a = norm(cross(ref, dir)), b = cross(dir, a);
        double base = hash(seed, id * 16 + 13) * TAU;
        for (int ci = 0; ci < n; ci++) {
            double phi = base + ci * TAU / n + (hash(seed, id * 16 + ci) - 0.5) * 0.7;
            double spread = 0.3 + hash(seed, id * 16 + 3 + ci) * 0.38;
            double cs = Math.cos(spread), sn = Math.sin(spread), cp = Math.cos(phi), sp = Math.sin(phi);
            double[] nd = new double[3];
            for (int k = 0; k < 3; k++) nd[k] = dir[k] * cs + a[k] * cp * sn + b[k] * sp * sn;
            nd[1] += 0.07;
            branch(p1, norm(nd), len * (0.74 + hash(seed, id * 16 + 5 + ci) * 0.08), w * 0.7, depth + 1, id * 4 + ci + 1);
        }
    }

    private final int[] branchDepth = new int[BARK_MAX], branchId = new int[BARK_MAX];
    private final float[] branchG = new float[BARK_MAX];

    private void addLeaves() {
        double grow = 0.45 + 0.55 * Math.min(1, t * 2.5);
        double[] white = { 255, 255, 255 };
        for (int i = 0; i < nb; i++) {
            int depth = branchDepth[i], id = branchId[i];
            double g = branchG[i];
            double mult = depth >= levels - 4 ? 2.6 : depth == 0 ? 0.8 : 1.1;
            int cnt = (int) Math.ceil(g * (1.5 + DETAIL * 3) * mult);
            for (int k = 0; k < cnt && nl < LEAF_MAX; k++) {
                int hk = id * 32 + k;
                double u = depth == 0 ? 0.8 + hash(seed, hk) * 0.2 : 0.25 + hash(seed, hk) * 0.75;
                double x = bx0[i] + (bx1[i] - bx0[i]) * u + (hash(seed, hk + 7) - 0.5) * 0.5;
                double y = by0[i] + (by1[i] - by0[i]) * u + (hash(seed, hk + 8) - 0.5) * 0.5;
                double z = bz0[i] + (bz1[i] - bz0[i]) * u + (hash(seed, hk + 9) - 0.5) * 0.5;
                double sh = hash(seed, hk + 31);
                double sz = (0.1 + hash(seed, hk + 5) * 0.09) * (0.4 + 0.6 * g) * grow;
                lx[nl] = (float) x; ly[nl] = (float) y; lz[nl] = (float) z; ls[nl] = (float) sz;
                lvert[nl] = hash(seed, hk + 11) < 0.5;
                lcol[nl] = argb(mix(ramp(pal, 0.4 + 0.6 * sh), pal[1], (1 - sh) * 0.3));
                nl++;
                if (t > 0.25 && nbl < BLOOM_MAX && hash(seed, hk + 77) < 0.03 + 0.1 * t) {
                    fx[nbl] = (float) x; fy[nbl] = (float) (y + sz * 0.4); fz[nbl] = (float) z; fs[nbl] = (float) (sz * 0.4);
                    nbl++;
                }
            }
        }
        bloomColor = argb(mix(pal[4], white, 0.5));
    }

    int bloomColor;

    /* ---------- helpers (match Tree3D.html) ---------- */

    static double hash(int a, int b) {
        int x = ((a ^ 0x9e3779b9) * 0x85ebca6b) ^ ((b + 1) * 0xc2b2ae35);
        x ^= x >>> 15; x *= 0x2c1b3c6d; x ^= x >>> 12; x *= 0x297a2d39; x ^= x >>> 15;
        return (x & 0xffffffffL) / 4294967296.0;
    }

    static double[][] palette(String name) {
        int idx = 0;
        for (int i = 0; i < PAL_NAMES.length; i++) if (PAL_NAMES[i].equalsIgnoreCase(name)) idx = i;
        double[][] out = new double[5][3];
        for (int i = 0; i < 5; i++) {
            String h = PAL_HEX[idx][i];
            out[i][0] = Integer.parseInt(h.substring(1, 3), 16);
            out[i][1] = Integer.parseInt(h.substring(3, 5), 16);
            out[i][2] = Integer.parseInt(h.substring(5, 7), 16);
        }
        return out;
    }

    static double[] mix(double[] a, double[] b, double t) {
        return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }

    static double[] ramp(double[][] pal, double t) {
        t = clamp01(t) * (pal.length - 1);
        int i = Math.min(pal.length - 2, (int) Math.floor(t));
        return mix(pal[i], pal[i + 1], t - i);
    }

    static int argb(double[] c) {
        return 0xff000000 | (clamp255(c[0]) << 16) | (clamp255(c[1]) << 8) | clamp255(c[2]);
    }

    private static int clamp255(double v) { return (int) Math.max(0, Math.min(255, v)); }
    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    private static double[] cross(double[] a, double[] b) {
        return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static double[] norm(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] { v[0] / l, v[1] / l, v[2] / l };
    }
}
