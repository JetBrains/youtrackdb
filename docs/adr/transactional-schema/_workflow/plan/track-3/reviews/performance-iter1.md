<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: PF1, sev: suggestion, loc: SchemaShared.java:53, anchor: "### PF1 ", cert: C1, basis: "copyForTx holds the committed write lock across record I/O (N class-record loads/allocs/deletes), not just serialize+re-parse; benign under the low-schema-change premise, worth a forward note for a populated-schema / high-DDL workload"}
evidence_base: {section: "## Evidence base", certs: 4, matches: 4}
cert_index:
  - {id: C1, verdict: CONFIRMED-NOTE, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [suggestion] copyForTx holds the committed schema write lock across record I/O, not just serialize + re-parse

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 53-66)

**Issue**: The held-lock window is heavier than the focus framing ("serialize the whole schema, re-parse it") implies, but it is benign under this design's premises and needs no change now. The framing matters for the forward-looking workloads the plan defers (populated-class index build, YTDB-1064; high-frequency DDL), so it is worth recording.

`copyForTx` holds the committed `SchemaShared.lock` write lock across three phases, and the first and third do record I/O, not pure in-memory serialization:

- `toStream(session)` (SchemaShared.java:736-803) does, per live class: `session.load(boundRid)` for an already-bound class, or `session.newInternalInstance()` + link-set add for an unbound one; then `c.toStream(session, classRecord)` re-serializing the class into its record; plus `session.load(rid)` + `delete()` for every previously-linked record no longer backing a live class. So `toStream` touches one record per class (load or allocate) and re-serializes each.
- `newInstanceForCopy()` — one object allocation.
- `copy.fromStream(session, serialized)` (SchemaShared.java:542-728) re-reads the root record's `classes` link set and calls `session.load(classRid)` **again** for every class, re-parses each into a fresh `SchemaClassImpl`, then rebuilds the inheritance tree and the cross-class derived state (`setSuperClassesInternal`, the polymorphic-collection ripple).

So the window is roughly `2 × N` record loads (N once in `toStream`, N again in `fromStream`), `N` re-serializations, `N` re-parses, and one full inheritance/derived-state recompute, for N = class count — plus the transient `EntityImpl` and collection allocations the round trip churns. The loads ride the caller's open transaction (the method opens none of its own); in steady state the schema records are page-cache-resident, so the I/O is cache reads rather than disk seeks, but it is still buffer-touch and parse work under the lock, not a memcpy.

**Evidence**: COST TRACE C1 and SCALE CHECK C1 below. The premise that neutralizes this (low schema-change rate, single serialized schema writer) is C2/C3; the no-production-caller-yet fact is C4.

**Impact**: None at the target operating point. The window is a one-time cost per schema-changing transaction's first write, and the committed write lock it holds is (a) already exclusive of a second schema writer via `MetadataWriteMutex` upstream (D5/D7), and (b) orthogonal to the hot read path, which the design routes through snapshot reads that never take this lock (D19/I-A6). At a realistic schema (dozens to low-hundreds of classes) the `2 × N` loads and the derived-state recompute are microseconds-to-low-milliseconds of cache-resident work, dwarfed by the surrounding transaction. The window only becomes a stall worth measuring if a future workload combines a large class count (thousands) with a high DDL rate — exactly the corner the plan's load-bearing premise (Constraints) and the YTDB-1064 index-build deferral set aside.

**Suggestion**: No code change for this step. Carry the lock-window characterization forward to Track 4 (commit-time promotion, which also runs under a held lock) and to the YTDB-1064 populated-schema follow-up: if either profiling or a large-schema benchmark later shows this window matters, the deferred D8 alternative B (immutable committed base + changed-class overlay map) was rejected for read-path complexity, not for build cost, so it remains the natural escape hatch — re-cost it there rather than here. Optionally, a one-line note on `copyForTx` that the window includes per-class record I/O (not only serialization) would keep the next reader from under-estimating it.

## Evidence base

#### C1 COST TRACE + SCALE CHECK for SchemaShared.copyForTx (SchemaShared.java:53-66) — CONFIRMED as a forward note, not an issue
CONFIRMED: the lock window does per-class record I/O under the committed write lock (`toStream` load/alloc/delete per class + `fromStream` re-load per class + derived-state recompute, ~`2 × N` loads for class count N), heavier than "serialize + re-parse", but the SCALE CHECK verdict is NEGLIGIBLE at the target operating point and MATTERS-AT-SCALE only under a large-class-count + high-DDL workload the plan's premise excludes — so it is recorded as a suggestion-level forward note, not a defect. (OPERATION: serialize N class records + re-parse N class records + rebuild inheritance/derived state, all under `lock.writeLock()`. COMPLEXITY: O(N) record loads + O(N + inheritance-edges) recompute per copy. DATA SCALE: N = schema class count, bounded dozens–low-hundreds typical. ALLOCATIONS: O(N) transient `EntityImpl`/collection objects + 1 `SchemaShared`. I/O: ~`2 × N` record loads riding the caller's tx, cache-resident in steady state, no fsync. LOCK HOLD TIME: includes the record-I/O round trip, not computation-only. AT SMALL/MEDIUM SCALE: negligible. AT a pathological thousands-of-classes + high-DDL workload: noticeable, but excluded by the low-schema-change-rate premise. VERDICT: NEGLIGIBLE now / MATTERS-AT-SCALE only outside the premise.)

#### C2 PREMISE — copyForTx frequency is gated by the low-schema-change rate — REFUTES any "hot path" reading
The plan Constraints name the low schema-change rate as "the load-bearing premise" that makes pessimistic serialization (D5/D7), the whole-commit exclusive lock (D19), and the in-commit index build (D12) acceptable. D8 states the copy is "cheap to build and built rarely (D5)". `copyForTx` runs once per schema-changing transaction's first write, not per record, per query, or per data commit. A finding that treated this window as a hot-path cost would be refuted by the design's own operating-point assumption — the cost is real but rare. This is why C1 lands as a note, not a should-fix.

#### C3 PREMISE — the held lock is not contended by a second schema writer — REFUTES a lock-contention reading
The lock held is the *committed* `SchemaShared.lock` write lock. A second schema-changing transaction is serialized upstream by `MetadataWriteMutex` (`Semaphore(1)`, D5/D7), engaged above the shared locks before the seed, so it never reaches `copyForTx` concurrently. The remaining potential contender is a lock-based committed-schema read, but the design converts the hot read path to snapshot-first reads that do not take this lock (D19, I-A6), and the track's own Episodes assert the mutex holder does not hold `SchemaShared.lock` during the tx. A lock-contention finding against this window would therefore be refuted: the exclusive lock is held against an already-excluded population. (Reference accuracy: `MetadataWriteMutex` and the snapshot-first read conversion are Track 3 Step 4 / Track 4 deliverables, not in this step's diff — the refutation rests on the plan/track design contract, not on code present here. If those deliverables diverge from the plan, this premise should be re-checked at Track 4 review.)

#### C4 REFERENCE-ACCURACY — copyForTx has no production caller in this step (PSI-verified) — REFUTES any runtime-allocation-pressure reading
PSI find-usages on `SchemaShared.copyForTx` returns 8 references: one Javadoc `{@link}` in SchemaShared.java:169, two Javadoc references in TxSchemaState.java (33, 58), and five test sites in CopyForTxTest.java (14 is a `{@link}`, 35/61/97/129 are calls). Zero non-test production callers. `newInstanceForCopy` has one production usage (the `copyForTx` body at SchemaShared.java:156) and one override (`SchemaEmbedded#newInstanceForCopy`), correctly polymorphic. So in this step the round trip executes only under test; the production seed (proxy routing on first schema write) lands in Step 2. Any finding asserting runtime allocation/GC pressure *from this step* would be refuted — the path is not yet reachable in production. The allocation characterization in C1 is therefore a property of the method as it will be driven later, validated against the design's frequency premise (C2), not a measured runtime cost of this commit. (PSI via mcp-steroid, project `transactional-schema` aligned; reference-accuracy is authoritative, not a grep-caveat finding.)
