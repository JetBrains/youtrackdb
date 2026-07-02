<!--MANIFEST
dimension: code-quality
scope: track
track: 5
iteration: 1
verdict: PASS
blockers: 0
should_fix: 1
suggestions: 4
evidence_base:
  certs: 0
cert_index: []
flags: []
index:
  - id: CQ1
    sev: should-fix
    anchor: "#cq1-schema-tx-plan-cache-bypass-added-to-the-yql-cache-but-not-its-sibling-gremlin-cache"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/executor/GqlExecutionPlanCache.java:98
    cert: n/a
    basis: diff + grep (sibling cache lacks the bypass) + episode note (Step 4 residual)
  - id: CQ2
    sev: suggestion
    anchor: "#cq2-getclassrawindexes-and-getclassindexes-overrides-duplicate-the-overlay-resolution"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:319
    cert: n/a
    basis: diff + source read (base methods identical)
  - id: CQ3
    sev: suggestion
    anchor: "#cq3-reconciledindexplan-record-used-as-a-mutable-post-construction-accumulator"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:813
    cert: n/a
    basis: diff + source read
  - id: CQ4
    sev: suggestion
    anchor: "#cq4-overlay-rename-category-and-some-plural-accessors-have-no-production-caller"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexOverlay.java:142
    cert: n/a
    basis: diff + PSI find-usages (rename category and getTxCreatedNames test-only)
  - id: CQ5
    sev: suggestion
    anchor: "#cq5-applycommitoperations-schema-carry-branch-nesting-deepened-by-index-reconciliation"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2626
    cert: n/a
    basis: diff + source read + cross-ref to Track-4 code-quality CQ1
-->

# Code Quality — Track 5 (track-level), iteration 1

## Findings

### CQ1 [should-fix] schema-tx plan-cache bypass added to the YQL cache but not its sibling Gremlin cache

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/executor/GqlExecutionPlanCache.java` (getInternal line 98, putInternal line 83)

**Issue**: Step 4 added a schema-tx guard to both the get and the put side of `YqlExecutionPlanCache`
(`YqlExecutionPlanCache.java:99` and `:140`): while `db.getTxSchemaState() != null`, the shared
statement cache is bypassed so a plan shaped by the tx-local schema view (tx-created classes carrying
provisional collection ids, tx-dropped classes and indexes) is neither served from nor published to
the cross-session cache. `GqlExecutionPlanCache` has the byte-for-byte same shape — a capacity-0
early return in both `getInternal` and `putInternal`, a Guava cache keyed by statement text — and
received no equivalent guard. A Gremlin query planned inside a schema/index transaction can therefore
read a stale committed-state plan, or publish a tx-shaped plan (baked-in provisional ids, or a
polymorphic scan missing a tx-created subclass) into the shared cache where it outlives the
transaction's rollback. The two caches are siblings that should apply the same safety rule; applying it
to one and not the other leaves a guard that a future reader will assume is uniform.

The Step 4 episode already records this as a known residual ("the Gremlin `GqlExecutionPlanCache` has
the same staleness shape and no bypass — a follow-up for whichever track (or issue) touches
Gremlin-versus-schema-tx"), so it is a conscious gap rather than an oversight. The correctness weight
of the Gremlin path is for the bugs reviewer to judge; from the consistency lens the sibling caches
should not diverge silently.

**Suggestion**: Either mirror the `db.getTxSchemaState() != null` bypass into `GqlExecutionPlanCache`'s
`getInternal`/`putInternal` (the smaller, symmetric fix), or, if the Gremlin-versus-schema-tx
interaction is genuinely out of this track's scope, file a tracked follow-up issue and leave an inline
`// no schema-tx bypass yet — see YTDB-xxxx` note at the two Gremlin cache sites so the asymmetry is
visible at the code, not only in the episode log.

### CQ2 [suggestion] getClassRawIndexes and getClassIndexes overrides duplicate the overlay resolution

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (lines 318-343)

**Issue**: The two new `@Override` methods are identical except for the `super.` call target:

```java
var overlay = activeOverlay(session);
if (overlay == null) {
  super.getClassRawIndexes(session, className, indexes);   // getClassIndexes in the sibling
  return;
}
final Collection<Index> committed = new ArrayList<>();
super.getClassRawIndexes(session, className, committed);   // getClassIndexes in the sibling
indexes.addAll(overlay.resolveClassRawIndexes(className, committed));
```

The base `IndexManagerAbstract.getClassRawIndexes` and `getClassIndexes` have identical bodies today, so
routing both through `resolveClassRawIndexes` is functionally correct, but the overlay-resolution dance
is copy-pasted. A future change to the resolution shape must be applied to both, and a copy that drifts
is a silent divergence between the raw and non-raw index views. A small note: `getClassIndexes` calling
a method named `resolveClassRawIndexes` reads slightly against the grain — fine given the base
equivalence, but worth a one-line comment or a shared helper.

**Suggestion**: Extract a private helper that takes the committed collector as a functional parameter
(for example `resolveWithOverlay(session, className, indexes, (s, c, out) -> super.getClassRawIndexes(...))`),
or at minimum a comment stating the two overrides are deliberately identical because the base bodies
are. Readability only; no behavior change.

### CQ3 [suggestion] ReconciledIndexPlan record used as a mutable post-construction accumulator

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (lines 813-819)

**Issue**: `ReconciledIndexPlan` is a `record`, which signals an immutable value carrier, but two of its
components (`createdEngineExternalIds`, `droppedEngines`) are handed in as empty `new ArrayList<>()` by
`enrollReconciledIndexRecords` and then mutated in a later phase — `plan.createdEngineExternalIds().add(externalId)`
and `plan.droppedEngines().add(droppedEngine)` in `buildAndDropReconciledEngines`. The record's own
Javadoc documents the two lists as "filled in during the build phase", so the intent is deliberate, but
a record whose fields are populated across three commit phases works against the value-type reading a
`record` invites: a reader sees `record` and assumes construction-time completeness. The two
build-phase-filled lists and the enroll-phase-filled lists (`created`, `dropped`, `appliedMembership`)
carry different lifecycles behind the same flat component list.

**Suggestion**: Optional. Either keep the `record` but make the two accumulator lists visually distinct
(a short comment at the two `new ArrayList<>()` construction args noting they are filled later), or model
the plan as a small plain class with clearly named mutable fields. No correctness impact.

### CQ4 [suggestion] overlay rename category and some plural accessors have no production caller

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexOverlay.java` (recordRenamed line 142, getRenamed line 218, getTxCreatedNames line 200)

**Issue**: PSI find-usages against the open project shows the overlay's rename category is unwired:
`recordRenamed(String,String)` has one caller (`IndexOverlayTest`) and `getRenamed()` has two, both
tests — no production path records or reads a rename. `getTxCreatedNames()` is likewise test-only. This
is by design: D15 defines four overlay categories and defers the in-place rename accelerator and its
commit half to Track 6 (D17: "Track 6's rename rides the overlay's in-place rename category"), and the
`recordRenamed` Javadoc says as much. So this is documented forward-looking scaffolding, not dead code
in the strict sense. The mild concern is that a production seam covered only by unit tests, with no
integration exercise until a later track, can quietly rot (a signature or semantic assumption Track 6
needs may drift) with nothing but the isolated overlay unit test to catch it.

**Suggestion**: Optional. Leave the rename category in place (it is a planned Track-6 seam), but a
one-line pointer at `recordRenamed` naming the consuming track/issue (Track 6 / D17) would keep a later
reader from assuming it is live, and confirm the seam contract is what Track 6 expects. The other
membership/created/dropped accessors are all production-wired (PSI-confirmed), so no action there.

### CQ5 [suggestion] applyCommitOperations schema-carry branch nesting deepened by index reconciliation

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (schema-carry branch, ~lines 2600-2670)

**Issue**: The Track-4 code-quality review already flagged `applyCommitOperations` as an oversized method
(its CQ1, should-fix) and recommended extracting the schema-carry serialization preamble into a named
helper. Track 5 did not take that extraction and added more into the same block: the schema serialize
now sits in its own inner `try/finally` (releasing the schema write lock), the index enroll phase runs
in the same suppressed-link-consistency window, and the branch then continues with the tx-index-changes
strip, the memo-invalidate-plus-pinned-snapshot-rebuild, and `rewriteProvisionalRecordCollectionIds`
before the working set is even gathered. The result is a longer body with a deeper try nest, where a
reader must track which statements fire on the `schemaContext != null` branch across the added phases.
The three phase methods (`enrollReconciledIndexRecords`, `buildAndDropReconciledEngines`,
`publishReconciledIndexes`) are cleanly factored on `IndexManagerEmbedded`, so the residual complexity
is the orchestration inside the host commit body, not the phase logic itself.

**Suggestion**: Optional, and a continuation of the accepted-but-open Track-4 CQ1 rather than a new
issue: extracting the schema-carry serialize + index-enroll preamble into a private
`serializeAndEnrollSchemaCarry(...)` helper would drop the host method's nesting depth and let the apply
body read as the reconcile → serialize/enroll → rewrite → gather → apply → (promote | undo) sequence it
is. Readability/maintainability only; the lock-window and atomic-operation threading make this a
careful refactor, so it is a suggestion, not a blocker.

## Evidence base

No certificates: this dimension is evidence-trail-exempt (no refutation or certificate phase to
persist). Reference-accuracy claims were checked with mcp-steroid PSI find-usages against the open
`transactional-schema` project (confirmed matching the working tree via `steroid_list_projects`,
routing key `transactional-schema-b4l1mcdq`): the overlay accessor caller sets (CQ4), the
`findCollectionsByIds`-still-used and `resolveDeferredCollectionNames` caller check, and the
membership/created/dropped wiring. The Gremlin sibling-cache gap (CQ1) was confirmed by locating
`GqlExecutionPlanCache` and grepping it for any `getTxSchemaState`/schema-tx bypass (none present); the
base-method equivalence (CQ2) and the record-accumulator and commit-branch shapes (CQ3, CQ5) were read
directly from source.
