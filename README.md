# Tree Steps

A 3D tree that grows with the steps you walk. Drag to rotate, pinch to zoom, or switch on Tilt mode and move your phone.

- `Tree3D.html` — the 3D tree (the app's home screen)
- `Seed Wallpapers.html` — the 2D seeded wallpaper generator
- `lib/` — Three.js and OrbitControls, bundled so the app works offline

## Get the APK

Every push to `main` builds a debug APK and publishes it in the repo's **Releases** tab (newest build on top, file `app-debug.apk`). It is also kept as a `tree-steps-debug-apk` artifact on the run in the **Actions** tab. You can start a build by hand with **Run workflow**.

Installing it means allowing "install unknown apps" for your browser or file manager. It is a debug build, not signed for the Play Store.

## Try it in a browser

Open `Tree3D.html` directly. Add `?steps=8000&seed=123&pal=Orchid` to the address to set the tree.
