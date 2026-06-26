# design-author params — phase1-creation, round 1

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/track-complexity-assessment-workflow-optimization/docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-complexity-assessment-workflow-optimization/docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
- round: 1

## Design type and grounding (scope, not decisions — the decisions live in the research log)

This is a **workflow-machinery** design for YTDB-1162, not a Java-code design.
Ground on the research log (D1–D10, `## Surprises & Discoveries`, the initial
request) and on the live `.claude/**` workflow machinery — the `.md` files under
`.claude/workflow/`, the agent files under `.claude/agents/`, the skills under
`.claude/skills/`, and `.claude/scripts/workflow-startup-precheck.sh` plus its
tests. The research log's `## Surprises & Discoveries` names the concrete files
in the blast radius (~30 files); read those to ground the mechanism, not Java
symbols. `steroid_list_projects` / PSI is Java-oriented and will not help here —
use `Read`/`Grep` over the named `.claude/**` files and note no PSI caveat is
needed for markdown/bash grounding.

**Diagrams.** Adapt the design-document-rules structure to a workflow design:
- The "Class Design" analog is the **data model** — render the phase-ledger
  schema delta (D10: drop `tier=`; add `design_gate`, plan/track-count,
  Phase-1-complete marker, per-track reconciled-tag home) and the reviewer
  roster split/merge (D7) as Mermaid diagrams or tables, not Java `classDiagram`.
- The "Workflow" section carries Mermaid `flowchart`/`sequenceDiagram` for: the
  three-axis decision model replacing the tier (design gate / track-count →
  plan / per-track complexity tag); the Phase-A reconciliation-on-upward-
  divergence flow (D5); the `domain × complexity` reviewer-selection logic for
  Phase A and Phase C (D6); and the artifact-derivation from the three axes (D8).

## Two open items are Phase-B rendering details — specify the contract, do NOT hard-decide them

The research log flags two items the design must NOT resolve into a fixed
rendering (they are implementer choices in Phase B, constrained by the contract
the design states):

1. **A10 — Step 1c resume-routing branch structure.** Specify the routing
   *contract*: which ledger fields disambiguate which resume cases (the
   `design_gate` value, the Phase-1-complete marker that resolves the A2
   collision between the design+single-track steady state and a full mid-
   authoring crash, and the plan-presence/track-count signal — all per D10).
   Do NOT commit the design to "collapse the two single-track branches into one"
   vs "keep them separate" — note both renderings satisfy the contract and the
   choice is made at implementation.

2. **Split-agent finding prefixes** (`review-bugs`, `review-concurrency`). Note
   that each split agent gets a distinct finding prefix decided when the agent
   files are authored (Phase B), and that `review-test-quality` keeps `TB`+`TC`
   verbatim. Do NOT invent the two new prefixes in the design.
