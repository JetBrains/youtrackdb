<!--MANIFEST
dimension: workflow-instruction-completeness
prefix: WI
iteration: 1
evidence_base: { certs: 1 }
cert_index: [C1]
flags: []
index:
  - id: WI1
    sev: should-fix
    anchor: "#wi1-should-fix"
    loc: ".claude/workflow/prompts/design-review.md:412-417"
    cert: C1
    basis: judgment
-->

## Findings

### WI1 [should-fix] Output-format emit template not extended for Prose AI-tell findings (and silent for target=tracks)

- File: `.claude/workflow/prompts/design-review.md` (lines 412-417)
- Axis: phase output → next-phase input (block produces findings; the emit template that renders them was not updated)
- Cost: the reviewer is told to *produce* evidence-bearing Prose AI-tell findings but the literal output template gives no rule for *rendering* them — undefined dimension prefix and undefined placement, with the `target=tracks` case wholly unmentioned; findings emit inconsistently across runs
- Issue: the new `### Prose AI-tell additions` block (lines 186-215) instructs the reviewer to scan both axes and raise findings, and the § Tone and depth second exception (line 447) requires evidence-bearing multi-line bullets for them, exactly as for the Human-reader rules. But the `## Structural findings` emit template's only routing parenthetical (lines 412-417) still names **only** the Human-reader cold-read additions and **only** the three design kinds (`phase1-creation`, `phase4-creation`, `design-sync`). It says nothing about where Prose AI-tell findings go, what dimension-label prefix they carry, or how the `target=tracks` Step-4b invocation — which runs the Prose AI-tell block (line 238) but not the Human-reader block — renders them. A reviewer filling the template literally has no instruction for the new findings' shape; the only signal is the Tone exception, which fixes depth, not placement or labeling. This is the parallel of the sibling Human-reader block, whose findings *do* have an explicit routing parenthetical — the new block's was not added.
- Suggestion: extend the parenthetical at lines 412-417 to cover the Prose AI-tell additions: name the dimension labels the block uses (e.g. `over-dense`, `too-terse`, or the per-rule labels `banned-analysis` / `mechanism-trace` / `inflated-label` / `orientation`), state that they go in the same `## Structural findings` list with a dimension-label prefix and multi-line evidence bullets (mirroring the Human-reader sentence), and add a clause that for `target=tracks` the Prose AI-tell findings are rendered the same way even though the Human-reader additions do not run there.

## Evidence base

#### C1 [confirmed] No emit-format instruction exists for Prose AI-tell findings

- `grep -n 'Prose AI-tell\|prefix\|dimension label\|in the same list' design-review.md` returns the block definition (186, 238), the three design-kind activation pointers (127, 137, 151), the TOC row (23), and the § Tone and depth exception (447) — but the only emit-template routing parenthetical (414, "same list but prefix each with the dimension label") sits inside the Human-reader-only note at lines 412-417, which enumerates `phase1-creation`, `phase4-creation`, `design-sync` and the Human-reader additions exclusively.
- The `target=tracks` Step-4b path (line 238) invokes the Prose AI-tell block but explicitly excludes the Human-reader block (lines 241-242); the structural-findings note for tracks (line 354) covers only the absorption/fidelity criteria, so the track-side Prose AI-tell findings have no rendering instruction at all.
- The complement-handled checks all pass and are recorded here as confirmed for completeness: four activation pointers present with none orphaned (phase1 at 127, design-sync at 137, phase4 at 151, tracks at 238); applies-to line complete (the three `target=design` kinds + `target=tracks`, lines 189-190); both axes specified (over-dense 197-204, too-terse 205-208); the on-hit action is defined (cite which `house-style.md §`, lines 198-208 + 447); `design-document-rules.md:284` and the in-file docstring (1839-1854) both read "eleven" with the two new patterns named, and the prose count matches; every cited house-style section exists (`§ Orientation` 54, `§ Banned analysis patterns` 129, `§ Mechanism traces and inline citations` 384); the Overview-skip wiring is correct both ways (design.md call passes `sections` at 2362, mechanics call passes `sections=None` at 2371, matching the comment at 1893-1894); the on-branch `design.md` Overview heading is exactly `## Overview`, so the `== "overview"` match holds; the test suite and `workflow-reindex.py --check` both pass.
