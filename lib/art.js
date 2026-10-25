/*
 * Tree Steps illustrated renderer (style "A"): layered hills at dusk with a seeded tree as the hero.
 * TreeArt.draw(ctx, W, H, opts) paints one frame; nothing animates, so it is cheap to redraw.
 *
 * android/.../ArtRenderer.java is a line-for-line port. Keep the two in step: same constants, same
 * random streams (mulberry32 seeded per element type), same order of draws.
 *
 * opts: { seed, pal, t (growth 0..1, steps / goal), hour (0-24), parX (-1.5..1.5 parallax),
 *         lift (fraction of H to move the scene up, for the app's bottom card), label: null |
 *         { style: 'steps'|'goal'|'bar'|'percent', pos: 'tree'|'bottom', size: 's'|'m'|'l', steps, goal } }
 */
(function (root) {
  var TAU = Math.PI * 2;

  function mulberry32(a) {
    return function () {
      a |= 0; a = a + 0x6D2B79F5 | 0;
      var t = Math.imul(a ^ a >>> 15, 1 | a);
      t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
      return ((t ^ t >>> 14) >>> 0) / 4294967296;
    };
  }
  function hex(h) { return [parseInt(h.substr(1, 2), 16), parseInt(h.substr(3, 2), 16), parseInt(h.substr(5, 2), 16)]; }
  function mix(a, b, t) { return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]; }
  function scale(c, k) { return [c[0] * k, c[1] * k, c[2] * k]; }
  function rgb(c, a) {
    var f = function (v) { return Math.max(0, Math.min(255, v)) | 0; };
    return 'rgba(' + f(c[0]) + ',' + f(c[1]) + ',' + f(c[2]) + ',' + (a === undefined ? 1 : a) + ')';
  }
  function clamp01(v) { return Math.max(0, Math.min(1, v)); }

  /* colour sets: dusk sky (5 stops), haze, near hills, foreground, trunk, rim light, leaves (dark/mid/light), blossom, sun */
  var PALS = {
    Sunset: { sky: ['#18234a', '#3d4c7f', '#d9826e', '#f4b878', '#f7d29a'], haze: '#e7a083', near: '#231a35', fg: '#1a1428', trunk: '#1f1527', rim: '#f7aa6e', leaf: ['#6b2b4f', '#c8466a', '#f48a70'], bloom: '#ffd0b5', sun: '#fff0cf' },
    Ocean: { sky: ['#0b1f3a', '#1d4e7a', '#4fa3b8', '#a9dccf', '#e3f4e8'], haze: '#8cc7c4', near: '#0f2a38', fg: '#0b1e2a', trunk: '#13202b', rim: '#bfeee0', leaf: ['#1d5b6e', '#2f9aa0', '#7fd6c2'], bloom: '#e8fff7', sun: '#f2fff9' },
    Forest: { sky: ['#13261f', '#2e5442', '#8fb07a', '#e2d59a', '#f6ebc0'], haze: '#b9c28e', near: '#16261c', fg: '#101c15', trunk: '#1d1a14', rim: '#f0d68a', leaf: ['#1f4d2c', '#3f8a45', '#9cc95a'], bloom: '#fff3c4', sun: '#fff6d8' },
    Ember: { sky: ['#140808', '#3e1414', '#a33a22', '#ee7a3a', '#ffcf87'], haze: '#d9774a', near: '#1e0c0c', fg: '#150808', trunk: '#1a0b0b', rim: '#ffb070', leaf: ['#5a1414', '#b8321e', '#f2773a'], bloom: '#ffe0a0', sun: '#fff0c8' },
    Orchid: { sky: ['#0c0826', '#2c1a63', '#7b3fb5', '#e25fa8', '#ffc2e0'], haze: '#c27bd0', near: '#1a0f33', fg: '#120a26', trunk: '#170d28', rim: '#ff9ad8', leaf: ['#4a1f7a', '#a43ec0', '#ff7fc8'], bloom: '#e8fffb', sun: '#fff0fa' },
    Slate: { sky: ['#0f141d', '#2a3546', '#6b7a91', '#b9c4d3', '#e8edf3'], haze: '#a9b4c4', near: '#1a2130', fg: '#121823', trunk: '#161b25', rim: '#e6ecf5', leaf: ['#3a4658', '#6f8199', '#b8c6d8'], bloom: '#ffffff', sun: '#ffffff' }
  };
  var NAMES = ['Sunset', 'Ocean', 'Forest', 'Ember', 'Orchid', 'Slate'];

  /* time of day: the colour set's own sky is dusk; other times blend it toward fixed skies */
  var KEY_H = [0, 5, 6.5, 8, 16.5, 18.3, 19.8, 24];
  var KEY_T = [0, 0, 1, 2, 2, 3, 0, 0];               // 0 night, 1 dawn, 2 day, 3 dusk
  var TINT = [
    { sky: ['#03060f', '#081430', '#14244a', '#233863', '#2f4675'], haze: '#3a4a72', near: '#0a0f1e', amt: 0.75, light: 0.55, stars: 1 },
    { sky: ['#2a3a78', '#6a5a9a', '#d48aa0', '#f6b89a', '#fbe0c0'], haze: '#e4b4b0', near: '#2a2240', amt: 0.6, light: 0.85, stars: 0.3 },
    { sky: ['#3f78c8', '#6aa0dc', '#a9cdeb', '#d6e9f4', '#eef6f8'], haze: '#c9dbe6', near: '#2b3b4a', amt: 0.65, light: 1.0, stars: 0 },
    null
  ];
  function look(p, type) {
    var P = { sky: p.sky.map(hex), haze: hex(p.haze), near: hex(p.near), light: 0.95, stars: 0.45 };
    var tt = TINT[type];
    if (!tt) return P;
    return {
      sky: P.sky.map(function (c, i) { return mix(c, hex(tt.sky[i]), tt.amt); }),
      haze: mix(P.haze, hex(tt.haze), tt.amt * 0.85), near: mix(P.near, hex(tt.near), tt.amt * 0.6),
      light: tt.light, stars: tt.stars
    };
  }
  function timeLook(p, hour) {
    var i = 0;
    while (i < KEY_H.length - 2 && hour >= KEY_H[i + 1]) i++;
    var u = clamp01((hour - KEY_H[i]) / (KEY_H[i + 1] - KEY_H[i]));
    var a = look(p, KEY_T[i]), b = look(p, KEY_T[i + 1]);
    return {
      sky: a.sky.map(function (c, j) { return mix(c, b.sky[j], u); }),
      haze: mix(a.haze, b.haze, u), near: mix(a.near, b.near, u),
      light: a.light + (b.light - a.light) * u, stars: a.stars + (b.stars - a.stars) * u,
      sun: hour >= 6 && hour < 19
    };
  }

  /* a seeded branching tree; the shape never depends on growth, so it stays the same tree all day */
  var LEVELS = 6;
  function makeTree(seed) {
    var rng = mulberry32(seed), segs = [];
    (function br(x, y, ang, len, w, d, parent) {
      var bend = (rng() - 0.5) * len * 0.35;
      var x1 = x + Math.sin(ang) * len, y1 = y - Math.cos(ang) * len;
      var me = segs.length;
      segs.push({ x0: x, y0: y, cx: (x + x1) / 2 + Math.cos(ang) * bend, cy: (y + y1) / 2 + Math.sin(ang) * bend, x1: x1, y1: y1, w: w, d: d, parent: parent });
      if (d >= LEVELS) return;
      var n = d < 2 ? 2 : (rng() < 0.3 ? 3 : 2);
      for (var i = 0; i < n; i++) {
        var side = n === 2 ? (i ? 1 : -1) : i - 1;
        br(x1, y1, ang * 0.85 + side * (0.32 + rng() * 0.3) * 0.62 + (rng() - 0.5) * 0.15, len * (0.72 + rng() * 0.1), w * 0.7, d + 1, me);
      }
    })(0, 0, (rng() - 0.5) * 0.08, 230, 44, 0, -1);
    return segs;
  }

  function ridge(rng, baseY, amp, rough, W) {
    var ph = [rng() * 10, rng() * 10, rng() * 10, rng() * 10], pts = [];
    for (var x = -40; x <= W + 40; x += 12) {
      var u = x / W;
      pts.push([x, baseY - amp * (0.55 * Math.sin(u * 3.1 + ph[0]) + 0.3 * Math.sin(u * 7.3 + ph[1]) + rough * 0.18 * Math.sin(u * 19 + ph[2]) + rough * 0.08 * Math.sin(u * 41 + ph[3]))]);
    }
    return pts;
  }

  var treeCache = { seed: null, segs: null };

  function draw(ctx, W, H, o) {
    var p = PALS[o.pal] || PALS.Sunset, k = H / 2400, sx = W / 1080, i;
    var L = timeLook(p, o.hour), t = clamp01(o.t), par = o.parX || 0;
    var lift = (o.lift || 0) * H;
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);

    /* sky, stars, sun or moon */
    var g = ctx.createLinearGradient(0, 0, 0, 1560 * k - lift);
    [0, 0.38, 0.66, 0.84, 1].forEach(function (s, j) { g.addColorStop(s, rgb(L.sky[j])); });
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    var rs = mulberry32(11);
    for (i = 0; i < 70; i++) {
      var stx = rs() * W, sty = rs() * 900 * k, sta = (0.2 + rs() * 0.5) * L.stars, str = (1.5 + rs() * 2) * k;
      if (sta > 0.01) { ctx.fillStyle = 'rgba(255,255,255,' + sta + ')'; ctx.beginPath(); ctx.arc(stx, sty, str, 0, TAU); ctx.fill(); }
    }
    var cx = 735 * sx + par * 6 * k, cy = 1330 * k - lift, sun = L.sun ? hex(p.sun) : [243, 241, 230];
    var glow = ctx.createRadialGradient(cx, cy, 60 * k, cx, cy, 700 * k);
    glow.addColorStop(0, rgb(sun, L.sun ? 0.7 : 0.35)); glow.addColorStop(0.3, rgb(mix(sun, L.haze, 0.5), L.sun ? 0.25 : 0.1)); glow.addColorStop(1, rgb(L.haze, 0));
    ctx.fillStyle = glow; ctx.fillRect(0, 0, W, H);
    ctx.fillStyle = rgb(sun); ctx.beginPath(); ctx.arc(cx, cy, (L.sun ? 105 : 70) * k, 0, TAU); ctx.fill();

    /* hills: far layers take the haze colour, near ones darken; mist between layers; near layers move more */
    var rr = mulberry32(12), lay = [[1390, 150, 1.0], [1470, 140, 0.8], [1560, 120, 0.6], [1660, 110, 0.45], [1760, 90, 0.3]];
    lay.forEach(function (l, j) {
      var pts = ridge(rr, l[0] * k - lift, l[1] * k, l[2], W), dx = par * (6 + j * 8) * k;
      ctx.fillStyle = rgb(scale(mix(L.haze, L.near, 0.18 + (j + 1) / 6 * 0.62), 0.75 + 0.25 * L.light));
      ctx.beginPath(); ctx.moveTo(-40, H);
      pts.forEach(function (q) { ctx.lineTo(q[0] + dx, q[1]); });
      ctx.lineTo(W + 40, H); ctx.closePath(); ctx.fill();
      var m = ctx.createLinearGradient(0, l[0] * k - lift - 40 * k, 0, l[0] * k - lift + 120 * k);
      m.addColorStop(0, rgb(L.haze, 0)); m.addColorStop(1, rgb(L.haze, (0.28 - j * 0.04) * (0.4 + 0.6 * L.light)));
      ctx.fillStyle = m; ctx.fillRect(0, l[0] * k - lift - 40 * k, W, 160 * k);
    });

    /* birds by day */
    if (L.stars < 0.5) {
      ctx.strokeStyle = rgb(scale(L.near, 1.2), 0.75); ctx.lineWidth = 5 * k; ctx.lineCap = 'round';
      [[300, 1080, 26], [360, 1040, 20], [255, 1120, 16]].forEach(function (b) {
        var bx = b[0] * sx + par * 10 * k, by = b[1] * k - lift, bw = b[2] * k;
        ctx.beginPath(); ctx.moveTo(bx - bw, by);
        ctx.quadraticCurveTo(bx - bw / 2, by - bw * 0.6, bx, by); ctx.quadraticCurveTo(bx + bw / 2, by - bw * 0.6, bx + bw, by); ctx.stroke();
      });
    }

    /* foreground hill the tree stands on */
    var fg = scale(hex(p.fg), 0.7 + 0.3 * L.light), hillDx = par * 30 * k;
    var hillY = function (x) { var u = (x - hillDx - 0.52 * W) / (0.54 * W); return 1805 * k - lift + 125 * k * u * u; };
    ctx.fillStyle = rgb(fg); ctx.beginPath(); ctx.moveTo(-40, H);
    for (var hx = -40; hx <= W + 40; hx += 12) ctx.lineTo(hx, hillY(hx));
    ctx.lineTo(W + 40, H); ctx.closePath(); ctx.fill();

    /* the tree */
    if (treeCache.seed !== o.seed) { treeCache.seed = o.seed; treeCache.segs = makeTree(o.seed); }
    var segs = treeCache.segs;
    var P = 0.9 + t * (LEVELS + 0.25);                     // even at 0 steps there is a young plant
    var young = 1 + 0.9 * Math.pow(1 - t, 2);                // saplings are drawn larger so they read
    var tk = k * 1.22 * young, bx0 = 545 * sx + par * 40 * k, by0 = 1815 * k - lift;
    var thin = 0.5 + 0.5 * Math.min(1, t * 1.5);
    var trunk = scale(hex(p.trunk), 0.8 + 0.2 * L.light), rim = hex(p.rim);
    var ends = [], clusters = [];
    segs.forEach(function (s, si) {
      var gg = clamp01(P - s.d);
      if (gg <= 0) { ends.push(null); return; }
      var X0 = bx0 + s.x0 * tk, Y0 = by0 + s.y0 * tk, CX = bx0 + s.cx * tk, CY = by0 + s.cy * tk, X1 = bx0 + s.x1 * tk, Y1 = by0 + s.y1 * tk;
      // grow along the curve: split the quadratic at gg
      var qx = X0 + (CX - X0) * gg, qy = Y0 + (CY - Y0) * gg;
      var ex = (1 - gg) * (1 - gg) * X0 + 2 * (1 - gg) * gg * CX + gg * gg * X1, ey = (1 - gg) * (1 - gg) * Y0 + 2 * (1 - gg) * gg * CY + gg * gg * Y1;
      var w0 = s.w * tk * thin, w1 = w0 * (1 - 0.3 * gg);
      var dx = ex - X0, dy = ey - Y0, ln = Math.hypot(dx, dy) || 1, nx = -dy / ln, ny = dx / ln, a = w0 / 2, b = w1 / 2, mm = (a + b) / 2;
      ctx.fillStyle = rgb(trunk);
      ctx.beginPath();
      ctx.moveTo(X0 + nx * a, Y0 + ny * a); ctx.quadraticCurveTo(qx + nx * mm, qy + ny * mm, ex + nx * b, ey + ny * b);
      ctx.lineTo(ex - nx * b, ey - ny * b); ctx.quadraticCurveTo(qx - nx * mm, qy - ny * mm, X0 - nx * a, Y0 - ny * a);
      ctx.closePath(); ctx.fill();
      ctx.beginPath(); ctx.arc(ex, ey, b, 0, TAU); ctx.fill();
      if (s.d < 3 && L.light > 0.6) {
        ctx.strokeStyle = rgb(rim, 0.55 * (L.light - 0.4)); ctx.lineWidth = 4 * k;
        ctx.beginPath(); ctx.moveTo(X0 + a * 0.76, Y0); ctx.quadraticCurveTo(qx + mm * 0.6, qy, ex + b * 0.76, ey); ctx.stroke();
      }
      ends.push([ex, ey]);
      // leaves sit on the outer twigs, and on whatever tip is growing right now (so a sapling has leaves)
      var frontier = s.d === LEVELS || P - (s.d + 1) < 0.35;
      if ((s.d >= Math.max(2, Math.floor(P) - 2) || frontier) && gg > 0.15) clusters.push([ex, ey, si, gg]);
    });

    var leaf = p.leaf.map(function (c) { return scale(hex(c), 0.55 + 0.45 * L.light); });
    var leafy = 0.45 + 0.55 * t;
    if (t >= 1) {                                             // goal reached: a soft glow behind the crown
      var gc = ctx.createRadialGradient(bx0, by0 - 640 * k, 40 * k, bx0, by0 - 640 * k, 600 * k);
      gc.addColorStop(0, rgb(hex(p.bloom), 0.28)); gc.addColorStop(1, rgb(hex(p.bloom), 0));
      ctx.fillStyle = gc; ctx.fillRect(0, 0, W, H);
    }
    [[0, 1.0, -0.25, 0.25], [1, 0.82, 0, 0], [2, 0.5, 0.3, -0.3]].forEach(function (lyr) {
      ctx.fillStyle = rgb(leaf[lyr[0]]);
      clusters.forEach(function (c) {
        var r3 = mulberry32(c[2] * 31 + 7), cs = c[3] * leafy;
        for (var j = 0; j < 4; j++) {
          var r = (34 + r3() * 34) * lyr[1] * cs * tk, ox = ((r3() - 0.5) * 70 + lyr[2] * 40) * cs * tk, oy = ((r3() - 0.5) * 60 + lyr[3] * 40) * cs * tk;
          var skip = lyr[0] === 2 && r3() < 0.45;
          if (!skip) { ctx.beginPath(); ctx.arc(c[0] + ox, c[1] + oy, r, 0, TAU); ctx.fill(); }
        }
      });
    });

    /* blossoms and falling petals arrive as the tree fills out */
    var bloom = hex(p.bloom), rb = mulberry32(5), nb = Math.round(clusters.length * 1.4 * clamp01((t - 0.25) / 0.75));
    ctx.fillStyle = rgb(bloom, 0.6 + 0.4 * L.light);
    for (i = 0; i < nb; i++) {
      var c = clusters[Math.floor(rb() * clusters.length)];
      ctx.beginPath(); ctx.arc(c[0] + (rb() - 0.5) * 80 * tk, c[1] + (rb() - 0.5) * 60 * tk, (5 + rb() * 4) * k, 0, TAU); ctx.fill();
    }
    var rp = mulberry32(6), np = Math.round(26 * clamp01((t - 0.4) / 0.6));
    for (i = 0; i < np; i++) {
      var px = (330 + rp() * 480) * sx + par * 40 * k, py = (1300 + rp() * 560) * k - lift, rot = rp() * 3, pa = 0.5 + rp() * 0.4;
      ctx.save(); ctx.translate(px, py); ctx.rotate(rot); ctx.fillStyle = rgb(leaf[2], pa);
      ctx.beginPath(); ctx.ellipse(0, 0, 11 * k, 5 * k, 0, 0, TAU); ctx.fill(); ctx.restore();
    }

    /* grass tufts along the ridge */
    var rg = mulberry32(7);
    ctx.fillStyle = rgb(fg);
    for (i = 0; i < 90; i++) {
      var gx = rg() * W, gh = (18 + rg() * 30) * k, lean = (rg() - 0.5) * 10 * k, gy = hillY(gx) + 4 * k;
      ctx.beginPath(); ctx.moveTo(gx - 5 * k, gy + 6 * k); ctx.lineTo(gx + lean, gy - gh); ctx.lineTo(gx + 5 * k, gy + 6 * k); ctx.fill();
    }

    if (o.label) drawLabel(ctx, W, H, k, o.label, L, p, lift);
    ctx.restore();
  }

  function drawLabel(ctx, W, H, k, l, L, p, lift) {
    var goal = Math.max(1, l.goal), s = l.steps, f = function (n) { return Number(n).toLocaleString('en-US'); };
    var text = l.style === 'steps' ? f(s) + ' steps'
      : l.style === 'percent' ? Math.min(999, Math.round(s * 100 / goal)) + '% of goal'
      : l.style === 'bar' ? f(s) + ' / ' + f(goal)
      : f(s) + ' / ' + f(goal) + ' steps';
    var z = (l.size === 's' ? 0.8 : l.size === 'l' ? 1.3 : 1) * k, bar = l.style === 'bar';
    ctx.font = '600 ' + (44 * z) + 'px "Segoe UI", Roboto, sans-serif';
    var tw = ctx.measureText(text).width, w = tw + 90 * z, h = (bar ? 116 : 92) * z, x = (W - w) / 2;
    var y = (l.pos === 'bottom' ? 2090 : 1950) * k - (l.pos === 'bottom' ? 0 : lift);
    ctx.fillStyle = rgb(scale(L.near, 0.6), 0.55);
    ctx.beginPath(); ctx.roundRect(x, y, w, h, h / 2); ctx.fill();
    ctx.fillStyle = '#ffffff'; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
    ctx.fillText(text, W / 2, y + (bar ? 60 : 61) * z);
    if (bar) {
      var frac = Math.min(1, s / goal);
      ctx.fillStyle = 'rgba(255,255,255,0.22)'; ctx.beginPath(); ctx.roundRect(x + 45 * z, y + 80 * z, w - 90 * z, 10 * z, 5 * z); ctx.fill();
      ctx.fillStyle = rgb(hex(p.leaf[2])); ctx.beginPath(); ctx.roundRect(x + 45 * z, y + 80 * z, Math.max(10 * z, (w - 90 * z) * frac), 10 * z, 5 * z); ctx.fill();
    }
  }

  root.TreeArt = { draw: draw, names: NAMES, palettes: PALS };
})(this);
