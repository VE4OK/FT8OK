# Third-party licences and provenance

Keep this file current. Every borrowed file or algorithm gets a line. Not legal advice.

Our own code is **GPLv3** (see `LICENSE` and ADR 0001). MIT and public-domain components can be included with their notices kept; the reverse is not possible.

| Component | Use | Licence | Status |
|---|---|---|---|
| `usb-serial-for-android` (mik3y) | USB serial / CI-V | MIT | Planned: verify version and licence text when added, and that it needs no network permission |
| `ft8_lib` (kgoba) | FT8/FT4 decoder | MIT, © 2018 Kārlis Goba | **VENDORED 2026-10-14** at `core-dsp/src/main/cpp/ft8_lib/`, upstream commit `9fec6ca39886edbf96f4f5e71edc76da5074e871`. Library sources only — `demo/` and `test/` are not included. One local addition, DXpedition (0.1) decoding (section below). Licence text reproduced below and kept at `ft8_lib/LICENSE`. Decodes identically on host and phone (12 messages, corpus file 1). Needs a `stpcpy` shim for host builds, not for Android (ADR 0004). Its "SNR" is `score × 0.5` and is not an SNR; sensitivity vs WSJT-X unverified |
| **KISS FFT** (Mark Borgerding) | FFT used by `ft8_lib` | **BSD-3-Clause**, © 2003–2010 Mark Borgerding | **A separate work**, embedded in `ft8_lib/fft/`. Upstream's SPDX headers point at a `COPYING` file that `ft8_lib` does not ship, so the licence text was fetched from `mborgerding/kissfft` and vendored at `ft8_lib/fft/LICENSE.kissfft`. BSD-3-Clause is GPLv3-compatible; the copyright notices in the source headers must be retained |
| WSJT-X / JTDX algorithms | Possible reference or port for decode improvements | GPLv3 | Only with per-file provenance notes (our project is GPLv3) |
| WSJT-X **frequency table** (`models/FrequencyList.cpp`) | Source of the dial-frequency data in `docs/reference/FREQUENCIES.md` | GPLv3 | **Used 2026-10-12.** Factual frequency data read from a GPLv3 project; our project is GPLv3, so no conflict either way. Provenance recorded in that file |
| WSJT-X **sample WAV files** | Decoder test corpus (rung 1 / rung 4) | GPLv3 project; per-file terms **unverified** | **Check before committing any file.** Source: https://sourceforge.net/projects/wsjt/files/samples/FT8/ . Record each file's origin. Prefer our own IC-7300 captures where possible |
| Natural Earth (Admin-0 countries) | Offline map outlines | Public domain | **BUNDLED 2026-10-16** as `app/src/main/assets/basemap/countries-50m.bin` (405 KB) and `countries-110m.bin` (44 KB): v5.1.1 `ne_50m_admin_0_countries` and `ne_110m_admin_0_countries` from `naciscdn.org/naturalearth/`, converted by a build script (coordinates rounded to 0.01 degree; nothing else changed). Terms read first-hand on 2026-10-16 at naturalearthdata.com/about/terms-of-use: "All versions of Natural Earth raster + vector map data found on this website are in the public domain … No permission is needed to use Natural Earth. Crediting the authors is unnecessary." Credited anyway: made with Natural Earth, by Tom Patterson, Nathaniel Vaughn Kelso and contributors |
| Natural Earth (lakes, Admin-1 lines) | Map lakes and state/province lines | Public domain | **BUNDLED 2026-10-16** as `app/src/main/assets/basemap/lakes-50m.bin` (76 KB, `ne_50m_lakes` v5.0.0) and `admin1-50m.bin` (68 KB, `ne_50m_admin_1_states_provinces_lines` v5.1.0), from `naciscdn.org/naturalearth/50m/`, converted by a build script the same way. Same terms as the row above |
| Jetpack Compose, Room, AndroidX | App framework | Apache 2.0 | Planned |
| Country/entity prefix table (cty.dat, AD1C) | Country/continent/DXCC policies, map colouring | **MIT** | **Verified 2026-10-12** by the owner reading country-files.com/copyright/ in a browser (the site 403s automated fetches). Standard MIT grant, so it may be bundled in this GPLv3 app. **BUNDLED 2026-10-16** as `app/src/main/assets/cty/cty.dat`, unmodified, version `VER20260915` (346 entities, 106 119 bytes), fetched from `https://www.country-files.com/cty/cty.dat`. Read by `core-log` `CtyDat` (ADR 0012 as amended). The notice is reproduced below as the copyright page served it on 2026-10-16. **The page's copyright line reads only "Copyright © 1994-"**, with no end year or holder, so it is copied as shown and not completed by guesswork; the owner should read the line in a browser and correct it here if it shows more |

## Do not use
Binaries or code taken from other apps' APKs, including the native libraries shipped with FT8TW (`libft8tw`, `libjs8tw`): their source and licence are unknown. The FT8CN and FT8TW Java sources (MIT) may be read for ideas; copying any code needs a line in the table above.

---

## Full licence texts

Reproduced here because both licences require their notices to travel with the code, and because a table entry is a pointer, not compliance.

### ft8_lib — MIT

```
MIT License

Copyright (c) 2018 Kārlis Goba

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### KISS FFT — BSD-3-Clause

```
Copyright (c) 2003-2010 Mark Borgerding . All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

**Note on the BSD-3-Clause text:** `ft8_lib` vendors KISS FFT's sources but not its licence file, and the SPDX headers reference a `COPYING` that is therefore absent. The text above is the BSD 3-clause licence that upstream `mborgerding/kissfft` names in its own `COPYING`, with Borgerding's copyright line as it appears in the source headers. The third clause means **neither Mark Borgerding's name nor KISS FFT's may be used to promote FT8OK**.

### cty.dat (Jim Reisert AD1C, country-files.com) — MIT

Reproduced from https://www.country-files.com/copyright/ as served on 2026-10-16. The copyright line is exactly as the page shows it, incomplete (see the table above):

```
Copyright © 1994-

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Local change to ft8_lib: DXpedition (0.1) decoding

`ft8_lib` recognises the 0.1 message type but cannot unpack it. FT8OK adds
`ftx_message_decode_dxpedition` to `ft8_lib/ft8/message.c` (declared in
`message.h`, marked "FT8OK addition"), and raises `FTX_MAX_MESSAGE_LENGTH` from 35
to 52 to fit the message. The function is a port of the i3.n3 = 0.1 branch of
`unpack77` in WSJT-X's `lib/77bit/packjt77.f90` (GPLv3, © Joe Taylor K1JT and the
WSJT Development Group), read 2026-10-20 from `github.com/WSJTX/wsjtx`: field
layout, report formula and text format. No WSJT-X code was copied verbatim, since
it is Fortran. GPLv3 is FT8OK's own licence. The persistent 10-bit call table in
`ft8ok_decoder.c` follows WSJT-X's `calls10` (indexed by hash, never cleared,
newest wins).

**Encoding too (2026-10-21).** `ftx_message_encode_dxpedition`, added to the same
file and called from `ftx_message_encode` for text whose second word is `RR73;`,
is a port of WSJT-X's `pack77_01` (same source, read 2026-10-21): word checks,
`n5 = (n+30)/2` held to 0..31, the sender's 10-bit hash, and the bit layout. It is
stricter than WSJT-X: both partner calls must be plain standard calls. It is checked
bit for bit against an independent packer of that layout in a host test.

## Local change to ft8_lib: contest and WSPR decoding

`ft8_lib` recognises the ARRL Field Day (0.3/0.4), RTTY Roundup (3) and EU VHF (5)
types but does not unpack them, and does not recognise 0.6. FT8OK adds
`ftx_message_decode_contest` to `ft8_lib/ft8/message.c` (declared in `message.h`,
marked "FT8OK addition"), called from `ftx_message_decode` for any type the switch
does not handle. It is a port of the matching branches of `unpack77` in WSJT-X's
`lib/77bit/packjt77.f90` (GPLv3, © Joe Taylor K1JT and the WSJT Development Group),
read 2026-11-02 from sourceforge.net/p/wsjt/wsjtx (master): field layouts, validity
checks and text format, plus the `to_grid4`/`to_grid6`/`to_grid` grid unpacking. The
ARRL section list (`csec`, 86 entries) and the states/provinces list (`cmult`, 171
entries) are copied from the same file, in the same order. They are data, and GPLv3
is FT8OK's own licence. Checked by a host test, with payloads packed by
hand to WSJT-X's layouts and WSJT-X's own example messages (`lib/77bit/messages.txt`).

## Local change to ft8_lib: bit likelihoods

`ftx_ft8_log174` in `ft8_lib/ft8/decode.c` (declared in `decode.h`, marked "FT8OK addition") exposes the library's own likelihood step, for `ft8ok_replies.c`. The idea of expecting a partner's message is WSJT-X's a priori decoding (`lib/ft8/ft8b.f90`, GPLv3); comparing whole codewords with no decoder is ours.

## Local change to ft8_lib: FT4 start-time search

`ftx_find_candidates` in `ft8_lib/ft8/decode.c` (marked "FT8OK change") searches FT4 start times over -11..32 symbol blocks instead of upstream's -10..20, which is FT8-sized and covered only -0.48..+0.96 s at FT4's 48 ms symbols. The new range is WSJT-X's FT4 DT range of about ±1.0 s (`lib/ft4_decode.f90`, GPLv3, © Joe Taylor K1JT and the WSJT Development Group, read 2026-11-12: start offsets -344..1012 at 666.67 samples/s, `xdt = ibest/666.67 - 0.5`). Only the range was taken; FT8 is unchanged.

## Algorithm from WSJT-X: signal subtraction

`core-dsp/src/main/cpp/ft8ok_subtract.c` takes a decoded message out of the audio, by the method of WSJT-X's `lib/ft8/subtractft8.f90` and `lib/ft8/ft8_downsample.f90` (GPLv3, © Joe Taylor K1JT and the WSJT Development Group), read 2026-11-07 from `sourceforge.net/p/wsjt/wsjtx`. It is new C written from that method, not a translation: the filter is two box filters at 400 Hz where WSJT-X applies a cos² window by FFT, and the search for frequency and time is our own. Our project is GPLv3. The waveform follows `ft8_lib`'s `synth_gfsk` (MIT).

## Ported algorithm: GFSK synthesis

`core-dsp/src/main/java/com/ft8ok/dsp/Ft8Modulator.kt` is a Kotlin port of
`gfsk_pulse` and `synth_gfsk` from `ft8_lib`'s `demo/gen_ft8.c` (MIT, Karlis Goba),
with the constants `FT8_SYMBOL_BT = 2.0` and `GFSK_CONST_K = 5.336446` taken from
the same file.

Ported rather than called through JNI so the transmitted waveform can be tested on
the JVM — this is the only code in the app whose output other people hear. The
error function it needs is Abramowitz and Stegun 7.1.26, since the Kotlin standard
library has no `erf`.
