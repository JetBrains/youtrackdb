# Handoff: Phase 0 (research) — YTDB-382 transactional schema — ready for Phase 1

**Paused:** 2026-06-04
**Phase:** 0 (research, `/create-plan`) — research complete, ready for Phase 1 (planning)
**Context level at pause:** info (~33%) — user-requested pause to start planning in a fresh session
**Branch:** transactional-schema
**HEAD:** 65bb0162fc "Add F48-F51: performance pass on the 400-class/4000-index batch"
**Unpushed:** 0 commits (all pushed)

## What I was investigating

Two adversarial passes this session over the YTDB-382 spine: a third (correctness) pass on
the D8/D14/D15 commit-machinery interaction seams, and a fourth (performance) pass on the
headline 400-class × 10-property all-indexed one-batch migration workload. Both complete.
The spine is now **D1–D20, F1–F51, four passes, zero open questions**. Authoritative record:
`docs/adr/transactional-schema/_workflow/decision-log.md`. Read it first on resume; do NOT
re-derive it.

## Already ruled out / settled (do NOT re-explore)

- Full spine in `decision-log.md` (D1–D20, F1–F51), every entry with `file:line` citations
  plus a small Mermaid diagram.
- Passes 1–2 (F1–F44): see `decision-log.md` §2a.
- **Pass 3 (correctness, F45–F47 + an F46/F42 refinement), all PSI-verified:**
  - F45 [MAJOR]: the F41 `fromStream` seed must carry each existing class's committed
    per-class record RID; `SchemaClassImpl` has no RID field today (PSI: one inheritor, no
    `Identifiable` field). Folded into D8/D14.
  - F46 [MAJOR/BLOCKER]: class-structural ops (`createClass`/`addCollectionId`/`addSuperClass`)
    mutate index `collectionsToIndex` via the polymorphic ripple →
    `IndexManagerEmbedded.addCollectionToIndex` is the fifth/sixth self-commit guard
    (`executeInTxInternal`), mutates the shared committed `Index` in place (D4 leak), and is
    a missing D15 category; `getCollectionNameById(provisional<0)`→null makes commit-only
    deferral correctness-required. Folded into D15/D7/F42.
  - F47 [VERIFIED + pitfall]: the ripple is lock-free and `polymorphicCollectionIds` is
    unserialized → no changed-class-set pollution; the changed-class-set hook is one lock
    level above the D7-mutex engage-point.
  - `collectionCounter` dual-authority is clean (names/ids decoupled by D11, counter tx-local
    under D8).
- **Pass 4 (performance, F48–F51), code-grounded:**
  - Verdict: the design delivers the win (one commit vs ~8,400 per-op self-commits; the
    schema-record AND index-manager-record O(N²) rewrites both drop to O(N)), and
    concentrates cost into one exclusive-locked atomic operation.
  - F48 [MAJOR — envelope]: ~24,800 files + ~4,402 records + 4,000 engine inits under
    `stateLock.writeLock`; safe in the offline D20 migration envelope, stalls a live DB;
    off-lock build = YTDB-1064; `FileChanges` buffer until `commitChanges` (F16).
  - F49 [MAJOR — operational]: `CLASS_COLLECTIONS_COUNT`=8 is the dominant file multiplier
    (3,200 collections); the D20 import should set it low.
  - F50 [latent]: the index-manager `CONFIG_INDEXES` link set is monolithic → incremental
    index creation keeps the O(N²) write-amp D14 removed for classes; folds into YTDB-1064.
  - F51 [MAJOR — invariant]: F35's tx-local snapshot rebuild must be lazy invalidation, not
    eager reconstruction (else O(N²) for the batch); single-column `createIndex` reads no
    snapshot (PSI Q3), composite/data-interleaved batches do.

## Open questions

None — the spine is settled and the batch performance is assessed.

## Most promising lead / Next action on resume

The user chose to **create the plan in a fresh `/create-plan` session**. On resume:

- Run `/create-plan`; it auto-resumes Phase 0 via this handoff. When the user says "create
  the plan", transition to Phase 1 and derive `implementation-plan.md` + `plan/track-N.md`
  + `design.md` from D1–D20 / F1–F51.
- Carry the small-Mermaid convention into `design.md` (MEMORY feedback
  `decision-log-mermaid-diagrams`).
- The plan MUST carry the BLOCKERs and invariants surfaced across the four passes:
  - F33/D19 (schema-carrying commit takes `writeLock` from the start, no read→write upgrade).
  - F39 (extract lock-free `doAddIndexEngine`/`doDeleteIndexEngine`).
  - F42 (split the `collectionId < 0` predicate; re-key the reverse map at commit).
  - F45 (per-class record RID is a field bound at load; the seed preserves it).
  - F46 (the guard inventory is now SIX self-commit paths: F3/F4/F21/F26 +
    `addCollectionToIndex`/`removeCollectionFromIndex`; membership is a commit-only D15
    category).
  - F51 (lazy tx-local snapshot invalidation, not eager rebuild).

## Raw notes / partial findings

Six-track sketch (from the prior handoff, refined by F39–F51):

1. tx-local schema view + write routing + guard removal [F41 `fromStream` seed + F45
   per-class RID binding; F44 dual engage-point; F46 de-guard
   `addCollectionToIndex`/`removeCollectionFromIndex`].
2. commit-time structural reconciliation + diff [F39 `doAddIndexEngine`/`doDeleteIndexEngine`;
   F43 D9 set-diff over committed vs tx-local in-memory structures; D19 `writeLock` branch;
   F48 the single large atomic op].
3. per-class records (D14) [D20 export/import migration → no on-open migrator; F45 RID field].
4. index create/populate-at-commit + planner guard + overlay (D15) [F40 rename category +
   F46 membership category; F35/F51 lazy snapshot invalidation; F49 `CLASS_COLLECTIONS_COUNT`
   knob].
5. collection-name decoupling (D11).
6. genesis two-phase restructure (D18).

Plus v1 class-rename re-association (D17). F42's provisional-id predicate split +
`collectionOverrides` re-key spans tracks 1–2.

- IDE: mcp-steroid was reachable and the `transactional-schema` project was open and matched
  the worktree (PSI used for F45/F46/F51 verification). A fresh session may find it closed or
  another project open — re-run the preflight (`steroid_list_projects`) and switch/open
  `transactional-schema` before any load-bearing symbol audit during Phase 1.
- Follow-up issues: YTDB-1064 (index-build optimization; now also F50 manager-link-set and
  F48 off-lock build), YTDB-1066 (D16 base-keyed engine files).

## Resume notes

- **Do NOT re-explore:** `decision-log.md` D1–D20, F1–F51; the "ruled out" list above.
- **Next action on resume:** run `/create-plan`; on "create the plan", derive
  `implementation-plan.md` + track files + `design.md` from the spine.
- Re-run the mcp-steroid preflight once before the first symbol audit; confirm
  `transactional-schema` is the open project.
