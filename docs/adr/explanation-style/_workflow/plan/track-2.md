<!-- workflow-sha: 26f990ed824d113fdb5fcb930361e69378f0f12a -->
# Track 2: Over-dense prose enforcement

## Purpose / Big Picture
After this track lands, the over-dense AI-tells YTDB-1084 names — run-on mechanism traces, lists spliced into one sentence, inflated-abstraction labels — are caught: the regex-expressible cases by two new `dsc-ai-tell` patterns, the judgment cases by a new cold-read block that runs for both design and track prose.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Adds the YTDB-1084 over-dense enforcement that does not enumerate the subset: a judgment-layer `### Prose AI-tell additions` block in the `design-review.md` cold-read running for both `target=design` and `target=tracks`, plus two `dsc-ai-tell` regexes (inflated-abstraction labels and the "X, not Y" faux-symmetry) with tests. Ships demotable, calibrated against this branch's own authoring.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- The track-canonical live decision carrier (D7). Full inline Decision
Records this track owns. The regex severity follows D5's A9 clause, owned
by Track 1; cross-referenced below, not duplicated as authority. -->

#### D4: New cold-read block runs for design AND tracks
- **Alternatives considered**: design-only (the `### Prose AI-tell additions` block runs only for the `design.md` cold-read); both `target=design` and `target=tracks` (chosen).
- **Rationale**: track prose carries the same over-dense / too-terse failure as design prose at **creation time**, so scanning only `design.md` leaves the plan-at-start track sections unchecked. The block sits sibling to `### Human-reader cold-read additions` (`design-review.md:165`) and instructs the reviewer to scan the changed sections against `§ Banned analysis patterns`, `§ Mechanism traces and inline citations`, lists-disguised-as-sentences, and inflated-abstraction labels — the judgment cases regex cannot catch — plus the too-terse direction (the `## Orientation` rule). One block covers both axes.
- **Risks/Caveats**: the claim is bounded to **creation-time** prose. The `target=tracks` cold-read runs once, at Step 4b before the plan commit, so it does **not** cover the YTDB-1106 exemplar surface (live `## Decision Log` entries, episodes, review findings) that accrues during Phase 3 — nothing re-runs a cold-read there. The Phase-3 surface is held by the always-on subset wiring (D1/D2) on the writers, not by this block. The sibling `### Human-reader cold-read additions` applies only to the three design-target mutation kinds (phase1-creation / phase4-creation / design-sync), so this block **cannot copy its applies-to line** — it needs its own statement covering `target=design` (those three kinds) **and** `target=tracks`.
- **Implemented in**: this track (`design-review.md`); step references added during execution
- **Full design**: design.md §"Over-dense prose enforcement" (the applies-to asymmetry, the three sync sites)

#### D4b: Two dsc-ai-tell regexes (severity per D5/A9)
- **Alternatives considered**: add the over-dense rules to the cold-read only (judgment-only, no regex); add regexes for the cleanly-detectable cases (chosen).
- **Rationale**: `dsc-ai-tell` (`design-mechanical-checks.py`) gains the inflated-abstraction labels ("the enabling primitive", "the key abstraction", "the underlying mechanism", and the participle-plus-category-noun shape) and the "X, not Y" faux-symmetry variant of negative parallelism. These two regexes are YTDB-1084's deliverable; **D5's A9 clause governs only their severity** — both ship at the rule's documented demotable severity (this is a cross-reference to Track 1's D5, not a re-decision).
- **Risks/Caveats**: the "X, not Y" pattern risks firing on legitimate contrastive "A, not B" prose; the inflated-abstraction-label regex collides with the design-doc Overview template, which prescribes naming "the enabling primitive(s)" (`design-document-rules.md § Overview`). The regex must target the **subject-slot** inflated label, not the Overview's sanctioned enumeration element, or every conforming design Overview self-flags. The false-positive count observed on this branch's own Phase-4 `design-final.md` authoring (where the live regex self-applies through `edit-design`) is the calibration point — a blocker-severity false positive there would exit 1 and block the loop.
- **Implemented in**: this track (`design-mechanical-checks.py` + `test_dsc_ai_tell.py`); step references added during execution
- **Full design**: design.md §"Over-dense prose enforcement" (the Overview-template collision, the demotable calibration)

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation

This track adds the two YTDB-1084 combining moves that do **not** enumerate the AI-tell subset, so they sit outside Track 1's atomic flip. State of the surfaces (verified on the branch tip `26f990ed82`):

- **`design-review.md`** — the cold-read prompt with two targets (`target=design` / `target=tracks`). `### Human-reader cold-read additions` at line 165; the new block sits sibling to it. The TOC row for it goes near line 22. `## Tone and depth` at line 403 carries the "five Human-reader rules require evidence" count at line 406, a sync site for the new block.
- **`design-mechanical-checks.py`** — holds the `dsc-ai-tell` rule (the narrow regex set). Not a subset-enumeration site. The two new regexes extend it.
- **`test_dsc_ai_tell.py`** — exercises `dsc-ai-tell`. Not a subset-enumeration site; it gains cases for the two new regexes (positive matches + the false-positive guards: contrastive "A, not B" prose, the Overview "the enabling primitive(s)" enumeration).
- **`readability-feedback/SKILL.md`** — its `## Rule sync map` (line 41) is the codified add-a-rule procedure; the new cold-read block needs a row in its design-review entry (which today lists only the Human-reader bullets). The same file's governance grep (line 54) is flipped by Track 1; see the overlap note below.

Non-obvious terminology: **dsc-ai-tell** is the mechanical AI-tell check inside `design-mechanical-checks.py`, run by `edit-design` on every `design.md` mutation; **demotable severity** means a finding the rule may down-grade to `suggestion` rather than fail the mutation, the documented fallback when a pattern risks false positives.

## Plan of Work

1. **The cold-read block (D4).** Add `### Prose AI-tell additions` to `design-review.md` sibling to `### Human-reader cold-read additions`, with its **own** applies-to line covering `target=design` (phase1-creation / phase4-creation / design-sync) and `target=tracks`. The block scans both axes — over-dense (the four judgment cases) and too-terse (the `## Orientation` rule). Sync the three sites: the new TOC row (near line 22), the `§ Tone and depth` count (line 406), and the `readability-feedback` Rule sync map's design-review row.
2. **The two regexes (D4b, severity per D5/A9).** Add the inflated-abstraction-label pattern (targeting the subject slot, not the Overview enumeration element) and the "X, not Y" faux-symmetry pattern to `dsc-ai-tell` in `design-mechanical-checks.py`. Ship both demotable.
3. **Tests.** Extend `test_dsc_ai_tell.py` with positive matches and the false-positive guards (contrastive "A, not B"; the sanctioned "the enabling primitive(s)" Overview enumeration).

Ordering: this whole track lands after Track 1 (its cold-read scans the `## Orientation` rule, which Track 1 adds; its live edits rely on Track 1's `§1.7` opt-out). Internal order is free — the block, regexes, and tests are independent within the track.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

Track-level behavioral acceptance:

- `design-review.md` carries a `### Prose AI-tell additions` block with its own applies-to line naming `target=design` (the three design kinds) and `target=tracks`; the TOC row, the `§ Tone and depth` count, and the `readability-feedback` Rule sync map design-review row are synced to it.
- `dsc-ai-tell` flags an inflated-abstraction label in a subject slot and an "X, not Y" faux-symmetry sentence, both at demotable severity; it does **not** flag a contrastive "A, not B" sentence or the design-doc Overview's "the enabling primitive(s)" enumeration.
- `test_dsc_ai_tell.py` passes with the new positive and false-positive cases.
- Running `edit-design` on this branch's own `design-final.md` in Phase 4 produces no blocker-severity false positive from the two new regexes (the demotable calibration holds).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim
as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In-scope files** (~4):
- `.claude/workflow/prompts/design-review.md` — the `### Prose AI-tell additions` cold-read block + TOC row + `§ Tone and depth` count sync.
- `.claude/scripts/design-mechanical-checks.py` — the two `dsc-ai-tell` regexes.
- `.claude/scripts/tests/test_dsc_ai_tell.py` — tests for the two regexes (positive + false-positive guards).
- `.claude/skills/readability-feedback/SKILL.md` — the Rule sync map design-review row (the file's governance grep is Track 1's).

**Out-of-scope**: the AI-tell subset enumeration (Track 1's atomic flip); `house-style.md` rule text (the over-dense rules the block scans against — `§ Banned analysis patterns`, `§ Mechanism traces and inline citations` — already exist; this track adds enforcement, not new rule text); every `_workflow/**` artifact schema.

**Inter-track dependency**: depends on Track 1 (the `## Orientation` rule must exist for the block to scan the too-terse direction; the `§1.7` opt-out sanctions these live edits).

**Overlap note (planning.md §"Justify any overlap-split").** `readability-feedback/SKILL.md` is shared with Track 1 (Track 1 flips its governance grep; this track adds the Rule sync map design-review row). The two edits are in different sections of the file. The Rule-sync-map row documents this track's cold-read block, so it cannot move into Track 1 without forward-referencing a block that does not yet exist. Track 2 is ordered adjacent to Track 1 so rebase distance on the shared file is minimal.

**Sizing justification (argumentation gate, under floor).** This track is ~4 in-scope files, under the ~12 merge-candidate floor. It is **not** folded into Track 1 because Track 1 is already over the ~20-25 ceiling (the atomic subset flip forces it), so folding would worsen the breach with no reviewability gain. This track is the one independently-mergeable seam split off from over-ceiling Track 1 (planning.md §"Then clamp with a two-sided bound" sanctions the split), and its review surface — regex false-positive calibration and both-axes cold-read coverage — is orthogonal to Track 1's atomic-flip review focus.
