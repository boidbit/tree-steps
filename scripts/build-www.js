// Copies the web pages into www/ (the folder Capacitor packages into the app).
// Tree3D.html is the app's home screen.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const out = path.join(root, 'www');

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
fs.copyFileSync(path.join(root, 'Tree3D.html'), path.join(out, 'index.html'));
// the privacy policy, also published from docs/ (Settings links to it, and Health Connect opens it)
fs.copyFileSync(path.join(root, 'docs/privacy.html'), path.join(out, 'privacy.html'));
fs.cpSync(path.join(root, 'lib'), path.join(out, 'lib'), { recursive: true });
// Capacitor's browser bundle lets the page call the app's native code (steps, wallpaper)
fs.copyFileSync(path.join(root, 'node_modules/@capacitor/core/dist/capacitor.js'), path.join(out, 'lib', 'capacitor.js'));
console.log('www/ ready:', fs.readdirSync(out).join(', '));
