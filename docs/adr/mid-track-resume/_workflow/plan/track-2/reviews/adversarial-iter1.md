<!-- MANIFEST
role: reviewer-adversarial
phase: 3A
track: "Track 2: Wire the `substate` append sites across the resume protocol"
iteration: 1
verdict: should-fix
findings: 5
blockers: 0
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    loc: "track-code-review.md:743 (live); track-2.md:153,171-175 (Plan of Work boundary 3)"
    cert: "Assumption test: boundary 3 rides the :743 per-iteration commit"
    basis: prose-ref
  - id: A2
    sev: should-fix
    anchor: "### A2 "
    loc: "step-implementation-recovery.md:315-321 (live, commit-pattern item 5); track-2.md:250 (in-scope), 255-257 (out-of-scope)"
    cert: "Assumption test: the new Phase-B-complete commit is covered by a dependent prompt's classifier"
    basis: prose-ref
  - id: A3
    sev: suggestion
    anchor: "### A3 "
    loc: "track-2.md:227-231,279-283 (S2 closure invariant)"
    cert: "Violation scenario: a phase=C track carries a stale non-empty substate"
    basis: prose-ref
  - id: A4
    sev: suggestion
    anchor: "### A4 "
    loc: "track-2.md:74-78,292-295 (D1+D3 wiring-pair constraint)"
    cert: "Violation scenario: phase=C track with no substate (silent-fallback revival)"
    basis: prose-ref
  - id: A5
    sev: suggestion
    anchor: "### A5 "
    loc: "track-2.md:263-269 (sizing justification); 35-82 (D1/D3)"
    cert: "Challenge: the core->consumer cut vs folding into Track 1"
    basis: prose-ref
evidence_base: "Three boundary-host commits verified against live docs (A->C at track-review.md:596/:1048; Phase B->C new commit at step-implementation.md §Phase B Completion :1070; replan revert at inline-replanning.md :249/:266; track-advance at track-code-review.md :1409). All Track 1 read-side outputs (--substate flag :187, substate key validation :1697, ledger_tail_value_for_track :1828, ledger-first read :1981) verified present in the staged precheck script. The one boundary whose host commit does NOT match the plan's claim is boundary 3 (review-done-track-open): the cited :743 commit is the conditional per-iteration commit, absent on a zero-findings review pass."
-->

# Adversarial review — Track 2 (iteration 1)

Track 2 survives the three track-realization challenges. The scope cut is sound, all Track 1 read-side outputs exist as the plan assumes, and S2 closure holds at the never-empty level. One should-fix corrects a host-commit misattribution at boundary 3 (the `review-done-track-open` append), one should-fix flags a dependent classifier the new Phase-B-complete commit is not yet enrolled in, and three suggestions strengthen rationale.

## Findings

### A1 [should-fix]
**Certificate**: Assumption test — boundary 3 rides the `:743` per-iteration commit
**Target**: Decision D1 (append side), `## Plan of Work` table row 3 / boundary 3
**Challenge**: Boundary 3 wires the `review-done-track-open` append onto "the pre-approval code-review-complete Workflow-update commit (around `:743`, the Progress entry recording the passed iteration)." But `track-code-review.md` `:743` is inside step 3 of the Review loop, whose guard is "**If any in-scope findings need fixes:**" (`:701`). That commit fires once per fix iteration, not once per review pass. When a track passes code review on the **first pass with zero findings**, the loop never enters step 3, so no `:743` commit is ever created. The flow goes Synthesis (zero in-scope findings) → step 6 "When all reviews pass" appends a `Track complete` Progress entry that is written but **not committed** (`:826`-`:840` has no `git commit`), → Track Completion → wait for user approval → step 5 (`:1401`) commits `Mark <track> complete` with the track-advance ledger append (boundary 4). On that path the `review-done-track-open` append has no host commit to ride: boundary 3's stated host does not exist, and the next committed ledger line is boundary 4's `decomposition-pending` for track N+1.
**Evidence**: Live `track-code-review.md` step 3 guard at `:701` ("If any in-scope findings need fixes"); the per-iteration Progress commit at `:743`-`:746`; step 6 appends the `Track complete` Progress entry at `:826`-`:840` with no commit; the only commit between review-pass and user-approval is step 5's `Mark <track> complete` at `:1401`-`:1423` (which carries boundary 4, not boundary 3). The committed-boundary cadence (S4, `track-2.md:284`) requires every append to ride a commit that survives `git reset --hard HEAD`; boundary 3's named host is absent on the zero-findings pass, so the append would have to ride a commit the protocol does not produce on that path.
**Proposed fix**: Re-anchor boundary 3. The append must ride a commit that exists on **every** review-pass path, including zero findings. Two viable hosts: (a) the new Phase-B-complete commit (boundary 2) could carry `steps-done-review-pending` and the code-review-pass cadence could add a dedicated commit at step 6 ("When all reviews pass") that stages the `Track complete` Progress entry plus the `review-done-track-open` append — symmetric with how boundary 2 adds a new Phase-B-complete commit; or (b) fold `review-done-track-open` into the deferred `Mark <track> complete` commit ordering by appending it as a separate ledger line in step 5 before the `decomposition-pending`/`phase D` advance. Option (a) preserves the deferred-write invariant (`track-code-review.md:1471`) and keeps the resume routing distinct; whichever is chosen, Decompose this boundary so the per-step roster names the exact commit it rides and confirms that commit fires unconditionally on review-pass.

### A2 [should-fix]
**Certificate**: Assumption test — the new Phase-B-complete commit is covered by a dependent prompt's classifier
**Target**: Decision D1 (the new Phase B→C commit), Integration point with `step-implementation-recovery.md`
**Challenge**: Boundary 2 introduces a brand-new commit type: a Phase-B-complete Workflow-update commit that stages the `Step implementation [x]` flip plus the `steps-done-review-pending` append (`track-2.md:162`-`170`). `step-implementation-recovery.md` `:315`-`321` (commit-pattern item 5, "Other Workflow update commits") is a **closed enumeration** of the workflow-update commit subjects the Phase B Resume scan recognizes as scaffolding: "Phase 1 init, Phase A decomposition, Phase B base-commit recording, plan-corrections application, track-completion mark, inline-replanning update, Phase C iteration-count Progress updates, Phase C iteration-failure Progress updates." A Phase-B-complete commit is not in that list. Under the workflow-machinery criteria this is a breakage-of-dependent-prompts concern: a new commit the resume classifier was not told about. The practical blast radius is small — the commit appears strictly after the last episode commit and touches only `_workflow/`, and Phase B Resume only runs while a `[ ]` step exists (none remain once `steps-done-review-pending` is recorded). But the item-5 enumeration is consulted by name, and an unlisted workflow-update subject is exactly the kind of drift the consistency lens flags.
**Evidence**: Live `step-implementation-recovery.md:315`-`321` enumerates the recognized "Other Workflow update commits" by name; no entry covers a Phase-B-complete / `steps-done-review-pending` commit. Track 2's `## Interfaces and Dependencies` out-of-scope list (`track-2.md:255`-`257`) names only `workflow.md` step 5 routing as the touched-but-unchanged neighbor; it does not mention the recovery doc's commit-pattern reference.
**Proposed fix**: Add the new commit subject to `step-implementation-recovery.md` item 5's enumeration (in-scope edit — it is a `.claude/workflow/**` file, staged per §1.7(d)), or add it to Track 2's out-of-scope list with a one-line justification that the new commit lands after the last episode commit in a window where Phase B Resume cannot fire. The decomposition should pick one and record it, so the new commit type is either enrolled in the classifier or explicitly argued harmless.

### A3 [suggestion]
**Certificate**: Violation scenario — a `phase=C` track carries a stale non-empty `substate`
**Target**: Invariant "S2 closure" (`track-2.md:227`-`231`, `279`-`283`)
**Challenge**: S2 closure claims the four committed appends "cover every `phase=C` track, so the Track 1 ledger read never falls back for a current plan." Closure-against-emptiness holds: the A→C append always sets `steps-partial` riding the mandatory `Phase A review and decomposition` commit (`track-review.md:596`), so no `phase=C` track is ever substate-empty on a current ledger — the fallback is never wrongly triggered. But closure does not imply the last substate is the **correct** one. Construct the scenario tied to A1: a track passes code review on the first pass with zero findings, then the session crashes after step 6 writes the (uncommitted) `Track complete` Progress entry but before user approval. The last committed substate is `steps-done-review-pending` (boundary 2's commit) — `review-done-track-open` never committed (A1). On resume, `workflow.md:348` routes `steps-done-review-pending` to "Run Phase C from the current iteration," re-running code review, rather than `workflow.md:349`'s "Resume track completion." Not a corruption — review is re-runnable — but a wasted Phase C fan-out and a resume that lands one state behind where the track actually is.
**Evidence**: `workflow.md:348`-`349` routing table maps the two slugs to different resume actions; A1's path leaves `steps-done-review-pending` as the committed tail after a clean review pass. Feasibility: CONSTRUCTIBLE (a zero-findings track is the common case for a small doc-only track like Track 2 itself).
**Proposed fix**: Resolving A1 dissolves this — once `review-done-track-open` rides a commit that exists on the zero-findings path, the committed tail is correct and the resume routes to track completion. Note in `## Validation and Acceptance` that S2 closure is the never-empty guarantee, and that correct-tail routing depends on boundary 3 committing on every review-pass path (the A1 fix).

### A4 [suggestion]
**Certificate**: Violation scenario — `phase=C` track with no `substate` (silent-fallback revival)
**Target**: Constraint "The D1+D3 wiring pair lands together" (`track-2.md:74`-`78`, `292`-`295`)
**Challenge**: The constraint argues a half-implementation (A→C `steps-partial` append without the track-advance `decomposition-pending` append, or vice versa) leaves a `phase=C` track with no `substate`, reviving the silent fallback. The constraint is self-satisfying: both appends are in this track, so a partial land would have to be a mid-track abandonment. Probe the harder case: track N completes and the track-advance append sets `decomposition-pending` for track N+1 (boundary 4, `track-code-review.md:1409`). That append rides the `Mark <track> complete` commit, which lands **after user approval**. Until track N+1's own Phase A runs and appends `steps-partial` (boundary 1), track N+1 sits at `phase=C` (the ledger advanced `track` but kept `phase=C`) with `substate=decomposition-pending`. That is correct and intended — D3 sets it explicitly precisely so an empty read is unambiguous. The genuine no-substate window is narrower: a session that crashes after the A→C commit lands `phase=C track=N` but before any `substate` rides it. But boundary 1 puts `--substate steps-partial` **into** that same A→C commit, so the window is zero on a current ledger. The constraint holds; the only residual no-substate case is a pre-this-change ledger, which is exactly D3's intended fallback trigger.
**Evidence**: `track-review.md:596` shows the A→C append and `:600`-`:609` shows it committed atomically with the track file; adding `--substate steps-partial` to that same append (boundary 1) closes the window. Staged precheck `determine_state_from_ledger` `:1981`-`1999` treats an empty `substate` as the unambiguous pre-change signal. Feasibility: INFEASIBLE on a current ledger (the substate rides the same atomic commit as `phase=C`); the only empty case is a pre-change ledger, which is the designed fallback.
**Proposed fix**: None required — the constraint survives. Optionally tighten the constraint's prose to distinguish "no `phase=C` track is ever substate-empty on a current ledger" (the real guarantee) from the weaker "the pair lands together" framing, so a future reader does not read the constraint as the whole closure argument.

### A5 [suggestion]
**Certificate**: Challenge — the core→consumer cut vs folding Track 2 into Track 1
**Target**: Decision (sizing justification), `## Interfaces and Dependencies` (`track-2.md:263`-`269`)
**Chosen approach**: Two tracks, cut at the core→consumer dependency boundary: Track 1 lands the tested read-side primitive (`--substate` flag, `substate` key, track-scoped reader) dormant; Track 2 wires the doc-only append sites that activate it.
**Best rejected alternative**: One track that lands the primitive and its append sites together, so the read side never ships dormant and the wiring is validated end-to-end in a single PR.
**Counterargument trace**:
1. Track 1 already merged dormant (per `{prior_episodes}` and plan checklist `:57`), so the cut is realized, not hypothetical — re-litigating it would mean unwinding a landed track.
2. The merge-candidate threshold (~12 files, `planning.md` §Track descriptions) makes a 4-file track a merge candidate by default; Track 2 carries the required written justification (`track-2.md:263`-`269`).
3. Folding would mix the tested bash primitive (Track 1's 12 tests) with resume-protocol prose across four `.claude/workflow/**` docs, and the append-site docs "have no behavior to validate until the read side exists" — so the combined track's tests would all be Track 1's; Track 2 adds no new test, only prose-review acceptance.
**Codebase evidence**: Track 1's read side is fully present in the staged precheck (`--substate` flag at `:187`, validation at `:1697`, reader at `:1828`, ledger-first read at `:1981`); Track 2 calls these without adding code. The dependency is genuine and one-directional (Track 2 → Track 1), confirming the seam.
**Survival test**: YES. The cut is defensible and already realized; the dormant-read-side risk (an empty read routing to fallback) was Track 1's explicit safety property, so shipping it dormant was correct. The one residual is that Track 2 ships **no executable test** — its acceptance is review-of-prose plus Track 1's tests "exercising the slugs these appends write" (`track-2.md:217`-`231`). That is acceptable for a doc-only track but means a wrong slug at an append site (e.g., a typo `step-done-review-pending`) would not be caught by any test — only by review and by the bare-token validation (which accepts any well-formed token, not only the four valid slugs; the staged enum-guard WI2 was declined per `{prior_episodes}`).
**Proposed fix**: None to the cut. Strengthen `## Validation and Acceptance` to state explicitly that the append slugs are verified by the cadence-table review (`track-2.md:217`-`219`) and **not** by any test, since the declined WI2 enum-guard means the precheck validation accepts any bare token, not only the four committed slugs — so a misspelled slug at an append site is a review-only catch. This makes the acceptance gap visible at decomposition time.

## Evidence base

### Assumption test: boundary 3 rides the `:743` per-iteration commit
- **Claim** (boundary 3, `track-2.md:153`,`171`-`175`): the `review-done-track-open` append rides "the pre-approval code-review-complete Workflow-update commit (around `:743`)."
- **Stress scenario**: a track passes code review on the first pass with zero in-scope findings.
- **Code evidence**: `track-code-review.md:701` ("If any in-scope findings need fixes:") guards step 3, which contains the `:743` per-iteration Progress commit. Zero findings ⇒ step 3 is skipped ⇒ no `:743` commit. Step 6 (`:826`-`:840`) appends the `Track complete` Progress entry with no commit. The next commit is step 5's `Mark <track> complete` (`:1401`), which carries boundary 4 (`decomposition-pending`/`phase D`), not boundary 3.
- **Verdict**: BREAKS — boundary 3's named host commit does not exist on the zero-findings review-pass path.

### Assumption test: the new Phase-B-complete commit is covered by a dependent prompt's classifier
- **Claim**: the new Phase-B-complete commit (boundary 2) is a known workflow-update commit the resume side handles.
- **Stress scenario**: Phase B Resume scans `git log` and must classify the new commit.
- **Code evidence**: `step-implementation-recovery.md:315`-`321` enumerates recognized "Other Workflow update commits" by subject; a Phase-B-complete / `steps-done-review-pending` commit is absent from the list. The commit lands after the last episode commit, touching only `_workflow/`, in a window where no `[ ]` step remains (so Phase B Resume cannot fire on it).
- **Verdict**: FRAGILE — harmless in practice (the resume scan never reaches it while a `[ ]` step exists), but the classifier enumeration is consulted by name and the new subject is unlisted; the consistency lens would flag the drift.

### Violation scenario: a `phase=C` track carries a stale non-empty `substate`
- **Invariant claim** (S2 closure): every `phase=C` track on a current-scheme ledger carries an explicit `substate` the Track 1 read resolves directly.
- **Violation construction**:
  1. Start state: track passes code review on the first pass, zero findings; ledger tail is `steps-done-review-pending` (boundary 2's commit).
  2. Action sequence: step 6 writes (uncommitted) `Track complete` Progress entry (`track-code-review.md:826`); session crashes before user approval; `review-done-track-open` never commits (A1).
  3. Intermediate state: ledger tail substate = `steps-done-review-pending`; track-file `## Progress` shows all steps `[x]` and code review `[x]` (uncommitted, reverted by the implicit `git reset` on resume).
  4. Violation point: the substate is non-empty but **stale** — it names the prior boundary.
  5. Observable consequence: `workflow.md:348` routes `steps-done-review-pending` to "Run Phase C from the current iteration," re-running code review instead of resuming track completion (`workflow.md:349`).
- **Feasibility**: CONSTRUCTIBLE — a zero-findings doc-only track (Track 2 itself is a candidate) makes this the common path, not an edge case. Severity is suggestion because the consequence is a re-runnable Phase C, not corruption, and it is fully dissolved by the A1 fix.

### Violation scenario: `phase=C` track with no `substate` (silent-fallback revival)
- **Invariant claim** (D1+D3 wiring-pair constraint): a `phase=C` track must never carry no `substate` on a current ledger, or the fallback fires when it should not.
- **Violation construction**:
  1. Start state: attempt to find a committed `phase=C` ledger line with no substate riding it.
  2. Action sequence: the A→C boundary (boundary 1) adds `--substate steps-partial` into the same atomic `Phase A review and decomposition` commit that writes `phase=C track=N` (`track-review.md:596`-`609`).
  3. Intermediate state: the `phase=C` line and its `steps-partial` substate are one commit; there is no committed `phase=C` line without a substate on a current ledger.
  4. Violation point: none reachable on a current ledger — the only empty-substate case is a pre-this-change ledger.
  5. Observable consequence: pre-change ledger ⇒ empty read ⇒ intended fallback (D3), not a violation.
- **Feasibility**: INFEASIBLE on a current ledger (substate rides the same atomic commit as `phase=C`); the constraint survives. The empty case maps exactly to D3's designed fallback trigger.

### Challenge: the core→consumer cut vs folding Track 2 into Track 1
- **Chosen approach**: two tracks split at the read-side-primitive / append-site-wiring dependency boundary.
- **Best rejected alternative**: one track landing primitive + appends together for end-to-end validation in one PR.
- **Counterargument trace**: Track 1 already merged dormant, so the cut is realized; the 4-file Track 2 carries the required merge-candidate justification; folding would mix the tested bash primitive with prose-only append-site edits that have no behavior to validate until the read side exists.
- **Codebase evidence**: Track 1 read side fully present in the staged precheck (`--substate` `:187`, validation `:1697`, reader `:1828`, ledger-first read `:1981`); Track 2 adds no code and no test.
- **Survival test**: YES — the cut is sound and already realized. Residual: Track 2 ships no executable test, and the declined WI2 enum-guard means the bare-token validation accepts any well-formed token, so a misspelled append slug is a review-only catch.
