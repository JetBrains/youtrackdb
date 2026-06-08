<!-- MANIFEST
findings: 2   severity: {blocker: 1, should-fix: 1, suggestion: 0}
index:
  - {id: BC1, sev: blocker,    loc: Foo.java:142, anchor: "### BC1 ", cert: C3, basis: "TOCTOU on shared cache map; concrete interleaving traced"}
  - {id: BC2, sev: should-fix, loc: Bar.java:88,  anchor: "### BC2 ", cert: C7, basis: "missing null check on nullable return"}
evidence_base: {section: "## Evidence base", certs: 2, matches: 1}
cert_index:
  - {id: C3, verdict: WRONG, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

<!--
Fixture: a valid two-finding dimensional review file (two-letter prefix `BC`).
The canonical count-validation regex (conventions-execution.md §2.5, S4/S6) is:

    grep -cE '^### [A-Z]+[0-9]+ '

Over this file it returns 2 — the two `### BC<N> ` finding anchors — which
equals the manifest `findings: 2`, so validation passes. The `#### C<N> `
evidence entries are four-hash and are excluded by the trailing space after
`[0-9]+` in the anchor pattern. The intra-body `#### detail` heading below is
likewise four-hash and excluded.
-->

## Findings
### BC1 [blocker] TOCTOU on the shared cache map
A concrete interleaving is traced where two threads read-modify-write the
shared cache map without a guarding lock.

#### detail
The window is between the `containsKey` check and the `put`.

### BC2 [should-fix] missing null check on nullable return
The return value of `lookup()` is nullable but dereferenced unconditionally.

## Evidence base
#### C1 ... MATCHES
#### C3 ... WRONG
