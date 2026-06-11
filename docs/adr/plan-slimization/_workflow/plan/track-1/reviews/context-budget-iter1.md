<!--
MANIFEST
dimension: workflow-context-budget
target: track-1 (full track diff, 2775833bc33bab3d8acc1f3dd34a219e8ebe5ea7..HEAD)
iteration: 1
findings: 0
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, reason: "(a) no refutation or certificate phase to persist" }
index: []
verdict: PASS
reindex_check: { mode: "--check --files", scope_files: 11, exit_code: 0, diff_filtered_findings: 0 }
-->

## Findings

None. No always-loaded surface, load-on-demand discipline, or instant per-operation
consumption impact in the track-1 delta.

- **Axis 1 — always-loaded surface.** The only prose every role reads under the
  §1.8 TOC `any:any` filter is the five new §1.1 glossary terms (change tier, tier
  gates, research log, aggregator plan, track-canonical live decision), ~2.6K chars
  / ~640 tokens. `conventions.md` is TOC-role/phase-filtered, not unconditionally
  always-loaded like project `CLAUDE.md`; the glossary is the canonical home for
  closed-term vocabulary, and these terms buy the documented anti-collision guard
  (tier vs per-step risk tag vs Phase-3A step-count axis). No `CLAUDE.md`, skill
  `description:`, agent `description:`, or SessionStart hook stdout change in the diff.
  Below the Recommended threshold; justified. Not flagged.
- **Axis 2 — load-on-demand discipline.** Every bulky new mechanic lands behind a
  narrow role/phase TOC annotation: the D15 batching block + handoff queue
  (`planner`/`orchestrator,planner`, phase 1), the third adversarial scope
  (`reviewer-adversarial`/1), the write-time track cold-read (`reviewer-design`/1),
  the §1.2 *Per-tier artifact set* matrix (`planner,orchestrator`/1,2), `planning.md`
  §Tier classification (`planner`/1), and the §2.5 third-scope review-file home
  (`planner,reviewer-adversarial`/1). No new `any:any` section or TOC row was added.
  No load-on-demand file leaked inline rules into always-read territory; no broken
  always-read pointer. No structural drift.
- **Axis 3 — instant per-operation consumption.** The relocated gate's D17 design
  caps the orchestrator's per-iteration context: file-output mode, thin-manifest
  return, §2.5 count-grep validation, then `## Findings` partial-fetch ("do not pull
  the whole file into context"). The gate spawn and the Step-4b cold-read spawn pass
  the research log / plan / track / design by **path**, not inlined content, so a
  multi-iteration loop does not re-inline the log on each spawn. The durable verdict
  carrier is the committed log gate records, not free-form reviewer prose returned to
  the orchestrator. This is a per-operation budget improvement over the prior
  inline-adversarial-in-`edit-design` pattern, not a hit.

Deterministic check: `workflow-reindex.py --check --files` over the 11 changed
staged workflow/skills files exited 0 (clean) — §1.8 schema intact (TOC presence
and consistency, per-section annotation well-formedness, cross-file ref subset
validation, in-file §X.Y(z) suffixes). No diff-filtered findings.

## Evidence base

(none — this dimension is evidence-trail-exempt: no refutation or certificate phase to persist)
