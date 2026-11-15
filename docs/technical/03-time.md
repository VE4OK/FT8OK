# 3. Time

FT8OK has no NTP or GPS code. Its timing comes from the stations it hears. There are two separate clocks that are never merged.

## The listen clock

This answers "where is the slot boundary on this band?"

At each closing update, every decode's DT goes into an estimate:

1. At least **3 stations** are needed.
2. Take the median of their DT, weighted by the decoder's sync strength, so clean signals count for more.
3. Drop anything over **0.5 s** from that median. Still need 3.
4. The difference between that and an on-time start is the clock error.

The first correction is applied all at once. After that, each slot can move the clock by only **200 ms**, so one odd slot can't throw it off.

One correction is built in: the decoder reads an on-time signal 0.15 s late (measured against a reference over 936 decodes), and this is subtracted. An on-time station therefore shows DT 0.0 in the feed, as in WSJT-X.

### What the top bar tells you

| State | Meaning | Display |
|---|---|---|
| None | Never synced, using the phone's clock | `phone` |
| Tentative | Grid found from only 1-2 stations; still searching | Offset, amber dot |
| Synced | Recent agreement of 3+ stations | Offset, normal dot, `band +0.02` |
| Holding | Nothing fresh for 120 s, or the phone's clock stepped | Offset, amber dot |

**On air:** a phone with network time on was 1.66 s fast at first, and the correction stayed near -1.1 s for days. A second receiver put our transmissions within ±0.16 s of the band's median.

## Finding the grid from nothing

If the phone is more than about 2.5 s out, nothing decodes, so the normal method has no data. (Measured range of decoding: clock -2.1 to +2.4 s off.)

While not locked, after each slot the app decodes **five windows of old audio spaced 3 s apart**. One of them will fall close to the true grid. Each decoded message is a vote for where the boundary is. Votes are kept for 60 seconds and grouped within 0.5 s.

- 3 or more stations agree: **Synced**.
- 1 or 2: **Tentative**, and it keeps searching. Only a bigger group can replace it.
- After 4 fruitless searches, a banner says no stations have been heard and the clock may be off.

The search doesn't run during a contact or within 27 s of our last transmission.

**Bench:** on four real recordings, the grid was found within 2 slots from every starting point, with no false lock on noise or a dead band. **On air:** from a phone about 1 s off, on a band with 1-2 decodes a slot, it went tentative then synced in 2 slots, costing about 104 ms.

**Not yet tested:** a phone with its clock set deliberately wrong by hand.

## What the band can't tell you

The band gives the **phase** of the grid, not the time of day. If a phone's clock is wrong by a whole number of slots or more:

- contacts still work, since parity comes from when stations were actually heard on our own numbering;
- **logged times will be wrong**;
- the EVEN and ODD labels may be swapped compared with everyone else's.

Keep the phone's date and time about right for the sake of the log.

The app currently doesn't refuse to transmit while unsynced. Whether it should warn or refuse is undecided.

## The transmit clock

During a contact, the app starts its transmissions on your **partner's** timing as well as the band's. If they read 0.3 s late, we transmit 0.3 s late too, so we land where their receiver expects.

- Uses the median of their last 3 readings. Readings over 2.5 s off are ignored. Limited to **±1 s**.
- Applies to that station only. Between contacts the shift is zero.
- Shown as `TX +0.07` in the top bar.

**The assumption:** a partner who reads late has a slow clock. If they read late because of their own audio delay, band time would be better. Nothing on air can tell these apart.

## Output delay

Between "write audio" and "audio leaves the phone" there is a delay of 100-430 ms on a cold start. The app measures it from the audio hardware's timestamp and pads silence so the signal leaves on target. The warm stream has a delay of 110-130 ms and typically lands within a few milliseconds of target. **On air.**

**Not measured:** the radio's own delay from USB audio to RF. Nothing corrects for it, although the second-receiver checks above include it.

**On air, provisional (2026-11-11, 17 m, two remote receivers):** against the other stations in the same slot, one phone (Android 13) landed 0.12-0.16 s after the time it aimed for. A second phone (Android 15) on the same radio landed 0.04 s after on clean starts. So the larger part is inferred to come from the phone, not the radio. Both phones were within 1 Hz of the intended frequency. These results need a repeat with a GPS- or NTP-disciplined receiver before they can be relied on.

## Known problem

If the phone's clock jumps by over 1 s during a session, the app's timing stays right but the audio buffer remains anchored to the old clock, so each slot is then cut in the wrong place. A jump over about 2.4 s means nothing decodes until the app restarts. Found by reading the code, not seen on air, not yet fixed.
