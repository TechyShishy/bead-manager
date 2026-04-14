---
description: "Use when writing any code for this Android project. Covers architecture, naming conventions, data layer patterns, Compose UI patterns, and release process. Always load for Kotlin, Gradle, or Compose tasks in bead-manager."
applyTo: "**"
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
- **HTTP scraping**: OkHttp 4.x + Jsoup 1.x — live vendor price fetching
- **Java/JVM**: 17, compileSdk 36, targetSdk 36, minSdk 28

## Package Structure

Root package: `com.techyshishy.beadmanager`

```
data/
  db/          — Room entities (BeadEntity, VendorLinkEntity, VendorPackEntity), DAOs, BeadDatabase (v5)
  firestore/   — Firestore entry models and sources: inventory, orders, projects
  model/       — UI-ready data classes (BeadWithInventory, AllOrderItem)
  repository/  — CatalogRepository, InventoryRepository, OrderRepository, ProjectRepository, PreferencesRepository, PackOptimizer
  scraper/     — Live price fetching: PackPriceFetcher (interface), AcPackPriceFetcher, FmgPackPriceFetcher, VendorPackPriceFetcher
  rgp/         — RGP project import: RgpParser, RgpProject (gzip-compressed JSON from Bead Directory)
  seed/        — CatalogSeeder (seeds Room from delica-beads.json)
di/            — Hilt modules (FirebaseModule, DatabaseModule, AppModule); qualifiers @AppScope, @AppDataStore
domain/        — Use cases: FinalizeOrderUseCase, ImportRgpProjectUseCase
ui/
  adaptive/    — AdaptiveScaffold (NavigationSuiteScaffold + ListDetailPaneScaffold wiring for all tabs)
  auth/        — Sign-in screen
  catalog/     — Bead catalog browse with filter/sort
  detail/      — Bead detail pane
  orders/      — Order management: AllOrdersScreen, OrderDetailScreen, FinalizeOrderScreen, AddToOrderScreen
  projects/    — Project management: ProjectsScreen, ProjectDetailScreen
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
| DataStore keys    | `KEY_` prefix + snake_case          | `KEY_GLOBAL_LOW_STOCK_THRESHOLD`, `KEY_VENDOR_PRIORITY_ORDER`, `KEY_BUY_UP_ENABLED` |
| Enum classes      | PascalCase, values UPPER_SNAKE_CASE | `enum class AppTab { CATALOG, PROJECTS, ORDERS, SETTINGS }`          |
| Functions         | verb-first camelCase                | `inventoryStream()`, `adjustQuantity()`, `migrateLegacyThresholds()` |
| Use cases         | `{Action}UseCase`                   | `FinalizeOrderUseCase`, `ImportRgpProjectUseCase`                    |
| Firestore entries | `{Entity}Entry`                     | `InventoryEntry`, `OrderEntry`, `ProjectEntry`                       |

## Compose UI Patterns

- Screens accept a ViewModel and callbacks: `fun CatalogScreen(viewModel: CatalogViewModel, onBeadSelected: (String) -> Unit)`
- Inject ViewModels in Compose via `hiltViewModel()` from `androidx.hilt.navigation.compose`
- Expose state as `StateFlow<T>` using `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), default)`
- Navigation is managed in `AdaptiveScaffold` (`ui/adaptive/`); all tabs use state-machine navigation (`rememberSaveable`/`mutableStateOf`): CATALOG uses `var catalogDetailCode: String?` (navigates by bead code); PROJECTS and ORDERS support multi-level detail flows and cross-tab linking
- Tab selection via `enum class AppTab { CATALOG, PROJECTS, ORDERS, SETTINGS }`
- Theme via `BeadManagerTheme()` — dynamic color on API 31+, M3 baseline fallback on 28–30

## Room (Catalog Layer)

- The catalog is **read-only** — never write to Room from user actions
- Three entities: `BeadEntity`, `VendorLinkEntity`, `VendorPackEntity` — current schema version 5
- Schema versioned and exported: KSP arg `room.schemaLocation = "$projectDir/schemas"` — always write a migration when bumping schema version
- Use `@Transaction` for queries returning `BeadWithVendors`
- Use `Flow`-returning queries for reactive subscriptions

## Firestore (User Data Layer)

Three top-level collections under `users/{uid}/` (debug builds use `users_debug/{uid}/`):

| Collection | Entry type | Key fields |
|---|---|---|
| `inventory/{beadCode}` | `InventoryEntry` | `@DocumentId beadCode`, `quantityGrams`, `lowStockThresholdGrams`, `notes`, `@ServerTimestamp lastUpdated` |
| `orders/{orderId}` | `OrderEntry` | `@DocumentId orderId`, `projectIds: List<String>`, `items: List<OrderItemEntry>`, `@ServerTimestamp createdAt/lastUpdated` |
| `projects/{projectId}` | `ProjectEntry` | `@DocumentId projectId`, `name`, `beads: List<ProjectBeadEntry>`, `@ServerTimestamp createdAt` |

- Always use `SetOptions.merge()` for writes — never overwrite whole documents
- Delta mutations for inventory: `adjustQuantity(beadCode, deltaGrams)` — not set-absolute unless necessary
- `OrderItemEntry.status` uses `OrderItemStatus` enum: `PENDING → FINALIZED → ORDERED → RECEIVED` (or `SKIPPED`)
- Batch writes capped at 500 operations (Firestore limit)
- Use `callbackFlow` with snapshot listeners; retain last good cached value on error
- **Quantity unit is grams only** — never introduce other units

## Scraper (Live Vendor Prices)

- Sub-package `data/scraper/` — fetches live prices from vendor product pages via OkHttp + Jsoup
- `PackPriceFetcher` interface; `VendorPackPriceFetcher` dispatches to vendor implementations
- Supported vendor keys: `"ac"` (Aura Crystals), `"fmg"` (Fire Mountain Gems — 4-tier quantity pricing)
- Results cached in `VendorPackEntity.priceCents/tierNPriceCents` and `available` flag
- Exceptions: `NoConnectivityException`, `ScrapingFailedException`
- `PackOptimizer.selectCheapestVendor()` runs DP knapsack over available packs; used by `FinalizeOrderUseCase`

## Hilt DI

- All modules `@InstallIn(SingletonComponent::class)`
- Repositories are `@Singleton`, scoped with `@Inject constructor`
- Use `@HiltViewModel` for all ViewModels — never instantiate ViewModels manually
- Custom qualifier `@AppDataStore` disambiguates the `DataStore<Preferences>` binding
- Custom qualifier `@AppScope` disambiguates the app-level `CoroutineScope` binding

## Domain (Use Cases)

- Package `domain/` — pure Kotlin use cases, injected with `@Inject constructor`
- `FinalizeOrderUseCase` — validates pending items, checks live prices via scraper, runs `PackOptimizer`, writes finalized `OrderEntry` to Firestore
- `ImportRgpProjectUseCase` — parses a `.rgp` gzip file via `RgpParser`, maps Bead Directory color codes to `beadCode`s, creates a `ProjectEntry` in Firestore

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
