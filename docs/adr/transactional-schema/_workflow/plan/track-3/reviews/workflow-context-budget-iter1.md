<!--
MANIFEST
dimension: workflow-context-budget
track: 3
iteration: 1
target_commit_range: 8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1..HEAD
finding_count: 0
high_water_mark_in: 0
high_water_mark_out: 0
evidence_base:
  certs: 0
cert_index: []
flags: []
index: []
-->

# Workflow Context-Budget Review — Track 3, iteration 1

## Findings

No always-loaded surface, load-on-demand discipline, or instant per-operation
consumption impact in this diff. The only workflow-machinery files in the
cumulative track diff are auto-generated execution bookkeeping: the track file
`track-3.md` and the dimensional-review manifest files under
`plan/track-3/reviews/`. All live under `docs/adr/transactional-schema/_workflow/`,
none are part of the always-loaded context surface (no project `CLAUDE.md`, no
skill/agent `description:` field, no SessionStart hook stdout), none are opened by
any §1.8 TOC/role/phase rule, and none introduce orchestrator-side reads,
sub-agent dispatches, inlined recipes, or multi-phase content reuse. They are
ephemeral artifacts swept at Phase 4.

The deterministic half ran clean: `workflow-reindex.py --check` returned exit 0
with zero findings. The nine in-scope paths fail the in-scope regex
`.claude/(workflow|skills|agents)/.*\.md$` (and the staged-workflow variant), so
the script silently skips them — there is no schema surface to validate on a Java
feature track's bookkeeping.

## Evidence base
