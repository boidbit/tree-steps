# Tree Steps

An illustrated tree that grows with the steps you walk, in the app and as a live wallpaper on your home and lock screen. The app can also show the tree in 3D.

- `Tree3D.html` — the app: today's steps over the illustrated tree, settings, first-run onboarding, and **3D** (drag to rotate, pinch to zoom)
- `lib/art.js` — the illustrated renderer: six designs (scene × tree kind × colours), a seeded tree that grows with steps, the sun at its real position for the phone's time zone and the moon's phase at night; the wallpaper uses a Java port of it
- `Seed Wallpapers.html` — the 2D seeded wallpaper generator (not part of the app)
- `docs/` — the website for GitHub Pages: `privacy.html` is the privacy policy (also bundled in the app, under Settings), `index.html` a small landing page
- `lib/three.min.js`, `lib/OrbitControls.js` — Three.js for the 3D view, bundled so the app works offline
- `store/` — the Google Play listing: `PLAY_STORE.md` (step-by-step guide, listing text, Data safety answers), `icon-512.png`, `feature-graphic-1024x500.png`, `screenshots/`
- `android/` — the Capacitor Android project, with the native parts in `app/src/main/java/com/boidbit/treesteps/`:
  - `TreeStepsPlugin` — real steps, Health Connect, settings, widgets and opening the live wallpaper picker, called from `Tree3D.html`
  - `StepStore` — turns the phone's step counter (or Health Connect) into "steps today", leaves out steps in vehicles, keeps the history, today's hours, the goal streak and the time since the last walk, and stores the settings the wallpaper reads
  - `VehicleWatch` — Google Play services activity detection: the step counter is read on getting into and out of a car, bus, train or bike, and the steps in between don't count
  - `HealthSteps.kt` — reads steps from Health Connect (Samsung Health, Google Fit and others), today and the last 60 days
  - `TreeWidget`, `TreeWidgetMedium`, `TreeWidgetLarge` — the home screen widgets; `Reminders`, `ReminderReceiver` — the evening reminder and re-arming it (and vehicle detection) after a reboot
  - `TreeWallpaperService`, `ArtRenderer` — the live wallpaper; `ArtRenderer` is a line-for-line port of `lib/art.js`, so a seed gives the same picture in both. Change them together.
  - the launcher icon is a vector (`res/drawable/ic_launcher_tree.xml` on `res/drawable-v24/ic_launcher_sky.xml`), with PNG copies in `res/mipmap-*` for older Android

## In the Android app

The first launch walks through three screens: what the app does, allowing step counting ("Physical activity"), and where the tree should live: **wallpaper**, a **home screen widget**, or **just in the app**. Nothing on the phone changes unless you pick it. After that the home screen shows today's steps against your goal with the tree's growth stage, distance (stride from your height), calories (scaled by your weight), walking minutes (about 100 steps a minute), a seven-day chart, the goal streak, a **share** button and **Show on my phone** (the same three choices). Finished days are saved on the phone (last 60, with that day's goal, design and seed), so the chart, streak and forest fill in from the day you start.

The tree is alive:

- **Visitors**: animals move in as the goal streak grows: butterflies (fireflies at night) from day 1, a bird at 3, a nest and a second bird at 5, a squirrel at 7, a rabbit at 14 and a peacock at 30 (a fox in the snow scene). They leave if the streak breaks.
- **Thirsty tree**: with real steps, two hours without a walk (50 steps within ten minutes) between 8 AM and 9 PM, the leaves start to yellow and droop, fully by four hours; a walk perks it up, and it never wilts once the day's goal is reached.

The tree icon opens **Your journey**: totals, today by hour (steps between two readings count toward the later hour), **My forest** (each day's tree, redrawn from its design and seed; tap one to share it as a picture), **achievements** and **visitors**.

There are three home screen **widgets**: small (2×2, tree and steps), wide (4×2, adds distance, calories and the streak) and large (4×4, adds the week). The app can add them in one tap where the launcher allows it. Android refreshes them every 30 minutes, and new readings refresh them every 100 steps. There is also an optional **evening reminder** that fires at the chosen hour only if today's goal isn't reached (needs notification permission on Android 13+). Back closes settings or 3D first; pressing back twice exits.

Steps in vehicles: phones count shaking in a car or bus as steps. With **Ignore steps in vehicles** on (the default), Google Play services tells the app when you get into and out of a vehicle or onto a bike, and those steps are left out. A trip that never reports its end stops being left out after four hours. **Step source: Health Connect** reads steps from Samsung Health, Google Fit and other apps instead (they already filter car rides), and fills in up to 60 past days. Between reads, the phone's counter adds the steps it sees.

The gear opens **Settings** (light theme, with a live preview of the wallpaper at the top):

| Section | Settings |
|---|---|
| About you | Height (sets the stride) · Weight (for calories) |
| Reminders | Evening reminder · time (5 to 9 PM) |
| On your phone | Whether the wallpaper is on and how many widgets are added · Choose where it shows (all optional) |
| Steps | Count my real steps · Step source (phone or Health Connect) · Ignore steps in vehicles · Daily goal (5k–15k; the tree is fully grown at the goal) · Preview steps (when real steps are off) |
| Wallpaper | Update the tree: **Live** (grows as you walk while the wallpaper is on screen) or every 5 / 15 / 30 / 60 minutes (checks steps when you unlock, then on that interval) · Tilt to look around (parallax) |
| Step counter on the wallpaper | Style: off, steps, steps / goal, progress bar, percent · Position: under the tree or bottom · Size |
| Tree | Design: Banyan (India, the default: fused trunk, aerial and pillar roots, figs), Oak, Sakura, Maple, Pine, Jacaranda · New tree (a different shape) · Preview growth |
| Sky | Follow the sun (real position for the time zone) or a fixed Dawn, Day, Sunset or Night |

The wallpaper never runs while it is hidden. In interval mode it reads the step counter once when it becomes visible and then every N minutes; tilt uses the accelerometer only when switched on.

The sun is placed from the phone's time zone (no location permission): each zone in `lib/art.js` / `ArtRenderer.java` maps to a representative city, and unknown zones fall back to a longitude from the UTC offset.

Steps count from the first time you turn them on; Android's step counter cannot report steps from before that.

## Get the APK

Every push to `main` builds the app and publishes it in the repo's **Releases** tab (newest on top). It is also kept as an artifact on the run in the **Actions** tab. You can start a build by hand with **Run workflow**.

- With the signing secrets set in the repo (`TS_KEYSTORE_BASE64`, `TS_KEYSTORE_PASSWORD`; see `store/PLAY_STORE.md`), a build is a signed release: `tree-steps-0.1.N.apk` to install and `tree-steps-0.1.N.aab` to upload to Google Play. The version code is the build number, so each build can be uploaded.
- Without them (pull requests, forks), it is `tree-steps-0.1.N-debug.apk`.

The signing key never goes in the repo. Gradle reads it from the `TS_KEYSTORE` environment variable (a path), with `TS_KEYSTORE_PASSWORD`, `TS_KEY_ALIAS` (default `upload`) and `TS_KEY_PASSWORD` (defaults to the keystore password).

Installing the APK means allowing "install unknown apps" for your browser or file manager. It needs Android 8.0 or newer and targets Android 16 (API 36). The project uses Capacitor 8, which needs JDK 21 to build. A signed APK can't update a debug install, or the other way round: uninstall first.

## Try it in a browser

Open `Tree3D.html` directly. Add `?steps=8000&seed=123&pal=Orchid` to the address to set the tree. Settings are kept in the browser; real steps and the wallpaper only work in the Android app.
