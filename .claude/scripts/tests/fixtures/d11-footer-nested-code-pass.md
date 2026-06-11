# D11 Nested-Code Pass Fixture

## Overview

This fixture pins the top-level-only scoping of the
decision-cited-without-rationale check. The footer cites `D1 (see D2)`:
`D1` is the real citation and carries an introduced full record, while
`D2` is nested inside `D1`'s parenthetical, immediately before the
closing `)`. `D2` is deliberately introduced nowhere. Before the fix,
the scan emitted `D2` as its own citation and judged it bare (its next
character is `)`), firing a false-positive should-fix; the depth-aware
scan now skips it. The runner asserts zero
`decision-cited-without-rationale` findings, proving a nested code is
excluded from the citation scan rather than surfaced as a bare dangling
reference.

The baseline being replaced scanned every `D<n>` token in the joined
footer entry, so a nested code came out as its own citation. The change
tracks parenthetical depth and yields only depth-0 codes. The enabling
primitive is a per-offset depth map over the joined entry. Nothing else
is restructured. The rest of this document is one shape section.

## Decision detail

**TL;DR.** This section introduces the full record of `D1` so the
footer below can cite it, and never introduces `D2`, which appears only
inside `D1`'s parenthetical.

**D1. The citation scan is scoped to top-level codes.** Alternatives:
scan every token (rejected — it flags nested codes as bare citations).
Rationale: a code inside another code's parenthetical is rationale, not
a citation. Risk: none material.

### Decisions & invariants

- D-records: D1 (see D2)
- Invariants: S1 (nested codes are never counted as citations)
