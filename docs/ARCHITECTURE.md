# Architecture (proposed; refine during implementation, record changes as ADRs)

## Principles
- **Pure core, thin shell.** Everything that decides things (decoding glue, time sync, QSO state machine, ADIF, entity lookup, grid maths) is plain Kotlin or C++ with no Android types, driven by an injected clock and injected audio/rig interfaces. This makes replay and unit tests possible without a phone or radio.
- **One USB cable, two devices.** The radio appears as a serial port (CAT/CI-V) and a USB audio codec. They are opened and routed explicitly, never through default system audio routing.
- **Offline by construction.** No network code. Keep a `network` module boundary empty so a future "online" flavour (POTA spots, for example) can plug in via a `SpotSource` interface without touching the core.

## Modules (Gradle)
| Module | Contents | Android deps |
|---|---|---|
| `core-model` | `Mode` (slot length, TX start, TX length, decode-pass schedule, late-start limit, hard stop), `Message`, `Decode`, `Qso`, `Band`, `Grid` | none |
| `core-dsp` | Native FT8/FT4 encode/decode behind a small JNI; WAV reader; resampler | JNI only |
| `core-time` | Clock model, band-consensus offset estimator, partner snap, slew, hold-over, cold-start slot search | none |
| `core-qso` | QSO state machine, auto-answer policy, retry/watchdog, queue, TX arm logic. `CallerQueue` holds stations calling us during a CQ run (first heard first, dropped after 2 missed slots); `AutoTx` in `app` chains them with DXpedition (0.1) messages (`A RR73; B <US> -NN`, | none |
| `core-log` | ADIF writer/reader, dupe rules, worked-before index, entity (prefix) lookup **as an interface with a no-op implementation in v1** (ADR 0012) | none |
| `radio` | `Rig` interface, `FakeRig`, `Ic7300Rig` (CI-V framing), PTT strategies. **Must be a continuous stream reader** (see below), not request/response | `usb-serial-for-android` (CP21xx driver: the IC-7300's CAT interface is a vendor-specific CP2102) |
| `audio` | `UsbAudioIn/Out` with explicit device selection, level meter, ring buffer, slot-aligned capture | Android audio |
| `data` | Room database (logbooks, QSOs, settings), migrations, SAF ADIF auto-save | Room, SAF |
| `app` | Compose UI, view models, navigation, theming, design tokens | Compose |

Dependencies point inward: `app` > (`data`, `audio`, `radio`) > `core-*`. `core-*` never imports `app`, `data`, `audio` or `radio`.

## Runtime data flow
```
USB audio in --> ring buffer --(slot-aligned by TimeEngine)--> FT8/FT4 decoder (passes ~11.8/13.5/14.7 s)
   --> Decode events --> TimeEngine (DT consensus) + Feed (To-you group / other group) + Occupancy map
                                      |
                       QSO engine <---+--- user taps (queue partner, Halt, arm)
                           |
                       TX scheduler (slot, late-start limit, hard stop) --> encoder --> USB audio out + PTT (CI-V/RTS/DTR)
                           |
                       QSO closed (RR73/73) --> Log (DB) --> ADIF auto-save (SAF)
```

## Rig link: the radio pushes, we listen (proven on hardware 2026-10-13)
The first hardware tests established three things about CI-V that change how `radio` must be written.

- **The IC-7300 broadcasts every frequency and mode change unsolicited** (CI-V Transceive, on by default): command `0x00` for frequency, `0x01` for mode, addressed to `00`. So `radio` **subscribes** rather than polls — instant updates, no polling interval to tune, quiet bus. Explicit reads stay necessary for things the radio does not volunteer (SWR, ALC, and confirming a write).
- **It is a byte stream.** Frames split across reads, including through their own `FE FE` preamble, so the reader keeps a persistent buffer and parses incrementally, retaining partial tails. A fresh buffer per request silently drops frames.
- **The bus echoes our own requests**, and the echo carries the same command byte with an empty payload. Select frames by *decodable content*, never by position or command alone.

Consequently `Rig` exposes observable state (frequency, mode, filter) updated from any inbound frame, with `FakeRig` able to emit the same unsolicited updates so the QSO engine and UI are tested against the real behaviour. The radio's address is taken from a reply's `from` byte, not from configuration.

## Timing engine
- Two clocks: **listen clock** (`TimeEngine`: median of recent decode DT values weighted by the decoder's sync score, outliers rejected) and **TX clock** (`TxClock`, built 2026-10-16: during a contact our start moves by the partner's DT residual - median of their last 3 decodes, readings beyond ±2.5 s ignored, clamped to ±1 s - and returns to zero when the contact ends, so there is nothing to slew).
- **Transmit path timing (2026-10-16):** `AutoTx.decide` may fire from the slot boundary (`TxScheduler.Request.leadMs`), but while receiving it first waits for the previous slot's final decodes (up to half the late-start window), since what we send depends on them. `TxSession` then opens the audio, keys, and pads with silence so the signal leaves the phone at `AutoTx.txStartTargetMs`, using the output delay measured from `AudioTrack.getTimestamp` on the previous transmission (196 ms until then). After the last sample it **drains the buffer before unkeying**, and fades over 20 ms when stopped early. Each transmission reports how far from target the signal left the phone, and the report goes to `adb logcat -s FT8OK`.
- Hold-over via the monotonic clock (`elapsedRealtime`) so a sync survives hours offline.
- Audio pipeline latency (USB in and out) is a **separate calibrated term**, never mixed with clock error.
- Cold start: if the clock is off by more than the decoder's search range, capture about 30 s and try offsets across the slot, pick the best, then hand over to consensus.
- Late start: per-mode constant (FT8 3.5 s), hard stop about 14.7 s; if the decision lands later than the limit, skip this slot and carry the queue to the next TX slot.

## QSO engine
- One primitive: the **partner slot**. S&P = user sets it; CQ = app proposes from callers. Same Halt, same override.
- States: idle, calling, report, confirm, closing, logged; paused (partner kept, TX disarmed).
- Auto-answer levels: Off / Auto with veto (default) / Full auto (call first). Ordered policy criteria, retry limit, failed-call list, unattended-TX watchdog.
- **Halt:** abort TX, release PTT, disarm, keep partner paused. Re-arm resumes within the late-start rule.
- Auto-log at the closing RR73/73 once both reports were exchanged; Undo; "Log anyway" for incomplete QSOs. Dupes not skipped except in Contest logbooks.

## Call list rules
Two groups: **To you** (active partner first, then policy order, `Next`/`Queued` badges) above **All other decodes** (decode order, append-only within a cycle). Re-sort only at decode-pass boundaries; freeze while a finger is down.

## Data model (sketch)
`Logbook(id, type[General|POTA|SOTA|Contest], name, myRefs, dupeRule, adifUri)` ; `Qso(id, logbookId, call, timeOn, timeOff, band, freqHz, offsetHz, mode, rstSent, rstRcvd, myGrid, theirGrid, power, comment, myPotaRef, theirPotaRef, mySotaRef, theirSotaRef, p2pPending, deleted)` ; `RigProfile` ; `Settings`. ADIF export writes `MY_SIG`/`MY_SIG_INFO` (own park) and `SIG`/`SIG_INFO` (park-to-park) only — verified 2026-10-12 against POTA's ADIF reference, which does not use `POTA_REF`/`MY_POTA_REF` (ADR 0008 amendment).

## UI (Compose)
Bottom nav: Radio, Map, Log, Setup (a fifth POTA destination is a possible later addition, so build the nav from a list). Main screen: two thin spectrum lanes, call list (about nine rows), slim control bar [Halt TX] [TX on/off]. Layout metrics are named tokens. Offline map: bundled Natural Earth vector outlines, 4 fixed zoom steps (Squares / Area / Region / World), grid drawn by the app. Themes: auto, light, dark, high-contrast Outdoor.

## v1 scope (ADR 0011)
v1 is **FT8 only**, plus the Map tab and the occupancy strip with automatic TX-frequency picking. FT4 and the POTA/SOTA activation profile are deferred — but `Mode` still carries FT4's constants and they stay unit-tested, and the ADIF writer still implements the verified POTA field mapping. Entity lookup is an interface with a no-op implementation (ADR 0012), so country/continent/DXCC policies and per-country map colouring are later drop-ins. Build the modules above in full; the cut is about what v1 *exposes*, not about leaving seams out.

## Build and CI
- Kotlin, Compose, Gradle version catalog, NDK/CMake for the native decoder (16 KB page alignment for Android 15 devices).
- Variants: `debug`, `release`. Future `online` flavour not created in v1.
- Checks: unit tests, lint, **merged-manifest `INTERNET` check**, ADIF round-trip test.
- A consistent release keystore kept outside the repo.

## Diagnostics (no crash reporter, because no network)
`files/events.log` in the app's private storage always records connects, disarms with their reason, transmission problems (SWR abort, no RF, failed key) and the operator's actions. **Settings › Diagnostics › Detailed logs** adds the troubleshooting lines (per-slot decode counts, TX timing, Auto's frequency moves), `files/decodes.log` with every decode, and the audio of the slots the a priori list tested (`files/slots`, up to 500 recordings). It is off by default in release builds and on in debug builds; turning it off deletes the decode log and the recordings. Nothing leaves the phone: a share option for the log is planned, not built. Debug builds also copy the event log to logcat and include a strip preview screen (`app/src/debug`).
