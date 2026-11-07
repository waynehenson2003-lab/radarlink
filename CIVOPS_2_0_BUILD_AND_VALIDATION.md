# SØPHIA CIVOPS 2.0 — build and validation handoff

## Scope
This source update adds an offline, versioned reference catalog, per-observation evidence details, explicitly unverified ownership, and a radar category filter. All displayed sightings come from the original Android scan/repository path. No synthetic devices, operator attribution, or proximity-based public-safety targeting were added.

## Build on Windows
1. Extract the ZIP and open the `sophia_work` directory in Android Studio.
2. Install Android SDK Platform 36 and required build tools when prompted. Use JDK 17.
3. Allow Gradle sync to complete. This source archive does not include a Gradle wrapper; Android Studio or a compatible Gradle installation is needed.
4. Run `gradle :app:assembleDebug` from the project root if Gradle is installed, or use **Build > Build APK(s)** in Android Studio.
5. Install `app/build/outputs/apk/debug/app-debug.apk` only if the build succeeds.

## Important verification status
No Android SDK, Gradle executable, or complete dependency cache was available in the editing environment. This update has **not** been compiled, installed, or tested on a phone; no new APK is claimed. The uploaded APK archive is the original, unmodified build.

## Suggested device checks
- Grant only the runtime permissions required by the existing scanner and confirm a live Wi-Fi/BLE sighting.
- Verify that category filtering does not introduce phantom observations.
- Inspect evidence and source links on a real observation; confirm manufacturer and ownership remain qualified.
- Verify history and encrypted labels survive an app restart.
- Test Android versions 10–16 for scanning restrictions, throttling, and runtime permissions.
- Check that a locally administered/randomized address does not become an asserted product or owner identity.

## Catalog maintenance
`ReferenceCatalog.kt` has a catalog version and source URLs. The citations document how broadcast fields and identifier registries work; they do **not** validate a particular product model. The catalog is intentionally generic and incomplete. Before adding product-level claims, require independently verified vendor model documentation and specific observable signatures, plus a review date. Do not infer who owns a detected device.
