# 11. Known limits

## Out of scope, for now or by design

- **IC-7300 only.** The radio interface is separate, but this is the only implementation.
- **One mode per app.** FT8OK is FT8 and FT4OK is FT4 ([part 12](12-ft4ok.md)); neither switches.
- **No network, by design:** no PSK Reporter, spots, online lookups or log uploads. The country data is whatever shipped with the app.
- **Foreground only, by design:** leaving the app or locking the phone stops receive and disarms.
- **Portrait only.** No tablet layout.
- **Grid square by hand,** never from GPS.
- **English only.**
- **POTA and SOTA:** `CQ POTA` and `CQ SOTA` are built in, but there's nowhere yet to enter your own park or summit reference, so the log doesn't record it. Add it in your logging program before uploading.
- No Fox/Hound or SuperFox modes.
- No Roundup or EU VHF sending, serial numbers, Cabrillo or scoring.
- No rename or delete of a logbook.

## Not yet released

No signed build, no app-store listing. Only debug builds have run on phones.

Tested on phones running **Android 10, 13 and 15**, all 64-bit ARM. 32-bit and x86 builds are compiled but untested, and so are tablets.

## Known problems

| Problem | State |
|---|---|
| About 10 s receive-audio dropouts, sometimes just after a transmission | Cause unknown |
| A phone clock jump mid-session misplaces slot cuts | Found in code, not fixed |
| TX isn't blocked while the clock is unsynced | Undecided |
| Early partner shift can make a warm start late; a partner more than ~0.15 s early can't be met | Known, kept on purpose (the decision waits for the final pass) |
| A phone refusing the volume pin doesn't stop TX | Undecided |
| Other apps' sounds can reach the radio | Use Do Not Disturb |
| The phone can't charge while the radio is on its USB port | Powered hub untested |
| Duplicate rows about 3 Hz apart | Occasional, not fixed |
| A failed or refused transmission counts as a try | Code behaviour |
| A CQ-run station that repeats its R-report after being closed could be logged a second time | Inferred from code, not seen |
| FT4: the decoder loses some signals starting 0.30-0.45 s late | Bench, not investigated |
| FT4OK on a slower phone may decide before the partner's last decodes arrive | Tested only on Android 15 |

## Not yet proven

- Decoding sensitivity against WSJT-X.
- Recovery on the phone from a deliberately wrong clock.
- The radio's own USB-to-RF delay.
- Our Field Day exchange, or custom CQ word, decoded by other software.
- A completed contact with a non-standard call.
- JTDX and MSHV as receivers of a combined message.
- A contact with a station only subtraction found.
- A dock pick held against a repeating partner, or restarting our own transmission.
- A queued close going out after the partner moved on.
- The soft end of a transmission, on a scope.
- The quietest-spot fallback on a full band.
- Transmitting while the app is sent to the background.
- FT4OK: a late start decoded and answered; the grid-search values; a real contest.

## Assumptions worth questioning

- **The band is right.** Consensus of 3+ stations defines "on time". If most stations are off the same way, so are we. Log times still come from the phone.
- **A late partner has a slow clock,** not audio delay ([Time](03-time.md)).
- **Strong signals don't bias the noise floor** ([Reports](05-reports.md)).
- **The widest gap is the best one.** It may not be, particularly at the band's low end.
- **A partner's R-report to another station means they've moved on.** A CQ run acts on this. Search and pounce keeps calling.
