<!-- MANIFEST
dimension: workflow-consistency
iteration: 1
range: 98c5dd4719..HEAD
findings_total: 1
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - id: WC1
    sev: should-fix
    anchor: "WC1 [should-fix] structural-review.md overlap-split criterion mis-attributes the Interfaces-and-Dependencies read to the footprint check"
    loc: "docs/adr/token-economy-oriented-planning/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md:213-222"
    cert: n/a
    basis: judgment
-->

## Findings

### WC1 [should-fix] structural-review.md overlap-split criterion mis-attributes the Interfaces-and-Dependencies read to the footprint check

- **File:** `docs/adr/token-economy-oriented-planning/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md` (lines 213-222; the new criterion's clause at 214-215)
- **Axis:** cross-file rule restatement
- **Cost:** the criterion's stated rationale for why its read is "free" points at the wrong sibling check and contradicts its own trailing annotation; a reviewer following the inline clause looks for in-scope file lists in a pass that never read them
- **Issue:** The new criterion says "The `## Interfaces and Dependencies` file lists *you already read for the footprint check* reveal cross-track overlap." Neither footprint check in this same file reads those lists. The SCOPE INDICATORS footprint check (lines 150-162) is tagged `*(plan-file only … so this check reads no track file)*` and compares the `~N files` count on the plan-checklist `**Scope:**` line. The TRACK SIZING footprint check (lines 194-212) is likewise tagged `*(plan-file only — the Scope line lives in the plan checklist)*`. The referent ("the footprint check") therefore reads the aggregate `~N files` Scope figure, never the per-file `## Interfaces and Dependencies` lists. The reviewer *does* read those lists, but in the TRACK DESCRIPTIONS pass (line 175, tagged `… ## Interfaces and Dependencies sections for pending …`), for description-completeness — not "for the footprint check." The criterion's own trailing tag `*(cross-file: read each track's ## Interfaces and Dependencies in-scope list)*` (line 221-222) correctly frames the read as a per-track cross-file read, directly contradicting the inline "already read for the footprint check" clause two lines above. The body and its own annotation disagree.
- **Suggestion:** Re-attribute the read to the pass that actually performs it, or drop the "already read" framing. Either point at the TRACK DESCRIPTIONS pass (which reads each pending track's `## Interfaces and Dependencies`), e.g. "The `## Interfaces and Dependencies` lists you read for the TRACK DESCRIPTIONS checks reveal cross-track overlap"; or state the read plainly without the false reuse claim, e.g. "Read each pending track's `## Interfaces and Dependencies` in-scope list and compare them pairwise." Both align the body with the trailing `*(cross-file: …)*` annotation, which already states the correct read shape.

## Evidence base

<!-- This dimension is evidence-trail-exempt: (a) no refutation or certificate
phase to persist. No #### C<n> certificate entries are written. -->
