# Design mutations log — YTDB-1162

## Mutation 1 — 2026-06-26 — phase1-creation (design.md)

**Diff summary**: Initial authoring of `design.md` for YTDB-1162 (replace the
whole-change `tier` enum with three unbundled axes — change-level design gate,
track-count-driven plan presence, and a per-track complexity tag). The
workflow-machinery design carries an Overview, Core Concepts (six glossed
terms), a Data model section (ledger schema delta + reviewer-roster split/merge),
and six Parts: the three axes (D1/D8/D9), Phase-A reconciliation (D5), reviewer
selection across both phases (D6) plus step-level routing (D3/D4), artifact
derivation (D8), resume routing (D10), and the bugs/concurrency ownership
boundary (D7). D1–D10 all seeded as D-records; A10 (Step-1c branch structure)
and the split-agent finding prefixes left as Phase-B rendering details
constrained by the stated contract. Authored via the dual-clean multi-agent loop
(design-author + readability-auditor fan-out + absorption-check), then the cold
comprehension gate.

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: whole-doc): comprehension gate PASS — a cold reader can
build a working mental model; readability auditor hit the designed never-clean
should-fix tail (no blockers).

**Findings**:
- Absorption (per-round): round-1 flagged D2 missing-from-draft and an
  A10-cited-without-log-basis; both resolved in round 2 (D2 seed added; A10/A2
  reframed as D10-grounded contract prose, not decision records). Rounds 2–3
  absorption clean.
- Readability (per-round, all should-fix, never-clean tail): round 1 — 12;
  round 2 — 7; round 3 — 8. Cheap unambiguous fixes applied each round; the
  budget-3 inner loop exited the should-fix tail per S5 with a terminal cleanup
  pass (spell out `∃`, de-coin "verbs-on-change", plain "subtract cautiously",
  literal "sacred"/"primes lenses", cite the catch-rate source, un-fold the
  Phase-A selection diagram node).
- Comprehension gate: PASS with one should-fix and one suggestion. The
  should-fix (footer heading `### Decisions & invariants` vs `### References`)
  is a **false positive** — the heading is the design-document-rules.md D11
  rename, which the mechanical checker accepts; not applied. The suggestion
  (Overview roadmap listed 5 of 6 Part labels) was applied.

**Iterations**: 3 of 3 inner-loop rounds (never-clean readability tail) + 1
terminal cleanup pass + comprehension gate. Outcome: PASS (comprehension gate
passed; readability residual accepted per S5; no blockers, no unresolved
should-fix beyond the documented false positive).
