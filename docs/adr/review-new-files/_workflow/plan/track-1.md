<!-- workflow-sha: 38bd7a0b1539ec1b3529e077fa0fba57df312574 -->
# Track 1: Cover genuinely-new staged files in Phase B/C review scope

## Purpose / Big Picture
A genuinely-new staged `.claude/**` file — one with no live counterpart on `develop` — is now reviewed in full during Phase B and Phase C, instead of shipping unreviewed under an out-of-scope marker.

> **Complexity (predicted):** medium — Workflow machinery, behavioral but bounded: an else-branch plus a context-block rewrite across two review-setup files (`track-code-review.md`, `step-implementation.md`); no HIGH trigger fires (no auto-running script, no load-bearing gate/schema edit). Reconciled to `max(step tags)` at Phase A → C.
> **Scope:** ~2 files covering the Phase C step-8 delta-staging loop + context block (`track-code-review.md`) and the byte-identical Phase B step-review copy (`step-implementation.md`).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

A brand-new staged `.claude/**` file ships unreviewed while the review machinery reports a clean pass. The chain that produces this is short (the three terms in bold below — *staged copy*, *delta file*, and the `if [ -f "$live" ]` guard — are glossed in `## Context and Orientation`). The review setup builds a **delta file** only when the staged copy has a live counterpart, gated by the `if [ -f "$live" ]` guard. A staged file that is itself new (a new agent, skill, prompt, or doc, with no `develop` version) has no live counterpart, so it falls through the guard and gets no delta entry. The reviewer-facing context block marks everything past a delta boundary out of scope, treating it as verbatim-copied and already-reviewed. A NEW file has no delta entry, so it has no reviewed portion for the boundary to carve out, and the whole file is marked out of scope. So the new file is never reviewed. This track closes the gap in the two files that carry the delta-staging setup, adding a NEW-file marker for the no-counterpart case and rewriting the context block so a NEW file reads as review-in-full.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- The track-canonical live decision carrier (D7). Phase 1 seeds the full
inline Decision Records this track owns (full four-bullet form). Seeded from
the research log (design_gate=no, no design.md). Author fills from the log's
D1/D2/D3. -->

### D1: Fix both files, all four defect locations per file
- **Decision:** Fix both `track-code-review.md` (Phase C step 8) and `step-implementation.md` (Phase B step-level review), and within each file fix all four locations where the "only when a live counterpart exists" scoping is stated: the preamble prose, the bash loop's `if [ -f "$live" ]`, the post-loop narration, and the context block. That is eight edit points total. The context block is a rewrite, not an appended sentence.
- **Why:** The defect is one logical bug — a missing no-live-counterpart case plus the "out of scope" instruction that follows from it — but the scoping that produces it is stated four times per file. Editing a subset leaves a contradiction that a consistency review flags. For example, the loop now emits a NEW marker while the preamble above it still says a delta is written only when the live file exists. Or the post-loop line still restricts the trigger to adds "that has a live counterpart", now contradicting the loop below it. The context block specifically must be rewritten rather than appended, because a NEW-file marker makes the delta file non-empty, and the current block's blanket "when that file is non-empty, scope your findings to the delta … the rest is out of scope" would then read the NEW file as out-of-scope — which reintroduces the same unreviewed-file bug, this time through the non-empty delta file rather than the missing guard branch. The two files carry a near-verbatim copy of the same setup (the "canonical context block"), so both must move together or the Phase B copy under-covers silently.
- **Alternatives rejected:** (a) Fix only the loop and context block, or only `track-code-review.md` as the issue literally scopes it — rejected: leaves contradicting preamble/post-loop prose and the defective Phase B copy (this is what the iter1 adversarial gate flagged as blocker A1). (b) De-duplicate the loop and context block into a shared include — rejected: workflow docs are standalone Markdown read independently per phase, there is no include mechanism, and adding one is a far larger change than this bug warrants. Mirror the existing intentional duplication instead.
- **Scope confirmed closed:** grep over `.claude/**` for the delta temp-path and the "freshly-created staged" prose found the loop and context block in exactly these two files. `conventions.md §1.7(k)` only references the concept (it points at "the Phase C Startup staged-delta prep in track-code-review.md step 8"); it holds no third copy of the loop, so no fix is needed there.

### D2: The NEW-file marker emits the staged path
- **Decision:** The else-branch marker is `=== NEW staged file (no live counterpart): <staged> ===`, naming the staged path (`docs/adr/<dir>/_workflow/staged-workflow/.claude/…`), not the derived live path.
- **Why:** The reviewer locates the file in the cumulative or step diff, which shows it under its staged path as a whole-file add. The staged path is the locator the reviewer already needs to find the file; the derived live path is that same path minus a fixed prefix, so it adds no locating power.
- **Alternatives rejected:** (a) Emit the derived live path — rejected: it names the semantic identity but not where the file appears in the diff the reviewer reads. (b) Emit both — rejected: redundant, since the live path is a deterministic prefix-strip of the staged path.
- **Consistency:** The `=== NEW staged file … ===` marker text is identical in both files; only the temp-file path (`track-{N}-delta` vs `step-{N}-{M}-delta`) and the surrounding indentation differ, matching the existing per-file divergence.

### D3: §1.7(k) prose-rule opt-out — edit live, minimal shape
- **Decision:** Take the `§1.7(k)` judgment-layer opt-out. Edit the two workflow files live (under `.claude/workflow/`) rather than staging copies. Single-track, `design_gate=no`, minimal complexity.
- **Why:** The change is confined to judgment-layer workflow prose — an else branch in a bash snippet plus a context-block rewrite. This branch adds no new `.claude/**` files, so the bug it fixes cannot even trigger on this branch's own review. The `§1.7(k)` opt-out disables the staged-delta prep that the fix touches, so editing live carries no self-referential hazard. The shape matches prior prose-fix branches that used minimal complexity plus opt-out.
- **Alternatives rejected:** Full `§1.7` staging — rejected: the staging machinery overhead is unwarranted for a two-file prose fix with no new-file adds and no self-application risk.
- **Consequence:** The fix ships without in-workflow self-validation (the opt-out disables the prep it changes on this branch). A manual coherence trace across the eight edit points and the two acceptance-verifying reviews stand in for the missing self-run; the adversarial gate recorded this (A3) as a non-gating suggestion.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

Three terms recur below. A **staged copy** is the `docs/adr/<dir>/_workflow/staged-workflow/.claude/…` mirror of a `.claude/**` file that a `§1.7`-staged track edits; committing the mirror alongside the live file is what lets a draft PR show the change without the branch overwriting a live rule mid-flight. The **delta file** is a per-track (Phase C) or per-step (Phase B) temp file the review setup builds — for each staged copy it holds a `diff <live> <staged>`, so the reviewer scopes findings to the actual edit instead of the whole-file add the diff shows. The **§1.7(k) opt-out** is the judgment-layer rule that lets a small prose fix edit workflow files live and skip the staging machinery.

Both files are at the current on-disk state on branch `review-new-files`, freshly cut from `develop` (last workflow commit touching `track-code-review.md` is `03eac656fa`). Both defect sites are present and match the issue.

The setup lives in two files, each with the same four defect locations that must move together:

1. `.claude/workflow/track-code-review.md` — Phase C Startup step 8. The preamble prose ends "…and when the live file exists write `diff <live> <staged>`" (~271); the bash loop guards the diff with `if [ -f "$live" ]` and has no else branch (~283-289); the post-loop narration says the trigger "fires only on a new-file add … that has a live counterpart" (~293); the "Review-target delta for freshly-created staged copies" context block (~454-465) tells reviewers that when the delta file is non-empty, "the rest of each whole-file add is verbatim-copied, already-live, already-reviewed content and is out of scope."
2. `.claude/workflow/step-implementation.md` — Phase B step-level review (fires on `high` steps). The same preamble (~486), the same loop else-gap (~498-504), the same post-loop narration (~508), and the byte-identical context block (~610-621), differing only in the temp-file path (`step-{N}-{M}-delta` vs `track-{N}-delta`) and indentation.

The loop already enumerates the new file — it is a `--diff-filter=A` add under the staged prefix — the file simply falls through the `if [ -f "$live" ]` with no else. So the else branch is the complete loop-side fix; the enumeration needs no change.

Concrete deliverables: eight edits (four per file), all landing live under `.claude/workflow/`.

## Plan of Work

Apply the same four-location fix to each file, then confirm the two stay consistent. The four locations within one file have no ordering constraint between them (they are separate passages); the cross-file constraint is that both files end in the same shape.

**(a) Preamble.** State the no-live-counterpart case. The current preamble stops at "…and when the live file exists write `diff <live> <staged>` to a per-track delta temp file". Extend it so it also says a staged add with no live counterpart is recorded under a NEW-file marker. This keeps the prose consistent with the loop's new else branch.

**(b) Loop else branch.** Add an `else` to `if [ -f "$live" ]` that appends the NEW marker (D2). In `track-code-review.md` the loop body becomes:

```bash
if [ -f "$live" ]; then
    {
        printf '=== delta: %s vs %s ===\n' "$live" "$staged"
        diff "$live" "$staged"
        printf '\n'
    } >> /tmp/claude-code-track-{N}-delta-$PPID.txt
else
    printf '=== NEW staged file (no live counterpart): %s ===\n\n' "$staged" \
        >> /tmp/claude-code-track-{N}-delta-$PPID.txt
fi
```

The `step-implementation.md` copy is identical except for the temp-file path (`step-{N}-{M}-delta`) and two extra levels of indentation.

**(c) Post-loop narration.** Drop the "that has a live counterpart" restriction. Post-fix the loop records every add under the staged prefix — as a delta when a live counterpart exists, as a NEW marker when it does not — so the sentence must describe both outcomes rather than restricting the trigger to the delta case.

**(d) Context block rewrite.** Replace the block's single blanket "when non-empty, scope to the delta … the rest is out of scope" instruction with a per-entry, mutually-exclusive distinction keyed on the marker (D1). The rewritten block must say: a file under a `=== delta: … ===` marker is scoped to its delta (the rest of the whole-file add is verbatim-copied, already-reviewed, out of scope); a file under a `=== NEW staged file (no live counterpart): … ===` marker has no already-reviewed live baseline and must be reviewed in full. The empty-file case (no freshly-created staged copy in range, or an ordinary plan) still says review the diff as usual.

**Consistency check.** After both files are edited, diff the two setup regions to confirm they diverge only in the temp-file path and indentation — the same near-verbatim relationship they hold today. Confirm an ordinary (non-workflow-modifying) plan is unaffected: with no staged adds in range the loop iterates zero times and yields an empty delta file, so the context block stays inert exactly as before.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step.
Empty at Phase 1. -->

## Validation and Acceptance

This is a prose and bash change with no unit test; acceptance is verified by inspection and by tracing the machinery it edits.

- **NEW-file marker emitted.** In each file, the step-8 / step-review loop appends `=== NEW staged file (no live counterpart): <staged> ===` for a staged add whose derived live counterpart does not exist. Verify by reading the else branch and, if desired, dry-running the loop against a staged tree that contains one new file and one edited copy of a live file: the delta file must show a `=== delta: … ===` entry for the edited copy and a `=== NEW staged file … ===` entry for the new file.
- **Context block distinguishes NEW from delta-scoped.** Each file's "Review-target delta for freshly-created staged copies" block states the delta-scoped case (scope to the delta, rest out of scope) and the NEW case (no live baseline, review in full) as a per-entry, mutually-exclusive distinction keyed on the marker — not an appended note under the old blanket "out of scope" sentence.
- **Preamble and post-loop agree with the loop.** No location still restricts the recorded set to adds "that has a live counterpart"; the preamble names the NEW-file marker; the post-loop narration describes both the delta and NEW outcomes.
- **The two files stay consistent.** The two setup regions diverge only in the temp-file path (`track-{N}-delta` vs `step-{N}-{M}-delta`) and indentation.
- **Ordinary plans unaffected.** On a non-workflow-modifying plan (no `§1.7(b)` staging marker), the loop enumerates zero staged adds, produces an empty delta file, and the context block stays inert — identical to pre-fix behavior.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as
test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once
steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In scope:** `.claude/workflow/track-code-review.md` (Phase C Startup step 8, four locations) and `.claude/workflow/step-implementation.md` (Phase B step-level review, four locations). Both edited live under `.claude/workflow/`.

**Out of scope:** `conventions.md §1.7(k)`, which only references "the Phase C Startup staged-delta prep in track-code-review.md step 8" as a pointer and holds no copy of the loop or context block. The fix's wording should stay consistent with how `§1.7(k)` names step 8, but no edit lands there.

**Inter-track dependencies:** none — this is a single-track plan.

**Staging:** under the `§1.7(k)` opt-out (D3) the edits are live; there is no `staged-workflow/` mirror for this branch and no phase-ledger `s17` workflow-modifying token.

## Invariants & Constraints
<!-- Plan-at-start, combined section (D9). Phase 1 writes the per-track
testable constraints and invariants; each is a property that must hold, backed
by a check. Author fills. -->

Each invariant is verifiable by review or inspection — a prose change has no unit test.

- **Inv 1 — NEW markers cover no-counterpart adds.** A staged add with no live counterpart appears in the delta file under a `=== NEW staged file (no live counterpart): … ===` marker, never silently absent. *Check:* the loop's else branch (Plan of Work (b)); a dry run against a staged tree with one new file.
- **Inv 2 — the context block separates the two cases.** The context block presents delta-scoped (scope to the delta) and NEW (review in full) as a per-entry, mutually-exclusive distinction keyed on the marker, with no residual blanket sentence that would fold a NEW file into "out of scope". *Check:* read the rewritten block in each file.
- **Inv 3 — cross-file consistency.** The two files' setup regions diverge only in the temp-file path and indentation. *Check:* diff the two regions.
- **Inv 4 — ordinary plans unaffected.** On a plan with no staged adds the loop yields an empty delta file and the context block stays inert, matching pre-fix behavior. *Check:* trace the loop with an empty `--diff-filter=A` result set; confirm no path change outside the staged-prefix `§1.7(b)` branch.
- **Inv 5 — no third copy left stale.** No file other than the two named carries a copy of the loop or context block; `§1.7(k)` holds only a pointer. *Check:* grep `.claude/**` for the delta temp-path and the "freshly-created staged" prose (D1 scope-closed).
