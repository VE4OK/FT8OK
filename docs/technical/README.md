# FT8OK technical guide

What the app actually does, in operating terms: when each thing happens, what it assumes and where it can be wrong. It was written from the code as of November 2026. The numbers are the ones the app runs on.

## Parts

| # | Topic | Answers |
|---|---|---|
| 1 | [One 15-second slot](01-one-slot.md) | What happens at each second of a slot? |
| 2 | [Decoding](02-decoding.md) | In what order are signals found? How does subtraction work? What is a priori doing? Which messages are understood? |
| 3 | [Time](03-time.md) | Where does the clock come from? What if the phone is wrong? |
| 4 | [When TX goes](04-when-tx-goes.md) | How is the decision made? What are the late-start and stop limits? |
| 5 | [Reports](05-reports.md) | How is the dB number measured? |
| 6 | [Choosing a TX frequency](06-tx-frequency.md) | How does Auto pick a spot, and when does it move? |
| 7 | [The exchange](07-exchange.md) | What gets sent in reply to what? When does the app give up? |
| 8 | [CQ runs and combined messages](08-cq-runs.md) | Who gets answered next? When does a DXpedition-style message go out? |
| 9 | [Contest modes](09-contests.md) | What changes in Grid, Field Day and Rpt+grid modes? |
| 10 | [Radio, safety and logging](10-radio-safety-logging.md) | What must be true before keying? When is a contact logged? |
| 11 | [Known limits](11-known-limits.md) | What isn't built, known, or proven? |
| 12 | [FT4OK](12-ft4ok.md) | How does the FT4 app differ, and which numbers change? |

Parts 1-11 describe FT8OK and use FT8's numbers (15-second slots). FT4OK works the same way with FT4's, listed in part 12.

## How far each statement has been proven

Many behaviours have been proven at different levels, so this guide says which:

| Label | Meaning |
|---|---|
| **On air** | Seen working on a real radio with real stations, or on the phone in real use |
| **Bench** | Passed automated tests or replays of real recordings, but not seen on the air |
| **Code** | Read from the code. This is what it should do, with no separate proof |
| **Trial** | A working default whose value may change after more operating |

## Terms

- **Slot:** one 15-second period. Slots start at :00, :15, :30 and :45.
- **EVEN / ODD:** EVEN slots start at :00 and :30, ODD at :15 and :45. Two stations in a contact use opposite ones.
- **Band time:** the slot grid the stations on the band are actually using, which may differ from any phone's clock.
- **DT:** how far from an on-time start a signal arrived, in seconds.
- **Partner:** the one station you're currently working. S&P and CQ both use this same slot. In S&P you fill it with a tap, and in a CQ run the app fills it.
- **ARM:** permission to transmit. Nothing goes out without it.
- **Dock:** the bar with ARM, CQ, HALT and the message picks, present on every tab.
- **Lane:** the EVEN or ODD row of the occupancy strip.
- **Dial frequency:** the radio's display. **Audio frequency:** where in the passband a signal sits (for example 1500 Hz).
