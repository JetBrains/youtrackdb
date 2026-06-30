<!-- workflow-sha: 8b0a24709d3369f1c78740210f86acd9f51d404e -->
# Phase-C review iteration keyed to the complexity tag — Design

## Overview

The Phase-C track code review loop today caps every track at three iterations
and escalates if blockers survive the cap. This change removes that fixed cap
where the complexity tag calls for it — the blocker loop at every level, and the
should-fix loop on `high` — and replaces it with no-progress detection: the loop
iterates as long as it keeps clearing findings, and escalates the moment an
iteration clears nothing. The per-track complexity tag — the `low` / `medium` /
`high` value already reconciled to `max(step tags)` and read from the phase
ledger at the A→C boundary — sets how the loop terminates, the same dial that
already sets how hard the loop iterates today.

The dial moves from "how many iterations" to "what terminates the loop." Today
the tag chooses a depth within a flat cap-3 ceiling: `low` runs once, `medium`
runs the normal cap-3, `high` runs all three iterations. After this change the
tag chooses a termination rule. A `low` track loops on blockers until none
remain, uncapped. A `medium` track does the same and also iterates up to three
times to clear should-fix findings. A `high` track loops until no blockers and no
should-fix remain, uncapped on both. Blocker-looping is decoupled from
should-fix-looping: blockers always loop until clear, and should-fix-looping
depth scales with the tag.

Removing the cap removes the safety valve that stopped an unfixable blocker from
looping forever, so the change supplies a replacement: no-progress detection. An
iteration that returns `STILL OPEN` for every carried finding, clears none, and
surfaces no new fixable finding has made no progress; the orchestrator escalates
to the user instead of looping again. The signal is the gate-check verdict stream
the loop already emits, so no-progress detection adds no new measurement
machinery — it reads a stream that already exists.

This is a workflow-machinery design for a reader who maintains
`.claude/workflow/**` and knows the Phase A / B / C review structure, the
per-track complexity tag (its reconciliation and ledger home), and the cap-3
review-iteration protocol in `review-iteration.md`. The change touches workflow
prose only — no scripts, hooks, settings, or Java. The rest covers the load-
bearing concepts, a flowchart of the new loop, the no-progress definition, the
per-level policy as a delta from today, and the scope carve-outs the change must
restate at the cap-3-keyed sites.

## Core concepts

**TL;DR.** Four ideas the rest of the document uses without re-defining: the
*per-track complexity tag* (the rigor dial this change re-keys), the
*cap-3-then-escalate protocol* (the shared rule Phase C overrides), the
*gate-check verdict stream* (the per-finding verdicts the loop already emits),
and *no-progress detection* (the termination rule that replaces the fixed cap).

**Per-track complexity tag.** The `low` / `medium` / `high` value computed per
track and reconciled to `max(step tags)` at the Phase A→C boundary, read
track-scoped from the phase ledger. It is the only tag that drives review
iteration today — the Phase-C rigor dial. It is distinct from the per-*step* risk
tag, which gates Phase-B step review and has no single track-level value to read.
The new policy keys on this per-track tag, never on the per-step risk tag.

**The cap-3-then-escalate protocol.** The canonical shared review-iteration rule
in `review-iteration.md` §Limits: "Max 3 iterations per review type; if blockers
persist after 3 iterations, escalate." Phase-2 plan reviews, Phase-A track
reviews, and Phase-B step reviews all use it. The Phase-C track code review uses
it today too; this change overrides it for Phase C alone.

**Gate-check verdict stream.** Each review iteration after the first re-runs the
affected reviewers as a gate-check, which emits one verdict per carried finding:
`VERIFIED`, `REJECTED`, `MOOT`, `STILL OPEN`, or `REGRESSION`
(`review-iteration.md` §Gate-check verdict handling). `VERIFIED` / `REJECTED` /
`MOOT` clear a finding; `STILL OPEN` carries it forward; `REGRESSION` forces a
`FAIL`. No-progress detection reads this stream — it needs no new measurement.

**No-progress detection.** The termination rule that replaces the fixed cap on
the uncapped loops. An iteration makes no progress when its gate-check clears no
carried finding and surfaces no new fixable finding; the orchestrator then
escalates instead of looping again. Defined in full under §No-progress detection.

### References

- D1: tag axis is the per-track complexity tag
- D2: scope is Phase-C track code review only
- D3: per-level iteration policy
- D4: no-progress detection replaces the cap-3 safety valve

## The new Phase-C review loop

**TL;DR.** The orchestrator reads the reconciled complexity tag and runs a
termination rule the tag selects. The blocker gate is identical at every level —
blockers loop until clear; the levels differ only in whether and how long
should-fix findings keep the loop running. The flowchart below traces one entry,
with the per-iteration context pause drawn on a separate axis.

Read the flowchart top-down: the diamond at `Tag?` splits into the three
per-level columns, and the dashed edges at the bottom belong to the independent
context-pause axis.

```mermaid
flowchart TD
    A[Enter Phase-C review loop] --> B[Read reconciled complexity tag from ledger]
    B -->|missing tag| MED[Treat as medium - safe default]
    B --> C{Tag?}
    MED --> C

    C -->|low| L1[Run reviewers]
    C -->|medium| M1[Run reviewers]
    C -->|high| H1[Run reviewers]

    %% --- low: uncapped blocker loop only ---
    L1 --> LB{Blockers or REGRESSION open?}
    LB -->|no| LX[Exit loop. Surface remaining should-fix at track completion]
    LB -->|yes| LP{Gate-check made progress?}
    LP -->|yes| LF[Fix iteration: spawn implementer, re-run gate-check] --> LB
    LP -->|no| ESC[Escalate to user: surviving findings + per-iteration verdict history]

    %% --- medium: uncapped blocker loop + capped should-fix loop ---
    M1 --> MB{Blockers or REGRESSION open?}
    MB -->|yes| MP{Gate-check made progress?}
    MP -->|yes| MF[Fix iteration: spawn implementer, re-run gate-check] --> MB
    MP -->|no| ESC
    MB -->|no| MS{Should-fix open AND iteration <= 3?}
    MS -->|yes| MSF[Fix iteration: spawn implementer, re-run gate-check] --> MB
    MS -->|no| MX[Exit loop. Surface remaining should-fix at track completion]

    %% --- high: uncapped on both blocker and should-fix ---
    H1 --> HB{Blockers or REGRESSION open?}
    HB -->|yes| HP{Gate-check made progress?}
    HP -->|yes| HF[Fix iteration: spawn implementer, re-run gate-check] --> HB
    HP -->|no| ESC
    HB -->|no| HS{Should-fix open?}
    HS -->|yes| HSP{Gate-check made progress?}
    HSP -->|yes| HSF[Fix iteration: spawn implementer, re-run gate-check] --> HB
    HSP -->|no| ESC
    HS -->|no| HX[Exit loop. All blockers and should-fix cleared]

    %% --- per-iteration context pause, independent of no-progress ---
    LF -.context warning/critical.-> PAUSE[Pause: write mid-phase-handoff, resume next session]
    MF -.context warning/critical.-> PAUSE
    MSF -.context warning/critical.-> PAUSE
    HF -.context warning/critical.-> PAUSE
    HSF -.context warning/critical.-> PAUSE
```

The dashed edges are the existing per-iteration context-consumption pause, drawn
to show it sits on a different axis from no-progress detection: any fix iteration
can hit it, and it suspends rather than terminates the loop. §No-progress
detection covers why the two never substitute for each other.

The `low` track has no should-fix branch at all — its only exit on a clean
gate-check is the blocker check returning "no." It therefore relies entirely on
no-progress detection for its escalation path; it has no should-fix cap as a
backstop.

### Edge cases

- **Missing or torn tag.** When the ledger carries no reconciled tag (a pre-scheme
  branch or a torn append), the loop treats the track as `medium` and runs the
  standard cap-3, the same safe default the dial uses today.
- **Suggestions never drive iteration.** A leftover suggestion never causes a
  `FAIL` or another round at any level. Suggestions are applied opportunistically,
  routed to plan corrections if out-of-track, or dropped if rejected. The new dial
  stays silent on suggestions; nothing changes for them.
- **`REGRESSION` short-circuits.** A `REGRESSION` verdict is progress-negative and
  escalates immediately at every level — it already forces a `FAIL` under existing
  handling, so it never waits for the no-progress check.

### References

- D1: tag axis is the per-track complexity tag
- D2: scope is Phase-C track code review only
- D3: per-level iteration policy
- D4: no-progress detection replaces the cap-3 safety valve

## No-progress detection

**TL;DR.** An iteration makes no progress when its gate-check returns `STILL
OPEN` for every carried finding, clears none, and surfaces no new fixable
finding; the orchestrator then escalates instead of looping again. Findings are
compared across iterations by their reviewer-assigned `id`. The rule gates every
uncapped loop and is read off the verdict stream the loop already emits, so it
adds no new measurement machinery.

No-progress detection bounds the uncapped loops by the real convergence signal —
whether findings are shrinking — not by a fixed iteration count. A genuinely
converging high-risk track is never cut off early; a stuck loop escalates on the
first iteration that clears nothing. The definition turns on three parts.

**Identity.** A finding is "the same" by its reviewer-assigned `id` — the
cumulative finding ID the gate-check already verdicts by. The unit of comparison
across iterations is the finding `id`, not its text or location.

**Threshold.** An iteration makes no progress when its gate-check returns `STILL
OPEN` for every finding carried into it, clears none (no `VERIFIED` / `MOOT` /
`REJECTED`), and surfaces no new fixable finding. One net clear, or one new
fixable finding, is progress and the loop continues. A `REGRESSION` is always
progress-negative and escalates immediately, because it already forces a `FAIL`
under existing verdict handling.

**Which loops it gates.** The rule gates each uncapped loop: the blocker loop at
all three levels and `high`'s should-fix loop. The `medium` should-fix loop is
already bounded by its cap-3, so no-progress detection is moot there. It applies
to `medium` only once a surviving blocker carries the loop past three iterations,
at which point the blocker loop's no-progress gate takes over.

On a no-progress iteration the orchestrator escalates to the user rather than
looping again. It surfaces the surviving findings and the per-iteration verdict
history — the same escalation shape the cap-3 exhaustion produced, fired on the
no-progress signal instead of a fixed count.

### Composition with the context-consumption pause

The Phase-C loop already carries a mandatory per-iteration context-consumption
check that halts at `warning` (≥40%) / `critical` (≥50%) and writes a
`mid-phase-handoff.md` so the next session resumes the loop. No-progress detection
and the context pause compose on independent axes, and neither substitutes for the
other.

The context pause bounds per-session burn. It pauses the loop and resumes it next
session; the cross-session resume re-reads loop state. No-progress detection
bounds convergence. It escalates when findings stop shrinking across iterations,
including across a resume.

The two combine cleanly because they measure different things. A slow-but-real-
progress `high` track makes real progress each iteration, so it never triggers
no-progress escalation; it hits the context pause, hands off, and continues next
session. A stuck track escalates on the first no-progress iteration regardless of
the context level. The context pause never substitutes for no-progress escalation,
and no-progress escalation never preempts a context pause.

### Edge cases

- **First iteration.** Iteration 1 is the full review, not a gate-check, so it has
  no carried findings to verdict and cannot register as no-progress. The earliest
  an iteration can be no-progress is iteration 2.
- **Resume across the pause.** A track that paused on context and resumes still
  carries its open-findings state, so no-progress detection spans the resume — a
  resumed iteration that clears nothing escalates exactly as an in-session one
  would.

### References

- D4: no-progress detection replaces the cap-3 safety valve
- D2: scope is Phase-C track code review only

## Per-level iteration policy

**TL;DR.** Stated as a delta from today: blockers loop uncapped to clear at every
level; `low` stops there, `medium` adds a should-fix loop bounded at three
iterations, and `high` makes the should-fix loop uncapped too. The cap-3 ceiling
is replaced by no-progress detection on the uncapped loops, and `medium` keeps
the single shared iteration counter, gating only should-fix continuation.

The policy is stated as the delta from today's behavior. Today the complexity tag
chose iteration depth within a flat cap-3 ceiling. After this change it chooses a
termination rule, with the cap removed on the paths the user scoped uncapped.

| Level | Today | After this change |
|---|---|---|
| `low` | single shallow pass; blocker/REGRESSION forces continuation within cap-3 | blocker loop runs uncapped to clear; should-fix never drives iteration; remaining should-fix surface at track completion |
| `medium` | normal cap-3 iteration | uncapped blocker loop, plus up to 3 iterations to clear should-fix; after 3, remaining should-fix surface at track completion |
| `high` | iterate to convergence within the cap-3 ceiling (run all 3) | loop until no blockers and no should-fix remain; no fixed cap on either |

The common thread: blockers always loop until clear at every level, and the
cap-3-then-escalate ceiling is replaced by no-progress detection on the uncapped
loops. `low` loops on blockers and stops there. `medium` adds a bounded should-fix
loop. `high` makes the should-fix loop uncapped too.

### Why `low` removes the cap rather than introducing a loop

`low` already loops on blockers today — the single-pass shortcut shortens optional
iteration depth, never the must-fix gates, so a blocker or `REGRESSION` already
forces a `low` track to continue past the single pass. This change removes the
cap-3 ceiling on that existing blocker loop; it does not introduce a loop where
there was none. The delta for `low` is "the blocker loop is now uncapped,"
nothing more on the should-fix side.

### The `medium` shared counter

The iteration counter is shared across all dimensions today — one counter, not
independent per-dimension counters. `medium` needs the blocker loop uncapped while
the should-fix loop caps at three on that one counter, so this change keeps the
single shared counter and gates only the should-fix continuation.

"Should-fix drives a new iteration" is gated on `iteration ≤ 3`. "A surviving
blocker drives a new iteration" is not gated — it continues past three, bounded by
no-progress detection. A should-fix finding that re-surfaces in a post-3,
blocker-driven iteration is fixed opportunistically when the implementer is
already touching that code, otherwise surfaced at track completion. No second
counter is introduced.

### Edge cases

- **`high` with only should-fix open.** Once blockers clear, a `high` track keeps
  looping on should-fix alone, gated by no-progress detection. A should-fix loop
  that clears nothing escalates the same way a stuck blocker loop does.
- **`medium` past iteration 3 with a blocker.** The blocker continues to drive
  iterations past three; the should-fix loop stops at three. Any should-fix that
  re-surfaces in those post-3 iterations is fixed opportunistically, not as its own
  driver.

### References

- D3: per-level iteration policy
- D3.1: the `medium` shared-counter interaction
- D4: no-progress detection replaces the cap-3 safety valve

## Scope and the cap-3-keyed restate sites

**TL;DR.** The change is scoped to the Phase-C track code review loop only; the
Phase-2 / 3A / 3B loops keep cap-3-then-escalate. `review-iteration.md` §Limits
keeps cap-3 as its default and gains one carve-out sentence announcing the
Phase-C override at the canonical home. Every cap-3-keyed mechanic in
`track-code-review.md`, plus two more dial-mapping sites, must be restated so no
derived file ships self-contradictory text. The full set is enumerated by a
`grep`, not by the line numbers listed below.

The change is scoped to the Phase-C track code review loop only. Phase-2 plan
reviews, Phase-A track reviews, and Phase-B step reviews keep the cap-3-then-
escalate behavior, because the per-track complexity tag is only reconciled and
available at the A→C boundary — Phase-2 plan reviews run before any track tag
exists, and the user scoped the iteration-depth change to Phase C alone.

### Wiring the §Limits carve-out

`review-iteration.md` §Limits is the canonical shared home for the cap-3 protocol,
and its TOC filter loads it in Phase C. A Phase-C reader who lands on §Limits (for
example via the `review-agent-selection.md` rigor-dial cross-reference) would
otherwise read "Max 3, then escalate," which contradicts the new uncapped Phase-C
policy. So §Limits is in scope: it keeps cap-3-then-escalate as the default for
Phases 2 / 3A / 3B and gains one carve-out sentence — Phase-C track code review
overrides this per `track-code-review.md` §Review loop, with iteration depth keyed
to the per-track complexity tag, no fixed cap, terminated by no-progress
detection. The override is announced at the canonical home, not silently asserted
only at the override site. Default behavior for the non-Phase-C loops is unchanged.

### The full restate set

Uncapping the `low` / `medium` blocker loop and the `high` should-fix loop
invalidates several cap-3-keyed mechanics that derived artifacts must restate, or
the file ships self-contradictory text. The complete set is the output of this
grep, which the author runs and restates every hit of:

```bash
grep -nE '3 iterations|N/3|/3|of 3|three iteration' .claude/workflow/track-code-review.md
```

The hit set as of this writing is lines 491, 527, 685 (the dial site), 724, 765,
832, 837, 848, 875, 1092, 1106, and 1256. Each restate:

- **Dial site** (`track-code-review.md` §Review loop, ≈681-693): gets the new
  per-level mapping from §Per-level iteration policy.
- **Progress format** (≈765): `Track-level code review iteration N complete (N/3
  iterations)` drops the `/3` denominator; record `iteration N complete` with no
  fixed denominator.
- **Step 4 resume** (≈832-847): "Max 3 iterations total across sessions — on
  resume, read the iteration count to determine how many remain" no longer applies,
  because with no cap there is no "remaining" count. Restate: resume reads the
  running iteration count plus the no-progress / open-findings state, not a
  remaining-cap count.
- **Pre-spawn-split rationale** (≈837): "consumes 2 of 3 iterations" drops the "of
  3" framing; the split still consumes iterations, against no fixed ceiling.
- **Step 5** (≈848): "If blockers persist after 3 iterations, ... note the unfixed
  findings" becomes "if the loop escalates on no-progress with blockers open, note
  the unfixed findings."
- **Step 6 commit guard** (≈875): "not when the loop exited with blockers still
  open after 3 iterations" becomes "...exited on no-progress with blockers still
  open."
- **Checklist seed** (≈1256): the `(1/3 iterations, iteration 1...)` template
  aligns to the cap-free Progress format.
- **Cost models** (≈491, ≈527): "reviewers × three iterations = eighteen spawns"
  and "× 3 iterations per track" use the cap as a cost bound the change removes;
  reword to a representative or typical count, not a hard `× 3`.
- **Failure and budget mentions** (≈724 "2 of 3 used", ≈1092 `FAILED at iteration
  N/3`, ≈1106 "If blockers persist after 3 iterations, note them"): restate without
  the `/3` denominator and against the no-progress exit rather than a fixed count.

The lines above are the major sites, not a substitute for re-running the grep on
the live file (see the drift edge case below).

### The other dial-mapping sites

Two more files carry the dial mapping and must stay in sync, outside the grep
above:

- `review-agent-selection.md` §"Complexity sets the Phase-C rigor dial, never the
  set": the `low` / `medium` / `high` dial-mapping prose gets the new policy; its
  cross-reference to §Limits stays.
- `code-review/SKILL.md` (≈225): the standalone-skill note describing the dial. The
  `/code-review` skill takes no complexity input, but its prose ("low single
  shallow pass / medium cap-3 / high iterate to convergence") must stay in sync with
  the new mapping.

### Edge cases

- **The grep can drift.** The line numbers above are valid as of this writing; an
  author on a later branch state re-runs the grep rather than trusting the listed
  lines, because intervening edits move them.
- **Spelled-out counts.** The `three iteration` alternative in the grep pattern
  catches the spelled-out count at lines 491 and 685 that the digit-only patterns
  miss; do not drop it from the pattern.

### References

- D2: scope is Phase-C track code review only
- D2.1: wire the §Limits carve-out, do not merely assert the override
