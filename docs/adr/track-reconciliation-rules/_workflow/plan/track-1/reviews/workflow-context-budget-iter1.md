<!--MANIFEST
dimension: workflow-context-budget
iteration: 1
findings_count: 1
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WB1
    sev: Recommended
    anchor: "### WB1 [Recommended]"
    loc: "docs/adr/track-reconciliation-rules/_workflow/staged-workflow/.claude/workflow/review-agent-selection.md:228"
    cert: n/a
    basis: "workflow-reindex.py --check (rule_5c) + manual char count"
-->

## Findings

### WB1 [Recommended]

- **File**: `docs/adr/track-reconciliation-rules/_workflow/staged-workflow/.claude/workflow/review-agent-selection.md` (line 228)
- **Axis**: load-on-demand discipline
- **Cost**: TOC annotation summary 116 → 132 chars (12 over the §1.8 120-char limit)
- **Issue**: `workflow-reindex.py --check` reports `rule_5c: summary field is 132 chars; limit is 120`. Edit 4 appended "and termination" to the `§Complexity sets the Phase-C rigor dial, never the set` TOC-annotation summary (`<!-- roles=orchestrator phases=3A,3B,3C summary="Domain selects the Phase-C set at every level; complexity moves only iteration depth and termination; the floor is never suppressed." -->`), pushing it past the §1.8 schema cap. The matching in-section sentence at line 11 (the visible TOC row) carries the same expanded summary and is within budget; only the HTML-comment annotation exceeds it. This is the deterministic half of the review and is a schema-gate finding the pre-commit toc-check / Phase-C reindex gate will also catch.
- **Suggestion**: Trim the annotation summary to ≤120 chars without dropping the new "termination" sense — e.g. `Domain selects the Phase-C set at every level; complexity moves only iteration depth and termination, never the set.` (114 chars). Keep the line-11 TOC-row summary in sync with whatever wording lands. Re-run `python3 .claude/scripts/workflow-reindex.py --check --files <the six staged paths>` (or `--write` to auto-stamp) to confirm clean.

## Evidence base
