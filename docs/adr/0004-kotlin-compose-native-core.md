# 0004 Kotlin + Compose UI, native C++ decoder core
**Status:** Proposed. **Context:** Need a modern UI, testable logic, and a fast FT8/FT4 decoder. **Decision:** Kotlin and Jetpack Compose for UI and logic; native C++ for encode/decode behind a thin JNI. Start with `ft8_lib` (MIT) as the baseline decoder; measure decode counts against WSJT-X on the WAV corpus; port stronger algorithms from GPL sources only if the corpus shows a real gap. **NDK path proven 2026-10-14: the phone decodes identically to the host.** `ft8_lib` is vendored at `core-dsp/src/main/cpp/ft8_lib/` (upstream `9fec6ca`, library sources only), cross-compiled for `arm64-v8a` with NDK 28.2.13676358 and CMake 4.0.2, behind the `nativeDecode(float[], int) -> String[]` JNI boundary.

Run against the *same* corpus file the host build already decoded, so the expected answer was known: **12 messages, identical messages, identical DT values, identical frequencies**. Verifying the cross-compile and the JNI boundary against a known answer is why this step came before live capture — a zero-decode result from live audio could not have been attributed between the build, the bridge, the decimator and the slot alignment.

- **Decode time: 39 ms for a full 15 s slot**, against a decision budget of roughly a second. Ample headroom, and it suggests multiple decode passes per slot are affordable.
- **The "SNR" question is closed by arithmetic.** The host printed `+18.5` where the JNI reports score `37`, and `+11.0` where it reports `22` — exactly `score × 0.5`, confirming the demo's own TODO. The JNI therefore passes the **raw score** up and names it `score`, never `snr`, so nothing downstream can mistake it for a signal report.
- **ARM and x86 agree**, which incidentally rules out an endianness or float-width problem in the vendored code.

**JNI shape:** tab-separated text across the boundary rather than constructed Java objects. Building objects from C is a lot of fragile boilerplate for tens of messages per 15 s slot, and a text protocol is trivially inspectable when something goes wrong. The Kotlin side parses into a `Decode` data class, so the interface above stays typed and the library stays swappable.

**Decoder confirmed 2026-10-13: `ft8_lib` decodes real signals off our own radio.** Built natively with MinGW-w64 gcc with a bench script and run over corpus file #1: **12 messages, deterministic across runs**, with valid callsigns, grids and message types. So the baseline decoder choice stands and the `audio window in, decodes out` interface can be built against it.

Three caveats that do *not* change the decision but constrain how it is used:

- **Sensitivity versus WSJT-X remains unverified**, and that is still the thing that decides whether this library survives contact with weak signals. 12 decodes proves it works, not how deep it reaches. Needs WSJT-X over the same file, ideally a marginal recording.
- **Its SNR estimate is uncalibrated and must not be aired**. All 12 decodes reported +11 to +18.5 dB where real FT8 SNRs are mostly negative. Since the decoded SNR is transmitted back as the other station's signal report, shipping this uncalibrated would send wrong reports.
- **It needs 12 kHz input.** 48 kHz is rejected outright, so `core-dsp` must decimate 48 → 12 kHz ahead of the decoder. That is a real component, not a detail: the rig's SSB filter makes a crude decimator *appear* adequate, but production should use a proper FIR.

**Portability note for the vendoring step:** `ft8_lib` calls POSIX `stpcpy` unconditionally in `ft8/message.c`, despite a `-DHAVE_STPCPY` in its Makefile that nothing in the code tests. It therefore does not build on MinGW/UCRT without a shim. Android's bionic provides `stpcpy`, so the NDK build is unaffected — but it is worth knowing for a library that advertises microcontroller use, and the host-native corpus harness (test-ladder rung 1) depends on the shim. The harness injects it with `-include` so the third-party sources stay unmodified, and `third_party/` is gitignored rather than vendored until we commit to the library.

**Hardware facts added 2026-10-13 (IC-7300 enumerated on the phone):** the radio's CAT interface is a **Silicon Labs CP2102** (`0x10C4:0xEA60`) presenting a **vendor-specific (0xFF)** interface, not CDC — so the `radio` module needs `usb-serial-for-android`'s CP21xx driver and cannot rely on any built-in Android serial support. The audio side is a **standard USB Audio Class 1.0** codec (`0x08BB:0x2901`, TI PCM290x), which means the `audio` module can use `AudioRecord`/`AudioTrack` with `setPreferredDevice` and needs no custom USB-audio implementation. Both arrive on one cable via an internal hub in the radio.

**Amended 2026-10-13 (measured on the test phone):** `getconf PAGE_SIZE` on the Android 15 (SDK 35) test phone returns **4096** — not 16384. So 16 KB page alignment is **not** required to run on this device; it is forward-compatibility for phones that do use 16 KB pages. Keep doing it (Play will require it and other devices need it), but it was not a blocker for the first hardware tests and must not be diagnosed as the cause of a native load failure on *this* phone. Supported ABIs are `arm64-v8a, armeabi-v7a, armeabi`, so the decoder targets **arm64-v8a only** unless there is a reason to ship more. `android.hardware.audio.low_latency` is present, which is relevant to the separately-calibrated audio latency term (ADR 0005).

**Consequences:** NDK and CMake required; native libs should be 16 KB page-aligned for devices that need it. Verify ft8_lib's FT4 support and sensitivity before committing. The decoder interface (`audio window in, decodes out`) must stay swappable.

---

## Measured 2026-10-14: the sensitivity gap is structural, not a tuning knob

The caveat above — "sensitivity versus WSJT-X remains unverified, and that is still
the thing that decides whether this library survives contact with weak signals" — is
now measured, and the answer needed no WSJT-X install. `ft8_lib`'s own test corpus
ships 60 WAVs paired with reference decodes in WSJT-X's decode-window format, all
already at 12 kHz, so no resampling sits between the two decoders
(a reference-comparison bench script; the reference files' provenance is undocumented).

**At the library's default settings, `ft8_lib` finds 72.3% of the reference
decodes** — 932 of 1289 across 60 slots, missing 357 and finding 27 the reference
did not. The misses average −7.9 dB, which is unsurprising, but run up to **+13 dB**,
which is. Missing a signal 13 dB above the noise is not a sensitivity limit.

So the three depth parameters were swept (with a bench script):

| Setting | min score | candidates | LDPC iters | decodes | shared | % |
|---|---|---|---|---|---|---|
| defaults | 10 | 140 | 25 | 959 | 932 | 72.3 |
| more candidates | 10 | 400 | 25 | 961 | 933 | 72.4 |
| lower threshold | 5 | 400 | 25 | 963 | 933 | 72.4 |
| lower + deeper | 5 | 400 | 60 | 968 | 936 | **72.6** |
| wide net | 3 | 800 | 60 | 968 | 935 | 72.5 |
| everything | 2 | 1200 | 90 | 969 | 936 | **72.6** |

**Roughly nine times the work buys 0.3 percentage points.** Four more decodes out of
1289. The parameters are not the cause and there is no knee to find — the curve is
flat. Two conclusions follow:

1. **Keep the defaults.** They are both the cheapest and, within noise, the best.
   `kMinScore = 10`, `kMaxCandidates = 140`, `kLdpcIterations = 25` in
   `ft8ok_decoder.c` stay as they are, now for a measured reason rather than because
   they were inherited from the demo.
2. **The remaining 27% is architectural.** The most likely cause is that WSJT-X
   decodes in multiple passes, **subtracting each decoded signal from the waterfall
   and decoding again**, which uncovers signals sitting underneath stronger ones —
   precisely the pattern of missing a strong signal while finding weaker ones in the
   same slot. `ft8_lib` does a single pass. It does ship an encoder, so subtraction
   is implementable rather than hypothetical.

**The decision does not change yet, and this is deliberately not recorded as a
verdict.** 72% is a working FT8 app: it hears roughly three of every four stations
WSJT-X hears, which is usable on air and better than the app it replaces is reputed
to be. The choice is between accepting that for v1, implementing subtractive
multi-pass decoding ourselves, and replacing the library. That is a scope decision
for the maintainer, informed by on-air experience, not something to settle from a corpus
run — and it is not blocking, because the interface was kept swappable exactly so
this question could stay open.

**This also retires the decode-depth idea at the cheap end**. A
toggle over these three parameters would be a setting that changes nothing; if a
depth control is ever offered, the lever has to be subtraction passes.

### Correction 2026-10-14: what is verified, what is documented, what is inferred

The section above was written with more confidence than its evidence carried, and
the maintainer challenged it. Separating the three:

**Verified** — `ft8_lib` is single-pass. `demo/decode_ft8.c` calls
`ftx_find_candidates` once and loops over the result once; the string "subtract"
appears nowhere in `ft8/` or `common/`. Read from the vendored source, not inferred.

**Verified** — the 72% figure reproduces by hand. `websdr_test1.txt` holds 18
decodes; our decoder finds 13 of the same messages on `websdr_test1.wav`, 13/18 =
72.2%, matching the aggregate. The five missed are at −14, −24, −15, −6 and −1 dB
in the reference. This was checked after two real bugs were found in the harness,
precisely because the harness had been wrong twice.

**Documented, not inferred** — WSJT-X does signal subtraction and multi-pass
decoding. K1JT's own release notes for WSJT-X 1.8.0, as carried by the ARRL in
September 2017: *"The decoder is faster and better. It now includes signal
subtraction, multi-pass decoding, and the use of accumulated 'a priori' information
as a QSO progresses."* The earlier amendment asserted this from the shape of the
data; it should have cited the source.

**Still inference** — that subtraction accounts for *our* 27.7% gap specifically. It
is a well-grounded inference now, sitting between two established facts, but it has
not been demonstrated, and the way to demonstrate it is to implement subtraction and
re-measure.

**A caveat that weakens the 72% figure, raised by the same quote.** WSJT-X also uses
**a priori (AP) decoding** — accumulated knowledge of expected message content, such
as one's own callsign, as a QSO progresses. If the reference decodes were produced
with AP enabled, some of them may depend on information no standalone decoder has
from a bare WAV file, and the gap would be partly unreachable by any single-pass or
even multi-pass decoder working blind. **We do not know whether AP was enabled**, and
the provenance of those files is undocumented, so **72% is a lower
bound on our performance against a fair comparison, not a measurement of one.**

Nothing here changes the decision. It changes how much weight the number can bear.

**Amended 2026-11-05 (to try other devices):** the decoder now builds for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` in one APK. With arm64 alone, Compose's native library still declared all four, so a 32-bit or x86 device installed the app and then had no decoder. Built and packaged; not yet run on anything but arm64.
