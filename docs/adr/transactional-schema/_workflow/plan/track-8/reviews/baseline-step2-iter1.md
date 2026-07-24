# Code-baseline review — Track 8 Step 2, commit `908a2374e6` (iteration 1)

- **Reviewed commit:** `908a2374e6` "Persist bootstrap-valid empty-schema root at creation"
  (branch `transactional-schema`, HEAD `fbbf661d3e` at review time).
- **Diff scope (verified via `git show 908a2374e6 --stat`):**
  `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java`
  (+17/−1, all inside `create`) and new test class
  `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/GenesisSchemaBootstrapTest.java`
  (+121). No other file touched.
- **Binding spec:** track-8.md Step 2; track-8-design-drafts.md §G2.a, Q-G3 ruling (drafts:579-621),
  FM-G1 (drafts:209), pin G.5 #1/#2(a) (drafts:226-231).
- **Perspective:** code baseline — correctness/bugs, blast radius, independent Q-G3 re-trace,
  test quality. Read-only review; no build was run (per charter).

---

## 1. Decision criteria

A finding is a **defect** only if a concrete counterexample path exists (inputs + call sequence +
observable wrong outcome). Verdicts below are preceded by numbered premises with file:line
citations. Null verdicts (no defect) are stated with the justification that closes them.
Severity: blocker (breaks the step's contract or ships a corruption path) / should-fix (real
hazard, bounded) / suggestion (hardening, hygiene, documentation).

The four charter questions:

- **Q-A**: Does the identity-assignment-before-`toStream` mechanism hold on **every** path through
  `computeInTx` (incl. retry/rollback)?
- **Q-B**: Does `toStream` under create-context write **exactly** the shape `fromStream`/`copyForTx`
  require (field-by-field), and does the change alter any non-genesis save path?
- **Q-C**: Is the Q-G3 GREEN verdict (no IM-root symmetric fix) correct — independent re-trace of
  every IM-root touch point?
- **Q-D**: Do the 3 tests pin the claimed properties, fail on regression, and is the
  fresh-instance reconstruction a faithful proxy for the mid-genesis virgin state?

---

## 2. Q-A — the identity-before-toStream mechanism, path by path

### Premises

1. New code (`SchemaShared.create`, SchemaShared.java:1387-1413): inside
   `session.computeInTx`, `root = session.newInternalInstance()` (:1391), `identity =
   root.getIdentity()` (:1403), `return toStream(session)` (:1404); after the tx,
   `this.identity = entity.getIdentity()`; `setSchemaRecordId(entity.getIdentity().toString())`
   (:1407-1408). The whole method runs under `lock.writeLock()` (:1388).
2. `newInternalInstance` (DatabaseSessionEmbedded.java:2109-2126) allocates an `EntityImpl` with a
   fresh `ChangeableRecordId` carrying the `internal` collection id and an invalid (provisional)
   position, and registers it in the current tx as `RecordOperation.CREATED` (:2122-2123). The
   `internal` collection exists from storage birth (`AbstractStorage.doCreate`), so
   `getCollectionIdByName` (:2114) resolves.
3. `RecordAbstract.getIdentity()` returns the record's own mutable rid instance
   (RecordAbstract.java:94-95), so the `identity` field aliases the record's
   `ChangeableRecordId` — the identical aliasing the PRE-change code produced via
   `this.identity = entity.getIdentity()` after commit (85e517ed1f, old create).
4. `toStream(session)` (SchemaShared.java:1076-1080 → :1101) asserts the schema write lock is held
   (:1109-1110 region) — satisfied by premise 1 — and loads the root via
   `session.load(identity)` (:1126).
5. `session.load` → `currentTx.loadRecord(rid)` (DatabaseSessionEmbedded.java:4385-4391).
   `FrontendTransactionImpl.loadRecord` (FrontendTransactionImpl.java:520-535) consults
   `getRecord(rid)` FIRST (:527), which resolves through `recordOperations`
   (HashMap, :93) plus an identity map (:94) via `getRecordEntry` (:1525-1537). The lookup key is
   the very same `ChangeableRecordId` instance stored at registration
   (`recordOperations.put(record.getIdentity(), txEntry)`, :637), and
   `ChangeableRecordId.equals`/`hashCode` fall back to `tempId` equality for non-persistent ids
   (ChangeableRecordId.java:227-266), so the just-created record is served from the tx — storage
   is never consulted for the provisional rid.
6. `computeInTx` → `computeInTxInternal` (DatabaseSessionEmbedded.java:5303-5315): `begin()`,
   run the lambda **exactly once**, `finishTx(ok)`. There is **no optimistic-retry loop** at this
   level — the "tx retry" case the charter asks about does not exist in this machinery.
   `finishTx` (:5226-5238) commits on `ok`, otherwise rolls back.
7. On commit, the `ChangeableRecordId` promotes **in place**
   (`setCollectionAndPosition`, ChangeableRecordId.java:121-140), so both the `identity` field
   (aliased, premise 3) and the returned `entity.getIdentity()` observe the persistent rid before
   `setSchemaRecordId` stringifies it.
8. `create` is reachable from exactly one production call chain:
   `DatabaseSessionEmbedded.internalCreate` (:572-586) → `createMetadata` (:598-603) →
   `SharedContext.create` (SharedContext.java:195-199) → `schema.create(session)`. No transaction
   is active there (unlike `loadMetadata` :605-612, which wraps in `executeInTx`), so the
   `computeInTx` is top-level. `DatabaseImport` calls `security.create`
   (DatabaseImport.java:404 region), never `schema.create` — checked.

### Path enumeration through `computeInTx`

| # | Path | Outcome | Verdict |
|---|------|---------|---------|
| P1 | Happy path: lambda runs, commit succeeds | Root record + payload written in ONE atomic commit; rid promotes in place (premise 7); `setSchemaRecordId` records the persistent rid | ✓ correct |
| P2 | Lambda throws (load/serialization failure) | `finishTx(false)` → rollback; exception propagates; nothing persisted, no schema rid recorded; **`identity` field left aliasing the rolled-back provisional rid** (new vs pre-change `null`) | see BG13 |
| P3 | Commit itself throws | Same as P2 (atomic-op rollback); same `identity` residue | see BG13 |
| P4 | Retry of the lambda | **Unreachable** — no retry machinery exists (premise 6) | ✓ vacuous |
| P5 | Nested tx (an outer tx already active) | **Unreachable today** (premise 8). If a future caller nested it, `finishTx`'s "commit" only decrements the nesting counter — the rid would still be provisional when `setSchemaRecordId` stringifies it, and the same-tx atomicity claim would break | see CQ15 |
| P6 | Crash between commit and `setSchemaRecordId` | Root durable but config rid unset → next open: `load` (:1366-1385) reads an invalid rid → `SchemaNotCreatedException` (:1371-1374) — loud refusal. Identical window existed pre-change; half-create condemnation is Step 3's §A1 (out of scope here) | ✓ no regression |

**Verdict Q-A:** the mechanism holds on every reachable path. The lambda executes exactly once;
the tx serves the provisional rid; the rid promotes in place at the single commit. Two bounded
residual observations → BG13 (dangling `identity` on failure) and CQ15 (unasserted top-level-tx
precondition), both suggestion-severity, with the counterexample analysis below.

---

## 3. Q-B — exact shape written, and blast radius

### Field-by-field trace (create-context `toStream(session)` = full-write path, `changedClassNames == null`, `writeRootPayload = true`)

At create time the fresh `SchemaShared` has: `classes` empty, `properties` empty
(SchemaShared.java:171), `blobCollections` empty (:173), `collectionCounter` 0 (:155). Therefore:

| Field | Written by | Value | `copyForTx` requirement (SchemaShared.java:279-321) | `fromStream` requirement (:881-1062) |
|---|---|---|---|---|
| `schemaVersion` | :1233 | `CURRENT_VERSION_NUMBER` = 6 (:71) | n/a | :885-905 — non-null ✓, `== 6` ✓ (silences the "schema is empty" branch :887-894; see §6 note) |
| `globalProperties` | :1234-1240 | **empty EMBEDDEDLIST, present** | :300-301 assert `!= null` ✓ — the pinned precondition | :907-919 — `hasGlobalProperties = true` (:909-911), so the legacy self-save at :1056-1059 (which would throw `SchemaException` inside an active tx, `saveInternal` :1513-1529) never fires ✓ |
| `classes` link set | **not written** — `realClasses` empty so the per-class loop (:1154-1200) never calls `getOrCreateLinkSet` (:1146, :1168); `getLinkSet` read (:1139) returns null without dirtying | absent | re-parse loads linked class records via null-guarded `getLinkSet` (:930-931) → absent parses as zero classes ✓ | same ✓ |
| `collectionCounter` | :1241 | 0 | n/a | :1043-1049 — non-null → assigned; the `initCollectionCounterFromExisting` fallback is skipped ✓ |
| `blobCollections` | :1243-1244 | empty EMBEDDEDSET, present | n/a | :1053-1055 — `hasProperty` true → parsed as empty set ✓ |

Also verified inside the create-context `toStream` run: the changed-class asserts hold vacuously
(`changedLower == null`, :1204-1210, helper :1268-1285); the drop loop iterates an empty
`previouslyLinked` (:1214-1224); `linkSetChanged == false` but `changedLower == null` selects the
payload write (:1231-1232). No record other than the root is loaded or dirtied.

**Design-letter deviation, dispositioned:** G2.a's parenthetical says "empty `classes` link set"
(drafts:104-106); the implementation leaves the property **unallocated**. This is forced by the
same spec's mandated mechanism ("route the initial write through `toStream` itself",
drafts:108-110) — `toStream`'s empty shape never allocates the set — and both consumers parse
absence as empty (:930-931). The Step 2 episode records this explicitly (track-8.md Step 2
episode, "NO `classes` link set"). Not a defect.

### Blast radius on non-genesis paths — NULL verdict, justified

1. The production diff is confined to `create` (:1387-1413). `toStream` (:1076-1247),
   `saveInternal` (:1513-1531), the selective commit write, `load` (:1366-1385), `reload`, and
   `fromStream` are byte-identical to the parent commit (verified via `git show 908a2374e6`).
2. Existing databases are untouched — no migration/upgrade logic exists in the diff, and every
   completed-genesis database already carries a populated root (each legacy DDL self-commit
   rewrote it through the same `toStream`).
3. The only new observable: a fresh database's root record carries the empty-schema payload from
   its first (and only) creation commit — same number of commits, same record version as before,
   just non-empty content. All root readers were enumerated: `fromStream`/`copyForTx` (above),
   `SQLTarget` metadata target (rid only, SQLTarget.java:235-248), `JSONSerializerJackson`
   identity comparison (:771-776), `DatabaseExport` info section (rid string only,
   DatabaseExport.java:381-384), `DatabaseImport` skip-set (identity only,
   DatabaseImport.java:1192-1216), `DatabaseCompare` (identity only, :687-691). None has a
   shape precondition that the new payload could violate; none required the payload before and
   breaks on it now.

**Verdict Q-B:** exact-shape ✓; zero non-genesis blast radius. Null verdict closed on premises
1-3.

---

## 4. Q-C — independent Q-G3 re-trace (IM root shell)

**Claim under test:** every reader/writer of the empty `IndexManagerEmbedded` root shell
tolerates it; no symmetric fix needed; production `IndexManagerEmbedded.java` correctly left
untouched.

Exhaustive enumeration of IM-root touch points (every `CONFIG_INDEXES` use and every load of
`indexManagerIdentity` in production code — grep-verified: `CONFIG_INDEXES` appears only at
IndexManagerAbstract.java:52,235,256 and IndexManagerEmbedded.java:1187; `indexManagerIdentity`
loads only at IndexManagerEmbedded.java:119,134,1186 and IndexManagerAbstract.java:255):

| # | Touch point | Mechanism | Empty-shell tolerant? |
|---|---|---|---|
| T1 | `IndexManagerEmbedded.load` :110-126 → `IndexManagerAbstract.load` :231-246 | `entity.getLinkSet(CONFIG_INDEXES)` **null-guarded** (:235-236) | ✓ (pinned by test 3) |
| T2 | `IndexManagerEmbedded.reload` :128-140 → same parser | same null guard | ✓ |
| T3 | `IndexManagerAbstract.addIndexInternalNoLock(updateEntity=true)` :253-257 (legacy create path; also the crash-recreate path via `RecreateIndexesTask` → `addIndexInternal(..., true)`) | `getOrCreateLinkSet` (:256) — allocates on demand | ✓ |
| T4 | `IndexManagerEmbedded.enrollReconciledIndexRecords` :1186-1187 (commit-time reconciliation — the genesis phase-1 commit arm) | `getOrCreateLinkSet` (:1187) — allocates on demand | ✓ (trace-only until Step 3 exercises it end-to-end, as the test Javadoc honestly states) |
| T5 | `IndexManagerEmbedded.create` :608-621 | writes the empty shell; no read | ✓ (writer) |
| T6 | Identity-only consumers: `JSONSerializerJackson` :771-776, `SQLTarget` :242-248, `DatabaseExport` :383-384, `DatabaseImport` :1196-1200, `DatabaseCompare` :690-691 | compare/emit the rid; never parse the payload | ✓ |
| T7 | `autoRecreateIndexesAfterCrash` arm (:1632-1645) + `getIndexesConfiguration` :1576-1585 | reads the **in-memory** `indexes` map, not the root record | ✓ (out of root-record scope) |

**Asymmetry with the schema root confirmed:** the IM parser has no version field, no
`ConfigurationException` version gate, and — decisively — no re-parse-with-self-save branch. The
schema-side hazard exists precisely because `copyForTx`'s re-parse of a payload-less root would
route into `fromStream`'s missing-globalProperties self-save (:1056-1059), which throws inside
the active user transaction — hence the :300-301 assert. Nothing analogous exists on the IM side;
the tx-local index view is an overlay over in-memory maps (`getIndexes(session)`,
IndexManagerEmbedded.java:567-581), never a root-record re-parse.

**Verdict Q-C:** GREEN verdict **confirmed** — leaving `IndexManagerEmbedded` untouched was
right. My enumeration found two touch-point classes the episode's summary didn't name (T6, T7);
both are payload-blind, so they strengthen rather than weaken the verdict.

---

## 5. Q-D — test quality

Premises for regression power (test builds run `-ea`: core/pom.xml:36 `<argLine>-ea …`; surefire
assertions on):

1. **`virginDbSchemaTransactionSeedsCleanly` (pin G.5 #1):** on regression to a bare-entity root,
   `copyForTx`'s assert (:300-301) throws `AssertionError` → red. Red-first at 85e517ed1f is
   code-verified: the pre-fix `create` (85e517ed1f SchemaShared.java:1387-1398) persisted a bare
   `newInternalInstance`, which carries no `globalProperties`. ✓
   *Caveat (TQ15):* with assertions **disabled**, the regression is silent for this test —
   `fromStream` early-returns on the null `schemaVersion` (:886-894) and the copy comes back
   empty, passing all three of its asserts. Test 2 is the assert-independent net (its
   `schemaVersion`/`globalProperties` asserts fail regardless of `-ea`), so the class as a whole
   still catches the regression in any build shape. No action strictly required.
2. **`createdRootPersistsAsBootstrapValidEmptySchema` (pin G.5 #2(a)):** pins all five facets of
   the persisted shape, including the absent-`classes` deviation (`assertNull(getLinkSet)`), and
   fails on any facet's regression (red-first signature `schemaVersion expected:<6> but
   was:<null>` is code-consistent). ✓
3. **`emptyIndexManagerRootShellToleratesReopenLoad` (Q-G3 empirical arm):** pins T1 (the load
   arm). It was green at pre-fix HEAD by design — it pins the *current tolerance* so a future
   parse-precondition regression in `IndexManagerAbstract.load` turns it red. The commit arm (T4)
   is trace-only until Step 3; the test Javadoc says exactly that. Honest and adequate. ✓

**Fidelity of the fresh-instance reconstruction:** the proxy is faithful in what matters. The
real mid-genesis virgin state is: a committed bootstrap root + a schema instance with no classes
+ the SAME session that created it. The test reproduces all three (fresh `SchemaEmbedded`,
`create(session)`, same-session `copyForTx`). Notably, the production genesis phase-1 seed will
ALSO read the root through the session's local record cache (`executeReadRecord` cache hit,
DatabaseSessionEmbedded.java:2239-2266; the fresh-committed-read refresh skips the re-fill when
versions match, :3779-3807), so the test's cache-served read matches production semantics rather
than diverging from them. Contexts the test lacks vs. production: (a) mid-genesis there is no
admin user and the seed routes through `SchemaProxy.resolveForWrite()`/`ensureTxSchemaState`
rather than a direct `copyForTx` call — equivalent for the pinned precondition, and Step 3's pins
(#2(b), #3, #4) own the end-to-end path; (b) no test ever **deserializes the persisted bytes** of
the bootstrap root in a fresh context — see TQ14.

---

## 6. Interim diagnosability note (confirming a recorded disposition, not a finding)

With Step 2 landed and Step 3 not yet, a crash between `SchemaShared.create` and the first
genesis DDL leaves a database whose reopen now parses the bootstrap root **cleanly** —
`fromStream`'s "schema is empty" breadcrumb (:887-894) no longer fires, so the half-genesis
corpse opens silently until Step 3's completion marker lands. This is exactly the CS35 window
the design accepted (drafts:113-117) and the commit message and episode both record ("deliberately
superseded by Step 3's genesis-completion marker"). Confirmed as a knowingly-armed in-flight
window, matching the CS48 precedent from Step 1. No action.

---

## 7. Findings

### BG13 — suggestion — `SchemaShared.create` leaves `identity` aliasing a rolled-back provisional rid on create failure
**Location:** SchemaShared.java:1403 (assignment inside the lambda), vs. :1407 (post-commit).
**Premises:** (1) pre-change, a failed create left `identity == null`; post-change, P2/P3 leave it
pointing at the provisional rid of a record that no longer exists in any transaction. (2) The
assignment must happen pre-`toStream` (the load at :1126 keys off it), so the exposure is
inherent to the chosen mechanism.
**Counterexample gist:** commit throws inside `finishTx` → `create` rethrows → `getIdentity()`
(:1436-1442) now returns a non-null, non-persistent rid on a schema instance that was never
created.
**Why only suggestion (exhaustive consumer check):** the exception aborts DB creation entirely;
the enumerated downstream consumers of a stale `identity` are all unreachable or self-healing —
`load` reassigns from storage config and throws `SchemaNotCreatedException` on the unset rid
(:1367-1374); a hypothetical `create` retry on the same instance reassigns at :1403;
`copyForTx`/`toStream` are unreachable without a created schema; the half-created storage is
Step 3 §A1's cleanup target. **Suggested hardening:** restore `identity = null` on the failure
path (or note the residue in the comment) so the field never dangles.

### CQ15 — suggestion — the atomicity claim silently depends on an unasserted top-level-transaction precondition
**Location:** SchemaShared.java:1390-1404 (comment + mechanism); DatabaseSessionEmbedded.java:5303-5315 (`computeInTxInternal` nesting semantics).
**Premises:** (1) if a transaction were already active, `computeInTx`'s "commit" merely
decrements the nesting counter — the root would not be durable and the `ChangeableRecordId`
would still be provisional when `setSchemaRecordId(entity.getIdentity().toString())` (:1408)
stringifies it, persisting a provisional rid string into the storage config and voiding the
"no crash point can expose a payload-less root" claim. (2) Unreachable today (premise 8 of §2),
and the design pins `schema.create` **before** the genesis tx (drafts WC4, :115-118 region:
"the bootstrap root pre-exists the genesis tx"). (3) Step 3 rewires `SharedContext.create` into
the phase-1 tx — the exact refactor that could accidentally swallow `schema.create` into it.
**Counterexample gist (future misuse):** Step 3 wraps `SharedContext.create` lines 199+ wholesale →
`setSchemaRecordId("#0:-2…")` → every subsequent open dies in `load`'s rid parse — a bricked DB
from a one-line boundary mistake.
**Suggested hardening:** `assert !session.getTransactionInternal().isActive()` at :1389 (create
entry), or a one-line comment naming the top-level precondition; and the Step 3 reviewer should
verify `schema.create`/`indexManager.create` stay pre-tx (carry alongside the existing CS47/TQ12
Step 3 obligations).

### CQ16 — suggestion — repointing-containment comment exists only on the IM test
**Location:** GenesisSchemaBootstrapTest.java:40-41/:69-70 (schema tests) vs. :110-112 (IM test).
`SchemaShared.create` repoints the test database's schema record id
(`setSchemaRecordId`, :1408) exactly as `IndexManagerEmbedded.create` repoints the IM rid — the
IM test carries the "contained: per-test database" comment; the two schema tests perform the same
global repointing silently. Contained in fact (per-test DB, dropped afterwards; the session's
in-memory `SharedContext` schema never re-reads the config rid within the test). Add the same
one-line containment note for parity so a future reader doesn't re-derive the safety argument.

### TQ14 — suggestion — no test deserializes the persisted bootstrap-root bytes in a fresh context
**Location:** GenesisSchemaBootstrapTest.java:69-97 (test 2), :40-55 (test 1).
**Premises:** (1) both tests read the root in the same session that created it; the read is
served by the session-local record cache (`executeReadRecord` cache hit,
DatabaseSessionEmbedded.java:2239-2247), and even test 1's fresh-committed-read scope skips the
byte re-fill when the cached version equals the on-disk version (:3798-3801). (2) So a
serialize/deserialize asymmetry (e.g. an empty EMBEDDEDLIST property that failed to round-trip as
*present*) would pass all three tests and surface only at a reopen. (3) Mitigations: the
production mid-genesis consumer has the SAME same-session cache semantics (fidelity, not a gap);
the only byte-parsing consumer of a *virgin* root is the mid-genesis-crash reopen, which Step 3's
completion marker condemns, and the full-suite + IT runs exercise the serializer's empty-embedded
handling pervasively. **Suggested hardening (optional):** a close/reopen leg (fresh
`SchemaEmbedded().load(session)` against the virgin rid in a new session, mirroring what the IM
test *names*) would pin the byte-level shape directly; alternatively accept as covered by Step
3's pin G.5 #2(b).

### TQ15 — suggestion (accept as-is) — test 1's red signature is `-ea`-dependent
**Location:** GenesisSchemaBootstrapTest.java:40-55; core/pom.xml:36.
With assertions disabled, the pre-fix behavior degrades to a silently-empty copy
(`fromStream` :886-894 early return) and test 1 passes vacuously. Test 2's asserts are
assert-independent, so the class as a whole retains regression power in any build shape. No code
change needed; recorded so a future refactor doesn't delete test 2 believing test 1 subsumes it.

---

## 8. Alternative-hypothesis check

- *"The fix should have written the payload via a second `save` instead of inside the create
  tx"* — rejected: a two-tx shape would open exactly the crash window the design forbids
  (payload-less root durable between the txs); the implemented single-tx shape is strictly
  stronger and matches G2.a's mechanism mandate.
- *"`toStream` mutating shared state at create time could leak into other sessions"* — rejected:
  no classes exist, so the only mutations are the root-entity property writes on a tx-local
  record; the schema write lock (:1388) excludes all readers of `identity` (`getIdentity`
  takes the read lock, :1436-1442).
- *"The Q-G3 GREEN verdict might hold only for the paths the episode sampled"* — tested by
  enumerating ALL `CONFIG_INDEXES`/`indexManagerIdentity` uses (§4, T1-T7), including five
  consumer classes the episode didn't list; all payload-blind. Verdict survives.
- *"Record-version drift could break version-pinned fixtures"* — rejected: pre- and post-change
  the root is written once, in the same creation commit; the payload rides that same version.
- *"`setCollectionId`'s id-vs-position comparison bug (ChangeableRecordId.java:87-89) could
  misfire for the root rid"* — checked: `newInternalInstance` calls `setCollectionId(0)` against
  position −1, so the buggy `collectionId == collectionPosition()` short-circuit does not
  trigger for the internal collection; pre-existing code, out of this diff's scope.

## 9. Hypothesis log

| # | Hypothesis | Method | Outcome |
|---|---|---|---|
| H1 | tx retry re-runs the lambda with a stale `identity` | read `computeInTxInternal` :5303-5315 | no retry machinery — vacuous |
| H2 | provisional-rid load misses the tx record | traced `loadRecord` :520-535, `getRecordEntry` :1525-1537, `ChangeableRecordId.equals` :227-252 | served from tx by tempId/identity — holds |
| H3 | rollback leaves `identity` dangling | path P2/P3 | confirmed, bounded → BG13 |
| H4 | nested-tx caller breaks atomicity + persists provisional rid string | traced `finishTx`/nesting + call-chain :572-603 | unreachable today; Step-3 hazard → CQ15 |
| H5 | create-context `toStream` shape mismatches `fromStream`/`copyForTx` | field-by-field table §3 | exact match — null |
| H6 | non-genesis save paths altered | diff inspection + reader enumeration §3 | confined to `create` — null |
| H7 | Q-G3 missed an IM-root reader | exhaustive grep + trace §4 T1-T7 | none chokes — GREEN confirmed |
| H8 | tests pass under regression | premise trace §5 | test 2 is the robust net; test 1 `-ea`-dependent → TQ15 |
| H9 | fresh-instance proxy diverges from mid-genesis state | cache-semantics trace §5 | faithful; byte-parse gap → TQ14 |
| H10 | CS35 breadcrumb silencing ships an undispositioned regression | §6 | recorded/accepted window — confirmed disposition |
| H11 | schema-rid repointing in tests corrupts the shared test DB | per-test DB + no config re-read in-session | contained → CQ16 (comment parity only) |
| H12 | `advanceVersion`/snapshot staleness from the new create path | create sets `snapshot = null` (:1409) as before | unchanged — null |
| H13 | crash between commit and `setSchemaRecordId` newly harmful | path P6 | identical pre-existing window, loud on reopen — null |

## 10. Verdict summary

- **Blockers:** none.
- **Should-fix:** none.
- **Suggestions:** BG13, CQ15, CQ16, TQ14, TQ15.
- The step's contract (G2.a / FM-G1 / pin G.5 #1, #2(a) / Q-G3 verify-first) is implemented as
  specified; the Q-G3 GREEN ruling is independently confirmed; the red-first claims are
  code-consistent with the pre-fix HEAD.
