# Handoff: Phase 0 (research) — YTDB-382 transactional schema

**Paused:** 2026-06-04
**Phase:** 0 (research, `/create-plan`)
**Context level at pause:** warning (~40%) — user-requested pause
**Branch:** transactional-schema
**HEAD:** 541c371a4b "Refine F44/F35: index mutation hits schema snapshotLock, not the mutation lock"
**Unpushed:** 0 commits (all pushed)

## What I was investigating

A second adversarial pass over the YTDB-382 transactional-schema spine, targeting
this session's less-reviewed additions (D16–D19, F26–F38) and the four assignee-
relevant seams (migration, D8 ripple, D2 sentinel range, D6 diff, D7 mutex). The
architecture spine is now twice adversarially vetted: **D1–D20, F1–F44, zero open
questions**. The authoritative record is
`docs/adr/transactional-schema/_workflow/decision-log.md`. Read it first on resume;
do not re-derive it.

## Already ruled out / settled (do NOT re-explore)

- Full spine in `decision-log.md` (D1–D20, F1–F44). Every entry carries `file:line`
  citations and a small Mermaid diagram.
- **Migration (D20):** JSON export/import, NO in-place on-open migrator — drops the
  D14 migration crash-safety scope. Verified vs `DatabaseExport`/`DatabaseImport`
  (export walks the logical schema; import rebuilds via the schema API).
- **D8 derived-state ripple (F41):** the tx-local seed must be `fromStream` re-parse,
  not a deep-copy — `SchemaClassImpl.owner` is `final`; a naive copy leaves `owner`
  pointing at the committed `SchemaShared` (D4 isolation violation + shared-lock
  serialization).
- **D2 sentinel range (F42, BLOCKER):** provisional collection ids collide with the
  pervasive `collectionId < 0` convention; `collectionsToClasses` skips negatives, so
  a record in a new collection NPEs on record→class resolution. Resolution: split the
  `< 0` predicate into abstract(−1)/provisional(≤−2)/real(≥0); re-key the reverse map
  at commit via the existing `collectionOverrides` seam (`AbstractStorage:2269`/`:2317`).
- **Index/provisional-RID (F24, verified):** the tx index machinery is
  identity-change-driven, so a provisional collection-id resolution rides the same
  rails as the temp-position resolution — no remap needed.
- **D6 diff (F43):** D6 (per-property) and D9 (set difference) conflate "which records
  to write" with "which collections to create/drop"; the drop path needs the committed
  in-memory `SchemaShared`, not the deleted record.
- **D7 mutex engage-point (F44):** dual chokepoint (`acquireSchemaWriteLock` +
  `acquireExclusiveLock`); index-only txs bypass the schema chokepoint, so both must be
  instrumented. Index mutation hits the schema's `snapshotLock` (cache invalidation),
  NOT the mutation lock (user-raised follow-on, verified).

## This session's additions (all committed + pushed)

- **D20** (export/import migration).
- **F39** (BLOCKER — engine create/delete need lock-free `doAddIndexEngine`/
  `doDeleteIndexEngine`; D19's held write-lock self-deadlocks the public methods on the
  non-reentrant `ScalableRWLock`), **F40**, **F41**, **F42** (BLOCKER), **F43**, **F44**.
- Resolutions folded into D2/D3/D6/D7/D8/D9/D15/D17/D19; F24 and F35 strengthened.
- Commits: `cb5e6d40b6` (resume) … `541c371a4b` (final). Branch is in sync with origin.

## Open questions

None — the spine is settled.

## Most promising lead / Next action on resume

The spine is complete and twice vetted. On resume, ask the user which:

- **Create the plan** — "create the plan" transitions `/create-plan` to Phase 1; derive
  `implementation-plan.md` + track files + `design.md` from D1–D20 / F1–F44. Carry the
  small-Mermaid convention into `design.md` (MEMORY feedback
  `decision-log-mermaid-diagrams`). Each finding carries an actionable track-insertion
  point (see the track sketch below).
- **Another adversarial pass** or a **named research thread** if the user wants more.

## Raw notes / partial findings

Sketched 6-track shape (from the prior handoff, now informed by F39–F44):

1. tx-local schema view + write routing + four-guard removal [+ F41 `fromStream` seed;
   F44 dual engage-point].
2. commit-time structural reconciliation + diff [+ F39 extract `doAddIndexEngine`/
   `doDeleteIndexEngine`; F43 structural diff = D9 set difference over committed vs
   tx-local in-memory structures; D19 schema-carrying write-lock branch].
3. per-class records (D14) [D20 drops the on-open migrator → no migration crash-recovery
   work in this track].
4. index create/populate-at-commit + planner guard + overlay (D15) [F40 rename-mutation
   commit-only category; F35 tx-local snapshot rebuild hooks
   `IndexManagerEmbedded.releaseExclusiveLock:201-208`].
5. collection-name decoupling (D11).
6. genesis two-phase restructure (D18).

Plus v1 class-rename re-association (D17). F42's provisional-id predicate split +
`collectionOverrides` re-key spans tracks 1–2.

- IDE: mcp-steroid reachable; project `transactional-schema` open and matches the
  worktree — PSI available.
- Convention: every decision-log F/D entry carries a small Mermaid diagram (MEMORY
  feedback `decision-log-mermaid-diagrams`).

## Resume notes

- **Do NOT re-explore:** `decision-log.md` D1–D20, F1–F44; the "ruled out" list above.
- **Next action on resume:** ask the user — create the plan, another adversarial pass,
  or a named research thread.
- Re-run the mcp-steroid preflight (`steroid_list_projects`) once before the first
  symbol audit; confirm `transactional-schema` is still the open project.
