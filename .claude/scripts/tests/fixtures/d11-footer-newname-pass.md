# D11 New-Footer Pass Fixture

## Overview

This fixture exercises the D11 footer rename and the
decision-cited-without-rationale check. It uses the new
`### Decisions & invariants` footer spelling. The fixture is read by
`test_design_mechanical_checks_d11.py`; that runner asserts the file
emits zero `decision-cited-without-rationale` findings and zero
blockers, proving the renamed footer is recognized as a valid footer
and that both rationale-bearing citations and full-record-elsewhere
citations satisfy the introduce-once rule.

The baseline being replaced is a footer-presence check that only knew
the `### References` spelling. The change teaches the check both
spellings. The enabling primitive is one shared footer regex. Nothing
else is restructured. The rest of this document is one shape section.

## Decision detail

**TL;DR.** This section introduces the full record of `D1` so a later
footer can cite it bare. The mechanism is a bold-prefix decision-record
paragraph, the canonical introduce-once markup.

**D1. The shared footer regex is the single source of truth.**
Alternatives: four copied regexes, rejected because they drift.
Rationale: one constant keeps the two spellings in lockstep. Risk:
none material.

### Decisions & invariants

- D-records: D1, D2 (the new footer spelling is accepted alongside the
  legacy one)
- Invariants: S1 (footer detection is backward-compatible)
