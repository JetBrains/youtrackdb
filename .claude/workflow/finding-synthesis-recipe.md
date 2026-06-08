# Finding Synthesis Recipe

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Step 1 — Validate the manifest and collapse by `loc` | orchestrator | 3B,3C | Run the `§2.5` count grep, then collapse manifest index entries sharing a `loc` into one bucket key, no body read. |
| §`loc`-collapse without a body read | orchestrator | 3B,3C | Group index entries by loc so co-located findings route as one implementer concern; REGRESSION rows stay unmerged. |
| §Worked example — 5-way `Thread.sleep` co-location | orchestrator | 3B,3C | A worked 5-way loc-collapse: one polling-loop site flagged by five dimensions stays five addressable ids in one bucket. |
| §Step 2 — Upgrade-only severity backstop | orchestrator | 3B,3C | Scan each entry's basis for an under-severed correctness/crash/CI-hang/data-loss impact and upgrade; never downgrade. |
| §Step 3 — Bucket on the index | orchestrator | 3B,3C | Sort each loc bucket into in-scope-now, next-iteration, or plan-correction by sev and loc; Review-mode overrides by id. |
| §In-scope, this iteration | orchestrator | 3B,3C | All blockers plus correctness/crash-safety/CI-hang should-fixes and small in-scope fixes fitting the pre-spawn budget. |
| §In-scope, next iteration | orchestrator | 3B,3C | Style/naming/boundary should-fixes, cleanup depending on this iteration's fixes, and budget-displaced items. |
| §Deferred — plan correction or episode note | orchestrator | 3B,3C | Out-of-track suggestions and over-scope refactors route to plan corrections by loc; drop a rejected suggestion outright. |
| §Step 4 — Pre-spawn budget check | orchestrator | 3B,3C | The ~15-finding / ~10-file ceiling, the 8–12 target, when to split vs accept a larger spawn, and high-volume handoff. |
| §Why the headroom matters | orchestrator | 3B,3C | Aim for 8–12 findings touching ≤6–8 files: per-test re-runs, gate-check carry-overs, and message-budget margin. |
| §Splitting vs accepting a larger spawn | orchestrator | 3B,3C | Split over-12-finding sets by severity, but accept a larger spawn for independent small fixes or atomic refactors. |
| §High-volume routing and handoff | orchestrator | 3B,3C | When the binary split cascades (≳24 findings), let the context-consumption gate fire the handoff, not a user prompt. |
| §Step 5 — Implementer spawn handoff | orchestrator | 3B,3C | The handoff for the implementer's findings: block: review-file paths, in-scope id/loc/sev/anchor rows, high-water marks. |
| §Gate-check routing | orchestrator | 3B,3C | Map gate-check verdicts to forward/drop actions, fold in New findings, then re-run Steps 1–4 over the union. |
| §Verdict-to-action mapping | orchestrator | 3B,3C | VERIFIED/REJECTED/MOOT drop, STILL OPEN forwards by id, REGRESSION escalates to blocker and forces FAIL. |
| §Dedup and termination | orchestrator | 3B,3C | REGRESSION rows never collapse and forward standalone; an empty post-Steps-1–4 list returns PASS and exits the loop. |

<!--Document index end-->

Shared procedure for routing a multi-agent dimensional review
fan-out to the per-iteration implementer without the orchestrator
reading a finding body. The reviewers write the `§2.5` manifest-plus-
sections review files; this recipe routes on the manifest index alone
(`id` / `sev` / `loc` / `anchor` / `basis`), preserves every reviewer
`id` end to end, and hands the implementer file paths plus in-scope
anchors so it reads the bodies and reconciles cross-dimension framings
at the code level.

The file schema, the field split (mandatory `id`/`sev`/`anchor` vs
downstream `loc`/`cert`/`basis`), the ID-anchored count-validation grep,
and the `CONTRACT_VIOLATION` whole-section fallback are defined once in
conventions-execution.md:orchestrator,implementer:2,3A,3B,3C `§2.5`.
This recipe consumes that schema; it does not restate it.

**Load on demand** at every routing call site:

- track-code-review.md:orchestrator:3C §Synthesis (Phase C
  track-level review — initial spawn and the post-gate-check
  re-routing).
- step-implementation.md:orchestrator:3B §Per-Step
  Orchestration Loop sub-step 4(b) (Phase B `risk: high` step-level
  review).
- review-iteration.md:orchestrator:3B,3C §Gate-check synthesis
  routing (re-run on aggregated gate-check `New findings` blocks
  before composing the next implementer input).

Routing produces three outputs, in this order:

1. A `loc`-collapsed bucketing of the manifest index entries, each
   bucket keyed on `loc` and carrying every contributing reviewer `id`
   as an individually addressable anchor.
2. A backstop severity pass that upgrades any entry whose `basis`
   names an under-severed correctness/crash/CI-hang/data-loss impact;
   no downgrades, no `blocker` second-guessing.
3. A split into **in-scope this iteration**, **deferred to next
   iteration**, and **plan correction / out-of-track** buckets, keyed
   on `sev` and in-scope `loc`.

Apply the steps below in order. Skip a step that is inapplicable
(e.g., a 4-finding fan-out across distinct `loc`s has no collapse to
do).

**No-bodies invariant (S1).** The orchestrator routes on the manifest
index and never ingests `## Findings` in steady state. The one bounded
exception is a single contested-finding drill-down (Step 3 below),
pulled transiently by anchor and dropped before the next teardown. The
implementer is the actor that reads bodies; this recipe never instructs
the orchestrator to read one.

If every selected agent's manifest reports `findings: 0`, emit:

```markdown
## Routed findings — iteration {N}/3

(no findings)
```

and signal PASS to the caller. Do not respawn the implementer.

---

## Step 1 — Validate the manifest and collapse by `loc`
<!-- roles=orchestrator phases=3B,3C summary="Run the `§2.5` count grep, then collapse manifest index entries sharing a `loc` into one bucket key, no body read." -->

Before routing on any manifest, validate it. For each review file,
run the `§2.5` ID-anchored count-validation grep and confirm the
heading-anchor count equals the manifest's claimed `findings` count
(S4); the grep reads heading lines only, so validation never ingests a
body (S6):

```bash
grep -cE '^### [A-Z]+[0-9]+ ' <review-file>
```

When the count matches (the manifest carries `flags: [CONTRACT_OK]`),
trust the index and proceed to the `loc`-collapse below. When the
counts differ (the manifest carries `flags: [CONTRACT_VIOLATION]`), do
**not** read the body to reconcile: route the **whole-section
fallback to the implementer** per `§2.5` — hand the implementer the
review-file path with an instruction to read the entire `## Findings`
section and fix at the code level. A tactical file's fallback owner is
the implementer, never the orchestrator, so a malformed manifest never
forces a body read on the orchestrator and S1 holds through validation.

A `CONTRACT_VIOLATION` file counts as **one budget unit** at routing
time (Step 4): the orchestrator cannot count its findings by `id`
without a body read, which S6 forbids, so the untrusted index
contributes a single unit to the pre-spawn budget. If the body's true
finding count blows the iteration budget once the implementer reads it,
the implementer flags it through the normal failure return
(`recommended_action: retry` at step level, `escalate` at track level
per implementer-rules.md:implementer:3B,3C §Fundamental failure) — the
orchestrator never body-counts to pre-empt this. When every contributing
review file is `CONTRACT_VIOLATION`, the handoff degenerates to a pure
whole-section handoff: every file lands under the §Step 5 whole-section
fallback sub-section and no anchor section is emitted (rather than an
empty or PASS handoff).

### `loc`-collapse without a body read
<!-- roles=orchestrator phases=3B,3C summary="Group index entries by loc so co-located findings route as one implementer concern; REGRESSION rows stay unmerged." -->

When the fan-out is wide (baseline 4 + at least 2 conditional groups,
typical for any track that touches storage or perf-sensitive paths),
expect 30–80 manifest entries across the files with 30–60 %
cross-dimension co-location: five reviewers can flag the same code
site through five different lenses, each emitting its own `### <id> `
anchor at the same `loc`.

The collapse is **non-destructive**. Group the manifest index entries
that share a `loc` into one bucket so the orchestrator routes a
co-located cluster as one implementer concern, but keep every
contributing `id` individually addressable inside the bucket. The
implementer reads each `id`'s body by anchor and decides at the code
level whether the cluster is one concern (same fix → one edit) or
distinct concerns at the same site (separate edits). The orchestrator
does not make that call — it never reads the bodies.

Group on `loc` alone:

- **Same `loc`**: group deterministically on **exact `file:line`** (and
  overlapping line ranges) — these entries always share a bucket key.
  Near-but-unequal `loc` proximity (a few lines apart) is an **optional
  grouping convenience, not a load-bearing rule**: any reasonable
  grouping is acceptable because the orchestrator's bucketing is
  non-destructive and every `id` stays individually addressable, so the
  implementer re-decides one-concern-vs-distinct at the code level
  regardless of how the orchestrator grouped. There is intentionally no
  ±N threshold or method-body test — the orchestrator cannot determine a
  method's extent from a `file:line` manifest field without a body read
  (S1 forbids it), and a fixed-distance test would be non-transitive
  with no tie-break, making bucketing walk-order-dependent. Distant
  lines in the same file stay in separate buckets.
- **No `loc`** (a missing-test flag, a missing-invariant flag, an
  architectural plan-vs-code drift entry whose `loc` names a component
  or test class rather than a `file:line`): use that component or test
  class as the bucket key. When even a component cannot be named, the
  entry routes as its own singleton bucket.
- **REGRESSION rows never collapse** (S3). A REGRESSION-flagged entry
  forwards as a standalone bucket even at a shared `loc`, because its
  `revert-or-repair` guidance is load-bearing and must reach the
  implementer unmerged. No REGRESSION entry exists at initial review;
  the exclusion fires only on re-routing, when a gate-check REGRESSION
  verdict re-enters this collapse (see §Gate-check routing).

The bucket key is `loc` (or the component substitute); the bucket's
**severity** is the strictest `sev` among its entries (`blocker` >
`should-fix` > `suggestion`), used only for Step 3 bucketing. Severity
at a shared `loc` is still routed per `id`, not flattened to the
bucket — a `blocker` and a `suggestion` at one `loc` reach the
implementer as two addressable anchors, and the implementer fixes each
on its own merits.

### Worked example — 5-way `Thread.sleep` co-location
<!-- roles=orchestrator phases=3B,3C summary="A worked 5-way loc-collapse: one polling-loop site flagged by five dimensions stays five addressable ids in one bucket." -->

A `Thread.sleep(10)` polling loop in a CAS WAL test gets flagged by
five dimensions at one `loc`. Condensed manifest index entries (drawn
from each reviewer's review file):

| Dimension | Index entry |
|---|---|
| `review-bugs-concurrency` | `{id: BC3, sev: should-fix, loc: CASDiskWriteAheadLogLifecycleTest.java:142, basis: "fixed-sleep polling can mask a race"}` |
| `review-test-concurrency` | `{id: TX1, sev: blocker, loc: CASDiskWriteAheadLogLifecycleTest.java:142, basis: "30 s test timeout fires before WAL flush on slow CI; suite hangs"}` |
| `review-performance` | `{id: PF2, sev: suggestion, loc: CASDiskWriteAheadLogLifecycleTest.java:142, basis: "busy-loop wastes ~100 ms per invocation"}` |
| `review-test-structure` | `{id: TS5, sev: should-fix, loc: CASDiskWriteAheadLogLifecycleTest.java:142, basis: "long poll signals weak WAL-state isolation"}` |
| `review-code-quality` | `{id: CQ9, sev: should-fix, loc: CASDiskWriteAheadLogLifecycleTest.java:142, basis: "prefer CountDownLatch/Awaitility over bare Thread.sleep"}` |

After `loc`-collapse, one bucket keyed on
`CASDiskWriteAheadLogLifecycleTest.java:142` carrying five addressable
anchors:

```markdown
### loc bucket: CASDiskWriteAheadLogLifecycleTest.java:142 [blocker]
ids: BC3, TX1, PF2, TS5, CQ9
anchors: "### BC3 ", "### TX1 ", "### PF2 ", "### TS5 ", "### CQ9 "
```

The bucket severity is `blocker` — strictest of the five, contributed
by `TX1`. Step 2's backstop sees `TX1` is already a `blocker`, so no
upgrade fires (`PF2`'s `suggestion` `basis` describes a performance
miss, not an under-severed correctness one). In Step 3 the `blocker`
bucket routes **In-scope this iteration**. In Step 4 a one-bucket
spawn is well under the 8–12 target, so no split is needed. The Step 5
handoff hands the implementer the review-file path plus the five
anchors; the implementer reads all five bodies and, finding one shared
concern (await the flushed condition), applies one edit that satisfies
every contributing dimension. Each `id` survives end to end for the
per-dimension gate-check.

---

## Step 2 — Upgrade-only severity backstop
<!-- roles=orchestrator phases=3B,3C summary="Scan each entry's basis for an under-severed correctness/crash/CI-hang/data-loss impact and upgrade; never downgrade." -->

The orchestrator trusts the reviewer's self-assigned `sev`. It does
not re-judge severity by reading bodies — that was the body-dependent
OVERRIDE this routing replaces. The one exception is an **upgrade-only
backstop**, run over the manifest `basis` field alone (D4).

Scan every `suggestion`- or `should-fix`-severity index entry's
`basis` for an impact statement that names a **correctness**,
**crash-safety**, **CI-hang**, or **data-loss** consequence. When the
`basis` describes such an impact but the `sev` sits below `blocker`,
upgrade the entry to the severity its `basis` implies — typically
`blocker` for a crash/data-loss/CI-hang `basis`, `should-fix` for a
correctness `basis` on a non-critical path. Never downgrade, and never
second-guess an entry the reviewer already labelled `blocker`.

The asymmetry is deliberate. A dropped downgrade is the cheap
direction: a nit routes in-scope, the implementer fixes it, nothing
ships broken. A missed upgrade ships a real bug. The backstop catches
only the second, costlier miss, and it does so from a one-line `basis`
without a body read.

**The highest-frequency miss** the backstop targets is a real
correctness or CI-hang issue self-labelled `suggestion` with a
performance-framed `basis` — the polling-loop site the §"5-way
`Thread.sleep` co-location" example flags five ways. When `PF2` is the
only dimension that flagged the loop and frames it as "busy-loop wastes
~100 ms," the literal `basis` reads as a perf nit; but a `basis` that
also names "30 s test timeout fires before WAL flush on slow CI; suite
hangs" carries a CI-hang impact and upgrades to `blocker`. The
backstop reads the impact words, not the severity label.

**The backstop is a net, not a guarantee (A3).** Two blind spots
remain and are accepted:

- **A `basis` that under-describes its own impact.** A finding whose
  `basis` frames a correctness bug as a style preference is missed —
  the same blind spot a body read would also fall into if the body
  itself under-described the impact. The mitigation lives upstream, in
  the reviewer prompt that asks for an honest impact-first `basis`, not
  in this scan.
- **Emergent severity.** Findings that are each individually benign but
  combine into a real defect (two independently-safe lock acquisitions
  that together invert an ordering) are not caught: the scan reads each
  `basis` in isolation. Cross-finding emergent severity is the
  implementer's to notice at the code level, or a later reviewer's.

The backstop runs once over the collapsed buckets, before bucketing.
It mutates only `sev`; it never reads `## Findings`.

---

## Step 3 — Bucket on the index
<!-- roles=orchestrator phases=3B,3C summary="Sort each loc bucket into in-scope-now, next-iteration, or plan-correction by sev and loc; Review-mode overrides by id." -->

For each `loc` bucket, choose one of three destinations using the
bucket's `sev` and its `loc` (in-track vs out-of-track), never a body
read:

### In-scope, this iteration
<!-- roles=orchestrator phases=3B,3C summary="All blockers plus correctness/crash-safety/CI-hang should-fixes and small in-scope fixes fitting the pre-spawn budget." -->

- All `blocker` buckets at an in-track `loc`.
- `should-fix` buckets whose `basis` touches concurrency correctness,
  crash-safety invariants, CI-hang risks, or the falsifiability of
  newly-added tests.
- `should-fix` buckets whose fix shape is small enough to fit
  alongside the items above within the pre-spawn budget (Step 4).

### In-scope, next iteration
<!-- roles=orchestrator phases=3B,3C summary="Style/naming/boundary should-fixes, cleanup depending on this iteration's fixes, and budget-displaced items." -->

- `should-fix` buckets whose `basis` touches style, naming, boundary
  cases, minor falsifiability tightenings, dead-code removal, or
  comment clarity.
- Cleanup that depends on this iteration's fixes landing first
  (e.g., a rename of a helper introduced in this iteration).
- `should-fix` items displaced from iteration 1 by the pre-spawn
  budget (Step 4).

### Deferred — plan correction or episode note
<!-- roles=orchestrator phases=3B,3C summary="Out-of-track suggestions and over-scope refactors route to plan corrections by loc; drop a rejected suggestion outright." -->

- `suggestion`-severity buckets whose `loc` falls in a different
  track. Route via
  track-code-review.md:orchestrator:3C §Plan Corrections
  from Deferred Findings, keyed on `loc`.
- Structural refactors whose scope exceeds the current track's goals
  (e.g., a `basis` that asks to "extract this 200-line method into a
  new class hierarchy" surfaced during a 2-line bug-fix track). Plan
  correction.
- Buckets the orchestrator judges out-of-scope despite the reviewer
  flagging them. When the rationale is purely "low value", treat as a
  rejected `suggestion` and drop it from the routing entirely rather
  than routing it to a plan correction. Whether the orchestrator may
  drop a bucket without reading its body is a judgement on the
  manifest's `basis` and `loc`; when the `basis` is too thin to judge
  and the bucket is a candidate for in-scope work, that is a
  contested-finding drill-down — see below.

The split is a judgement call on `sev`, `basis`, and `loc`; the
destinations above are the default, not a contract.

**Mixed-destination bucket (anchors split across destinations).** A
`loc` bucket whose anchors do not all route to the same destination —
e.g., one in-track `blocker` and one out-of-track `suggestion` at the
same `loc` — routes **as a whole to its strictest-severity
destination**. The `blocker` keeps the whole bucket in-scope, and the
per-`id` anchors that would individually defer ride along in-scope
rather than splitting the bucket apart, so the implementer reads the
co-located bodies together and reconciles them in one pass (the
non-destructive collapse already keeps every `id` addressable, so a
ride-along anchor the implementer judges genuinely out-of-track surfaces
as a deferred finding in the next gate-check). A bucket with **no**
in-scope anchor — every anchor defers or routes out-of-track — routes
wholly to its deferred / plan-correction destination.

**Contested-finding drill-down (the bounded S1 exception).** When a
single bucket's in-scope/deferred destination genuinely cannot be
decided from its manifest fields — a `basis` too terse to judge, a
`loc` that may or may not fall in-track — the orchestrator pulls **one**
finding's evidence by anchor: the `cert`/evidence block from the
`## Evidence base` section by its `cert` anchor when the finding's
dimension writes a cert, or the single `### <id> ` finding body by its
`anchor` when the dimension is one of the 6 evidence-trail-exempt
dimensions that emit `cert: n/a` (R5). Either way it is one transient
block, read for the one decision and dropped before the next teardown,
so S1's bound holds. This is the only place the orchestrator touches a
tactical body, and it is the exception, not the routine.

**Review-mode guidance override (track level only).** When the user
gave explicit bucketing guidance during a prior Review-mode pass, that
guidance overrides the default for the items it named. Review-mode
guidance names reviewer `id`s directly (`BC3`, `TX1`, …) — the same
IDs that survive end to end (S2). Match each guidance `id` against the
manifest index `id` directly and apply the user's bucket override
before final assignment. There is no merge layer and no contributing-
dimensions list to walk: the `id` is the match key.

Step-level routing (per
step-implementation.md:orchestrator:3B §Per-Step
Orchestration Loop sub-step 4(b)) runs before any Review-mode pass
exists; this override is unreachable there and the default bucketing
always wins.

---

## Step 4 — Pre-spawn budget check
<!-- roles=orchestrator phases=3B,3C summary="The ~15-finding / ~10-file ceiling, the 8–12 target, when to split vs accept a larger spawn, and high-volume handoff." -->

The hard ceiling from
track-code-review.md:orchestrator:3C §Review loop step 2 is
~15 in-scope findings or ~10 distinct source files per implementer
spawn. The two homes for this ceiling are deliberately kept in sync:
the inline check in `track-code-review.md` §Review loop step 2 is the
enforcement point the orchestrator runs; this section is the rationale
and the splitting procedure it cites. Neither is orphaned — the inline
check owns the gate, this recipe owns the "why" and the "how to split."

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

Count findings by contributing `id`, not by `loc` bucket: a 5-`id`
bucket at one `loc` is five findings against the budget even though it
routes as one implementer concern, because the implementer still reads
five bodies and reconciles them.

### Splitting vs accepting a larger spawn
<!-- roles=orchestrator phases=3B,3C summary="Split over-12-finding sets by severity, but accept a larger spawn for independent small fixes or atomic refactors." -->

When the in-scope set exceeds 12 findings, split it: take the
highest-severity 8–12 items first, displace the rest to iteration 2.
The displaced items re-surface in iteration 2's gate-check fan-out and
consume an iteration counter normally — they are not lost.

**Soft pacing, not a hard split.** When the findings are genuinely
independent and the per-finding fix is small (e.g., 20
identical-pattern Javadoc fixes touching 10 files), one large
iteration is still fine. When iteration counts are already tight
(2 of 3 used) and the remaining findings cohere, accept the larger
spawn and note the over-budget choice in the handoff (Step 5) with
the rationale.

When a single `loc` bucket inherently spans more files than the
per-iteration ceiling (systematic rename, polymorphic SPI signature
change, package move), keep it as one bucket and note the over-ceiling
decision in the handoff. Splitting an atomic refactor across
iterations is worse than the over-budget spawn — the partial states
between iterations would not compile.

### High-volume routing and handoff
<!-- roles=orchestrator phases=3B,3C summary="When the binary split cascades (≳24 findings), let the context-consumption gate fire the handoff, not a user prompt." -->

When the in-scope set is so large that the binary split cascades
across iterations (≳24 findings), do not surface a decision to the
user. The context-consumption gate at the end of each iteration is the
natural pause point — high-volume routing drives context pressure into
`warning` / `critical`, which fires the handoff per
mid-phase-handoff.md:orchestrator:3B,3C. The collapsed buckets and the
in-scope/deferred split are preserved across the handoff as
work-in-progress (the manifest index and bucketing, never a body); a
fresh-context orchestrator on resume can decide whether to continue,
`ESCALATE`, or route some items to plan corrections with cache
headroom intact.

---

## Step 5 — Implementer spawn handoff
<!-- roles=orchestrator phases=3B,3C summary="The handoff for the implementer's findings: block: review-file paths, in-scope id/loc/sev/anchor rows, high-water marks." -->

The handoff the orchestrator composes into the implementer's
`findings:` input carries **review-file paths plus in-scope anchors**,
never finding bodies. The implementer reads the bodies by anchor and
fixes at the code level. Present the routed list in this shape:

```markdown
## Routed findings — iteration {N}/3

### In-scope this iteration
Review files:
- docs/adr/{dir}/_workflow/plan/track-{N}/reviews/{type}-iter{N}.md
- ... (one per contributing review file)

#### loc bucket: <file:line> [<strictest sev>]
ids: BC3, TX1, CQ9
anchors: "### BC3 ", "### TX1 ", "### CQ9 "
basis (per id, manifest-sourced, no body read):
  - BC3 [should-fix]: <basis line>
  - TX1 [blocker]: <basis line>
  - CQ9 [should-fix]: <basis line>

#### loc bucket: <file:line> [should-fix]
... (one per in-scope bucket)

### Whole-section fallback (CONTRACT_VIOLATION)
- docs/adr/{dir}/_workflow/plan/track-{N}/reviews/{type}-iter{N}.md
- ... (one per review file whose manifest failed §2.5 count-validation)

### Deferred to next iteration
- BC7 (should-fix style) — displaced by pre-spawn budget.
- TS8 (should-fix boundary case) — depends on the prior bucket landing.

### Plan corrections (route to other tracks)
- PF4 — belongs in Track <N+M> per its `loc`. Route via
  [`track-code-review.md`](track-code-review.md) §Plan Corrections
  from Deferred Findings.

### Per-dimension high-water-marks (handed to the reviewer at re-spawn)
- BC: 9   TX: 1   PF: 4   TS: 8   CQ: 9
```

The handoff carries the manifest-sourced `basis` line per `id` (one
line each, copied from the index — not a body read) so the implementer
sees the impact framing without the orchestrator ingesting `##
Findings`. The implementer reaches the full body by anchor when it
needs the reviewer's detail.

**`id`s are the sole addressing (D5).** There is no `M<n>` merge layer:
every routed finding carries its reviewer-assigned `id` (`BC3`, `TX1`)
end to end, into the implementer's `findings:` block, its `Review fix:`
commit message, and the per-dimension gate-check. The `id` is preserved
verbatim and never renumbered (S2) — it is both the bucketing dimension
proxy and the Review-mode override match key.

**Per-dimension high-water-marks.** The orchestrator hands each
dimension's highest-seen `id` number back to the reviewer at the next
spawn, so the reviewer continues its own numbering (`BC10`, `BC11`)
rather than restarting. This applies at initial review too, not only at
gate-check: the reviewer self-assigns from the high-water-mark the
orchestrator passes. The mark per dimension is the max `id` index seen
across this loop's review files for that prefix.

**Whole-section fallback (CONTRACT_VIOLATION).** A review file whose
manifest failed the `§2.5` ID-anchored count-validation (Step 1) cannot
be routed by anchor — its index is untrusted. The orchestrator lists
each such file under the `### Whole-section fallback
(CONTRACT_VIOLATION)` sub-section as a path only, with no anchor rows.
The implementer reads the **entire `## Findings` section** of each listed
file (not just anchors) and reconciles at the code level, mirroring the
`§2.5` fallback contract (the implementer is the fallback owner for a
violated tactical file). The implementer-side contract for this entry
shape is implementer-rules.md:implementer:3B,3C §Inputs the orchestrator
passes on each spawn (the `findings:` input rule). The sub-section is
parallel to `### In-scope this iteration`: it carries paths, not
`id`/`loc`/`sev`/`anchor` rows, and "omit empty sections" applies to it
too — a fan-out with no violated manifest emits no whole-section block.

Omit empty sections; never emit a heading with no content beneath it.

---

## Gate-check routing
<!-- roles=orchestrator phases=3B,3C summary="Map gate-check verdicts to forward/drop actions, fold in New findings, then re-run Steps 1–4 over the union." -->

When this recipe runs against gate-check returns (per
review-iteration.md:orchestrator:3B,3C §Gate-check synthesis
routing), the inputs are per-`id` verdicts plus optional `New findings`
blocks, not raw dimensional manifests.

### Verdict-to-action mapping
<!-- roles=orchestrator phases=3B,3C summary="VERIFIED/REJECTED/MOOT drop, STILL OPEN forwards by id, REGRESSION escalates to blocker and forces FAIL." -->

Map the verdicts as follows before re-running Steps 1–4:

| Gate-check verdict | Action |
|---|---|
| `VERIFIED` | Drop from the forwarded list. Cleared this iteration. |
| `REJECTED` | Drop from the forwarded list. The reviewer recanted; the verdict itself is the record (a per-dimension gate-check return carrying the recant), so the next reviewer instance does not re-raise the same `id`. No separate audit-trail entry is minted. |
| `MOOT` | Drop from the forwarded list. Code path no longer reachable. |
| `STILL OPEN` | Forward by the original reviewer `id` and severity. |
| `REGRESSION` | Escalate to `blocker` with `revert-or-repair` guidance; carry the regression's `loc` plus the original reviewer `id` into the next implementer spawn. A `REGRESSION` forces the iteration `FAIL` even if every other verdict is `VERIFIED`. |

### Dedup and termination
<!-- roles=orchestrator phases=3B,3C summary="REGRESSION rows never collapse and forward standalone; an empty post-Steps-1–4 list returns PASS and exits the loop." -->

`REGRESSION` rows never collapse with other rows during Step 1's
`loc`-collapse, even at the same `loc` (S3). Their `revert-or-repair`
guidance is load-bearing and must reach the implementer unmerged.
Forward `REGRESSION` findings as standalone buckets; other findings at
the same location continue through normal `loc`-collapse.

After mapping, fold any `New findings` from the gate-check reports into
the resulting list and re-run Steps 1–4 across the union. The handoff
format from Step 5 applies unchanged.

If the in-scope list is empty after Steps 1–4, return PASS and exit
the review loop without respawning the implementer. A `REGRESSION`
verdict always forces `FAIL` and is forwarded as a standalone in-scope
entry per Step 1 above, so an empty list and a `REGRESSION` verdict
cannot co-occur; the regression's `revert-or-repair` guidance always
reaches the implementer.
