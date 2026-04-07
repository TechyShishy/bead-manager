---
description: "Use when writing any code for this Android project. Covers architecture, naming conventions, data layer patterns, Compose UI patterns, and release process. Always load for Kotlin, Gradle, or Compose tasks in bead-manager."
applyTo: "app/src/**/*.kt, app/build.gradle.kts, gradle/libs.versions.toml"
---

# Bead Manager — Project Conventions

## Technology Stack

- **Language**: Kotlin 2.1.10
- **Build**: AGP 8.7.0, Gradle 8.9, KSP 2.1.10-1.0.31
- **UI**: Jetpack Compose + Material3 Adaptive (`ListDetailPaneScaffold`, `NavigationSuiteScaffold`)
- **Local DB**: Room 2.7 — read-only bead catalog seeded from `delica-beads.json`
- **Remote DB**: Firebase Firestore — user inventory (offline persistence enabled)
- **Auth**: Firebase Auth + Google Sign-In via Credential Manager
- **DI**: Hilt (`@HiltAndroidApp`, `@HiltViewModel`, `@InstallIn(SingletonComponent::class)`)
- **Images**: Coil 3.x with CDN URLs + hex color placeholder/fallback
- **Serialization**: `kotlinx.serialization`
- **Java/JVM**: 17, compileSdk 36, targetSdk 36, minSdk 28

## Package Structure

Root package: `com.techyshishy.beadmanager`

```
data/
  db/          — Room entities, DAOs, BeadDatabase
  firestore/   — Firestore data sources and entry models
  model/       — UI-ready data classes
  repository/  — Repository abstractions
  seed/        — CatalogSeeder (seeds Room from delica-beads.json)
di/            — Hilt modules (FirebaseModule, DatabaseModule, AppModule)
ui/
  adaptive/    — ListDetailPaneScaffold scaffold wiring
  auth/        — Sign-in screen
  catalog/     — Bead catalog browse
  detail/      — Bead detail pane
  settings/    — Settings screen
  migration/   — One-time data migration logic
  theme/       — BeadManagerTheme() / Material3 theming
```

## Naming Conventions

| Element           | Pattern                             | Examples                                                             |
| ----------------- | ----------------------------------- | -------------------------------------------------------------------- |
| Screens           | `{Feature}Screen`                   | `CatalogScreen`, `SettingsScreen`                                    |
| Detail panes      | `{Feature}Pane`                     | `BeadDetailPane`                                                     |
| ViewModels        | `{Feature}ViewModel`                | `CatalogViewModel`, `SettingsViewModel`                              |
| Firestore sources | `Firestore{Entity}Source`           | `FirestoreInventorySource`                                           |
| Repositories      | `{Entity}Repository`                | `InventoryRepository`                                                |
| Room entities     | `{Entity}Entity`                    | `BeadEntity`, `VendorLinkEntity`                                     |
| Room DAOs         | `{Entity}Dao`                       | `BeadDao`, `VendorLinkDao`                                           |
| Constants         | `UPPER_SNAKE_CASE`                  | `DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD`                                 |
| DataStore keys    | `KEY_` prefix + snake_case          | `KEY_GLOBAL_LOW_STOCK_THRESHOLD`                                     |
| Enum classes      | PascalCase, values UPPER_SNAKE_CASE | `enum class AppTab { CATALOG, SETTINGS }`                            |
| Functions         | verb-first camelCase                | `inventoryStream()`, `adjustQuantity()`, `migrateLegacyThresholds()` |

## Compose UI Patterns

- Screens accept a ViewModel and callbacks: `fun CatalogScreen(viewModel: CatalogViewModel, onBeadSelected: (String) -> Unit)`
- Inject ViewModels in Compose via `hiltViewModel()` from `androidx.hilt.navigation.compose`
- Expose state as `StateFlow<T>` using `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), default)`
- Navigation is managed in `MainActivity`/the adaptive scaffold using `rememberListDetailPaneScaffoldNavigator<String>()` (navigates by bead code string)
- Tab selection via `enum class AppTab { CATALOG, SETTINGS }`
- Theme via `BeadManagerTheme()` — dynamic color on API 31+, M3 baseline fallback on 28–30

## Room (Catalog Layer)

- The catalog is **read-only** — never write to Room from user actions
- Schema versioned and exported: KSP arg `room.schemaLocation = "$projectDir/schemas"` — always write a migration when bumping schema version
- Use `@Transaction` for queries returning `BeadWithVendors`
- Use `Flow`-returning queries for reactive subscriptions

## Firestore (Inventory Layer)

- Collection path: `users/{uid}/inventory` (debug builds: `users_debug/{uid}/inventory`)
- `InventoryEntry` maps to a Firestore document: `@DocumentId` on `beadCode`, `@ServerTimestamp` on `lastUpdated`
- Always use `SetOptions.merge()` for writes — never overwrite whole documents
- Delta mutations: `adjustQuantity(beadCode, deltaGrams, current)` — not set-absolute unless necessary
- Batch writes capped at 500 operations (Firestore limit)
- Use `callbackFlow` with snapshot listeners; retain last good cached value on error
- **Quantity unit is grams only** — never introduce other units

## Hilt DI

- All modules `@InstallIn(SingletonComponent::class)`
- Repositories are `@Singleton`, scoped with `@Inject constructor`
- Use `@HiltViewModel` for all ViewModels — never instantiate ViewModels manually
- Custom qualifier `@AppDataStore` disambiguates the `DataStore<Preferences>` binding

## Gradle & Build

- Use the version catalog (`gradle/libs.versions.toml`) for all dependency and plugin declarations — never add inline version strings to `build.gradle.kts`
- The `copyBeadCatalog` task keeps `delica-beads.json` as the single source of truth — do not copy/duplicate this file manually
- `versionCode` is set from `GITHUB_RUN_NUMBER` env var (falls back to `"1"` locally) — do not hardcode it
- `versionName` is a manual semver string set in `app/build.gradle.kts` — bump it before tagging a release
- Release signing reads from env vars (`ANDROID_KEYSTORE_FILE`, etc.) — never commit keystore credentials

## Release Process — IMPORTANT

**Do NOT create GitHub Releases.** The release workflow does not create GitHub Releases. GitHub Releases are not used by this project.

The release process is:

1. Update `versionName` in `app/build.gradle.kts` manually
2. Create and push a git tag: `git tag v<version> && git push origin v<version>`
3. The `release.yml` workflow is triggered by the tag and builds a signed AAB
4. Download the AAB artifact from GitHub Actions and upload to Play Store Console manually

Never suggest:

- Creating a GitHub Release via `gh release create` or the GitHub UI
- Automating Play Store uploads
- Using `release-please`, `semantic-release`, or similar tools
- Any step outside the four-step flow above

## Out of Scope

- **No WorkManager** — no background sync, no background notifications
- **No custom sync logic** — Firestore offline persistence handles last-write-wins via `@ServerTimestamp`
- **No Play Store automation** — AAB upload to Play Console is always manual
