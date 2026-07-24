<!--MANIFEST
dimension: crash-safety
step: 5
track: 4
iteration: 1
commit_range: 920fa2aa00298238abf4bfa2eaca520a6b453cb4~1..920fa2aa00298238abf4bfa2eaca520a6b453cb4
verdict: CHANGES_REQUESTED
findings_total: 2
blockers: 1
should_fix: 0
suggestions: 1
evidence_base: C1, C2
cert_index: C1, C2
flags: reference-accuracy-PSI-backed
index:
  - id: CS1
    sev: blocker
    anchor: "#cs1-selective-write-drops-per-class-record-mutations-not-routed-through-markclasschanged"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:1013-1035"
    cert: C1
    basis: "PSI find-usages of TxSchemaState#markClassChanged (5 production callers) + SchemaClassProxy/SchemaProxedResource routing read"
  - id: CS2
    sev: suggestion
    anchor: "#cs2-rootpayloaddiffersfrom-append-only-assumption-is-load-bearing-but-unenforced"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:1092-1121"
    cert: C2
    basis: "Read of findOrCreateGlobalProperty (append-only) + rootPayloadDiffersFrom signature comparison"
-->

# Crash Safety & Durability Review — Track 4, Step 5 (selective per-class schema write)

The selective write itself is well-built: the root-omission guard fires the root record on
every link-set change and on every root-payload diff, the warm read-only `session.load` of
unchanged records correctly covers the promotion re-parse on every storage profile, and the
created/dropped per-class records ride the commit's own atomic operation so the WAL reverts
them on a failed commit. One gap is durability-critical: the changed-class signal that keys
the selective write is incomplete, so a class of committed schema mutations is silently
dropped from the write set.

## Findings

### CS1 [blocker] Selective write drops per-class-record mutations not routed through `markClassChanged`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 1013-1035), keyed by `AbstractStorage.java:2506-2510`.

The selective write decides whether to rewrite an existing class's per-class record solely
from `changedLower.contains(c.getName().toLowerCase(...))` (SchemaShared.java:1013-1014), and
`changedLower` is built from `schemaContext.txSchemaState().getChangedClasses()`
(AbstractStorage.java:2506). A class that is not in that set falls into the final `else`
branch (line 1019) — a read-only warm `session.load(boundRid)` with no rewrite — so its
per-class record stays out of the commit working set.

`getChangedClasses()` is populated only by `TxSchemaState#markClassChanged`, and PSI
find-usages shows exactly five production callers: `SchemaEmbedded#createClassInternal`,
`SchemaShared#changeClassName` (class rename), `SchemaClassEmbedded#setAbstractInternal`,
`SchemaEmbedded#dropClassInternal`, and the `IndexManagerEmbedded` membership/create/drop
ripple. Several other operations mutate an existing class's per-class record content but never
call `markClassChanged` and are reachable inside a transaction with no throw-guard:

- `addSuperClass` / `removeSuperClass` / `setSuperClasses` (SchemaClassEmbedded.java:124,172,210
  → `setSuperClassesInternal` at 228, no `markClassChanged`)
- `setCustom` / `removeCustom` / `clearCustom` (SchemaClassEmbedded.java:68,81 →
  `setCustomInternal`/`clearCustomInternal`)
- `setDescription` (→ `setDescriptionInternal`), `setStrictMode` (→ `setStrictModeInternal`),
  `setOverSize`, property `setName` / `setType` (SchemaPropertyEmbedded.java:45,81)

`SchemaClassImpl.toStream` serializes `name`, `description`, `defaultCollectionId`,
`collectionIds`, `overSize`, `strictMode`, `abstract`, `properties`, `superClass`/`superClasses`,
and `customFields` into the per-class record (SchemaClassImpl.java:602-632). So each of the
operations above changes the per-class record's intended content.

Routing into a transaction is confirmed: `SchemaClassProxy` sends these mutations through
`resolveForWrite()` (e.g. line 178 `set`, 262 `setStrictMode`, 308 `addSuperClass`, 347
`setDescription`, 583 `setCustom`), and `SchemaProxedResource#resolveForWrite` (lines 92-99)
rebinds to the tx-local copy without calling `markClassChanged`. The tx-local mutation does
not eagerly persist (`SchemaShared#saveInternal` returns early when `txLocal`, line 1266), so
the legacy active-transaction throw at line 1275 never fires and the change defers to commit.
The deliberate `markClassChanged` wiring inside `setAbstractInternal` (line 587) — added in
Step 2 precisely so an abstract→concrete alter inside a tx is committed — confirms alters DO
run transactionally and shows the omission for the sibling setters is an asymmetry, not a
reachability dead-end.

**Crash scenario / failure**: A committed transaction calls, on an existing class `C`,
`getClass("C").addSuperClass(super)` (or `setCustom`, `setDescription`, `setStrictMode`,
`setOverSize`, a property rename/retype) and commits. `C`'s name is not in `getChangedClasses()`,
its `boundRid` is persistent, and the link set is unchanged (it already links `C`), so neither
the `else if` write arm (line 1013) nor the link-set arm fires; `C`'s record is only warm-loaded
(line 1029). The mutation is never written. Worse, promotion then re-parses the committed schema
from the *unwritten* on-disk record (`fromStream` at SchemaShared.java:775-799 loads each linked
class record), so the in-memory committed schema reverts the change as well. The commit reports
success while the change is lost immediately in memory and on disk; after a restart the WAL
replays the stale (unwritten) record. This is a silent lost-update of acknowledged committed
schema state — exactly the F59 class of failure this step's guard targets, but on the per-class
record axis rather than the root.

**Refutation considered**: (1) Could `resolveForWrite()` or `rebindToTxLocal` mark the class
changed? No — read of SchemaProxedResource.java:92-99 and SchemaClassProxy.java:37-49 shows
neither does. (2) Could these ops be throw-guarded inside a tx like `addProperty`/`dropProperty`
/`dropClass`? A grep of every `isActive()`/"inside a transaction" guard in the schema package
shows guards only on property create/drop (SchemaClassEmbedded.java:42,439,459) and class drop
(SchemaEmbedded.java:426,474); superclass and attribute setters have none. (3) Could the full
non-selective path still cover them? Only outside a tx (`changedClassNames == null`); inside a
tx the selective path is taken with the incomplete set. (4) Could promotion read the live
tx-local object instead of the re-parsed record, masking the loss? No — promotion is by
re-parse from the committed records (D8 promotion facet; SchemaShared.java:803-809), so a missing
record write is a real loss. The finding survives.

**Suggestion**: Make the changed-class signal complete on the tx-local path rather than relying
on each mutation site to remember to call `markClassChanged`. Two robust options: (a) have
`SchemaProxedResource#resolveForWrite` mark the resolved class changed when it rebinds a
`SchemaClassProxy`/`SchemaPropertyProxy` for a write (the rebind already names the class), so any
tx-local class write is recorded uniformly; or (b) add `markClassChanged` to each unguarded
internal mutator (`setSuperClassesInternal`, `addSuperClassInternal`, `removeSuperClassInternal`,
`setCustomInternal`, `clearCustomInternal`, `setDescriptionInternal`, `setStrictModeInternal`,
`setOverSizeInternal`, `setCollateInternal`, and the property `setNameInternal`/`setTypeInternal`,
recording the owner class). Option (a) is the more durable fix because it closes the whole class
of future omissions. Either way, add a regression test that performs one such alter on an
existing class inside a transaction, commits, reloads durably, and asserts the attribute
survived — the existing `SchemaCommitReconciliationTest` covers create/drop/counter but no
attribute-only alter on an unchanged-link class.

### CS2 [suggestion] `rootPayloadDiffersFrom` append-only assumption is load-bearing but unenforced

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 1092-1121).

`rootPayloadDiffersFrom` compares the global-property table slot by slot through a
`name|type` signature (lines 1105-1110) and short-circuits on a size difference (line 1099).
Its correctness rests on `findOrCreateGlobalProperty` being strictly append-only — verified
today: it only ever appends a new slot at `id = properties.size()` and never rewrites or
removes an existing slot (SchemaShared.java:1255-1260). The Javadoc states this dependency, but
nothing enforces it, and the comparison is keyed on name+type only. If a future change ever
mutated an existing slot in place (same index, same name and type, different attribute) or
introduced a sparse-table compaction, `rootPayloadDiffersFrom` could return false against a
genuinely-changed root and the selective write would omit the root record — the same silent
cross-restart corruption class as F59.

**Crash scenario**: Not reachable today (append-only holds), so this is a forward-looking
hardening note, not a live defect.

**Refutation considered**: I confirmed the only mutators of the `properties` table via PSI
(`addPropertyInternal`, `setTypeInternal`, `setNameInternal`, `fromStream`, and the direct
`createGlobalProperty`) all go through `findOrCreateGlobalProperty`'s append path or the
load path, so no in-place slot rewrite exists now. The finding is purely defensive.

**Suggestion**: Either include the global property `id` in the signature (it is already the
slot index, so this is implicit, but making it explicit documents intent) or add a one-line
unit assertion / comment at `findOrCreateGlobalProperty` that any future non-append mutation
must update `rootPayloadDiffersFrom`. Low priority.

## Evidence base

#### C1 — Changed-class signal is incomplete for the selective write (CONFIRMED as issue)
PSI find-usages of `TxSchemaState#markClassChanged` returns five production callers
(`createClassInternal`, `changeClassName`, `setAbstractInternal`, `dropClassInternal`,
`IndexManagerEmbedded` ripple) — none for superclass/custom/description/strict-mode/oversize/
collate/property-rename. Routing into the tx-local path confirmed by reading
`SchemaClassProxy` (resolveForWrite at lines 178/262/308/347/583) and
`SchemaProxedResource#resolveForWrite` (lines 92-99, no markClassChanged). Per-class record
content confirmed by `SchemaClassImpl.toStream` field list (lines 602-632). Throw-guard
absence confirmed by grep of all `isActive()`/"inside a transaction" sites in the schema
package (guards only on property create/drop and class drop). Promotion-by-re-parse loss
confirmed by `SchemaShared.fromStream` reading each linked record (lines 775-799). Survived all
four refutation angles. PSI-backed; reference accuracy high.

#### C2 — Append-only assumption behind `rootPayloadDiffersFrom` (REFUTED as a live defect; kept as hardening)
`findOrCreateGlobalProperty` (lines 1255-1260) appends only; PSI confirms all `properties`-table
mutators route through it or `fromStream`. No in-place slot rewrite exists today, so
`rootPayloadDiffersFrom`'s name+type signature comparison cannot miss a real change now. Reported
only as a forward-looking invariant-documentation suggestion, not a current durability gap.
