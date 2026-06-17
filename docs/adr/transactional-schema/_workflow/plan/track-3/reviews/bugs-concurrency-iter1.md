<!--
MANIFEST
dimension: bugs-concurrency
step: 3.1
iteration: 1
commit_range: 12f0d99b7a2fc15c34780f6e785310bfbcfa4571~1..12f0d99b7a2fc15c34780f6e785310bfbcfa4571
verdict: PASS-WITH-SUGGESTIONS
finding_count: 4
high_water_mark: 4
evidence_base: "## Evidence base"
cert_index:
  - { cert: C1, anchor: "#### C1" }
  - { cert: C2, anchor: "#### C2" }
  - { cert: C3, anchor: "#### C3" }
  - { cert: C4, anchor: "#### C4" }
flags: []
index:
  - id: BC1
    sev: should-fix
    anchor: "### BC1 [should-fix] copyForTx dirties committed class records into the user transaction and rebinds committed class RIDs"
    loc: "core/.../metadata/schema/SchemaShared.java:152-165, 736-803"
    cert: C1
    basis: "PSI: copyForTx has no production callers yet; toStream mutates committed SchemaClassImpl.setRecordId + dirties loaded class records in the caller tx"
  - id: BC2
    sev: suggestion
    anchor: "### BC2 [suggestion] copy.identity aliases the committed instance's mutable RecordIdInternal reference"
    loc: "core/.../metadata/schema/SchemaShared.java:159"
    cert: C2
    basis: "RecordIdInternal is a sealed mutable type (ChangeableRecordId); identity is shared by reference between committed and copy"
  - id: BC3
    sev: suggestion
    anchor: "### BC3 [suggestion] copy.fromStream can throw on a committed schema with no global properties because saveInternal rejects an active tx"
    loc: "core/.../metadata/schema/SchemaShared.java:160, 717-721, 946-953"
    cert: C3
    basis: "fromStream !hasGlobalProperties branch calls saveInternal which throws when a tx is active; copyForTx runs inside the user tx"
  - id: BC4
    sev: suggestion
    anchor: "### BC4 [suggestion] copyForTx has no precondition guard that a transaction is open"
    loc: "core/.../metadata/schema/SchemaShared.java:152-165"
    cert: C4
    basis: "PSI: only caller is the test, always inside computeInTx; method does record I/O that assumes an open tx but does not assert it"
-->

## Findings

### BC1 [should-fix] copyForTx dirties committed class records into the user transaction and rebinds committed class RIDs

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 152-165, calling `toStream` at 736-803)
- **Issue**: `copyForTx` seeds the copy with `final var serialized = toStream(session)`. `toStream` is a writer against the **committed** instance, not a pure read. For each live committed class it calls `session.load(boundRid)` and then `c.toStream(session, classRecord)`, which sets properties on the loaded class record (`name`, `properties`, `superClasses`, ...) and so marks that committed class record dirty in the **caller's open user transaction**. For any committed class whose `recordId` is null or non-persistent (the `boundRid == null || !boundRid.isPersistent()` arm, line 762), it allocates a fresh record and calls `c.setRecordId(...)` on the **committed** `SchemaClassImpl` object — a permanent mutation of committed shared state performed solely to build a tx-local copy. The net effect is that building the private copy is not side-effect-free against the committed schema: it enrolls every committed class record into the user transaction's dirty set, so a user transaction that made no schema change still re-serializes every class record at its commit, and (in the unbound-class case) the committed in-memory object's identity is rebound under the copy operation.
- **Evidence**: `copyForTx` (152-165) → `toStream(session)` (736). At 759-780, `toStream` iterates `realClasses` (the committed classes), loads or allocates a per-class record, and at 775 calls `c.toStream(session, classRecord)` on the **committed** `c`. `SchemaClassImpl.toStream` (591-626) calls `entity.setProperty(...)` repeatedly, dirtying the loaded record in the active tx. At 770, `c.setRecordId(classRecord.getIdentity())` mutates the committed class object. PSI find-usages confirms `copyForTx` has no production caller yet (only `CopyForTxTest` and Javadoc), so this is the foundation step and the side effect is not yet exercised by a real routing path — but the seam is being established here.
- **Refutation considered**: In steady state after Track 2 every committed class already carries a persistent `recordId`, so the unbound-class RID-rebind arm (762) does not fire and no committed-object mutation occurs; and the content re-serialized into each class record is byte-identical to what is already stored, so a user-tx commit re-persists identical bytes — wasteful, not corrupting. That refutation holds for the corruption scenario, which is why this is should-fix and not a blocker. What survives is real: the seed is documented and reasoned about as a read of the committed graph ("serializes into its root record"), but it dirties committed records into the user tx and can rebind a committed class's RID, which is a correctness-adjacent surprise for the Track-4 promotion/commit step that will consume this seam. The Track-3 commit contract says a no-active-tx schema change keeps the legacy path; this finding is about the in-tx path the copy is built for.
- **Suggestion**: Either document explicitly on `copyForTx` that the seed dirties the committed class records in the caller's transaction (so Track 4's commit-time diff/promotion accounts for the committed records appearing in the user tx's change set), or seed the copy from a detached/read-only serialization that does not enroll the committed records into the user transaction. At minimum, add an assertion or guard that no committed class enters `toStream` with a null/non-persistent `recordId` during a `copyForTx` seed, so the silent committed-object RID rebind cannot happen unnoticed.

### BC2 [suggestion] copy.identity aliases the committed instance's mutable RecordIdInternal reference

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 159)
- **Issue**: `copy.identity = this.identity` copies the **reference**, so the committed instance and the tx-local copy share one `RecordIdInternal` object. `RecordIdInternal` is a sealed interface implemented by mutable types (`ChangeableRecordId`); a `ChangeableRecordId` mutates in place when a temporary id is promoted to persistent. If the committed root identity were ever a still-mutable id, an in-place promotion would be observed by both instances, and a later in-place mutation on one side would silently change the other.
- **Evidence**: line 159 assigns the reference directly. `identity` is declared `private volatile RecordIdInternal identity` (SchemaShared.java:94). `RecordIdInternal` is `sealed ... permits ChangeableRecordId, ContextualRecordId, ...` (RecordIdInternal.java:37), and `ChangeableRecordId` is the mutate-in-place variant the per-class records use (`toStream` comment at 766-768 relies on `ChangeableRecordId` mutating in place).
- **Refutation considered**: The schema root record is created at `create()` (856-867) and its identity is already persistent by the time any `copyForTx` runs, so the shared reference is effectively immutable in practice and the aliasing is benign. That is why this is a suggestion, not a defect. It is still worth a defensive copy or an immutable-id assertion, because the safety rests on an external invariant (root identity already persistent) that nothing at this site enforces.
- **Suggestion**: Either snapshot the identity into an immutable record id (`copy.identity = this.identity.copy()` / the project's immutable-RID equivalent), or assert `this.identity != null && this.identity.isPersistent()` at the top of `copyForTx` so the shared-reference assumption is checked rather than implied.

### BC3 [suggestion] copy.fromStream can throw on a committed schema with no global properties because saveInternal rejects an active tx

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 160 calling 542-729; the `!hasGlobalProperties` branch at 717-721; `saveInternal` at 946-953)
- **Issue**: `copy.fromStream(session, serialized)` runs the full `fromStream` re-parse. If the re-parsed entity has no `globalProperties` (`hasGlobalProperties == false`), `fromStream` falls into the branch at 717-721 and calls `saveInternal(session)`. `saveInternal` opens with a throw-guard: `if (tx.isActive()) throw new SchemaException("Cannot change the schema while a transaction is active ...")` (949-952). Because `copyForTx` is designed to run inside the caller's open user transaction, hitting that branch would throw and abort the copy seed.
- **Evidence**: 160 → `fromStream` (542). At 717-721, `if (!hasGlobalProperties) { if (session.getStorage() instanceof AbstractStorage) { saveInternal(session); } }`. `saveInternal` (946) throws on an active tx (949-952). The copy is built inside `session.computeInTx(...)` in every current test caller (CopyForTxTest.java:214 etc.), confirming the active-tx context.
- **Refutation considered**: A real committed schema always has global properties after bootstrap, so `hasGlobalProperties` is true and the branch is dead for a `copyForTx` seed; the four green tests confirm the normal path never enters it. The throw is therefore latent, not live. It is still a sharp edge: the seed path reuses `fromStream` wholesale, and that branch was written for the standalone-load path where no tx is active. If a future caller seeds from an empty/global-property-free schema, the failure mode is a confusing `SchemaException` about "schema changes are not transactional" rather than a clear copy-seed error.
- **Suggestion**: Have `copyForTx` (or a seed-specific re-parse path) bypass the `!hasGlobalProperties` self-save, or assert that the committed instance has global properties before seeding, so the seed never reaches a branch that throws on the active user transaction it deliberately runs inside.

### BC4 [suggestion] copyForTx has no precondition guard that a transaction is open

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 152-165)
- **Issue**: `copyForTx` performs record I/O — `toStream` does `session.load(identity)` and per-class `session.load(...)`/record allocation, and `fromStream` does `session.load(...)` — all documented to "ride the caller's already-open user transaction." Nothing in `copyForTx` checks that a transaction is actually open. Called with no active tx, the load/allocate calls behave differently (auto-micro-tx or failure), and any record allocated in `toStream` (the unbound-class arm) would self-commit rather than defer to the user tx, defeating the isolation the method exists to provide.
- **Evidence**: `copyForTx` (152-165) has no `assert session.getTransactionInternal().isActive()` or equivalent. The Javadoc (141-147) states the contract ("this method does not open or commit a transaction of its own ... the change defers to the user transaction's commit") but the code does not enforce it. PSI find-usages shows the sole caller is the test, always inside `computeInTx`, so the precondition is honored today only by caller discipline.
- **Refutation considered**: The Track-3 commit contract and the method Javadoc both state the open-tx precondition, and the only caller honors it, so there is no live failure. This is a defensive-assertion gap, not a defect — hence suggestion severity. Given the method is the foundation seam that Track 4's commit path and the Step 2/3 routing will call from new sites, an explicit assertion converts a silent misuse (records self-committing outside the user tx) into a loud test failure.
- **Suggestion**: Add `assert session.getTransactionInternal().isActive() : "copyForTx must run inside the caller's open user transaction"` at the top of `copyForTx`, consistent with the engage-order and write-lock assertions used elsewhere in this subsystem (`toStream` already asserts `isWriteLockedByCurrentThread`).

## Evidence base

#### C1 — copyForTx side effects on committed state (CONFIRMED as should-fix)
Survived refutation: the corruption scenario is refuted (steady-state RIDs persistent, content byte-identical), but the confirmed residue — `toStream` enrolls committed class records into the user tx's dirty set (SchemaShared.java:775 → SchemaClassImpl.java:591-626) and the unbound-class arm rebinds a committed object's RID (SchemaShared.java:762-770) — is a real, un-documented side effect of a method reasoned about as a committed-graph read. PSI: `copyForTx` has zero production callers (only CopyForTxTest + Javadoc), `newInstanceForCopy` overridden only by `SchemaEmbedded`. Tests green (4/4).

#### C2 — identity aliasing (REFUTED to suggestion)
REFUTATION CHECK: Could the shared `RecordIdInternal` be mutated after aliasing? The committed root identity is set at `create()`/`load()` and is persistent before any `copyForTx`. `ChangeableRecordId` mutates in place only during temp→persistent promotion, which the root record has already undergone. Searched: `identity` assignment sites (SchemaShared.java:94, 159, 415, 839, 861). VERDICT: REFUTED as a live bug — no path mutates the shared id after aliasing in normal operation. Retained as a suggestion because the safety depends on the external "root already persistent" invariant, unenforced at the site.

#### C3 — fromStream self-save throw on active tx (REFUTED to suggestion)
REFUTATION CHECK: Can `hasGlobalProperties` be false for a `copyForTx` seed? A bootstrapped committed schema always carries global properties; the `!hasGlobalProperties` branch (SchemaShared.java:717-721) targets the standalone no-tx load path. Searched: `globalProperties` population in `toStream` (791-797) — always writes the list, so the round-tripped entity carries it. The 4 green tests never enter the branch. VERDICT: REFUTED as a live bug — branch is dead for the seed today. Retained as suggestion: the seed reuses `fromStream` wholesale and the branch throws on the active tx the seed runs inside, a sharp latent edge for future seed callers.

#### C4 — missing open-tx precondition assertion (REFUTED to suggestion)
REFUTATION CHECK: Is the open-tx precondition guaranteed? The Javadoc states it and the sole (test) caller wraps `copyForTx` in `computeInTx` (CopyForTxTest.java:214/240/276/308). PSI: no other caller exists. VERDICT: REFUTED as a live bug — precondition honored by caller discipline. Retained as suggestion: the method is the foundation seam new Track-4/Step-2-3 sites will call; an assertion turns silent misuse (records self-committing outside the user tx) into a loud failure, matching the subsystem's existing assertion style.
