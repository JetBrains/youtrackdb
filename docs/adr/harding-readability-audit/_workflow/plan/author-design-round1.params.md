# design-author params — phase1-creation, round 1

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/research-log.md
- round: 1

## Authoring brief (read research_log_path for the full decision content)

Author `design.md` for **YTDB-1158** — hardening the in-loop `readability-auditor`
machinery. This is a workflow-prose change, so the "Class Design" diagram models
the **workflow components** (the dual-clean loop, the orchestrator, the cold
auditor, the orchestrator-side settled-state), not Java classes. Ground in the
live `.claude/**` workflow files named in the research log's Baseline section
(`edit-design/SKILL.md` Step 4/6, `readability-auditor.md`, `create-plan/SKILL.md`
Step 4b item 9, `readability-feedback/SKILL.md` Procedure step 2,
`conventions-execution.md` §2.5/§2.1). No Java symbols — PSI does not apply;
read the workflow files with `Read`/`Grep`.

The design must let a fresh reader rebuild three mechanisms and their context:

- **Concern A — deterministic design-path slicing (D1/D2).** The prose partition
  rule (~200-line windows on `##`/`# Part` boundaries, cap ~6, one auditor per
  window), the whole-doc floor invariant, the orchestrator's deterministic
  expected-slice-count obligation + self-check (the verifiable-count route), and
  the agent-side guard made computable by the `slice_count`/`total_lines` params
  (fires only when `slice_count == 1 AND total_lines > ~300`). Warm-up (gate A7)
  is severed from slicing.
- **Concern B — orchestrator-side section-keyed settled-state (D3/D4/D5).** The
  convergence mechanism: settled-state keyed per section (design path) / per
  track file (track path) on a content hash that folds in the standing anchors
  that exist; a clean-or-held unchanged unit has its re-flags dropped (and may
  skip re-spawning); changed units re-audit fresh; the auditor stays fully cold;
  calibrated holds are deliberate, recorded with a reason, backstopped only by
  the D15 user veto for prose holds. Covers both the design path
  (`edit-design` Step 6, canonical) and the track path (`create-plan` Step 4b,
  cross-references) with the two path-specific parameters.
- **Concern C — file relocation (D6).** All Phase-1 authoring-loop per-spawn
  params + review outputs move from `_workflow/plan/` to `_workflow/reviews/`;
  the design-path resume glob follows; `conventions-execution.md` §2.5
  generalizes its third-scope home.

Also capture the **meta context (D7)**: tier `full`, full §1.7 staging (the
edits are executable orchestration procedure, so the prose-rule opt-out does not
qualify), and the consequence that this branch runs the live (unmodified) loop
during its own authoring.

Cover the cross-cutting edge cases the research log surfaced: the track-path
loop has no mid-loop resume glob today (C does not add one); the anchor-hash
must tolerate an absent `## Core Concepts`; a legitimate single-slice short doc
is not a collapse.

Write the H1 as `# Harden readability-auditor slicing and convergence — Design`
on line 1 (the orchestrator prepends the workflow-sha stamp above it after you
return). Follow `design-document-rules.md` for structure and the house style.
Return ONLY a thin summary (sections written + any open question) — never the
drafted document.