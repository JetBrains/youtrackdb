# Handoff: Phase 0 — YTDB-382 pass-8 fix discussion, F83–F91 pending

**Paused:** 2026-06-11
**Phase:** 0 (research / adversarial fix discussion, `/create-plan`)
**Context level at pause:** warning (40%) — paused after pass-8 registration, before settlement
**Branch:** transactional-schema
**HEAD:** 589116eee3 "Run adversarial pass 8, register F83-F91"
**Unpushed:** 0 commits (all pushed)

## Durable artifacts on disk

- `docs/adr/transactional-schema/_workflow/decision-log.md` — the spine, now D1–D20 /
  F1–F91. Passes 1–8 complete. **F1–F82 all RESOLVED and folded into the D entries**
  (maps in §2a). F83–F91 registered with proposed resolutions (pass-8 block at the end
  of §2), none yet accepted.
- `_workflow/adversarial-pass{5,6,7,8}-{concurrency,durability}.md` — eight report
  files, each with a failed-attack list. Pass-8 verdict: 1 BLOCKER (F83), 5 MAJOR,
  3 MINOR after dedup (C22+U17 → F85, C24+U18 → F87).
- Pass-7 settlement commits (this session): F76 `43adcbf20d` (RMW variant), F77
  `abf10c6146`, F78 `97d7cf659f` (reject-loudly), F79 `eeac5e87af` (token CAS, Java
  sketch in entry), F80 `94b5809fc1`, F81 `0711110ea0` ((c)+(b)), F82 `d7ebd3af13`.
- YouTrack: YTDB-1099 (WAL replay), YTDB-1101 (enqueue race). Both relate to YTDB-382.

## Pending decision

**F83–F91 settlement, one by one, starting with F83 [BLOCKER].** Entry texts with
proposed resolutions are in the log §2 (after F82); present each from the log, get
accept/modify, fold into the D entries, extend the §2a pass-8 map line, commit per
finding. Flip the map tail to "All pass-8 findings are resolved" when done.

After all nine settle: the loop is NOT dry (pass 8 found a BLOCKER), so the next
decision is pass 9 (same two lenses, attack the pass-8 folds) vs stopping. The user has
**pre-committed to a performance-implications adversarial pass** once a
concurrency/durability pass comes back clean ("if previous succeed") — queue it after
the loop dries, do not run it before.

## Verbatim re-present text (F83, first to present)

Present the F83 entry from the log §2 verbatim (title, body, the sequenceDiagram, and
the proposed resolution with options (a) packed/locked compound state vs (b)
zero-count-ignoring LWM with the re-derived TOCTOU argument). The entry is
self-contained in the log; do not re-derive.

## Resume notes

- **Do NOT redo:** passes 1–8 (eight adversarial passes complete; failed-attack lists
  in the eight report files); F1–F82 resolutions (accepted, folded into D entries,
  committed); do NOT re-spawn adversarial agents for old ground.
- **Settlement-shape suggestion (from this session):** F83, F84, and F85 are three
  faces of one defect class (the D7 reap protocol's cross-thread compounds). Consider
  settling them as ONE coherent D7 reap-protocol rewrite — compound {count, tsMin}
  state + once-only consume of the captured holder + atomic status handshake
  (BEGUN→COMMITTING vs BEGUN→REAPING) — instead of three incremental patches to the
  same bullet. F86+F87 likewise pair (both are the freezer gate; one D7 bullet
  rewrite). F88/F89/F91 are independent one-pin folds; F90 reworks the F81 bullet in
  D20.
- **PSI re-verification debt:** mcp-steroid was unreachable when pass 8 ran, so these
  grep-based claims need PSI confirmation when the IDE is back, before or during their
  folds: F84's initiator set (Gremlin `afterTimeout` outside the kill monitor), F87's
  transient-freeze caller set (`doSynch`/backup paths), F88's registrar inventory
  (`rebuild`, `loadExternalIndexEngine`, `recreateIndexes`). Run
  `steroid_list_projects` preflight first.
- **Open Phase-1 decision carried in the log:** D12/F57 boundary behavior (reject vs
  accept-with-heap-envelope for populated-class createIndex beyond the bound). The F71
  thread-binding checkpoint was RESOLVED by F76 (operation half passes, tsMin/freezer/
  component-lock halves fail) and then re-opened in sharper form by F83/F85 — the D7
  reap protocol is the live design question of the pass-8 settlement.
- Follow-ups: YTDB-1064, YTDB-1066, YTDB-1099, YTDB-1101.
