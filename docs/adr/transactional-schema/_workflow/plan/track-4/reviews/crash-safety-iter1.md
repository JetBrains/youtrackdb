<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: CS1, sev: suggestion, loc: AbstractStorage.java:2877-2880, anchor: "### CS1 ", cert: C5, basis: "New javadoc claims the A1/R1 deferral discipline as active, but publishIndexEngine/registerCollection still run inside the atomic-op lambda this step; deferral is Step 2 work. Doc-vs-current-state mismatch only, no durability defect."}
evidence_base: {section: "## Evidence base", certs: 5, matches: 4}
cert_index:
  - {id: C1, verdict: SAFE, anchor: "#### C1 "}
  - {id: C2, verdict: SAFE, anchor: "#### C2 "}
  - {id: C3, verdict: SAFE, anchor: "#### C3 "}
  - {id: C4, verdict: SAFE, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### CS1 [suggestion] Javadoc claims the A1/R1 deferral discipline as active, but this step does not yet defer the in-memory publish past the atomic operation

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (lines 2877-2880 `publishIndexEngine`; 2825-2845 and 5115-5137 the two seam javadocs; 5103-5113 `doAddCollection`; 2802-2806 the public `addIndexEngine` lambda)

**What is true and safe (the load-bearing fact):** this is a pure extraction. For both public wrappers, the in-memory publish still runs **inside** the same `calculateInsideAtomicOperation` lambda as the WAL-emitting work, exactly as before the split:

- `addIndexEngine` (line 2802-2804): `doAddIndexEngine(...)` then `publishIndexEngine(...)` are both inside the `calculateInsideAtomicOperation` lambda, before the lambda returns.
- `addCollection` → `doAddCollection` → `registerCollection` (line 5106-5112): `registerCollection` is the lambda's return value, inside the atomic op.

So no new crash window is introduced versus the original, and the durable WAL coverage is byte-for-byte identical (see C1-C4).

**The mismatch:** the new javadoc on `doAddIndexEngine` (2829-2835), `publishIndexEngine` (2865-2872), and `doCreateCollection` (5115-5125) states the discipline as if already in force — "The caller publishes the returned engine ... only after the atomic operation has committed, so a failed commit leaves no phantom in-memory registration." That is the D10 / A1/R1 target state, but it is **not** what this step's callers do. The public wrappers still publish inside the atomic op, so a failed commit (fault at/after `commitChanges`) would still leave a phantom in-memory registration today. D10's required deferral ("publication deferred to the post-`commitChanges` success path, or undone in the failure `finally`") is Step 2's commit-window work, not realized here.

**Crash scenario (why this is only informational):** the only path that reaches these primitives in this step is the public wrapper, whose publish-inside-the-lambda behavior is unchanged and crash-safe — on a crash before the publish, the durable config + files are re-read and re-published at open. The phantom-registration hazard the javadoc describes is reachable only from the not-yet-wired commit window. So this step ships no durability regression; the doc just describes a future state as present.

**Recovery impact:** none for this step. For Step 2, the risk is that a reader trusts the seam javadoc and assumes the deferral is already wired, and so does not add the deferred / failure-reverting publication the commit path needs (D10 A1/R1, I-A4). The Track-4 Validation "Force a commit to fail at apply" test is the guard that must actually exercise the deferral.

**Refutation considered:** I checked whether the public wrappers themselves already defer publication past commit — they do not; the publish is the lambda return value, inside the atomic op (PSI-confirmed call sites). I checked whether a failed atomic op in the public path leaves a phantom registration that would actually corrupt anything — it does not in practice, because the public wrappers pre-check `collectionMap.containsKey` under the write lock and the atomic-op failure path is the existing, tested one; the deferral matters only for the commit window's batched multi-structure case. So the finding is a doc-accuracy / Step-2-handoff note, not a durability bug in this step.

**Suggestion:** either (a) soften the three seam javadocs to say the caller is *expected* to defer publication (with the deferral landing in the commit window, Step 2), making clear the public wrappers publish inside the atomic op as before; or (b) leave as-is but ensure the Step 2 review explicitly verifies the commit path does the deferral / failure-revert the javadoc promises, with the "Force a commit to fail at apply" test (I-A4) as the gate. No change required to ship this step.

## Evidence base

#### C1 Index-create durable write path is unchanged (config key, registry key, engine id) — SAFE
Original inlined `addIndexEngine` body (patch lines 24-40): `engine.create(atomicOperation)` + histogram + `indexEngineNameMap.put(indexMetadata.getName(), engine)` + `indexEngines.add(engine)` + `configuration.addIndexEngine(atomicOperation, indexMetadata.getName(), engineData)`. New: `doAddIndexEngine` does `engine.create` + histogram + `configuration.addIndexEngine(atomicOperation, engineData.getName(), engineData)`; then `publishIndexEngine(engineData.getIndexId(), engine)` does `indexEngineNameMap.put(engine.getName(), engine)` + `setIndexEngine(internalId, engine)`.
PSI-verified equivalences:
- `IndexEngineData` 10-param ctor (config.IndexEngineData) sets `this.name = metadata.getName()`, so `engineData.getName() == indexMetadata.getName()` — durable config-entry key unchanged.
- `DefaultIndexFactory.createIndexEngine` wires every production engine (`BTreeSingleValueIndexEngine`, `BTreeMultiValueIndexEngine`, `RemoteIndexEngine`) `name` field from `data.getName()`, and each `getName()` returns that field, so `engine.getName() == engineData.getName() == indexMetadata.getName()` — `indexEngineNameMap` key unchanged.
- `engineData.getIndexId()` returns the ctor `indexId == generatedId == indexEngines.size()`; `setIndexEngine(size, engine)` takes the `size <= id` branch with an empty `while`, i.e. `indexEngines.add(engine)` — identical to the original append.
The single durable WAL op (`config.addIndexEngine`) reordered before the in-memory publish, but both stay inside the same `calculateInsideAtomicOperation` lambda; the in-memory publish is not a WAL op, so WAL coverage is identical.

#### C2 Index-delete is a pure extraction with unchanged durable ops — SAFE
Original `deleteIndexEngine` lambda: `engine.delete(atomicOperation)` + `config.deleteIndexEngine(atomicOperation, engine.getName())`. New `doDeleteIndexEngine` body is character-identical. The deferred in-memory map mutation (`indexEngines.set(id, null)` + `indexEngineNameMap.remove`) stays after the atomic op in the public wrapper (lines 3112-3117), exactly as before. No durable change.

#### C3 Collection-create durable write path is unchanged; `createComponent` id and reordering are safe — SAFE
Original `doAddCollection(...,collectionPos)`: create object → `configure(collectionPos, name)` → `collection.create(atomicOperation)` [WAL] → `createdCollectionId = registerCollection(collection)` [in-memory] → `config.updateCollection(...)` [WAL] → `linkCollectionsBTreeManager.createComponent(atomicOperation, createdCollectionId)` [WAL]. New: `doCreateCollection` runs `collection.create` + `config.updateCollection` + `createComponent(atomicOperation, collectionPos)` [all WAL], then `doAddCollection` runs `registerCollection(collection)` [in-memory] last.
PSI-verified: `configure(collectionPos, name)` → `PaginatedCollectionV2.init` sets `this.id = collectionPos`; `getId()` returns it; `registerCollection` returns `collection.getId()`. So `createdCollectionId == collectionPos` — the durable `createComponent` id (file name `linkbag_<collectionId>`) is unchanged.
`LinkCollectionsBTreeManagerShared.createComponent` keys only on the passed `collectionId` (builds `SharedLinkBagBTree`, `bTree.create(operation)`, stores in its own `fileIdBTreeMap`); it does **not** read `AbstractStorage.collections`/`collectionMap`. So moving it before `registerCollection` (in-memory publish) introduces no dependency violation. Both public `addCollection` wrappers invoke `doAddCollection` inside `calculateInsideAtomicOperation`, so the publish stays inside the atomic op — same crash window as before.

#### C4 `doGetIndexEngine` is read-only and preserves the `InvalidIndexEngineIdException` retry contract — SAFE
`doGetIndexEngine` does `checkIndexId(internalId)` + `indexEngines.get(internalId)` + assert; no durable state. `getIndexEngine` now delegates to it (still under `stateLock.readLock()`), keeping a `checkIndexId` before the lock and adding the inner check under the lock (a marginal hardening, not a regression). It still throws `InvalidIndexEngineIdException` on a missing/invalid id. PSI-confirmed the retry consumer `IndexAbstract.acquireAtomicExclusiveLock` loops `getIndexEngine` catching `InvalidIndexEngineIdException` → `doReloadIndexEngine`, and the histogram methods retry the same way; the contract is intact. The lock-free resolver itself is not yet reached by any commit consumer in this step (Step 2 wiring); it is exercised only by the white-box test under a held write lock.

#### C5 Seam javadoc describes the A1/R1 deferral as active while the step's callers publish inside the atomic op — CONFIRMED (see CS1)
The `doAddIndexEngine` / `publishIndexEngine` / `doCreateCollection` javadocs assert publication happens only after the atomic op commits, but the public wrappers (the only callers in this step) publish inside the `calculateInsideAtomicOperation` lambda. PSI-confirmed: `addIndexEngine` lambda calls `publishIndexEngine` before returning; `doAddCollection` returns `registerCollection(collection)` from inside the lambda. The deferral D10 / A1/R1 requires is Step 2's commit-window work and is not realized here. No durability defect this step; doc-vs-current-state mismatch and a Step-2 handoff hazard only.
