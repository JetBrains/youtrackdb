<!--
MANIFEST
dimension: context-budget
iteration: 1
findings: 1
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: WB1, sev: Recommended, anchor: "#wb1-recommended-design-reviewmd-toc-summary-over-120-char-cap", loc: ".claude/workflow/prompts/design-review.md:187", cert: n/a, basis: "workflow-reindex.py --check rule_5c" }
-->

## Findings

### WB1 [Recommended] design-review.md TOC summary over 120-char cap

- File: `.claude/workflow/prompts/design-review.md` (line 187)
- Axis: load-on-demand discipline (§1.8 schema gate is a load-on-demand surface)
- Cost: net ~7 lines added to the `### Prose AI-tell additions` block; the annotation summary itself grew 119 → 150 chars
- Issue: `workflow-reindex.py --check rule_5c` — "summary field is 150 chars; limit is 120". The pre-diff summary sat at 119 chars (one under the cap). This diff appended `/ hard-to-read` to the axis phrase and `, Plain language` to the rule list, pushing the `<!-- … summary= -->` annotation on line 187 to 150 chars and over the §1.8 per-section cap. This diff introduced the violation; it is not pre-existing schema debt.
- Suggestion: tighten the line-187 summary back under 120 chars without dropping the new axis — e.g., drop the parallel rule list to the lens names only: `summary="Over-dense / too-terse / hard-to-read scan vs Banned analysis patterns, Orientation, Plain language; creation-time."` (118 chars), or abbreviate the axis triple. `--write` reflows the TOC row from the annotation but does not shorten an over-cap summary, so this is a manual annotation edit. Keep the matching `:23` TOC-region row in step (the summaries must stay byte-identical per the §1.8 TOC ↔ annotation consistency check).

## Evidence base

(Evidence-trail-exempt: no refutation or certificate phase runs in this dimension, so no `#### C<n>` entries are written.)
