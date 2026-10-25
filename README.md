# Tree Steps

An illustrated tree that grows with the steps you walk, in the app and as a live wallpaper on your home and lock screen. The app can also show the tree in 3D.

- `Tree3D.html` — the app: today's steps over the illustrated tree, settings, first-run onboarding, and **3D** (drag to rotate, pinch to zoom)
- `lib/art.js` — the illustrated renderer: six designs (scene × tree kind × colours), a seeded tree that grows with steps, the sun at its real position for the phone's time zone and the moon's phase at night; the wallpaper uses a Java port of it
- `Seed Wallpapers.html` — the 2D seeded wallpaper generator
- `lib/three.min.js`, `lib/OrbitControls.js` — Three.js for the 3D view, bundled so the app works offline
- `store/icon-512.png` — the app icon for a store listing
- `android/` — the Capacitor Android project, with the native parts in `app/src/main/java/com/boidbit/treesteps/`:
  - `TreeStepsPlugin` — real steps, settings and opening the live wallpaper picker, called from `Tree3D.html`
  - `StepStore` — turns the phone's step counter into "steps today" and stores the settings the wallpaper reads
  - `TreeWallpaperService`, `ArtRenderer` — the live wallpaper; `ArtRenderer` is a line-for-line port of `lib/art.js`, so a seed gives the same picture in both. Change them together.
  - the launcher icon is a vector (`res/drawable/ic_launcher_tree.xml` on `res/drawable-v24/ic_launcher_sky.xml`), with PNG copies in `res/mipmap-*` for older Android

## In the Android app

The first launch walks through three screens: what the app does, allowing step counting ("Physical activity"), and setting the live wallpaper. After that the home screen shows today's steps against your goal with the tree's growth stage, distance (average 0.76 m stride), calories (0.04 kcal a step), walking minutes (about 100 steps a minute), a seven-day chart, the goal streak, and a **Set as live wallpaper** button. Finished days are saved on the phone (last 60), so the chart and streak fill in from the day you start. Back closes settings or 3D first; pressing back twice exits.

The gear opens **Settings** (light theme, with a live preview of the wallpaper at the top):

| Section | Settings |
|---|---|
| Steps | Count my real steps · Daily goal (5k–15k; the tree is fully grown at the goal) · Preview steps (when real steps are off) |
| Wallpaper | Update the tree: **Live** (grows as you walk while the wallpaper is on screen) or every 5 / 15 / 30 / 60 minutes (checks steps when you unlock, then on that interval) · Tilt to look around (parallax) |
| Step counter on the wallpaper | Style: off, steps, steps / goal, progress bar, percent · Position: under the tree or bottom · Size |
| Tree | Design: Banyan (India, the default: fused trunk, aerial and pillar roots, figs), Oak, Sakura, Maple, Pine, Jacaranda · New tree (a different shape) · Preview growth |
| Sky | Follow the sun (real position for the time zone) or a fixed Dawn, Day, Sunset or Night |

The wallpaper never runs while it is hidden. In interval mode it reads the step counter once when it becomes visible and then every N minutes; tilt uses the accelerometer only when switched on.

The sun is placed from the phone's time zone (no location permission): each zone in `lib/art.js` / `ArtRenderer.java` maps to a representative city, and unknown zones fall back to a longitude from the UTC offset.

Steps count from the first time you turn them on; Android's step counter cannot report steps from before that.

## Get the APK

Every push to `main` builds a debug APK and publishes it in the repo's **Releases** tab (newest build on top, file `app-debug.apk`). It is also kept as a `tree-steps-debug-apk` artifact on the run in the **Actions** tab. You can start a build by hand with **Run workflow**.

Installing it means allowing "install unknown apps" for your browser or file manager. It is a debug build, not signed for the Play Store.

## Try it in a browser

Open `Tree3D.html` directly. Add `?steps=8000&seed=123&pal=Orchid` to the address to set the tree. Settings are kept in the browser; real steps and the wallpaper only work in the Android app.
