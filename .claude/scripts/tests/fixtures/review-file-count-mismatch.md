<!-- MANIFEST
findings: 3   severity: {blocker: 1, should-fix: 2, suggestion: 0}
index:
  - {id: CQ1, sev: blocker,    loc: A.java:10, anchor: "### CQ1 ", cert: C1, basis: "..."}
  - {id: CQ2, sev: should-fix, loc: B.java:20, anchor: "### CQ2 ", cert: C2, basis: "..."}
  - {id: CQ3, sev: should-fix, loc: C.java:30, anchor: "### CQ3 ", cert: C3, basis: "..."}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

<!--
Fixture: a count-mismatch CONTRACT_VIOLATION. The manifest claims `findings: 3`
but only two `### CQ<N> ` finding anchors exist in the body. The canonical
count-validation regex (conventions-execution.md §2.5, S4/S6) is:

    grep -cE '^### [A-Z]+[0-9]+ '

Over this file it returns 2, which does NOT equal the manifest `findings: 3`,
so a reader raises CONTRACT_VIOLATION and falls back to a whole-section read.
The manifest's stale `CONTRACT_OK` flag is intentional — the count grep is the
backstop a reader runs before trusting the flag.
-->

## Findings
### CQ1 [blocker] first finding
Body.

### CQ2 [should-fix] second finding
Body. The third index entry (CQ3) was promised by the manifest but its anchor
was dropped from the body, producing the mismatch.

## Evidence base
