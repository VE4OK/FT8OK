# 6. Choosing a TX frequency

## Where we can go

- **Receive and display:** 0-3100 Hz.
- **Transmit:** inside the radio's SSB-D transmit passband, which the app reads over CI-V once per connect. If the radio doesn't answer, it assumes 300-2700 Hz. Audio outside that passband can key the radio with no power out (seen at 2838 Hz). The whole 50 Hz signal must fit.

## The map behind it

There's one map for each lane, EVEN and ODD. Each is rebuilt at the closing update, and never from a slot we were transmitting in. A map older than 60 seconds isn't used.

A frequency is marked busy if it's at least **6 dB above the floor**, or it's inside any decoded station's 50 Hz. Whatever isn't busy forms the gaps, with **5 Hz** of guard on each side. A usable gap must be at least 60 Hz wide. A candidate is the middle of a gap, widest gaps first.

## How Auto chooses

Each lane has its own pick, judged only against its own map.

**First pick:** the first candidate that's "comfortable" (nothing at 12 dB or more within 100 Hz of our centre), or else the widest candidate, or else the quietest spot.

**Staying put:** Auto holds its spot unless something appears on it at **9 dB** or more (**Trial**, previously 12) or the spot falls outside the transmit passband.

**Moving:** to the nearest comfortable candidate, or the nearest candidate, or the quietest spot.

**Quietest spot:** when no gap exists anywhere, the app scans in 5 Hz steps and picks the lowest peak. It used to give up and sit at 1500 Hz. **Bench**; not yet seen on a truly full band.

## During a contact or CQ run

The lane we're transmitting in **holds still**, since your partner is listening there. The other lane keeps choosing.

## Set by hand

Tap a lane to set a frequency by hand. It snaps to the nearest gap in that lane and is used for both lanes. A tap on either side cell of the lane you transmit in returns to Auto.

## Things to know

- Auto often lands low in the band (around 500-700 Hz), since that's often where the widest gap is. Inferred; worth watching.
- **With a narrow receive filter, Auto may choose a spot outside it**, where you'd transmit but not hear answers. Not fixed.
- The app can't see a station on our own frequency in the same parity, because we're transmitting at the time.
- After a band change, picks are cleared. Until a slot has been heard, TX falls back to 1500 Hz.

Each change is logged with its reason. Auto's behaviour on air: first picks, moves, back to Auto, Set on both lanes and a band change have all been seen working.
