# Handoff: Phase 0 (research) — YTDB-382 transactional schema

**Paused:** 2026-06-03
**Phase:** 0 (research, `/create-plan`)
**Context level at pause:** info (~33%) — user-requested pause, not context-triggered
**Branch:** transactional-schema
**HEAD:** 26ba6a35f7 "[no-test-number-check] Fix two platform-flaky non-vacuousness test guards (#1118)" (pre-handoff; the handoff commit lands on top)
**Unpushed:** <no upstream — first push sets it; see workflow.md §What to do before ending a session>

## What I was investigating

Making schema changes transactional (YTDB-382). The design is the metadata-first
inversion: a tx mutates only metadata, and storage reconciles physical structure
(collections, indexes) at commit. The architecture spine is fully settled; the
session paused on the user's request to resume in a fresh session, just before
either the remaining optional probes or plan creation.

## Already ruled out

- Deletion pool / page-reuse for structural rollback — unnecessary. File
  create/delete is already WAL-revertible inside an atomic operation (commit
  buffers intent, `commitChanges` applies it, rollback skips it). decision-log F16, D10.
- In-memory overlay (approach B) for the **class** side — the inheritance
  derived-state ripple makes it error-prone; a full working copy was chosen. D8.
- Deep-copy of the IndexManager — indexes are storage-backed thin handles with no
  self-contained in-memory content; a definition overlay was chosen instead. D15, F25.
- Keeping the single schema record — rejected in favour of per-class records to
  kill write amplification. D14.

## Most promising lead

Not applicable — the design is settled. The authoritative research record is
`docs/adr/transactional-schema/_workflow/decision-log.md` (D1–D15 decisions,
F1–F25 findings, every claim carrying file:line citations). Read it first on resume.

## Open questions

Remaining **optional** probes the user may want before planning (none blocks the
plan; the spine is complete):
- Security / internal-classes path: `OUser`/`ORole` and the `internalClasses`
  set are schema too, created at database creation. How does the metadata-write
  mutex (D7) interact with the `internalClasses` bootstrap during DB creation?
- The distributed/remote schema path: the `reload`-on-remote branch in
  `SchemaShared.releaseSchemaWriteLock` (vs the embedded `saveInternal` branch).

## Raw notes / partial findings

- Full state lives in `decision-log.md`. Spine in one breath: metadata-first +
  reconcile-at-commit (D1, D3), provisional collection ids like temp RIDs (D2),
  diff over collection ids from the tx's changed records (D6, D9), tx-scoped
  metadata-write mutex serializing writers by blocking not rollback (D5, D7),
  record-local isolation (D4), tx-local `SchemaShared` full copy + changed-class
  set (D8), per-class schema records + on-open migration (D14), tx-local index
  **definition overlay** not a copy (D15), structural revertibility free from the
  atomic-operation machinery (D10/F16), RID resolution already flows into index
  entries (F24), artificial collection names so rename is metadata-only (D11),
  index build at commit under the exclusive lock for v1 (D12) with new indexes
  not query-usable until commit so the planner skips unbuilt indexes (D13).
- Follow-up issue filed: **YTDB-1064** (move the transactional index build off
  the exclusive commit lock), depends on YTDB-382.
- Sketched track shape (for planning): (1) tx-local schema view + write routing +
  guard removal, (2) commit-time structural reconciliation + diff, (3) per-class
  records + migration, (4) index create/populate-at-commit + planner guard,
  (5) collection-name decoupling. Three tx guards to remove: `saveInternal`
  (`SchemaShared:817`), `dropClass` (`SchemaEmbedded:373/417`), `createIndex`
  (`IndexManagerEmbedded:306`).
- IDE: mcp-steroid reachable; project `transactional-schema` is open and matches
  the worktree, so PSI symbol audits are available.

## Resume notes

- **Do NOT re-explore:** anything in `decision-log.md` (D1–D15, F1–F25) or in
  "Already ruled out" above. Read `decision-log.md` before doing anything.
- **Next action on resume:** the user wants to continue research. Offer the
  remaining optional probes (Open questions above), or proceed straight to
  "create the plan" — the design spine is ready either way. Ask which.
- Re-run the mcp-steroid preflight (`steroid_list_projects`) once before the
  first symbol audit; confirm `transactional-schema` is still the open project.
