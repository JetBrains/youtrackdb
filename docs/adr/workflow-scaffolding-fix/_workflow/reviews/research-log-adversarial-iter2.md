# Adversarial review — research log (iteration 2, verdict-producer)

Scope: research-log (`_workflow/research-log.md`), Phase 0→1 gate, role `reviewer-adversarial`. Lens-free (`matched_categories = (none)`). This is a gate: 0 blockers is the clear condition; a should-fix still gates.

**Verdict:** CLEAR — 0 blocker, 0 should-fix, 0 suggestion. All three iter1 findings VERIFIED; no new finding raised.

## Evidence base

#### Verification A1 — Open-Question/Surprise contradiction resolution
- **Prior finding:** the log carried an Open Question ("does develop already carry a partial fix?") that contradicted the `## Surprises` "bug is live" assertion, with no resolution in `## Decision Log`.
- **Check:** `## Decision Log` now opens with a dated entry `2026-07-01 — Bug is live on develop; no partial fix; proceed [ctx=safe] (resolves the open question)` (lines 24-35), carrying `**Decision:**` (proceed), `**Why:**` (verified live against `create-final-design.md:609` / `workflow.md:764` bare `git rm -r`, plus the `:617` / `:769` "sweeps automatically" prose, plus an empirical git repro), and `**Alternatives rejected:**` (assume develop carries a partial fix — refuted by direct read). The `## Open Questions` entry (lines 150-153) is marked `— RESOLVED` and cross-links back to that Decision entry. The `## Surprises` "bug confirmed live" block (line 108) no longer has a competing unresolved question.
- **Code grounding:** `grep -rn "git rm" create-final-design.md workflow.md` confirms the two bare-`git rm -r` operative sites (`create-final-design.md:609`, `workflow.md:764`) and the "sweeps automatically" prose (`create-final-design.md:617`, `workflow.md:769`) the entry cites. Line numbers accurate.
- **Verdict:** VERIFIED — contradiction removed; the resolution exists in the Decision Log and the question is marked resolved.

#### Verification A2 — §1.7-stage self-application invariant stated
- **Prior finding:** the §1.7-stage Decision Log rationale under-stated the invariant its "self-application risk is minor" claim rests on.
- **Check:** the `§1.7 mode = stage` entry (lines 57-74) now carries a dedicated bullet `Self-application gap is absent *for this branch specifically* (the invariant the "minor" rests on):` stating that a minimal (`design_gate=no`) branch has **no plan file** (so no `[>]`→`[x]` marker flip) and runs **no** `edit-design phase4-creation` (so no `design-mutations.md` append and no per-round params files), leaving nothing under `_workflow/` for the live buggy `git rm -r` to choke on. It further notes the general staged-mode gap still exists for `design_gate=yes` staged branches — flagged as a pre-existing property, not a regression this fix introduces.
- **Load-bearing check on the disclosed residual gap:** the disclosed `design_gate=yes` staged-mode residual is not a new gate-worthy open question. The fix's payload (`git rm -r` → `git rm -rf` + follow-up `rm -rf`) repairs the cleanup command for every branch once promoted; the self-application gap is only about *this* branch running its own not-yet-promoted command, and the bullet correctly frames it as out of this fix's scope. No unresolved load-bearing decision hides under it.
- **Verdict:** VERIFIED — the invariant is now stated explicitly, with the general gap correctly scoped as pre-existing.

#### Verification A3 — sixth `git rm` fixture match noted as verified-negative
- **Prior finding:** the "full site enumeration" surprise omitted a sixth `git rm` match in a test fixture.
- **Check:** the `Bug confirmed live; full site enumeration` surprise (lines 108-136) now carries a `**Verified-negative (out of scope):**` block (lines 123-130) naming `.claude/scripts/tests/fixtures/review-file-valid-strategic.md:33` as illustrative finding-body text inside a count-validation-regex fixture — not a live cleanup instruction or a descriptive claim about the real command — deliberately out of scope, with a note that a later Phase-C `git rm` grep should not re-flag it.
- **Code grounding:** `sed -n '30,35p'` of the fixture confirms line 33 reads `The blanket recursive `git rm -r _workflow/` already sweeps review files.` inside a `### T2 [should-fix]` finding body — fixture text, exactly as the note claims.
- **Verdict:** VERIFIED — the verified-negative line exists and is factually correct.

## Findings

(none — all three prior findings VERIFIED; no new finding raised)
