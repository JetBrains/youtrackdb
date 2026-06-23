# Design mutation log — mid-track-resume

## Mutation 1 — 2026-06-23 — phase1-creation (design.md)

**Diff summary**: Authored the initial `design.md` seed for the ledger-sourced
State-C sub-state change (re-route the resume sub-state off the `## Concrete
Steps` roster parse onto a track-scoped `substate` phase-ledger key, with a
wrap-fixed `roster_scan` fallback). Single file (no mechanics companion —
637 lines, under the length trigger). Structure: Overview, Core Concepts,
ledger grammar + script function structure, the resume-state-machine substate
lifecycle (Mermaid stateDiagram), the dual-path resolution (Mermaid flowchart),
the wrapped-roster fallback fix with a worked bug trace, test surface, Decision
records D1–D3, Invariants S1–S6.

**Mechanical checks** (target=design): PASS — 0 findings (round 3, after an
overview-length should-fix introduced and cleared in round 2→3).
**Cold-read** (scope: whole-doc): comprehension gate PASS. Inner dual-clean
loop ran 3 rounds (budget): round 1 author seed + 4-spawn review
(absorption clean, 8 readability findings); round 2 fixed all 8 (introduced
overview-length); round 3 trimmed the Overview + fixed F1/F2/F3.

**Findings** (all non-blocking; residual carried as known debt to Phase 4
design-final authoring):
- should-fix: `### Decisions & invariants` footers diverge from the canonical
  `### References` footer shape (comprehension gate; deterministic mechanical
  checker passed it).
- should-fix: "the placeholder" (line ~113) used with a definite article and no
  antecedent (supporting; main claim survives).
- should-fix: `section-discrepancy` used in a TL;DR (~line 303) before its gloss
  ~50 lines below (supporting; glossed in-document).

**Iterations**: 3 of 3 (PASS — comprehension gate passed; inner loop exited on
budget with only minor should-fix readability nits, no blockers).
