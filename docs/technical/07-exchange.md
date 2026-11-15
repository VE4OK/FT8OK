# 7. The exchange

## One idea: a record, not a script

The app doesn't step through a fixed sequence. It keeps a record of what has been exchanged and works out the right next message from that. This is why stations that skip steps still work.

Two distinctions matter in the record. A report or close can be **queued** or actually **on the air**. Logging and "did it go out" questions use the on-air fact.

## What we send in reply

Examples use `VE4OK` as our call.

| They send | We reply |
|---|---|
| `VE4OK K1ABC FN42` (grid) | `K1ABC VE4OK -11` |
| `VE4OK K1ABC` (no grid) | `K1ABC VE4OK -11` |
| `VE4OK K1ABC -07` | `K1ABC VE4OK R-11` |
| `VE4OK K1ABC R-07` | `K1ABC VE4OK RR73` |
| `VE4OK K1ABC RRR` | `K1ABC VE4OK 73`, always, so a lost close is recovered |
| `VE4OK K1ABC RR73` or `73` | `K1ABC VE4OK 73` if we hadn't closed, otherwise nothing |
| Their CQ, free text, a different contest's format | Nothing (their parity is still followed) |

The R means "I have your report". Reports are re-measured each time they're heard.

### Answering a CQ

```
K1ABC  CQ K1ABC FN42
we     K1ABC VE4OK FN25        you tapped, then ARM
K1ABC  VE4OK K1ABC -07
we     K1ABC VE4OK R-11
K1ABC  VE4OK K1ABC RR73        logged here
we     K1ABC VE4OK 73          TX disarms: "Contact complete"
```

### Being answered

```
we     CQ VE4OK FN25
K1ABC  VE4OK K1ABC FN42
we     K1ABC VE4OK -10
K1ABC  VE4OK K1ABC R-08
we     K1ABC VE4OK RR73        logged when this finishes going out
                               CQ resumes, still armed
```

### Lost messages

An unanswered message repeats in our next slot. Tests lose each message of a contact in turn and it still completes. If a partner sends the same thing again, the reply repeats and nothing is counted twice.

## When the app gives up

| Situation | Limit |
|---|---|
| Calling a station that has never answered | 3 transmissions, about 1.5 minutes |
| Contact under way, partner goes quiet | 7 transmissions after they were last heard |
| Our RR73 not acknowledged | 2 RR73s in total |
| Partner in a CQ run | Settings › CQ PREFERENCES: missed replies (silent or repeated), default 2, then dropped; the run continues ([CQ runs](08-cq-runs.md)) |
| CQ with no reply | 10, about 5 minutes, then disarm |

Before giving up, the app **waits to hear the partner's next slot**, in case their answer was just arriving. This exists because the app once gave up one slot before the reply came.

A 73 sent cleanly ends the contact immediately, with no repeat.

A note on counting: a transmission that was refused or failed (a guard refused it, say) also counts as a try.

## If your partner works someone else

| What's heard from the partner | Search and pounce | CQ run |
|---|---|---|
| R-report to another station | Keep calling, armed. Limits still run | Dropped, CQ resumes |
| Their CQ, grid, plain report, or RR73/73 to someone else | Keep calling | Keep calling |
| Anything to someone else, after the contact is complete | Contact ends | Contact ends |

An RR73 or 73 already queued but not yet sent goes out **once** whatever the partner does next, so they get their close. A combined message is the exception and can be lost this way. A message from the partner to a hashed addressee isn't counted as working someone else, since it could be us.

## What a tap does

A tap never arms the transmitter.

| Row tapped | Result |
|---|---|
| A message to us | Answers it as that message, keeping their report |
| Their RRR, RR73 or 73 to us | Queues our 73, even after the contact ended |
| A CQ or a call to someone else | Fresh call. **CQ stops** |
| During a CQ run, another station calling us | Makes them next; current contact continues |
| The partner's own R-form to someone else | Keeps the contact and the queued message |
| A row from another band | Refused |
| A `<...>` sender | Nothing |

## Picking a message by hand

The dock offers **GRID · RPT · RR73/73 · AUTO**.

- **RPT** includes the R once we hold their report. **RR73/73** becomes 73 once they've closed.
- A pick is **sticky**: it keeps going every slot until AUTO, another pick, a tap, or the end of the contact. The contents update as new information arrives, so a pinned report gains its R when theirs comes in.
- Picks reset the unanswered count, and un-pause a halted contact. They never arm.

## Non-standard callsigns

Stations like `VE3XAA/QRP` can be answered from a tap on their message.

| Step | We send |
|---|---|
| Call | `<VE3XAA/QRP> VE4OK FN25` |
| Report | `<W1AW/8> VE4OK R-02` |
| Close | `<W1AW/8> VE4OK RR73` |

The close differs from WSJT-X, which sends `W1AW/8 <VE4OK> RR73`. Whether a WSJT-X station's sequencer acts on ours is unconfirmed, and a contact with such a station hasn't completed on air yet. The CALL tab refuses a non-standard call. Whether our own call can be non-standard is unchecked; contest mode refuses that.

## Typed messages

Whatever you type or build is **decoded back before it can be queued**, because the FT8 encoder garbles what it can't carry: `CQ HIHI TEST` would otherwise go out as `CQ HIHI <...>`. A message goes as standard only if it comes back exactly. Otherwise it's free text, up to 13 characters (A-Z, 0-9, space and `+ - . / ?`), or it's refused.

The **⋯** panel next to CQ prepares a CQ word (POTA, SOTA, DX, QRP or your own), a call to any station, or free text. The button next to it then shows that choice and queues it. ARM still transmits it. Free text goes once per ARM.
