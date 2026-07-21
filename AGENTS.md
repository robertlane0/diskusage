# AGENTS.md

Guidance for AI agents when working in this repository.

## Project overview

DiskUsage — an Android app that scans storage and renders a treemap of directory/app sizes so users can find and clean up space hogs. Single module (`app`), package `com.google.android.diskusage`. Mix of Java, Kotlin, and a small C native library.

- **Language mix:** Kotlin (newer code, `datasource/`, `ui/*.kt`, `utils/`), Java (older UI and filesystem code), C (`app/src/main/jni/scan.c`).
- **Rendering:** the main screen (`ui/DiskUsage.java`) is a hand-rolled full-screen `Canvas`/OpenGL treemap (`opengl/FileSystemViewGPU.java`, `ui/FileSystemViewCPU.java`) — not a RecyclerView/Toolbar layout. Treat any UI change here as touching a custom rendering surface, not standard widgets.
- **Native build:** `ndkBuild` via `externalNativeBuild { ndkBuild { path file('src/main/jni/Android.mk') } }`. No `Application.mk` currently exists.
- **Data layer:** `datasource/` defines an abstraction (`DataSource`, `PortableFile`, `PkgInfo`, `StatFsSource`, etc.) with a single `fast/` implementation (`DefaultDataSource`) backing it — check this layer before assuming a direct `PackageManager`/`File` call is the right place to add code.

## Build & test

```bash
./gradlew assembleDebug          # build debug APK
./gradlew assembleRelease        # build release APK (uses proguard-rules.txt)
./gradlew test                   # JVM unit tests (JUnit 4)
./gradlew lint                   # lint (see lint.xml overrides below)
```

`lint.xml` currently sets `MissingTranslation` and `NewApi` to `severity="warning"` — `NewApi` in particular means lint will **not** fail the build on unguarded new-API usage. When making SDK-level changes, don't rely on lint to catch guard-less API calls; check `Build.VERSION.SDK_INT` usage manually or temporarily bump `NewApi` to `error` for the pass. `ExpiredTargetSdkVersion` is currently bypassed, so it doesn't flag on the project's current outdated API target. That should be removed once any API version changes are made.

## Current milestone: target Android 16 (API 36)

**Baseline before this migration:** `compileSdk 34`, `targetSdkVersion 30`, `minSdk 21`, AGP `8.3.2`, Kotlin `2.0.0`, NDK `25.2.9519653` (r25c).
**Goal:** `compileSdk 36` / `targetSdkVersion 36`, Play-policy compliant, no regressions on the custom rendering surfaces.

Do not treat this as a routine `targetSdkVersion` bump — two things in this repo make it higher-risk than usual, and both need real engineering, not just a manifest edit:

1. **16 KB native page-size alignment.** `scan.c` itself is fine (no hardcoded page size, no raw `mmap`), but the `.so` built by `ndkBuild` with NDK r25c is not guaranteed 16 KB-aligned. This is a hard Play Store requirement for apps with native code.
2. **Edge-to-edge.** There is currently **zero** `WindowInsets`/`WindowCompat` usage anywhere in this codebase (confirmed by search). At `targetSdkVersion 36` there is no opt-out — the full-screen treemap view and the search bar overlay (`res/layout/main.xml`) will render under the status bar / gesture nav bar unless explicitly inset.

### Task checklist (in dependency order)

- [ ] **Tooling:** bump AGP (from 8.3.2), Gradle wrapper, and `ndkVersion` (from 25.2.9519653 to r27+) — confirm current minimums against `developer.android.com/build/releases/gradle-plugin` before picking exact numbers, they move fast.
- [ ] **`app/build.gradle`:** `compileSdk 36`, `targetSdkVersion 36`. Leave `minSdk 21` as-is (no requirement to raise it).
- [ ] **`AndroidManifest.xml` — build blocker:** `SelectActivity` is the launcher activity with an `<intent-filter>` but no `android:exported`. This fails the manifest merge at `targetSdkVersion 31+`. Add `android:exported="true"`. (`DiskUsage` already declares this; `PermissionRequestActivity`/`DeleteActivity`/`ShowHideMountPointsActivity` have no intent filters so they're already correctly `exported="false"` by default — don't add `exported` to those.)
- [ ] **Native/16 KB:** add `app/src/main/jni/Application.mk` with `APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true` (NDK r27+), or fall back to explicit `LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` in `Android.mk` if the flag isn't recognized. **Verify** the built `libscan.so` is actually 16 KB-aligned (Google's `check_elf_alignment.py`, or `readelf -Wl` and confirm `LOAD` segment alignment is `0x4000` not `0x1000`) — don't assume the flag alone did it. Test on Android Studio's 16 KB AVD image.
- [ ] **Edge-to-edge:** add `ViewCompat.setOnApplyWindowInsetsListener` at each activity root. In `DiskUsage.java` specifically, inset the search bar and any tappable chrome — don't necessarily pad the whole canvas (letting the treemap extend behind translucent bars is fine visually), but any hardcoded screen-edge tap-target math in `FileSystemState`/`FileSystemViewCPU` needs the same inset offset or those targets become unreachable.
- [ ] **Predictive back:** add `android:enableOnBackInvokedCallback="true"` to `<application>`. The app currently intercepts `KEYCODE_BACK` directly (`FileSystemViewGPU.java`, `FileSystemState.java`) — this still works via the compatibility shim, but won't get predictive-back preview animations until migrated to `OnBackPressedDispatcher`. Not a build blocker; can trail the rest.
- [ ] **Permissions (policy, not code):** `MANAGE_EXTERNAL_STORAGE` handling in `PermissionRequestActivity.java` and `QUERY_ALL_PACKAGES` usage in `DefaultDataSource.kt` are already correct for API 30+ — no code changes expected here. Re-confirm both Play Console declaration forms reflect `targetSdkVersion 36` before release.
- [ ] **Dependency refresh:** `androidx.core:core-ktx` (1.13.1), `androidx.appcompat:appcompat` (1.7.0) — bump for current insets/predictive-back API support. Confirm exact current versions at implementation time.
- [ ] **Cleanup (optional):** dead reflective `getPackageSizeInfo`/`IPackageStatsObserver` code is already commented out in `DefaultDataSource.kt`/`DataSource.kt`, left over from pre-`StorageStatsManager` days. Safe to delete along with `app/src/main/aidl/android/content/pm/IPackageStatsObserver.aidl`.

### Already compliant — don't over-engineer these

- No `screenOrientation` lock or `resizeableActivity="false"` anywhere → already fine under Android 16's large-screen policy (orientation/resizability restrictions are ignored on large screens at API 36 regardless).
- No foreground services, `JobScheduler`, `AlarmManager`, or notifications in the app → nothing to migrate for the API 33/34 behavior changes in those areas.
