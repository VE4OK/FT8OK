# 8. CQ runs and combined messages

## S&P and CQ differences

| Situation | Search and pounce | CQ run |
|---|---|---|
| Station calls us, no contact in progress | Row highlighted, **not answered** without a tap | Answered automatically |
| Station calls during a contact | Row only | Queued as next |
| Contact finishes | Disarms | Stays armed, next caller or CQ |

## Who goes next

Decided once per slot, after its last decode pass and before the TX decision at +15.3 s:

1. **The contact in progress** carries on to its end, uninterrupted.
2. **A returning station:** a partner the run dropped (silent, repeating or busy elsewhere), or one logged in the last 5 minutes, heard sending to us again. It carries on from what was exchanged; an R-report gets our RR73. While one waits, our RR73 to the current partner goes out plain. A logged contact gets one extra RR73 this way, then its R-reports are ignored.
3. **A new caller:** one heard in its latest slot first, then first heard; a tap puts a station first.
4. **CQ.**

A decode with no measured report counts like any other, as -24.

## The queue of callers

- **Fresh callers first, then first heard, first served.** A station heard in its latest slot goes ahead of one heard earlier; among each group, a station already waiting keeps its place, with its latest report refreshed. A station not heard in its latest slot is still answered when nobody fresher is waiting. **Code** and JVM tests.
- A station counts as a caller if it sends us its grid, a call with no grid, a plain report or a contest exchange. An R-report doesn't count, except from a partner the run dropped (below).
- **A tap on a caller** makes them next, fresh or not.
- A waiting caller is dropped after **two missed slots** (about a minute), or when heard calling CQ or working someone else.

## When a partner stops answering, or keeps repeating

**Settings › CQ PREFERENCES** sets how many **missed replies** a partner in a CQ run gets: **Off, or 1 to 5; default 2**. A miss is one of our messages after which the partner's next slot tells us nothing new: silence, or the same message again (their grid again after our report, their R-report again after our RR73). Anything new (their report, their R, their close) starts the count again. After the last miss the run moves on, still armed: the partner's slot is decoded about 0.0-0.2 s into ours, the decision is made there, and the next caller or our CQ goes out in that same slot, so no slot is lost (**On air** for silence; **Code** and JVM tests for repeats).

- After our RR73 nothing is expected back, so **silence ends the contact** (already logged) with no second RR73. Their R-report again is a miss, answered with RR73 until the limit.
- A partner moved on from before the close is kept aside, as below; one moved on after it is simply over.
- While a dock pick is on the air, nothing is counted: that is the operator's choice. Re-arming, a tap or a pick starts the count again.
- **Search and pounce is not affected**: a station that needs many tries (one completed on our tenth R-report) is the operator's to keep or leave.
- **Off** is the behaviour before the setting: a partner gets **2 silent messages**, and one who keeps answering with the same message is kept until you press ARMED or HALT.

A partner heard working someone else in the middle of our contact is dropped the same way, and kept aside too.

If a dropped partner is heard sending to us within **5 minutes**, the contact picks up where it left off, with the original reports: their R-report gets our RR73. If we're free, this happens at once. If another contact is in progress, that contact finishes first, and the returning partner goes **ahead of new callers**. While one waits, our RR73 to the current partner goes out plain, because the returning partner needs an RR73 of their own and two RR73s cannot share a message. The returning partner's RR73 can then carry the next new caller's report. A returning partner not heard for about 50 seconds, or heard calling CQ or working someone else, is forgotten. **Code** and JVM tests; **On air**: N9XAB, dropped after two misses, came back with their R-report and was answered and logged.

The dock's status line shows who's next, for example `will send K1ABC VE4OK -10 · then W9XYZ`.

## Combined (DXpedition-style) messages

FT8 message type 0.1 fits one station's RR73 and another's report in a single signal, so there's no power loss from splitting.

```
K1ABC RR73; W9XYZ <VE4OK> -14
```

### When it's used

It's automatic, with no setting, and only if **all** of these hold:

- we're in a CQ run with a contact in progress;
- no dock pick is pinned;
- it's not a contest contact;
- a caller is waiting, transmitting in the same parity as the partner (a station in ours can't hear us anyway);
- both calls are plain standard calls.

Otherwise an ordinary message goes.

### Three uses

1. **RR73 to the partner + report to the next caller.** The normal case.
2. **Our final 73, when they closed first,** is sent as RR73 + next caller's report. **On air.** Software that has already sent its own close ignores the message, so it isn't double-logged.
3. **A repeat RR73** to a station that missed ours, again carrying the next report, while the new partner hasn't answered yet.

If the new partner has answered by the time the closed station sends its R-report again, the closed station is not forgotten (**Code** and JVM tests). It goes next after the contact in progress, ahead of new callers, the same as a returning dropped partner. Our RR73 to the current partner goes out plain meanwhile. A closed station that took our RR73 is kept aside the same way, for 5 minutes, in case its R-report comes later. Each logged contact gets **one** extra RR73 this way; after that, the usual missed-reply limit applies.

The same goes for any station logged in the last 5 minutes that sends its R-report again while it isn't our partner, however its contact ended (**Code** and JVM tests): it gets our RR73, never a new contact. Once a contact has had its one extra RR73, or reached the missed-reply limit at its close, further R-reports from that station are ignored.

A station logged more than 5 minutes ago that calls again is answered as a new contact, by design. The operator can choose not to answer.

### Worked example

```
we     CQ VE4OK FN25
K1ABC  VE4OK K1ABC FN42            W9XYZ also calls: VE4OK W9XYZ EN37
we     K1ABC VE4OK -10             status: "· then W9XYZ"
K1ABC  VE4OK K1ABC R-08            W9XYZ still calling
we     K1ABC RR73; W9XYZ <VE4OK> -14     K1ABC logged, W9XYZ now partner
W9XYZ  VE4OK W9XYZ R-11
we     W9XYZ VE4OK RR73            nobody waiting: ordinary RR73
```

A repeat to the new partner is a plain message with our call in full. A partner who stays silent after our RR73, or has sent their R-report again up to the CQ PREFERENCES limit, is let go, and the next caller gets a plain report. (With the setting Off: after two RR73s ignored.)

### Why the format is like this

These limits come from WSJT-X's message definition:

- The message has 71 usable bits. Two full calls, the sender's hash and a report just fit. A full sender call would need 89 bits, so **our call is always a 10-bit hash** in this message.
- Two reports would need 78 bits. Sending "report to A + report to B" isn't possible.
- There's no type combining RR73 with a CQ.
- Reports are even numbers only ([Reports](05-reports.md)).

### What others do with it

From WSJT-X's source, a plain WSJT-X station sequences both halves automatically, without Hound mode.

**On air so far:** four stations answered a combined message directly and three didn't. One of those three answered the plain repeat, one only ever answered plain messages, and one answered neither. **The reason is unknown**. JTDX and MSHV's handling hasn't been checked, and a missed slot would look the same.

### Receiving one

The app acts on the half addressed to us. If that's an RR73, it's treated as an ordinary RR73, so the app answers 73 (**Code**). Showing the row requires knowing the sender's hash from earlier decodes.
