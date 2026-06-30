<!-- workflow-sha: 8b0a24709d3369f1c78740210f86acd9f51d404e -->
# Track 1: Phase-C review iteration keyed to the per-track complexity tag

## Purpose / Big Picture
After this track, Phase-C termination is keyed to the per-track complexity tag,
not a fixed three-iteration cap. Three rules replace the cap:

- Blockers loop until clear at every level (uncapped).
- Should-fix loop depth scales with the tag: `low` none, `medium` up to three,
  `high` uncapped.
- No-progress detection — escalate the moment an iteration clears nothing —
  replaces the fixed cap-3 escalation as the safety valve.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The Phase-C track code review loop today caps every track at three iterations and
escalates if blockers survive the cap. The per-track complexity tag
(`low` / `medium` / `high`, reconciled to `max(step tags)` and read track-scoped
from the phase ledger at the A→C boundary) currently moves a rigor dial *within*
that flat cap-3 ceiling: `low` runs a single shallow pass, `medium` the normal
cap-3, `high` runs all three iterations. This track re-keys that dial from "how
many iterations" to "what terminates the loop." The blocker loop becomes uncapped
at every level; the should-fix loop's depth scales with the tag; and the cap-3
safety valve is replaced by no-progress detection, which reads the gate-check
verdict stream the loop already emits and escalates when findings stop shrinking.
The change touches workflow prose only — no scripts, hooks, settings, or Java —
and is scoped to Phase C alone; Phase-2 plan reviews, Phase-A track reviews, and
Phase-B step reviews keep the canonical cap-3-then-escalate behavior.

## Progress
- [x] 2026-06-30T16:33Z [ctx=info] Review + decomposition complete
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-30T17:20Z [ctx=safe] Step 1 complete (commit da53127753)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- **2026-06-30 [Phase A]** The Phase-1 restate mechanism — a grep scoped to
  `track-code-review.md` alone — structurally missed two other Phase-C-loading
  files carrying standalone cap-3 assertions that describe the track-level loop:
  `code-review-protocol.md:53` (`Max 3 iterations per level.`) and
  `design-decision-escalation.md:62` (`Phase C: track-level code review (up to 3
  iterations …)`). Surfaced by Phase-A risk (R1) and adversarial (A1, A2). The
  in-scope set, Plan of Work (edits 7-8 + the tree-wide restate authority),
  Validation, and Invariants were broadened to the Phase-C-loading file set. This
  extends D2.1's "announce the override at every canonical home" principle to two
  more homes; no decision changed — D2's scope stays Phase-C behavior only.
- **2026-06-30 [Phase B]** Step-level dimensional review extended the restate set
  to a seventh Phase-C-loading file outside the track's in-scope six.
  `finding-synthesis-recipe.md` emitted `## Routed findings — iteration {N}/3`
  headers (lines 79, 451) that read as a cap-3 bound for the now-uncapped Phase-C
  loop, because the orchestrator loads the recipe in both the still-capped
  step-level loop and the uncapped Phase-C loop. Surfaced by workflow-consistency
  (WC1, should-fix); fixed by dropping the `/3` denominator and routing the file
  into the staged tree, leaving line 414 `(2 of 3 used)` as step-level pacing.
  Same pattern as the Phase-A R1/A1/A2 expansion — the tree-wide restate authority
  covers the whole Phase-C-loading file set, not the enumerated list. Two further
  in-file residuals (the `review-iteration.md` §Iteration flow diagram and §Limits
  TOC summary) were caught by workflow-instruction-completeness (WI1/WI2) and
  fixed in the same review-fix commit. See Episodes §Step 1.

## Decision Log
<!-- The track-canonical live decision carrier (D7). Phase 1 seeds the full
inline Decision Records this track owns, seeded from the frozen design.md
D-records. One block per decision (full four-bullet form). -->

### D1: Tag axis is the per-track complexity tag, never the per-step risk tag
The new iteration policy is keyed on the per-track complexity tag
(`low` / `medium` / `high`, reconciled to `max(step tags)` and read track-scoped
from the phase ledger at the A→C boundary), not the per-step risk tag.

- **Alternatives considered:** The per-step risk tag, which gates Phase-B step
  review. Rejected because it is step-scoped and has no single track-level value
  to read — keying a track loop on a per-step tag would have no well-defined
  input at the A→C boundary.
- **Rationale:** The per-track complexity tag is the only tag that drives review
  iteration today (it is the Phase-C rigor dial), and it is available at Phase C.
  The proposal is a refinement of that existing dial, not a greenfield rule.
- **Risks / caveats:** "Track risk level" in the originating request overlaps the
  per-step risk-tag vocabulary; the prose must name the per-track complexity tag
  explicitly so the two axes are not conflated.
- **Implemented in:** this track.
- **Full design**: design.md §Core concepts (Per-track complexity tag).

### D2: Scope is the Phase-C track code review loop only
The new per-level iteration policy changes the Phase-C track code review loop's
rigor dial only. Phase-2 plan reviews, Phase-A track reviews, and Phase-B step
reviews keep the canonical cap-3-then-escalate behavior of `review-iteration.md`
§Limits.

- **Alternatives considered:** All review loops — rejected because it would
  require inventing a tag source for Phase 2 (no track tag exists yet, since the
  tag is reconciled at the A→C boundary) and is a larger, messier blast radius.
  Phase-C + Phase-A — Phase A already keys panel breadth off the tag, but the
  user scoped the iteration-depth change to Phase C alone.
- **Rationale:** The per-track complexity tag is only reconciled and available at
  the A→C boundary, so Phase-C is where the dial already lives; the blast radius
  is smallest there and the change is coherent with the existing override
  pattern.
- **Risks / caveats:** `review-iteration.md` §Limits is the canonical shared home
  for the cap-3 protocol and its TOC filter loads it in Phase C, so a Phase-C
  reader landing on §Limits would read "Max 3, then escalate" — which contradicts
  the new uncapped Phase-C policy if the override is not wired there.
- **Implemented in:** this track.
- **Full design**: design.md §Scope and the cap-3-keyed restate sites.

**D2.1 — wire the carve-out into `review-iteration.md` §Limits, do not merely
assert the override in `track-code-review.md`.** §Limits stays in scope: it keeps
cap-3-then-escalate as the default for Phases 2 / 3A / 3B and gains one explicit
carve-out sentence — Phase-C track code review overrides this per
`track-code-review.md` §Review loop, with iteration depth keyed to the per-track
complexity tag, no fixed cap, terminated by no-progress detection. The override
is announced at the canonical home, not silently asserted only at the override
site, so a Phase-C reader who lands on §Limits via the
`review-agent-selection.md` rigor-dial cross-reference is routed to the override.
Default behavior for the non-Phase-C loops is unchanged.

### D3: The per-level iteration policy (the new dial)
The Phase-C review loop terminates per the reconciled complexity tag, stated as
the delta from today's behavior:

- `low` → the blocker loop that `low` already runs today continues until no
  blockers remain, with the cap removed (terminated by no-progress detection, D4).
  Should-fix does not drive iteration; remaining should-fix surface at track
  completion. `low` therefore relies entirely on no-progress detection for
  termination — it has no should-fix cap as a backstop.
- `medium` → as `low` (uncapped blocker loop) plus iterate up to three times to
  clear should-fix; after three, remaining should-fix surface at track
  completion.
- `high` → loop until no blockers and no should-fix remain; no fixed iteration
  cap on either, terminated by no-progress detection.

- **Alternatives considered:** Keeping the flat cap-3 for all levels — rejected
  because it does not let `high` converge fully. The current dial's "iterate to
  convergence within the cap-3 ceiling" for `high` — rejected because capping at
  three contradicts the user's "no bound on iteration count."
- **Rationale:** Decouples blocker-looping (always loop until clear) from
  should-fix-looping (depth scales with complexity), matching the intent that
  higher-risk tracks get more rigorous convergence.
- **Risks / caveats:** `low` already loops on blockers today — the single-pass
  shortcut shortens optional iteration depth, never the must-fix gates. The `low`
  delta must be framed as "remove the cap on the existing blocker loop," not
  "introduce a loop," or the prose mis-describes today's behavior.
- **Implemented in:** this track.
- **Full design**: design.md §Per-level iteration policy.

**D3.1 — the `medium` single shared counter.** The iteration counter is shared
across all dimensions today (one counter, not independent per-dimension
counters). `medium` needs the blocker loop uncapped while the should-fix loop
caps at three on that same counter. Resolution: keep the single shared counter and
gate only the should-fix continuation. "Should-fix drives a new iteration" is
gated on `iteration ≤ 3`; "a surviving blocker drives a new iteration" is not
gated — it continues past three, bounded by no-progress detection. When a
should-fix finding re-surfaces in a post-3 blocker-driven iteration, it is fixed
opportunistically if the implementer is already touching that code. Otherwise it
is surfaced at track completion. No second counter is introduced.

### D4: No-progress detection replaces the cap-3 escalation safety valve
Because the blocker loops (all levels) and `high`'s should-fix loop are now
uncapped, termination uses no-progress detection: escalate to the user when an
iteration makes no progress, rather than capping at a fixed iteration count.

- **Alternatives considered:** A hard ceiling that still escalates — simpler but
  reintroduces the arbitrary cap the user wanted removed. A truly unbounded loop —
  rejected because a stuck blocker would loop indefinitely with no escape.
- **Rationale:** An unbounded loop on a finding the implementer cannot fix would
  loop forever (across sessions). No-progress detection bounds the loop on the
  real convergence signal — whether findings are shrinking — without a fixed cap,
  so a genuinely-converging high-risk track is never cut off early while a stuck
  loop escalates the moment it clears nothing.
- **Risks / caveats:** The detector must read off a signal that already exists,
  or the change would require new measurement machinery. It reads the gate-check
  verdict stream the loop already emits, so it adds no new machinery.
- **Implemented in:** this track.
- **Full design**: design.md §No-progress detection.

**D4.1 — operational definition on the gate-check verdict stream.** No-progress
detection is read off the verdict stream `review-iteration.md` §Gate-check verdict
handling already emits per carried finding (`VERIFIED` / `REJECTED` / `MOOT` /
`STILL OPEN` / `REGRESSION`):

- **Identity** — a finding is "the same" by its reviewer-assigned `id` (the
  cumulative finding ID the gate-check already verdicts by), not by its text or
  location.
- **Threshold** — an iteration makes no progress when its gate-check returns
  `STILL OPEN` for every finding carried into it, clears none (no `VERIFIED` /
  `MOOT` / `REJECTED`), and surfaces no new fixable finding. One net clear, or one
  new fixable finding, is progress and the loop continues. A `REGRESSION` is
  always progress-negative and escalates immediately, because it already forces a
  `FAIL` under existing verdict handling.
- **Which loops it gates** — each uncapped loop: the blocker loop at all three
  levels and `high`'s should-fix loop. The `medium` should-fix loop is already
  capped at three, so no-progress detection does not gate it. Once a surviving
  blocker carries iterations past three, the blocker loop's no-progress gate
  governs from there.

On a no-progress iteration the orchestrator escalates to the user, surfacing the
surviving findings and the per-iteration verdict history. This is the same
escalation shape cap-3 exhaustion produced, fired on the no-progress signal
instead of a fixed count.

**D4.2 — composition with the existing per-iteration context pause.** The Phase-C
§Review loop already carries a mandatory per-iteration context-consumption check
that halts at `warning` (≥40%) / `critical` (≥50%) and writes a
`mid-phase-handoff.md`. The two termination mechanisms compose on orthogonal axes:
the context pause bounds per-session burn (unchanged — it pauses and resumes next
session, and the cross-session resume re-reads loop state); no-progress detection
bounds convergence (it escalates when findings stop shrinking across iterations,
including across a resume). A `high` track that makes real but slow progress hits
the context pause, hands off, and continues next session. Because it makes real
progress each iteration, it never escalates. A stuck track escalates on the first no-progress
iteration regardless of context level. The context pause never substitutes for no-progress escalation,
and no-progress escalation never preempts a context pause.

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->
- [x] Technical: PASS at iteration 1 (0 findings). All six edit sites resolved
  against the live workflow files; the restate-set grep matched the cited line
  list byte-for-byte.
- [x] Risk: PASS at iteration 2 (1 finding, 1 accepted). R1 (should-fix): the
  `track-code-review.md`-only restate grep missed `code-review-protocol.md:53`.
  Fixed by broadening to the Phase-C-loading file set; gate-verified VERIFIED.
- [x] Adversarial: PASS at iteration 2 (2 findings, 2 accepted). A1/A2
  (should-fix): two more Phase-C-loading files with standalone cap-3 assertions
  (`design-decision-escalation.md:62`, `code-review-protocol.md:53`) outside the
  Phase-1 scope. Fixed by the same broadening (edits 7-8 + tree-wide restate
  authority); both gate-verified VERIFIED. Ran on Opus — Fable 5 unavailable
  (D14 degradation caveat: the model pin degrading to the session default does
  not reopen the decision).

## Context and Orientation
This is a workflow-machinery change for a reader who maintains `.claude/workflow/**`
and knows the Phase A / B / C review structure, the per-track complexity tag (its
reconciliation and ledger home), and the cap-3 review-iteration protocol in
`review-iteration.md`. The codebase at the start of this track holds the existing
flat cap-3 Phase-C loop; the deliverables are the edited rules listed below.

The in-scope files and their current state:

- **`.claude/workflow/track-code-review.md`** — carries the Phase-C review loop.
  Its §Review loop dial site (≈681-693) maps `low` → single shallow pass,
  `medium` → normal cap-3, `high` → iterate to convergence within the cap-3
  ceiling. The section and the rest of the file carry the cap-3 ceiling as live
  behavior at the sites the restate-set grep enumerates (see §Plan of Work,
  edit 3).
- **`.claude/workflow/review-agent-selection.md`** — its §"Complexity sets the
  Phase-C rigor dial, never the set" carries the same `low` / `medium` / `high`
  dial-mapping prose and asserts that complexity moves only iteration depth, never
  which reviewers run.
- **`.claude/workflow/review-iteration.md`** §Limits — the canonical shared home
  for the cap-3 protocol: "Max 3 iterations per review type" / "If blockers
  persist after 3 iterations, escalate." Its TOC filter loads it in Phase C.
- **`.claude/skills/code-review/SKILL.md`** (≈line 225) — a standalone-skill note
  that describes the dial (`low` single shallow pass / `medium` cap-3 / `high`
  iterate to convergence) for prose-sync purposes, even though the `/code-review`
  skill itself takes no complexity input.
- **`.claude/workflow/code-review-protocol.md`** — the shared code-review
  protocol, loaded in Phase C. Its synthesis preamble (≈line 53) asserts
  `Max 3 iterations per level.` as a flat cap covering both the step-level and
  the track-level loop; its §Iteration protocol (≈line 97) calls the shared
  protocol `max 3 iterations` but defers to `review-iteration.md` §Limits by
  pointer, so it inherits edit 6's carve-out. The standalone preamble assertion
  does not. (Surfaced by Phase-A risk R1 / adversarial A2.)
- **`.claude/workflow/design-decision-escalation.md`** §Per-phase autonomy
  (≈line 62) — describes the Phase-C autonomy boundary as
  `Phase C: track-level code review (up to 3 iterations; …)`, a direct
  description of the loop this track uncaps. The Phase-B step-level line just
  above it (`up to 3 per step`) is unchanged — step-level review keeps cap-3.
  (Surfaced by Phase-A adversarial A1.)

The design.md already carries the loop flowchart (design.md §The new Phase-C
review loop); this track does not duplicate it.

## Plan of Work
The edits, in order. Edits 1-3 are the substantive policy change in
`track-code-review.md`; edits 4-8 keep every derived/sync site consistent so no
Phase-C-loading file ships self-contradictory text. Edits 4-6 cover the three
sites known at Phase 1; edits 7-8 add two more Phase-C-loading files the Phase-A
risk and adversarial reviews surfaced (R1, A1, A2), each carrying a standalone
cap-3 assertion the `track-code-review.md`-only grep could not catch.

1. **`track-code-review.md` §Review loop — replace the dial mapping** (≈681-693).
   Swap the current `low` single-pass / `medium` cap-3 / `high` iterate-within-cap-3
   mapping for the new per-level termination policy from §Per-level iteration policy
   (D3): `low` = uncapped blocker loop only (should-fix never drives iteration,
   surfaced at track completion); `medium` = uncapped blocker loop plus up to three
   iterations to clear should-fix; `high` = uncapped on both blocker and should-fix.
   Preserve the existing "the dial shortens optional iteration depth, never the
   must-fix gates" framing and the missing-tag → treat-as-`medium` safe default.

2. **`track-code-review.md` §Review loop — add the new termination machinery.** In
   the same section, add: (a) the no-progress detection definition (D4.1) — identity
   by reviewer-assigned finding `id`, the threshold (`STILL OPEN` for every carried
   finding + zero net clears + no new fixable finding = no progress; one net clear
   or one new fixable finding = progress; `REGRESSION` escalates immediately); (b)
   the `medium` single-shared-counter rule (D3.1) — should-fix continuation gated on
   `iteration ≤ 3`, blockers continue past three, no second counter; (c) the
   composition note (D4.2) — no-progress detection and the per-iteration context
   pause sit on orthogonal axes and neither substitutes for the other.

3. **`track-code-review.md` — restate every cap-3-keyed site the uncapping
   touches.** The authority is the live grep, re-run at implementation time, not a
   frozen line list:
   ```bash
   grep -nE '3 iterations|N/3|/3|of 3|three iteration' .claude/workflow/track-code-review.md
   ```
   The sites as of Phase 1 (verified against the live file) are lines 491, 527, 685
   (the dial site), 724, 765, 832, 837, 848, 875, 1092, 1106, 1256. Each restate:
   - **Progress format** (≈765): `Track-level code review iteration N complete (N/3
     iterations)` drops the `/3` denominator; record `iteration N complete` with no
     fixed denominator.
   - **Step 4 resume** (≈832-847): "Max 3 iterations total across sessions — on
     resume, read the iteration count to determine how many remain" no longer
     applies; with no cap there is no "remaining" count. Restate: resume reads the
     running iteration count plus the no-progress / open-findings state, not a
     remaining-cap count.
   - **Pre-spawn-split rationale** (≈837): "consumes 2 of 3 iterations" drops the
     "of 3" framing; the split still consumes iterations, against no fixed ceiling.
   - **Step 5** (≈848): "If blockers persist after 3 iterations, ... note the
     unfixed findings" becomes "if the loop escalates on no-progress with blockers
     open, note the unfixed findings."
   - **Step 6 commit guard** (≈875): "not when the loop exited with blockers still
     open after 3 iterations" becomes "...exited on no-progress with blockers still
     open."
   - **Checklist seed** (≈1256): the `(1/3 iterations, iteration 1...)` template
     aligns to the cap-free Progress format.
   - **Cost models** (≈491, ≈527): "reviewers × three iterations = eighteen spawns"
     and "× 3 iterations per track" use the cap as a cost bound the change removes;
     reword to a representative or typical count, not a hard `× 3`.
   - **Failure / budget mentions** (≈724 "2 of 3 used", ≈1092 `FAILED at iteration
     N/3`, ≈1106 "If blockers persist after 3 iterations, note them"): restate
     without the `/3` denominator and against the no-progress exit rather than a
     fixed count.

   The `three iteration` alternative in the grep pattern catches the spelled-out
   count at lines 491 and 685 that the digit-only patterns miss — keep it in the
   pattern.

4. **`review-agent-selection.md` §"Complexity sets the Phase-C rigor dial, never
   the set"** — update the `low` / `medium` / `high` dial-mapping prose to the new
   policy. Its cross-reference to §Limits and `track-code-review.md` §Review loop
   stays. Leave the surrounding assertion ("complexity never drops a
   domain-selected reviewer; the dial only changes iteration depth") untouched —
   the dial still changes only iteration depth/termination, never which reviewers
   run.

5. **`code-review/SKILL.md` (≈line 225)** — update the standalone-skill note that
   describes the dial so its prose ("`low` single shallow pass / `medium` cap-3 /
   `high` iterate to convergence") stays in sync with the new mapping. The
   `/code-review` skill itself takes no complexity input, so only the descriptive
   prose changes; the skill's behavior (always run the domain-selected set once) is
   unchanged.

6. **`review-iteration.md` §Limits** — add the one-line Phase-C carve-out sentence
   (D2.1): Phase-C track code review overrides this per `track-code-review.md`
   §Review loop, with iteration depth keyed to the per-track complexity tag, no
   fixed cap, terminated by no-progress detection. Keep "Max 3 iterations per
   review type" / "If blockers persist after 3 iterations, escalate" as the stated
   default for Phases 2 / 3A / 3B.

7. **`code-review-protocol.md` — restate the standalone cap-3 assertion** (≈line
   53). The synthesis preamble `Max 3 iterations per level.` asserts a flat cap
   covering the track-level (Phase-C) loop this track uncaps. Restate it so the
   per-level termination defers to each level's protocol: step-level and
   Phase-2/3A reviews keep `review-iteration.md` §Limits cap-3, while the
   Phase-C track-level loop is keyed to the per-track complexity tag with no fixed
   cap per `track-code-review.md` §Review loop. The §Iteration protocol pointer
   (≈line 97) already defers to `review-iteration.md` §Limits, so it inherits
   edit 6's carve-out — confirm the carve-out landed; no separate edit.

8. **`design-decision-escalation.md` §Per-phase autonomy — restate the Phase-C
   line** (≈line 62). `Phase C: track-level code review (up to 3 iterations; …)`
   becomes the new policy: track-level code review keyed to the per-track
   complexity tag, no fixed iteration cap, terminated by no-progress detection;
   keep the `medium`/`high` focal-point clause. Leave the Phase-B step-level line
   above it (`up to 3 per step`) unchanged — step-level review keeps cap-3.

**Restate authority is the Phase-C-loading file set, not one file.** The R1/A1/A2
findings showed a `track-code-review.md`-only grep structurally misses cap-3 prose
in other files a Phase-C reader loads. At implementation time, re-run the restate
grep over the whole set, then triage each hit:
   ```bash
   grep -rnE '3 iterations|N/3|/3|of 3|three iteration|Max 3|up to 3' \
     .claude/workflow/track-code-review.md \
     .claude/workflow/code-review-protocol.md \
     .claude/workflow/design-decision-escalation.md \
     .claude/workflow/review-iteration.md \
     .claude/workflow/review-agent-selection.md \
     .claude/skills/code-review/SKILL.md
   ```
   Restate any assertion describing the **Phase-C track-level** loop; leave
   step-level (Phase B), Phase-2, and Phase-3A assertions on cap-3 (out of scope),
   and reword illustrative cost-model counts only where they cite the cap as a hard
   bound. One borderline shared-recipe hit — `finding-synthesis-recipe.md:414`
   `(2 of 3 used)` — is pacing guidance shared with the still-capped step-level
   loop; reword only if it reads as a Phase-C bound, otherwise leave it.

## Concrete Steps
<!-- Decomposed at Phase A. One coherent commit: the policy re-key plus every
consistency-coupled sync edit must land together so no intermediate state ships
self-contradictory text. HIGH-isolation rule keeps the whole change in one step
so its step-level dimensional review sees track-code-review.md and all six
sync sites at once. -->

1. Re-key the Phase-C track-code-review loop to the per-complexity-tag termination policy and propagate it to every Phase-C-loading sync site in one consistent commit — Plan-of-Work edits 1-8 (the `track-code-review.md` §Review loop dial mapping D3 + no-progress detection D4.1 + `medium` single-shared-counter D3.1 + context-pause composition D4.2 + every cap-3-keyed restate the tree-wide grep surfaces; the `review-iteration.md` §Limits Phase-C carve-out D2.1; and the `review-agent-selection.md` / `code-review/SKILL.md` / `code-review-protocol.md` / `design-decision-escalation.md` sync restates); edits route through `_workflow/staged-workflow/.claude/...` per §1.7, verify by re-running the tree-wide restate grep + reading each carve-out — risk: high (workflow machinery: re-keys the Phase-C review-iteration termination control-flow protocol; also edits `review-iteration.md` §Limits, the canonical cap-3 home)  [x]  commit: da53127753

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

### Step 1 — commit da53127753, 2026-06-30T17:20Z [ctx=safe]
**What was done:** Re-keyed the Phase-C track-code-review loop from a fixed cap-3
to a per-track-complexity-tag termination policy and propagated it across every
Phase-C-loading sync site in one logical change (Plan-of-Work edits 1-8). In
`track-code-review.md` §Review loop: replaced the dial mapping (`low` = uncapped
blocker loop only; `medium` = uncapped blocker loop plus up-to-three should-fix;
`high` = uncapped on both) and added the no-progress detection definition (D4.1),
the `medium` single-shared-counter rule (D3.1), and the context-pause composition
note (D4.2). Restated every cap-3-keyed track-level mechanic the tree-wide grep
surfaced (Progress format, Step 4 resume, pre-spawn-split rationale, Step 5/6
guards, FAILED format, checklist seed, two cost-model counts) off the fixed `/3`
denominator. Added the §Limits Phase-C carve-out (D2.1) in `review-iteration.md`
and synced `review-agent-selection.md`, `code-review/SKILL.md`,
`code-review-protocol.md`, and `design-decision-escalation.md`. Every edit routed
through the `_workflow/staged-workflow/.claude/...` staged tree per §1.7; live
`.claude/` stays at develop state until the Phase-4 promotion.

**What was discovered:** The step-level dimensional review (five workflow
reviewers, all firing under the single-step-high override that runs the full
track-pass-equivalent selection) found three more Phase-C-loading sites the
original six-file restate set missed — all fixed and gate-verified VERIFIED at
iteration 2. The `review-iteration.md` §Iteration flow diagram still showed
`Iteration 3 → escalate`, and a Phase-C reader is TOC-gated onto that section; a
carve-out note now routes them to the override. Its §Limits TOC summary did not
advertise the new exception. And `finding-synthesis-recipe.md` — a shared
finding-routing recipe outside the six-file set — emitted
`## Routed findings — iteration {N}/3` headers that read as a cap-3 bound for the
now-uncapped Phase-C loop; the `/3` denominator was dropped. The no-progress
threshold's term "new fixable finding" was undefined; it now pins to a new
in-scope `blocker`/`should-fix` finding (a new `suggestion` does not count). One
flagged site, `code-review-protocol.md` §Iteration protocol, was left unchanged:
it states "max 3 iterations" only as a pointer to `review-iteration.md` §Limits,
which carries the carve-out, so it inherits the override — the plan's edit-7
flat-assertion-vs-pointer call.

**What changed from the plan:** The restate set extended to a seventh file,
`finding-synthesis-recipe.md`, beyond the plan's enumerated six. This stays
within the plan's stated "restate authority is the Phase-C-loading file set, not
one file" and mirrors the Phase-A precedent, where R1/A1/A2 had already added two
files. No decision changed; D2's scope stays Phase-C behavior only. See
Surprises §Step 1.

**Key files:**
- `…/staged-workflow/.claude/workflow/track-code-review.md` (new staged copy)
- `…/staged-workflow/.claude/workflow/review-iteration.md` (new staged copy)
- `…/staged-workflow/.claude/workflow/finding-synthesis-recipe.md` (new staged copy)
- `…/staged-workflow/.claude/workflow/review-agent-selection.md` (new staged copy)
- `…/staged-workflow/.claude/workflow/code-review-protocol.md` (new staged copy)
- `…/staged-workflow/.claude/workflow/design-decision-escalation.md` (new staged copy)
- `…/staged-workflow/.claude/skills/code-review/SKILL.md` (new staged copy)

## Validation and Acceptance
Track-level behavioral acceptance (verified by the Phase-C workflow-review agents
plus grep, not by Java tests — this is a prose-only change):

- The Phase-C track code review loop terminates per the per-track complexity tag:
  blockers loop until clear at every level; should-fix loop depth scales with the
  tag (`low` none, `medium` up to three, `high` uncapped); and no-progress
  detection — escalate on the first iteration that clears nothing — replaces the
  fixed cap-3 escalation as the safety valve.
- No cap-3-keyed site describing the **Phase-C track-level loop** still asserts a
  fixed `/3` cap as live behavior, across the Phase-C-loading file set
  (`track-code-review.md`, `code-review-protocol.md`,
  `design-decision-escalation.md`, plus the `review-iteration.md` §Limits
  carve-out, `review-agent-selection.md`, and `code-review/SKILL.md`); every
  restate-set hit reads as restated (cost-model / illustrative) or carries the
  no-progress framing. Step-level (Phase B), Phase-2, and Phase-3A cap-3
  assertions stay live and unchanged.
- `review-iteration.md` §Limits carries the Phase-C carve-out so a Phase-C reader
  landing there is routed to the override, and keeps cap-3-then-escalate as the
  stated default for Phases 2 / 3A / 3B.
- The dial-mapping prose in `review-agent-selection.md` and the standalone note in
  `code-review/SKILL.md` describe the new policy and stay consistent with
  `track-code-review.md`.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
- **Step 1.** Idempotent: the edits are deterministic text replacements driven by
  the tree-wide restate grep over the Phase-C-loading file set — re-running the
  grep and re-applying the same restates converges to the same state, so a partial
  re-run leaves no half-edit. Recovery: `git reset --hard HEAD` discards the
  staged-workflow edits; re-derive the restate set from the live grep and re-apply.
  No durable state, no migration — the only artifacts are the staged
  `_workflow/staged-workflow/.claude/...` copies, which the Phase-4 promotion
  overwrites live.

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In-scope files** (six edited rules):
- `.claude/workflow/track-code-review.md` — the dial mapping, the new termination
  machinery, and every cap-3-keyed restate site.
- `.claude/workflow/review-agent-selection.md` — §"Complexity sets the Phase-C
  rigor dial, never the set" dial-mapping prose.
- `.claude/workflow/review-iteration.md` §Limits — the one-line Phase-C carve-out
  (default cap-3-then-escalate for Phases 2 / 3A / 3B preserved).
- `.claude/skills/code-review/SKILL.md` — the standalone-skill dial note (≈225).
- `.claude/workflow/code-review-protocol.md` — the standalone synthesis-preamble
  cap-3 assertion (≈53) restated; §Iteration protocol (≈97) inherits the §Limits
  carve-out via its pointer. (Added from Phase-A risk R1 / adversarial A2.)
- `.claude/workflow/design-decision-escalation.md` §Per-phase autonomy — the
  Phase-C `up to 3 iterations` line (≈62) restated. (Added from Phase-A
  adversarial A1.)

**Out-of-scope** (keep cap-3 unchanged): `structural-review.md`,
`implementation-review.md`, `track-review.md` (Phase A), `step-implementation.md`
(Phase B), `inline-replanning.md`, the step-level cap-3 path in
`code-review-protocol.md` and the `risk-tagging.md` step-level row, and any other
§Limits consumer outside Phase C. The cap-3-then-escalate default in
`review-iteration.md` §Limits must stay live for those loops.

**Inter-track dependencies:** none — this is a single-track change.

**Staging mode:** this is a **workflow-modifying** change and **stages** under
`conventions.md` §1.7 (ledger `s17` = workflow-modifying). The §1.7(k)
prose-rule opt-out does **not** apply: criterion 2 keeps orchestration loops
staged, and the dominant edit is `track-code-review.md` §Review loop — the
Phase-C orchestration loop's termination control flow, an execution procedure,
not judgment-layer review criteria. So during this branch's own Phase 3, the
implementer routes every edit to `.claude/workflow/**` and
`.claude/skills/**` through `_workflow/staged-workflow/.claude/...`
(copy-then-edit live on first touch), and the live `.claude/` files stay at
develop state until the Phase-4 promotion — so the branch is never reviewed
under its own half-built loop.

## Invariants & Constraints
<!-- Plan-at-start, combined section (D9). Phase 1 writes both the per-track
testable constraints and the testable invariants. -->
- Blockers always loop until clear at every complexity level — the dial never
  shortens the must-fix gate. Verified by: the new policy text in
  `track-code-review.md` §Review loop plus a consistency review.
- The change is scoped to Phase C; `review-iteration.md` §Limits keeps
  cap-3-then-escalate as the stated default for Phases 2 / 3A / 3B. Verified by:
  reading §Limits after the edit.
- After the change, no cap-3-keyed site describing the Phase-C track-level loop
  still asserts a fixed `/3` cap as live behavior, across the Phase-C-loading file
  set (`track-code-review.md`, `code-review-protocol.md`,
  `design-decision-escalation.md`, `review-iteration.md` §Limits,
  `review-agent-selection.md`, `code-review/SKILL.md`). Verified by: re-running
  the tree-wide restate grep over that set and confirming every Phase-C hit reads
  as restated (cost-model / illustrative) or carries the no-progress framing,
  while step-level / Phase-2 / Phase-3A cap-3 assertions stay live.
- `review-iteration.md` §Limits carries the Phase-C carve-out so a Phase-C reader
  landing there is routed to the override. Verified by: reading §Limits.
- The dial only changes iteration depth / termination, never which reviewers run
  (the domain-selected set is unchanged). Verified by: the
  `review-agent-selection.md` text still asserting "complexity never drops a
  domain-selected reviewer".

## Base commit
1570fd20ae7d6b5524d46a6c762b06b929b29278
