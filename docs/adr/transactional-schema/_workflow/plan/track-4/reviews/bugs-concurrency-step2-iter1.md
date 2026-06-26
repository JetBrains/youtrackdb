<!--
MANIFEST
dimension: bugs-concurrency
step: 4.2
commit_range: abba0e178b~1..abba0e178b
verdict: pass-with-findings
blocker_count: 0
findings_total: 4
evidence_base: psi
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-should-fix-setabstractinternal-still-eagerly-self-commits-a-collection-on-the-tx-local-alter-path"
    loc: "core/.../schema/SchemaClassEmbedded.java:564,595"
    cert: C1
    basis: psi
  - id: BC2
    sev: suggestion
    anchor: "#bc2-suggestion-getresolvedcollectionid-sentinel-overloads-abstract_collection_id"
    loc: "core/.../schema/TxSchemaState.java:90,152"
    cert: C2
    basis: read
  - id: BC3
    sev: suggestion
    anchor: "#bc3-suggestion-provisional-allocator-has-no-overflow-or-exhaustion-guard"
    loc: "core/.../schema/TxSchemaState.java:126-128"
    cert: C3
    basis: read
  - id: BC4
    sev: suggestion
    anchor: "#bc4-suggestion-getresolvedcollectionids-leaks-the-mutable-backing-map"
    loc: "core/.../schema/TxSchemaState.java:161-164"
    cert: C4
    basis: psi
-->

## Findings

### BC1 [should-fix] setAbstractInternal still eagerly self-commits a collection on the tx-local alter path

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassEmbedded.java` (line 564, and the abstract branch at 595)

**Issue**: This step inverts the eager allocation on the *create* path, but the sibling *alter* path `setAbstractInternal` still self-commits a real collection during a transaction. `SchemaClassProxy.setAbstract` routes through `resolveForWrite()` to the tx-local copy, so `setAbstractInternal` runs against the tx-local schema while a user transaction is active. The make-non-abstract branch (line 562-564) calls `database.addCollection(collectionName)` directly — the eager, self-committing allocation. So an in-transaction `class.setAbstract(false)` allocates a durable real collection that survives the user transaction's rollback as an orphan, the exact stray-collection-on-rollback defect this step removes from the create path. It also produces a real id on a class while a tx-created class on the create path carries a provisional id — a provisional/real mix within one transaction.

The implementer flagged this out of scope, and for the step's own acceptance it is deferrable: `setAbstractInternal` is pre-existing and unchanged, so this step did not regress it, and the step's create-path behavior is internally consistent. The make-*abstract* branch is already safe under provisional ids (traced below). But the make-non-abstract eager `addCollection` is a live, reachable defect of the same family the track is closing, so it should be tracked explicitly, not left silent.

**Evidence**: PSI `resolveForWrite()` routing — `SchemaClassProxy.setAbstract` → `resolveForWrite().setAbstract(session, …)` → `SchemaClassEmbedded.setAbstractInternal`. Make-abstract branch trace under a provisional id (class created in-tx, id `-2`): line 539 `defaultCollectionId(-2) != NOT_EXISTENT_COLLECTION_ID(-1)` is true; `tryDropCollection(-2)` calls `getCollectionNameById(-2)` which returns `null` for `< 0`, so the name-equality guard fails and no drop runs — safe, no real collection touched. Make-non-abstract branch (abstract class altered to concrete in-tx): line 558 `!abstractClass` is false, falls through to line 564 `database.addCollection(...)` — eager real-collection allocation inside the tx.

**Refutation considered**: I checked whether `setAbstract` is reachable on the tx-local path at all (it is — proxy `resolveForWrite()`), whether the make-abstract branch mishandles a provisional id (it does not — `getCollectionNameById` returns null for negatives so `tryDropCollection` no-ops), and whether the step *introduced* this (it did not — `setAbstractInternal` is untouched, pre-existing). It survives as a should-fix only because it is the same eager-self-commit class the track exists to eliminate and is reachable in-tx today; it is not a blocker for this step because the step's create-path scope is self-consistent and the alter inversion is a separate unit of work.

**Suggestion**: Record the make-non-abstract eager `addCollection` (and, for symmetry, any future make-abstract real-collection drop) as a tracked follow-up in the Track decision log / a sibling step, so the alter path receives the same provisional treatment the create path just got. No code change required in this step.

### BC2 [suggestion] getResolvedCollectionId sentinel overloads ABSTRACT_COLLECTION_ID

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (line 90, read at 152)

**Issue**: The not-resolved sentinel for `getResolvedCollectionId` is `provisionalToReal.defaultReturnValue(SchemaShared.ABSTRACT_COLLECTION_ID)` — i.e. `-1`. The reasoning is sound (a real id is always `>= 0`, so `-1` is unambiguous as "not resolved"), and there is no correctness bug: `-1` can never be a stored real id. The concern is semantic overloading — `-1` is the *abstract-class marker* in this codebase, and reusing it as the resolution sentinel means a caller that does not read the Javadoc could misread a not-resolved result as "this provisional id resolved to the abstract collection". The commit-time consumer in Step 3 will read this; a dedicated sentinel name (or a distinct value like `Integer.MIN_VALUE`, or returning `OptionalInt`) would remove the ambiguity at the point of use.

**Evidence**: Line 90 sets the default return value to `ABSTRACT_COLLECTION_ID`; line 152 `getResolvedCollectionId` returns `provisionalToReal.get(...)` which yields that default on a miss. The test `txSchemaStateProvisionalToRealCarrierRoundTrips` asserts the sentinel as `ABSTRACT_COLLECTION_ID` directly, baking the overloading into the test contract.

**Refutation considered**: I confirmed real ids are non-negative (`recordResolvedCollectionId` asserts `realCollectionId >= 0`) and provisional ids are `<= -2`, so `-1` collides with neither a real nor a provisional id. There is no value-collision bug. This is a readability/maintainability concern only — hence suggestion, not should-fix.

**Suggestion**: Introduce a named constant such as `NO_RESOLUTION = Integer.MIN_VALUE` (or expose `getResolvedCollectionIds()` and let Step 3 use `containsKey`) so the not-resolved signal does not share a value with the abstract marker. If the value stays `-1`, the consumer in Step 3 must test resolution by `containsKey`, never by `== -1`, since `-1` is also a meaningful schema value elsewhere.

### BC3 [suggestion] Provisional allocator has no overflow or exhaustion guard

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (line 126-128)

**Issue**: `allocateProvisionalCollectionId` does `return nextProvisionalCollectionId--;` with no lower bound. After `Integer.MIN_VALUE` it wraps to `Integer.MAX_VALUE` (a positive value), which would then be classified as a *real* id by `isProvisionalCollectionId` (`<= -2` is false for a positive number) and silently treated as a real collection id at commit. This requires ~2.1 billion collection allocations in one transaction to reach, so it is not a practical hazard, but the allocator is an unguarded decrement and the failure mode (a provisional id that stops looking provisional) is silent corruption rather than a clean failure.

**Evidence**: Line 71 `nextProvisionalCollectionId = PROVISIONAL_COLLECTION_ID_CEILING` (`-2`); line 127 `return nextProvisionalCollectionId--`. No bound check. `isProvisionalCollectionId(int)` returns `id <= -2`, so a wrapped positive id fails the provisional test.

**Refutation considered**: The transaction-scoped lifetime makes ~2^31 allocations in a single tx the only trigger, which is unreachable in practice. This is why it is a suggestion, not a defect. The note is worth keeping because the value is the substrate for a five-item commit patch list, and a silent reclassification at the boundary is harder to debug than an assertion.

**Suggestion**: Add `assert nextProvisionalCollectionId < 0 : "provisional collection id space exhausted"` (or a thrown `IllegalStateException`) before the decrement, matching the assert discipline the class already uses in `recordResolvedCollectionId`.

### BC4 [suggestion] getResolvedCollectionIds leaks the mutable backing map

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (line 161-164)

**Issue**: `getResolvedCollectionIds()` returns the live `Int2IntOpenHashMap` backing instance (the Javadoc says so and asks callers not to mutate it outside `recordResolvedCollectionId`). This mirrors the existing `getChangedClasses()` convention in the same class, so it is consistent with the codebase. The risk is a Step 3 consumer mutating the returned map directly (bypassing the `recordResolvedCollectionId` asserts) and recording an inconsistent provisional→real mapping with no validation. `TxSchemaState` is transaction-confined (one per session per transaction, stored in tx custom data), so there is no cross-thread concurrency hazard here — the concern is encapsulation, not thread safety.

**Evidence**: PSI `getResolvedCollectionIds()` returns `provisionalToReal` directly (the field at line 79). The only current caller is the test; Step 3 is named in the Javadoc as the consumer. `getTxSchemaState()` resolves from `tx.getCustomData(TX_SCHEMA_STATE_KEY)` (transaction-confined, single-threaded per tx).

**Refutation considered**: I confirmed `TxSchemaState` is transaction-scoped and single-threaded (the routing-proxy seam reads/writes it within the owning transaction), so this is not a publication/visibility bug. It matches the class's own `getChangedClasses()` precedent. Hence suggestion, not should-fix.

**Suggestion**: If Step 3 only needs read access, expose a read-only view (`Int2IntMaps.unmodifiable(provisionalToReal)`) or a typed accessor; if it needs the live map for the patch loop, keep as-is but ensure Step 3 routes writes through `recordResolvedCollectionId`.

## Evidence base

The load-bearing reference-accuracy audit (every collection-id sign-test site reachable from the schema in-memory maps and from the file/storage paths, and its correct classification) ran via mcp-steroid PSI against the open `transactional-schema-b4l1mcdq` project, which matches the working tree. PSI find-usages, package-wide PSI binary-expression enumeration, and PSI inheritor/caller search were used; grep was not relied on for any classification claim.

#### C1 — setAbstractInternal eager self-commit (PSI)

CONFIRMED-as-issue (should-fix): `SchemaClassProxy.setAbstract` → `resolveForWrite()` → `SchemaClassEmbedded.setAbstractInternal` routes to the tx-local copy in-tx; the make-non-abstract branch calls eager `database.addCollection` (PSI method body, line 564); the make-abstract branch is safe under provisional ids because `getCollectionNameById(<0)` returns null. Survived refutation: reachable in-tx, same defect family, not introduced by this step but a live latent eager allocation. Basis: PSI routing + method-body trace.

#### C2 — resolution sentinel overloads the abstract marker (read)

CONFIRMED-as-issue (suggestion): `defaultReturnValue(ABSTRACT_COLLECTION_ID)` at line 90 makes the not-resolved sentinel equal to the `-1` abstract marker. No value collision with real (`>= 0`) or provisional (`<= -2`) ids, so no correctness bug; readability/consumer-safety concern only. Basis: direct read of the constructor and `getResolvedCollectionId`.

#### C3 — unbounded provisional allocator (read)

CONFIRMED-as-issue (suggestion): `return nextProvisionalCollectionId--` has no exhaustion guard; ~2^31 allocations in one tx would wrap to a positive value that `isProvisionalCollectionId` reclassifies as real. Unreachable in practice; flagged for the silent failure mode. Basis: direct read.

#### C4 — mutable backing map escapes (PSI)

CONFIRMED-as-issue (suggestion): `getResolvedCollectionIds()` returns the live `Int2IntOpenHashMap`. Transaction-confined, single-threaded per tx (PSI: resolved from `tx.getCustomData`), so no concurrency hazard; encapsulation concern matching the class's own `getChangedClasses()` precedent. Basis: PSI caller search + read.

#### Load-bearing PSI audit — predicate-split completeness (all sites CONFIRMED correctly classified)

Every collection-id sign-test site in the `metadata.schema` package was enumerated via PSI and classified. All were classified correctly by the diff; no in-memory-map site was missed and no file/storage site was wrongly flipped. The roster (survived the refutation check — compressed to one line each):

- In-memory-map sites, correctly flipped to `== ABSTRACT_COLLECTION_ID` (treat `<= -2` as pending-real): `SchemaShared.checkCollectionCanBeAdded:448`, `SchemaShared.addCollectionClassMap:1102`, `SchemaEmbedded.checkCollectionsAreAbsent:401`, `SchemaEmbedded.removeCollectionClassMap:582`, `SchemaEmbedded.addCollectionForClass:600`, `SchemaEmbedded.removeCollectionForClass:626` — exactly the 6 the implementer reported changing.
- File/storage sites, correctly left skipping all negatives: `SchemaClassImpl.renameCollection:1417` (`< 0`, calls `setCollectionAttribute` on storage — the one the implementer reported leaving), `SchemaEmbedded.dropClassInternal:517` (`id != -1` then `deleteCollection`; only reachable on the `!txLocal` legacy path where ids are always real), `DatabaseSessionEmbedded.getCollectionNameById` (`< 0` → null, storage lookup).
- Abstract-detection / not-a-map sites, correctly untouched: `SchemaClassImpl` ctor:102 (`defaultCollectionId == NOT_EXISTENT_COLLECTION_ID`; provisional `[-3,-2]` sorts so `[0] == -3 != -1`, class not falsely marked abstract), `SchemaClassImpl.fromStream:542` (`collectionIds[0] == -1`; a provisional class is `length>=1` with `[0] <= -2`, not falsely abstract), `SchemaClassEmbedded.addCollectionIdInternal:621` / `setAbstractInternal:539` (`defaultCollectionId == NOT_EXISTENT_COLLECTION_ID`; not a reverse-map skip).
- Allocator/validation guards, correct: `SchemaShared.isProvisionalCollectionId:113` (`<= PROVISIONAL_COLLECTION_ID_CEILING`), `TxSchemaState.recordResolvedCollectionId:142` (`realCollectionId >= 0`).

#### Routing-guard correctness (CONFIRMED, survived)

`provisional = txLocal && !session.isSeedingTxSchemaState()`. The committed/non-tx path stays byte-identical: `txLocal` is set true only in `SchemaShared.copyForTx` (PSI: single assignment site), so the committed shared instance has `txLocal == false` and takes the `session.addCollection` branch verbatim. Seeding gets real ids: during `copyForTx → fromStream` re-create, `isSeedingTxSchemaState()` is true, so `provisional` is false and the seed re-creates committed classes through the eager `addCollection` branch (loads existing collections, allocates none new). The guard mirrors the create-path recording guard in `createClassInternal:211`. The abstract-create path bypasses `createCollections` entirely (`doCreateClass:107-112` builds `{-1}` directly when `collections <= 0`), so an in-tx abstract create reads `-1` not a provisional id — pinned by the `inTransactionAbstractCreateStillReadsMinusOne` test.

#### Null safety of the new carrier methods (CONFIRMED, survived)

The two `getTxSchemaState() == null` guards (create path `SchemaEmbedded:368`, drop path mirrored at `:510`) throw `IllegalStateException` rather than NPE on the provisional branch. `getResolvedCollectionId` on a fastutil `Int2IntOpenHashMap` returns the default value on a miss (no null — primitive map). The allocator and recorder operate on primitives. No null-dereference path found.

#### Polymorphic-id propagation under provisional ids (REFUTED — not a bug)

HYPOTHESIS: an in-tx subclass create now carrying provisional ids propagates a provisional id to an indexed superclass via `addPolymorphicCollectionId → addCollectionIdToIndexes → getCollectionNameById(provisional)==null → IndexManagerEmbedded.addCollectionToIndex(session, null, …)`, risking an NPE or a null collection name reaching the shared index. EVIDENCE checked: PSI located the single `addCollectionToIndex` in `IndexManagerEmbedded`; its first statement is `if (recordMembershipChangeIntoTxLocalView(session, indexName)) return;`. OBSERVATION: when a schema transaction is in progress the membership ripple is recorded into the tx-local changed-class set and the method returns before the shared-index path, with an in-code comment explicitly naming the "could name a collection that has only a provisional id during the transaction" case. VERDICT: REFUTED — the tx-local guard (an earlier track's index-manager seam) short-circuits before the null name is used; no NPE, no corruption.

#### Provisional/real mix within a transaction (REFUTED for the create path; the alter path is BC1)

HYPOTHESIS: a class could hold a mix of provisional and real ids in one transaction. EVIDENCE: on the create path every id comes from `allocateProvisionalCollectionId` (all `<= -2`) or all from `addCollection` (all real, non-tx/seeding) — never mixed within one create. The only mix vector is the alter path `setAbstractInternal` make-non-abstract eager `addCollection`, captured as BC1. VERDICT: REFUTED for the create path; the residual alter-path vector is BC1.
