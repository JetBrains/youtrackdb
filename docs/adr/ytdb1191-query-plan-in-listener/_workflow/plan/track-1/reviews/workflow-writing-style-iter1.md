<!-- MANIFEST
findings: 1   severity: {Critical: 0, Recommended: 0, Minor: 1}
index:
  - {id: WS1, sev: Minor, loc: track-1.md:59, anchor: "### WS1 ", cert: C1, basis: "trailing 'X, not unconditional' negation restates the positive clause it follows"}
evidence_base: {section: "## Evidence base", certs: 1, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [Minor] D5 caveat ends on a redundant "…, not unconditional" negation

- **File:** `docs/adr/ytdb1191-query-plan-in-listener/_workflow/plan/track-1.md` (line 59)
- **Axis:** banned sentence patterns
- **Cost:** trailing contrastive negation that restates the positive clause it follows
- **Issue:** The D5 Risks/Caveats sentence closes with `So the replay-null contract is "when the tx result cache is enabled," not unconditional.` The `X, not Y` tail is the roundabout-negation/negative-parallelism shape from `house-style.md § Banned sentence patterns`: `"when the tx result cache is enabled"` already states the condition, so `not unconditional` adds no information and only performs the contrast. State what is true and stop.
- **Suggestion:** Replace the final sentence with `So the replay-null contract holds only when the tx result cache is enabled.`

## Evidence base

#### C1 D5 trailing negation — CONFIRMED

Banned-sentence-patterns sweep: the clause `is "when the tx result cache is enabled," not unconditional` matches the roundabout-negation form (state-what-is-true, then append a negated restatement) in `house-style.md § Banned sentence patterns`. The condition phrase already carries the full meaning; `not unconditional` is a synonym-negation of it, so the tail is redundant rather than additive. Minor: single slip in a long decision-log field, load-bearing meaning intact.
