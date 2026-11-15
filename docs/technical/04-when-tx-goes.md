# 4. When TX goes

## The timing limits

| Limit | Value | What it means |
|---|---|---|
| Nominal start | 0.5 s | When a correct signal begins |
| Signal length | 12.64 s | 79 symbols |
| Nominal end | 13.14 s | |
| Late shift limit | 1.5 s behind the band | A late message goes out whole up to here |
| Latest decision | 4.0 s | After this, we don't transmit in this slot |
| Hard stop | 14.7 s | Transmission ends no matter what |
| PTT watchdog | 15 s | Independent thread releases PTT if all else fails |

There's no user setting for any of these. They live in one place per mode, because FT4's slot is half as long and FT8's limits would be wrong there.

## How the decision is made

A dedicated high-priority timer runs every **50 ms**, separately from audio, so a receive stall can't cost a transmit slot. On each tick:

1. Do we have a parity to transmit in? This is set by the contact, the CQ run, or a queued text.
2. Have we already transmitted this slot, or are we transmitting now? Stop.
3. **Is the last slot's result in yet?** If not, and we're under 0.3 s past the boundary, wait for the next tick.
4. If a limit has been reached and an ending was being held, resolve it.
5. Final checks, in order: armed, profile valid, message present, dial known, dial allowed, correct parity, not past 4.0 s.

Then the last gate: the radio has confirmed USB-D, audio output exists, and the message can be encoded.

If the check at step 3 runs out of time, the app decides with what it has, and a reply that lands after that **waits for our next slot**. The same applies to a reply first found by subtraction at the very end of the budget. Automatically restarting in that case was considered and declined for now.

### What goes out, in priority order

1. Queued free text.
2. The contact's next message.
3. Our CQ, if running with no contact.

### Which slot we use

- **Answering anyone:** the opposite of the last slot they were heard in. If a partner changes sides, we follow them. A tap uses the station's *newest* parity, not that of the row tapped. **On air.**
- **CQ:** the slot coming next at the moment you press it.

## When we're late

Late means the decision came after the on-time start, most often because you tapped late. Two limits apply.

**Up to 1.5 s behind the band:** the whole message goes out, late.

**Later, up to the 4.0 s limit:** the message is positioned as if it had started 1.5 s late, and the part that would already have gone is left out. The receiver sees a 1.5 s late signal with its first portion missing, with a 10 ms fade-in.

The maximum loss is about 2 s out of 12.64 s, roughly 16% of the message (arithmetic). FT8's error correction handles many cases like this.

**Why 1.5 s?** WSJT-X searches only about ±2.5 s for sync and a +2.7 s call went unanswered. A message that keeps sliding later would also run past the end of the slot, on top of whoever answers. On a bench test at -17 dB, starting 3 s late, this hybrid decoded 55 of 60, cutting alone 13 and sending whole 0. On air, cut starts landed at +1.44 to +1.50 s and a contact completed.

The 1.5 s counts from the *band's* time. If a partner shift has us already 0.3 s late on purpose, only 1.2 s remain.

## Last-moment changes

A dock pick or AUTO made **during our own transmission**, before 3.0 s, restarts it with the new message in the same slot. After that, it waits for our next slot. **Bench.**

## The end of a transmission

The audio is allowed to finish playing before PTT is released, up to the measured output delay plus 300 ms. If stopped early, it fades out over 20 ms, then goes silent, then unkeys, so the transmission isn't cut at full tone. These were added after finding every transmission had been cut about 0.2 s short. **Code**; the result hasn't been confirmed on a scope since.

## ARMED, HALT and disarm

| Action | Current transmission | Queue |
|---|---|---|
| Press **ARMED** | Finishes | Kept; ARM resumes |
| Press **HALT** | Stops now | Cleared, CQ stops, partner kept as paused |
| Automatic disarm | Stops now | Kept |

Automatic disarms: contact complete (S&P), gave up with no answer, unanswered CQs in a row (Settings › CQ PREFERENCES: Off, 5, 10, 20 or 40; 10 by default; Off never stops), frequency changed, app left the screen, radio disconnected, radio left USB-D. Free text sent alone also disarms, so ARM sends it again.

## A known timing issue

With a partner shift that moves us *earlier*, silence already queued in the warm stream can't be taken back. Some of those starts have gone out 60-234 ms later than intended, and in one case 320 ms. The fix would be a shallower queue, which raises the risk of the audio running dry.

A partner more than about 0.15 s early can't be met at all. What we send depends on the partner's last message, so the decision waits for the slot's final decode pass, up to 300 ms past the boundary. Measured in band time, it was made at a median of +0.20 to +0.24 s on two phones, and the warm stream adds another 50-130 ms. Such a start goes out at once, which lands close to the band's +0.5 s. Partners 0.33-0.49 s early therefore got us about 0.5 s late by their own timing, and those contacts still completed (**On air**, measured with a remote receiver). The decision is kept this way on purpose: the latest message carries the most context.

Against a remote receiver, starts the app made on time land within about 0.1 s of where it predicts on two phones and 0.16 s late on a third. Starts decided after their target land about 0.1 s later than the app's own "signal left the phone" figure on two of the three (**On air**, provisional).
