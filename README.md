# Bead Manager

An Android app for tracking a Miyuki Delica bead collection. Browse the full Delica color catalog, record how many grams you have on hand, and get low-stock alerts — all synced across devices via Firebase.

## Features

- **Full catalog** — complete Miyuki Delica color database, searchable and filterable by vendor, finish, and color family
- **Inventory tracking** — log quantity (grams) per bead; quantities sync across devices in real time
- **Low stock view** — dedicated screen showing beads below your threshold
- **Adaptive layout** — `ListDetailPaneScaffold` gives phones a standard list/detail flow and tablets a persistent side-by-side panel
- **Google Sign-In** — inventory is tied to a Google account via Firebase Auth; the catalog is local-only (Room)
- **Offline-first** — Firestore offline persistence means the app is fully functional without a network connection

## Requirements

- Android SDK 28+ (Android 9.0)
- JDK 21
- A Firebase project with Firestore and Authentication (Google Sign-In) enabled

## Getting started

### 1. Clone

```bash
git clone https://github.com/techyshishy/bead-manager.git
cd bead-manager
```

### 2. Configure local secrets

```bash
cp local.properties.template local.properties
```

Edit `local.properties` and set:

```
google_web_client_id=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

The Web Client ID comes from **Firebase Console → Project Settings → General → Your apps**, or from **Google Cloud Console → APIs & Services → Credentials → OAuth 2.0 Client IDs → Web client (auto created by Google Service)**.

### 3. Add google-services.json

Download `google-services.json` from **Firebase Console → Project Settings → General → Your apps → Android app** and place it at:

```
app/google-services.json
```

This file is gitignored. Never commit it.

### 4. Build and run

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run normally.

## CI

The GitHub Actions workflows at `.github/workflows/` require the following repository secrets:

| Secret | Description |
|---|---|
| `GOOGLE_WEB_CLIENT_ID` | OAuth 2.0 Web Client ID |
| `GOOGLE_SERVICES_JSON` | Full contents of `google-services.json` |
| `ANDROID_KEYSTORE_BASE64` | Release keystore encoded as base64 (release builds only) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password (release builds only) |
| `ANDROID_KEY_ALIAS` | Key alias within the keystore (release builds only) |
| `ANDROID_KEY_PASSWORD` | Key password (release builds only) |

**CI** (`ci.yml`) runs on every push to `main` / `develop` and on pull requests targeting `main`. Produces a debug APK artifact.

**Release** (`release.yml`) runs when a `v*` tag is pushed. Produces a signed release AAB artifact.

To cut a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## Architecture

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material3 Adaptive |
| DI | Hilt |
| Catalog storage | Room (read-only, seeded from `delica-beads.json` at first launch) |
| Inventory storage | Firebase Firestore (offline persistence, last-write-wins by `@ServerTimestamp`) |
| Image loading | Coil 3 (CDN URLs from catalog JSON, hex color swatch as fallback) |
| Auth | Firebase Auth + Google Sign-In via Credential Manager |

### Adding a new vendor

1. Add your key → display name mapping to `CatalogSeeder.VENDOR_DISPLAY_NAMES`.
2. Add the vendor's purchase URLs to `delica-beads.json`.
3. Bump `CATALOG_VERSION` in `CatalogSeeder`.
4. Write a Room migration for the schema change (if any).

## Privacy

[Privacy Policy](https://techyshishy.github.io/bead-manager/privacy)

## License

[MIT](LICENSE)
