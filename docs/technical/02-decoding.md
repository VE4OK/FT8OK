# 2. Decoding

The decoder is [ft8_lib](https://github.com/kgoba/ft8_lib), running as native code on the phone, with additions for subtraction and more message types.

## The first look

For each pass:

1. Search 0 to 3100 Hz, and from about -1.6 to +3.0 s of time offset, for FT8's sync pattern.
2. Rank the candidates by sync strength, strongest first, and keep up to 140.
3. Try to decode each with LDPC error correction (25 iterations), up to 100 messages. A message must pass its 14-bit CRC.

This takes about 22-38 ms on a normal band on the test phones. **On air.**

**Deliberately missing here:** the deeper error-correction search (OSD) that WSJT-X uses. A priori works differently here (below). FT8OK's sensitivity has not been measured against WSJT-X, so we don't claim either way.

## The second look: subtraction

A strong signal can hide a weak one on nearly the same frequency. Once a message has been decoded, we know exactly what was sent, so it can be rebuilt and removed from the audio.

For each decoded message, strongest first:

1. Rebuild its waveform from the decoded symbols.
2. Line it up with the audio in time (within 80 ms) and frequency (within 4 Hz, then a finer step).
3. Follow its amplitude and phase through the transmission, with a smoothing of about 0.17 s, so fading is tracked.
4. Subtract it.

The method follows WSJT-X's subtraction. The decoder then looks again. Up to three rounds run, each removing whatever the last one found. A round that finds nothing ends the process.

### Why it can stop early

The whole second look must finish by **15.2 s**, or the next transmission would have to go without it. The app checks the time before each removal, and won't start another look unless the previous one's duration still fits. On a slow phone this means less depth, not a late slot. An Android 10 phone hit the limit in most busy slots and still gained about 10 messages a slot. There is no decode-depth setting; this budget does that job (**Trial**).

### Results

| Test | Result | Proven |
|---|---|---|
| Busy 20 m recording, 9 slots | 192 messages became 255, 267 and 271 with 1, 2 and 3 rounds | Bench |
| Pure noise, 20 slots | 0 invented messages | Bench |
| Hidden signal 18 dB under a stronger one | 0 of 10 before, 10 of 10 after | Bench |
| Live on 14.074 MHz | +37% to +47% over the first look; worst case 386 ms; latest finish 15.22 s | On air |

At most about 4% of the extra messages on the recording could not be confirmed, a small sample. Not yet seen: a contact with a station only subtraction found.

## Duplicates

A message counts once per slot, in the pass that first found it, using the text and a 10 Hz frequency bucket. Being a bucket, the same message found either side of a bucket edge shows as two rows about 3 Hz apart. This has been seen occasionally and is not fixed.

## A priori: the partner's possible replies

FT8's strongest trick for weak signals is knowing what to expect. If you're in a contact, your partner's next message is one of only about 126 possibilities: RRR, 73, RR73 or a report from -30 to +30, with or without R, plus their grid forms if we know it.

Each FT8 message is sent as 174 bits. Right after the second look, FT8OK builds the 174 bits of every one of those messages and compares them, bit by bit, with the audio left at the partner's last frequency and time (15 Hz and 0.4 s either way). The closest one wins. Testing a list always returns a *closest* match, so the danger is believing a match in noise, or mistaking a message to someone else for one to us. So a match counts as heard only if **all** of these hold (**Trial**, since 2026-11-09):

- the normal decoder and subtraction did not hear the partner at all in that slot;
- at most **35** of the 174 bits disagree (a chosen threshold);
- a "decoy" test, the same with a fake callsign in place of ours, disagrees in more than 35;
- no other message to us was decoded within 60 Hz (another caller's reply to us there came within 40 on the bench);
- it is a message from the partner to us, and both calls are plain (as in WSJT-X).

A reply found this way is shown with an asterisk after the message, `VE4OK IV3XAA R-12 *`, and from there is treated like any other decode: the contact moves on, and a found `RR73` or `73` completes it. Its report is measured on the cleaned audio, or -24 when nothing there can be measured. Any other decode the spectrum cannot measure (about 1 in 100, mostly weak ones found by subtraction) shows `-` for its report, and the exchange treats it as -24 (**Code**, rev 90). `decodes.log` marks it `list` in the pass column, and `events.log` gets a `reply list: heard ...` line.

| Test | Result | Proven |
|---|---|---|
| Silent partner: each decoded station tested as our partner, 356 saved slots, 2 phones, 40/20/17 m | 0 false matches in 22,226 tests; closest 40 | Bench |
| Partner too weak to decode, sending to someone else, put into another slot | 0 false in 5,487 stations; closest 40 | Bench |
| Another station's real reply to us at a silent partner's spot | 0 false in 9,319 tests; closest 40 | Bench |
| Real replies to us, decodable | 90 of 93 returned as themselves; the 3 wrong ones scored 60-65 | Bench |
| Not-heard slots on air (log only), 2026-11-09 | 10 of 132 matched at 38 or under, 6 agreeing with the slot before or after | On air |

The first one on air, 2026-11-10: VE3XAX's `+08` on 40 m, missed by the normal decoder in a busy slot and found with 12 of 174 bits wrong. The operator tapped the starred row, and VE3XAX's `RR73` (decoded normally) confirmed they had sent us a report. That miss was not a missing candidate: the decoder found the signal but its error correction did not converge, which WSJT-X follows with a second method (OSD) that this decoder lacks (**Bench**, one slot).

**Not yet seen:** a contact moved on, or completed, by a found reply with the app armed. The safety figures come from saved slots of two phones, so the first weeks on air are the real test.

## Messages understood

| Type | Example | Proven |
|---|---|---|
| Standard, including /R and /P | `W1AW VE4OK FN25` | On air |
| Free text (13 characters) | `TNX 73 GL` | On air |
| DXpedition combined (0.1) | `K1ABC RR73; W9XYZ <KH1/KH7Z> -12` | On air |
| Non-standard calls (type 4) | `CQ PJ4/K1ABC` | On air, partly |
| ARRL Field Day (0.3, 0.4) | `WA9XYZ KA1ABC R 16A EMA` | Bench |
| RTTY Roundup (3) | `TU; W9XYZ K1ABC R 579 MA` | Bench |
| EU VHF (5) | `<PA3XYZ> <G4ABC/P> R 590003 IO91NP` | Bench |
| WSPR-in-FT8 (0.6), telemetry (0.5) | | Bench / library |

**Everything WSJT-X displays is displayed.** Types WSJT-X treats as undefined (0.2, 0.7, i3 of 6 or 7) are hidden, because one that passes the CRC is far more likely to be a false decode.

## Callsign hashes

Long or portable calls are often sent as a hash, shown in `<angle brackets>`. To name one, the app must have heard that call in full earlier. It remembers calls for as long as the app runs, and knows our own from the start. An unknown hash shows as `<...>` and is never answered.

There is an accepted risk, the same as in WSJT-X: about 1 in 4,096 other calls share our 12-bit hash and would read as addressed to us.
