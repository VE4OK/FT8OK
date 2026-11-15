# 9. Contest modes

Set up in **Settings › Contest: Off, Grid, Field Day or Rpt+grid**. One setting covers CQ, answering, S&P and CALL, and it survives a restart. A green `Contest` line on the CQ button tells you it's on.

> **Check the rules.** Some contests restrict automated contacts. The app's sequencing and CQ run behave as they do in normal operating, and it's your job to use them within your contest's rules.

**Each step follows the setting as it is now,** including a contact already under way. An earlier rule kept each contact in the format it started in, and it trapped a real contact on air, so switching modes is now your way out.

## What you can set

| Mode | Settings | Refused if |
|---|---|---|
| Grid | CQ word: TEST (NA VHF, ARRL Digi), WW (WW Digi), or Custom. The exchange is your 4-character grid | No grid; bad word |
| Field Day | Class (1-32 transmitters + A-F, like `2A`) and ARRL/RAC section | Class or section not valid |
| Rpt+grid | CQ word: none (a plain `CQ`), TEST, or Custom. The exchange is the ordinary report and grid | No grid; bad word |

Custom words are 1-4 letters or exactly 3 digits, not `FD`. Four digits can't be sent. In Grid and Field Day your call must be plain: no `/P` or prefix. Rpt+grid sends ordinary messages, so it takes any call the app can send.

**Rpt+grid** is for contests whose exchange is an ordinary contact: the International FT Challenge, DARC FT4, Africa FT4 DX, FT4 Field Day and the RSGB FT4 events. The messages are exactly those of normal operating. What contest mode adds is everything else on this page: no combined messages (the FT Challenge prohibits DXpedition-style multi-answering), dupes marked and skipped by the CQ run, the CQ word, and the contest field in the log. **Code** and **Bench**; not yet used in a contest.

If your settings later stop being valid (you change your call, say), the app sends standard messages, the `Contest` line disappears and Settings says why.

## The sequences

### Field Day

```
K1ABC  CQ FD K1ABC FN42
we     K1ABC W9XYZ 6A WI          the grid message is skipped
K1ABC  W9XYZ K1ABC R 2B EMA
we     K1ABC W9XYZ RR73           logged when sent
K1ABC  W9XYZ K1ABC 73             no 73 back
```

### Grid

```
K1ABC  CQ TEST K1ABC FN42
we     K1ABC W9XYZ EN37           same text as an ordinary call
K1ABC  W9XYZ K1ABC R FN42
we     K1ABC W9XYZ RR73
```

### Rpt+grid

```
K1ABC  CQ K1ABC FN42              or CQ TEST
we     K1ABC W9XYZ EN37           as in normal operating
K1ABC  W9XYZ K1ABC -12
we     K1ABC W9XYZ R-08
K1ABC  W9XYZ K1ABC RR73
we     K1ABC W9XYZ 73
```

## What contest mode changes

The table is for Grid and Field Day. In Rpt+grid only the CQ word, combined messages and dupes change; every message is the normal one.

| Event | Normal | In contest mode |
|---|---|---|
| Our call | Grid | Exchange |
| CQ word | From the ⋯ panel | The contest's, locked |
| Their exchange in our format | Ignored | R + ours, or RR73 if they sent the R |
| Another contest's format | Ignored | Ignored |
| Their grid | Report | Grid: R + ours. Field Day: our exchange, no R unless we hold theirs |
| Plain report from a non-contest station | R-report | R + exchange (unproven against WSJT-X) |
| RPT button | Report | Exchange |
| Combined messages | Yes | Never |

## Dupes

A dupe is the same call on the same band, in the same contest mode, within 48 hours (**Trial**), looked up in the current logbook.

- Dupes are **marked**, never refused: red `DUPE` on Live and Roster.
- A CQ run doesn't answer a dupe by itself.
- A tap still does.

## Logged fields

| ADIF field | Field Day | Grid | Rpt+grid |
|---|---|---|---|
| `STX_STRING`, `SRX_STRING` | Yes | Yes | None (the report fields hold it) |
| `CONTEST_ID` | `ARRL-FIELD-DAY` | None (matches WSJT-X, since one setting covers several contests) | None |
| `CLASS`, `ARRL_SECT` | From theirs | None | None |
| `APP_FT8OK_CONTEST` | `FIELD_DAY` | `GRID` | `REPORT_GRID` |

Signal reports stay empty unless a dB report really was exchanged, as in Rpt+grid, where they always are.

## Decoded but not answered

RTTY Roundup and EU VHF exchanges are decoded and displayed, and those stations appear in the Roster and Map, but the app can't send those formats.

In normal mode, tapping a contest station still sends a standard call, and Live warns that the contact may not complete.

## Proven so far

- **On air:** Grid contacts completed in a real contest on a custom frequency, with a custom CQ word set.
- **On air:** `CQ FD` decoded by a remote receiver.
- **Not confirmed:** our Field Day exchange decoded by a real WSJT-X station, or our custom CQ word decoded by anyone.

Cabrillo export, scoring and serial-number exchanges are not built.
