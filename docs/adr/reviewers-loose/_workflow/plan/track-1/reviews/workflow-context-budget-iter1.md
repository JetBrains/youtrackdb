<!-- MANIFEST
dimension: workflow-context-budget
iter: 1
findings: 0
evidence_base: { certs: 0 }
cert_index: []
flags: []
index: []
-->

# Workflow context-budget review — Track 1, Step 1 (iter 1)

## Findings

None. No always-loaded surface, load-on-demand discipline, or instant
per-operation consumption impact in this diff.

- **Axis 1 (always-loaded):** The diff touches three load-on-demand
  `.claude/workflow/*.md` files only. No project `CLAUDE.md`, no skill/agent
  `description:` field, and no SessionStart hook stdout is changed. Net
  always-loaded delta: zero. The one `CLAUDE.md` pointer to a changed file
  (`track-code-review.md`, cited for its context-monitor threshold gate at
  `CLAUDE.md:255`) is not touched by this diff and remains intact.
- **Axis 2 (load-on-demand discipline):** Per-file net growth is
  code-review-protocol.md +11/-2, review-agent-selection.md +34/-6,
  track-code-review.md +10/-4 — the largest single-file net is ~28 lines,
  far under the >100-line structural-drift threshold. The added content is
  dispatch-routing rules native to the reviewer-selection doc, not
  recipes/examples that belong in `CLAUDE.md`. No structural drift; no broken
  pointer.
- **Axis 3 (instant per-operation consumption):** `review-agent-selection.md`
  is read by the orchestrator at each step/track dispatch. The net +28 lines
  (~a few hundred tokens) is below the <5K-token Minor floor. The added
  one-paragraph override plus three lead clauses introduce no new
  orchestrator-side full-file read, no new uncapped sub-agent dispatch, no
  inlined recipe that should be a pointer, no `/tmp`-staging gap, and no new
  long-running phase. The override is positioned read-first per the track's
  dispatch-reader-sees-exception-first requirement; it raises every dispatch
  read by a small constant only.

Deterministic gate: `workflow-reindex.py --check --files <the three changed
files>` exited 0 with no findings — the §1.8 schema (TOC, per-section
annotations, cross-file refs, in-file §X.Y(z) stamps, bootstrap blocks) is
clean on every changed file.

Note (not a finding): the added prose is somewhat verbose relative to the rule
it states — the override is re-cited across four narrowing paragraphs and the
rationale is restated in both skip-gate docs. The compactness/clarity tradeoff
is a writing-style and prompt-design concern, not a budget concern; the
absolute per-operation cost stays under the Minor floor, so no `WB` item is
filed.

## Evidence base
