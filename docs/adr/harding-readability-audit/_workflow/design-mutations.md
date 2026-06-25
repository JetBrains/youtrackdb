# Design mutation log — harding-readability-audit

<!-- Append-only. One block per design.md mutation: kind, diff summary,
mechanical result, cold-read verdict, iteration count. Unstamped (§1.6(f)). -->

## Mutation 1 — 2026-06-25 — phase1-creation (design.md)

- **Diff summary:** Initial `design.md` creation for YTDB-1158 — harden the
  in-loop `readability-auditor` (Concern A deterministic slicing, Concern B
  orchestrator-side section-keyed settled-state, Concern C file relocation,
  meta D7). 442 lines, `full`-tier shape (Overview, Core Concepts, Class Design
  + Workflow Mermaid diagrams, Parts 1–3).
- **Mechanical checks:** PASS (0 findings) at freeze. One `overview-length`
  should-fix surfaced after the cleanup pass and was resolved by condensing the
  Overview.
- **Absorption check:** clean every round (0 findings, 7/7 coverage both ways —
  every load-bearing decision D1–D7 seeded, no invented decision).
- **Readability inner loop:** ran the 3-round `iteration_budget` on the live
  (unfixed) loop and did **not** converge — finding counts 11 → 10 → 12,
  non-monotone (a first-hand reproduction of the Concern B oscillation, recorded
  in the research log). At budget exhaustion the orchestrator took the S5
  user-is-the-gate path: applied the cheap unambiguous prose fixes in a final
  targeted pass (round 4 cleanup) and accepted the residual deep-workflow-term
  density as documented calibrated holds.
- **Comprehension gate (outer, de-warmed, cold):** PASS — a fresh reader can
  build a working mental model; 0 blockers, 0 should-fix, 2 borderline
  suggestions (verbless roadmap, implicit audience-naming), the roadmap one
  applied during the Overview condense.
- **Iteration count:** 4 author rounds (3 budgeted + 1 orchestrator-directed
  cleanup at budget exhaustion).
- **Outcome:** frozen.
