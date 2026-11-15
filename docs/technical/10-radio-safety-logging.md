# 10. Radio, safety and logging

## Connecting to the radio

One USB cable gives the phone a serial port (CI-V) and a sound card, both inside the IC-7300.

At connect the app tries 115200, 19200, 38400, 57600, 9600 and 4800 baud, at the radio's address 94h and then the broadcast address. A radio that doesn't answer takes about 5 seconds to give up on. It then asks for frequency, mode, data mode and the SSB-D transmit passband.

The radio doesn't announce changes made over CI-V, so **every field the app sets is read back**:

| Item | Read |
|---|---|
| Frequency | At connect, after each tune (up to 3 tries), every 10 s |
| Mode | At connect, after each tune or banner tap, every 10 s |
| Data mode | Same, as a mode report doesn't include it |
| Transmit passband | Once per connect (needs firmware 1.30 or later, else 300-2700 Hz is assumed) |

The 10-second poll never runs while keyed. Changes made on the radio's front panel are announced by the radio.

Only the top-bar frequency picker can tune the radio, and an automated test enforces that. A tune is refused while keyed.

If the USB link fails without a detach, the app might not notice until the app leaves the screen or the cable is unplugged. **Code.**

## What must be true before keying

Checked in this order, on every transmission, including the TX test:

1. **ARM** is on. It's off at every start, and nothing turns it on automatically.
2. **Your callsign and grid** are valid.
3. **A radio** is connected and its dial is known.
4. **An allowed frequency:** on the FT8 table (including DXpedition alternates) within 200 Hz, or one of your custom frequencies.
5. **USB with data on,** confirmed by the radio. If not, a banner offers a fix, on your tap only.
6. The right parity, within the time limits.

## While transmitting

| Protection | Behaviour |
|---|---|
| **Hard stop** | Ends at 14.7 s |
| **SWR abort** | Checked every 400 ms. Over your limit (1.5 to 3.0, default 2.0) it fades and unkeys |
| **PTT watchdog** | Independent thread, releases PTT after 15 s without a refresh. Extended by 2 s with each audio block, never past 20 s |
| **Foreground** | Leaving the app disarms and disconnects |

**After an SWR abort the app stays armed.** The message tries again in the next slot of your parity, and the attempt counts as an unanswered try. If you're seeing SWR aborts, press HALT and check the antenna.

The PTT watchdog also releases PTT without disarming.

## Drive level

The app outputs at -11 dBFS, measured as linear on the development radio, and pins Android's media volume to maximum for the duration. If a phone refuses that volume change, the app logs a warning and transmits anyway. Use the radio's RF POWER control to set output.

**Any other sound on the phone can reach the modulator** because Android routes default audio to the radio's USB codec. Use Do Not Disturb. The app itself makes no sounds.

## TX test

Settings › Radio has a HOLD TO TX button. It keys only after a finger has stayed on it for 0.5 s (it shows HOLD… while waiting), then transmits while the finger stays down, using the same guards, at 1500 Hz. A scroll of the page that starts on it is cancelled within the wait, so it never keys. **Code**. Use a dummy load.

## Band changes

Any change of dial frequency, even on the same band:

- disarms;
- clears the partner, armed or not, and stops a CQ run;
- drops the slot it happened in and the occupancy maps;
- dims older feed rows behind a `— now on 17m —` line and locks them.

Roster and Map show the current band only.

Losing the CAT link isn't a band change. Reconnecting on the same frequency changes nothing.

## Automatic logging

A contact is logged **once**, when reports have been exchanged in both directions and it has been closed, at whichever of these comes first:

1. Their RR73, 73 or RRR is decoded (even if disarmed).
2. Our RR73 or 73 finishes going out cleanly.
3. A combined message closing that contact finishes going out cleanly.

A close cut short by HALT, an SWR abort or a disarm doesn't log. Those contacts go to Not logged.

### What's recorded

Call, start and end time, dial frequency, band, FT8, your call and grid, their grid, both reports, and contest fields.

- **Start time** is when you tapped, or when a caller first called.
- **End time** is the last time we heard them.
- **Their grid** comes from the exchange, or else what they most recently sent in the past hour.

### Fixing the log

- **Undo** is offered for 20 seconds.
- **Not logged** at the top of the Log tab lists contacts where both reports really went out but no entry exists, each with a LOG button. It's in memory: one hour, up to 20 entries, gone when the app closes.
- **Edit or delete** by tapping a log row. Delete has Undo.

### Files

The app's database is the source of truth. After each contact, an ADIF file is rewritten in the folder you chose in Settings. If writing fails, the contact is still logged, a banner appears and the next good write catches up.

Choose opens the folder picker on Download while no folder is set (on the current folder after that). Make a folder there, such as `Download/FT8OK`, and use it: a website's upload button opens the same system picker, where Downloads is one tap away. Android 11 and later refuse Download itself, only a folder inside it. Where the picker opens is only a hint, and some phones may ignore it (Code).

- **Logbooks:** tap the name in the Log header to switch, create one, or import ADIF into the current one. There's no rename or delete of a whole logbook.
- **Import** skips records already there (same call, band and start minute) and rejects non-ASCII ones.
- **SAVE COPY** writes a one-off file wherever you choose.

The database isn't backed up by Android and is deleted if the app is uninstalled. Use the ADIF folder.
