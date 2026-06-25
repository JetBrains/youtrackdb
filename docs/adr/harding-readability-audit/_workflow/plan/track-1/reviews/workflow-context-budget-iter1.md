<!-- MANIFEST
findings: 1   severity: {Recommended: 1}
index:
  - {id: WB1, sev: Recommended, loc: "docs/adr/harding-readability-audit/_workflow/staged-workflow/.claude/workflow/conventions-execution.md:708", anchor: "### WB1 ", cert: n/a, basis: "§1.8 annotation summary grew from 113 to 212 chars, over the 120-char cap; rule_5c"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### WB1 [Recommended] §1.8 annotation summary over the 120-char cap on a diff-touched row

- **File:** `docs/adr/harding-readability-audit/_workflow/staged-workflow/.claude/workflow/conventions-execution.md` (line 708)
- **Axis:** load-on-demand discipline
- **Cost:** annotation `summary=` field grew from 113 chars (live) to 212 chars (staged), 92 chars over the cap. Load-on-demand surface — the cost lands on a reader whose role/phase matches this row, not on every turn.
- **Issue:** `workflow-reindex.py --check` rule_5c — the `### Third-scope review-file home` annotation summary is 212 chars against a 120-char limit. The Step-1 rewrite widened the section's scope (Phase-0→1 gate plus Phase-1 authoring plus Phase-4 design-final) and packed all three families into one summary sentence. This is a diff-introduced finding: the live row's summary was 113 chars (under the cap); the edit on delta line 230 pushed it over.
- **Suggestion:** trim the summary to ≤120 chars — drop the parenthetical family enumeration, which the section body already states. For example: `summary="Plan-scoped home for authoring-loop review scaffolding before any track directory exists, and its commit/sweep lifecycle."` (~123 chars — trim one clause further). Re-run `python3 .claude/scripts/workflow-reindex.py --write` over the staged file to confirm clean, or shorten by hand and re-run `--check`.

## Evidence base
