<!-- MANIFEST
role: reviewer-technical
phase: 3A
track: 2
iteration: 1
verdict: CHANGES-REQUESTED
findings: 3
blockers: 0
index:
  - id: T1
    sev: should-fix
    anchor: "T1"
    loc: ".claude/workflow/track-code-review.md review loop + track-2.md Plan of Work item 3"
    cert: "Integration: boundary 3 — review-done-track-open append site"
    basis: "code-read"
  - id: T2
    sev: should-fix
    anchor: "T2"
    loc: ".claude/workflow/inline-replanning.md:245-255 + track-2.md Plan of Work item 5"
    cert: "Integration: boundary 5 — inline-replan substate revert"
    basis: "code-read"
  - id: T3
    sev: suggestion
    anchor: "T3"
    loc: "track-2.md Validation and Acceptance / S2 closure invariant"
    cert: "Edge case: clean-pass multi-step track between review-pass and approval"
    basis: "code-read"
evidence_base: >
  9 certificates. All named precheck primitives (--substate flag, bare-token-only
  validation, ledger_tail_value_for_track reader, phase=C ledger-first arm)
  CONFIRMED in the staged script. All four resume-protocol doc sites and the five
  line refs in the Plan of Work table CONFIRMED in the live docs. Two integration
  certificates flag the append-cadence mismatch driving T1/T2; one edge-case
  certificate drives T3.
-->

## Findings

### T1 [should-fix]
**Certificate**: Integration: boundary 3 — `review-done-track-open` append site
**Location**: `track-2.md` `## Plan of Work` item 3 and the `## Plan of Work` table row "Code review passed (pre-approval)"; the live append target is `.claude/workflow/track-code-review.md` review loop (`:732-746`) and §Track Completion (`:1401`).
**Issue**: The Plan of Work asserts a pre-approval code-review-complete commit always exists for a multi-step track and stages the `review-done-track-open` append into it ("`track-code-review.md` commits a pre-approval code-review-complete Workflow-update commit (around `:743`, the Progress entry recording the passed iteration)"). That commit is **conditional, not guaranteed**. The only pre-approval commit in the multi-step review loop is the per-iteration Progress-update commit at `track-code-review.md:732-746`, and it fires only inside step 3's "If any in-scope findings need fixes" branch (`:701`). On the clean-pass path — review PASSes iteration 1 with no in-scope fixable findings, no deferred findings (no plan-corrections commit at `:1217`), and no stale-base rebase (no discrepancy commit at `:201`) — step 3 is never entered, so no per-iteration commit lands. Step 6 (`:826-840`) appends the `Track complete` Progress entry "when all reviews pass" but carries **no commit instruction**; the next commit is the post-approval track-completion commit at `:1401/:1423`. So on a clean-pass multi-step track there is no committed pre-approval boundary for the append to ride. The committed-boundary cadence (S4) then cannot place `review-done-track-open` before approval, and a resume after review-pass but before approval reads the prior `substate` = `steps-done-review-pending`, which `workflow.md:348` routes to "Run Phase C from the current iteration" rather than `workflow.md:349`'s "Resume track completion." The design's single-step edge case (`design.md:174-180`) explicitly accepts exactly this graceful-degradation behavior for the no-pre-approval-commit case, so the runtime outcome is sound — but Track 2's PoW item 3 describes a pre-approval commit that may not exist, and the implementer would look for a commit to stage into and find none.
**Proposed fix**: In decomposition, fix the PoW item 3 framing to match the live loop. Either (a) — preferred, symmetric with boundary 2 — introduce a new unconditional pre-approval Workflow-update commit at step 6 (`:826`) that stages the `Track complete` Progress flip plus the `--substate review-done-track-open` append, so the boundary is always committed before approval; or (b) accept the design's graceful-degradation behavior and rewrite PoW item 3 to say the append rides the per-iteration commit **when one exists** and that on a clean pass the `substate` stays `steps-done-review-pending` until track completion advances it (the same fallback the single-step edge case already documents at `design.md:174-180`). Option (a) keeps `workflow.md:349`'s distinct "resume track completion" route reachable on the ledger path for every multi-step track; option (b) does not, so prefer (a) unless the re-run-Phase-C-on-clean-pass cost is judged acceptable. The Decision Records are immutable during execution, so if (b) is chosen, the divergence from the "every `phase=C` track carries an explicit `substate`" closure wording in D3/S2 must be recorded as a Phase-4 design-final reconciliation note, not a design edit.

### T2 [should-fix]
**Certificate**: Integration: boundary 5 — inline-replan `substate` revert
**Location**: `track-2.md` `## Plan of Work` item 5 ("Replan revert: `steps-partial`"); the live append site is `.claude/workflow/inline-replanning.md:245-255` (§Process step 6 "Review PASS").
**Issue**: PoW item 5 says to "append `--substate steps-partial` on the replan's commit so the ledger sub-state matches the reopened roster ... keeps the within-track signal consistent when the replan reopens a closed track." The append is **dormant under the live replan flow**. `inline-replanning.md` has exactly one ledger-append site — the §Process step 6 `--phase 0` reset (`:249`), which makes `0` the last-value-wins phase. `determine_state_from_ledger` resolves `phase=0` through its `0 | A | D | Done` arm (staged script `:1952-1959`) and emits `{phase:"0", substate:null}` — it **never reads the `substate` key while the phase resolves to 0**. So `--substate steps-partial` appended on the same commit has no observable effect: the next session enters State 0, re-runs Phase 2, and only when the reopened track re-runs Phase A does the A→C append (boundary 1) write `steps-partial` again — which is the value the resume will actually read. The replan append is therefore redundant with the A→C append that necessarily follows it. The framing "keeps the within-track signal consistent when the replan reopens a closed track" overstates the effect: with `phase=0` the signal is not consulted as a `phase=C` sub-state at all. (The frozen `design.md:186-188` prescribes this same append without reconciling it against the `phase=0` reset, so the gap originates in the design, not Track 2's reading of it.)
**Proposed fix**: In decomposition, rewrite PoW item 5 to state the append's actual role accurately: it is a forward-correct, harmless write that rides the same commit as the `--phase 0` reset and survives `git reset --hard HEAD`, but it is **dormant until the reopened track's A→C append re-establishes `phase=C`**, at which point that A→C append (not this one) supplies the `steps-partial` value the resume reads. Note that no inline-replan path keeps the ledger at `phase=C` (every replan routes through the State-0 reset at `:249`), so this append cannot affect a resume on its own. Keep the append (it is cheap and keeps the last-value-wins `substate` for the track from claiming `steps-done-review-pending` should any future change ever read `substate` while a replan leaves phase at `C`), but do not describe it as the mechanism that reopens the track — the `--phase 0` reset is that mechanism. Because this touches the design's intent at `design.md:186-188`, surface the dormancy as a Phase-4 design-final reconciliation note rather than a design edit during execution.

### T3 [suggestion]
**Certificate**: Edge case: clean-pass multi-step track between review-pass and approval
**Location**: `track-2.md` `## Validation and Acceptance` (the S2-closure bullet) and `## Invariants & Constraints` (the **S2 closure** invariant).
**Issue**: The S2-closure argument claims "the A→C, Phase B→C, pre-approval, and track-advance appends cover every `phase=C` track, so the Track 1 ledger read never falls back for a current plan." This is true for *empty-substate* fallback (every `phase=C` track does carry *some* explicit `substate`, so the read never routes to `roster_scan`). But the closure wording implies each track also carries the *correct terminal* sub-state for its position, and that is not guaranteed for a clean-pass multi-step track in the window between review-pass and approval (see T1): its last appended `substate` is `steps-done-review-pending`, not `review-done-track-open`, because no pre-approval commit carried the advance. The empty-read fallback is correctly ruled out, but the resume routes to `workflow.md:348` (re-run Phase C) instead of `:349` (resume track completion). The S2 invariant as stated ("carries an explicit `substate`") is satisfied; the implicit stronger reading ("carries the sub-state matching its lifecycle position") is not.
**Proposed fix**: Tighten the S2-closure prose to scope the invariant to what it actually guarantees — every `phase=C` track carries a *non-empty* `substate` so the empty-read fallback is never taken — and add one sentence noting the clean-pass-before-approval window where the terminal value is `steps-done-review-pending` and the resume re-enters Phase C (harmless: a clean pass re-passes). This keeps the acceptance criterion honest and pre-empts a reviewer reading S2 as a stronger guarantee than the cadence delivers. Fold into the same decomposition edit that resolves T1, since both turn on whether boundary 3 has a committed home.

## Evidence base

#### Premise: the `--substate` append flag exists on `--append-ledger` as Track 1 built it
- **Track claim**: "Each append calls the `--substate` flag Track 1 introduced" (`track-2.md` `## Plan of Work`); "the `--substate` append flag on `--append-ledger`" (`## Context and Orientation`).
- **Search performed**: Read of the staged precheck script (the §1.7(d) staged copy under `_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh`), arg-parser + append_ledger.
- **Code location**: staged `workflow-startup-precheck.sh:187-190` (the `--substate)` case in the arg loop), `:128` (`LEDGER_SUBSTATE`), `:1697` (validation call), `:1719-1723` (line emission, pre-`categories`).
- **Actual behavior**: `--substate` is parsed into `LEDGER_SUBSTATE`; `append_ledger` validates it via `reject_bad_ledger_value "substate" "$LEDGER_SUBSTATE" bare` and, when non-empty, emits ` substate=$LEDGER_SUBSTATE` BEFORE the quoted `categories` field.
- **Verdict**: CONFIRMED

#### Premise: `--substate` validation is bare-token-only, not enum-membership (WI2 deferred for Track 2)
- **Track claim**: spawn brief — "the `--substate` append flag (validates only bare-token-ness, not enum membership)"; PoW does not assume enum validation.
- **Search performed**: Read of `reject_bad_ledger_value` in the staged script.
- **Code location**: staged `workflow-startup-precheck.sh:1655-1681`.
- **Actual behavior**: rejects only a newline (any field) and a space (bare fields); no check that the value is one of the four committed slugs. A typo like `--substate steps-partail` would be accepted and silently mis-route.
- **Verdict**: CONFIRMED — matches the deferred-WI2 decision. Track 2 is doc-only and out of scope for hardening this; it must pass the four canonical slug spellings verbatim. No finding (the slugs Track 2 writes are fixed literals in the PoW table, not free-form), but the implementer must copy the slug strings exactly — `decomposition-pending`, `steps-partial`, `steps-done-review-pending`, `review-done-track-open`.

#### Premise: the track-scoped reader and the `phase=C` ledger-first arm exist
- **Track claim**: "the track-scoped reader that `determine_state_from_ledger` calls before its `determine_c_substate` fallback" (`## Context and Orientation`).
- **Search performed**: Read of `ledger_tail_value_for_track` and `determine_state_from_ledger` in the staged script.
- **Code location**: staged `workflow-startup-precheck.sh:1828-1863` (reader), `:1960-2002` (`phase=C` arm), `:1981-1986` (ledger-first read), `:1988-2000` (empty-substate fallback to `determine_c_substate`).
- **Actual behavior**: the `phase=C` arm reads `track`, then `ledger_tail_value_for_track "substate" "$track"`; a non-empty value emits `{phase:"C", substate:$s}` directly; an empty value falls back to the roster-driven `determine_c_substate`.
- **Verdict**: CONFIRMED — the read side Track 2 activates is present and dormant exactly as stated.

#### Premise: no live workflow file appends `--substate` today
- **Track claim**: "This track activates the primitive Track 1 landed dormant" / "nothing appends a `substate` yet, so every read is empty."
- **Search performed**: `grep -rn '\-\-substate\|substate=' .claude/workflow/ .claude/skills/ .claude/agents/`.
- **Code location**: zero matches in the live tree.
- **Actual behavior**: no live append site exists; Track 2 introduces all of them.
- **Verdict**: CONFIRMED

#### Premise: the five Plan-of-Work line refs resolve in the live docs
- **Track claim**: the `## Plan of Work` table refs — `track-review.md:596` and `:1048`; `step-implementation.md` §Phase B Completion; `track-code-review.md:743` and `:1409`/`:1411`; `inline-replanning.md:249`.
- **Search performed**: `sed -n` at each line + `grep -n` for the append sites in the live docs.
- **Code location**: `track-review.md:596` (A→C `--append-ledger` block, step 6), `:1048` (recovery-path append block); `step-implementation.md:1070-1100` (§Phase B Completion); `track-code-review.md:743` (per-iteration "Commit and push the Progress update"), `:1409` (`--track <N+1>`), `:1411` (`--phase D`); `inline-replanning.md:249` (`--phase 0` reset append).
- **Actual behavior**: every ref lands at the named site. The `:596`/`:1048` refs point at the `--append-ledger \` continuation line inside their respective multi-line blocks; `:1409`/`:1411` are exact append lines; `:743` is the per-iteration commit instruction line.
- **Verdict**: CONFIRMED — all references are workflow file paths / line refs that resolve.

#### Premise: §Phase B Completion today ends with no commit and no append
- **Track claim**: PoW item 2 / D1 — "Today `step-implementation.md` §Phase B Completion marks `Step implementation [x]` ... and ends with no commit and no append."
- **Search performed**: Read of `step-implementation.md` §Phase B Completion and sub-step 8 (per-step episode commit).
- **Code location**: `step-implementation.md:1070-1100` (steps 1-4: mark `[x]`, inform, reflect, end — no `git commit`), `:885-891` (sub-step 7.1 flips the roster `[x]`), `:922-934` (sub-step 8 commits the episode + roster flip per step).
- **Actual behavior**: per-step roster `[x]` flips ARE committed per step (sub-step 8, "Record episode for <step>"). The §Phase B Completion `Step implementation [x]` flip is the `## Progress` phase-checkpoint entry — a separate write — and no step in §Phase B Completion commits it.
- **Verdict**: CONFIRMED — boundary 2's "incidentally commits the previously-uncommitted `Step implementation [x]` flip" is internally consistent.

#### Integration: boundary 2 — Phase B→C `steps-done-review-pending` (new commit)
- **Plan claim**: add a new Phase-B-complete Workflow-update commit staging the `Step implementation [x]` flip plus `--substate steps-done-review-pending`, symmetric with the A→C commit.
- **Actual entry point**: `step-implementation.md:1070` §Phase B Completion (orchestrator, phase 3B).
- **Caller analysis**: §Phase B Completion runs for every track after its last step (single- and multi-step alike), before Phase C. A single-step risk:high track still reaches it, so it appends `steps-done-review-pending`; the Phase C single-step skip path (`track-code-review.md:116`) then commits nothing and proceeds to completion, leaving `steps-done-review-pending` as the last value — which `design.md:174-180` documents as routing correctly.
- **Breaking change risk**: low. The new commit adds a commit where none existed; it commits a previously-uncommitted Progress flip (a latent correctness improvement) and stages the ledger append atomically. No existing consumer keys on §Phase B Completion ending without a commit.
- **Verdict**: MATCHES

#### Integration: boundary 3 — `review-done-track-open` append site (drives T1)
- **Plan claim**: rides "the pre-approval code-review-complete Workflow-update commit (around `:743`)".
- **Actual entry point**: `track-code-review.md:732-746` (per-iteration Progress-update commit, inside step 3's fixes-needed branch at `:701`); `:826-840` (step 6 `Track complete` Progress entry, NO commit); `:1401/:1423` (post-approval track-completion commit).
- **Caller analysis**: the per-iteration commit fires only when an iteration applies fixes. A clean-pass multi-step track (PASS iter 1, no fixable findings, no deferred findings, no stale-base rebase) lands no pre-approval commit. The only other pre-approval commits — `:201` stale-base note, `:1217` plan corrections — are also conditional.
- **Breaking change risk**: the cadence cannot place `review-done-track-open` before approval on the clean-pass path; the resume reads `steps-done-review-pending` and routes to `workflow.md:348` (re-run Phase C) rather than `:349` (resume track completion).
- **Verdict**: CALLERS AT RISK — the PoW asserts an always-present commit that is in fact conditional.

#### Integration: boundary 5 — inline-replan `substate` revert (drives T2)
- **Plan claim**: "append `--substate steps-partial` on the replan's commit so the ledger sub-state matches the reopened roster."
- **Actual entry point**: `inline-replanning.md:245-255` §Process step 6 "Review PASS" — the sole ledger-append site, which appends `--phase 0`.
- **Caller analysis**: every inline-replan path routes through §Process step 6's `--phase 0` reset (no path keeps the ledger at `phase=C`). `determine_state_from_ledger` resolves `phase=0` and emits `{phase:"0", substate:null}` (staged script `:1952-1959`) without reading `substate`.
- **Breaking change risk**: none (harmless), but the append is dormant — its `steps-partial` value is never the value a resume reads; the reopened track's later A→C append supplies `steps-partial`.
- **Verdict**: MISMATCHES — the append's described effect ("keeps the within-track signal consistent") does not occur while the co-committed `phase=0` reset governs resolution.

#### Edge case: clean-pass multi-step track between review-pass and approval (drives T3)
- **Trigger**: a multi-step track whose code review PASSes on iteration 1 with no fixable in-scope findings, no deferred findings, and no stale-base rebase; the orchestrator has appended the `Track complete` Progress entry (`:826`) but the user has not yet approved.
- **Code path trace**:
  1. Phase B→C boundary appended `substate=steps-done-review-pending` (boundary 2) @ `step-implementation.md` §Phase B Completion (new commit).
  2. Phase C review PASSes iteration 1 with no fixes → step 3 never entered → no per-iteration commit @ `track-code-review.md:701,732-746`.
  3. Step 6 appends `Track complete` Progress entry, no commit @ `:826-840`.
  4. Session interrupted before §Track Completion step 5 commits the advance @ `:1401`.
  5. Resume: `determine_state_from_ledger` reads track-scoped `substate` = last appended = `steps-done-review-pending` @ staged script `:1981-1986`.
- **Outcome**: `workflow.md:348` routes to "Run Phase C from the current iteration" — the review fan-out re-runs (re-passes, no harm to correctness) instead of resuming track completion via `:349`. S2's empty-read fallback is correctly avoided; the terminal-value-matches-position reading of S2 does not hold here.
- **Track coverage**: partial — the PoW does not call out this window; `design.md:174-180` documents the analogous single-step case but not the clean-pass multi-step case.
