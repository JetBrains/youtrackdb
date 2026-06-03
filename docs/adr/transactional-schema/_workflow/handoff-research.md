# Handoff: Phase 0 (research) — YTDB-382 transactional schema

**Paused:** 2026-06-03
**Phase:** 0 (research, `/create-plan`)
**Context level at pause:** warning (~41%) — user-requested pause
**Branch:** transactional-schema
**HEAD:** 21383ac3e1 "Resolve adversarial findings: D19 (lock branch), fix D1/D3/D6/D7/D15/F31"
**Unpushed:** 10 commits onto `origin/transactional-schema` plus this handoff; pushed as part of the pause.

## What I was investigating

YTDB-382: make schema changes transactional (metadata-first, storage reconciles
at commit). This session resumed the paused Phase 0 research and deepened three
areas — the index lifecycle (create/drop/rename), the genesis bootstrap, and
commit-time locking/ordering — then ran an adversarial pass over the whole
decision log. The architecture spine is settled and adversarially vetted:
**D1–D19, F1–F38, zero open questions**. The authoritative record is
`docs/adr/transactional-schema/_workflow/decision-log.md`. Read it first on
resume; do not re-derive it.

## Already ruled out / settled (do NOT re-explore)

- The full spine is in `decision-log.md` (D1–D19, F1–F38). Every claim carries
  `file:line` citations and a small Mermaid diagram.
- **Remote/distributed schema path** — dropped as a leftover artifact (user,
  2026-06-03). Not a live concern.
- **Index-name rename via base-keyed engine files (D16)** — deferred to
  follow-up **YTDB-1066**; v1 does the metadata-only class-rename re-association
  (D17), which keeps indexes accelerating after a class rename.
- **Locking:** D1's read→write `stateLock` upgrade is impossible
  (`ScalableRWLock` non-reentrant, no upgrade primitive — F33). Resolved by D19:
  a schema-carrying commit takes `writeLock()` from the start; pure-data commits
  keep the `readLock()` fast path.

## This session's additions (all committed)

- **Index lifecycle findings:** F26 (fourth tx guard, `dropIndex`
  `IndexManagerEmbedded:459`), F27 (engines name-keyed, no first-class rename),
  F28 (class rename orphans name-keyed index associations), F29 (base-keyed
  files feasible, migration-free), F30 (`IndexDefinition.className` mutable; the
  planner resolves by class name, not by index name), F32 (`ClassIndexManager`
  enqueue is engine-agnostic — a same-tx engine-less index behaves like a built
  one).
- **Decisions:** D16 (base-keyed files — deferred to YTDB-1066), D17 (v1
  metadata-only class-rename re-association), D18 (two-phase genesis bootstrap),
  D19 (branch the commit lock).
- **Genesis:** F31 (bootstrap must become tx-aware; today it relies on per-op
  self-commit and the tx guards).
- **Adversarial review (§2a):** F33–F38, all resolved (F33→D19, F34→D3, F35→D15,
  F36→F31, F37→D6, F38→D7).
- Filed **YTDB-1066** (depends on YTDB-382, relates to YTDB-1064).

## Open questions

None in the log — all resolved. The only open choice is direction: plan versus
more research.

## Most promising lead / Next action on resume

The spine is complete and adversarially vetted. On resume, ask the user which:

- **Create the plan** — say "create the plan" to transition `/create-plan` to
  Phase 1; derive `implementation-plan.md` + track files + `design.md` from
  D1–D19 / F1–F38. Carry the small-Mermaid-diagram convention into `design.md`
  (see the MEMORY feedback `decision-log-mermaid-diagrams`).
- **Another adversarial pass** on the D16–D19 / F26–F38 additions (this
  session's work, less independently reviewed), or attack a specific area
  (crash-recovery, the per-class-record on-open migration).
- **Research a named thread:** the per-class-record migration (D14), the
  four-guard removal sequencing, or the commit-time diff mechanics (D6).

## Raw notes / pointers

- **Sketched track shape** (extended from the original handoff): (1) tx-local
  schema view + write routing + guard removal (four guards: `saveInternal`
  `SchemaShared:817`, `dropClass` `SchemaEmbedded:373/417`, `createIndex`
  `IndexManagerEmbedded:306`, `dropIndex` `IndexManagerEmbedded:459`); (2)
  commit-time structural reconciliation + diff + lock-branch (D19) + ordering
  (D3/D12); (3) per-class records + on-open migration (D14); (4)
  index create/populate-at-commit + planner guard + the overlay
  snapshot-rebuild invariant (D15/F35); (5) collection-name decoupling (D11);
  (6) genesis two-phase restructure (D18). v1 also: metadata-only class-rename
  re-association (D17).
- IDE: mcp-steroid reachable; project `transactional-schema` open and matches
  the worktree — PSI available.
- Convention: every decision-log F/D entry carries a small Mermaid diagram
  (user preference; saved to MEMORY feedback `decision-log-mermaid-diagrams`).

## Resume notes

- **Do NOT re-explore:** `decision-log.md` D1–D19, F1–F38; the "ruled out" list
  above.
- **Next action on resume:** ask the user — create the plan, another adversarial
  pass, or a named research thread (above).
- Re-run the mcp-steroid preflight (`steroid_list_projects`) once before the
  first symbol audit; confirm `transactional-schema` is still the open project.
