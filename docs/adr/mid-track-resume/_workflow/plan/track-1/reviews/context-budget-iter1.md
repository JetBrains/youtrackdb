<!--MANIFEST
dimension: workflow-context-budget
target: track-1
range: b1621946c39bfb2b24d4d97bd29b60203ff65981..HEAD
findings_total: 0
evidence_base: { certs: 0 }
cert_index: []
flags: []
index: []
verdict: no-budget-impact
summary: >-
  No always-loaded surface, load-on-demand discipline, or instant per-operation
  consumption impact. The precheck is orchestrator-invoked, not a SessionStart
  hook, so its stdout is not per-turn additionalContext; the new reader is an
  in-script whole-file scan returning a single shell var; the state JSON shape is
  unchanged; the two convention-doc edits are sub-10-line glossary touches with no
  structural drift; workflow-reindex.py --check exits 0 on the staged docs.
-->

## Findings

None. No `WB` findings — all three context-budget axes are clean and the
workflow-reindex schema check ran clean on the changed staged documents.

Axis-by-axis basis:

- **Always-loaded surface (clean).** No `CLAUDE.md` change; no skill or agent
  `description:` field change. `workflow-startup-precheck.sh` is invoked on demand
  by the orchestrator (e.g. `/execute-tracks`), not wired as a SessionStart hook
  (`.claude/settings.json` § SessionStart runs only `mcp-steroid-probe.sh`), so its
  stdout never becomes per-turn additionalContext. The script's `state` output keeps
  its existing `{phase, substate}` JSON shape — the new ledger-first arm emits the
  same JSON the fallback arm emits — so the per-resume orchestrator-context surface
  is byte-shape-unchanged. The two staged convention docs are read on demand under
  the §1.8 TOC protocol, not always-loaded.

- **Load-on-demand discipline (clean).** The `conventions.md` and
  `conventions-execution.md` edits are sub-10-line glossary/row touches (per the
  delta file), well under the 100-line structural-drift threshold. No inline
  recipe/table/example migrated into an always-read region; the Phase-ledger row
  stays in its existing `§1.1` slot and the `§2.1` note stays in its section. No
  CLAUDE.md pointer to either file is broken.

- **Instant per-operation consumption (clean).** The new
  `ledger_tail_value_for_track` reader and the ledger-first read in
  `determine_state_from_ledger` are pure in-script work: a single extra whole-file
  scan over the small append-only phase-ledger (`ledger_path()`), using only `local`
  vars and parameter expansion, returning one shell variable (`LEDGER_VALUE`).
  Nothing is `Read`/`Bash`-ed into the orchestrator's context — the orchestrator sees
  only the unchanged tiny `state` JSON once per resume. The ~400-line test growth
  lives in the test module, which the orchestrator never reads on the resume path
  (tests surface as pass/fail counts). Per-operation orchestrator-context delta: zero.

## Evidence base
