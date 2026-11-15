# 1. One 15-second slot

Everything in FT8OK is driven by the slot. Times below are seconds after the slot boundary, on the app's corrected clock ([band time](03-time.md)).

| Time | What happens |
|---|---|
| Continuous | The radio's USB audio is captured at 48 kHz, reduced to 12 kHz and kept in a 40-second buffer. Each sample's time comes from the audio hardware's timestamp, so a slot is cut out by sample count and isn't affected by when Android happens to deliver the data. |
| About -1.0 | If ARMED with a message for this slot, the audio output is opened and starts running on silence. The radio isn't keyed yet. This is the "warm stream", and it makes the start accurate. |
| 0.0 | Slot boundary. The app chooses what to send (it can wait up to 0.3 s for the previous slot's final decodes). See [When TX goes](04-when-tx-goes.md). |
| 0.5 | Nominal signal start for every correct station, including us (plus any partner shift). |
| 11.8 | **First decode pass.** Finds strong signals, even though they haven't finished. Rows appear in the feed with their reports. |
| 13.14 | A correctly timed transmission ends. |
| 13.5 | **Second pass.** Adds new messages only. |
| 14.7 | **Third pass.** The full audio. New rows are posted right away. Also the hard stop for our own transmission. |
| After 14.7 | **Second look: subtraction.** Removes known signals and looks again, until 15.2 s at the latest. |
| Right after the second look | **A priori:** if we have a partner who wasn't heard, their possible replies are tested against what is left (about 15 ms). See [Decoding](02-decoding.md#a-priori-the-partners-possible-replies). |
| About 14.8 to 15.2 | **Closing update.** The slot is complete. Now, and only now, the band's timing is measured, the occupancy map is updated, and the contact engine sees what was heard. |
| After closing | Background work nothing waits on: the a priori log line and saved audio, and the grid search if the clock isn't locked. |

## Two things worth knowing

**The feed and the engine see a slot at different times.** A reply can appear on screen at 11.8 s, but the app only acts on it at the closing update. Earlier passes are for you to read. This way, a single complete view of the slot decides what to send next.

**Each pass decodes from the start.** The first three passes decode the same audio from the slot boundary, each with a little more on the end. A message found on an earlier pass is not shown twice. Only the second look alters the audio.

## How late can decoding finish?

The final update has been measured arriving as late as 15.22 s, which is 0.22 s into the next slot. On 3,123 slots before subtraction was added, the median was 14.79 s and the latest 15.1 s. **On air.**

The transmit decision waits up to 0.3 s past the boundary for it, which is why subtraction must stop by 15.2 s: the budget is set by the 0.3 s wait, less a 0.1 s margin.

## What can lose a slot

- A band change during the slot. Its decodes are dropped, because some of the audio belongs to another band.
- A receive gap of about 10 seconds. These occur occasionally, sometimes soon after a transmission, and the cause is still unknown.
- The first slot after receive starts, if audio capture began after the slot boundary.
- Transmitting. We can't hear during our own slot, and that slot doesn't update the occupancy map.
