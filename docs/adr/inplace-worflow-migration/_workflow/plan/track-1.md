# Track 1: Stamp format and conventions

## Purpose / Big Picture
After this track lands, every reader and writer of `_workflow/**` artifacts resolves the line-1 workflow-SHA stamp format and the unstamped-artifact protocol from a single section of `conventions.md`.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Define the per-artifact `<!-- workflow-sha: ... -->` stamp format and the one-liner that computes the SHA at creation time. Document the format and the unstamped-artifact protocol (drift check short-circuits to "drift detected"; migration prompts once for a base SHA covering the unstamped set) in `conventions.md` so every reader (drift check, migration, future writers) resolves to one source of truth. Foundational — Tracks 2/3/4 depend on the spelling this track lands.

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

`.claude/workflow/conventions.md` is the shared rule file every other workflow doc cross-references. It already defines plan-file structure (§1.2), review iteration protocol (§1.3), tooling discipline (§1.4), and house-style mapping (§1.5). The stamp rules slot in as a new top-level section (§1.6 or equivalent) named *Workflow-SHA stamps on `_workflow/**` artifacts*, containing the format definition, the SHA computation rule at creation, the unstamped-artifact protocol (drift signal + migration prompt), and the explicit "no silent fork-point fallback" non-rule.

No other file is touched in this track. Tracks 2, 3, and 4 reference this new section from their own edits. The deliverable is a single new `##` section in `conventions.md` plus any cross-reference notes that the structural review will want (e.g., glossary entry, link from §1.2 plan-file structure to the new section).

## Plan of Work

Add a `## 1.6 Workflow-SHA stamps` section (or whichever number fits the file's existing numbering) to `conventions.md`. The section carries: (a) the format definition — `<!-- workflow-sha: <40-char SHA> -->` on line 1 of every stamped artifact, with the H1 on line 2; (b) the SHA computation rule at artifact-creation time — `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills`; (c) the stamp range definition — `BASE_SHA..HEAD`, where `BASE_SHA` is the oldest stamp reachable from HEAD (derived by folding the set of stamps pairwise through `git merge-base`), and HEAD is the upper bound because the branch is a self-contained capsule (workflow commits enter only via explicit rebase; see plan D10); (d) the unstamped-artifact protocol — the drift check signals drift unconditionally when any artifact lacks a stamp, and the migration prompts the user once for a base SHA covering the unstamped set (validated via `git rev-parse --verify` + `git merge-base --is-ancestor "$SHA" HEAD`); (e) an explicit non-rule stating that no auto-computed reference (fork-point, HEAD itself, or merge-base with develop) is a silent default for unstamped artifacts — rebase moves auto-computed references forward, which would mark unstamped artifacts as already-current and skip the migration (see plan D8); (f) the list of stamped artifact types — `implementation-plan.md`, `design.md`, `design-mechanics.md`, every `plan/track-N.md`. Phase 4 artifacts are explicitly NOT stamped. `design-mutations.md` is also explicitly NOT stamped: append-only log, stamp would always equal `design.md`'s, replay-immune by the log's append-only contract.

Then add a glossary entry to §1.1 *Glossary* for "Workflow-SHA stamp" pointing at the new section, and a cross-reference from §1.2 *Plan File Structure* near the artifact list noting that each ephemeral artifact carries a line-1 stamp (with a link to §1.6).

Order: glossary entry → §1.6 section → §1.2 cross-reference. Each one is a single `Edit` against `conventions.md`; no other file changes.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 1 lands, the following must hold:

- `grep -n "Workflow-SHA stamp" .claude/workflow/conventions.md` returns at least one match in §1.1 (glossary) and one match in the new §1.6 (or equivalent number).
- The new section defines the format (regex `<!-- workflow-sha: [0-9a-f]{40} -->`), the SHA computation one-liner, the stamp range definition (`BASE_SHA..HEAD`), the unstamped-artifact protocol (drift signal + migration prompt), the explicit "no auto-computed silent default" non-rule, and the list of stamped artifact types.
- §1.2 carries a cross-reference to the new section.
- No other workflow file is touched in this track (Tracks 2/3/4 own those edits).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/conventions.md` (one new section, two cross-references)

**Out-of-scope files:**
- All `.claude/skills/**` SKILL bodies (Tracks 2 and 4)
- `.claude/workflow/workflow-drift-check.md` (Track 3)
- `.claude/workflow/self-improvement-reflection.md` (Track 5)

**Inter-track dependencies:**
- This track has no upstream dependencies.
- Tracks 2, 3, 4, 5 all read from §1.6 of `conventions.md` and quote the stamp format / SHA computation rule / unstamped-artifact protocol verbatim. A change to the format spelling after Track 1 lands requires a coordinated edit across all downstream tracks.

**External interfaces:**
- `git` CLI — `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills` (SHA computation at creation), `git merge-base <a> <b>` (pairwise fold over stamps), `git log $BASE_SHA..HEAD -- workflow paths` (drift / migration range), `git merge-base --is-ancestor "$SHA" HEAD` (unstamped-artifact bootstrap validation), `git rev-parse HEAD` (migration's final-batch stamp value). All POSIX-portable and already used elsewhere in `conventions.md` examples. The `git fetch origin develop` and `git merge-base origin/develop HEAD` invocations referenced by older drafts are deliberately absent — the branch is a self-contained capsule per D10.
