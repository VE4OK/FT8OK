# 0013 TX frequency: two modes, pick-and-hold, move only on measured evidence
**Status:** Accepted (2026-10-14); thresholds Proposed.

**Context.** FT8 is half-duplex, and that has a consequence the occupancy design had not accounted for: the spectrum we need to know about is the spectrum of *our own* TX parity, and we are deaf during every slot we transmit in. A station CQing every cycle of its parity never hears that parity again, so its occupancy map for the only parity that matters is frozen at whatever it last heard — 30 seconds ago, or four minutes ago. The maintainer put it directly: *"we have no idea if the slot we are currently txing on is still clear if we've been txing on every slot continuously for even or odd."*

Nor can the collision be inferred. A squatter sharing our parity and our frequency is mutually deaf with us, so neither of us ever finds out. One detection path looked promising — answerers reply on the caller's frequency, so a squatter's answers would appear at *our* frequency in the parity we do hear — but the maintainer's operating experience rules it out: **most operators work split**, precisely so that twenty answers do not pile onto one caller's frequency. Their answers land on their own frequencies, scattered, and cannot be tied to ours. So a co-parity collision on our own spot is, in normal practice, **undetectable while we transmit**.

What real operators do with that: a station running continuous CQ *"will often just keep the same tx spot regardless of noise around, and bash through."* The measurement bar is therefore low — an operator holding a spot has no data at all, so picking from a stale map is still better than the human default, and the failure mode is one humans already accept.

Against that, the maintainer's standing view of the feature: *"one thing i've always thought about ft8 was that choosing a tx spot is unnecessary distraction... the 'computer' should be able to do that."* Demote the display, keep the data.

**Decision.**

1. **Two modes, chosen in Setup, not on the main screen.** The main screen's occupancy strip carries no mode control; UI simplicity outranks discoverability here (maintainer: *"UX simplicity and UI has to be super simple, so no options on the main screen with the bar chart"*).
   - **Full Auto (default).** The app picks the TX frequency and **holds** it. It moves only when a *fresh listening slot of our own TX parity* shows our spot has become busy. Never mid-QSO.
   - **User-set.** The user taps the strip; the app transmits there and never adjusts it.

   This supersedes the `Auto → Pinned → Lock` model in. An earlier three-mode split (auto / tap-to-override / fixed) collapsed, because "tap to override and it sticks" and "set one spot" are the same mode once the tap has happened.

2. **Pick and hold, in both modes.** Re-picking every cycle is wrong, and was measured to be wrong: an early test build re-picked per slot and wandered 744 → 1708 → 1711 → 1728 Hz across consecutive same-parity slots. Each pick was correct for its own slot — the widest gap genuinely moves as stations come and go — but our partner is listening where we were. Full Auto re-picks freely **between** QSOs, which is also the only time fresh data on both parities exists.

3. **The move rule (thresholds Proposed, to be tuned on air).** Full Auto abandons a held frequency when **all** of these hold:
   - the evidence comes from a slot of our **own TX parity** in which we were **listening, not transmitting** — a stale map must never trigger a move, since staleness is the whole problem;
   - energy at or above **12 dB over that slot's noise floor** falls within our signal window, **TX ± 35 Hz** (half of the 50 Hz signal width plus the 10 Hz guard);
   - no QSO is in progress.

   **Why 12 dB and not the 6 dB used for picking.** The costs are asymmetric and they point opposite ways. When *choosing* a frequency, a false "busy" costs only a different choice, so 6 dB is deliberately sensitive. When *abandoning* a frequency we already hold, a false positive costs us the spot and possibly the station mid-call, so moving must require more evidence than picking did. 12 dB is also the `+` tier on the occupancy strip, so the operator can **see** the reason for a move rather than taking it on trust — the number is legible in the display they are already looking at. Energy in the guard band alone does not mask us and does not trigger a move.

4. **Full Auto needs listening slots in its TX parity to exist at all.** They occur naturally between QSOs and before the first CQ. A station CQing every single cycle produces none, and Full Auto then degenerates to pick-once-and-hold — which is exactly what human operators do, so this is a limit to document, not a bug. **Proposed:** after some number of unanswered CQs, skip one TX cycle to refresh the map. No answers is itself the symptom of being stepped on, so the cost is paid only when something is already wrong.

**Consequences.**

- Full Auto is **more etiquette-compliant than WSJT-X's default**, which moves your TX to the caller's frequency and is how pileups stack on one spot. Picking a quiet gap *is* working split. The one case where answering on their frequency is correct — a DXpedition asking for it — stays the setting already noted in.
- A tap on the strip must **snap to a candidate gap**, not to literal Hz: at roughly 330 px for 2700 Hz a fingertip covers about 300 Hz, so the user is choosing a gap whether the UI admits it or not.
- The TX-parity lane can be **minutes stale**, and must not be drawn as if it were live. It needs a visible age, since a confidently-rendered bar chart of old data is worse than no chart.
- "Last known" should mean the **most recent few same-parity listening slots**, not only the last one; a single slot can miss a station that happened to be quiet that cycle. Taking the busiest reading across them is the conservative direction, at the cost of possibly finding no gap on a crowded band. **Proposed**, with the count to tune.
- Two modes mean two paths through the TX-frequency decision, each needing its own test. The surface is small — a policy enum and one frequency — so this is cheap.

**Amended 2026-10-20.** Decision 1's "chosen in Setup" no longer holds. The mode now lives on the strip: a tap on the lane sets the frequency by hand (User-set), as it always did, and a tap on the line under the axis, while it reads `Set`, goes back to Full Auto. That line is the only mode control, and Settings no longer has one. The strip still carries no option or toggle; the maintainer's "super simple" rule is kept by reusing the line that already says which mode is on.

**Amended 2026-11-01.**
- **The back-to-Auto tap moved.** The line under the axis is gone. While the frequency reads `Set`, a tap on the frequency under the lane name, or on `Set` under the lane's age, goes back to Full Auto.
- **One pick per lane.** Full Auto keeps a separate pick for EVEN and for ODD, each judged only against its own lane's map, and transmits on the pick for the lane it transmits in. Before, one frequency was judged against each lane in turn. In a contact or CQ run only the committed lane holds; the other keeps choosing. User-set is still one frequency for both lanes (decided by the maintainer, for now).
- **No gap left: the quietest spot.** Decision 3's trigger is unchanged except its level, now 9 dB on our own spot on trial (was 12; comfort still uses 12 as `LOUD_DB`), from a fresh listening slot of that lane, no contact in progress. When it fires, Auto still prefers the nearest comfortable gap, then the nearest gap. With no gap anywhere it now goes to the **quietest spot** in the TX band, with no margin (maintainer: *"its 'auto mode', so i think it should always move to the quietest. if we set the channel, then it has the option to stay there"*). A first pick with no gap also takes the quietest spot; it used to pick nothing, and the transmitter then fell back to 1500 Hz.
- **The collision window is now TX −5 Hz to TX +55 Hz.** The guard is 5 Hz, not 10, and a frequency is the signal's low edge, not its centre as decision 3 was written.
- **Where we may transmit is the radio's (later the same day).** In USB-D the IC-7300 passes only audio inside its SSB-D transmit passband (`SET > Tone Control/TBW > TX > SSB-D`, default 300-2700) to the transmitter; outside it the radio keys with no power. The app reads that setting over CI-V (`1A 05 01 96`) once per connect (up to 3 questions until it answers or refuses; amended 2026-11-02, maintainer: the setting is set once or never, so no 10 s poll - restart the app or reconnect after changing it) and keeps every pick, move and tap inside it, falling back to 300-2700 until it answers. A tap is taken on either lane, not only the lit one.
