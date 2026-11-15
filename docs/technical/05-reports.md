# 5. Reports

The report is defined the same way as WSJT-X's: signal power compared with noise power in a 2500 Hz bandwidth.

## How one is measured

1. The slot's audio is turned into an average spectrum with about 5.86 Hz resolution.
2. **The noise floor** comes from 500-3000 Hz. The app ranks those frequency bins and keeps only the ones within 30 dB of the 90th percentile, so frequencies the radio's filter has cut don't drag the result down. The floor is the 25th percentile of what's left.
3. **Signal power** is the power in the 50 Hz occupied by the signal, minus the noise expected there.
4. **Duty correction:** the signal may have filled only part of the audio analysed, particularly at the 11.8 s pass, so the figure is scaled by the actual overlap.
5. Report = signal compared with the noise in 2500 Hz.

If signal minus noise comes to zero or less, the row shows `--` and no report can be sent from it.

The report we send is rounded and never below **-25 dB**, matching WSJT-X.

## Rules

**Measured once.** A row's report arrives with the row, and a later pass never revises it.

**Stations found by subtraction** are measured on the audio from the look that found them: with that station still in it and its neighbours removed. Measured on the original audio, such a station read up to 17 dB too high. Measured this way, a test set of known levels came within 0.6 dB. **Bench.**

**The report goes out fresh.** Each time a partner is heard, their report is re-measured. If a dock button would need a report we haven't measured, it's disabled, so a report is never made up.

**Combined messages carry even numbers only.** The format has room for -30 to +32 in steps of 2. An odd value goes one lower: -13 is sent as -14, and that's what is logged.

## Known weaknesses

- Signals over 30 dB above the noise across a tenth of the band would raise the floor. Not seen.
- A row decoded beside a neighbour, on the first look, reads about 1 dB high in about one case in nine.
- Sent reports have not yet been compared in a controlled way with what partners report for the same signals.
