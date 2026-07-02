<!-- workflow-sha: 38bd7a0b1539ec1b3529e077fa0fba57df312574 -->
# Track 1: Cover genuinely-new staged files in Phase B/C review scope

## Purpose / Big Picture
A genuinely-new staged `.claude/**` file — one with no live counterpart on `develop` — is now reviewed in full during Phase B and Phase C, instead of shipping unreviewed under an out-of-scope marker.

> **Complexity (predicted):** medium — Workflow machinery, behavioral but bounded: an else-branch plus a context-block rewrite across two review-setup files (`track-code-review.md`, `step-implementation.md`); no HIGH trigger fires (no auto-running script, no load-bearing gate/schema edit). Reconciled to `max(step tags)` at Phase A → C.
> **Scope:** ~2 files covering the Phase C step-8 delta-staging loop + context block (`track-code-review.md`) and the byte-identical Phase B step-review copy (`step-implementation.md`).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

A brand-new staged `.claude/**` file ships unreviewed while the review machinery reports a clean pass. The chain that produces this is short (the three terms in bold below — *staged copy*, *delta file*, and the `if [ -f "$live" ]` guard — are glossed in `## Context and Orientation`). The review setup builds a **delta file** only when the staged copy has a live counterpart, gated by the `if [ -f "$live" ]` guard. A staged file that is itself new (a new agent, skill, prompt, or doc, with no `develop` version) has no live counterpart, so it falls through the guard and gets no delta entry. The reviewer-facing context block marks everything past a delta boundary out of scope, treating it as verbatim-copied and already-reviewed. A NEW file has no delta entry, so it has no reviewed portion for the boundary to carve out, and the whole file is marked out of scope. So the new file is never reviewed. This track closes the gap in the two files that carry the delta-staging setup, adding a NEW-file marker for the no-counterpart case and rewriting the context block so a NEW file reads as review-in-full.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [x] Track completion

- [x] 2026-07-01T17:02Z [ctx=safe] Review + decomposition complete
- [x] 2026-07-02T05:46Z [ctx=safe] Step 1 complete (commit c310214714)
- [x] 2026-07-02T06:19Z [ctx=safe] Track-level code review complete — reviews PASS (iter 1)
- [x] 2026-07-02T06:27Z [ctx=safe] Track complete

## Base commit

0aaf4e7c61dee2475d30e77a05f77f19dfb515fa (Phase B startup, before the first implementer spawn).

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- Inv 5 / D1 / Idempotence acceptance grep — run it as the bare substring `delta: %s vs %s`, not the quote-adjacent `'delta: %s vs %s'` the track file writes. The source marker is `=== delta: %s vs %s ===`, so a literal-single-quote reading of the pattern matches nothing; the shell-quoting reading matches. Phase C's consistency review should use the bare-substring form to confirm the loop and context block live in exactly the two named files. See Episodes §Step 1.

## Decision Log
<!-- The track-canonical live decision carrier (D7). Phase 1 seeds the full
inline Decision Records this track owns (full four-bullet form). Seeded from
the research log (design_gate=no, no design.md). Author fills from the log's
D1/D2/D3. -->

### D1: Fix both files, all four defect locations per file
- **Decision:** Fix both `track-code-review.md` (Phase C step 8) and `step-implementation.md` (Phase B step-level review), and within each file fix all four locations where the "only when a live counterpart exists" scoping is stated: the preamble prose, the bash loop's `if [ -f "$live" ]`, the post-loop narration, and the context block. That is eight scoping edits total. The context block is a rewrite, not an appended sentence, and the rewrite removes the file-level "when the delta file is non-empty, scope your findings to the delta" routing gate entirely — not just the inner "out of scope" sentence — replacing it with per-marker routing (see Plan of Work (d)). One further edit per file falls out of the fix but is not part of the scoping chain: the burden-measure prose (Plan of Work (e)) claims a staged whole-file copy adds no proportional review surface, which is false for a NEW file, so it is qualified too. Ten edits across the two files in total (eight scoping + two burden-measure).
- **Why:** The defect is one logical bug — a missing no-live-counterpart case plus the "out of scope" instruction that follows from it — but the scoping that produces it is stated four times per file. Editing a subset leaves a contradiction that a consistency review flags. For example, the loop now emits a NEW marker while the preamble above it still says a delta is written only when the live file exists. Or the post-loop line still restricts the trigger to adds "that has a live counterpart", now contradicting the loop below it. The context block specifically must be rewritten rather than appended, because a NEW-file marker makes the delta file non-empty. The current block routes on the file being non-empty: an outer "when that file is non-empty, scope your findings to the delta" gate, followed by an inner "the rest is out of scope" justification. A NEW-only delta file is non-empty yet carries zero `diff <live> <staged>` lines, so under the current wording the outer gate routes it into "scope to the delta" — scoping to nothing — and the NEW file goes unreviewed. This is why the rewrite must dismantle the file-level non-empty gate, not just soften the "out of scope" sentence. The two files carry a near-verbatim copy of the same setup (the "canonical context block"), so both must move together or the Phase B copy under-covers silently.
- **Alternatives rejected:** (a) Fix only the loop and context block, or only `track-code-review.md` as the issue literally scopes it — rejected: leaves contradicting preamble/post-loop prose and the defective Phase B copy (this is what the iter1 adversarial gate flagged as blocker A1). (b) De-duplicate the loop and context block into a shared include — rejected: workflow docs are standalone Markdown read independently per phase, there is no include mechanism, and adding one is a far larger change than this bug warrants. Mirror the existing intentional duplication instead.
- **Scope confirmed closed:** grep over `.claude/**` for the loop marker (`'delta: %s vs %s'`) and the "Review-target delta for freshly-created staged copies" context-block heading found the loop and block in exactly these two files. `conventions.md §1.7(k)` only references the concept (it points at "the Phase C Startup staged-delta prep in track-code-review.md step 8"); it holds no third copy of the loop, so no fix is needed there. (Grepping the bare delta temp-path is not the check — it also matches the two inert `rm -f` teardown lines, adding noise; the loop marker and block heading target the actual copies.)
- **Dispatch already works (A2):** reviewer *dispatch* on a NEW staged file needs no edit. The Step-5b staged-path normalization (`code-review/SKILL.md`, `review-agent-selection.md`) is a pure prefix-strip with no live-existence check, so a NEW staged file matches the workflow-review globs and the reviewers launch. The fix only changes what a launched reviewer treats as in-scope, not whether one launches — which is why the two-file footprint is complete.
- **Single commit (A4 survival test):** the eight scoping edits ship as one commit, not split per-file or per-location. Any split creates a committed intermediate state where the prose contradicts the code — e.g., the loop emits a NEW marker while the context block still folds it into "out of scope" — the very contradiction D1 exists to avoid. Decomposition keeps this a single step (see `## Concrete Steps`).

### D2: The NEW-file marker emits the staged path
- **Decision:** The else-branch marker is `=== NEW staged file (no live counterpart): <staged> ===`, naming the staged path (`docs/adr/<dir>/_workflow/staged-workflow/.claude/…`), not the derived live path.
- **Why:** The reviewer locates the file in the cumulative or step diff, which shows it under its staged path as a whole-file add. The staged path is the locator the reviewer already needs to find the file; the derived live path is that same path minus a fixed prefix, so it adds no locating power.
- **Alternatives rejected:** (a) Emit the derived live path — rejected: it names the semantic identity but not where the file appears in the diff the reviewer reads. (b) Emit both — rejected: redundant, since the live path is a deterministic prefix-strip of the staged path.
- **Consistency:** The `=== NEW staged file … ===` marker text is identical in both files; only the temp-file path (`track-{N}-delta` vs `step-{N}-{M}-delta`) and the surrounding indentation differ, matching the existing per-file divergence.

### D3: §1.7(k) prose-rule opt-out — edit live, minimal shape
- **Decision:** Take the `§1.7(k)` judgment-layer opt-out. Edit the two workflow files live (under `.claude/workflow/`) rather than staging copies. Single-track, `design_gate=no`, minimal complexity.
- **Why:** The change is confined to judgment-layer workflow prose — an else branch in a bash snippet plus a context-block rewrite. This branch adds no new `.claude/**` files, so the bug it fixes cannot even trigger on this branch's own review. The `§1.7(k)` opt-out disables the staged-delta prep that the fix touches, so editing live carries no self-referential hazard. The shape matches prior prose-fix branches that used minimal complexity plus opt-out.
- **Alternatives rejected:** Full `§1.7` staging — rejected: the staging machinery overhead is unwarranted for a two-file prose fix with no new-file adds and no self-application risk.
- **Consequence:** The fix ships without in-workflow self-validation (the opt-out disables the prep it changes on this branch). A manual coherence trace across the ten edit points and the two acceptance-verifying reviews stand in for the missing self-run; the research-log adversarial gate recorded this as a non-gating suggestion.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (2 findings, 2 accepted). T1 (diff-range divergence not named in the cross-file-consistency claims) and T2 (Inv 5 grep target caught two inert `rm -f` teardown lines) — both suggestions, both applied. Gate-verification VERIFIED both, no regression.
- [x] Adversarial: PASS at iteration 2 (5 findings, 4 accepted). A1 (context block's outer non-empty gate would route a NEW-only delta into scope-to-nothing — should-fix, applied), A2 (record that reviewer dispatch already works, so the two-file footprint is provably complete — should-fix, applied), A3 (step-9 burden-measure prose false for a NEW file — should-fix, brought in scope), A4 (single-commit survival test — suggestion, folded into D1 + decomposition). A5 (Inv 1 stage-then-delete edge, THEORETICAL/safe) — no change. Gate-verification VERIFIED A1–A4, REJECTED A5, whole-file regression read clean.
- [x] Phase C track-level code review: PASS at iteration 1. Workflow-only diff → 4 workflow-review agents; consistency / context-budget / instruction-completeness returned 0 findings, writing-style returned 1 suggestion (WS1). WS1 — a negative-parallelism `not X` tail in the burden-measure prose, mirrored in both files — was applied at track completion via `Review fix: fd6bb219ec`. No blockers, no should-fix, no deferred findings.

## Context and Orientation

Three terms recur below. A **staged copy** is the `docs/adr/<dir>/_workflow/staged-workflow/.claude/…` mirror of a `.claude/**` file that a `§1.7`-staged track edits; committing the mirror alongside the live file is what lets a draft PR show the change without the branch overwriting a live rule mid-flight. The **delta file** is a per-track (Phase C) or per-step (Phase B) temp file the review setup builds — for each staged copy it holds a `diff <live> <staged>`, so the reviewer scopes findings to the actual edit instead of the whole-file add the diff shows. The **§1.7(k) opt-out** is the judgment-layer rule that lets a small prose fix edit workflow files live and skip the staging machinery.

Both files are at the current on-disk state on branch `review-new-files`, freshly cut from `develop` (last workflow commit touching `track-code-review.md` is `03eac656fa`). Both defect sites are present and match the issue.

The setup lives in two files, each with the same four defect locations that must move together:

1. `.claude/workflow/track-code-review.md` — Phase C Startup step 8. The preamble prose ends "…and when the live file exists write `diff <live> <staged>`" (~271); the bash loop guards the diff with `if [ -f "$live" ]` and has no else branch (~283-289); the post-loop narration says the trigger "fires only on a new-file add … that has a live counterpart" (~293); the "Review-target delta for freshly-created staged copies" context block (~454-465) routes on the delta file being non-empty — an outer "when that file is non-empty, scope your findings to the delta" gate, then the inner "the rest of each whole-file add is verbatim-copied, already-live, already-reviewed content and is out of scope" justification.
2. `.claude/workflow/step-implementation.md` — Phase B step-level review (fires on `high` steps). The same preamble (~486), the same loop else-gap (~498-504), the same post-loop narration (~508), and the byte-identical context block (~610-621). Within the edited regions the two files differ only in the temp-file path (`step-{N}-{M}-delta` vs `track-{N}-delta`) and indentation. The surrounding loop scaffolding also differs in the git-diff range each phase computes (`{commit}~1..{commit}` here vs `{base_commit}..HEAD` in Phase C) — a pre-existing, intentional divergence outside the added else branch, which is itself byte-identical modulo temp-path and indentation.

The loop already enumerates the new file — it is a `--diff-filter=A` add under the staged prefix — the file simply falls through the `if [ -f "$live" ]` with no else. So the else branch is the complete loop-side fix; the enumeration needs no change.

A fifth passage in each file is adjacent prose, not part of the scoping chain, but the NEW case makes it wrong: the burden-measure line (track-code-review.md step 9 ~340-342; step-implementation.md ~862-863) tells the Phase C orchestrator a staged whole-file copy adds no proportional review surface and is line-count noise. That holds for a copy-of-live file but not for a NEW file, whose whole-file content is the real review surface and which has no `diff <live> <staged>` delta to be "the truer measure". Left unqualified it would under-count a NEW file's review burden — a consistency review would flag it by D1's own standard — so it is fixed too.

Concrete deliverables: ten edits — four scoping locations plus one burden-measure qualifier per file — all landing live under `.claude/workflow/`.

## Plan of Work

Apply the same four-location scoping fix plus the one burden-measure qualifier to each file, then confirm the two stay consistent. The locations within one file have no ordering constraint between them (they are separate passages); the cross-file constraint is that both files end in the same shape in the edited regions.

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

**(d) Context block rewrite.** Replace the block's file-level routing — the outer "when that file is non-empty, scope your findings to the delta" gate together with its inner "the rest is out of scope" justification — with a per-entry, mutually-exclusive distinction keyed on the marker (D1). Removing the outer non-empty gate is the load-bearing part: a NEW-only delta file is non-empty yet carries zero delta lines, so any surviving "when non-empty ⇒ scope to the delta" framing would route it into scoping-to-nothing and reintroduce the bug. The rewritten block must say: a file under a `=== delta: … ===` marker is scoped to its delta (the rest of the whole-file add is verbatim-copied, already-reviewed, out of scope); a file under a `=== NEW staged file (no live counterpart): … ===` marker has no already-reviewed live baseline and must be reviewed in full. The empty-file case (no freshly-created staged copy in range, or an ordinary plan) still says review the diff as usual.

**(e) Burden-measure qualifier.** Qualify the burden-measure prose so the NEW case reads correctly. In `track-code-review.md` step 9 (~340-342) the claim that a whole-file staged copy "inflates the line count without adding proportional review surface (the `diff <live> <staged>` delta from step 8 is the truer measure)" holds only for a copy-of-live file; add that a NEW staged file (no live counterpart) has no such delta and its whole-file content is the real review surface, so its line count is not inflated. Apply the parallel qualifier to `step-implementation.md` (~862-863). This is adjacent prose the fix makes imprecise, not a scoping location, but it moves with the fix so a consistency review does not flag it (D1).

**Consistency check.** After both files are edited, diff the *edited regions* — the added else branch and the rewritten context block — to confirm they diverge only in the temp-file path and indentation, the same near-verbatim relationship those regions hold today. Do not expect the whole surrounding setup to match byte-for-byte: the loops already diverge in the git-diff range each phase computes (`{commit}~1..{commit}` in Phase B vs `{base_commit}..HEAD` in Phase C), a pre-existing intentional difference the fix does not touch. Confirm an ordinary (non-workflow-modifying) plan is unaffected: with no staged adds in range the loop iterates zero times and yields an empty delta file, so the context block stays inert exactly as before.

## Concrete Steps

1. Add NEW-staged-file handling to the Phase C (step 8, `track-code-review.md`) and Phase B (step-level review, `step-implementation.md`) delta-staging setup — one commit covering all ten edits per D1/A4: (a) preamble names the NEW-file marker, (b) loop `else` branch emits `=== NEW staged file (no live counterpart): <staged> ===`, (c) post-loop narration drops the "has a live counterpart" restriction, (d) context block rewritten to per-marker routing (delta-scoped ⇒ scope to delta, NEW ⇒ review in full) with the file-level non-empty gate removed, (e) burden-measure prose qualified so a NEW file's whole-file count is not treated as review-free noise — risk: medium (workflow machinery: behavioral but bounded — changes agent-observable review scope across two phase docs; no auto-running script, no load-bearing gate/schema/enum edit) — size: ~2 files; the whole track is this one coherent fix and the eight scoping edits must ship together (A4), so no other low/medium work exists to merge — end of track  [x] commit: c310214714

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step.
Empty at Phase 1. -->

### Step 1 — commit c310214714, 2026-07-02T05:46Z [ctx=safe]

**What was done:** Applied all ten edits in one commit (D1/A4), five per file in `track-code-review.md` (Phase C step 8) and `step-implementation.md` (Phase B step-level review). Per file: the preamble names the NEW-file marker; an `else` branch on `if [ -f "$live" ]` emits `=== NEW staged file (no live counterpart): <staged> ===` (D2, staged path); the post-loop narration describes both the delta and NEW outcomes instead of restricting the trigger to adds with a live counterpart; the context block moves from a file-level non-empty routing gate to per-marker routing (delta-scoped ⇒ scope to the delta, NEW ⇒ review in full), with the non-empty gate removed so a NEW-only non-empty/zero-delta file is no longer routed into scoping-to-nothing; the burden-measure prose distinguishes a copy-of-live file (line count inflated, the delta is the truer measure) from a NEW file (whole-file content is the real review surface). The added else branch and the rewritten context block stay byte-identical across the two files except for the temp-file path (`track-{N}-delta` vs `step-{N}-{M}-delta`) and indentation (Inv 3).

**What was discovered:** The Inv 5 / D1 acceptance grep as written — `` `'delta: %s vs %s'` `` — is ambiguous. The source marker is `=== delta: %s vs %s ===`, so a literal-single-quote reading of the pattern matches nothing; only the shell-quoting reading (`grep 'delta: %s vs %s'`) or the bare substring `delta: %s vs %s` matches. Under the bare substring, the loop marker and the "Review-target delta for freshly-created staged copies" heading resolve to exactly the two named files — Inv 5 holds, no third stale copy.

**What changed from the plan:** None. Ten edits as specified; no scope change, no design decision, no risk upgrade.

**Key files:** `.claude/workflow/track-code-review.md`, `.claude/workflow/step-implementation.md` — both edited live under the §1.7(k) opt-out (D3).

### Track completion — 2026-07-02T06:27Z [ctx=safe]
Closed a review-scope gap where a genuinely-new staged `.claude/**` file (no `develop` counterpart) shipped unreviewed in Phase B/C: the delta-staging loop recorded a `diff <live> <staged>` entry only when a live counterpart existed, and the reviewer-facing context block routed on the delta file being non-empty, so a NEW-only file (non-empty delta, zero diff lines) was scoped to nothing. The fix (single commit `c310214714`) adds a NEW-file `else` branch emitting `=== NEW staged file (no live counterpart): <staged> ===`, rewrites the context block from a file-level non-empty gate to per-marker routing (delta-scoped ⇒ scope to the delta; NEW ⇒ review in full), and qualifies the burden-measure prose — across both mirror files (`track-code-review.md` Phase C step 8 and `step-implementation.md` Phase B step-level review). Track-level review passed at iteration 1 (4 workflow-review agents on the workflow-only diff); the one writing-style suggestion (WS1) was applied as `Review fix: fd6bb219ec`. No cross-track impact — single-track plan.

1 step, 0 failed.

## Validation and Acceptance

This is a prose and bash change with no unit test; acceptance is verified by inspection and by tracing the machinery it edits.

- **NEW-file marker emitted.** In each file, the step-8 / step-review loop appends `=== NEW staged file (no live counterpart): <staged> ===` for a staged add whose derived live counterpart does not exist. Verify by reading the else branch and, if desired, dry-running the loop against a staged tree that contains one new file and one edited copy of a live file: the delta file must show a `=== delta: … ===` entry for the edited copy and a `=== NEW staged file … ===` entry for the new file.
- **Context block distinguishes NEW from delta-scoped, with no file-level non-empty gate.** Each file's "Review-target delta for freshly-created staged copies" block states the delta-scoped case (scope to the delta, rest out of scope) and the NEW case (no live baseline, review in full) as a per-entry, mutually-exclusive distinction keyed on the marker. The file-level "when that file is non-empty, scope your findings to the delta" routing gate is gone — not merely softened — so a NEW-only (non-empty, zero-delta) file is not routed into scoping-to-nothing.
- **Preamble and post-loop agree with the loop.** No location still restricts the recorded set to adds "that has a live counterpart"; the preamble names the NEW-file marker; the post-loop narration describes both the delta and NEW outcomes.
- **Burden-measure line qualified.** In each file the burden-measure prose no longer implies a NEW staged file is line-count noise: it distinguishes a copy-of-live file (line count inflated, the step-8 `diff <live> <staged>` delta is the truer measure) from a NEW file (whole-file content is the real review surface, no such delta exists).
- **The two files stay consistent in the edited regions.** The added else branch and the rewritten context block diverge only in the temp-file path (`track-{N}-delta` vs `step-{N}-{M}-delta`) and indentation. The pre-existing git-diff-range divergence in the surrounding loop scaffolding is not part of this check.
- **Ordinary plans unaffected.** On a non-workflow-modifying plan (no `§1.7(b)` staging marker), the loop enumerates zero staged adds, produces an empty delta file, and the context block stays inert — identical to pre-fix behavior.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as
test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Single step, single commit. **Idempotence:** the ten edits are deterministic text replacements; re-running the step against an already-fixed file is a no-op — the acceptance greps (loop marker `'delta: %s vs %s'`, the "Review-target delta for freshly-created staged copies" heading, the `else` branch's NEW marker) detect the fix is already present. **Recovery:** the step touches only two tracked workflow files under `.claude/workflow/`; a failed or reverted step restores develop-state with `git checkout -- .claude/workflow/track-code-review.md .claude/workflow/step-implementation.md` (or the implementer's `git reset --hard HEAD` revert path). No generated artifacts, temp files, or state outside these two files.

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In scope:** `.claude/workflow/track-code-review.md` (Phase C Startup step 8, four scoping locations plus the step-9 burden-measure line ~340-342) and `.claude/workflow/step-implementation.md` (Phase B step-level review, four scoping locations plus the burden-measure line ~862-863). Both edited live under `.claude/workflow/`.

**Out of scope:** `conventions.md §1.7(k)`, which only references "the Phase C Startup staged-delta prep in track-code-review.md step 8" as a pointer and holds no copy of the loop or context block. The fix's wording should stay consistent with how `§1.7(k)` names step 8, but no edit lands there. Also out of scope: the Step-5b staged-path normalization in `code-review/SKILL.md` and `review-agent-selection.md`. It is a pure prefix-strip with no live-existence check (D1 *Dispatch already works*), so a NEW staged file already matches the workflow-review globs and reviewers launch — no edit is needed for a NEW file to reach a reviewer; the fix only changes what the reviewer treats as in-scope once launched.

**Inter-track dependencies:** none — this is a single-track plan.

**Staging:** under the `§1.7(k)` opt-out (D3) the edits are live; there is no `staged-workflow/` mirror for this branch and no phase-ledger `s17` workflow-modifying token.

## Invariants & Constraints
<!-- Plan-at-start, combined section (D9). Phase 1 writes the per-track
testable constraints and invariants; each is a property that must hold, backed
by a check. Author fills. -->

Each invariant is verifiable by review or inspection — a prose change has no unit test.

- **Inv 1 — NEW markers cover no-counterpart adds.** A staged add with no live counterpart appears in the delta file under a `=== NEW staged file (no live counterpart): … ===` marker, never silently absent. *Check:* the loop's else branch (Plan of Work (b)); a dry run against a staged tree with one new file.
- **Inv 2 — the context block separates the two cases and drops the file-level non-empty gate.** The context block presents delta-scoped (scope to the delta) and NEW (review in full) as a per-entry, mutually-exclusive distinction keyed on the marker. No residual file-level "when the delta file is non-empty ⇒ scope to the delta" routing gate survives, and no residual blanket "out of scope" sentence — either of which would route a NEW-only (non-empty, zero-delta) file into scoping-to-nothing. *Check:* read the rewritten block in each file; confirm routing is per-marker, not per-file-non-empty.
- **Inv 3 — cross-file consistency in the edited regions.** The added else branch and the rewritten context block diverge only in the temp-file path and indentation. The surrounding loop scaffolding carries a pre-existing git-diff-range divergence (`{commit}~1..{commit}` vs `{base_commit}..HEAD`) that is out of this check's scope. *Check:* diff the added else branch and the context block across the two files.
- **Inv 4 — ordinary plans unaffected.** On a plan with no staged adds the loop yields an empty delta file and the context block stays inert, matching pre-fix behavior. *Check:* trace the loop with an empty `--diff-filter=A` result set; confirm no path change outside the staged-prefix `§1.7(b)` branch.
- **Inv 5 — no third copy left stale.** No file other than the two named carries a copy of the loop or context block; `§1.7(k)` holds only a pointer. *Check:* grep `.claude/**` for the loop marker (`'delta: %s vs %s'`) and the "Review-target delta for freshly-created staged copies" context-block heading (D1 scope-closed). Grepping the bare delta temp-path is not the check — it also matches the two inert `rm -f` teardown lines and adds noise.
- **Inv 6 — burden-measure prose is NEW-aware.** In each file the burden-measure line distinguishes a copy-of-live staged file (line count inflated; the step-8 `diff <live> <staged>` delta is the truer measure) from a NEW staged file (whole-file content is the real review surface; no such delta exists), so it never labels a NEW file's line count as review-free noise. *Check:* read the burden-measure line in each file.
