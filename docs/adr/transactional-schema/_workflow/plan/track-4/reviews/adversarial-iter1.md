<!-- MANIFEST
findings: 4   severity: {blocker: 1, should-fix: 2, suggestion: 1}
index:
  - {id: A1, sev: blocker,    loc: "AbstractStorage.java:5026", anchor: "### A1 ", cert: V1, basis: "doAddCollection publishes into in-memory collections/collectionMap synchronously; a commit that fails after reconciliation leaves a phantom registration, breaking I-A4 'next commit reuses the ids' since neither the closed 5-item patch list nor rollback() reverts the in-memory registry"}
  - {id: A2, sev: should-fix, loc: "SchemaShared.java:809", anchor: "### A2 ", cert: A1c, basis: "toStream serializes every live class, not the changed-class set; the D6 write-amplification win and the SchemaShared selective-write change are absent from Track 4's in-scope file list and Plan of Work"}
  - {id: A3, sev: should-fix, loc: "track-4.md:43", anchor: "### A3 ", cert: V2, basis: "the five-item provisional-id patch list is asserted closed, but the engine-id allocator (indexEngines.size()) collides with the collection-id concern and the reverse-map re-key timing is under-specified for a multi-class commit; the closed-list claim is fragile"}
  - {id: A4, sev: suggestion, loc: "track-4.md:189", anchor: "### A4 ", cert: S1, basis: "~15 files plus 8 strictly-ordered concerns is dense for one reviewable PR; the engine extraction and the snapshot-first read conversions are separable, but the track survives the soft footprint bound"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 2}
cert_index:
  - {id: V1,  verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: V2,  verdict: THEORETICAL,   anchor: "#### V2 "}
  - {id: A1c, verdict: FRAGILE,       anchor: "#### A1c "}
  - {id: C1,  verdict: WEAK,          anchor: "#### C1 "}
  - {id: C2,  verdict: YES,           anchor: "#### C2 "}
  - {id: S1,  verdict: SURVIVES,      anchor: "#### S1 "}
flags: [CONTRACT_OK]
-->

# Track 4 — Adversarial review (iteration 1)

Track 4 builds on three completed tracks, and the cross-track outputs it assumes
were verified against the live code, not just the episodes: Track 1's
`ensureFileForReplay` helper exists in `AbstractStorage` and is reached by both
`restoreFrom` callers; Track 2's per-class `toStream` asserts the write lock and
takes no other lock, the load reader rejects a non-persistent linked class record
with a `ConfigurationException` (`SchemaShared.java:655-658`), and the schema
version is 6; Track 3's `TxSchemaState`, `MetadataWriteMutex` (engage + same-thread
reject + normal release, holder is a plain volatile per the single-releaser
premise), and `getChangedClasses()` recording create / rename-new-name-only / drop /
index-membership all exist as described. Those facts hold. The findings below are
where the realization on top of them does not yet close.

The narrowing held: this review does not re-litigate D1/D2/D3/D6/D8/D9/D10/D19 as
design decisions (the research-log gate vetted those). It challenges scope/sizing,
cross-track-episode reality, and invariant realizability.

## Findings

### A1 [blocker]
**Certificate**: V1 (Violation scenario — I-A4 no phantom registration)
**Target**: Invariant I-A4 / Decision D10 (commit-local allocator, deferred publication)
**Challenge**: D10 promises "a failed commit leaves no phantom registration and frees
its ids to reuse," and I-A4's acceptance test requires that after a forced apply
failure "the next commit reuses the ids." But reconciliation calls the lock-free
primitive `doAddCollection`, which calls `registerCollection`
(`AbstractStorage.java:5026`) → `setCollection`, mutating the in-memory `collections`
array and `collectionMap` **synchronously at reconciliation time** — before
`commitChanges`, before `commitEntry`, before `commitIndexes`. D3 requires exactly
this early publication (the collection must be live in `collections` before the
record-position-allocation loop allocates into it). So the same step that D3 mandates
violates D10's "deferred publication" wording. When the commit then fails — the
`catch (IOException | RuntimeException | AssertionError)` at line 2377 fires and
`finally` calls `rollback(error, atomicOperation)` at line 2398 — the WAL atomic
operation reverts the *files*, but nothing reverts the in-memory `collections` /
`collectionMap` / `indexEngines` entries that `doAddCollection` / `doAddIndexEngine`
already published. The next open or the next commit sees a phantom collection id
occupied by a registration whose backing files were rolled back. I-A4 fails.
**Evidence**: `registerCollection` (line ~5012) unconditionally `collectionMap.put`
+ `setCollection`; `setCollection` (line ~5037) appends/sets into `collections` with
no atomic-operation linkage. The commit failure path (lines 2377-2398) reverts only
the atomic operation. The existing per-op `addCollection` (line 1441) survives this
because a structural failure there self-commits or pushes the whole storage into
error state — an escape hatch I-A4 explicitly rejects ("the next commit reuses the
ids," i.e. storage stays usable). The provisional-id patch list (D2) is asserted to
have exactly five items, and registry-revert-on-failed-commit is not one of them, nor
does the Plan of Work mention it.
**Proposed fix**: Either (a) extend the patch list / Plan of Work with an explicit
"unregister the just-published collections and engines from the in-memory registries
on a failed commit" step (a sixth patch item or a reconciliation-undo hook in the
commit's failure `finally`), or (b) change the reconciliation primitives so the
in-memory publication is itself buffered and applied only on the post-`commitChanges`
success path, with a separate id-reservation that satisfies D3's "live before
record-position allocation" without a durable `collections` mutation. Whichever is
chosen, add the forced-apply-failure assertion that the in-memory registry is clean,
not only the file/registry-on-disk assertion the current I-A4 line states.

### A2 [should-fix]
**Certificate**: A1c (Assumption test — the D6 write-amplification win is realized here)
**Target**: Decision D6 / the Track 2→4 hand-off ("write-amplification win deferred to Track 4")
**Challenge**: The track's own Decision Log D6 claims the per-property dirty tracking
is "the D14 write-amplification win," and the Track 2 episode says the win "stay[s]
deferred to Track 4 (D6 dirty tracking)." But the per-class serializer Track 4
inherits, `SchemaShared.toStream` (`SchemaShared.java:796`), iterates
`new HashSet<>(classes.values())` and calls `c.toStream(session, classRecord)` for
**every** live class on every invocation (lines 809-840). Writing only the changed
class records requires either restructuring `toStream` to consult the changed-class
set, or relying on the record layer's per-property dirty marks to suppress the
unchanged record writes. The track's `## Interfaces and Dependencies` in-scope list
names `AbstractStorage.commit`, the engine extraction, the provisional-id sites,
promotion, and the two read conversions — it does **not** name `SchemaShared.toStream`
or any selective per-class write. So the file where the win must land is outside the
declared scope, and the Plan of Work step 5 ("re-pointing the changed-class records'
property values") touches only the provisional-id re-point, not the
write-only-changed-classes behavior.
**Evidence**: `SchemaShared.toStream` lines 809 (`realClasses = classes.values()`),
819 (`for (var c : realClasses)`), 835 (`c.toStream(...)` per class). Track file
`## Interfaces and Dependencies` in-scope list (track-4.md:189-195) omits
`SchemaShared`.
**Proposed fix**: Add `SchemaShared` (and the selective per-class write keyed on
`getChangedClasses()`) to the in-scope list, or state explicitly in D6 that the win is
realized by the record-layer dirty-mark suppression of unchanged-record writes and add
an acceptance assertion that a one-class change writes exactly one (plus the root when
its payload changed) per-class record. As written, I-U1's write-amplification claim has
no in-scope step that demonstrably produces it.

### A3 [should-fix]
**Certificate**: V2 (Violation scenario — provisional-id / engine-id allocator interplay)
**Target**: Decision D2 (five-item patch list, asserted closed) / the commit-local allocator
**Challenge**: D2 asserts the commit-time patch list "has five items" and frames that
count as load-bearing (skipping the property-value re-point is "the F58
silent-corruption case"). Two gaps make the closed-list claim fragile for a
multi-class, multi-index commit. First, the patch list is about **collection** ids; the
commit-local **engine**-id allocator is a separate concern (today `addIndexEngine`
derives the engine id from `var genenrateId = indexEngines.size()`,
`AbstractStorage.java:2786`, the same stale-after-deferral problem as the collection
allocator), yet neither D2's five items nor D10's wording enumerates an engine-id
reservation step distinct from the collection one. Second, the
`collectionsToClasses` reverse-map re-key (patch item 3) and the per-class property
re-point (item 5) must both run after provisional→real resolution; for two classes
created in one tx whose provisional ids resolve to real ids that interleave with each
other's references, the re-key ordering is unstated. The list may well be complete,
but the track presents the count as proven without the engine-id item or the
multi-class ordering being spelled out.
**Evidence**: `addIndexEngine` engine-id allocation at `AbstractStorage.java:2786`
(`indexEngines.size()`), structurally identical to the collection allocator
(`doAddCollection` line 4991) the track does treat as needing a commit-local seed.
D2 (track-4.md:43-44) lists five items, all collection-shaped.
**Proposed fix**: Either fold the engine-id reservation into the patch list as an
explicit item (making it six, or a two-axis list), or state in D2 that the engine-id
allocator follows the identical commit-local-seed discipline as the collection
allocator and is covered by D10's allocator clause. Add a multi-class create test to
the acceptance set (the current I-A3 "two classes, 16 collections, 2+ engines" test is
the right vehicle — make it assert the cross-class reference re-point, not only that
"every collection and engine resolves").

### A4 [suggestion]
**Certificate**: S1 (Scope/sizing)
**Target**: Track scope (~15 files, eight strictly-ordered concerns)
**Challenge**: The track packs eight load-bearing, strictly-ordered concerns
(write-lock branch, set-difference diff, engine-primitive extraction, ordered
reconciliation, five-item provisional patch, deferred publication, re-parse promotion,
two read-site conversions) into ~15 files. Two are cleanly separable along the
dependency seam: the `doAddIndexEngine` / `doDeleteIndexEngine` extraction is a
mechanical refactor with no behavior change and could land as its own step (or even
fold earlier), and the two snapshot-first read conversions
(`createVertexWithClass`, `getLowerSubclass`) are independent of the reconciliation
core — they guard the read-outage risk but do not feed reconciliation. Splitting would
shrink the highest-risk commit (the reconciliation + provisional-id + promotion core)
to a smaller reviewable unit.
**Evidence**: The eight concerns are enumerated in `## Plan of Work` (track-4.md:120-138);
the read conversions and the extraction are listed in-scope but have no data dependency
on the reconciliation core. `getLowerSubclass` lives in the JJTree-headed but
hand-maintained `SQLMatchStatement.java` (Spotless-excluded under
`**/internal/core/sql/parser/**`), a minor coupling that argues mildly for isolating
that conversion.
**Proposed fix**: No change required — the track is within the soft footprint bound and
the ordering is genuinely coupled at the reconciliation core. If decomposition produces
an oversized core step, consider peeling the engine-primitive extraction and the
read-site conversions into their own steps to keep the reconciliation commit small. Left
as a suggestion because the decision survives.

## Evidence base

#### V1 [CONSTRUCTIBLE] I-A4 phantom registration on a failed schema commit
- **Invariant claim**: A failed commit registers no collection or engine; the next
  commit reuses the freed ids (D10, I-A4).
- **Violation construction**:
  1. Start state: an open storage; a tx creates one class (provisional collection id
     `<= -2`) plus a record in it, and commits as a schema-carrying (write-lock) commit.
  2. Action sequence: at commit, the write-lock branch runs reconciliation;
     `doAddCollection(atomicOperation, name, collectionPos)` is called
     (`AbstractStorage.java:4988`/`5002`) → `registerCollection`
     (`5026`) → `collectionMap.put` + `setCollection` (`5037`) publish the new collection
     into the in-memory `collections` array. Reconciliation continues; the
     record-position-allocation loop allocates into the now-live collection (D3 ordering).
  3. Intermediate state: `collections` and `collectionMap` hold a live entry for the new
     id; the WAL atomic operation has buffered the file-create intent (not yet applied —
     `commitChanges` has not run).
  4. Violation point: `commitEntry` or `commitIndexes` throws (the I-A4 "force a commit to
     fail at apply" condition). The `catch` at line 2377 sets `error`; the `finally` at
     2396-2398 calls `rollback(error, atomicOperation)`. `rollback` reverts the atomic
     operation (file intent discarded) but does **not** touch the in-memory `collections` /
     `collectionMap` / `indexEngines` registries.
  5. Observable consequence: the in-memory `collections` array retains a phantom entry at
     the new id, backed by no file. The next `doAddCollection` "first null slot" allocator
     (`4991`) skips that occupied slot, so the id is **not** reused — I-A4's
     "the next commit reuses the ids" assertion fails — and a later open may diverge between
     the in-memory registry and on-disk state.
- **Feasibility**: CONSTRUCTIBLE — the failure-injection point is exactly the I-A4 test's
  own "force a commit to fail at apply," and the registry-revert gap is visible in the
  commit method's failure `finally`.

#### V2 [THEORETICAL] Engine-id reservation absent from the five-item patch list
- **Invariant claim**: D2's commit-time patch list is complete at five items, covering every
  provisional reference that must resolve before serialization.
- **Violation construction**:
  1. Start state: a tx creates two classes plus an index on each (I-A3's "2+ engines"
     shape).
  2. Action sequence: reconciliation needs a commit-local engine id for each new engine.
     The live allocator `indexEngines.size()` (`AbstractStorage.java:2786`) is the same
     stale-after-deferral hazard the track flags for collection ids, but D2's five items
     are all collection-shaped (class id-list, inserted RIDs, `collectionsToClasses`
     re-key, provisional→real, changed-class property values).
  3. Intermediate state: if engine-id reservation is left to the live `indexEngines.size()`
     at `doAddIndexEngine` time, two engines created in the same commit before the first is
     published could both read the same `size()` and collide.
  4. Violation point: the second `doAddIndexEngine` reuses the first's id, or the engine id
     drifts from the index-manager record's expectation at `lockIndexes`.
  5. Observable consequence: a duplicate or wrong engine id; `lockIndexes` resolves the
     wrong engine or throws on a missing one.
- **Feasibility**: THEORETICAL — the track clearly *intends* the commit-local allocator to
  cover engines (D10 says "collection/engine ids from a commit-local allocator"), so the
  hazard is likely closed in implementation; the finding is that D2's patch-list count is
  presented as proven while the engine axis is not enumerated. Marked theoretical, graded
  should-fix on the spec-completeness axis, not the runtime-likelihood axis.

#### A1c [FRAGILE] The D6 write-amplification win is realized within Track 4's declared scope
- **Claim**: Track 4 realizes the deferred D6/D14 write-amplification win via the
  changed-class set.
- **Stress scenario**: a tx alters one property on one of fifty classes and commits. For
  the win, the commit must write exactly that one class's per-class record (plus the root
  if its non-link payload changed), not all fifty.
- **Code evidence**: `SchemaShared.toStream` (`SchemaShared.java:809,819,835`) iterates
  every live class and calls `c.toStream` for each, with no consultation of
  `getChangedClasses()`. Suppression of unchanged-record writes can only come from the
  record layer's per-property dirty marks, which is a different mechanism than "write only
  the changed class records." Track 4's in-scope file list (track-4.md:189-195) does not
  include `SchemaShared`, so the file that would carry a selective-write change is
  undeclared.
- **Verdict**: FRAGILE — the win may emerge from record-layer dirty suppression, but the
  track neither names the file nor adds an acceptance assertion that the per-class record
  count equals the changed-class count. The claim holds only if the dirty-mark path
  silently produces it; that is not stated or tested.

#### C1 [WEAK] Decision D9 — set-difference diff vs the changed-class set
- **Chosen approach**: Compute the create/drop structural delta as a set difference over
  committed-vs-tx-local **collection-id** sets (D9); compute which per-class **records** to
  write from the changed-class-**name** set (`getChangedClasses()`).
- **Best rejected alternative**: drive both from one signal (the changed-class set), with
  drop inferred as "a recorded name absent from the tx-local copy."
- **Counterargument trace**:
  1. The track uses two distinct mechanisms keyed on two distinct identities (collection id
     for structure, class name for record writes). `getChangedClasses()` returns
     `Set<String>` of names (`TxSchemaState.java:87`); the structural diff is over collection
     ids (`SchemaShared` collection-id sets).
  2. The rejected single-signal alternative is already half-built: Track 3's drop site
     records the dropped name and the rename site records the new name only, with the comment
     at `SchemaShared.java:579-591` documenting "a name in the changed-class set absent from
     the tx-local copy reads as a drop." That is precisely the single-signal drop inference.
  3. This produces a subtle coupling: the structural drop (by collection-id set difference)
     and the record drop (by name-absence) must agree. A class created then dropped within
     the same tx is in the changed-class set (create recorded the name) but absent from the
     tx-local copy (drop removed it) — so name-absence reads it as a drop, while the
     collection-id diff sees no committed collection to drop. The two mechanisms must
     reconcile the create-then-drop-in-one-tx case, which the Plan of Work does not call out.
- **Codebase evidence**: `SchemaEmbedded.createClassInternal:224` (records name on create),
  `SchemaEmbedded.dropClassInternal:484` (records name on drop, then `classes.remove`),
  `SchemaShared.changeClassName:591` (records new name only). The create-then-drop case
  leaves the name in the set and absent from the copy with no committed collection.
- **Survival test**: WEAK — D9 survives as a structural-diff decision (collection id is the
  right structural identity), but the interaction between the collection-id structural diff
  and the name-based record-write/drop signal for the create-then-drop-in-one-tx and
  rename-then-drop cases is unspecified in the Plan of Work. Folded into A2's proposed test
  additions rather than raised as a separate finding, since no concrete corruption was
  constructed (the per-class write's own previously-linked set-difference at
  `SchemaShared.java:843` likely absorbs it) — recorded here so decomposition adds the
  edge-case test.

#### C2 [YES] Decision D19 — exactly two lock-based read sites convert to snapshot-first
- **Chosen approach**: Convert `YTDBGraphImplAbstract.createVertexWithClass` and
  `SQLMatchStatement.getLowerSubclass` to snapshot-first so the whole-commit write lock is
  not a read outage.
- **Best rejected alternative**: re-audit whether exactly two lock-based reader sites remain
  (a third would still outage under the write lock).
- **Counterargument trace**: The completeness of the two-site inventory is a D19 design
  fact, already vetted by the research-log gate and out of scope under the D9 narrowing. Both
  named sites exist and resolve schema through `schema.getClass` (`createVertexWithClass`
  via `executeSchemaCode`/`acquireSession`; `getLowerSubclass` directly), so the conversion
  target is real. `getLowerSubclass` is duplicated in `MatchExecutionPlanner.java:4925`, but
  the track names the `SQLMatchStatement` copy, which is the one on the MATCH read path.
- **Codebase evidence**: `YTDBGraphImplAbstract.createVertexWithClass` (len 719, calls
  `schema.getClass`/`getOrCreateClass`); `SQLMatchStatement.getLowerSubclass` (len 806, calls
  `schema.getClass`); the file is git-tracked and hand-maintained despite the JJTree header
  and is editable.
- **Survival test**: YES — the named sites are correct and the conversion is feasible.
  No finding; recorded to show the cross-track-reality check on the read sites passed.

#### S1 [SURVIVES] Scope and sizing
- **Chosen approach**: one track, ~15 files, eight strictly-ordered concerns.
- **Best rejected alternative**: split out the engine-primitive extraction and the two
  read-site conversions.
- **Counterargument trace**: The reconciliation core (diff, provisional patch, ordered
  reconcile, promotion) is genuinely coupled and cannot split without inter-step
  half-states. The extraction and the read conversions are separable but small. The track is
  within the soft footprint bound (~15 ≤ ~20-25), so it passes planning without a written
  justification.
- **Codebase evidence**: Plan of Work enumerates the eight concerns (track-4.md:120-138); the
  extraction and read conversions have no data dependency on the reconciliation core.
- **Survival test**: SURVIVES — sizing is acceptable; the split is a decomposition-time
  suggestion, not a track-level defect.
