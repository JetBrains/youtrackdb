<!--MANIFEST
dimension: workflow-context-budget
prefix: WB
findings: 0
evidence_base: { certs: 0 }
cert_index: []
flags: []
index: []
-->
# Workflow context-budget review — Track 1, Step 1

## Findings

No always-loaded surface, load-on-demand discipline, or instant per-operation
consumption impact in this diff.

- **Axis 1 (always-loaded surface):** untouched. No `CLAUDE.md` edit, no skill
  or agent `description:` field edit. The changed `workflow-startup-precheck.sh`
  is a SessionStart-dispatched precheck whose stdout is JSON consumed by the
  dispatch, not injected as conversation `additionalContext`; the delta mutates
  only the pre-existing `DRIFT_DETECTED` / `DRIFT_KIND` scalars and prints no new
  field, so the conversation-facing output shape is byte-identical.
- **Axis 2 (load-on-demand discipline):** no structural drift. The script grew
  by ~23 lines (one bash block plus its explanatory comment) and the test file
  by two methods (~70 lines); neither is inline rules/recipes that belong in an
  always-loaded surface, and no `CLAUDE.md` pointer to these files broke.
- **Axis 3 (instant per-operation consumption):** negligible. The new
  `ledger_tail_value phase` call is a single pure-bash, last-value-wins line scan
  over the small append-only phase-ledger file, run once inside `detect_drift`
  (which already executes at turn 1 on `--mode full`). It spawns no `git`
  subprocess, pulls no large content into context, dispatches no sub-agent, and
  inlines no recipe. The per-startup cost delta is one file `stat` plus a
  few-line scan — it does not move any context threshold.

The workflow-reindex schema gate did not run: after filtering the changed-files
list against the in-scope regex
`\.claude/(workflow|skills|agents)/.*\.md$` (and its staged-workflow form), the
set is empty — all three changed files are `.claude/scripts/**` (`.sh` + `.py`),
which carry no §1.8 TOC annotation schema. The diff-filtered finding set is
therefore empty by construction.

## Evidence base
