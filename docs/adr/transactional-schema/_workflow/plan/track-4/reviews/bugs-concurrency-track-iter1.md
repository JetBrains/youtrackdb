<!--MANIFEST
dimension: bugs-concurrency
prefix: BC
level: high
verdict: APPROVED_WITH_COMMENTS
findings_total: 3
blockers: 0
should_fix: 1
suggestions: 2
evidence_base: PSI-backed (mcp-steroid reachable; project transactional-schema-b4l1mcdq matches the working tree)
cert_index: C1, C2, C3
flags: []
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-should-fix-schema-carry-commit-unconditionally-re-enables-link-consistency"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2512
    cert: C1
    basis: PSI find-usages of enable/disableLinkConsistencyCheck + source read of importRecord
  - id: BC2
    sev: suggestion
    anchor: "#bc2-suggestion-truncate-now-forces-the-heavier-schema-carry-commit-branch"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassProxy.java:128
    cert: C2
    basis: PSI find-usages of resolveForWrite + git base diff of truncate + isWriteTransaction read
  - id: BC3
    sev: suggestion
    anchor: "#bc3-suggestion-snapshot-first-reads-dereference-a-nullable-snapshot"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphImplAbstract.java:131
    cert: C3
    basis: source read of getImmutableSchemaSnapshot (declared @Nullable)
-->

# Bugs & Concurrency — Track 4 (commit-time tx-local schema reconciliation)

## Findings

### BC1 [should-fix] Schema-carry commit unconditionally re-enables link consistency

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2512`

**Issue.** `applyCommitOperations` wraps the tx-local schema serialization in
`session.disableLinkConsistencyCheck()` … `finally { session.enableLinkConsistencyCheck(); }`.
`enableLinkConsistencyCheck()` is an unconditional setter (`this.ensureLinkConsistency = true;`),
not a save-and-restore of the prior value, and `ensureLinkConsistency` is a single
plain `boolean` on the session, not a nesting counter. A schema-carrying commit that
runs while link consistency was already disabled by an *outer* scope leaves the flag
forced back ON when the commit returns, silently defeating the outer disable for the
remainder of that outer operation.

**Failure scenario.** An operation calls `session.disableLinkConsistencyCheck()`, then
performs work that triggers a schema-carrying commit (a `createClass` / `dropClass` /
attribute alter on the same session inside that disabled window), and continues writing
graph records afterwards expecting consistency still suppressed. After the schema commit,
line 2512 re-enables consistency, so the post-commit record writes are link-checked
against the operator's intent. The mirror caller `DatabaseImport.importRecord`
(`core/.../db/tool/DatabaseImport.java`) follows exactly this disable→`session.commit()`→
re-enable-in-its-own-`finally` shape; Track 8 restructures genesis/import into
schema-carrying transactions (D18/D20), which is precisely where a schema-carry commit
lands inside a `disableLinkConsistencyCheck` window.

**Evidence.** PSI find-usages of `DatabaseSessionEmbedded.disableLinkConsistencyCheck` /
`enableLinkConsistencyCheck` returns three caller sites — `DatabaseRecordWalker.walkEntitiesInTx`,
`DatabaseImport.importRecord`, and the new `AbstractStorage.applyCommitOperations`; the
field is `boolean ensureLinkConsistency` (non-volatile, unconditional setters). See C1.

**Why should-fix not blocker.** Not confirmed reachable today: the per-record import path
that disables consistency (`importRecord`) commits *pure-data* records (schema/index
manager records are `delete()`d, not applied, so `getTxSchemaState()` is null → the
pure-data branch, which never touches the flag), and the import's schema-build phase runs
the schema API outside the per-record disabled window. The defect is incorrect by
construction — a save-restore is required for nestability — and becomes live the moment a
schema-carrying commit executes inside any disabled window, so it should be fixed before
the Track 8 work that creates that adjacency lands.

**Suggestion.** Capture and restore the prior value instead of forcing `true`:
`final boolean prev = session.isLinkConsistencyEnabled(); session.disableLinkConsistencyCheck();
try { … } finally { session.setLinkConsistencyEnabled(prev); … }` (add the accessor pair if
absent), so a nested disable is preserved.

### BC2 [suggestion] `truncate()` now forces the heavier schema-carry commit branch

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassProxy.java:128`

**Issue.** `SchemaClassProxy.truncate()` — a pure data operation that empties a class's
records and changes no schema — routes through `resolveForWrite()`. Two things this track
adds make a truncate-only transaction behave like a schema change: (1) the new
`recordWriteTarget` hook marks the class into `getChangedClasses()`, and (2) the new
`isWriteTransaction` signal (`FrontendTransactionImpl.java:1867`) treats a seeded tx-local
schema state as a write signal. Together, a transaction whose only schema-layer touch is
`truncate` now takes the schema-carry commit path: `stateLock.writeLock()` for the whole
commit (excluding concurrent data commits), the four-lock acquisition, and a rewrite of the
truncated class's per-class record (a version bump for unchanged schema content).

**Failure scenario.** `session.begin(); schema.getClass("X").truncate(); … session.commit();`
serializes as a schema commit and rewrites `X`'s schema record, where before this track it
was a read-locked data commit. No incorrect result — reconciliation finds an empty
collection-id diff, resolution is empty, promotion re-parses unchanged — but the commit is
needlessly serialized against all other commits and writes an extra record.

**Evidence.** PSI find-usages of `resolveForWrite` shows `SchemaClassProxy#truncate` among
the write-routed callers; `git show <base>:SchemaClassProxy.java` confirms the
`resolveForWrite()` call in `truncate` predates this track (Track 3), so the tx-local seed
on truncate is pre-existing — what this track newly attaches is the changed-class mark and
the schema-carry commit treatment. See C2.

**Why suggestion.** Correctness-safe (over-recording / heavier path only). The
write-amplification and serialization cost is a performance dimension; flagged here only
because the mechanism (a data op masquerading as a schema change at commit) is a bug-shaped
behavioral surprise. Consider excluding `truncate` from `recordWriteTarget`, or routing it
through `resolve()` if it never needs the tx-local copy.

### BC3 [suggestion] Snapshot-first reads dereference a `@Nullable` snapshot

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphImplAbstract.java:131`
(also `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchStatement.java:368`)

**Issue.** The Step-6 conversions read
`session.getMetadata().getImmutableSchemaSnapshot()` and immediately call `.getClass(...)`
on it. `MetadataDefault.getImmutableSchemaSnapshot()` is declared `@Nullable` and returns
`null` when `immutableSchema == null && schema == null`. The two new sites add no null
guard, so a null return would NPE rather than fall through to the existing lock-based path.

**Failure scenario.** A call on a session whose metadata schema is not yet/no longer
materialized returns `null`, and `snapshot.getClass(label)` throws NPE. Practically
unreachable on an open session (the schema is always materialized), which is why the
Step-6 review recorded this as a deferred suggestion (BC1 there) and notes the existing
snapshot-consumer idiom does the same.

**Evidence.** Source read of `MetadataDefault.getImmutableSchemaSnapshot` (carries the
`@Nullable` annotation and the `return null` branch). See C3.

**Why suggestion.** Matches the established codebase idiom; no production path reaches the
null branch on an open session. A defensive `if (snapshot == null) { /* lock-based
fallback */ }` would harden it, but is optional.

## Evidence base

#### C1 — link-consistency re-enable clobber (CONFIRMED defect-by-construction; reachability PLAUSIBLE)
PSI find-usages of `disableLinkConsistencyCheck` / `enableLinkConsistencyCheck` →
{`DatabaseRecordWalker.walkEntitiesInTx`, `DatabaseImport.importRecord`,
`AbstractStorage.applyCommitOperations`}. Field is `boolean ensureLinkConsistency`,
setters are unconditional. `DatabaseImport.importRecord` source: disables, `session.commit()`
inside, re-enables in its own `finally`. The unconditional re-enable in
`applyCommitOperations` is non-nestable; it clobbers an outer disable. Live-reachability
refutation: the import per-record commit is pure-data (`SCHEMA_MANAGER`/`INDEX_MANAGER`
records are deleted, so `getTxSchemaState() == null` → pure-data branch, flag untouched),
so the clobber is not triggered on today's paths → ranked should-fix, not blocker.

#### C2 — truncate forces schema-carry (CONFIRMED behavior; correctness-safe)
PSI find-usages of `resolveForWrite` lists `SchemaClassProxy#truncate`.
`git show 1dd9c0424f:…/SchemaClassProxy.java` confirms `truncate` already called
`resolveForWrite()` at the track base, so the tx-local seed is pre-existing; this track adds
the `recordWriteTarget` mark and the `isWriteTransaction` schema signal that together route a
truncate-only tx onto the schema-carry commit. Reconcile/resolve/promote on an empty diff is
correct, so the impact is serialization + one extra record write, not a wrong result.

#### C3 — nullable snapshot deref (CONFIRMED nullable; unreachable on open session)
Source read: `MetadataDefault.getImmutableSchemaSnapshot()` is `@Nullable` with a
`return null` arm; the two Step-6 sites dereference without a guard. The schema is always
materialized on an open session, so the null arm is unreachable in production — recorded as
the matching deferred Step-6 suggestion.

### Refutation summary (Phase-4 roster)
- **Four-lock order / deadlock (REFUTED).** `commitSchemaCarry` takes
  `committedSchema.acquireSchemaWriteLock` → `indexManager.acquireExclusiveLockForCommit` →
  `stateLock.writeLock`. PSI confirms the two `*ForCommit` methods have only the one
  commit caller. `SchemaShared.reload` (the data-path racer) takes `SchemaShared.lock` then
  `session.load → stateLock.readLock` — same `SchemaShared.lock`-before-`stateLock` order, so
  acquisition stays acyclic. No path takes `stateLock` then `SchemaShared.lock`.
- **Read→write lock upgrade in commit (REFUTED).** `makeThreadLocalSchemaSnapshot()` (top of
  `commit`, before the write lock) calls `makeSnapshot()` which acquires *and releases* the
  schema read lock within a try/finally, holding nothing across; no upgrade.
- **Promotion re-entrancy (REFUTED).** `committedSchema.fromStream` during promotion
  re-acquires `committedSchema.lock.writeLock()` re-entrantly (a `ReentrantReadWriteLock`,
  already held by `commitSchemaCarry`); its tail save arm (`if (!hasGlobalProperties)`) is
  not taken because the just-written root carries global properties. `releaseSchemaWriteLock(…, false)`
  suppresses the save.
- **`rootPayloadDiffersFrom` slot ordering (REFUTED).** `fromStream` rebuilds `properties`
  by `properties.set(prop.getId(), prop)`, so the tx-local copy preserves the committed
  index→slot mapping; with the append-only global table the slot-by-slot comparison is valid.
- **Spurious `markClassChanged` from reads (REFUTED).** All `resolveForWrite` callers are
  mutators except `truncate` (BC2); `getCollectionSelection` and other reads use `resolve()`,
  which the diff confirms never calls `recordWriteTarget`. No read records a change.
- **Snapshot visibility after `forceSnapshot` (REFUTED as a bug; performance only).** A hot
  reader hitting a null snapshot rebuilds via `makeSnapshot → acquireSchemaReadLock`, which
  blocks behind the held schema write lock only in the narrow window where a second commit
  starts before any reader rebuilt. The result is correct; the residual stall is bounded by
  the low schema-change rate and is a performance concern, not a correctness one.
- **Commit-window leak across pooled threads (REFUTED).** `exitCommitWindow` clamps the
  depth at zero and `remove()`s the ThreadLocal at zero; `enter`/`exit` are balanced by
  `commitSchemaCarry`'s try/finally. PSI confirms the only production caller is
  `commitSchemaCarry`.
