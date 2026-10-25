/*
 * Tree Steps illustrated renderer: a seeded tree as the hero in one of several designs
 * (scene x tree kind x colours). TreeArt.draw(ctx, W, H, opts) paints one still frame.
 *
 * android/.../ArtRenderer.java is a line-for-line port. Keep the two in step: same constants,
 * same random streams (mulberry32 seeded per element), same order of draws.
 *
 * opts: { seed, design (id from TreeArt.order), t (growth 0..1 = steps / goal), now (ms since epoch),
 *         sky ('time' follows the real sun, or a fixed 'dawn' | 'day' | 'sunset' | 'night'), parX (-1.5..1.5 parallax), lift (fraction of H to move the
 *         scene up, for the app's bottom card), label: null | { style, pos, size, steps, goal } }
 * The sun follows its real path for the phone's time zone (no location needed); the moon shows its phase.
 */
(function (root) {
  var TAU = Math.PI * 2, D2R = Math.PI / 180;

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
  function smooth(a, b, v) { var u = clamp01((v - a) / (b - a)); return u * u * (3 - 2 * u); }

  /* ---------- designs: scene + tree kind + colours (trees from around the world) ---------- */
  var DESIGNS = {
    oak: { name: 'Oak', scene: 'hills', tree: 'broad', leaf: ['#1f4a2a', '#3f7f3c', '#86b84e'], flower: '', flower2: '', fall: '#86b84e', trunk: '#4a3626' },
    sakura: { name: 'Sakura', scene: 'lake', tree: 'broad', leaf: ['#a8406a', '#e07a9e', '#f8bfd2'], flower: '#fff2f6', flower2: '', fall: '#f8bfd2', trunk: '#4b3436' },
    maple: { name: 'Maple', scene: 'autumn', tree: 'broad', leaf: ['#7e2618', '#cf5228', '#f39a3a'], flower: '', flower2: '', fall: '#f39a3a', trunk: '#3e2a22' },
    pine: { name: 'Pine', scene: 'snow', tree: 'pine', leaf: ['#173630', '#2a5546', '#4f826a'], flower: '', flower2: '', fall: '#ffffff', trunk: '#3e2e26' },
    jacaranda: { name: 'Jacaranda', scene: 'fields', tree: 'umbrella', leaf: ['#2c5a2a', '#4d8a3a', '#8fbf52'], flower: '#8e62d6', flower2: '#c3a2f0', fall: '#b48ae8', trunk: '#4a3524' },
    banyan: { name: 'Banyan', scene: 'hills', tree: 'banyan', leaf: ['#22462a', '#3c7038', '#78a64a'], flower: '', flower2: '', fall: '#78a64a', trunk: '#5c4838' }
  };
  var ORDER = ['banyan', 'oak', 'sakura', 'maple', 'pine', 'jacaranda'];
  /* daylight colours of each scene: far hills, near hills, ground, grass */
  var SCENES = {
    hills: { far: '#a9c3cf', near: '#4f7a5a', ground: '#5f8f45', grass: '#4a7a33' },
    lake: { far: '#a3bccf', near: '#56798c', ground: '#6b8f4a', grass: '#557a38' },
    fields: { far: '#b7c7a0', near: '#7f9c54', ground: '#d8b246', grass: '#a8872c' },
    snow: { far: '#d9e4ef', near: '#9fb3c9', ground: '#eef3f8', grass: '#c9d6e4' },
    autumn: { far: '#c7b394', near: '#9a6a3e', ground: '#b9783a', grass: '#93552a' }
  };

  /* ---------- the sun's real path from the phone's time zone ---------- */
  var TZ = {
    'Asia/Kolkata': [22.5, 79], 'Asia/Calcutta': [22.5, 79], 'Asia/Karachi': [28, 68], 'Asia/Dhaka': [23.7, 90.4],
    'Asia/Kathmandu': [27.7, 85.3], 'Asia/Colombo': [7, 80], 'Asia/Dubai': [25.2, 55.3], 'Asia/Riyadh': [24.7, 46.7],
    'Asia/Singapore': [1.35, 103.8], 'Asia/Jakarta': [-6.2, 106.8], 'Asia/Bangkok': [13.75, 100.5], 'Asia/Shanghai': [31, 114],
    'Asia/Tokyo': [35.7, 139.7], 'Europe/London': [51.5, -0.1], 'Europe/Paris': [48.9, 2.35], 'Europe/Berlin': [52.5, 13.4],
    'Europe/Moscow': [55.75, 37.6], 'Africa/Cairo': [30, 31.2], 'Africa/Lagos': [6.5, 3.4], 'Africa/Nairobi': [-1.3, 36.8],
    'America/New_York': [40.7, -74], 'America/Chicago': [41.9, -87.6], 'America/Denver': [39.7, -105], 'America/Los_Angeles': [34, -118.2],
    'America/Sao_Paulo': [-23.5, -46.6], 'Australia/Sydney': [-33.9, 151.2]
  };
  function place(tzName, offsetMin) {
    if (TZ[tzName]) return TZ[tzName];
    return [20, -offsetMin / 4];                                // unknown zone: longitude from the UTC offset
  }
  /* solar elevation and azimuth (degrees, azimuth from north), NOAA's low-precision formulas */
  function sunPos(ms, lat, lon) {
    var n = ms / 86400000 + 2440587.5 - 2451545.0;
    var L = (280.460 + 0.9856474 * n) % 360, g = ((357.528 + 0.9856003 * n) % 360) * D2R;
    var lam = (L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) * D2R, eps = (23.439 - 0.0000004 * n) * D2R;
    var ra = Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam)), dec = Math.asin(Math.sin(eps) * Math.sin(lam));
    var gmst = (18.697374558 + 24.06570982441908 * n) % 24, ha = ((gmst + lon / 15) * 15) * D2R - ra;
    var la = lat * D2R;
    var el = Math.asin(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha));
    var az = Math.atan2(-Math.sin(ha), Math.tan(dec) * Math.cos(la) - Math.sin(la) * Math.cos(ha));
    return { el: el / D2R, az: (az / D2R + 360) % 360 };
  }
  /* 0 = new moon, 0.5 = full moon (from the new moon of 6 Jan 2000, 18:14 UTC) */
  function moonPhase(ms) { var p = ((ms - 947182440000) / 86400000 / 29.530588853) % 1; return p < 0 ? p + 1 : p; }

  /* sky colours for daylight, golden hour (evening / morning) and night */
  var SKY_DAY = ['#3d7fd1', '#5d9be0', '#8fbde9', '#bfdaf0', '#e2eff5'];
  var SKY_EVE = ['#2b3a6e', '#5a5a92', '#d98a7a', '#f5b880', '#fbd9a0'];
  var SKY_MORN = ['#2d4078', '#6b6aa6', '#d99aac', '#f6c4a4', '#fde3c4'];
  var SKY_NIGHT = ['#02040c', '#060d24', '#0e1a3a', '#182a52', '#22375f'];
  var WARM = hex('#ff9a5c'), NIGHTC = hex('#101c3c');
  /* fixed looks for when the user picks a style instead of following the sun */
  var SKY_STYLES = { dawn: { el: 5, az: 105 }, day: { el: 48, az: 160 }, sunset: { el: 4, az: 255 }, night: { el: -25, az: 300 } };

  /* ---------- tree skeletons; the shape never depends on growth, so it stays the same tree all day ---------- */
  function makeTree(seed, kind) {
    var rng = mulberry32(seed), segs = [];
    var spread = kind === 'umbrella' ? 0.9 : kind === 'banyan' ? 1.15 : 0.62;
    var up = kind === 'umbrella' ? 0.78 : kind === 'banyan' ? 0.55 : 0.9;    // how much branches keep pointing up
    var levels = kind === 'banyan' ? 5 : 6, grow = kind === 'banyan' ? 0.8 : 0.72;      // banyan limbs stay long: a wide, low crown
    (function br(x, y, ang, len, w, d) {
      var bend = (rng() - 0.5) * len * 0.35;
      var x1 = x + Math.sin(ang) * len, y1 = y - Math.cos(ang) * len;
      segs.push({ x0: x, y0: y, cx: (x + x1) / 2 + Math.cos(ang) * bend, cy: (y + y1) / 2 + Math.sin(ang) * bend, x1: x1, y1: y1, w: w, d: d, r: rng() });
      if (d >= levels) return;
      var n = d < 2 ? 2 : (rng() < 0.3 ? 3 : 2);
      for (var i = 0; i < n; i++) {
        var side = n === 2 ? (i ? 1 : -1) : i - 1;
        br(x1, y1, ang * up + side * (0.32 + rng() * 0.3) * spread + (rng() - 0.5) * 0.15, len * (grow + rng() * 0.1), w * 0.7, d + 1);
      }
    })(0, 0, (rng() - 0.5) * 0.08, kind === 'banyan' ? 165 : 230, kind === 'banyan' ? 80 : 44, 0);
    return { segs: segs, levels: levels };
  }

  function ridge(rng, baseY, amp, rough, W) {
    var ph = [rng() * 10, rng() * 10, rng() * 10, rng() * 10], pts = [];
    for (var x = -40; x <= W + 40; x += 12) {
      var u = x / W;
      pts.push([x, baseY - amp * (0.55 * Math.sin(u * 3.1 + ph[0]) + 0.3 * Math.sin(u * 7.3 + ph[1]) + rough * 0.18 * Math.sin(u * 19 + ph[2]) + rough * 0.08 * Math.sin(u * 41 + ph[3]))]);
    }
    return pts;
  }
  function fillPts(ctx, pts, H, dx) {
    ctx.beginPath(); ctx.moveTo(-40, H);
    pts.forEach(function (q) { ctx.lineTo(q[0] + dx, q[1]); });
    ctx.lineTo(pts[pts.length - 1][0] + dx, H); ctx.closePath(); ctx.fill();
  }
  /* an almond-shaped leaf centred on (x, y), pointing along ang; wide is its width as a fraction of its length */
  function leafShape(ctx, x, y, len, ang, wide) {
    var c = Math.cos(ang), s = Math.sin(ang), w = len * (wide || 0.42);
    ctx.beginPath();
    ctx.moveTo(x - c * len, y - s * len);
    ctx.quadraticCurveTo(x - s * w, y + c * w, x + c * len, y + s * len);
    ctx.quadraticCurveTo(x + s * w, y - c * w, x - c * len, y - s * len);
    ctx.fill();
  }

  var cache = { key: null, tree: null };

  function draw(ctx, W, H, o) {
    var dz = DESIGNS[o.design] || DESIGNS.banyan, sc = SCENES[dz.scene];
    var k = H / 2400, sx = W / 1080, t = clamp01(o.t), par = o.parX || 0, lift = (o.lift || 0) * H, i, j;
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);

    /* ---- where the sun is, and what light that gives ---- */
    var now = o.now || Date.now(), sun, pl;
    var fixed = SKY_STYLES[o.sky || (o.sunset ? 'sunset' : '')];
    if (fixed) { sun = fixed; pl = [20, 0]; }
    else {
      var tzName = '';
      try { tzName = Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) {}
      pl = o.place || place(tzName, new Date(now).getTimezoneOffset());
      sun = sunPos(now, pl[0], pl[1]);
    }
    var wN = smooth(-2, -10, sun.el), wD = smooth(6, 22, sun.el), wG = Math.max(0, 1 - wN - wD), eve = sun.az > 180;
    var gShare = wG / Math.max(1e-6, wG + wD);
    var sky = [0, 1, 2, 3, 4].map(function (s) {
      return mix(mix(hex(SKY_DAY[s]), hex((eve ? SKY_EVE : SKY_MORN)[s]), gShare), hex(SKY_NIGHT[s]), wN);
    });
    var haze = mix(mix(hex('#cfe0ea'), hex('#f0b896'), gShare), hex('#2a3858'), wN);
    var light = 1 - 0.18 * wG - 0.58 * wN;
    var lit = function (c) { return mix(mix(scale(c, light), WARM, 0.16 * wG), NIGHTC, 0.35 * wN); };

    /* sky and stars */
    var horizon = 1390 * k - lift;
    var g = ctx.createLinearGradient(0, 0, 0, 1560 * k - lift);
    [0, 0.38, 0.66, 0.84, 1].forEach(function (s, q) { g.addColorStop(s, rgb(sky[q])); });
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    var stars = wN + (eve ? 0.25 * wG : 0), rs = mulberry32(11);
    for (i = 0; i < 90; i++) {
      var stx = rs() * W, sty = rs() * 1000 * k, sta = (0.2 + rs() * 0.6) * stars, str = (1.4 + rs() * 2.2) * k;
      if (sta > 0.01) { ctx.fillStyle = 'rgba(255,255,255,' + sta + ')'; ctx.beginPath(); ctx.arc(stx, sty, str, 0, TAU); ctx.fill(); }
    }

    /* sun along its arc (east on the left when you face the equator), or the moon with its phase */
    var face = pl[0] >= 0 ? 1 : -1;
    var sunX = W * (0.5 - 0.42 * Math.sin(sun.az * D2R) * face) + par * 6 * k;
    var sunY = Math.max(820 * k - lift, horizon + 40 * k - (sun.el / 65) * (horizon + 40 * k - 820 * k));
    var sunC = mix(hex('#fff6dc'), hex('#ffd29a'), wG);
    if (sun.el > -4) {
      var gl = ctx.createRadialGradient(sunX, sunY, 50 * k, sunX, sunY, (500 + 300 * wG) * k);
      gl.addColorStop(0, rgb(sunC, 0.55 + 0.25 * wG)); gl.addColorStop(0.35, rgb(mix(sunC, haze, 0.5), 0.18 + 0.15 * wG)); gl.addColorStop(1, rgb(haze, 0));
      ctx.fillStyle = gl; ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = rgb(sunC); ctx.beginPath(); ctx.arc(sunX, sunY, 88 * k, 0, TAU); ctx.fill();
    }
    var moonA = smooth(-1, -8, sun.el);
    if (moonA > 0.02) {
      var mp = moonPhase(now), mx = 0.82 * W + par * 6 * k, my = 720 * k - lift, mr = 58 * k;
      var mg = ctx.createRadialGradient(mx, my, mr, mx, my, mr * 5);
      mg.addColorStop(0, 'rgba(220,230,255,' + 0.22 * moonA + ')'); mg.addColorStop(1, 'rgba(220,230,255,0)');
      ctx.fillStyle = mg; ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = 'rgba(40,50,80,' + 0.6 * moonA + ')'; ctx.beginPath(); ctx.arc(mx, my, mr, 0, TAU); ctx.fill();
      // lit part: the half-disc on the lit side plus a half-ellipse whose width follows the phase
      var cw = Math.cos(mp * TAU) * mr, waxing = mp < 0.5;
      ctx.fillStyle = 'rgba(244,240,226,' + moonA + ')';
      ctx.beginPath();
      ctx.arc(mx, my, mr, -Math.PI / 2, Math.PI / 2, !waxing);
      ctx.ellipse(mx, my, Math.abs(cw), mr, 0, Math.PI / 2, -Math.PI / 2, waxing ? cw > 0 : cw < 0);
      ctx.fill();
    }

    /* ---- scenery ---- */
    var far = hex(sc.far), near = hex(sc.near), rr = mulberry32(12);
    var hazeMix = function (c, f) { return mix(lit(c), haze, f); };
    if (dz.scene === 'snow') {
      // snowy peaks: mountain body, then a white cap over each summit
      [[1330, 330, far, 0.45], [1470, 260, mix(far, near, 0.5), 0.3], [1600, 200, near, 0.15]].forEach(function (m, q) {
        var pk = [], x = -80, dx = par * (6 + q * 10) * k;
        while (x < W + 160) { pk.push([x, m[0] * k - lift - (0.35 + rr() * 0.65) * m[1] * k]); x += (150 + rr() * 140) * sx; }
        ctx.fillStyle = rgb(hazeMix(m[2], m[3]));
        ctx.beginPath(); ctx.moveTo(-80, H);
        for (j = 0; j < pk.length; j++) { ctx.lineTo(pk[j][0] + dx, pk[j][1]); if (j < pk.length - 1) ctx.lineTo((pk[j][0] + pk[j + 1][0]) / 2 + dx, m[0] * k - lift + 40 * k); }
        ctx.lineTo(pk[pk.length - 1][0] + dx, H); ctx.closePath(); ctx.fill();
        ctx.fillStyle = rgb(hazeMix(hex('#ffffff'), m[3] * 0.6));
        for (j = 0; j < pk.length; j++) {
          var cap = (60 + rr() * 50) * k, px0 = pk[j][0] + dx, py0 = pk[j][1];
          ctx.beginPath(); ctx.moveTo(px0, py0);
          ctx.lineTo(px0 + cap * 0.9, py0 + cap); ctx.lineTo(px0 + cap * 0.3, py0 + cap * 0.8);
          ctx.lineTo(px0, py0 + cap * 1.1); ctx.lineTo(px0 - cap * 0.4, py0 + cap * 0.75); ctx.lineTo(px0 - cap * 0.9, py0 + cap);
          ctx.closePath(); ctx.fill();
        }
      });
    } else {
      var lay = dz.scene === 'lake' ? [[1390, 150, 1.0], [1470, 120, 0.8]] : [[1390, 150, 1.0], [1470, 140, 0.8], [1560, 120, 0.6], [1660, 110, 0.45]];
      lay.forEach(function (l, q) {
        var f = q / Math.max(1, lay.length - 1);
        ctx.fillStyle = rgb(hazeMix(mix(far, near, f), 0.55 * (1 - f) + 0.08));
        fillPts(ctx, ridge(rr, l[0] * k - lift, l[1] * k, l[2], W), H, par * (6 + q * 8) * k);
        var mist = ctx.createLinearGradient(0, l[0] * k - lift - 40 * k, 0, l[0] * k - lift + 120 * k);
        mist.addColorStop(0, rgb(haze, 0)); mist.addColorStop(1, rgb(haze, 0.22 - q * 0.04));
        ctx.fillStyle = mist; ctx.fillRect(0, l[0] * k - lift - 40 * k, W, 160 * k);
      });
      if (dz.scene === 'lake') {
        // water reflecting the sky, with ripples and the sun's glitter path
        var wy0 = 1500 * k - lift, wy1 = 1900 * k - lift, rw = mulberry32(13);
        var wg = ctx.createLinearGradient(0, wy0, 0, wy1);
        wg.addColorStop(0, rgb(mix(sky[3], haze, 0.3))); wg.addColorStop(1, rgb(mix(sky[1], NIGHTC, 0.25)));
        ctx.fillStyle = wg; ctx.fillRect(0, wy0, W, H - wy0);
        for (i = 0; i < 40; i++) {
          var ry = wy0 + Math.pow(rw(), 1.3) * (wy1 - wy0), rx = rw() * W, rl = (40 + rw() * 120) * k, ra = (0.08 + 0.12 * rw()) * (0.4 + 0.6 * light);
          ctx.fillStyle = 'rgba(255,255,255,' + ra + ')'; ctx.fillRect(rx, ry, rl, 3 * k);
        }
        if (sun.el > -2) {
          ctx.fillStyle = rgb(sunC, 0.35);
          for (i = 0; i < 14; i++) { var gw = (90 - i * 3) * k * (0.6 + rw() * 0.8), gox = (rw() - 0.5) * 20 * k; ctx.fillRect(sunX - gw / 2 + gox, wy0 + 10 * k + i * 26 * k, gw, 5 * k); }
        }
      }
      if (dz.scene === 'fields') {
        // two field bands with rows of crops curving across them
        [1600, 1700].forEach(function (y0, q) {
          var base = q ? hex(sc.ground) : mix(hex(sc.ground), near, 0.35), xx, rI;
          var fyAt = function (x) { return y0 * k - lift + Math.sin(x / W * 3 + q * 2) * 30 * k; };
          ctx.fillStyle = rgb(lit(base));
          ctx.beginPath(); ctx.moveTo(-40, H);
          for (xx = -40; xx <= W + 40; xx += 20) ctx.lineTo(xx, fyAt(xx));
          ctx.lineTo(W + 40, H); ctx.closePath(); ctx.fill();
          ctx.strokeStyle = rgb(lit(scale(base, 0.82)), 0.6); ctx.lineWidth = 3 * k;
          for (rI = 1; rI < 7; rI++) {
            ctx.beginPath();
            for (xx = -40; xx <= W + 40; xx += 20) { var yy = fyAt(xx) + rI * (16 + rI * 4) * k; if (xx === -40) ctx.moveTo(xx, yy); else ctx.lineTo(xx, yy); }
            ctx.stroke();
          }
        });
      }
    }

    /* foreground hill the tree stands on */
    var ground = hex(sc.ground), hillDx = par * 30 * k;
    var hillY = function (x) { var u = (x - hillDx - 0.52 * W) / (0.54 * W); return 1805 * k - lift + 125 * k * u * u; };
    var hg = ctx.createLinearGradient(0, 1780 * k - lift, 0, H);
    hg.addColorStop(0, rgb(lit(mix(ground, hex('#ffffff'), 0.12)))); hg.addColorStop(1, rgb(lit(scale(ground, 0.62))));
    ctx.fillStyle = hg; ctx.beginPath(); ctx.moveTo(-40, H);
    for (var hx = -40; hx <= W + 40; hx += 12) ctx.lineTo(hx, hillY(hx));
    ctx.lineTo(W + 40, H); ctx.closePath(); ctx.fill();

    /* ---- the tree ---- */
    var key = o.seed + '|' + dz.tree;
    if (cache.key !== key) { cache.key = key; cache.tree = makeTree(o.seed, dz.tree); }
    var young = 1 + 0.9 * Math.pow(1 - t, 2);
    var tk = k * 1.22 * young, bx0 = 545 * sx + par * 40 * k, by0 = 1815 * k - lift;
    var trunk = lit(hex(dz.trunk)), bark = scale(trunk, 0.7), sunSide = sunX > bx0 ? 1 : -1;
    var rim = mix(lit(mix(hex(dz.trunk), hex('#ffe2b8'), 0.55)), WARM, 0.3 * wG);
    var leaf = dz.leaf.map(function (c) { return lit(hex(c)); });
    if (dz.tree === 'pine') drawPine(ctx, tk, bx0, by0, t, trunk, leaf, dz.scene === 'snow', sunSide, o.seed, light);
    else {
      var tree = cache.tree, segs = tree.segs, L = tree.levels, clusters = [], geo = [];
      var P = 0.9 + t * (L + 0.25), thin = 0.5 + 0.5 * Math.min(1, t * 1.5), banyan = dz.tree === 'banyan';
      segs.forEach(function (s, si) {
        var gg = clamp01(P - s.d);
        if (gg <= 0) return;
        var X0 = bx0 + s.x0 * tk, Y0 = by0 + s.y0 * tk, CX = bx0 + s.cx * tk, CY = by0 + s.cy * tk, X1 = bx0 + s.x1 * tk, Y1 = by0 + s.y1 * tk;
        // grow along the curve: split the quadratic at gg
        var qx = X0 + (CX - X0) * gg, qy = Y0 + (CY - Y0) * gg;
        var ex = (1 - gg) * (1 - gg) * X0 + 2 * (1 - gg) * gg * CX + gg * gg * X1, ey = (1 - gg) * (1 - gg) * Y0 + 2 * (1 - gg) * gg * CY + gg * gg * Y1;
        var w0 = s.w * tk * thin, w1 = w0 * (1 - 0.3 * gg);
        var dx = ex - X0, dy = ey - Y0, ln = Math.hypot(dx, dy) || 1;
        geo.push({ s: s, si: si, gg: gg, X0: X0, Y0: Y0, qx: qx, qy: qy, ex: ex, ey: ey, a: w0 / 2, b: w1 / 2, nx: -dy / ln, ny: dx / ln });
        // leaves sit on the outer twigs, and on whatever tip is growing right now (so a young plant has leaves)
        var frontier = s.d === L || P - (s.d + 1) < 0.35;
        if ((s.d >= Math.max(2, Math.floor(P) - 2) || frontier) && gg > 0.15) clusters.push([ex, ey, si, gg]);
      });
      // soft shadow on the ground under the crown
      var shR = (120 + 260 * t) * tk;
      ctx.save(); ctx.translate(bx0, by0 + 8 * k); ctx.scale(1, 0.16);
      var shg = ctx.createRadialGradient(0, 0, 0, 0, 0, shR);
      shg.addColorStop(0, rgb([10, 16, 10], 0.35 * light)); shg.addColorStop(1, rgb([10, 16, 10], 0));
      ctx.fillStyle = shg; ctx.beginPath(); ctx.arc(0, 0, shR, 0, TAU); ctx.fill(); ctx.restore();
      // far side of the crown goes behind the branches, so some branches cross in front of leaves
      drawCanopy(ctx, tk, clusters, leaf, t, sunSide, dz, haze, true);
      geo.forEach(function (gm) { drawLimb(ctx, k, gm, trunk, bark, rim, sunSide, banyan); });
      if (banyan) drawRoots(ctx, k, tk, geo, t, trunk, bark, hillY);
      drawCanopy(ctx, tk, clusters, leaf, t, sunSide, dz, haze, false);
      drawBlooms(ctx, k, tk, clusters, leaf, t, dz, light);
    }

    /* falling petals / leaves, or snow */
    var fall = lit(hex(dz.fall)), rp = mulberry32(6), snowing = dz.tree === 'pine';
    var np = snowing ? 70 : Math.round(26 * clamp01((t - 0.4) / 0.6));
    for (i = 0; i < np; i++) {
      var px = snowing ? rp() * W : (330 + rp() * 480) * sx + par * 40 * k;
      var py = (snowing ? 300 + rp() * 1700 : 1300 + rp() * 560) * k - lift, rot = rp() * 3, pa = 0.5 + rp() * 0.4;
      ctx.fillStyle = rgb(fall, pa);
      if (snowing) { ctx.beginPath(); ctx.arc(px, py, (2 + rot) * k, 0, TAU); ctx.fill(); }
      else leafShape(ctx, px, py, 11 * k, rot);
    }

    /* grass tufts along the ridge */
    var rg = mulberry32(7), grass = lit(hex(sc.grass));
    for (i = 0; i < 110; i++) {
      var gx = rg() * W, gh = (16 + rg() * 30) * k, lean = (rg() - 0.5) * 12 * k, gy = hillY(gx) + 4 * k, shade = 0.8 + rg() * 0.4;
      ctx.fillStyle = rgb(scale(grass, shade));
      ctx.beginPath(); ctx.moveTo(gx - 5 * k, gy + 6 * k); ctx.lineTo(gx + lean, gy - gh); ctx.lineTo(gx + 5 * k, gy + 6 * k); ctx.fill();
    }

    if (o.label) drawLabel(ctx, W, k, o.label, leaf, lift);
    ctx.restore();
  }

  /* a tapered, bent limb; off shifts it sideways and twist pushes its bend (both in half-widths), wf scales its width */
  function taper(ctx, gm, off, twist, wf) {
    var nx = gm.nx, ny = gm.ny, a = gm.a * wf, b = gm.b * wf, mm = (a + b) / 2;
    var ox = nx * gm.a * off, oy = ny * gm.a * off, tx = nx * gm.a * twist, ty = ny * gm.a * twist;
    ctx.beginPath();
    ctx.moveTo(gm.X0 + ox + nx * a, gm.Y0 + oy + ny * a);
    ctx.quadraticCurveTo(gm.qx + ox + tx + nx * mm, gm.qy + oy + ty + ny * mm, gm.ex + ox * 0.5 + nx * b, gm.ey + oy * 0.5 + ny * b);
    ctx.lineTo(gm.ex + ox * 0.5 - nx * b, gm.ey + oy * 0.5 - ny * b);
    ctx.quadraticCurveTo(gm.qx + ox + tx - nx * mm, gm.qy + oy + ty - ny * mm, gm.X0 + ox - nx * a, gm.Y0 + oy - ny * a);
    ctx.closePath(); ctx.fill();
  }

  /* trunk and branches: banyan trunks are three fused, twisting strands; thick limbs get a lit edge and bark grooves */
  function drawLimb(ctx, k, gm, trunk, bark, rim, sunSide, banyan) {
    var s = gm.s, side = gm.nx * sunSide >= 0 ? 1 : -1, i, q;
    if (banyan && s.d <= 1) {
      ctx.fillStyle = rgb(scale(trunk, side > 0 ? 0.85 : 1.08)); taper(ctx, gm, -0.45, 0.5, 0.55);
      ctx.fillStyle = rgb(scale(trunk, side > 0 ? 1.08 : 0.85)); taper(ctx, gm, 0.45, -0.4, 0.55);
      ctx.fillStyle = rgb(trunk); taper(ctx, gm, 0, 0.3, 0.6);
    } else {
      ctx.fillStyle = rgb(trunk); taper(ctx, gm, 0, 0, 1);
    }
    ctx.fillStyle = rgb(trunk); ctx.beginPath(); ctx.arc(gm.ex, gm.ey, gm.b, 0, TAU); ctx.fill();
    if (s.d >= (banyan ? 3 : 2)) return;
    var mm = (gm.a + gm.b) / 2;
    ctx.strokeStyle = rgb(rim, 0.7); ctx.lineWidth = Math.max(2 * k, gm.a * 0.3); ctx.lineCap = 'round';
    ctx.beginPath(); ctx.moveTo(gm.X0 + gm.nx * gm.a * 0.72 * side, gm.Y0 + gm.ny * gm.a * 0.72 * side);
    ctx.quadraticCurveTo(gm.qx + gm.nx * mm * 0.72 * side, gm.qy + gm.ny * mm * 0.72 * side, gm.ex + gm.nx * gm.b * 0.72 * side, gm.ey + gm.ny * gm.b * 0.72 * side);
    ctx.stroke();
    var rb = mulberry32(gm.si * 97 + 3), n = banyan ? 14 : 8;
    for (i = 0; i < n; i++) {
      var off = (rb() - 0.5) * 1.5, u0 = rb() * 0.75, u1 = Math.min(1, u0 + 0.12 + rb() * 0.3), dark = rb() < 0.65;
      ctx.strokeStyle = dark ? rgb(bark, 0.65) : rgb(rim, 0.28);
      ctx.lineWidth = Math.max(1.2 * k, gm.a * (dark ? 0.07 : 0.05));
      ctx.beginPath();
      for (q = 0; q <= 4; q++) {
        var u = u0 + (u1 - u0) * q / 4, A = (1 - u) * (1 - u), B = 2 * (1 - u) * u, C = u * u, hw = gm.a + (gm.b - gm.a) * u;
        var px = A * gm.X0 + B * gm.qx + C * gm.ex + gm.nx * hw * off, py = A * gm.Y0 + B * gm.qy + C * gm.ey + gm.ny * hw * off;
        if (q === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
      }
      ctx.stroke();
    }
  }

  /* banyan aerial roots: thin ones hang from the branches, a few thicken into pillar roots as the tree matures */
  function drawRoots(ctx, k, tk, geo, t, trunk, bark, hillY) {
    ctx.lineCap = 'round';
    geo.forEach(function (gm) {
      var s = gm.s;
      if (gm.gg < 1) return;
      var u = 0.45 + s.r * 0.45, A = (1 - u) * (1 - u), B = 2 * (1 - u) * u, C = u * u;
      var x0 = A * gm.X0 + B * gm.qx + C * gm.ex, y0 = A * gm.Y0 + B * gm.qy + C * gm.ey, y1 = hillY(x0) + 6 * k;
      if (s.d === 1 && s.r < (t - 0.5) * 1.6) {
        var w = (10 + s.r * 10) * tk;
        ctx.fillStyle = rgb(scale(trunk, 0.95));
        ctx.beginPath(); ctx.moveTo(x0 - w * 0.3, y0); ctx.lineTo(x0 + w * 0.3, y0); ctx.lineTo(x0 + w * 0.55, y1); ctx.lineTo(x0 - w * 0.55, y1); ctx.closePath(); ctx.fill();
        ctx.strokeStyle = rgb(bark, 0.6); ctx.lineWidth = Math.max(1, w * 0.08);
        ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x0 + w * 0.1, y1); ctx.stroke();
      } else if (s.d >= 2 && s.d <= 4 && s.r < t * 0.95) {
        var ye = y0 + (y1 - y0) * (0.55 + 0.45 * clamp01(t * 1.2 - s.r));     // young roots don't reach the ground yet
        ctx.strokeStyle = rgb(scale(trunk, 1.12), 0.85); ctx.lineWidth = (2 + s.r * 3) * k;
        ctx.beginPath(); ctx.moveTo(x0, y0); ctx.quadraticCurveTo(x0 + (s.r - 0.5) * 24 * k, (y0 + ye) / 2, x0 + (s.r - 0.5) * 10 * k, ye); ctx.stroke();
      }
    });
  }

  /* crown. back = the far side, drawn before the branches: a darker, slightly higher mass per cluster.
     front = dark inner mass, mid leaves around it, light leaves on the sun's side. */
  function drawCanopy(ctx, tk, clusters, leaf, t, sunSide, dz, haze, back) {
    var leafy = 0.45 + 0.55 * t, wide = dz.tree === 'banyan' ? 0.58 : 0.42, j;
    if (back) {
      ctx.fillStyle = rgb(mix(scale(leaf[0], 0.8), haze, 0.12));
      clusters.forEach(function (c) {
        var r3 = mulberry32(c[2] * 31 + 5), s = c[3] * leafy;
        for (j = 0; j < 2; j++) {
          var cx = c[0] + (r3() - 0.5) * 70 * s * tk, cy = c[1] - (14 + r3() * 20) * s * tk, cr = (38 + r3() * 26) * s * tk;
          ctx.beginPath(); ctx.arc(cx, cy, cr, 0, TAU); ctx.fill();
        }
      });
      return;
    }
    ctx.fillStyle = rgb(leaf[0]);
    clusters.forEach(function (c) {
      var r3 = mulberry32(c[2] * 31 + 7), s = c[3] * leafy;
      for (j = 0; j < 3; j++) {
        var cx = c[0] + (r3() - 0.5) * 60 * s * tk, cy = c[1] + (r3() - 0.3) * 40 * s * tk, cr = (32 + r3() * 24) * s * tk;
        ctx.beginPath(); ctx.arc(cx, cy, cr, 0, TAU); ctx.fill();
      }
    });
    ctx.fillStyle = rgb(leaf[1]);
    clusters.forEach(function (c) {
      var r3 = mulberry32(c[2] * 31 + 11), s = c[3] * leafy, R = 50 * s * tk;
      ctx.beginPath(); ctx.arc(c[0] + sunSide * 6 * s * tk, c[1] - 10 * s * tk, R * 0.62, 0, TAU); ctx.fill();
      for (j = 0; j < 13; j++) {
        var a = r3() * TAU, dd = (0.45 + r3() * 0.6) * R, ll = (12 + r3() * 8) * s * tk, la = a + (r3() - 0.5) * 0.7;
        leafShape(ctx, c[0] + Math.cos(a) * dd, c[1] + Math.sin(a) * dd * 0.85 - 8 * s * tk, ll, la, wide);
      }
    });
    ctx.fillStyle = rgb(leaf[2]);
    clusters.forEach(function (c) {
      var r3 = mulberry32(c[2] * 31 + 13), s = c[3] * leafy, R = 46 * s * tk;
      for (j = 0; j < 7; j++) {
        var a = -Math.PI / 2 + sunSide * (0.2 + r3() * 1.0) + (r3() - 0.5) * 0.5, dd = (0.25 + r3() * 0.65) * R, ll = (10 + r3() * 6) * s * tk, la = a + (r3() - 0.5) * 0.8;
        leafShape(ctx, c[0] + Math.cos(a) * dd, c[1] + Math.sin(a) * dd - 10 * s * tk, ll, la, wide);
      }
    });
  }

  /* flowers (blossoms or jacaranda bunches), or figs ripening on a mature banyan */
  function drawBlooms(ctx, k, tk, clusters, leaf, t, dz, light) {
    if (!clusters.length) return;
    var rb = mulberry32(5), i, q, c, fx, fy, fr;
    if (dz.tree === 'banyan') {
      var nf = Math.round(clusters.length * 0.45 * clamp01((t - 0.6) / 0.4)), fig = hex('#a8402c'), shine = hex('#f08a6a');
      for (i = 0; i < nf; i++) {
        c = clusters[Math.floor(rb() * clusters.length)]; fx = c[0] + (rb() - 0.5) * 70 * tk; fy = c[1] + (rb() - 0.4) * 50 * tk; fr = (3 + rb() * 2) * k;
        ctx.fillStyle = rgb(scale(fig, 0.6 + 0.4 * light)); ctx.beginPath(); ctx.arc(fx, fy, fr, 0, TAU); ctx.fill();
        ctx.fillStyle = rgb(shine, 0.7 * light); ctx.beginPath(); ctx.arc(fx - fr * 0.3, fy - fr * 0.3, fr * 0.35, 0, TAU); ctx.fill();
      }
      return;
    }
    if (!dz.flower) return;
    var fl = hex(dz.flower), bunches = dz.tree === 'umbrella';
    var nb = Math.round(clusters.length * (bunches ? 2.2 : 1.3) * clamp01((t - 0.25) / 0.75));
    for (i = 0; i < nb; i++) {
      c = clusters[Math.floor(rb() * clusters.length)]; fx = c[0] + (rb() - 0.5) * 80 * tk; fy = c[1] + (rb() - 0.65) * 60 * tk; fr = (4 + rb() * 3) * k;
      if (bunches) {
        // jacaranda: little bunches of flowers on top of the crown, in two shades
        ctx.fillStyle = rgb(mix(fl, hex(dz.flower2), rb()), 0.6 + 0.4 * light);
        for (q = 0; q < 4; q++) { var qa = q / 4 * TAU + rb(); ctx.beginPath(); ctx.ellipse(fx + Math.cos(qa) * fr, fy + Math.sin(qa) * fr, fr * 1.3, fr * 0.7, qa, 0, TAU); ctx.fill(); }
      } else {
        // five-petal blossom with a darker heart
        ctx.fillStyle = rgb(fl, 0.65 + 0.35 * light);
        for (q = 0; q < 5; q++) { var pa2 = q / 5 * TAU; ctx.beginPath(); ctx.arc(fx + Math.cos(pa2) * fr * 0.9, fy + Math.sin(pa2) * fr * 0.9, fr * 0.65, 0, TAU); ctx.fill(); }
        ctx.fillStyle = rgb(leaf[0], 0.8); ctx.beginPath(); ctx.arc(fx, fy, fr * 0.35, 0, TAU); ctx.fill();
      }
    }
  }

  /* pine: a straight trunk with tiers of jagged boughs; snow settles on the tiers in the snow scene */
  function drawPine(ctx, tk, bx0, by0, t, trunk, leaf, snowy, sunSide, seed, light) {
    var tiers = 4 + Math.round(5 * t), grow = 0.35 + 0.65 * t, H0 = 980 * tk * grow, rz = mulberry32(seed * 7 + 3);
    var w0 = 34 * tk, i, q, p;
    ctx.fillStyle = rgb(trunk);
    ctx.beginPath(); ctx.moveTo(bx0 - w0 / 2, by0); ctx.lineTo(bx0 - w0 * 0.15, by0 - H0); ctx.lineTo(bx0 + w0 * 0.15, by0 - H0); ctx.lineTo(bx0 + w0 / 2, by0); ctx.fill();
    for (i = 0; i < tiers; i++) {
      var u = i / (tiers - 1), y = by0 - H0 * (0.18 + 0.8 * u), hw = (300 - 250 * u) * tk * grow, th = (190 - 80 * u) * tk * (0.5 + 0.5 * grow);
      var pts = [[bx0, y - th]];
      for (q = 0; q <= 7; q++) pts.push([bx0 - hw + q / 7 * hw * 2, y + (q % 2 ? -th * 0.18 : 0) + (rz() - 0.5) * 10 * tk]);
      // dark whole tier, then the lit half and a narrower highlight on the sun's side
      for (var lc = 0; lc < 3; lc++) {
        ctx.fillStyle = rgb(leaf[lc]);
        ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]);
        if (lc === 0) { for (p = 1; p < pts.length; p++) ctx.lineTo(pts[p][0], pts[p][1]); }
        else {
          var narrow = lc === 2 ? 0.45 : 1, drop = lc === 2 ? 0.7 : 1;
          for (p = 1; p < pts.length; p++) if ((pts[p][0] - bx0) * sunSide >= -2) ctx.lineTo(bx0 + (pts[p][0] - bx0) * narrow, pts[0][1] + (pts[p][1] - pts[0][1]) * drop);
          ctx.lineTo(bx0, y - th * (lc === 2 ? 0.4 : 0.05));
        }
        ctx.closePath(); ctx.fill();
      }
      if (snowy) {
        ctx.fillStyle = rgb(mix(hex('#ffffff'), leaf[2], 0.15), 0.6 + 0.4 * light);
        ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]);
        ctx.lineTo(bx0 - hw * 0.55, y - th * 0.38); ctx.lineTo(bx0 - hw * 0.2, y - th * 0.5); ctx.lineTo(bx0 + hw * 0.15, y - th * 0.36); ctx.lineTo(bx0 + hw * 0.5, y - th * 0.42);
        ctx.closePath(); ctx.fill();
      }
    }
  }

  function drawLabel(ctx, W, k, l, leaf, lift) {
    var goal = Math.max(1, l.goal), s = l.steps, f = function (n) { return Number(n).toLocaleString('en-US'); };
    var text = l.style === 'steps' ? f(s) + ' steps'
      : l.style === 'percent' ? Math.min(999, Math.round(s * 100 / goal)) + '% of goal'
      : l.style === 'bar' ? f(s) + ' / ' + f(goal)
      : f(s) + ' / ' + f(goal) + ' steps';
    var z = (l.size === 's' ? 0.8 : l.size === 'l' ? 1.3 : 1) * k, bar = l.style === 'bar';
    ctx.font = '600 ' + (44 * z) + 'px "Segoe UI", Roboto, sans-serif';
    var tw = ctx.measureText(text).width, w = tw + 90 * z, h = (bar ? 116 : 92) * z, x = (W - w) / 2;
    var y = (l.pos === 'bottom' ? 2090 : 1950) * k - (l.pos === 'bottom' ? 0 : lift);
    ctx.fillStyle = 'rgba(10,14,24,0.45)';
    ctx.beginPath(); ctx.roundRect(x, y, w, h, h / 2); ctx.fill();
    ctx.fillStyle = '#ffffff'; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
    ctx.fillText(text, W / 2, y + (bar ? 60 : 61) * z);
    if (bar) {
      var frac = Math.min(1, s / goal);
      ctx.fillStyle = 'rgba(255,255,255,0.22)'; ctx.beginPath(); ctx.roundRect(x + 45 * z, y + 80 * z, w - 90 * z, 10 * z, 5 * z); ctx.fill();
      ctx.fillStyle = rgb(mix(leaf[2], hex('#ffffff'), 0.2)); ctx.beginPath(); ctx.roundRect(x + 45 * z, y + 80 * z, Math.max(10 * z, (w - 90 * z) * frac), 10 * z, 5 * z); ctx.fill();
    }
  }

  root.TreeArt = { draw: draw, designs: DESIGNS, order: ORDER, sunPos: sunPos, moonPhase: moonPhase, place: place };
})(this);
