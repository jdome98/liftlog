// Copies the PWA's deployable assets from the repo root into mobile/www/,
// which Capacitor packs into the native APK / iOS bundle. The root file
// layout stays untouched so Cloudflare Pages keeps serving the live site
// the same way it always has.
import { mkdir, copyFile, rm } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..');
const wwwDir = join(here, 'www');

const FILES = [
  'index.html',
  'manifest.json',
  'sw.js',
  'favicon.ico',
  'icon-192.png',
  'icon-512.png',
  'icon-512-maskable.png'
];

await rm(wwwDir, { recursive: true, force: true });
await mkdir(wwwDir, { recursive: true });

for (const f of FILES) {
  await copyFile(join(repoRoot, f), join(wwwDir, f));
}

console.log(`Synced ${FILES.length} files to ${wwwDir}`);
