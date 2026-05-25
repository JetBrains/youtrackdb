# Handoff: Phase 4 — adr.md not yet written

**Paused:** 2026-05-25
**Phase:** 4
**Context level at pause:** warning
**Branch:** inplace-worflow-migration
**HEAD:** c459f1cf397b0d6a29735222e92a0c4464b44fd8 "Mark Track 7 complete"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/inplace-worflow-migration/design-final.md` — Phase 4 final design, complete on disk. Mechanical PASS (iter 3, 0 findings), cold-read PASS (iter 2, 3 should-fix items resolved, 2 suggestions held).
- `docs/adr/inplace-worflow-migration/_workflow/design-mutations.md` — Mutation 13 entry appended documenting the phase4-creation review log.
- All `_workflow/` working files (`implementation-plan.md`, `design.md`, `design-mechanics.md` does not exist, `plan/track-*.md` for tracks 1, 2, 3, 4a, 4b, 5, 6, 7) — all in `[x]` final state.

## Pending decision

No pending user decision. Phase 4 is mid-execution: `design-final.md` is on disk; `adr.md` is the next artifact to write per `prompts/create-final-design.md` Step 3 Artifact 2. The next session resumes at the ADR write step.

## Verbatim re-present text

The resume session should produce `docs/adr/inplace-worflow-migration/adr.md` directly (no edit-design skill invocation — `adr.md` does not go through the mutation discipline). Template lives at `prompts/create-final-design.md` Step 3 § "Artifact 2: ADR (`adr.md`)". Aggregate from BOTH step episodes (ground-truth detail in each `plan/track-N.md` § Episodes) AND track episodes (strategic framing in the plan file's Checklist `[x]` lines). The Ephemeral identifier rule applies rigorously: strip every Track N / Step N / review-finding ID from Decision Records and Key Discoveries; replace with prose, file/class reference, or commit SHA.

The Decision Records to restate in `adr.md` (carry the original D1–D14 numbering from the plan since `design-final.md` cites them by short label):

- D1: Per-artifact SHA stamp, not single sentinel
- D2: Lockstep per-commit advance + final stamp-to-HEAD batch
- D3: Ephemeral artifacts only, no Phase 4 stamping
- D4: "Migrate now" ends session; user re-invokes `/migrate-workflow`
- D5: No legacy backfill — migration's user-prompt bootstraps stamps
- D6: Parameterize `self-improvement-reflection.md`, don't fork it
- D7: HTML-comment stamp on line 1, before the H1
- D8: Ask user for unstamped-artifact base SHA, don't silently auto-compute
- D9: Drift gate fires at `/create-plan` startup, not only `/execute-tracks`
- D10: Comparison range is `BASE_SHA..HEAD`; branch is a self-contained capsule
- D11: On no-drift with non-uniform stamps, normalize to fold result + auto-commit
- D12: Migration preflight refuses on uncommitted or untracked `_workflow/**` state
- D13: Drift detection and migration scope to the active plan directory, not the whole branch
- D14: Stage workflow document changes under `<plan-dir>/_workflow/staged-workflow/`; promote at Phase 4

Invariants I1–I6 carry through verbatim from the plan's Invariants section.

Key Discoveries to synthesize from step episodes (strip ephemeral identifiers):

- §1.6 of `conventions.md` became the durable single source for the stamp format, parser idioms, SHA computation, range definition, unstamped-artifact protocol, no-silent-default non-rule, stamped-artifact list with Phase 4 / `design-mutations.md` exclusions, active-plan scope rule, and the Phase 1 walk bash. The `### (a)`–`### (h)` subsection rendering freezes the anchor surface so every downstream writer cites anchors verbatim instead of re-quoting prose.
- The §1.6(h) Phase 1 walk byte-copy contract between `workflow-drift-check.md` and `migrate-workflow/SKILL.md` survives extension because the migration-side `STAMPED_PAIRS` addition is purely additive (one init line plus one assignment inside the stamped branch). Future format changes need only diff the two copies.
- A first→second-caller pattern repeated across multiple workflow-modifying tracks: under-specified boundary conditions when adding a second caller to a previously single-caller protocol. The drift-gate file required a 12-surface caller-symmetric sweep to handle `/create-plan` alongside `/execute-tracks` (the original "intro paragraph only" scope was wrong). The reflection protocol required a 10-surface session-type sweep. Future tracks adding a third caller to similar two-caller protocols should plan a boundary-condition audit pass during Phase A decomposition.
- The drift-gate no-drift normalization commit's diff-shape guard uses two independent checks (`git diff -U0 @@ -1` hunk-header verification plus `git status --porcelain` cross-check) with a `git checkout` restore on mismatch. The redundancy is deliberate; either check on its own catches single-vector violations but not the combined "stamp line stays at line 1 but the artifact also gained an unrelated edit elsewhere" case.
- The migration-side merge-base failure recovery uses continue-and-collect rather than break-on-first-failure: a single user prompt covers every failing pair, avoiding quadratic re-prompting on heavily-pruned histories.
- The Phase 4 promotion bash for workflow-modifying plans (`prompts/create-final-design.md` Step 4) uses `[ -d "$STAGED_DIR/.claude" ]` (not the bare staged directory) for the directory-presence guard, the divergence sanity check measures `merge-base..origin/develop` past the fork-point (not `merge-base..HEAD`), and the commit step uses `git diff --cached --quiet || git commit` for empty-commit short-circuiting on Phase 4 resume.
- The ephemeral-identifier pre-commit gate excludes `_workflow/**` and `.claude/workflow/**` but NOT `.claude/skills/**`. Plan-file Decision Record IDs cited inside SKILL bodies fail the resolvability test, because SKILL.md survives the squash but plan DR IDs do not. Future tracks touching SKILL bodies should restate D-references in inline prose at SKILL.md edit sites.
- The ephemeral-identifier gate's regex matches Markdown structural-element names (`H1`, `H2`, `H3`) and SKILL-internal procedural-step headers (a file's own "Step 4" prose). Both pass under the inspect-then-rewrite contract — these are self-contained references that resolve trivially inside the same file, not the workflow-internal `Step N of Track N` labels the rule actually forbids.
- The forward-applicable carve-out for the current branch: this branch did not stage its own workflow edits because no prior version of §1.7 existed during its execution. The convention applies to the next workflow-modifying branch that opens a plan after this PR merges. The first such branch exercises the path-mapping rule, the Phase 4 promotion guard, and the drift-gate exclusion end-to-end.
- Track 7's review surfaced 17 follow-on findings deferred to this Phase 4 self-improvement reflection — 4 design enhancements (reads-precedence enforcement at commit time, pre-commit inverse-misroute gate, gate-checked-vs-final-`-m` coupling, post-rebase staged-subtree audit), 3 ephemeral / orchestrator-side surface edits, 3 context-budget refactors against the explicit inline-shape choice in Track 7's Decision Log, 4 minor documentation gaps, and 1 long-bullet readability observation at the soft cap by design.

## Resume notes

- Do NOT redo: every section of `design-final.md` (on disk and reviewed); every track episode (`[x]` final); every track-level code review iteration (closed in track files' Outcomes & Retrospective).
- Next action on resume: write `docs/adr/inplace-worflow-migration/adr.md` per `prompts/create-final-design.md` Step 3 Artifact 2. Use Write directly (no edit-design skill — adr.md does not route through the mutation discipline). After adr.md is on disk:
    1. Run the pre-commit ephemeral-identifier gate by hand on both `design-final.md` and `adr.md` per `ephemeral-identifier-rule.md` § Self-check before commit (the grep at `^\+.*\b(Track|Step)[ ]?[0-9]+|^\+.*\b[A-Z]{1,3}-?[0-9]+\b`, with the `:(exclude)` filters for `_workflow/**` and `.claude/workflow/**`).
    2. Step 4 (Promote staged workflow): the `[ -d "$STAGED_DIR/.claude" ]` guard at the top of the promotion bash returns false on this branch (forward-applicable carve-out — no staged subtree exists), so the step is a silent no-op. Just run the bash and verify it skips.
    3. Step 5 (Commit final artifacts): stage ONLY `design-final.md` and `adr.md` (top-level paths under `docs/adr/inplace-worflow-migration/`); do NOT stage anything under `_workflow/`. Commit with the message shape from `prompts/create-final-design.md` Step 5. Push immediately.
    4. Step 6 (Cleanup commit): `git rm -r docs/adr/inplace-worflow-migration/_workflow/`, commit with "Remove workflow scaffolding", push.
    5. Step 7 (Self-improvement reflection): load `self-improvement-reflection.md` with `session-type=execute-tracks` (this is `/execute-tracks` Phase 4, not `/migrate-workflow`). The 17 deferred findings from Track 7 are the highest-value reflection candidates; file the design-enhancement subset (reads-precedence enforcement, pre-commit inverse-misroute gate, gate-checked-vs-final-`-m` coupling, post-rebase staged-subtree audit) as `dev-workflow` YouTrack issues. Cap at 3 issues per the protocol.
    6. Step 8 (Inform user Phase 4 complete): list the YouTrack issue ids from Step 7 in the "Phase 4 complete" message.
- On user feedback after design-final.md is reviewed: address inline if mechanical/cold-read recheck passes; otherwise iterate via `/edit-design` (post-merge, the final artifact is committed; small touch-ups go through the standard git workflow).
