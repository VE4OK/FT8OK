# Architecture decision records
Short records: context, decision, consequences. Status: Accepted (user decided), Proposed (default, change if evidence says so).

| # | Title | Status |
|---|---|---|
| 0001 | Licence: GPLv3 | Accepted |
| 0002 | Fully offline, no INTERNET permission | Accepted |
| 0003 | Foreground-only operation, no notifications | Accepted |
| 0004 | Kotlin + Compose UI, native C++ decoder core | Proposed |
| 0005 | Time sync from the band, with a separate TX clock | Accepted (design), parameters Proposed |
| 0006 | Mode abstraction (FT8, FT4 now; JS8 later) | Proposed |
| 0007 | Android API levels: target 35, minSdk 26, compileSdk 36 | Proposed; amended 2026-10-14 (compileSdk 36 for Compose) |
| 0008 | Logbooks, ADIF auto-save, POTA `MY_SIG`/`MY_SIG_INFO` | Accepted (logbooks Proposed); amended 2026-10-12 |
| 0009 | Build from scratch, do not fork FT8CN/FT8TW | Accepted |
| 0010 | Portrait-locked v1, landscape not designed out | Accepted |
| 0011 | v1 scope: FT8 only, Map + occupancy in, FT4 and POTA profile deferred | Accepted |
| 0012 | Country/entity lookup behind an interface, no-op in v1 | Accepted; amended 2026-10-12 (cty.dat is MIT; reason is scope, not licence) |
| 0013 | TX frequency: two modes, pick-and-hold, move only on measured evidence | Accepted; thresholds Proposed |
