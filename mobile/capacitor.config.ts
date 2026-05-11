import type { CapacitorConfig } from '@capacitor/cli';

// Loading the WebView from the live splitstak.com URL (rather than the
// bundled `www/` directory) gives the native app the SAME ORIGIN as the
// browser PWA. That means:
//   - localStorage (`liftlog_data_v2`) is automatically shared between
//     the installed Android app and anyone visiting splitstak.com in
//     Chrome on the same phone — no migration step on first install.
//   - Web changes pushed to splitstak.com auto-propagate to the native
//     app on next launch, no APK rebuild required.
//   - The service worker registered for splitstak.com runs inside the
//     WebView too, providing offline cache after the first online visit.
//
// `webDir: 'www'` is kept (and `sync-web.mjs` continues to populate it)
// purely as a build-system requirement and as a safety net for any
// future Capacitor flows that need a static fallback. With `server.url`
// set, Capacitor on Android loads from the URL, not from `www/`.
//
// Note: this configuration is Android-only friendly. Apple's App Store
// review typically rejects iOS apps that just load a remote URL; iOS is
// deferred per the handoff plan, and would need a different config when
// it's revisited.
const config: CapacitorConfig = {
  appId: 'com.splitstak.app',
  appName: 'SPLITSTAK',
  webDir: 'www',
  server: {
    url: 'https://splitstak.com',
    androidScheme: 'https',
    cleartext: false
  }
};

export default config;
