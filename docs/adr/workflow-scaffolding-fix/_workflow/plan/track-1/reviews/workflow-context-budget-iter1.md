<!--MANIFEST
dimension: workflow-context-budget
iter: 1
findings: 0
evidence_base: { certs: 0 }
cert_index: []
index: []
flags: { evidence_trail_exempt: true, exempt_reason: "no refutation or certificate phase to persist" }
-->

# Workflow context-budget review — Track 1, iteration 1

## Findings

None. The ~15-line delta touches no context-budget axis.

- **Always-loaded surface (Axis 1):** unaffected. All five changed files are
  `.claude/workflow/**` load-on-demand rules/prompts, staged under
  `_workflow/staged-workflow/`. None is project `CLAUDE.md`, a skill/agent
  `description:` field, or SessionStart hook stdout. The staged copies live under
  `_workflow/` (which Phase 4 deletes) and reach the live tree only at the Phase-4
  promote step, so they never join the per-turn baseline. Zero always-loaded delta.
- **Load-on-demand discipline (Axis 2):** no structural drift. The real change is
  ~15 delta lines (a rationale comment on two command blocks plus reconciled prose
  in four descriptive mentions), not a >100-line migration of recipe/table content
  out of `CLAUDE.md`. No `CLAUDE.md` pointer is broken: the four references at
  `CLAUDE.md:154/254/258/259` anchor on stable section names (`§ Final Artifacts`,
  `§ Context Consumption Check`, `§ When this protocol fires`, the Phase-4 inline
  gate) that the edit leaves in place. `CLAUDE.md` inlines no `git rm` command, so
  there is no already-loaded duplication.
- **Instant per-operation consumption (Axis 3):** below the Minor floor. The
  largest single-site add — the `create-final-design.md` § Step 6 command block —
  is 3 comment lines + 1 command line + ~3 reconciled prose lines, ~6-7 lines total,
  far under the ~500-line (5K-token) threshold. The edit is a pure content
  substitution inside command blocks and prose the Phase-4 orchestrator already
  loads. No new orchestrator-side read, no new sub-agent dispatch, no inlined 50+
  line recipe, no new multi-phase re-read.

Deterministic half: `workflow-reindex.py --check` (scope-narrowed `--files` form,
5 changed staged files) exited 0 with no findings — the §1.8 schema gate is clean
for every changed file.

## Evidence base
