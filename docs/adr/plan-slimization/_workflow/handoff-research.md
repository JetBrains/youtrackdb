<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Handoff — Phase 0 complete (Complexity-Adaptive Workflow Tiering)

Phase 0 (research) is COMPLETE and the dogfooded research-log adversarial gate
PASSED (4 iterations). On resume, do NOT re-ask the aim or restart research.

## Resume steps
1. Read `docs/adr/plan-slimization/_workflow/research-log.md` end-to-end. It is
   the authoritative aim (`## Initial request`) plus the vetted decision ledger:
   `## Decision Log`, `## Surprises & Discoveries`, `## Open Questions`, and the
   iteration-by-iteration adversarial-gate record (06:30Z → 08:10Z).
2. This is a **`full`-tier, workflow-modifying** change, so **`§1.7` staging
   applies** (live `.claude/**` stays at develop; staged edits accumulate under
   `_workflow/staged-workflow/` until the Phase 4 promotion). It unifies
   **YTDB-965** (research log) + the new **complexity-tiering** feature
   (unticketed) + **YTDB-814** + **YTDB-815** + **YTDB-1083** + a revived
   **YTDB-832** + plan-as-aggregator. This branch **supersedes
   `ytdb-965-dd-decision-log`**.
3. Next step: **Step 4a** — author `design.md` from the frozen ledger via
   `edit-design` (`phase1-creation`). Operate under the **current live
   workflow** (its `phase1-creation` runs adversarial-on-`design.md` + cold-read
   today). The research-log adversarial gate we ran was a **manual dogfood of
   the new design** that validated the ledger; it is not yet a live mechanism,
   so do not expect the live workflow to run it. Author `design.md` from the
   vetted ledger.

## Do not
- Re-ask the aim or restart Phase 0 research (the ledger is complete and vetted).
- Write `implementation-plan.md` or `plan/track-N.md` until `design.md` is
  authored, cold-read-reviewed, and frozen (Step 4a → session boundary → Step 4b).

## Headline decisions to carry into the design (full detail in research-log.md)
- Tier = two orthogonal gates: Gate 1 (design? `full`=yes, `lite`/`minimal`=no);
  Gate 2 (scope? multi-track aggregator vs single-track stub). Agent-proposed at
  the Phase 0→1 boundary, user-confirmed. Names: `full` / `lite` / `minimal`.
- Route (a): a shape-complete stub `implementation-plan.md` exists in every tier
  (state machine untouched). Plan is always an aggregator.
- Research log = the single Phase-0/1 decision ledger; consumed (not referenced)
  by design/tracks; removed at Phase 4 cleanup. Includes the 5th
  `## Baseline and re-validation` section.
- Adversarial review relocates to the research log (reuse `adversarial-review.md`
  + a 3rd "research-log" scope), domain-primed off the confirmed tier. `design.md`
  keeps cold-read only.
- Self-containment is tier-relative: `full` → `design.md` canonical (1083
  inline records, introduce-once), track DR = gist + `**Full design**` pointer;
  `lite`/`minimal` → tracks canonical, full inline DRs (DL-3 full duplication).
- Cold-read at write-time: Step 4a on `design.md`, Step 4b on plan-at-start
  sections (new Step-4b spawn reusing the cold-read sub-agent); absorption-
  completeness is a cold-read criterion at BOTH 4a and 4b.
- Tier-gated review opt-outs: `minimal` drops Phase 2 structural + Phase 3A
  adversarial; `tier` drives Phase-3A review selection (replaces step-count).
