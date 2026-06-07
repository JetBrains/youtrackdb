<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 2, suggestion: 0}
index:
  - {id: CQ1, sev: should-fix, loc: A.java:10, anchor: "### CQ1 ", cert: C1, basis: "..."}
  - {id: CQ2, sev: should-fix, loc: B.java:20, anchor: "### CQ2 ", cert: C2, basis: "..."}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

<!--
Fixture: a stray-`### CASE1 ` CONTRACT_VIOLATION. The manifest claims
`findings: 2` and two real `### CQ<N> ` finding anchors exist, but a finding
body wrongly contains a `### CASE1 ` three-hash heading. The file-wide
reservation in conventions-execution.md §2.5 forbids any `### <CAPS><digit>`
heading outside a finding anchor — sub-structure must use `####` or bold. The
canonical count-validation regex (S4/S6) is:

    grep -cE '^### [A-Z]+[0-9]+ '

`### CASE1 ` matches that pattern (`CASE` is `[A-Z]+`, `1` is `[0-9]+`), so the
grep returns 3, which does NOT equal the manifest `findings: 2`. The reader
raises CONTRACT_VIOLATION. This is exactly why the `### <PREFIX><N> ` shape is
reserved file-wide rather than only under `## Findings`: a stray uppercase
heading anywhere inflates the count and is caught.
-->

## Findings
### CQ1 [should-fix] first finding
Body text.

### CQ2 [should-fix] second finding
Body text that wrongly introduces a numbered uppercase sub-heading:

### CASE1 illustrative case
This three-hash uppercase heading is the contract violation — it should have
been a `#### case` or bold label instead.

## Evidence base
