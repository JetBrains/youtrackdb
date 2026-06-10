# Handoff: Phase 0 — YTDB-382 pass-6 fix discussion, F74/F75 pending

**Paused:** 2026-06-10
**Phase:** 0 (research / adversarial fix discussion, `/create-plan`)
**Context level at pause:** warning (43%) — user-requested pause mid one-by-one resolution
**Branch:** transactional-schema
**HEAD:** 7511b199e5 "Accept F73 (three-step replay branch pins): F55 amended, YTDB-1099 commented"
**Unpushed:** 0 commits (all pushed)

## Durable artifacts on disk

- `docs/adr/transactional-schema/_workflow/decision-log.md` — the spine, now D1–D20 /
  F1–F75. Passes 1–6 complete. **F52–F73 all RESOLVED and folded into the D entries**
  (see §2a "Pass-5 resolutions" and "Pass-6 resolutions" blocks for the maps).
  F74/F75 remain proposed-not-yet-accepted.
- `_workflow/adversarial-pass5-concurrency.md` / `-durability.md` — pass-5 agent reports
  (C1–C7/U1–U7 + failed-attack lists).
- `_workflow/adversarial-pass6-concurrency.md` / `-durability.md` — pass-6 agent reports
  (C8–C15/U8–U11 + failed-attack lists).
- YouTrack: **YTDB-1099** (WAL replay fix, filed, F73 pins added as a comment),
  **YTDB-1101** (enqueue-phase race closure, filed). Both relate to YTDB-382.

## Pending decision

**F74 acceptance** (presented, awaiting yes/no), then **F75 discussion** (not yet
presented in detail). After both: extend the §2a pass-6 resolutions block, fold the
accepted text into the D entries, commit. Then decide: run a pass-7 dry-check (pass 6
found 12 new findings, so the loop is NOT yet dry) or proceed to Phase 1 design
authoring.

## Verbatim re-present text (F74, as presented to the user)

> ## F74 — the atomic operation opens at transaction *begin*, not at commit
>
> PSI-verified: the only production caller of `startStorageTx` is
> `FrontendTransactionImpl.beginInternal:185`. The operation is registered `IN_PROGRESS`
> at its WAL segment from that moment, both segment-cut bounds count `IN_PROGRESS`
> operations, and the full checkpoint refuses while any operation is open. Two
> consequences:
>
> - **WAL retention runs from tx begin through the whole user-code body**, not just the
>   commit window F57's envelope describes. Concurrent data commits proceed (the D7 mutex
>   doesn't block them) but their WAL volume accumulates uncut; a crash mid-body replays
>   a WAL proportional to everything since the schema tx began. Pre-existing for any long
>   data tx — but the design makes one long schema tx the recommended migration pattern.
> - **The reaper half is already folded**: the F71 acceptance wrote "reap runs the full
>   tx rollback (ending the atomic operation — the F74 WAL-pin half)" into D7.
>
> **Remaining resolution:** the envelope sentence — D12/F57's envelope gains "WAL
> retention and checkpoint deferral run from tx *begin* (`beginInternal:185`), so
> migration guidance keeps schema txs short-lived in wall-clock terms even though they
> are single commits" (the D20 import naturally complies: each import tx is
> begin-and-commit back-to-back).

## F75 preview (to present after F74)

F75 [MINOR] — F63's manifest needs its own write discipline: emitted strictly last and
atomically by export (temp + fsync + rename, or final section of the dump stream — an
interrupted export must be incapable of leaving a well-formed manifest); import
hard-fails on a missing/unparsable manifest for manifest-era dumps (legacy dumps
distinguished by dump version, not manifest absence). Resolution: one D20 bullet with
both pins. Affected: F63, D20. Full analysis: pass-6 report U11. Expected to be
uncontroversial.

## Resume notes

- **Do NOT redo:** passes 1–6 (six adversarial passes are complete; their failed-attack
  lists are in the four report files); F52–F73 resolutions (accepted, folded into D
  entries, committed); do NOT re-spawn adversarial agents for old ground.
- **Next action on resume:** re-present the F74 text above, get yes/no; fold (D12
  envelope sentence — anchor: the "Amended per F54/F57" paragraph in D12; F74 entry
  proposed→accepted; pass-6 map line). Then present F75, resolve, fold (D20 bullet; F75
  entry; map line; flip the map's tail to "All pass-6 findings are resolved" when done).
  Then ask the user: pass-7 dry-check vs proceed to Phase 1 (design authoring via
  `edit-design` per `/create-plan` Step 4a).
- **Open Phase-1 decisions carried in the log:** D12/F57 boundary behavior (reject vs
  accept-with-heap-envelope for populated-class createIndex beyond the bound); F71's
  Phase-1 checkpoint (verify `AtomicOperationsManager` thread-binding permits ending a
  stranded tx's operation from the reaper thread).
- Re-run the mcp-steroid preflight (`steroid_list_projects`) before any load-bearing
  symbol audit; the project may not be open in a fresh session.
- Follow-ups: YTDB-1064, YTDB-1066 (scope shrank per F67 — base-keying moved into v1;
  no comment posted on the issue yet, user declined-by-silence), YTDB-1099, YTDB-1101.
