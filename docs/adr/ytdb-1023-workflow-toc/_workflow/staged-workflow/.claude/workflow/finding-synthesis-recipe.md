# Finding Synthesis Recipe

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Step 1 — Deduplicate across dimensions | orchestrator | 3B,3C | Merge raw findings across reviewer framings using a four-level pivot key (file:line, issue shape, fix shape, severity). |
| §Pivot order | orchestrator | 3B,3C | Walk raw findings in dimension order, keying on file:line, then issue shape, then fix shape, then strictest severity. |
| §Worked example — 5-way `Thread.sleep` dedup | orchestrator | 3B,3C | A worked 5-way dedup collapsing one polling-loop site into a single blocker finding row. |
| §Step 2 — Assign severity to singletons | orchestrator | 3B,3C | Singletons keep their severity unless it conflicts with the standard scale; log an OVERRIDE when up- or downgrading. |
| §Step 3 — Bucket the deduped list | orchestrator | 3B,3C | Sort each merged finding into in-scope-now, next-iteration, or plan-correction buckets; guidance can override. |
| §In-scope, this iteration | orchestrator | 3B,3C | All blockers plus correctness/crash-safety/CI-hang should-fixes and small fixes fitting the pre-spawn budget. |
| §In-scope, next iteration | orchestrator | 3B,3C | Style/naming/boundary should-fixes, cleanup depending on this iteration's fixes, and budget-displaced items. |
| §Deferred — plan correction or episode note | orchestrator | 3B,3C | Out-of-track suggestions and over-scope refactors route to plan corrections; log a DROP for re-read out-of-scope items. |
| §Step 4 — Pre-spawn budget check | orchestrator | 3B,3C | The ~15-finding / ~10-file ceiling, the 8–12 target, when to split vs accept a larger spawn, and high-volume handoff. |
| §Why the headroom matters | orchestrator | 3B,3C | Aim for 8–12 findings touching ≤6–8 files: per-test re-runs, gate-check carry-overs, and message-budget margin. |
| §Splitting vs accepting a larger spawn | orchestrator | 3B,3C | Split over-12-finding sets by severity, but accept a larger spawn for independent small fixes or atomic refactors. |
| §High-volume synthesis and handoff | orchestrator | 3B,3C | When the binary split cascades (≳24 findings), let the context-consumption gate fire the handoff, not a user prompt. |
| §Step 5 — Output format | orchestrator | 3B,3C | The canonical synthesised-findings output: in-scope, deferred, plan-correction sections plus the audit trail. |
| §Gate-check synthesis | orchestrator | 3B,3C | Map gate-check verdicts to forward/drop actions, fold in New findings, then run Steps 1–4 over the union. |
| §Verdict-to-action mapping | orchestrator | 3B,3C | VERIFIED/REJECTED/MOOT drop, STILL OPEN forwards verbatim, REGRESSION escalates to blocker and forces FAIL. |
| §Dedup and termination | orchestrator | 3B,3C | REGRESSION rows never merge and forward standalone; an empty post-Steps-1–4 list returns PASS and exits the loop. |

<!--Document index end-->

Shared procedure for collapsing a multi-agent dimensional review
fan-out into a single, deduplicated, iteration-ready findings list.

**Load on demand** at every synthesis call site:

- track-code-review.md:orchestrator:3C §Synthesis (Phase C
  track-level review — initial spawn and the post-gate-check
  re-synthesis).
- step-implementation.md:orchestrator:3B §Per-Step
  Orchestration Loop sub-step 4(b) (Phase B `risk: high` step-level
  review).
- review-iteration.md:orchestrator:3B,3C §Gate-check synthesis
  routing (re-run on aggregated gate-check `New findings` blocks
  before composing the next implementer input).

Synthesis produces three outputs, in this order:

1. A unified list of distinct findings, each annotated with the
   dimension(s) that flagged it.
2. A severity per finding (`blocker` / `should-fix` / `suggestion`,
   per review-iteration.md:orchestrator:2,3A,3B,3C §Severity levels).
3. A split into **in-scope this iteration**, **deferred to next
   iteration**, and **plan correction / out-of-track** buckets.

Apply the steps below in order. Skip a step that is inapplicable
(e.g., a 4-finding fan-out has no deduplication to do).

If every selected agent returned zero findings, emit:

```markdown
## Synthesised findings — iteration {N}/3

(no findings)
```

and signal PASS to the caller. Do not respawn the implementer.

---

## Step 1 — Deduplicate across dimensions
<!-- roles=orchestrator phases=3B,3C summary="Merge raw findings across reviewer framings using a four-level pivot key (file:line, issue shape, fix shape, severity)." -->

When the fan-out is wide (baseline 4 + at least 2 conditional groups,
typical for any track that touches storage or perf-sensitive paths),
expect 30–80 raw findings with 30–60 % cross-dimension overlap.

The deduplication trap is **reviewer framing**: five reviewers can
flag the same code site through five different lenses. A
`Thread.sleep(10)` polling loop in a CAS WAL test gets flagged as a
flake risk (`review-bugs-concurrency`), as a CI-hang risk
(`review-test-concurrency`), as wasted CPU (`review-performance`), as
a slow-test smell (`review-test-structure`), and as poor style
(`review-code-quality`). All five point at the same `file:line` and
propose the same fix shape (await the condition instead of polling).

### Pivot order
<!-- roles=orchestrator phases=3B,3C summary="Walk raw findings in dimension order, keying on file:line, then issue shape, then fix shape, then strictest severity." -->

Each level breaks ties for the level above. Walk the raw findings
once in dimension order (baseline first, then conditional, then
workflow-review), inserting each into the running merged list at this
key:

Some findings have no single `file:line` — missing-test flags
(`review-test-completeness`), missing-invariant flags
(`review-crash-safety`), architectural plan-vs-code drift. For
these, substitute the affected component or test class as the
level-1 key (e.g., `core/.../CASDiskWAL` or
`BTreeMultiValueIndexTest`). When even a component cannot be named,
skip directly to level 2 (issue shape) and rely on suggested-fix
shape for the merge decision.

1. **`file:line`**: exact code location. Two findings at the same
   file and line are candidates to merge. Two findings whose line
   ranges overlap, or whose lines fall within ±5 lines of each
   other in the same method body, share the level-1 key. Distant
   lines in the same file stay separate.
2. **Issue shape**: the substantive complaint, ignoring how each
   reviewer framed it. "Polling loop with fixed sleep", "race-prone
   flake on slow CI", "wasted CPU per assertion", "no deterministic
   synchronisation" — same issue. When two findings at the same
   `file:line` express genuinely different complaints (e.g.,
   "incorrect lock order" vs "missing null check"), they stay
   separate even though `file:line` matches.
3. **Suggested fix shape**: the concrete change the finding asks
   for. Two findings at adjacent lines proposing the same fix merge
   under one row; two findings at the same line proposing different
   fixes stay separate.
4. **Severity (tie-break only)**: when the merged finding inherits
   severities from multiple agents, take the **strictest** (blocker
   > should-fix > suggestion). Record the contributing dimensions so
   the gate-check fan-out re-runs only the implicated reviewers.

### Worked example — 5-way `Thread.sleep` dedup
<!-- roles=orchestrator phases=3B,3C summary="A worked 5-way dedup collapsing one polling-loop site into a single blocker finding row." -->

Raw findings, condensed:

| Dimension | Raw finding |
|---|---|
| `review-bugs-concurrency` | `BC3 should-fix` — `Thread.sleep(10)` polling loop in `CASDiskWriteAheadLogLifecycleTest:142` can mask races; replace with await-condition. |
| `review-test-concurrency` | `TX1 blocker` — Same `:142` polling; on a slow CI runner the 30 s test timeout fires before the WAL flushes and the suite hangs. |
| `review-performance` | `PF2 suggestion` — `:142` busy-loops; ~100 ms wasted per invocation, ~6 s across the suite. |
| `review-test-structure` | `TS5 should-fix` — Slow-test smell; long polls indicate weak isolation between WAL state and the assertion. |
| `review-code-quality` | `CQ9 should-fix` — Use `CountDownLatch` or `Awaitility` instead of bare `Thread.sleep`. |

After dedup, one merged row:

```markdown
### Finding M1 [blocker]
**Location**: core/src/test/.../CASDiskWriteAheadLogLifecycleTest.java:142
**Dimensions**: bugs-concurrency, test-concurrency, performance, test-structure, code-quality
**Issue**: `Thread.sleep(10)` polling loop instead of awaiting the WAL-flushed condition; risks CI hangs on slow runners (severity-driving) and masks the underlying race.
**Proposed fix**: Replace the loop with an `Awaitility` await on the flushed-condition predicate. Remove the test's wall-clock dependency on the 30 s timeout.
```

Severity is `blocker` — strictest of the five contributing severities,
inherited from `review-test-concurrency`.

The example covers Step 1 in full. Step 2 does not apply because M1
is a multi-contributor row, not a singleton. In Step 3 the `blocker`
severity routes M1 to **In-scope this iteration**. In Step 4 a
1-finding spawn is well under the 8–12 target, so no split is
needed. The Step 5 output carries M1 as the sole entry in the
**In-scope this iteration** section.

---

## Step 2 — Assign severity to singletons
<!-- roles=orchestrator phases=3B,3C summary="Singletons keep their severity unless it conflicts with the standard scale; log an OVERRIDE when up- or downgrading." -->

Findings that survive Step 1 as singletons keep their original
severity unless it conflicts with the standard scale in
review-iteration.md:orchestrator:2,3A,3B,3C §Severity levels:

- **blocker**: must fix before merge — bugs, security vulns, crash
  safety, data corruption, tests giving false confidence, CI-hang
  risks.
- **should-fix**: should fix before merge — likely bugs, performance
  issues, concurrency risks, missing critical test coverage,
  falsifiability gaps in newly-added tests.
- **suggestion**: recommended improvements — minor style, optional
  optimisations, additional test scenarios.

When an agent assigned a severity higher than the criteria above
support (e.g., `blocker` for a style preference), downgrade during
synthesis and log an `OVERRIDE` entry in the §Synthesis audit trail
(Step 5 output) so the next reviewer instance does not re-raise the
same finding at the same severity. Conversely, when a singleton's
stated impact reads clearly above its assigned severity, upgrade and
log the same `OVERRIDE` entry.

---

## Step 3 — Bucket the deduped list
<!-- roles=orchestrator phases=3B,3C summary="Sort each merged finding into in-scope-now, next-iteration, or plan-correction buckets; guidance can override." -->

For each merged finding, choose one of three buckets:

### In-scope, this iteration
<!-- roles=orchestrator phases=3B,3C summary="All blockers plus correctness/crash-safety/CI-hang should-fixes and small fixes fitting the pre-spawn budget." -->

- All `blocker` findings.
- `should-fix` findings touching concurrency correctness,
  crash-safety invariants, CI-hang risks, or the falsifiability of
  newly-added tests.
- `should-fix` findings whose fix shape is small enough to fit
  alongside the items above within the pre-spawn budget (Step 4).

### In-scope, next iteration
<!-- roles=orchestrator phases=3B,3C summary="Style/naming/boundary should-fixes, cleanup depending on this iteration's fixes, and budget-displaced items." -->

- `should-fix` findings touching style, naming, boundary cases,
  minor falsifiability tightenings, dead-code removal, or comment
  clarity.
- Cleanup that depends on this iteration's fixes landing first
  (e.g., a rename of a helper introduced in this iteration).
- `should-fix` items displaced from iteration 1 by the pre-spawn
  budget (Step 4).

### Deferred — plan correction or episode note
<!-- roles=orchestrator phases=3B,3C summary="Out-of-track suggestions and over-scope refactors route to plan corrections; log a DROP for re-read out-of-scope items." -->

- `suggestion`-severity findings that genuinely belong in a
  different track. Route via
  track-code-review.md:orchestrator:3C §Plan Corrections
  from Deferred Findings.
- Structural refactors whose scope exceeds the current track's
  goals (e.g., "extract this 200-line method into a new class
  hierarchy" surfaced during a 2-line bug-fix track). Plan
  correction.
- Findings the orchestrator judges out-of-scope on re-read despite
  the reviewer flagging them. Log a `DROP` entry in the §Synthesis
  audit trail (Step 5 output) with the rationale; if the rationale
  is purely "low value", treat as a rejected `suggestion` and drop
  from the list entirely instead of routing it to a plan correction.

The split is a judgement call; the buckets above are the default,
not a contract.

**At track level only**, when the user gave explicit guidance during
a prior Review-mode pass, the guidance overrides the default
bucketing for the items it named. Review-mode guidance names
contributing dimension IDs (`BC3`, `TX1`, …), not `M<n>` IDs (which
are assigned during synthesis, after the Review-mode pass ran).
Walk the §Synthesis audit trail's contributing-dimensions list for
each merged row and apply the user's bucket override before final
assignment.

Step-level synthesis (per
step-implementation.md:orchestrator:3B §Per-Step
Orchestration Loop sub-step 4(b)) runs before any Review-mode pass
exists; this override is unreachable there and the default
bucketing always wins.

---

## Step 4 — Pre-spawn budget check
<!-- roles=orchestrator phases=3B,3C summary="The ~15-finding / ~10-file ceiling, the 8–12 target, when to split vs accept a larger spawn, and high-volume handoff." -->

The hard ceiling from
track-code-review.md:orchestrator:3C §Review loop step 2 is
~15 in-scope findings or ~10 distinct source files per implementer
spawn.

### Why the headroom matters
<!-- roles=orchestrator phases=3B,3C summary="Aim for 8–12 findings touching ≤6–8 files: per-test re-runs, gate-check carry-overs, and message-budget margin." -->

**Aim lower than the ceiling.** Target 8–12 findings touching ≤ 6–8
files per iteration. The headroom matters because:

- The implementer's per-test re-runs scale with the number of
  touched test classes; a 12-file spawn already implies 4–6
  targeted `-Dtest=…` invocations
  (implementer-rules.md:implementer:3B,3C §Pacing
  long-running tasks → "Prefer targeted `-Dtest=…` re-runs").
- The gate-check fan-out's per-agent `New findings` cap of ≤ 3
  fills faster when the originating iteration covered more
  ground; splitting now leaves room for carry-overs without
  re-tripping the ceiling.
- Cache-cold Opus spawns truncated mid-iteration at 22 findings ×
  14 files in past Phase C sessions. A 12-finding target keeps the
  message-budget margin healthy.

### Splitting vs accepting a larger spawn
<!-- roles=orchestrator phases=3B,3C summary="Split over-12-finding sets by severity, but accept a larger spawn for independent small fixes or atomic refactors." -->

When the deduped in-scope set exceeds 12 findings, split it: take
the highest-severity 8–12 items first, displace the rest to
iteration 2. The displaced items re-surface in iteration 2's
gate-check fan-out and consume an iteration counter normally — they
are not lost.

**Soft pacing, not a hard split.** When the findings are genuinely
independent and the per-finding fix is small (e.g., 20
identical-pattern Javadoc fixes touching 10 files), one large
iteration is still fine. When iteration counts are already tight
(2 of 3 used) and the remaining findings cohere, accept the larger
spawn and log an `OVER-BUDGET` entry in the §Synthesis audit trail
(Step 5 output) with the rationale.

When a single merged finding inherently spans more files than the
per-iteration ceiling (systematic rename, polymorphic SPI signature
change, package move), keep it as one finding and log the
over-ceiling decision with an `OVER-BUDGET` entry in the §Synthesis
audit trail. Splitting an atomic refactor across iterations is
worse than the over-budget spawn — the partial states between
iterations would not compile.

### High-volume synthesis and handoff
<!-- roles=orchestrator phases=3B,3C summary="When the binary split cascades (≳24 findings), let the context-consumption gate fire the handoff, not a user prompt." -->

When the deduped in-scope set is so large that the binary split
cascades across iterations (≳24 findings), do not surface a
decision to the user. The context-consumption gate at the end of
each iteration is the natural pause point — high-volume synthesis
drives context pressure into `warning` / `critical`, which fires
the handoff per mid-phase-handoff.md:orchestrator:3B,3C. The
synthesised list and §Synthesis audit trail are preserved across
the handoff as work-in-progress; a fresh-context orchestrator on
resume can decide whether to continue, `ESCALATE`, or route some
items to plan corrections with cache headroom intact.

---

## Step 5 — Output format
<!-- roles=orchestrator phases=3B,3C summary="The canonical synthesised-findings output: in-scope, deferred, plan-correction sections plus the audit trail." -->

Present the synthesised list to the caller in this shape. The caller
classifies further (in-scope subset, plan corrections) per its own
section; this recipe produces the canonical merged list.

```markdown
## Synthesised findings — iteration {N}/3

### In-scope this iteration

#### Finding M1 [blocker]
**Location**: <file:line>
**Dimensions**: <comma-separated review-agent suffixes>
**Issue**: <merged description, one paragraph>
**Proposed fix**: <fix shape, one paragraph>

#### Finding M2 [should-fix]
... (one entry per in-scope finding)

### Deferred to next iteration

- M7 (should-fix style) — displaced by pre-spawn budget.
- M8 (should-fix boundary case) — depends on M3 landing first.

### Plan corrections (route to other tracks)

- M9 — belongs in Track <N+M>: <title>. Route via
  [`track-code-review.md`](track-code-review.md) §Plan Corrections
  from Deferred Findings.

### Synthesis audit trail

- `DROP BC7` — rejected on re-read: covered by existing test
  `<class>#<method>`; reviewer missed the assertion.
- `OVERRIDE PF4 blocker→suggestion` — ~50 µs improvement on a
  non-hot path; criteria support `suggestion` only. Dropped from
  in-scope list.
- `OVER-BUDGET 14 findings` — iteration counts already 2/3 used
  and the remaining findings cohere; accepted one larger spawn.
- `REJECTED-VERDICT BC9` — gate-check reviewer recanted: original
  finding was a misread of the `volatile` semantics.
```

Entry-kind tags (`DROP`, `OVERRIDE`, `OVER-BUDGET`, `REJECTED-VERDICT`)
are the canonical names cited from elsewhere in this recipe and from
review-iteration.md:orchestrator:3B,3C §Verdict handling. All
synthesis decisions that do not appear in the in-scope or deferred
lists land here.

The `M<n>` prefix is assigned at synthesis time. The
per-dimension finding IDs (e.g., `BC3`, `TX1`) live only in the
audit trail of which dimension contributed each merged row; the
implementer's `findings:` block carries only the `M<n>` IDs and
their merged bodies. The implementer does not need to know which
reviewer framing originated each item.

`M<n>` IDs are cumulative across iterations of the same review loop.
Iteration N's first new finding starts at `M<last+1>` where `<last>`
is the highest M-ID seen in iteration N−1's synthesised list,
regardless of which earlier findings cleared. Cumulative numbering
keeps implementer commit messages and gate-check verdicts unambiguous
across the loop.

Omit empty sections; never emit a heading with no content beneath it.

---

## Gate-check synthesis
<!-- roles=orchestrator phases=3B,3C summary="Map gate-check verdicts to forward/drop actions, fold in New findings, then run Steps 1–4 over the union." -->

When this recipe runs against gate-check returns (per
review-iteration.md:orchestrator:3B,3C §Gate-check synthesis
routing), the inputs are verdicts plus optional `New findings`
blocks, not raw dimensional findings.

### Verdict-to-action mapping
<!-- roles=orchestrator phases=3B,3C summary="VERIFIED/REJECTED/MOOT drop, STILL OPEN forwards verbatim, REGRESSION escalates to blocker and forces FAIL." -->

Map the verdicts as follows before walking Steps 1–4:

| Gate-check verdict | Action |
|---|---|
| `VERIFIED` | Drop from the forwarded list. Cleared this iteration. |
| `REJECTED` | Drop from the forwarded list. Reviewer recanted; log a `REJECTED-VERDICT` entry in the §Synthesis audit trail (Step 5 output) with the rationale. |
| `MOOT` | Drop from the forwarded list. Code path no longer reachable. |
| `STILL OPEN` | Forward verbatim with the original `M<n>` ID and severity. |
| `REGRESSION` | Escalate to `blocker` with `revert-or-repair` guidance; carry the regression's `file:line` plus the original finding ID into the next implementer spawn. A `REGRESSION` forces the iteration `FAIL` even if every other verdict is `VERIFIED`. |

### Dedup and termination
<!-- roles=orchestrator phases=3B,3C summary="REGRESSION rows never merge and forward standalone; an empty post-Steps-1–4 list returns PASS and exits the loop." -->

`REGRESSION` rows never merge with other rows during Step 1, even
at the same `file:line`. Their `revert-or-repair` guidance is
load-bearing and must reach the implementer unmerged. Forward
`REGRESSION` findings as standalone entries; other findings at the
same location continue through normal dedup.

After mapping, fold any `New findings` from the gate-check reports
into the resulting list and run Steps 1–4 across the union. The
output format from Step 5 applies unchanged.

If the synthesised in-scope list is empty after Steps 1–4, return
PASS and exit the review loop without respawning the implementer.
A `REGRESSION` verdict always forces `FAIL` and is forwarded as a
standalone in-scope entry per Step 1 above, so an empty list and a
`REGRESSION` verdict cannot co-occur; the regression's
`revert-or-repair` guidance always reaches the implementer.
