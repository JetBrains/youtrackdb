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
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

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

The design.md already carries the loop flowchart (design.md §The new Phase-C
review loop); this track does not duplicate it.

## Plan of Work
The edits, in order. Edits 1-3 are the substantive policy change in
`track-code-review.md`; edits 4-6 keep the three derived/sync sites consistent so
no file ships self-contradictory text.

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

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the numbered roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
Track-level behavioral acceptance (verified by the Phase-C workflow-review agents
plus grep, not by Java tests — this is a prose-only change):

- The Phase-C track code review loop terminates per the per-track complexity tag:
  blockers loop until clear at every level; should-fix loop depth scales with the
  tag (`low` none, `medium` up to three, `high` uncapped); and no-progress
  detection — escalate on the first iteration that clears nothing — replaces the
  fixed cap-3 escalation as the safety valve.
- No cap-3-keyed site in `track-code-review.md` still asserts a fixed `/3` cap as
  live behavior; every restate-set hit reads as restated (cost-model /
  illustrative) or carries the no-progress framing.
- `review-iteration.md` §Limits carries the Phase-C carve-out so a Phase-C reader
  landing there is routed to the override, and keeps cap-3-then-escalate as the
  stated default for Phases 2 / 3A / 3B.
- The dial-mapping prose in `review-agent-selection.md` and the standalone note in
  `code-review/SKILL.md` describe the new policy and stay consistent with
  `track-code-review.md`.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In-scope files** (the four edited rules):
- `.claude/workflow/track-code-review.md` — the dial mapping, the new termination
  machinery, and every cap-3-keyed restate site.
- `.claude/workflow/review-agent-selection.md` — §"Complexity sets the Phase-C
  rigor dial, never the set" dial-mapping prose.
- `.claude/workflow/review-iteration.md` §Limits — the one-line Phase-C carve-out
  (default cap-3-then-escalate for Phases 2 / 3A / 3B preserved).
- `.claude/skills/code-review/SKILL.md` — the standalone-skill dial note (≈225).

**Out-of-scope** (keep cap-3 unchanged): `structural-review.md`,
`implementation-review.md`, `track-review.md` (Phase A), `step-implementation.md`
(Phase B), and any other §Limits consumer outside Phase C. The cap-3-then-escalate
default in `review-iteration.md` §Limits must stay live for those loops.

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
- After the change, no cap-3-keyed site in `track-code-review.md` still asserts a
  fixed `/3` cap as live behavior. Verified by: re-running the restate-set grep
  and confirming every hit reads as restated (cost-model / illustrative) or
  carries the no-progress framing.
- `review-iteration.md` §Limits carries the Phase-C carve-out so a Phase-C reader
  landing there is routed to the override. Verified by: reading §Limits.
- The dial only changes iteration depth / termination, never which reviewers run
  (the domain-selected set is unchanged). Verified by: the
  `review-agent-selection.md` text still asserting "complexity never drops a
  domain-selected reviewer".
