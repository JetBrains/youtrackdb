# Performance Analysis: State-Lock Separation & Read Path Profiling

**Date**: 2026-03-11
**Branch**: `ytdb-525-state-lock-separation` (YTDB-556)
**Environment**: Hetzner CCX33 (8 dedicated AMD EPYC vCPUs, 32 GB RAM), Ubuntu 24.04, JDK 21.0.10

## 1. State-Lock Separation Changes

The branch replaces the coarse-grained `stateLock.writeLock()` in DDL operations with
`stateLock.readLock() + ddlLock` and introduces TX-scoped lifecycle guard with read-side
reentrancy in `ScalableRWLock`. Key changes:

| Commit | Change |
|--------|--------|
| Replace `collectionMap` with `ConcurrentHashMap` | DDL reads no longer need exclusive lock |
| Replace `indexEngines` with `CopyOnWriteArrayList` | Lock-free reads on index engine list |
| Replace `indexEngineNameMap` with `ConcurrentHashMap` | Lock-free reads on index name map |
| Add read-side reentrancy to `ScalableRWLock` | Nested `sharedLock()` calls: ~1ns (plain int increment, no memory barriers) |
| Introduce `ddlLock` for DDL operations | DDL serialized by `ReentrantLock`, not `stateLock.writeLock()` |
| TX-scoped lifecycle guard in `FrontendTransactionImpl` | Pin `stateLock.readLock()` at TX begin; all subsequent storage ops hit reentrant fast path |

### What changed on the read path

Before: every `readRecordInternal()`, `getCollectionNames()`, `getIndexEngine()`, etc. called
`stateLock.readLock().lock()` with full Dekker protocol: `AtomicInteger.set(READING)` (StoreLoad
barrier ~20-40ns) + `StampedLock.isWriteLocked()` (volatile read).

After: the TX pins the read lock at `begin()`. All subsequent storage operations hit the
reentrant fast path: `reentrantCount++` (plain int increment, ~1ns, zero memory barriers).

There are 66 `stateLock.readLock().lock()` call sites in `AbstractStorage`. A single vertex
traversal of 1000 edges triggers ~1000+ read lock acquisitions.

## 2. Benchmark Results

### 2.1 JVM Flags

Previous benchmark runs used test-mode JVM flags (`-ea`, `-Dyoutrackdb.memory.directMemory.trackMode=true`,
`-Dyoutrackdb.storage.diskCache.checksumMode=StoreAndThrow`) which added significant overhead
not present in production. All results below use **production-equivalent flags**:

```
-Xms4096m -Xmx4096m
--add-opens jdk.unsupported/sun.misc=ALL-UNNAMED
--add-opens java.base/sun.security.x509=ALL-UNNAMED
--add-opens=java.base/java.io=ALL-UNNAMED
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.cs=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
--add-opens=java.base/java.net=ALL-UNNAMED
```

Impact of removing debug flags: **~60-70% throughput increase** across all benchmarks
(e.g., `st_getVertices`: 948 -> 1,563 ops/s).

### 2.2 VertexTraversalMTBenchmark

Star graph: 1 center vertex + 1000 outer vertices via `HeavyEdge` class, DISK database.
JMH: 3 forks, 5 warmup (2s), 10 measurement (2s). Mode: throughput (ops/s, higher is better).

#### Single-Threaded

| Benchmark | BEFORE (develop) | AFTER (branch) | Change |
|-----------|-----------------|----------------|--------|
| `st_getVertices` | 1,563 +/- 29 | 1,584 +/- 19 | +1.4% (noise) |
| `st_getEdgesThenVertex` | 696 +/- 19 | 682 +/- 5 | -2.0% (noise) |
| `st_matchQuery` | 1,158 +/- 15 | 1,171 +/- 32 | +1.1% (noise) |

#### Multi-Threaded (8 threads)

| Benchmark | BEFORE (develop) | AFTER (branch) | Change |
|-----------|-----------------|----------------|--------|
| `mt_getVertices` | 5,061 +/- 79 | 4,964 +/- 159 | -1.9% (noise) |
| `mt_getEdgesThenVertex` | 2,323 +/- 36 | 2,510 +/- 44 | **+8.1%** |
| `mt_matchQuery` | 4,041 +/- 90 | 4,175 +/- 29 | **+3.3%** |

#### Scalability (MT/ST ratio)

| Benchmark | BEFORE | AFTER |
|-----------|--------|-------|
| `getVertices` | 3.24x | 3.13x |
| `getEdgesThenVertex` | 3.34x | 3.68x |
| `matchQuery` | 3.49x | 3.57x |

### 2.3 MatchPlanCacheBenchmark (single-threaded)

Tiny graph (1 center + 2 outer vertices, 2 edges), MEMORY database. Mode: average time (us/op, lower is better).
3 forks, 5 warmup (2s), 10 measurement (2s).

| Benchmark | BEFORE | AFTER | Change |
|-----------|--------|-------|--------|
| `matchWithCache` | 12.992 +/- 0.220 | 12.708 +/- 0.246 | -2.2% (noise) |
| `matchNoCache` | 16.926 +/- 0.218 | 16.757 +/- 0.128 | -1.0% (noise) |

No regression. The tiny graph has minimal lock acquisitions per query, so the reentrant
fast path provides negligible benefit here.

## 3. Profiling Analysis

All profiles collected using **async-profiler 3.0** on Hetzner CCX33, production JVM flags (no `-ea`).
Workload: 3000 warmup + 10000 measurement iterations.

### 3.1 Why the lock overhead improvement is small

The lock cost in the AFTER version (with reentrant fast path) is **0.93% of total CPU**:

| Method | CPU % |
|--------|-------|
| `ScalableRWLock.sharedLock` | 0.51% |
| `ScalableRWLock.sharedUnlock` | 0.42% |

Even though the reentrant path eliminates StoreLoad barriers (~20-40ns each on x86),
**the `ThreadLocal.get()` call remains on every invocation** (`entry.get()` in `sharedLock()`).
The theoretical saving is ~50us per 1000-vertex traversal (~920us/op), which is ~5.4% --
at the noise floor for DISK benchmarks with fork-to-fork variance.

On x86 (TSO memory model), `AtomicInteger.set()` compiles to `LOCK XCHG` or `MOV` + `MFENCE`.
The CPU store buffer hides much of the latency under zero contention. The improvement would
be larger on **ARM/RISC-V** where StoreLoad barriers are more expensive.

### 3.2 CPU Profile: getVertices (ST vs MT)

#### Single-Threaded -- Top 10

| CPU % | Method | Category |
|-------|--------|----------|
| 3.90% | `ScopedMemoryAccess.getLongUnalignedInternal` | Page buffer read |
| 2.70% | `HashMap.getNode` | Hash lookups |
| 1.82% | `do_user_addr_fault` (kernel) | Page faults |
| 1.66% | `ConcurrentHashMap.get` | Page cache lookups |
| 1.66% | `Unsafe.convEndian` | Byte order conversion |
| 1.14% | `LongSerializer.deserialize` | LinkBag key deserialization |
| 1.04% | `FrequencySketch.incrementAt` | TinyLFU frequency counter |
| 1.04% | `ThreadLocal$ThreadLocalMap.getEntry` | ThreadLocal lookups |
| 0.94% | `_raw_spin_unlock_irqrestore` (kernel) | Kernel spinlock |
| 0.88% | `VarHandleReferences$Array.setRelease` | Array element stores |

**Flat profile**: no single hot method. Dominated by page buffer reads, hash lookups, and
deserialization. Pure compute, not I/O or locks.

#### Multi-Threaded (8 threads) -- Top 10

| CPU % | Method | Category |
|-------|--------|----------|
| **6.99%** | `LockFreeReadCache.doLoad` | Page cache lookup -- **#1 contention source** |
| 5.05% | `HashMap.getNode` | Hash lookups |
| **3.95%** | `AtomicIntegerFieldUpdater.CAS` | Cache entry ref counting |
| **2.30%** | `CacheEntryImpl.setNext` | LRU list pointer updates |
| **2.06%** | `FrequencySketch.incrementAt` | TinyLFU admission counters |
| 2.06% | `Unsafe.convEndian` | Byte order conversion |
| **1.96%** | `DurablePage.<init>` | Page wrapper allocation |
| 1.72% | `DirectByteBuffer.getInt` | Buffer reads |
| 1.64% | `ThreadLocal$ThreadLocalMap.getEntry` | ThreadLocal lookups |
| **1.37%** | `LRUList.moveToTheTail` | LRU eviction ordering |

**The page cache (`LockFreeReadCache`) is the #1 scalability bottleneck.** All 8 threads
access the same 1000 vertex pages, causing CAS contention on reference counts,
LRU pointer chasing, and frequency sketch updates. Methods marked in bold are cache
infrastructure -- they total **~17%** of CPU in MT vs **~4%** in ST.

### 3.3 CPU Profile: matchQuery (ST)

| CPU % | Method | Category |
|-------|--------|----------|
| 2.58% | `HashMap.getNode` | Hash lookups in match result rows, contexts |
| 2.32% | `SQLProjection.isExpand` | Per-row projection type check |
| 2.11% | `ScopedMemoryAccess.getLongUnalignedInternal` | Page buffer reads |
| 2.07% | `ConcurrentHashMap.get` | Page cache lookups |
| 1.69% | `RecordId.equals` | RID comparisons during traversal |
| 1.23% | `HashMap.resize` | Growing hash tables |
| 1.06% | `AtomicOperationsManager.executeReadOperation` | TX operation wrapper |
| 0.76% | `readbuffer.BoundedBuffer$RingBuffer.offer` | Cache read buffer |
| 0.72% | `PaginatedCollectionV2.internalReadRecord` | Record reading |
| 0.68% | `FrequencySketch.incrementAt` | TinyLFU counter |
| 0.68% | `FrequencySketch.indexOf` | TinyLFU hash |
| 0.68% | `AtomicIntegerFieldUpdater.CAS` | Cache ref counting |

**Flat profile: top method is only 2.58%.** CPU is spread across ~50+ methods
each at <2.5%. This is a "death by a thousand cuts" profile -- many small hash
lookups, RID comparisons, serialization, cache bookkeeping.

### 3.4 CPU Profile: matchQuery (MT) -- Contention Shifts

| ST % | MT % | Method | Scaling impact |
|------|------|--------|----------------|
| 0.68% | **3.31%** | `AtomicIntegerFieldUpdater.CAS` | 4.9x worse -- cache entry ref counting |
| -- | **2.48%** | `AtomicIntegerFieldUpdater.get` | Volatile reads under contention |
| 0.89% | **4.16%** | `AtomicOperationsManager.executeReadOperation` | 4.7x worse -- TX read op wrapper |
| -- | **1.90%** | `CacheEntryImpl.setNext` | LRU pointer updates |
| -- | **1.37%** | `LRUList.moveToTheTail` | LRU list contention |

Under MT load, the page cache concurrency infrastructure becomes the dominant cost,
not the query logic itself.

### 3.5 Allocation Profile: matchQuery (ST)

| Alloc % | Class | Count context |
|---------|-------|---------------|
| 14.0% | `byte[]` | Raw record buffers from page reads |
| 9.2% | `VertexEntityImpl` | One per vertex loaded (1000/query) |
| 7.6% | `PageKey` | One per page cache lookup |
| 5.7% | `HashMap$Node` | Match result row entries |
| 5.5% | `EdgeKey` | One per edge in LinkBag |
| 4.9% | `MatchResultRow` | Match engine intermediate results |
| 4.2% | `RecordId` | RID objects during traversal |
| 4.2% | `HashMap` | Per-result-row HashMap allocations |
| 3.7% | `HashMap$Node[]` | HashMap bucket arrays |
| 3.6% | `ResultInternal` | SQL result wrapper objects |
| 3.0% | `LinkBagValue` | LinkBag entry values |
| 2.9% | `WeakRefValue` | Record cache weak references |
| 2.9% | `RawBuffer` | Raw record buffer wrappers |
| 2.7% | `PaginatedCollectionV2$$Lambda` | Read operation lambdas |
| 2.6% | `Object[]` | Generic arrays |
| 2.6% | `CollectionPositionMapBucket$PositionEntry` | Collection position entries |
| 2.3% | `String` | String allocations |
| 2.2% | `ArrayList` | List allocations |
| 2.2% | `RawPair` | Key-value pairs |
| 2.2% | `RidPair` | RID pairs |

**~15+ objects allocated per vertex visited.** Dominated by per-record objects:
`byte[]` buffers, `VertexEntityImpl`, `PageKey`, `RecordId`, `EdgeKey`.
The match engine adds `MatchResultRow`, `ResultInternal`, `HashMap` per result row.

### 3.6 Allocation Profile: getVertices (ST vs MT)

| Alloc % | Class | Notes |
|---------|-------|-------|
| 17.5% | `byte[]` | Raw record buffers |
| 11.8% | `VertexEntityImpl` | 1000 per operation |
| 9.1% | `PageKey` | Per cache lookup |
| 7.1% | `EdgeKey` | Per edge in LinkBag |
| 5.4% | `RecordId` | Per RID |
| 3.8% | `PaginatedCollectionV2$$Lambda` | Read op lambdas |
| 3.7% | `LinkBagValue` | LinkBag entries |
| 3.6% | `WeakRefValue` | Record cache weak refs |
| 3.6% | `HashMap$Node` | Internal hash maps |
| 3.6% | `RawBuffer` | Raw buffer wrappers |

MT allocation profile is proportionally identical -- same objects, 8x the volume.
No new allocation sources appear under contention.

### 3.7 Cache Miss Profile: matchQuery (ST)

| Miss % | Method | Cause |
|--------|--------|-------|
| 1.85% | `libc.so.6` | OS-level memory operations (mmap) |
| 1.18% | `HashMap.resize` | Growing hash tables -> cold memory |
| 1.13% | `PaginatedCollectionV2.internalReadRecord` | Reading record data from page buffers |
| 1.08% | `WeakValueHashMap.forEach` | Record cache iteration -- pointer chasing |
| 0.89% | `AtomicIntegerFieldUpdater.CAS` | Cache line bouncing on shared counters |
| 0.88% | `RecordCacheWeakRefs$$Lambda.accept` | Record cache eviction callbacks |
| 0.82% | `HashMap$Node.getKey` | Hash node key access |
| 0.77% | `HashMap$HashIterator.nextNode` | Hash iteration -- pointer chasing |

Cache misses are spread thinly -- no single dominant source. Biggest contributors
are HashMap resize (allocating cold memory), record page reading, and
WeakValueHashMap pointer chasing.

### 3.8 Cache Miss Profile: getVertices (ST vs MT)

| ST Miss % | MT Miss % | Method | Notes |
|-----------|-----------|--------|-------|
| 1.12% | **3.84%** | `HashMap.resize` | Growing hash tables |
| 1.02% | **4.33%** | `RecordCacheWeakRefs$$Lambda.accept` | Record cache iteration |
| 0.88% | **3.65%** | `HashMap$HashIterator.nextNode` | Hash iteration |
| 0.95% | **3.10%** | `AtomicIntegerFieldUpdater.CAS` | Cache entry ref counting |
| -- | **3.13%** | `CacheEntryImpl.setNext` | LRU pointer updates |
| 0.82% | **2.92%** | `WeakValueHashMap.forEach` | Record cache pointer chasing |

Under MT, cache misses **explode** in the page cache and record cache infrastructure.
`CacheEntryImpl.setNext` and `AtomicIntegerFieldUpdater.CAS` suffer from **cache line
bouncing** -- multiple threads writing to the same cache lines. `HashMap.resize` and
`WeakValueHashMap.forEach` suffer from **pointer chasing** through cold memory.

## 4. Key Findings

### 4.1 State-lock separation works correctly

- No single-threaded regression in any benchmark
- +8.1% improvement on `mt_getEdgesThenVertex` (heaviest read path)
- +3.3% improvement on `mt_matchQuery`
- DDL no longer blocks concurrent readers (architectural improvement)

### 4.2 Lock overhead is not the bottleneck

`ScalableRWLock.sharedLock` + `sharedUnlock` = **0.93% of CPU** in ST.
Even eliminating lock overhead entirely would yield <1% improvement.
The `ThreadLocal.get()` call in the reentrant fast path is still ~5-10ns --
comparable to the barrier cost it replaces on x86/TSO.

### 4.3 The page cache is the scalability bottleneck

Under 8-thread load, cache infrastructure (`LockFreeReadCache.doLoad`,
`CacheEntryImpl.setNext`, `LRUList.moveToTheTail`, `FrequencySketch.incrementAt`,
`AtomicIntegerFieldUpdater.CAS`) consumes **~17% of CPU** vs ~4% in ST.
This is the primary factor limiting MT scalability to ~3.5x on 8 cores.

### 4.4 The read path has a flat CPU profile

No single method exceeds 4% of CPU in ST. The work is distributed across
hash lookups, RID comparisons, serialization, page buffer reads, and cache
bookkeeping. This is a "death by a thousand cuts" profile.

### 4.5 Allocation pressure is high

~15+ objects allocated per vertex visited: `byte[]`, `VertexEntityImpl`, `PageKey`,
`RecordId`, `EdgeKey`, `HashMap`, `MatchResultRow`, etc. GC pressure and cache
pollution from short-lived objects contribute to throughput limits.

## 5. Optimization Opportunities

### 5.1 Reduce allocation pressure (highest impact)

`PageKey`, `RecordId`, `EdgeKey` are small, short-lived objects created in huge
quantities (1000+ per operation). Strategies:
- **Object pooling** or thread-local reuse for `PageKey`, `RawBuffer`
- **Zero-copy deserialization**: read directly from page buffers instead of copying
  into `byte[]` arrays (14% of allocations). Hold a cache pin during deserialization.
- **Value-based classes** (future: Project Valhalla) for `RecordId`, `PageKey`, `EdgeKey`

### 5.2 Reduce per-row overhead in MATCH engine

The match engine allocates `MatchResultRow` (backed by `HashMap`), `ResultInternal`,
and `HashMap$Node[]` per result row. For single-hop traversals returning a few fields,
this is excessive. Strategies:
- **Specialized result containers** for common query shapes (e.g., array-backed for
  known column count instead of HashMap)
- **Hoist invariant checks**: `SQLProjection.isExpand` (2.32% CPU) is called per row
  but the answer is constant per query execution -- compute once and cache

### 5.3 Improve page cache scalability

The `LockFreeReadCache` is the #1 MT bottleneck due to CAS contention on reference
counts and LRU pointer updates. Strategies:
- **Batch cache access**: group multiple RID reads into a single cache scan to
  amortize per-access overhead
- **Per-thread read buffers**: reduce contention on shared frequency sketch and
  read buffer ring buffers
- **Clock-based eviction** instead of LRU linked list to eliminate pointer updates

### 5.4 Reduce HashMap overhead

`HashMap.getNode` appears in top-5 CPU consumers across all profiles (2.5-5.7%).
`HashMap.resize` causes significant cache misses. Strategies:
- **Pre-size HashMaps** where the expected size is known (e.g., result rows, property maps)
- **Replace HashMap with flat arrays** for small fixed-size maps (e.g., result rows
  with known column count)
- **Use open-addressing maps** (fastutil, Eclipse Collections) for internal structures
  to reduce pointer chasing

### 5.5 Reduce record cache overhead

`WeakValueHashMap.forEach` and `RecordCacheWeakRefs$$Lambda.accept` appear prominently
in both CPU and cache-miss profiles, especially under MT. The weak reference-based
record cache causes pointer chasing and GC pressure. Strategies:
- **Replace WeakValueHashMap** with a more cache-friendly structure (e.g., open-addressing
  with soft references, or a bounded LRU with explicit eviction)
- **Reduce iteration** -- if `forEach` is used for eviction, consider probabilistic
  eviction or generation-based cleanup

### 5.6 Batch storage operations

Instead of per-record lock/cache-lookup/page-read, batch multiple RIDs:
- One lock acquisition covers all reads
- One cache scan resolves all pages
- Sequential page reads improve spatial locality and prefetching

This amortizes per-operation overhead and is especially effective for the
vertex traversal pattern (loading 1000 records in sequence).
