<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 4, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: AbstractStorage.java:2292, anchor: "### A1 ", cert: C1, basis: "Snapshot pinned at commit entry from committed state before reconcileCollections; a tx-aware makeSnapshot changes what commit-path validate() and getCollectionForNewInstance resolve, and the plan's ordering mitigation assumes a rebuild the pin defeats"}
  - {id: A2, sev: should-fix, loc: MetadataDefault.java:106, anchor: "### A2 ", cert: C2, basis: "getImmutableSchemaSnapshot returns the pinned immutableSchema without re-invoking makeSnapshot, so D15 force-rebuild must invalidate the pin, not just the tx-local SchemaShared; plan says 'reuses D15 lazy rebuild' without naming the pin-invalidation"}
  - {id: A3, sev: should-fix, loc: EntityImpl.java:4194, anchor: "### A3 ", cert: C3, basis: "immutableClazz version-cache means a mid-tx property/rule change on an already-resolved class is not re-read unless immutableSchema.getVersion() advances; tx-aware snapshot must bump the version on every force-rebuild or same-tx constraint enforcement (I-P5) silently fails"}
  - {id: A4, sev: suggestion, loc: track-5.md:60, anchor: "### A4 ", cert: C4, basis: "D21 risk(1) cites AbstractStorage line numbers (2410/2473/2528/2691) that no longer match HEAD (computeCommitWorkingSet@2371, reconcileCollections@2781); stale anchors will misdirect the implementer"}
  - {id: A5, sev: should-fix, loc: track-5.md:112, anchor: "### A5 ", cert: C5, basis: "174-caller snapshot tier made tx-aware means every read site (query/MATCH/security) during a schema tx sees the tx-local class with its provisional collection id; the plan scopes the provisional-id fallthrough to the planner (D13) but not to the non-planner readers (security getCollectionNameById, serializers)"}
  - {id: A6, sev: suggestion, loc: track-5.md:99, anchor: "### A6 ", cert: C6, basis: "~18-file footprint plus a new commit-time engine build plus the 174-site snapshot semantic change is a large single track; D21 (snapshot tx-awareness) is separable from D12/D13/D15 (index overlay) and could split if Phase B decomposition strains"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: C1, verdict: WEAK, anchor: "#### C1 "}
  - {id: C2, verdict: FRAGILE, anchor: "#### C2 "}
  - {id: C3, verdict: FRAGILE, anchor: "#### C3 "}
  - {id: C4, verdict: WRONG, anchor: "#### C4 "}
  - {id: C5, verdict: WEAK, anchor: "#### C5 "}
  - {id: C6, verdict: HOLDS, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

# Track 5 adversarial review — iteration 1

Narrowed track-realization pass (D9). The inline Decision Records (D12/D13/D15/D21)
were vetted by the Phase 0→1 research-log adversarial gate and are not re-challenged
here. This pass challenges scope/sizing, cross-track-episode reality, and invariant
violation, per the spawn's three axes.

BLUF: every Track 3/4 seam Track 5 depends on exists with the shape the track
assumes (commit-window seam, `recordWriteTarget` choke point, component-guarded
engine-file revert arm, `TxSchemaState` provisional→name carrier, de-guarded
membership sites, the drop-side `dropIndex` `markClassChanged`-only half). The
174-caller snapshot claim is exact. No blocker. Four should-fix cluster on one real
gap the track under-specifies: the immutable snapshot is **pinned per operation** in
`MetadataDefault` and cached by version, so "make `makeSnapshot()` tx-aware and reuse
D15's force-rebuild" is necessary but not sufficient — the pin and the per-class
version cache both have to be invalidated, and the provisional-id-during-a-schema-tx
exposure reaches all 174 read sites, not only the planner.

## Findings

### A1 [should-fix]
**Certificate**: C1 (Violation scenario — D21 commit-path guard, risk (1))
**Target**: Decision D21, risk-caveat (1); Invariant I-P5
**Challenge**: The plan's mitigation for D21 risk (1) is "reconciliation re-keys the
tx-local class before the working-set build, or guard the commit-path read," resting
on the ordering that `computeCommitWorkingSet` (which calls `entity.validate()` and
`getCollectionForNewInstance`) runs after `resolveProvisionalCollectionIds`. That
ordering holds (verified: reconcile+resolve at `AbstractStorage.java:2472-2491`,
working-set build at `:2528`). But the snapshot `computeCommitWorkingSet` reads is
**pinned once at commit entry** (`makeThreadLocalSchemaSnapshot()` at
`AbstractStorage.java:2292`), before reconciliation. If D21 makes that pin tx-aware,
the pinned snapshot holds the tx-created class with its **provisional** collection id
(`<= -2`), and `getCollectionForNewInstance` → `getCollectionIds()[0]`
(`DefaultCollectionSelectionStrategy.getCollection`) returns the provisional id, which
`doGetAndCheckCollection` at `:2416` rejects. The plan describes the guard as a check
to "verify or guard" but does not state which; given the pin, the guard is mandatory,
not optional — ordering alone does not save it because the value was frozen into the
pin before reconciliation ran.
**Evidence**: `AbstractStorage.java:2292` (`makeThreadLocalSchemaSnapshot` at entry);
`:2472-2491` (reconcile then resolveProvisionalCollectionIds); `:2528`
(computeCommitWorkingSet); `:2410-2416` (getImmutableSchemaClass →
getCollectionForNewInstance → doGetAndCheckCollection); `SchemaImmutableClass.java`
`getCollectionForNewInstance` → `collectionSelection.getCollection` →
`iClass.getCollectionIds()[0]`.
**Proposed fix**: In Track 5 Plan of Work, commit to the guard (not merely "verify"):
either (a) keep the commit-entry pin committed-only and only widen tx-awareness for
non-commit reads, so `computeCommitWorkingSet` continues to see committed state and a
tx-created class resolves to null (the current safe `if (cls != null)` skip at :2411);
or (b) rebuild the pinned snapshot after `resolveProvisionalCollectionIds` and before
`:2528` so the class carries its real id. State the chosen arm and its test.

### A2 [should-fix]
**Certificate**: C2 (Assumption test — "reuses D15's lazy force-rebuild")
**Target**: Decision D21, rationale ("reuses D15's lazy force-rebuild rather than
adding a mechanism")
**Challenge**: D15's force-rebuild is described as O(1) null-and-rebuild of the
tx-local snapshot. But the read tier D21 must make tx-aware is the **pinned**
`immutableSchema` in `MetadataDefault`, not the tx-local `SchemaShared`.
`getImmutableSchemaSnapshot()` (`MetadataDefault.java:106`) returns the cached
`immutableSchema` whenever it is non-null and only calls `schema.makeSnapshot()` when
it is null; the pin is set by `makeThreadLocalSchemaSnapshot` (count 0→1) and cleared
by `clearThreadLocalSchemaSnapshot` (count→0). So a mid-tx class/property change that
force-rebuilds the tx-local `SchemaShared` does **not** change what an in-flight
pinned read sees — `makeSnapshot()` is never re-invoked while the pin is held. "Reuse
D15's rebuild" is under-specified: the trigger must also null (invalidate) the pinned
`immutableSchema` so the next `getImmutableSchemaSnapshot()` re-invokes the now
tx-aware `makeSnapshot()`.
**Evidence**: `MetadataDefault.java:78-91` (`makeThreadLocalSchemaSnapshot` /
`clearThreadLocalSchemaSnapshot`, `immutableCount` refcount, `immutableSchema` cache);
`:106` (`getImmutableSchemaSnapshot` returns cached instance without re-calling
`makeSnapshot`). `SchemaProxy.makeSnapshot` at `SchemaProxy.java:76` reads
`delegate.makeSnapshot(session)`.
**Proposed fix**: Add to D21's Plan of Work an explicit "invalidate the pinned
`MetadataDefault.immutableSchema` on every mid-tx class/property force-rebuild"
sub-step (or document that a pinned snapshot deliberately does not observe intra-pin
schema changes and state why that is acceptable for validation/serialization). Name
the invalidation seam. This is separate from the tx-local `SchemaShared` rebuild.

### A3 [should-fix]
**Certificate**: C3 (Violation scenario — I-P5 same-tx constraint enforcement)
**Target**: Invariant I-P5; Decision D21
**Challenge**: Even once `makeSnapshot()` is tx-aware and the pin is invalidated,
`EntityImpl` caches the resolved immutable class in `immutableClazz` and only
re-resolves when `immutableSchemaVersion != immutableSchema.getVersion()`
(`EntityImpl.java:4187-4210`). Construct: tx creates class C (snapshot rebuilt,
entity e of C resolves `immutableClazz`), then in the same tx adds a NOTNULL property
to C. If the force-rebuild produces a new snapshot instance whose `getVersion()` is
unchanged (a tx-local `SchemaShared` copy need not bump the committed version
counter), `getImmutableSchemaClass` returns the stale cached `immutableClazz` and the
NOTNULL check is skipped — I-P5 silently fails on exactly the "add a property/rule to
an existing class inside a tx" acceptance line in Validation & Acceptance.
**Evidence**: `EntityImpl.java:4194-4210` — `immutableClazz` cache with
`immutableSchemaVersion != immutableSchema.getVersion()` invalidation only; the
version comes from `immutableSchema.getVersion()`, sourced from the tx-local schema's
version which is not guaranteed to advance on a mid-tx alter.
**Proposed fix**: Track 5 must ensure the tx-aware snapshot's `getVersion()` strictly
advances on every mid-tx class/property force-rebuild (so entity-level
`immutableClazz` caches invalidate), and add a Validation line that mutates a rule on
an already-referenced class mid-tx and asserts enforcement on an entity whose
`immutableClazz` was resolved before the mutation.

### A4 [suggestion]
**Certificate**: C4 (Assumption test — D21 risk (1) line citations)
**Target**: Decision D21, risk-caveat (1) line anchors
**Challenge**: D21 risk (1) cites `computeCommitWorkingSet` at
`AbstractStorage.java:2410` "reached at line 2528," `reconcileCollections` at 2473,
`forceSnapshot` at 2691. At HEAD `computeCommitWorkingSet` is defined at :2371 (its
`getImmutableSchemaClass` call is at :2410, which is why that line was cited, but the
method entry is :2371), `reconcileCollections` is at :2781 (not 2473 — 2473 is the
*call site* inside `applyCommitOperations`). The mix of definition lines and call-site
lines in one caveat will misdirect an implementer who greps by the cited anchors.
**Evidence**: PSI: `computeCommitWorkingSet` @2371, `reconcileCollections` @2781,
call sites `reconcileCollections(...)`@2472, `computeCommitWorkingSet(...)`@2528,
`getImmutableSchemaClass`@2410 inside computeCommitWorkingSet.
**Proposed fix**: When Phase A amends the track file, re-anchor D21 risk (1) to
symbol names plus current call-site lines (reconcile call @2472, resolve @2489,
working-set build @2528) rather than the mixed definition/call lines, and note the
lines drift with edits.

### A5 [should-fix]
**Certificate**: C5 (Violation scenario — provisional-id exposure to non-planner
readers, risk (2))
**Target**: Decision D21, risk-caveat (2); the D13 planner extension scope
**Challenge**: D21 makes the single 174-caller snapshot tier tx-aware. Risk (2)
addresses only the query planner: "extend D13's skip-unbuilt treatment to
provisional-collection classes so the WHERE block falls through." But the tx-created
class (provisional id, no physical collection) is now visible in the snapshot to all
174 consumers, and not all of them are the planner. A read/serialize path that maps
collection id → name (security `getCollectionNameById`, `getPhysicalCollectionNameById`,
serializers) on an entity of the tx-created class during the same tx receives a
provisional id `<= -2` and can throw or return null — the same class of failure D13
guards for the planner, but at a different call site the plan does not enumerate.
Track 4's own commit path had to add commit-window read handling for exactly these
name-lookup methods; the mid-tx (non-commit) read path has no such guard yet.
**Evidence**: 174 project callers of `MetadataDefault.getImmutableSchemaSnapshot`
(PSI count, matches the plan's claim exactly); Track 4 episode notes
`getCollectionNameById`/`getPhysicalCollectionNameById` re-enter the read lock and had
to be made window-aware for the *commit* path — the *mid-tx read* path against a
provisional-id class is the analogous gap. D2 caveat: "a provisional id reaching
durable bytes loses the class's collections"; here it reaches a name-lookup instead.
**Proposed fix**: Track 5 Plan of Work should state how a mid-tx read/serialize of a
tx-created class's entity handles the provisional collection id at the non-planner
sites (e.g., resolve provisional → generated name via `TxSchemaState`, the same
carrier the create-side index gap uses, or scope the enforcement so serialization of
a provisional-collection entity stays schemaless until commit). Add a Validation line
that serializes/reads an entity of a tx-created class mid-tx through a non-planner
path.

### A6 [suggestion]
**Certificate**: C6 (Assumption test — track scope/sizing)
**Target**: Scope (Track 5 ~18-file footprint and its step decomposition)
**Challenge**: Track 5 bundles four decisions with distinct blast radii: D15 (index
overlay + routing seam), D12 (commit-time engine build), D13 (planner skip), and D21
(snapshot tx-awareness + the 174-site semantic change + the commit-path guard). D21
was added by inline replan after Track 4 and is the odd one out — it changes a
read-tier semantic consumed by the whole query/security/serialize stack, whereas
D12/D13/D15 are index-subsystem-local. The ~18-file estimate is plausible (all named
files verified to exist except the new `IndexOverlay`), and the two-sided bound
(≤~12 merge / >~20-25 split) puts 18 comfortably in range, so this is not a mandated
split. But the D21 sub-track (makeSnapshot tx-awareness, pin invalidation, version
bump, commit-path guard, non-planner provisional-id handling — findings A1/A2/A3/A5)
is a self-contained unit with its own risk profile and could carry its own
`risk: high` step cluster.
**Evidence**: File existence verified via PSI — `IndexManagerAbstract`,
`ClassIndexManager`, `SchemaProxy`, `SchemaClassProxy`, `SchemaPropertyProxy`,
`MetadataDefault`, `AbstractStorage`, `FetchFromClassExecutionStep`,
`FetchFromIndexStep`, `SchemaImmutableClass` all present; `IndexOverlay` is the one
new class. The two goal clusters share the force-rebuild seam (D15↔D21) but little
else.
**Proposed fix**: No plan change required. At Phase B step decomposition, keep the D21
snapshot-tx-awareness work as its own step cluster (distinct from the index-overlay
steps) so its risk is graded and reviewed on its own, and so a mid-Phase-B split (if
the snapshot semantics prove as thorny as A1-A3/A5 suggest) is clean.

## Evidence base

#### C1 Violation scenario: D21 commit-path guard against a provisional id — risk (1)
- **Invariant claim**: A tx-aware snapshot must never hand `doGetAndCheckCollection` a
  provisional collection id during the commit-path working-set build (D21 risk (1)).
- **Violation construction**:
  1. Start state: a tx creates class C and inserts one entity e of C. C's collection
     id is provisional (`<= -2`, D2).
  2. Action sequence: commit enters at `AbstractStorage.java:2272`; pins the snapshot
     at `:2292` (`makeThreadLocalSchemaSnapshot`) — if D21 is applied, this pin is now
     tx-aware and includes C with its provisional id. `reconcileCollections` at
     `:2472` allocates C's real id; `resolveProvisionalCollectionIds` at `:2489`
     patches the tx-local `SchemaShared`. But the **pinned** `immutableSchema` (built
     at :2292) is a separate immutable copy and is not re-derived.
  3. Intermediate state: pinned snapshot's C still carries the provisional id;
     tx-local `SchemaShared`'s C carries the real id.
  4. Violation point: `computeCommitWorkingSet` at `:2528` calls
     `entity.getImmutableSchemaClass(session)` (→ pinned snapshot) →
     `getCollectionForNewInstance` → `getCollectionIds()[0]` = provisional id →
     `doGetAndCheckCollection(collectionId)` at `:2416`.
  5. Observable consequence: `doGetAndCheckCollection` throws on the unknown negative
     id; the create commit fails on the very D21 acceptance line ("commit a tx that
     creates a class and inserts an entity … does not fail on a provisional id").
- **Feasibility**: CONSTRUCTIBLE (real scenario) if D21's tx-awareness applies to the
  commit-entry pin; INFEASIBLE if the pin stays committed-only (then C resolves to
  null and the `if (cls != null)` at :2411 skips safely). The plan does not say which,
  so the caveat's "verify or guard" is not yet a decision. Survival: WEAK — the design
  survives only under the committed-only-pin reading, which the plan does not commit
  to.

#### C2 Assumption test: "D21 reuses D15's lazy force-rebuild rather than adding a mechanism"
- **Claim**: Making `makeSnapshot()` tx-aware plus widening D15's force-rebuild
  trigger to class/property changes is sufficient to give read-your-writes on the
  snapshot tier.
- **Stress scenario**: A data-write operation pins the snapshot
  (`makeThreadLocalSchemaSnapshot`, count→1), then the same operation (or a nested
  call before `clearThreadLocalSchemaSnapshot`) triggers a mid-tx class change and
  force-rebuilds the tx-local `SchemaShared`. A subsequent `getImmutableSchemaSnapshot`
  within the still-held pin returns the cached `immutableSchema`.
- **Code evidence**: `MetadataDefault.java:106` returns cached `immutableSchema`
  without re-invoking `makeSnapshot()` while `immutableCount > 0`; `:78-91` show the
  pin lifecycle. The tx-local `SchemaShared` rebuild does not touch this field.
- **Verdict**: FRAGILE — the assumption holds only if the force-rebuild trigger *also*
  nulls the pinned `immutableSchema`. As written ("reuse D15's rebuild") it omits the
  pin-invalidation step, so a pinned read misses the mid-tx change.

#### C3 Violation scenario: I-P5 fails via EntityImpl.immutableClazz version cache
- **Invariant claim**: I-P5 — during a schema/index tx the snapshot reflects tx-local
  classes/property-types/rules so `validate()` enforces a same-tx constraint.
- **Violation construction**:
  1. Start state: tx creates class C; entity e of C is validated once, resolving and
     caching `e.immutableClazz` at `EntityImpl.java:4204`.
  2. Action sequence: same tx adds a NOTNULL property to C (a mid-tx alter). D15/D21
     force-rebuild produces a new tx-aware snapshot instance.
  3. Intermediate state: if the new snapshot's `getVersion()` equals the prior
     snapshot's version (tx-local copies need not advance the committed version
     counter), `e`'s cached `immutableClazz` is not invalidated.
  4. Violation point: `getImmutableSchemaClass(session, immutableSchema)` at `:4187`
     takes the `else if (immutableSchemaVersion != immutableSchema.getVersion())`
     branch, finds them equal, and returns the stale `immutableClazz` without the new
     property.
  5. Observable consequence: `validate()` skips the NOTNULL check; I-P5's "add a
     property/rule to an existing class inside a tx, enforced on the same tx's
     entities" acceptance line silently passes with no enforcement.
- **Feasibility**: CONSTRUCTIBLE unless the tx-aware snapshot version strictly
  advances on every force-rebuild. Survival: FRAGILE — depends on a version-bump
  discipline the plan does not state.

#### C4 Assumption test: D21 risk (1) line citations match HEAD
- **Claim**: The `AbstractStorage.java` line anchors in D21 risk (1) (2410, 2473,
  2528, 2691) locate the cited symbols.
- **Stress scenario**: An implementer greps the cited lines to find the guard site.
- **Code evidence**: PSI — `computeCommitWorkingSet` defined @2371 (its
  `getImmutableSchemaClass` call @2410); `reconcileCollections` defined @2781, called
  @2472; `computeCommitWorkingSet` called @2528. So 2410 is a call inside the method,
  2473 is near the call site (not the definition @2781), 2528 is the call site (right).
- **Verdict**: WRONG (as anchors) — the caveat mixes definition lines and call-site
  lines without labeling them; 2473/2691 do not point at the method definitions a
  reader would expect. Low severity: the symbol names are correct and the ordering
  claim is right; only the line hints drift.

#### C5 Violation scenario: provisional-id exposure to the 174 non-planner readers — risk (2)
- **Invariant claim**: D21 risk (2) — a query/MATCH against a tx-created class falls
  through to the merged tx scan (planner extension of D13).
- **Violation construction**:
  1. Start state: tx creates class C (provisional collection id, no physical
     collection, no engine) and inserts entity e of C.
  2. Action sequence: same tx triggers a read/serialize path that is NOT the query
     planner — e.g. a security check or serializer that maps e's collection id to a
     name via `getCollectionNameById` / `getPhysicalCollectionNameById`.
  3. Intermediate state: with the tx-aware snapshot, C resolves; e's RID carries the
     provisional collection id `<= -2`.
  4. Violation point: the name-lookup receives a negative id it does not know (the
     physical collection does not exist until commit) and returns null / throws.
  5. Observable consequence: a mid-tx read or serialize of a tx-created class's entity
     fails at a site the D13 planner extension does not cover.
- **Feasibility**: CONSTRUCTIBLE at the serialize/security read sites; the plan's
  risk (2) mitigation names only the planner. Survival: WEAK — D13's extension covers
  the WHERE-block scan but the plan does not enumerate the non-planner id→name lookups
  that the newly-visible provisional-id class exposes. (Track 4 already had to make
  these name lookups window-aware for the commit path, evidencing the hazard.)

#### C6 Assumption test: Track 5 scope/sizing (~18 files, D12+D13+D15+D21 in one track)
- **Claim**: ~18 in-scope files bundling the index overlay/build/planner work with the
  snapshot-tx-awareness work is correctly sized as one track.
- **Stress scenario**: Phase B decomposition — does the bundle decompose into coherent
  steps, or does the D21 read-tier semantic change strain against the index-subsystem
  work?
- **Code evidence**: All named files exist (PSI): `IndexManagerAbstract`,
  `ClassIndexManager`, `SchemaProxy`, `SchemaClassProxy`, `SchemaPropertyProxy`,
  `MetadataDefault`, `AbstractStorage`, `FetchFromClassExecutionStep`,
  `FetchFromIndexStep`, `SchemaImmutableClass`; only `IndexOverlay` is new. 18 sits
  inside the ≤~12/>~20-25 two-sided bound, so no split is mandated. D15 and D21 share
  the force-rebuild seam; otherwise the two clusters are independent.
- **Verdict**: HOLDS — sizing is within bounds and does not require a split. The
  finding is advisory: keep D21's snapshot work as its own step cluster so its higher
  risk (A1/A2/A3/A5) is isolated and a mid-Phase-B split stays clean.
