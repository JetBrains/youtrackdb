<!--MANIFEST
agent: review-workflow-context-budget
target: track-3
iter: 1
findings: 0
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index: []
-->

## Findings

None. No always-loaded surface, load-on-demand discipline, or instant per-operation consumption impact in this diff.

- Always-loaded (Axis 1): every changed agent kept its `description:` frontmatter (line 3, closing `---` line 5) byte-identical; all 20 hunks land in the on-spawn body preamble (lines 9-10 / 22-23 / 28-35). No project `CLAUDE.md` and no SessionStart hook stdout changed. Untouched.
- Load-on-demand (Axis 2): largest single-file growth is `review-workflow-writing-style.md` at +5 lines (1 "Key rules" bullet + a 4-line `### Plain language` subsection), far under the >100-line structural-drift bar; no always-loaded pointer to a changed file broke. Not affected.
- Instant per-operation (Axis 3): no new orchestrator-side reads, sub-agent dispatches, inlined recipes/tables, or multi-phase re-reads. Per-spawn body delta is ~1 line per agent and ~5 lines for `review-workflow-writing-style.md` — negligible against multi-hundred-line bodies. The `### Plain language` subsection is judgment-shaped and proportionate, not padded.
- workflow-reindex.py `--check` (scope-narrowed `--files`, 20 agents): exit 0, zero findings. Diff-filtered finding set empty.

## Evidence base
