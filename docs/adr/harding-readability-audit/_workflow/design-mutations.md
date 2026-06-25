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

## Mutation 2 — 2026-06-25 — structural-rewrite (design.md)

- **Diff summary:** Drop the calibrated-hold mechanism and its D15-veto backstop
  (research-log D8, supersedes D4's hold rules). A section is now **settled**
  only when it returned clean; the never-clean tail is bounded by
  `iteration_budget` + the existing S5 user-is-the-gate path. Edits: Overview and
  Core Concepts roadmap (seven → six load-bearing ideas; removed the "Calibrated
  hold" Core Concept), Class Design diagram + prose (dropped `calibratedHolds` /
  `heldFindings` and the held-quote clause), the "Section-keyed settled-state"
  settled definition / Changed bullet / unified-notion paragraph / edge case, two
  Part-1 held-set parentheticals, and replaced the "Calibrated holds and the
  convergence backstop" section with "Convergence backstop: the iteration budget
  and S5" (the budget+S5 termination argument, with calibrated holds recorded as
  a rejected alternative). User-driven simplification — the user judged holds
  fragile and the settled-state filter + budget + S5 sufficient for convergence.
- **Mechanical checks:** PASS (0 findings, whole-doc scope).
- **Comprehension gate (cold, interactive kind — no S3 gate, no readability
  auditor):** PASS — a fresh reader can build a working mental model; 0 blockers.
  One should-fix (References footer shape: `### Decisions & invariants` vs the
  house-style `### References`) is **pre-existing and uniform** across all 13
  sections, not introduced by this mutation; switching one section would diverge
  from the other twelve and a doc-wide footer change is out of scope here.
- **Iteration count:** 1 (apply → mechanical → cold-read, clean for the change).
- **Outcome:** frozen.
