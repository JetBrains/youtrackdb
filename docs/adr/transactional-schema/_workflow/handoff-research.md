# Handoff: Phase 0 — YTDB-382 pass 9 approved, not yet run

**Paused:** 2026-06-11
**Phase:** 0 (research / adversarial loop, `/create-plan`)
**Context level at pause:** info (38%) — clean stop after pass-8 settlement completed
**Branch:** transactional-schema
**HEAD:** f1c0c4928d "Accept F91: three durability pins on D20's F82 bullet"
**Unpushed:** 0 commits (all pushed)

## What I was investigating

Pass-8 settlement is complete: all nine findings resolved (§2a map tail flipped at
the F91 fold). The user approved running adversarial pass 9 in a fresh session,
scoped to the pass-8 settlement text only.

## Already ruled out

- Re-attacking the withdrawn reap machinery: cross-thread reaping left the design
  (postponed to YTDB-1114, commit `74230cf4c0`); F83/F84/F85/F89 dissolved with it,
  F76 superseded, F79's owner token retained.
- Re-attacking F1–F91 old ground: eight passes complete; failed-attack lists live in
  the eight `adversarial-pass{5,6,7,8}-{concurrency,durability}.md` report files.

## Most promising lead

Pass-9 attack surface = the settlement diff `589116eee3..f1c0c4928d` (decision-log
text only):

- D7 abnormal-termination bullet — owner-thread-only teardown + postponement wiring.
- D7 freezer bullet — the composed F86+F87 mechanism: freeze-kind taxonomy at five
  registration sites, pre-lock kind-aware probe, re-probing bounded try-acquire on
  `stateLock.write`, in-window gate as backstop with two wiring pins.
- D3 allocator-seed sentence — seed read inside the `stateLock.write` window (F88).
- D20 F81 bullet — section-presence criterion, ack flag reclassified procedural,
  export-exit-status pin (F90).
- D20 F82 stream/platform pins — whole-stream validation via gzip CRC32,
  best-effort directory fsync, warn-logged non-atomic move fallback (F91).

## Resume notes

- **Next action on resume:** spawn the two adversarial lenses (concurrency +
  durability), fresh agents, primed with all eight prior failed-attack lists,
  scoped to the settlement surface above. Reports →
  `adversarial-pass9-{concurrency,durability}.md`. Register findings as F92+ in §2
  with proposed resolutions, extend §2a, commit (`Run adversarial pass 9, register
  F92-…`), then settle one by one — orientation register in chat, ASCII diagrams in
  chat (Mermaid only in files).
- **If pass 9 comes back clean:** the loop is dry → the user PRE-COMMITTED to a
  performance-implications adversarial pass next ("if previous succeed") — run it
  in another fresh session.
- **Do NOT redo:** passes 1–8; F1–F91 resolutions (all folded, committed, pushed);
  do not re-spawn agents for old ground.
- Open Phase-1 decision carried in the log: D12/F57 boundary (reject vs
  accept-with-heap-envelope for populated-class createIndex beyond the bound).
- mcp-steroid: re-run `steroid_list_projects` preflight at resume before any
  symbol audit.
- Related issues filed this session: YTDB-1113 (Gremlin timeout-hook today-bugs),
  YTDB-1114 (postponed orthogonal reaper).
