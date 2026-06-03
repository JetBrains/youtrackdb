<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 4: Prose consolidation (staged)

## Purpose / Big Picture
After this track lands, the six gate-prose surfaces call or cite the script instead of carrying inline detection bash — `workflow.md § Startup Protocol` shrinks to a ~30-50-line dispatch rule — and every edit sits staged under `staged-workflow/`, ready for the Phase 4 promotion.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 4 rewrites the six prose surfaces to consume the script: `workflow.md § Startup Protocol` becomes a short dispatch rule; `workflow-drift-check.md` and `branch-divergence-check.md` shrink to reference docs; `conventions.md §1.6(h)` keeps its spec and gains a script pointer; `commit-conventions.md § Push failure handling` re-enters via `divergence-only`; `migrate-workflow/SKILL.md` Step 2 reuses `migrate-range`. Every edit is STAGED under `staged-workflow/`, and the script's JSON shape must be final before the prose cites it, which is why this track runs last.

## Progress
- [x] 2026-06-03T12:56Z [ctx=info] Review + decomposition complete
- [x] 2026-06-03T13:35Z [ctx=info] Step 1 complete (commit 92888ad18f)
- [x] 2026-06-03T13:47Z [ctx=info] Step 2 complete (commit e62746d37a)
- [x] 2026-06-03T13:52Z [ctx=info] Step 3 complete (commit 169e3f7f55)
- [x] 2026-06-03T13:58Z [ctx=info] Step 4 complete (commit 13214c4c86)
- [x] 2026-06-03T14:04Z [ctx=info] Step 5 complete (commit 3a9cc84855)
- [x] 2026-06-03T14:10Z [ctx=info] Step 6 complete (commit b97831dbc3)
- [x] 2026-06-03T14:10Z [ctx=info] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- **Phase A reviews (re-validation, 2026-06-03).** The prior pre-replan Phase A
  reviews (preserved in `## Artifacts and Notes`) all still hold for the
  six-surface scope, and blocker A1 is resolved by Track 5. The re-validation
  surfaced two genuinely-new should-fix findings the post-replan scope exposed:
  the §1.6(h) stale-framing problem (A3) has a twin one subsection up in
  §1.6(a1):530 (T2/A8), and the dispatch rewrite's dissolution of the numbered
  startup steps stales a cross-reference in `mid-phase-handoff.md`, a file
  outside the planned six surfaces (A9).
- **Scope grew to seven surfaces.** `mid-phase-handoff.md` was added as the
  7th in-scope file (one-line cross-ref touch-up, folded into Step 1). The step
  count stays at six; only the file count moved. See Decision Log.
- **D4 "four → one" hinges on Step 6 rewriting BOTH migrate walks.** The migrate
  skill carries two §1.6(h) byte-copies (Step 2.0 classify + Step 2 range), not
  one; Step 6's scope now names both so the durable count claim holds.
- **design.md ↔ script divergence is confirmed-live, not just anticipated (Step 1).**
  Cite the shipped `.claude/scripts/workflow-startup-precheck.sh emit_json`, not frozen
  `design.md`, for every JSON field name in Steps 2-6 and Track 5: the live `state` object
  is `{phase, substate}` (no `track` field) with slug substates, and the `migrate-range`
  contract Step 6 consumes diverges from design.md too. The Phase 4 `design-final.md` owns
  the reconciliation (plan §Final Artifacts). See Episodes §Step 1.
- **Staged cross-file `§`-refs must be backticked (rule_8), affecting Steps 2-6.** The
  `workflow-reindex.py` check descends into `_workflow/staged-workflow/`, so a bare
  `§X.Y(z)` cross-file reference inside a staged copy trips the in-file-ref validator; wrap
  such refs in backticks. The two `rule_1` line-1-stamp residues every staged copy carries
  are expected and clear at the Phase 4 promotion — do not stamp staged copies. See
  Episodes §Step 1.
- **`workflow-reindex.py` enforces a 120-char cap on `summary=` annotations (rule_5c),
  affecting Steps 3-6.** Keep each reworded section-annotation and its matching TOC-table
  row under 120 characters; over-cap annotations must be shortened in lockstep across both
  sites. The guard-2 `awk '{print $2}'` space-truncation known-debt now lives only in the
  staged `workflow-drift-check.md` Path-quoting note (R-A5), so Step 4's `§1.6(h)` pivot
  must not duplicate-then-drop it. See Episodes §Step 2.
- **All seven Track 4 staged copies are first-touch whole-file adds — Phase C must
  delta-scope each (D5).** The cumulative track diff shows seven whole-file adds under
  `_workflow/staged-workflow/.claude/`; the real change in each is only the `diff <live>
  <staged>` delta. Phase C must compute that delta per copy and scope findings to it, since
  the live-tree counterparts are already-reviewed develop-state content (the live tree is
  untouched, I6). The seven files: `workflow.md`, `mid-phase-handoff.md`,
  `workflow-drift-check.md`, `branch-divergence-check.md`, `conventions.md`,
  `commit-conventions.md`, `migrate-workflow/SKILL.md`. See Episodes §Step 6.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

- **A9 — include `mid-phase-handoff.md` as the 7th surface (user decision,
  2026-06-03).** The Step 1 dispatch rewrite dissolves the numbered
  `step 3 / 3a / 4 / 5` structure of `workflow.md § Startup Protocol`, staling
  the `(step 3)` / `(step 3a)` cross-references at `mid-phase-handoff.md:123-125`
  after the Phase 4 promotion. That file was outside the planned six surfaces.
  Offered include-as-7th-surface / ESCALATE / defer-as-post-merge-follow-up;
  user chose **include**. Handled as a light track-file scope amendment (a
  one-line genericization folded into Step 1) plus a one-line note in the plan
  Component Map, rather than a full inline replan, given the size.
- **T2/A8 — extend Step 4 within `conventions.md` to reconcile §1.6(a1)
  alongside §1.6(h).** Both subsections carry the dissolved "byte-for-byte by
  Tracks N" framing; both live in the file Step 4 already edits, so this is an
  in-scope refinement, not a new surface.
- **T1/A10 — Step 6 rewrites both migrate walks (Step 2.0 + Step 2).** Keeps
  D4's "four byte-copies → one" intact; both walks are in `migrate-workflow/SKILL.md`,
  already the in-scope surface, so no plan-of-work or scope change beyond naming
  Step 2.0 in Step 6's body.
- **Risk tagging.** Step 1 is `high` (the dispatch rewrite is the load-bearing
  prose→script Component-Map relationship and the behavior-parity heart, which
  no fixture covers); Steps 2-6 are `medium` (behavior-parity reference-doc
  consolidations of startup gates — the binding contract is parity (S1), so each
  is a Phase C focal point). No code-side HIGH/MEDIUM trigger applies to a
  prose-only track; tags are decomposer overrides under the criteria's intent.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (2 new findings, 2 accepted; preserved A2-A7 / R1-R6 / T3 re-validated against the live script). T1 (should-fix) — migrate Step 2.0's byte-copy walk sits outside Step 6's named scope, threatening D4's "four → one"; folded into Step 6 (rewrite both walks). T2 (should-fix) — `conventions.md §1.6(a1):530` carries the same stale "byte-for-byte by Tracks 3/4a/4b" framing as §1.6(h):677; folded into Step 4. A1 confirmed resolved by Track 5; the A5 record-vanishing worry is partly over-stated (the byte-source note at `workflow-drift-check.md:223-230` is a live file promoted at Phase 4, not in `_workflow/`), so Step 2 preserves-and-extends it rather than re-creating a record.
- [x] Risk: PASS at iteration 1 (re-validation; 0 new blockers). R1 / R3-A4 / R4 / R6 (should-fix) — the dispatch, migrate-recovery, push-failure, and handoff behaviors the JSON does not carry must survive the rewrite; threaded into Steps 1 / 6 / 5. R-A5 (should-fix, refined) — split into preserve-the-live-note (drift-check:223-230) and add-the-guard-2-`awk`-sentence; threaded into Steps 2 and 4. R5/A6 (suggestion) — cite the shipped script, note the `timeout 10` parity-delta. No blocker.
- [x] Adversarial: PASS at iteration 1 (5 findings A8-A12; A1 resolved, A2-A7 re-validated). A8 (should-fix) duplicates T2 (§1.6(a1) sibling framing); folded into Step 4. A9 (should-fix) — the dispatch rewrite dissolves the numbered startup steps, staling cross-refs including the out-of-scope `mid-phase-handoff.md:123-125`; user chose include-as-7th-surface (see Decision Log), folded into Step 1. A10 (should-fix) duplicates T1 (Step 2.0 walk). A11/A12 (suggestion) — correct the "rewrite is staged" prose; author Step 1 so it does not re-list SKILL consumers (Track 5's job). No blocker.
- [x] Step 1 dimensional review (risk:high): PASS at iteration 2. Four workflow reviewers (consistency, instruction-completeness, context-budget, writing-style); baseline skipped on the workflow-only diff. One blocker WI1 — the drift gate's original four-branch list missed the `{detected:false, kind:"stamped"}` no-drift steady state (the common startup path; script line 545-546), fixed by re-keying on `drift.detected` first so all five emitted shapes route. One should-fix WI2 — the `unstamped` / `merge-base-failed` bullets lacked the explicit no-default user-gating clause the `stamped` bullet carries. One suggestion WS1 — a §Conventions footer bullet ran one em dash over the cap. All three applied in Review fix `92888ad18f`; gate-check VERIFIED each. WB3 (the rewritten section grew ~24% over the soft ~30-50-line target) accepted as-is per A2 — the growth is behavior-preservation prose, and the startup *flow* still shrinks because the two reference docs no longer load unconditionally. WB1/WB2 (staged-copy `rule_1`) are the expected staged state and clear at promotion. Consistency and context-budget passed clean at iteration 1.

## Context and Orientation

Six live prose files carry the detection logic and the gate UX today; this track moves the mechanical detection out to the script and leaves the conversational and reference prose behind. Because the plan is workflow-modifying (the `§1.7(b)` marker in Constraints), every edit lands under the staged subtree, not the live tree.

The six surfaces and their target shapes:

- **`workflow.md § Startup Protocol`** — today ~full gate prose. Becomes a ~30-50-line dispatch rule: run `--mode full`, parse the JSON, present the divergence and drift gates the script reports without owning, run the handoff resume protocol when `handoffs` is non-empty, then route on `state.phase` (and on `state.substate` for State C — the shipped `state` object is `{phase, substate}` with no `track` field, so the dispatch re-derives the active track from the `## Checklist` walk).
- **`workflow-drift-check.md`** — the inline Detection and normalization bash is replaced by a citation of the script; the file keeps the conversational three-resolution prose as a reference doc.
- **`branch-divergence-check.md`** — the inline ahead/behind detection bash is replaced by a script citation; the resolution prose stays.
- **`conventions.md §1.6(h)`** — keeps the artifact-walk bash as the readable spec (§1.6 is the declared single source of truth) and gains a one-line pointer to the script implementation. This is a pivot, not a strip.
- **`commit-conventions.md § Push failure handling`** — the mid-session non-fast-forward push re-runs `--mode divergence-only` instead of reloading the divergence gate prose.
- **`migrate-workflow/SKILL.md` Step 2** — reads the `--mode migrate-range` fields (`stamped_artifacts` pairs, `unstamped_files`, `base_sha`, the `git log` range, optional `--bootstrap-sha`) instead of byte-copying the §1.6(h) walk in prose.

The staging discipline this track runs under: every first touch of a live file copies it verbatim into its staged path (§1.7(e)), edits land on the staged copy, reads resolve staged-first (§1.7(d)), and the live tree stays at develop state for the whole branch (§1.7(g), I6). The staged subtree promotes at the Phase 4 commit, after a rebase onto develop (§1.7(f)); the script is already live, so only the prose promotes (D6).

## Plan of Work

The work edits each surface once the JSON contract is frozen by Tracks 1-3. Each edit is a copy-then-edit on first touch under the staged path.

1. **`workflow.md § Startup Protocol` dispatch rewrite.** Replace the inline gate sequence with the ~30-50-line dispatch rule that runs `--mode full` and routes on the JSON.
2. **`workflow-drift-check.md` shrink.** Replace the inline Detection + normalization bash with a script citation; keep the resolution prose.
3. **`branch-divergence-check.md` shrink.** Replace the inline detection bash with a script citation; keep the resolution prose.
4. **`conventions.md §1.6(h)` pivot.** Keep the walk bash as the spec; add the one-line pointer to the script implementation and note the script conforms to this spec (checked by the Track 1 conformance fixture).
5. **`commit-conventions.md § Push failure handling` re-entry.** Route the first in-session non-fast-forward push to `--mode divergence-only`.
6. **`migrate-workflow/SKILL.md` Step 2 reuse.** Read the `migrate-range` JSON fields instead of re-deriving the walk in prose; keep the conversational unstamped-bootstrap prompt in the skill.

Ordering constraints and invariants to preserve: this track depends on Tracks 1-3 so the JSON shape the prose cites is final — citing field names that later change would desync the dispatch rule. Every edit lands under `staged-workflow/` (S4 / I6); no live `.claude/workflow/**` or `.claude/skills/**` file changes. The §1.6(h) edit is a pivot that keeps the readable spec, not a strip (D4). Cross-references between the rewritten files must resolve against the script's actual `--mode` outputs.

## Concrete Steps

1. `workflow.md § Startup Protocol` dispatch rewrite (+ `§ Conventions` footer + `mid-phase-handoff.md` cross-ref, A9). Replace the inline gate sequence with a dispatch rule that runs `--mode full`, then: presents the divergence gate (branch on `divergence.detected` / `skipped` / `skip_reason`, no-default pick preserved); presents the drift gate (branch on `drift.kind` ∈ {unstamped, stamped, merge-base-failed}, and recite the autonomous normalization commit from `drift.normalization_landed` + `actions_taken[].{action,commit,subject}`); runs the handoff-resume protocol when `handoffs` is non-empty, preserving the `ls -t` most-recent-first order and the resume freeze (no sub-agent spawn / gate re-run / episode recompile while unresolved); routes State 0/A/C/D/Done on `state.phase` and State C on `state.substate`, re-deriving the active track from the `## Checklist` walk (no `state.track` field); preserves the State A Panel-1 skip, the five State C sub-states plus the `section-discrepancy` edge, one-phase-per-resume, and halt-and-surface on a non-zero script exit (Track 2's total parse-error contract). Update the `§ Conventions` footer (workflow.md:635-636) so the gate-file descriptions match the dispatch model instead of "loaded by Startup Protocol step 3/3a", and genericize `mid-phase-handoff.md`'s step-number cross-refs (A9). Cite the live `workflow-startup-precheck.sh emit_json`, not frozen design.md (T3/R5). Author as the single canonical dispatch the Track 5 SKILLs defer to; do NOT re-list SKILL-layer consumers (A12). Threads R1/A2/A7, R6, A9, A12, T3/R5. The ~30-50-line target is a soft goal sized by behaviors-preserved, not a hard cap (A2). — risk: high (override: architecture — the load-bearing Component-Map relationship rewrite (prose→script) and the behavior-parity heart; instruction-completeness-critical and untestable by fixtures, so step-level review pays off; reviewers R1/A2/A7/A9 concentrated here)  [x] commit: 92888ad18f
2. `workflow-drift-check.md` shrink to reference doc. Replace the inline two-phase Detection and No-drift normalization bash with a citation of the script's `--mode full` drift detection; keep the conversational three-resolution prose (Migrate / Defer / Suppress). PRESERVE the existing live "Path-quoting assumption" note at `workflow-drift-check.md:223-230` during the shrink, and EXTEND it with one sentence naming the guard-2 `awk '{print $2}'` space-truncation known-debt so the only durable record does not vanish at `_workflow/` cleanup (R-A5). Correct the byte-source's "the rewrite is staged" phrasing to the script's in-place-rewrite-then-stage-on-clean-guards sequence (A11). — risk: medium (override: behavior-parity reference-doc consolidation of a startup gate; the binding contract is parity (S1), so this is a Phase C focal point)  [x] commit: e62746d37a
3. `branch-divergence-check.md` shrink to reference doc. Replace the inline ahead/behind detection bash with a citation of `--mode full` / `divergence-only`; keep the three-resolution prose (local-authoritative / remote-authoritative / defer). Note the `timeout 10 git fetch --no-tags` parity-delta: behavior parity holds except on a slow-but-reachable remote past 10s, which the per-commit push re-check still catches (A6). — risk: medium (override: behavior-parity reference-doc consolidation of a startup gate; Phase C focal point)  [x] commit: 169e3f7f55
4. `conventions.md §1.6(h)` pivot (+ `§1.6(a1)` reconciliation, T2/A8). Keep the §1.6(h) artifact-walk bash as the readable spec (§1.6 is the declared single source of truth); add a one-line pointer to the script implementation and note the script conforms to this spec (checked by the Track 1 conformance fixture). Reconcile the stale "Tracks 3 and 4a copy this block byte-for-byte" framing in §1.6(h):677 (A3) AND the identical "byte-for-byte by Tracks 3, 4a, and 4b" framing in §1.6(a1):530 (T2/A8) to the single-implementation model; the prior-plan "Tracks 3/4a/4b" labels themselves are live develop-state and out of scope to renumber, only the byte-copy framing is reconciled. — risk: medium (override: touches the §1.6 declared single-source-of-truth spec and reconciles two cross-section framings; consistency-sensitive; Phase C focal point)  [x] commit: 13214c4c86
5. `commit-conventions.md § Push failure handling` re-entry. Route the first in-session non-fast-forward push to `--mode divergence-only`. PRESERVE the first-occurrence-in-session guard, the do-not-silently-retry rule, and the already-Deferred record-and-continue suppression, reading both `divergence.detected` and `divergence.skipped` / `skip_reason` (R4). — risk: medium (override: behavior-parity re-entry of a mid-session gate; guard preservation is the parity hazard; Phase C focal point)  [x] commit: 3a9cc84855
6. `migrate-workflow/SKILL.md` Step 2 (+ Step 2.0) `migrate-range` reuse. Rewrite BOTH the Step 2.0 classification walk and the Step 2 range-derivation walk to consume `--mode migrate-range` (`unstamped_files` covers Step 2.0's classify; `stamped_artifacts` / `base_sha` / `log_range` cover Step 2's range), so D4's "four byte-copies → one" holds rather than collapsing to two (T1/A10). PRESERVE the agent-side merge-base-failure recovery loop verbatim — the script never prompts: combined unstamped + `merge_base_failed[].files` re-prompt, drop the failed SHAs, session-wide 3-attempt cap, re-invoke with `--bootstrap-sha`, restart the fold (R3/A4). Read the `migrate-range` JSON from a `/tmp` file via `Read` offset/limit because `log_range` is uncapped (WB1). Keep the conversational unstamped-bootstrap prompt in the skill. — risk: medium (override: rewrites both §1.6(h) walks and must preserve the agent-side recovery loop the script cannot carry; D4 correctness hinges here; Phase C focal point)  [x] commit: b97831dbc3

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 92888ad18f, 2026-06-03T13:35Z [ctx=info]
**What was done:** Rewrote `workflow.md § Startup Protocol` from the inline numbered gate sequence into a single dispatch over `workflow-startup-precheck.sh --mode full`, routing on the five JSON keys (`divergence`, `drift`, `handoffs`, `state`, `actions_taken`). All inline detection bash (the `git rev-list` divergence count, `git fetch`, the `ls -t` handoff glob, the `git merge-base` stamp fold) is gone; the section now presents each gate from the script's reported facts and defers the resolution UX to the reference docs it cites. Updated the two gate-file descriptions in the `§ Conventions` footer to the dispatch model and genericized the dissolved `(step 3)` / `(step 3a)` startup-step cross-references in `mid-phase-handoff.md` (the seventh-surface touch-up, A9). The dimensional review re-keyed the drift gate on `drift.detected` first so it covers all five `{detected, kind}` shapes the script emits, and added explicit user-gated no-default clauses to the `unstamped` and `merge-base-failed` bullets. Both edits land on freshly-staged copies under `_workflow/staged-workflow/`; the live files are byte-unchanged (S4 / I6).

**What was discovered:** The frozen `design.md` diverges from the shipped script exactly as T3/R5 warned: design.md shows `state: {phase, track, substate}` with long-form substate strings, while the live script emits `{phase, substate}` (no `track` field) with slug substates. The dispatch cites the live `workflow-startup-precheck.sh emit_json` throughout, and step 5 re-derives the active track from the `## Checklist` walk. The drift gate's correctness hinges on the script emitting **five** `{detected, kind}` shapes, not four: the `{detected: false, kind: "stamped"}` no-drift steady state (script line 545-546) is the common startup path and was the dimensional review's blocker (WI1) — the original four-branch list left it un-instructed. Steps 2-6 must likewise cite the live script's `emit_json`, not frozen design.md (the same divergence applies to the `migrate-range` contract for Step 6). Step 3 must note the same `timeout 10 git fetch` parity-delta (A6) when it shrinks `branch-divergence-check.md`. The `workflow-reindex.py` walk descends into `_workflow/staged-workflow/`, so cross-file `§X.Y(z)` references inside a staged copy must be backticked or they trip the in-file-ref validator (rule_8) — relevant to every staged copy Steps 2-6 create.

**What changed from the plan:** none. Scope matched the step description. The State C sub-state table is rendered as a `state.substate`-keyed table and the `section-discrepancy` edge folded in as a table row rather than a separate paragraph.

**Key files:**
- `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/workflow/workflow.md` (new staged copy)
- `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/workflow/mid-phase-handoff.md` (new staged copy)

**Critical context:** The dispatch section is the single canonical startup entry point Track 5's two SKILLs defer to; it deliberately omits the SKILL-consumer list (A12 — that reconciliation is Track 5's job). The two `rule_1` line-1-stamp residues the reindex check reports on the staged copies are the normal staged-subtree state and clear when the Phase 4 promotion overwrites and re-stamps the live tree; do not stamp the staged copies to silence them.

### Step 2 — commit e62746d37a, 2026-06-03T13:47Z [ctx=info]
**What was done:** Shrank the staged `workflow-drift-check.md` from a gate implementation (~552 lines) to a reference doc (~414 lines). Replaced the inline two-phase Detection bash (the `§1.6(h)` stamp walk, the `git merge-base` fold, the `git log` range) and the inline No-drift normalization bash with citations of the live script's `--mode full` drift output: the Detection section now documents the `drift` object (`{detected, kind, base_sha, commit_count, first_commits, normalization_landed}`) and the five outcomes keyed off `drift.detected` and `drift.kind`, and the No-drift section cites the `actions_taken` normalization entry (`{action, commit, subject}`, `action == "normalize-workflow-sha-stamps"`). Kept the three-resolution conversational prose (Migrate now / Defer / Suppress) intact. Preserved the "Path-quoting assumption" note and extended it with the guard-2 `awk '{print $2}'` space-truncation known-debt (R-A5). Corrected the "the rewrite is staged" phrasing (A11), and genericized the dissolved `workflow.md` step-number cross-references (A9-style: the intro, the §After-the-choice next-step ref, and the Remote-authoritative re-entry note).

**What was discovered:** The "the rewrite is staged" phrasing (A11) was not merely imprecise — the shipped script's own comment documents the opposite sequence: the normalization rewrites each stamp in place (`printf` + `tail`, never `git add`-ed) until both diff-shape guards pass, then stages and commits. The correction now matches the script. The reindex check also enforces a 120-char cap on section / TOC `summary=` annotations (rule_5c); two over-cap annotations were caught and shortened in lockstep with their TOC-table rows. Steps 3-6 authoring reference-doc summaries must keep each `summary=` annotation under 120 chars.

**What changed from the plan:** none. Scope matched the step description (shrink, preserve-and-extend the Path-quoting note, the A11 correction, the A9-style cross-ref genericization).

**Key files:**
- `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/workflow/workflow-drift-check.md` (new staged copy)

**Critical context:** The guard-2 `awk '{print $2}'` space-truncation known-debt now lives only in this file's Path-quoting note (R-A5); Step 4's `§1.6(h)` pivot must not duplicate-then-drop it. The single expected `rule_1` staged residue clears at Phase 4 promotion; do not stamp the staged copy.

### Step 3 — commit 169e3f7f55, 2026-06-03T13:52Z [ctx=info]
**What was done:** Shrank the staged `branch-divergence-check.md` to a reference doc. Replaced the §Detection inline ahead/behind bash (the `@{u}` upstream guard, `git fetch`, `git rev-list --left-right --count HEAD...'@{u}'`) with a citation of the live script's `divergence` object, reported under both `--mode full` (startup) and `--mode divergence-only` (the mid-session push-failure re-check Step 5 wires). The section now documents `divergence.{detected, ahead, behind, skipped, skip_reason}` with `skip_reason` ∈ {`"no-upstream"`, `"fetch-failed"`} and keeps the three-resolution UX (local-authoritative / remote-authoritative / defer) agent-side. Added a parity-delta paragraph stating the `timeout 10 git fetch --no-tags` bound honestly: parity holds except on a slow-but-reachable remote past 10s, where the script reports `skip_reason == "fetch-failed"` and the per-commit push re-check still catches the missed divergence (A6). Genericized the stale "§ Startup Protocol step 3" cross-reference in §Remote-authoritative to the dispatch model (A9). The live file is byte-unchanged.

**What changed from the plan:** none. Scope matched the step description (shrink, three resolutions preserved, the timeout-10 parity-delta stated, the A9 cross-ref genericization).

**Key files:**
- `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/workflow/branch-divergence-check.md` (new staged copy)

**Critical context:** The single expected `rule_1` staged residue this copy adds (four total across the staged subtree) clears at Phase 4 promotion; do not stamp the staged copy.

### Step 4 — commit 13214c4c86, 2026-06-03T13:58Z [ctx=info]
**What was done:** Pivoted the staged `conventions.md §1.6(h)` walk block from byte-copied-by-tracks framing to spec-plus-implementation: the artifact-walk bash STAYS as the readable spec, and the intro now points at `workflow-startup-precheck.sh` as the single implementation of the walk (drift detection, the migrate-range walk, and the no-drift normalization recompute all run it) and notes the script conforms to this spec, checked by the conformance fixture under `.claude/scripts/tests/`. Reconciled the identical stale byte-copy framing one subsection up in `§1.6(a1)` ("both quoted byte-for-byte by Tracks 3, 4a, and 4b") to the same single-implementation model, since the script embodies both regex idioms. First-touch copy-then-edit: the staged copy is md5-identical to live except the two reconciled subsections; the live `conventions.md` is byte-unchanged.

**What was discovered:** A bare `§1.6` in-file reference trips `workflow-reindex.py` rule_8 (it wants a `:roles:phases` suffix or a backtick span); the established §1.6 convention for sibling-subsection references is the bare parenthetical `(a1)` / `(g)` / `(d)` form, and a parent-section reference is reworded without the `§`-number. The intro was reworded to "the declared single source of truth" to clear rule_8. This refines the cross-file rule_8 note from Step 1: in-file `§X.Y(z)` prose references in a staged copy must be the bare sibling parenthetical, backticked, or suffix-stamped — relevant to Steps 5-6 authoring cross-section references.

**What changed from the plan:** none. The "Tracks 3 / 4a / 4b" prior-plan labels lived inside the byte-copy framing sentences, so reconciling the framing removed them rather than renumbering — matching the plan's "do not renumber the labels, only reconcile the framing" scope limit.

**Key files:**
- `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/workflow/conventions.md` (new staged copy)

**Critical context:** The guard-2 `awk` space-truncation known-debt was NOT duplicated here — it stays in the staged `workflow-drift-check.md` Path-quoting note (R-A5). The staged `conventions.md` adds one expected `rule_1` residue (five total across the staged subtree); it clears at Phase 4 promotion. Do not stamp the staged copy.

### Step 5 — commit 3a9cc84855, 2026-06-03T14:04Z [ctx=info]
**What was done:** Rewrote the staged `commit-conventions.md § Push failure handling` so the first in-session `non-fast-forward` push rejection re-runs `workflow-startup-precheck.sh --mode divergence-only` instead of reloading the full divergence gate prose. The re-run reads the reduced `{divergence, actions_taken}` object and routes on two fields: `divergence.detected == true` presents the three resolutions per `branch-divergence-check.md` with the `ahead` / `behind` counts on screen, and `divergence.skipped == true` (`skip_reason` ∈ {`"no-upstream"`, `"fetch-failed"`}) behaves as the skipped-check path (no gate, record the skip, continue). All three gating behaviors survive the detection move (R4): the first-occurrence-in-session guard, the do-not-silently-retry rule, and the already-Deferred record-and-continue suppression. The TOC row and the section `summary=` annotation were reworded in lockstep (under the rule_5c cap). First-touch copy-then-edit; the live `commit-conventions.md` is byte-unchanged.

**What was discovered:** A naive `X — Y` rewrite of the consolidated non-fast-forward bullet stacked four em dashes into one paragraph, over the house-style cap. Reworked with periods and colons to land at zero em dashes, pre-empting a writing-style finding the Phase C review would otherwise raise on the consolidated bullet.

**What changed from the plan:** none. Scope matched the step description (re-entry via `--mode divergence-only`; R4 guard preservation; both `divergence.detected` and `divergence.skipped` / `skip_reason` read).

**Key files:**
- `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/workflow/commit-conventions.md` (new staged copy)

**Critical context:** The new staged `commit-conventions.md` adds the sixth expected `rule_1` staged residue; it clears at Phase 4 promotion. Do not stamp the staged copy.

### Step 6 — commit b97831dbc3, 2026-06-03T14:10Z [ctx=info]
**What was done:** Rewrote both `§1.6(h)` byte-copy walks in the staged `migrate-workflow/SKILL.md` to consume `workflow-startup-precheck.sh --mode migrate-range`. Step 2.0's classification walk runs the script once, redirects stdout to a `/tmp` file, and reads `unstamped_files`; Step 2's range-derivation walk reads `base_sha`, `log_range`, and `merge_base_failed` from the same file via the `Read` tool's offset/limit (WB1 — `log_range` is uncapped), dropping the inline `git merge-base` fold. The agent-side merge-base-failure recovery loop is preserved verbatim (combined unstamped + `merge_base_failed[].files` re-prompt, drop the failed SHAs, session-wide 3-attempt cap, `--bootstrap-sha` re-invoke, restart the fold), since the script never prompts. The conversational unstamped-bootstrap prompt and the validation/retry prose are kept. First-touch copy-then-edit; the live SKILL is byte-unchanged.

**What was discovered:** The script's `migrate-range` `merge_base_failed[]` entries already carry a resolved `files` array (the script resolves each failing SHA to its artifact paths via its own pair table), so the skill reads `merge_base_failed[].files` directly rather than re-resolving SHAs agent-side. The two `§1.6(h)` references in Steps 4.5 / 4.8 (the per-commit and final stamp-advance walks) are stamp *rewriting*, not range derivation, so they correctly stay agent-side and unchanged — only the two range-derivation byte-copies collapsed. D4's "four byte-copies → one" therefore holds: zero inline `ls`-walk blocks remain in the skill.

**What changed from the plan:** none. Scope matched the step description (both walks rewritten, recovery loop preserved, the WB1 `/tmp`+`Read` pattern, the bootstrap prompt kept).

**Key files:**
- `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/skills/migrate-workflow/SKILL.md` (new staged copy)

**Critical context:** The staged copy adds the seventh expected `rule_1` staged residue; it clears at Phase 4 promotion. Do not stamp the staged copy. All seven Track 4 staged copies are first-touch whole-file adds, so Phase C must compute a `diff <live> <staged>` per copy and scope findings to the delta (D5), not the whole-file add.

## Validation and Acceptance

Track-level behavioral acceptance:

- The rewritten `workflow.md § Startup Protocol` runs `--mode full` and routes on `divergence`, `drift`, `handoffs`, `state`, and `actions_taken`, with no inline detection bash remaining.
- `workflow-drift-check.md` and `branch-divergence-check.md` no longer carry detection bash; each cites the script and retains its conversational resolution prose.
- `conventions.md §1.6(h)` still carries the artifact-walk bash as the spec and now also points at the script implementation.
- `commit-conventions.md § Push failure handling` routes the first in-session non-fast-forward push to `--mode divergence-only`.
- `migrate-workflow/SKILL.md` Step 2 reads the `migrate-range` fields and no longer byte-copies the walk; the unstamped-bootstrap prompt stays in the skill.
- Every edited file lives under `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/.claude/...`; no live `.claude/workflow/**` or `.claude/skills/**` file is modified (S4 / I6).
- Every JSON field name the rewritten prose cites matches the script's actual `--mode` output.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

Track-level note: copy-then-edit on first touch is idempotent — a file already staged is edited in place on its staged copy, not re-copied from live. The Phase 4 promotion is additive (`cp -r` staged over live) and re-entrant per `§1.7(j)`, so an interrupted promotion re-runs as a no-op.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

- Track 1 review note (WB1, context-budget): when Step 6 rewrites
  `migrate-workflow/SKILL.md` Step 2 to consume `migrate-range`, have the
  consumer read the JSON from a `/tmp` file via `Read` with `offset`/`limit`
  rather than inlining the whole blob into the orchestrator. The
  `migrate-range.log_range` array is intentionally uncapped (the migration
  must replay every commit, unlike the drift range's `head -10` display
  cap), so a long branch range could otherwise dump an unbounded commit
  list into context on each invocation.

- **Phase A review findings (iteration 1, captured pre-replan 2026-06-03) —
  decomposition guidance.** The Track 4 Phase A reviews (technical / risk /
  adversarial) ran before the Track 5 inline replan; their blocker (A1, the
  unscoped SKILL entry points) became Track 5. The remaining should-fix /
  suggestion items are recorded here so the next Track 4 Phase A
  decomposition threads them into the step bodies rather than re-deriving
  them:
  - **Dispatch rewrite (Step 1) instruction-completeness (R1/A2/A7).** The
    rewritten `workflow.md § Startup Protocol` must preserve every behavior
    the script's JSON does *not* carry: the no-default divergence pick, the
    handoff-resume freeze ("MUST NOT spawn sub-agents / re-run gate-checks /
    recompile episodes" while a handoff is unresolved), the State A Panel-1
    skip, the five State C sub-state resumes, the section-discrepancy edge,
    and "one phase per resume." Route State C on `state.substate`, not
    `state.phase`. Treat a non-zero script exit (Track 2's total parse-error
    contract) as halt-and-surface, not resume. Branch on the non-happy-path
    JSON: `divergence.skipped` / `skip_reason`, `drift.kind`
    (`unstamped` / `merge-base-failed` / `stamped`), and
    `drift.normalization_landed` + `actions_taken` (recite the autonomous
    normalization commit to the user). Treat the ~30-50-line target as a
    soft goal, not a hard cap — size Step 1 by behaviors-preserved.
  - **Migrate Step 2 (Step 6) recovery loop must survive (R3/A4).** The
    `migrate-range` mode emits the walk / fold / range as data, but the
    script never prompts. Keep the agent-side merge-base-failure recovery
    loop in the skill: re-prompt with the combined unstamped +
    `merge_base_failed[].files` set, drop the failed SHAs, enforce the
    session-wide 3-attempt cap, re-invoke with `--bootstrap-sha`, restart the
    fold. Clarify whether Step 2.0's walk is replaced by
    `migrate-range.unstamped_files` (the `migrate-range` mode carries it) so
    D4's "four byte-copies → one" claim holds rather than collapsing to two.
  - **Push-failure re-entry (Step 5) gating must survive (R4).** `--mode
    divergence-only` replaces only the *detection*; keep the
    first-occurrence-in-session guard and the already-Deferred
    record-and-continue suppression. Read both `divergence.detected` and
    `divergence.skipped` / `skip_reason`.
  - **§1.6(h) pivot (Step 4) stale cross-reference (A3 + T2/A8).** Reconcile the
    `conventions.md §1.6(h)` "Tracks 3 and 4a copy this block byte-for-byte"
    sentence — replace the byte-copy coordinated-edit framing with
    "the script is the single implementation; this section is the spec it
    conforms to, checked by the Track 1 conformance fixture" — or §1.6(h)
    self-contradicts after the pivot. The re-validation (T2/A8) found the same
    stale framing one subsection up in §1.6(a1):530 ("Two regex forms, both
    quoted byte-for-byte by Tracks 3, 4a, and 4b"); the script embodies both
    §1.6(a1) regex idioms, so Step 4 reconciles §1.6(a1) in the same staged
    `conventions.md` edit. The "Tracks 3/4a/4b" labels are prior-plan track
    numbers in the live develop-state file (out of scope to renumber); only the
    byte-copy framing is reconciled.
  - **Known-debt note (A5).** Carry a durable note into the rewritten
    `workflow-drift-check.md` / `conventions.md §1.6(h)` prose: the script's
    normalization walk inherits the fixed-template path-quoting assumption,
    and guard 2's `awk '{print $2}'` truncates a porcelain path containing a
    space. Otherwise the only record vanishes at the `_workflow/` cleanup.
  - **Cite the live script, not frozen design.md (T3/R5).** JSON field names
    are cited from the shipped `.claude/scripts/workflow-startup-precheck.sh`
    `emit_json`, not from frozen design.md, which diverges on the
    `migrate-range` shape, the omitted `state.track` field, and the substate
    slug form (Phase 4 reconciliations, plan §Final Artifacts).
  - **Parity-delta honesty (A6) and handoff order (R6).** The rewritten
    `branch-divergence-check.md` should note the script bounds the startup
    fetch with `timeout 10` (parity holds except on a slow-but-reachable
    remote, caught by the per-commit push re-check). The `handoffs` array
    order (`ls -t`, most-recent-first) is load-bearing — preserve it and the
    basename-under-plan-dir resolution when wiring the handoff-resume trigger.

## Interfaces and Dependencies

**In scope (all STAGED under `docs/adr/ytdb-1007-script-startup/_workflow/staged-workflow/`):**
- `.claude/workflow/workflow.md` — § Startup Protocol dispatch rewrite.
- `.claude/workflow/workflow-drift-check.md` — shrink to reference doc.
- `.claude/workflow/branch-divergence-check.md` — shrink to reference doc.
- `.claude/workflow/conventions.md` — §1.6(h) pivot (keep spec + add pointer).
- `.claude/workflow/commit-conventions.md` — § Push failure handling re-entry.
- `.claude/skills/migrate-workflow/SKILL.md` — Step 2 + Step 2.0 `migrate-range` reuse (both walks, per T1/A10).
- `.claude/workflow/mid-phase-handoff.md` — one-line cross-ref genericization of the `(step 3)` / `(step 3a)` startup-step references at lines 123-125, a ripple of the Step 1 dispatch rewrite (added as the 7th surface per finding A9; folded into Step 1's commit).

**Out of scope:**
- `.claude/scripts/workflow-startup-precheck.sh` and `.claude/scripts/tests/` — authored live by Tracks 1-3, not staged (§1.7(a) does not govern `.claude/scripts/`; D6).
- Any live `.claude/workflow/**` or `.claude/skills/**` file — stays at develop state until the Phase 4 promotion (S4 / I6).

**Staging discipline:** staged-subtree layout per `§1.7(a)`; the `§1.7(b)` marker is declared in the plan's Constraints; reads resolve staged-first per `§1.7(d)`; first touch copies the live file verbatim then edits per `§1.7(e)`; the Phase 4 promotion rebases onto develop first per `§1.7(f)` and is additive and re-entrant per `§1.7(j)`.

**Dependencies:** depends on Tracks 1, 2, and 3 — the `--mode full`, `divergence-only`, and `migrate-range` JSON shapes (including `state` and `actions_taken`) must be final before the prose cites them. No downstream track consumes this one; the Phase 4 promotion is the next consumer.

## Base commit
a65f3615fc7159521a181a2c82f5f3a74d96dd41
