<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: T1, sev: should-fix, loc: example-plan.md:204, anchor: "### T1 ", cert: C1, basis: "verdict-producer manifest variant unspecified"}
  - {id: T2, sev: should-fix, loc: example-plan.md:96,  anchor: "### T2 ", cert: C2, basis: "cleanup sweep needs confirmation"}
  - {id: T3, sev: suggestion, loc: example-plan.md:48,  anchor: "### T3 ", cert: C3, basis: "section-lifecycle row vs new-prose framing"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: MATCHES, anchor: "#### C1 "}
flags: [CONTRACT_OK]
-->

<!--
Fixture: a valid three-finding strategic review file using single-letter
prefixes (`T` = technical). This is the case that motivated correcting the
count-validation regex from `[A-Z]{2,}` to `[A-Z]+`
(conventions-execution.md §2.5, S4/S6). The canonical regex is:

    grep -cE '^### [A-Z]+[0-9]+ '

Over this file it returns 3 — the three `### T<N> ` finding anchors — which
equals the manifest `findings: 3`. The rejected `[A-Z]{2,}` form requires two
uppercase letters and would return 0 here, raising a spurious
CONTRACT_VIOLATION on every single-letter strategic file.
-->

## Findings
### T1 [should-fix] verdict-producer manifest variant unspecified
A gate-verification emits per-prior-finding verdicts, not a severity-graded
finding set; the variant must be specified.

### T2 [should-fix] Phase 4 cleanup sweep needs confirmation
The blanket recursive `git rm -r _workflow/` already sweeps review files.

### T3 [suggestion] section-lifecycle row vs new-prose framing
The review-file lifecycle is new prose under §2.1, not a matrix row.

## Evidence base
#### C1 ... MATCHES
#### C2 ... MATCHES
#### C3 ... MATCHES
