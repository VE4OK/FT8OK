# 0007 Android API levels
**Status:** Proposed; amended 2026-10-14 (compileSdk 36).

**Decision:** **targetSdk 35** (the main test phone runs Android 15), **minSdk 26** so older phones still work, **compileSdk 36**. No API newer than 35 at runtime; gate anything newer than 26. USB audio routed with `setPreferredDevice` (API 23).

**Amendment, 2026-10-14 — compileSdk 36, targetSdk still 35.** Adding Compose forced this. `androidx.core:core-ktx:1.18.0`, which Compose pulls in, refuses to be consumed by a project compiled against 35: *"requires libraries and applications that depend on it to compile against version 36 or later."* The three levels are separate decisions and only one of them had to move:

| | What it means | Value |
|---|---|---|
| `minSdk` | which devices can install | 26 |
| `targetSdk` | which new **runtime behaviours** the app opts in to | 35 |
| `compileSdk` | which APIs the **compiler** can see | 36 |

Raising compileSdk alone changes no runtime behaviour, so "target the phone's own API level" — the reason this ADR exists — is untouched. The guard against actually *using* a too-new API is unchanged and is the real protection: minSdk stays 26, so lint's `NewApi` check still flags every ungated call above 26, which is the rule that matters in practice.

**Rejected:** pinning an older Compose. The alternative was a version hunt that starts the project on stale UI libraries, to avoid a change that alters nothing at runtime.

**Not available:** compileSdk 37, which the *current* Compose BOM (2026.09.00, Compose 1.12.x) demands. `platforms;android-37` is not a published SDK package — `sdkmanager` reports "Failed to find package" — so that BOM cannot be built against at all here. Compose is therefore pinned to **BOM 2026.06.01 (Compose 1.11.4)**, the newest that builds against 36. Revisit when android-37 ships.

**Consequences:** Handle forced edge-to-edge (target 35). Test on Android 15; older versions are best effort. The Compose BOM is now coupled to the available platform SDK, so a BOM bump may require an SDK platform install first — check `checkDebugAarMetadata` before assuming a version is usable.
