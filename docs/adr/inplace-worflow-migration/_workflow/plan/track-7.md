# Track 7: Staging architecture for workflow-modifying branches

## Purpose / Big Picture
After this track lands, workflow-modifying branches accumulate their workflow document changes under `<plan-dir>/_workflow/staged-workflow/.claude/{workflow,skills}/...` during execution, so the branch's own sessions read a stable live workflow at develop's state until Phase 4 promotes the staged subtree in one commit.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The in-place migration (Tracks 1–6) handles drift on feature plans whose execution does not edit `.claude/workflow/**` or `.claude/skills/**`. Track 7 codifies the inverse case: a plan whose execution is itself the source of workflow drift. Writing directly to the live paths during execution forces the branch to migrate against its own work — plan citations go stale immediately, reviewers flag anchors that disappear at the next commit, and the drift gate trips on the branch's own changes. Staging isolates workflow document edits in a per-branch subtree under `_workflow/`; the live paths in the branch's checkout stay at develop's state; a "Promote workflow changes" commit at Phase 4 copies the staged subtree into the live paths immediately before the final-artifacts commit. The existing cleanup commit then removes `_workflow/` (the staged subtree included), so the squash-merge into `develop` carries only the live workflow files plus `design-final.md` and `adr.md`. Forward-applicable only: the current branch finishes under the existing in-place model; Track 7 codifies the new convention for the next workflow-modifying branch.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation

The workflow already separates ephemeral planning artifacts from live workflow rules: planning happens under `docs/adr/<dir-name>/_workflow/` (`implementation-plan.md`, `design.md`, `plan/track-N.md`, `design-mutations.md`), which the Phase 4 cleanup commit removes before merge; live workflow rules under `.claude/workflow/` and `.claude/skills/` are read by every session and survive merge into `develop`. The drift gate (Track 3) and the migration (Tracks 4a/4b) handle the case where live workflow rules move on `develop` while the branch carries pre-rebase artifacts. They do not handle the case where the branch is itself authoring the workflow change.

`.claude/workflow/implementer-rules.md` is the rulebook the Phase B per-step implementer sub-agent reads on every invocation. It owns the contract for what the implementer is and is not allowed to touch and how to spell file paths in writes. Today it has no awareness of staging; an implementer told to edit `.claude/workflow/X.md` writes there directly.

`.claude/workflow/step-implementation.md` is the Phase B orchestrator. It assembles the implementer prompt by stitching `implementer-rules.md`, the plan-slim render, and the step description together. It owns the call site that would forward a staging directive to the implementer.

`.claude/workflow/workflow.md` § Final Artifacts is the canonical specification for the two-commit Phase 4 shape (final-artifacts + cleanup). `.claude/workflow/prompts/create-final-design.md` is the prompt that drives Phase 4 execution and references the same two-commit shape. Both files need to learn the three-commit shape (promote-staged-workflow + final-artifacts + cleanup) that fires only on workflow-modifying plans.

`.claude/workflow/workflow-drift-check.md` carries the `git log` pathspec the drift gate uses for its Phase 2 walk (`.claude/workflow .claude/skills`). The staged subtree lives under `docs/adr/*/_workflow/staged-workflow/.claude/...`, a different prefix, so the pathspec naturally excludes the staged paths. A defensive comment at the pathspec site protects this exclusion against a future change that broadens the pathspec.

`.claude/workflow/conventions.md` is the shared-rules home. The staging convention (the staged-subtree path, the workflow-modifying-branch detection rule, the "writes to live workflow route to staged" rule, the I6 invariant) lands here so every consumer (planner, implementer, Phase 4 producer) resolves to one source of truth.

The bootstrap problem in concrete terms: a branch that edits `.claude/workflow/X.md` to add a new section directly in its own tree changes the rules its own subsequent sessions read. Every plan citation that anchored to the old section structure of X.md goes stale at the commit point — Phase A reviewers see references that no longer resolve, Phase B implementers read a moving target, Phase C cumulative diffs include the workflow churn alongside the substantive change, and the drift gate at the next session-start sees the branch's own commits as drift. Staging isolates the edits from the live workflow until Phase 4; the live workflow stays at develop's state; reviewers and implementers see one consistent rule surface throughout execution.

## Plan of Work

Introduce the staging convention in `.claude/workflow/conventions.md` as a new subsection alongside §1.2 (Plan File Structure) and §1.6 (Workflow-SHA stamps). The new subsection defines the staged-subtree path layout (`<plan-dir>/_workflow/staged-workflow/.claude/workflow/...` and `.../staged-workflow/.claude/skills/...`), the detection rule for workflow-modifying plans (a property declared in the plan's Constraints, mirrored by an opt-in glob check against the same prefixes), the "writes to `.claude/workflow/**` or `.claude/skills/**` route to staged" rule, the "reads still hit the live paths" rule, the "copy-then-edit on first touch" rule for files that already exist on the live side, and the I6 invariant restating the live-path-stability claim. The §1.6 walk explicitly does not enumerate the staged subtree — it stays under the same name in `_workflow/` but is functionally a separate artifact class (mirrors live shape, not the `implementation-plan.md` / `design.md` / `track-N.md` template).

Add the implementer path-mapping rule to `.claude/workflow/implementer-rules.md` and reference it from `.claude/workflow/step-implementation.md`. The rulebook gains a paragraph naming the workflow-modifying-branch detection (read the plan's Constraints; if the workflow-modifying marker is present, route every write whose target path begins with `.claude/workflow/` or `.claude/skills/` to `<plan-dir>/_workflow/staged-workflow/<same-relative-path>`). The "copy-then-edit" sub-rule fires the first time a step writes to an existing live file: the implementer copies the live file into the staged subtree under the matching relative path, then applies the edit there. Subsequent steps touching the same file edit the staged copy directly. Reads remain unchanged; the implementer still reads the live workflow for plan-slim rendering, rule-reference lookups, and review consumption — only writes are routed.

Wire the Phase 4 promotion step into `.claude/workflow/workflow.md` § Final Artifacts and into `.claude/workflow/prompts/create-final-design.md`. The promotion lands as a new commit before the final-artifacts commit, with a guard that fires only when `<plan-dir>/_workflow/staged-workflow/` exists. The bash that performs the copy walks the staged subtree, copies each staged file to the corresponding live path, stages the live paths, and commits with a message naming the source plan dir. On workflow-modifying plans Phase 4 lands three commits (promote-staged-workflow + final-artifacts + cleanup); on every other plan, the guard skips the promotion silently and Phase 4 keeps its two-commit shape.

Add the defensive comment to `.claude/workflow/workflow-drift-check.md` at the pathspec site. The comment records that `.claude/workflow .claude/skills` deliberately excludes the staged subtree, and that broadening the pathspec to match anything under `docs/adr/*/_workflow/staged-workflow/` would surface the staged commits as drift on the branch authoring them. The migration skill's range computation uses the same pathspec; the defensive comment applies symmetrically there but lives in the drift-check file as the single source.

Include a worked example of the staging-tree shape in `conventions.md` so a reader navigating to the new staging subsection sees a concrete on-disk picture before the rule statement. The example mirrors the design.md illustration: a single staged file under `staged-workflow/.claude/workflow/X.md`, the live `X.md` left untouched on disk, and the Phase 4 promotion commit landing the staged copy onto the live path.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 7 lands:

- `.claude/workflow/conventions.md` carries a new subsection that defines the staging convention: the staged-subtree path layout, the workflow-modifying-branch detection rule, the "writes route to staged, reads hit live" rule, the "copy-then-edit on first touch" rule, and the I6 invariant.
- `.claude/workflow/implementer-rules.md` carries the path-mapping rule that routes implementer writes to the staged subtree when the active plan is workflow-modifying, and `.claude/workflow/step-implementation.md` references it from the prompt-assembly site.
- `.claude/workflow/workflow.md` § Final Artifacts and `.claude/workflow/prompts/create-final-design.md` document the Phase 4 promotion commit as a guarded prefix to the final-artifacts commit, firing only when `<plan-dir>/_workflow/staged-workflow/` exists.
- `.claude/workflow/workflow-drift-check.md` carries a defensive comment at the pathspec site recording the staged-subtree exclusion and the symmetry with the migration's range computation.
- The new conventions subsection includes a worked example showing the on-disk shape of `<plan-dir>/_workflow/staged-workflow/` for a single staged file.
- The invariant I6 stated in `implementation-plan.md` is recorded once in `conventions.md` (the single source) and the plan-file restatement remains the planning surface.
- On a plan whose Constraints do not declare workflow-modifying status, Phase 4 still lands exactly two commits (final-artifacts + cleanup) — the promotion guard skips silently.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/conventions.md` (new subsection for the staging convention plus the worked example; I6 cross-reference)
- `.claude/workflow/implementer-rules.md` (path-mapping rule for workflow-modifying plans)
- `.claude/workflow/step-implementation.md` (reference to the path-mapping rule from the prompt-assembly site)
- `.claude/workflow/workflow.md` § Final Artifacts (Phase 4 promotion-commit specification with the staged-subtree guard)
- `.claude/workflow/prompts/create-final-design.md` (Phase 4 prompt update to drive the promotion-commit step)
- `.claude/workflow/workflow-drift-check.md` (defensive comment at the pathspec site recording the staged-subtree exclusion)

**Out-of-scope files:**
- `.claude/skills/create-plan/SKILL.md` (Track 6 owns; the workflow-modifying-plan constraint can land here as a follow-up but is not blocking)
- `.claude/skills/edit-design/SKILL.md` (no staging interaction; design-doc edits stay under `_workflow/`)
- `.claude/skills/migrate-workflow/SKILL.md` (Tracks 4a / 4b; uses the same pathspec the drift check now defensively comments, no skill-side change needed)
- `_workflow/implementation-plan.md` for this branch (the active plan's existing artifacts are not staged; only the new convention applies forward)
- Phase 4 final artifacts (`design-final.md`, `adr.md`) — not stamped, not staged, content rules unchanged

**Inter-track dependencies:**
- **Depends on:** none. The track is forward-applicable: this branch (in-place migration) finishes under the existing model and does not need to retroactively apply the new convention. Tracks 4a / 4b / 5 / 6 land on their existing sequencing; Track 7 lands wherever it fits the active session calendar.
- The new convention is consumed by the next workflow-modifying branch that opens a plan after Track 7 merges. The first such branch exercises the implementer path-mapping rule, the Phase 4 promotion-commit guard, and the drift-gate exclusion end-to-end.

**External interfaces:**
- No new external commands. The promotion bash uses `cp -r`, `git add`, `git commit` (and `git push` per the workflow's per-commit push rule); the staged subtree is plain filesystem state under the existing `_workflow/` tree.
- `git log` pathspec in the drift gate (`.claude/workflow .claude/skills`) carries the defensive comment but stays byte-identical; no behavioral change there beyond the comment.

## Base commit
<!-- Phase B writes the SHA of HEAD here at session start. -->
