# 12. FT4OK

FT4OK is FT4 in a separate app, built from the same code as FT8OK. It has its own name, icon slot, settings and logbook, and installs beside FT8OK (`com.ft4ok`). Nothing in either app switches modes: the mode is fixed when the app is built. **Code.**

Only one app can use the radio's USB port at a time, so close one before opening the other. **Code** (Android gives a USB device to one app).

Everything in parts 1-11 applies to FT4OK too, with the numbers below in place of FT8's. Where FT4OK behaves differently, it is said here.

## The numbers

| | FT8OK | FT4OK | Proven |
|---|---|---|---|
| Slot | 15 s | 7.5 s | Code |
| Our transmission starts | 0.5 s | 0.452 s | Code; FT4 from WSJT-X's source |
| Transmission length | 12.64 s | 5.04 s (105 symbols of 48 ms) | Code; FT4 from WSJT-X's source |
| Signal width | 50 Hz (8 tones) | 83 Hz (4 tones, 20.8 Hz apart) | Bench |
| Decode passes | 11.8, 13.5, 14.7 s | 5.6, 6.05 s, then subtraction | On air |
| Wait for the partner's last decodes | up to 0.3 s past the boundary | none | On air (Android 15); Trial |
| Late message sent whole up to | 1.5 s late | 0.6 s late | Bench |
| No transmission started later than | 3.5 s | 1.3 s | Bench |
| Hard stop | 14.7 s | 7.2 s | Code |
| PTT watchdog | 15 s | 7.5 s | Code |
| Decoder's DT offset | +0.15 s | +0.045 s | Bench |

**Why 0.452 s:** WSJT-X's FT4 timing counts from the first sync symbol, which comes after a 48 ms ramp symbol that fades the signal in. A station "on time" (DT 0.0) starts its audio 48 ms before 0.5 s. FT4OK does the same, so its DT shows 0.0 for an on-time station, as in WSJT-X. **Code**, from WSJT-X's `ft4sim.f90` and `ft4_decode.f90`.

## Decoding

- **The start-time search is WSJT-X's ±1.0 s.** ft8_lib's own FT4 search reached only from 0.48 s early to 0.96 s late, so on a recording made with the phone's clock 0.85 s off it found 7 messages where the wider search finds 48. **Bench**, then **On air**.
- **Subtraction works on FT4,** using FT4's own waveform. On the first FT4 recording it added 8 messages to 48, including one side of a contact the first look missed. On the phone it takes 27-61 ms a slot on a quiet band. **Bench, On air.**
- **A priori decoding is FT8 only,** by choice.
- **Known weak spot:** the decoder loses about a quarter to a third of signals that start 0.30-0.45 s late (at -12.5 dB on the bench), and recovers at 0.5 s. ft8_lib had it before FT4OK changed anything. Not yet investigated. **Bench.**

## Time

The same band timing as FT8OK ([Time](03-time.md)): five-offset grid search at start-up, then the band consensus. The grid search steps 1.5 s at a time over a 30 s history, values scaled from FT8 and not measured. It has found the grid within about 16 s every time so far. **On air** (Android 15), **Trial** (values).

## Transmitting

- **The waveform is WSJT-X's FT4:** Gaussian smoothing with BT 1.0, and the first and last symbol faded over the whole symbol. A test transmission was decoded by remote stations. **On air.**
- **Timing:** a remote receiver put our transmissions +0.03 s from the other stations in the same slot (median of 14), and partner shifts landed as planned. The remote's own clock was not trusted. **On air**, provisional.
- **The decision comes at the slot boundary,** 452 ms before our audio is due, because FT4's last decodes finish over a second before the boundary on the Android 15 test phone. A slower phone may need a wait again. **On air** (Android 15), **Trial**.
- **Late starts:** up to 0.6 s late the whole message goes out late. Later than that it starts on the nominal timeline with its start left out, and nothing starts after 1.3 s. A test bench with WSJT-X's 6.048 s receive window chose those numbers. **Bench.**

## Operating

- **Less time to react.** After FT4's last decodes there are about 1.9 s before our next transmission must start. A tap on a station rarely makes the very next slot. Arm, CQ or pick a station, and let the app sequence. **Inferred.**
- **CQ runs work as in FT8OK,** combined messages included. The first run logged 5 contacts in 4 minutes, none twice. **On air.**
- **Stop CQ after unanswered CQs** (Settings › CQ PREFERENCES) is worth setting to Off for a contest, so the run never stops by itself. **Code.**

## Contests

Every FT4 contest found on the WA7BNM contest calendar sends a normal contact, a grid or Field Day's exchange, all of which FT4OK already sends:

| Contest | Exchange | In FT4OK |
|---|---|---|
| ARRL International Digital, WW Digi, NCCC FT4 Sprint, CQ WW VHF, ZL Sprint (FT4) | 4-character grid | Contest › Grid (CQ word TEST or WW) |
| International FT Challenge, DARC FT4 Contest, Africa FT4 DX, FT4 Field Day (ft4fd.com) | report and grid | Contest › Rpt+grid |
| RSGB FT4 Series, RSGB FT4 International Activity Day | report | Contest › Rpt+grid |
| ARRL Field Day | class and section | Contest › Field Day |

**Combined messages in contests.** In contest mode, Rpt+grid included, they are never sent. That matters: the International FT Challenge prohibits "WSJT-X DXpedition mode" and multi-answering, which a combined message is, and with contest mode Off a CQ run sends them. Rpt+grid sends the normal messages and adds the contest-mode rest: no combined messages, dupes marked, the CQ word, the log field ([Contest modes](09-contests.md)). **Code, Bench**; rules read November 2026.

The ARRL RTTY Roundup now allows RTTY only, and the FT Roundup became the International FT Challenge (grid and SNR), so the Roundup exchange (report plus state or serial) has no FT4 contest and is not built. Rules were read from the sponsors' pages in November 2026. Check them before each contest, including whether automatic sequencing is allowed.

## Not yet proven

- Anything on the slowest test phone (Android 13).
- A late FT4 start decoded and answered.
- The grid search's values on FT4.
- FT4OK in a real contest.
