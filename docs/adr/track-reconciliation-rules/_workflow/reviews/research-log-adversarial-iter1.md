<!-- MANIFEST
findings: 6   severity: {blocker: 2, should-fix: 4, suggestion: 0}
index:
  - {id: A1, sev: blocker,    loc: "research-log.md:52", anchor: "### A1 ", cert: "Challenge D4", basis: "no-progress detection is undefined and exists nowhere in the workflow; uncapped loops have no operational termination"}
  - {id: A2, sev: blocker,    loc: "research-log.md:112", anchor: "### A2 ", cert: "Assumption test: 4-file footprint", basis: "uncapping breaks ~6 cap-3-keyed mechanics in track-code-review.md the Surprises footprint does not list"}
  - {id: A3, sev: should-fix, loc: "research-log.md:35", anchor: "### A3 ", cert: "Challenge D3 (medium)", basis: "medium splits one loop into uncapped-blocker + cap-3-should-fix; the single shared iteration counter cannot represent two caps"}
  - {id: A4, sev: should-fix, loc: "research-log.md:21", anchor: "### A4 ", cert: "Challenge D2", basis: "D2 leaves review-iteration.md §Limits canonical while Phase C now contradicts it; the override is asserted, not wired"}
  - {id: A5, sev: should-fix, loc: "research-log.md:37", anchor: "### A5 ", cert: "Challenge D3 (low)", basis: "low's 'iterate until no blockers' is already today's behavior within the cap; the real delta is removing the cap, which the log mis-frames"}
  - {id: A6, sev: should-fix, loc: "research-log.md:56", anchor: "### A6 ", cert: "Assumption test: cost bound", basis: "the existing mandatory context-consumption pause already bounds an uncapped loop; D4 reasons as if no bound exists, risking a redundant or conflicting mechanism"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: "Challenge D4", verdict: WEAK, anchor: "#### Challenge: Decision D4 — no-progress detection replaces cap-3"}
  - {id: "Assumption test: 4-file footprint", verdict: BREAKS, anchor: "#### Assumption test: the change touches only the 4 files in the Surprises footprint"}
  - {id: "Challenge D3 (medium)", verdict: WEAK, anchor: "#### Challenge: Decision D3 (medium) — uncapped blocker loop plus cap-3 should-fix loop"}
  - {id: "Challenge D2", verdict: WEAK, anchor: "#### Challenge: Decision D2 — Phase C overrides §Limits but §Limits stays canonical"}
  - {id: "Challenge D3 (low)", verdict: WEAK, anchor: "#### Challenge: Decision D3 (low) — 'iterate until no blockers'"}
  - {id: "Assumption test: cost bound", verdict: FRAGILE, anchor: "#### Assumption test: an uncapped loop is unbounded in cost without no-progress detection"}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [blocker]
**Certificate**: Challenge D4 — no-progress detection replaces cap-3
**Target**: Decision D4
**Challenge**: D4 removes the cap-3 escalation safety valve and replaces it with "no-progress detection: escalate when an iteration makes no progress (the same blocker/should-fix findings survive a fix attempt)." That mechanism does not exist anywhere in the workflow today — `grep -rn "no-progress\|findings survive\|makes no progress" .claude/workflow/ .claude/skills/` returns zero hits — and the log gives no operational definition. "The same findings survive" is ambiguous on three axes the implementer must resolve: (1) identity — is a finding "the same" when its `id` re-appears, when its `(loc, root-issue)` matches, or when the gate-check returns `STILL OPEN` for it? (2) threshold — does *one* surviving finding out of ten count as "no progress," or must *all* survive? (3) which loop — does it gate the blocker loop, the should-fix loop, or both independently? Without a definition the implementer either invents one (an un-reviewed design decision smuggled into Phase B) or the loop has no real termination and reduces to "truly unbounded," the alternative D4 itself rejects.
**Evidence**: `review-iteration.md` §Gate-check verdict handling (lines 134-161) already emits `VERIFIED`/`REJECTED`/`MOOT`/`STILL OPEN`/`REGRESSION` per prior finding `id`, carried by the §Gate-check synthesis routing. A no-progress signal is *constructible* from "every carried finding returned `STILL OPEN` and no `VERIFIED`/`MOOT` cleared this iteration" — but the log neither cites this existing verdict stream nor states the rule in those terms. The mechanism is buildable; the decision as written is not specified enough to derive an artifact from.
**Proposed fix**: Define no-progress detection in D4 in terms of the existing gate-check verdict stream: e.g., "an iteration makes no progress when its gate-check returns `STILL OPEN` for every finding carried into it and clears none (no `VERIFIED`/`MOOT`/`REJECTED`) and surfaces no new fixable finding; a `REGRESSION` is always progress-negative and escalates immediately per existing handling." State the identity axis (by reviewer `id`), the threshold (zero net clears), and that it gates each uncapped loop. Resolve this into the `## Decision Log` before any artifact derives, or the gate cannot clear.

### A2 [blocker]
**Certificate**: Assumption test — the change touches only the 4 files in the Surprises footprint
**Target**: Assumption (Surprises "Likely-touched files", research-log.md:112-120)
**Challenge**: The Surprises footprint lists four files (`track-code-review.md` §Review loop, `review-agent-selection.md`, `code-review/SKILL.md`, `review-iteration.md` §Limits) and flags only that the `(N/3 iterations)` Progress line "goes stale." That undercounts the blast radius. Uncapping the `low`/`medium` blocker loop and the `high` should-fix loop breaks at least six concrete cap-3-keyed mechanics *inside `track-code-review.md` §Review loop alone* that the footprint does not enumerate, so a derived artifact (design or track) would leave the file self-contradictory: it would say "iterate without a cap" in one paragraph and "Max 3 iterations total across sessions" two paragraphs down.
**Evidence**: `grep -n "3 iterations\|N/3\|of 3" .claude/workflow/track-code-review.md` surfaces: line 765 Progress format `(N/3 iterations)`; line 832 step 4 "Max 3 iterations **total across sessions** — on resume, read the iteration count from the Progress section to determine how many remain"; line 837 the pre-spawn-split rationale "consumes 2 of 3 iterations"; line 848 step 5 "If blockers persist after 3 iterations, ... note the unfixed findings"; line 875 step 6 "not when the loop exited with blockers still open after 3 iterations"; line 1256 the checklist seed `(1/3 iterations, iteration 1...)`. The cross-session resume logic (step 4) *reads the cap off the Progress line* to know how many iterations remain — with no cap there is no "remaining" count to read, so the resume contract itself changes shape. None of these is named in the Surprises footprint.
**Proposed fix**: Add a Surprises entry (or expand the existing "Likely-touched files" entry) enumerating every cap-3-keyed mechanic that the uncapping invalidates, specifically the §Review loop steps 4/5/6, the `(N/3 iterations)` Progress format and its cross-session-resume read, the pre-spawn-split "2 of 3" rationale, and the checklist seed at line 1256 — and state how each is restated under the new policy (e.g., Progress records `iteration N complete` with no `/3` denominator; resume reads the running count plus the no-progress/blocker state rather than a remaining-cap count). Without this enumeration the planner sizes the change as a 4-file touch and the derived artifact ships a contradictory file.

### A3 [should-fix]
**Certificate**: Challenge D3 (medium) — uncapped blocker loop plus cap-3 should-fix loop
**Target**: Decision D3 (`medium` policy)
**Challenge**: D3 `medium` = "loop on blockers until clear (uncapped, per `low`) **plus** iterate up to 3 times to clear should-fix." That is two loops with two different bounds (blocker: uncapped; should-fix: cap 3) sharing one counter. The current mechanism cannot represent that: step 4 states "The iteration count is shared across all review dimensions (not independent counters)" (`track-code-review.md:834`). A single shared counter cannot simultaneously be "uncapped for blockers" and "capped at 3 for should-fix" — when iteration 4 runs because a blocker survived, does it also still attempt should-fix (cap already exceeded), or are should-fix findings frozen out after iteration 3 while blocker iterations continue? The log does not say, and the existing single-counter model has no slot for the answer.
**Evidence**: `track-code-review.md:832-847` step 4 ("Max 3 iterations total across sessions ... iteration count is shared across all review dimensions, not independent counters") and the gate-check verdict handling in `review-iteration.md:134-161`, which routes blocker and should-fix verdicts through one iteration loop, not two. D3 implicitly requires either two counters or a rule for what the should-fix loop does once blocker iterations push past 3.
**Proposed fix**: In D3, specify the `medium` interaction explicitly: e.g., "the iteration counter is shared; should-fix findings stop driving new iterations after 3 iterations, but a surviving blocker continues to drive iterations (subject to A1's no-progress detection); should-fix findings that re-surface in a post-3 blocker iteration are fixed opportunistically if cheap, else surfaced at completion." Make the single-vs-double-counter question a named decision rather than leaving it to the implementer.

### A4 [should-fix]
**Certificate**: Challenge D2 — Phase C overrides §Limits but §Limits stays canonical
**Target**: Decision D2
**Challenge**: D2 scopes the change to Phase C and explicitly keeps `review-iteration.md` §Limits "unchanged" for Phases 2/3A/3B. But §Limits is the *canonical shared home* — its TOC summary reads "Max 3 iterations per review type; escalate if blockers persist" and it is loaded by `orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track` across phases `2,3A,3B,3C` (line 35). After D3/D4, Phase C (which carries role `reviewer-dim-track`, phase `3C`, both in §Limits' filter) reads §Limits and gets "Max 3, then escalate," which now directly contradicts the new uncapped Phase-C policy. D2's own Surprises entry concedes §Limits "may need a one-line pointer that Phase C overrides it" (research-log.md:120) but D2 does not commit to wiring that pointer — it asserts the override exists without making §Limits announce its own exception.
**Evidence**: `review-iteration.md:7,35-38` (§Limits TOC row + body, phase set `2,3A,3B,3C` includes `3C`); `review-agent-selection.md:227-242` §"Complexity sets the Phase-C rigor dial" already cross-references §Limits as the iteration-mechanics home for all three levels. A reader arriving at §Limits in Phase C sees no exception and follows the cap.
**Proposed fix**: Promote the "one-line pointer" from a Surprises maybe into a committed D2 sub-decision: §Limits keeps cap-3-then-escalate as the default for Phases 2/3A/3B and gains an explicit carve-out sentence ("Phase-C track code review overrides this per `track-code-review.md` §Review loop — uncapped per complexity tag with no-progress escalation"). State that the override is *wired into §Limits*, not merely asserted in `track-code-review.md`, so a §Limits reader is not silently misrouted.

### A5 [should-fix]
**Certificate**: Challenge D3 (low) — "iterate until no blockers"
**Target**: Decision D3 (`low` policy)
**Challenge**: D3 frames `low` → "iterate until no blockers remain" as the new behavior. But the current `low` *already* loops on blockers: the Surprises entry at research-log.md:67-76 quotes today's mapping as "`low` → single shallow pass (run once; do not iterate for should-fix; **blocker/REGRESSION still forces the loop to continue**)," and `track-code-review.md:688-690` confirms "a `REGRESSION` verdict or a `blocker` finding forces the loop to continue regardless of complexity." So "iterate until no blockers" is not the delta — within the cap-3 ceiling, `low` already does exactly that. The real and only change for `low` is **removing the cap** from that already-existing blocker loop. Stating the policy as if blocker-looping is newly introduced obscures the one thing that actually changes (and the one thing that needs A1's termination story most acutely, since `low` has no should-fix loop to bound it).
**Evidence**: research-log.md:67-76 (Surprises: current `low` mapping); `track-code-review.md:681-690` (`low` single-pass shortcut "still honors every hard gate: a `REGRESSION` verdict or a `blocker` finding forces the loop to continue").
**Proposed fix**: Reword D3 `low` to name the actual delta: "`low` already loops on blockers within the cap-3 ceiling today; the change removes the cap so the blocker loop continues until clear (bounded by A1's no-progress detection). should-fix still does not drive iteration." This also makes explicit that `low` relies entirely on no-progress detection for termination, since it has no should-fix cap as a backstop.

### A6 [should-fix]
**Certificate**: Assumption test — an uncapped loop is unbounded in cost without no-progress detection
**Target**: Assumption (D4 rationale, research-log.md:56-60)
**Challenge**: D4's rationale for no-progress detection is "an unbounded loop ... would loop forever and burn context/cost." That reasons as if no cost bound exists today, but the Phase-C §Review loop already carries a **mandatory context-consumption check after each iteration** that halts the loop at `warning` (≥40%) or `critical` (≥50%) and writes a handoff (`track-code-review.md:813-831`). So an uncapped `high` loop is *not* unbounded in context — it stops at the context threshold and resumes next session, where the cross-session resume (step 4) re-reads state. The interaction is unaddressed: does no-progress detection escalate to the user, or does the context pause fire first and hand off? Two termination mechanisms now coexist (no-progress escalation; context-pause handoff) and the log does not say how they compose. The risk is a redundant or, worse, conflicting bound — e.g., a `high` track that is making slow progress hits the context pause every session and never reaches a no-progress escalation, looping across many sessions indefinitely.
**Evidence**: `track-code-review.md:813-831` (mandatory per-iteration context-consumption check, halts at `warning`/`critical`, writes `mid-phase-handoff.md`); `track-code-review.md:832-833` (cross-session resume reads iteration count from Progress). The context pause already bounds per-session cost; D4's "would burn context/cost" premise is only true *across* sessions.
**Proposed fix**: In D4, acknowledge the existing per-iteration context-consumption pause and state how it composes with no-progress detection: the context pause bounds *per-session* burn (unchanged), no-progress detection bounds *convergence* (escalate when findings stop shrinking across iterations, including across a resume). Clarify that a slow-but-real-progress `high` track resumes after a context pause and continues, while a stuck track escalates on the first no-progress iteration regardless of context level.

## Evidence base

#### Challenge: Decision D4 — no-progress detection replaces cap-3
- **Chosen approach**: Replace the cap-3-then-escalate safety valve with "no-progress detection": escalate when an iteration makes no progress (same blocker/should-fix findings survive a fix attempt). Applies to all uncapped loops (`low`/`medium` blocker loops, `high` should-fix loop).
- **Best rejected alternative**: D4's own listed "hard ceiling that still escalates" — i.e., keep a (possibly higher) numeric cap as the backstop and layer no-progress detection on top. The log rejects it as "reintroduces an arbitrary cap the user wanted removed."
- **Counterargument trace**:
  1. In the scenario where a blocker cannot be fixed (the exact case the original cap-3 existed to escape), the chosen approach relies on no-progress detection to escalate — but no-progress detection has no definition anywhere in the workflow (`grep -rn "no-progress" .claude/` → 0 hits) and the log gives none.
  2. The rejected hard-ceiling-plus-escalate would guarantee termination even if no-progress detection is mis-tuned, because the numeric ceiling is a definitionally-unambiguous backstop.
  3. This produces the concrete difference: under the chosen approach, an implementer who slightly varies a fix each iteration (so findings are not byte-identical but the defect persists) may register as "progress" forever; under hard-ceiling-plus-escalate the loop always terminates.
- **Codebase evidence**: `review-iteration.md:37-38` (the cap-3 it replaces); `review-iteration.md:134-161` (the existing `STILL OPEN`/`VERIFIED`/`MOOT`/`REGRESSION` verdict stream from which a no-progress signal *could* be defined but the log does not).
- **Survival test**: WEAK. The decision to remove a fixed cap is defensible (it matches the user's intent), but the replacement mechanism is undefined, so the rationale needs strengthening into an operational rule before any artifact derives. → A1.

#### Assumption test: the change touches only the 4 files in the Surprises footprint
- **Claim**: The "Likely-touched files" Surprises entry implies the edit is a 4-file touch (`track-code-review.md` §Review loop, `review-agent-selection.md`, `code-review/SKILL.md`, `review-iteration.md` §Limits), with the only flagged collateral being a stale `(N/3 iterations)` Progress line.
- **Stress scenario**: A planner/author derives the design or track from this footprint and edits only the dial-mapping prose, leaving the rest of `track-code-review.md` §Review loop intact.
- **Code evidence**: `track-code-review.md:765,832,837,848,875,1256` — six distinct cap-3-keyed mechanics (Progress format, step 4 cross-session cap + resume read, pre-spawn-split rationale, step 5 blockers-persist exit, step 6 commit-path guard, checklist seed). The cross-session resume (step 4, line 832) reads the cap to compute remaining iterations — with no cap, the resume contract changes shape, not just one Progress string.
- **Verdict**: BREAKS. The footprint materially undercounts the in-file blast radius; the derived artifact would ship a self-contradictory `track-code-review.md`. → A2.

#### Challenge: Decision D3 (medium) — uncapped blocker loop plus cap-3 should-fix loop
- **Chosen approach**: `medium` = uncapped blocker loop + iterate up to 3 times to clear should-fix.
- **Best rejected alternative**: Single shared cap for both severities at `medium` (the current model), with the uncapping reserved for `high` only.
- **Counterargument trace**:
  1. The chosen approach needs two bounds on one loop (blocker uncapped, should-fix cap 3) but step 4 mandates a single shared counter (`track-code-review.md:834` "not independent counters").
  2. The single-cap alternative needs no new counter machinery and keeps `medium` close to today's behavior.
  3. The concrete difference: under the chosen approach, what happens to should-fix findings in a 4th iteration that runs because a blocker survived is undefined; under a single shared cap the question never arises.
- **Codebase evidence**: `track-code-review.md:832-847`; `review-iteration.md:134-161`.
- **Survival test**: WEAK. The intent (medium gets bounded should-fix convergence) is sound, but the single-vs-double-counter mechanics are unspecified. → A3.

#### Challenge: Decision D2 — Phase C overrides §Limits but §Limits stays canonical
- **Chosen approach**: Change only the Phase-C dial; leave §Limits canonical and unchanged for Phases 2/3A/3B.
- **Best rejected alternative**: Edit §Limits itself to carry the Phase-C carve-out (so the canonical home announces its own exception), which the log's Surprises entry half-proposes ("may need a one-line pointer").
- **Counterargument trace**:
  1. §Limits' filter is `roles=...,reviewer-dim-track phases=2,3A,3B,3C` (line 35) — it loads in Phase C, where its "Max 3, then escalate" body now contradicts the new uncapped policy.
  2. The rejected alternative wires the carve-out into §Limits so a Phase-C reader of §Limits is routed to the override rather than silently following the stale cap.
  3. The concrete difference: under the chosen approach a reader who lands on §Limits in Phase C (e.g., via the `review-agent-selection.md:239-242` cross-reference) reads the wrong rule.
- **Codebase evidence**: `review-iteration.md:7,35-38`; `review-agent-selection.md:239-242` (cross-ref to §Limits as the iteration-mechanics home).
- **Survival test**: WEAK. Scoping to Phase C is correct, but the §Limits contradiction must be wired, not asserted. → A4.

#### Challenge: Decision D3 (low) — "iterate until no blockers"
- **Chosen approach**: State `low` as "iterate until no blockers remain" (new behavior).
- **Best rejected alternative**: State the delta precisely — "remove the cap from the blocker loop `low` already runs today."
- **Counterargument trace**:
  1. Today's `low` already loops on blockers within the cap (Surprises research-log.md:67-76; `track-code-review.md:688-690`).
  2. The precise framing names the one change (cap removal) and surfaces that `low` now depends entirely on no-progress detection for termination.
  3. The concrete difference: the imprecise framing risks an implementer adding blocker-looping that already exists while missing the cap removal that is the actual edit.
- **Codebase evidence**: research-log.md:67-76; `track-code-review.md:681-690`.
- **Survival test**: WEAK. Decision is right; the framing obscures the delta and the termination dependency. → A5.

#### Assumption test: an uncapped loop is unbounded in cost without no-progress detection
- **Claim**: D4 rationale — "an unbounded loop ... would loop forever and burn context/cost," implying no existing cost bound.
- **Stress scenario**: A `high` track loops for many iterations.
- **Code evidence**: `track-code-review.md:813-831` — a mandatory per-iteration context-consumption check already halts at `warning`/`critical` and writes a handoff; `track-code-review.md:832` cross-session resume re-reads state. So per-session context *is* bounded today; only cross-session convergence is not.
- **Verdict**: FRAGILE. D4's premise holds only across sessions, not per-session; the log must say how no-progress detection composes with the existing context pause to avoid a redundant or conflicting bound. → A6.
