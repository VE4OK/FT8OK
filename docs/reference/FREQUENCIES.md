# FT8 / FT4 dial frequencies (verified)

This file is the single source for band presets and the TX frequency guard. Do not retype these numbers anywhere else — load them from one table in `core-model`.

## Source
Taken from the WSJT-X source of truth, not from a web article or memory: `models/FrequencyList.cpp`, the `default_frequencies` table, fetched from the official repository on **2026-10-12**.

- File: https://github.com/WSJTX/wsjtx/blob/main/models/FrequencyList.cpp
- The table in that file is introduced by the comment `// Band plans (all USB dial unless stated otherwise)`.
- Each entry carries a `bool preferred_`, documented in `models/FrequencyList.hpp` as *"preferred frequency for this band and mode"*. That flag is what distinguishes the normal calling frequency from alternates.
- Region values are `ALL`, `R1`, `R2`, `R3` (IARU regions).

**All values are USB dial frequencies** (the radio's displayed carrier frequency in USB/DATA-USB), not audio tones. Band labels below are derived from the frequency by me; the frequencies themselves are verbatim from the source.

## FT8

| Band | Dial (MHz) | Region | Preferred | Note in source |
|---|---|---|---|---|
| 160 m | 1.840 | ALL | **yes** | |
| 80 m | 3.567 | ALL | no | DXpedition |
| 80 m | 3.573 | ALL | **yes** | "above as below JT65 is out of DM allocation" |
| 60 m | 5.357 | ALL | **yes** | provisional |
| 40 m | 7.056 | ALL | no | DXpedition |
| 40 m | 7.074 | ALL | **yes** | |
| 30 m | 10.131 | ALL | no | DXpedition |
| 30 m | 10.136 | ALL | **yes** | |
| 20 m | 14.074 | ALL | **yes** | |
| 20 m | 14.090 | ALL | no | DXpedition |
| 17 m | 18.095 | ALL | no | DXpedition |
| 17 m | 18.100 | ALL | **yes** | |
| 15 m | 21.074 | ALL | **yes** | |
| 15 m | 21.091 | ALL | no | DXpedition |
| 12 m | 24.911 | ALL | no | DXpedition |
| 12 m | 24.915 | ALL | **yes** | |
| 10 m | 28.074 | ALL | **yes** | |
| 10 m | 28.091 | ALL | no | DXpedition |
| 6 m | 50.313 | ALL | **yes** | |
| 6 m | 50.323 | ALL | no | intercontinental |
| 4 m | 70.154 | R1 | **yes** | |
| 2 m | 144.174 | ALL | **yes** | |
| 1.25 m | 222.174 | R2 | **yes** | |
| 70 cm | 432.174 | ALL | **yes** | |
| 23 cm | 1296.174 | ALL | **yes** | |

## FT4

| Band | Dial (MHz) | Region | Preferred | Note in source |
|---|---|---|---|---|
| 80 m | 3.575 | ALL | **yes** | provisional |
| 80 m | 3.568 | **R3** | no | provisional |
| 60 m | 5.357 | ALL | **yes** | provisional |
| 40 m | 7.0475 | ALL | **yes** | provisional — moved |
| 30 m | 10.140 | ALL | **yes** | provisional |
| 20 m | 14.080 | ALL | **yes** | provisional |
| 17 m | 18.104 | ALL | **yes** | provisional |
| 15 m | 21.140 | ALL | **yes** | |
| 12 m | 24.919 | ALL | **yes** | provisional |
| 10 m | 28.180 | ALL | **yes** | |
| 6 m | 50.318 | ALL | **yes** | provisional |
| 2 m | 144.170 | ALL | **yes** | |

## Things to get right in the implementation

- **There is no FT4 allocation on 160 m** in this table. A band/mode picker must not offer one.
- **40 m FT4 is 7.0475 MHz**, not a whole kHz. Store all frequencies in **Hz as integers** (`7047500`), never as floating-point MHz.
- **60 m FT8 and FT4 share 5.357 MHz.** Both are marked provisional, and 60 m is channelised with differing national rules — treat 60 m as opt-in, not a default preset.
- **Alternates are not clutter, they are the DXpedition convention.** The frequency guard must *allow* every frequency in this table, while band presets and auto-selection offer only `preferred = true`. Blocking 14.090 would stop the user working a DXpedition.
- **Region matters for two entries only:** FT4 80 m has an R3-specific 3.568 MHz, and 4 m / 1.25 m FT8 are R1 / R2 respectively. Everything else is `ALL`. The user's region is a Station setting, not something to infer from a GPS the app does not have.
- **Entries above 54 MHz are outside the IC-7300's coverage**, so they are irrelevant to the v1 rig but belong in the table anyway — the architecture is radio-agnostic and `Mode` plus rig capability decides what is offered.
- **"Provisional" and "DXpedition" are the source's own words.** These are conventions, not regulations: WSJT-X's own guide notes the working-frequency table is a convention the user may edit. Keep the table editable in Setup rather than compiled in as immutable, and keep the guard's allow-list derived from it.
- **Regulatory limits are the operator's responsibility, not this table's.** A frequency being conventional for FT8 does not make it legal for a given licence class, region or power level. The guard exists to prevent *accidental* out-of-convention transmission, not to certify legality.
