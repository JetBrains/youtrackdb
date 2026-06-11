# Handoff: Phase 0 — pass 9 part-settled; F93–F95 remain

**Paused:** 2026-06-11
**Phase:** 0 (research / adversarial loop, `/create-plan`)
**Context level at pause:** info (39%) — clean stop after the F92 settlement cascade
**Branch:** transactional-schema
**HEAD:** 1fe08ee6bd "Swap the D7 mutex to a thread-owned write lock"
**Unpushed:** 0 commits (all pushed)

## What I was investigating

Pass 9 ran (two lenses over the pass-8 settlement diff `589116eee3..f1c0c4928d`)
and registered F92–F95 (`a031b4f73a`). F92's settlement became a premise-audit
cascade that simplified D7 step by step; F93–F95 are registered in §2a with
proposed resolutions and await settlement.

## Already ruled out

- **F92's entire threat model** — both premises disproven against the tree
  (PSI; TinkerPop fork project opened in the IDE). The 3.8 `GremlinExecutor`
  invokes `afterTimeout` only inside the eval worker's interrupt-unwind
  (`GremlinExecutor:354`, sole invocation site; the scheduler only cancels,
  `:370`–`:377`), and `tx()` resolves per-thread
  (`YTDBGraphImplAbstract:219`, `ThreadLocalState` at `:86`/`:511`). All ten
  `rollback()` sites in `server/src/main` run same-thread; the managed path
  defensively clears an inherited tx (`YTDBAbstractOpProcessor:235`–`:239`).
  **YTDB-1113 closed as Invalid** with a correcting comment.
- **Thread-carrying token** — rejected: no initiator exists, it would wedge
  pool-shutdown cleanup, and it would forbid `releaseStranded`.
- **The F79 token entirely** — withdrawn (`c4c59cd2e4`): YTDB-1114 revokes
  *registrations*, never acquisitions, so no revoker exists in any planned
  state and the stale arm is unreachable forever. Sketch stays in F79's
  record for any future revocation mechanism.
- **The `Semaphore(1)` primitive** — F71 arm (2) reversed (`1fe08ee6bd`):
  the D7 mutex is now a thread-owned `ReentrantLock`-shaped write lock
  (F71's premises dissolved: reap withdrawn; the kill path is owner-thread
  per F92, so cross-thread releasability had no caller). Two pins ride the
  swap: foreign unlock caught and warn-logged; engage path loudly rejects a
  different session on the current thread.

## Most promising lead

The settlement queue, with proposed resolutions already registered in §2a:

- **F93 [MINOR]** — D7 freezer bullet's "data commits keep today's uniform
  park everywhere" misstates the shipped gate (throw-mode freezes throw:
  `OperationsFreezer:40`/`:114`–`:118`; `freeze(db,true)` registers that
  supplier, `AbstractStorage:3901`–`:3903`). Proposed: restore the
  park/throw split in the D7 bullet and the F87 record; reconcile the
  wiring-pin rationale drift (D7 says "corrupts the freezer count", F87
  says "masks the gate throw" — F87's wording is the accurate one).
- **F94 [MAJOR]** — the legacy exporter swallows mid-collection iterator
  failures (only `YTIOException` rethrows, `DatabaseExport:212`–`:221`;
  `brokenRids` is populated only in `exportRecord:582`), so a dump missing
  a collection tail passes exit status, section presence, and the ack flag.
  Proposed: widen the residue wording ("any source-side loss the old
  exporter does not report") plus pin a per-collection count comparison or
  an export-log review step.
- **F95 [MINOR]** — gzip CRC32 is per-member; `GZIPInputStream.readTrailer`
  reads a malformed next-member header as clean EOF. Proposed: pin
  single-gzip-member framing, or state decompression success as necessary
  and never sufficient.

## Open questions

- **Pass 10 vs loop-dry** after F93–F95 settle: the F92 cascade produced a
  sizable fresh settlement surface (D7 header + teardown bullet rewrites,
  the F71 reversal, F79/F84/F85 correction notes) — fresh unattacked text
  is the loop's standard argument for one more pass. User decides.
- **Performance pass PRE-COMMITTED** once the loop is dry (fresh session).
- Then **Phase 1** via `/create-plan` Step 4a (`edit-design`). Open Phase-1
  decision: D12/F57 boundary (reject vs accept-with-heap-envelope).
- Phase-1 pin candidates from the pass-9 dry lists: freezer read-lock
  enclosure (new freeze call sites keep engage→release inside a
  `stateLock.read` window); "export into an empty directory".

## Raw notes / partial findings

- Commits this session (all pushed): `acdcab551c` resume handoff,
  `a031b4f73a` register F92–F95, `df92f16066` dissolve F92,
  `2980d5c1bc`/`0d13a302d1` token-role pins (superseded by the
  withdrawal), `c4c59cd2e4` withdraw the token, `1fe08ee6bd` lock swap.
- YTDB-1114's architecture (registry + leases + boundary fences, never
  touching acquisitions) is the fact that drove the token withdrawal —
  re-read the issue before re-opening any reclamation question.
- IDE: two projects open — `transactional-schema` and `ytdb-fork-3.8-dev`
  (`/home/andrii0lomakin/Projects/ytdb-tinkerpop/ytdb-fork-3.8-dev`); the
  fork checkout serves any further `GremlinExecutor` ground.

## Resume notes

- Do NOT re-explore: the Gremlin threading ground (F92 cascade), F1–F91
  old ground, or the prior reports' dry lists.
- **Next action on resume:** settle F93, F94, F95 one by one with the user
  (orientation register in chat, ASCII diagrams in chat, Mermaid only in
  files), folding each accepted resolution into D7/D20 and the F-records,
  one commit per settlement. Then the user decides pass 10 (scope: the
  pass-9 settlement text including the F92/D7 cascade) vs loop-dry → the
  pre-committed performance pass.
- mcp-steroid: re-run `steroid_list_projects` preflight on resume before
  any symbol audit.
