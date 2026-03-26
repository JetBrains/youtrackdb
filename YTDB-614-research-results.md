# YTDB-614: Open Hash Map Property Serializer — Research Results

## Objective

Replace the V1 linear-scan serialization format with a V2 format using an
open-addressing hash map layout for O(1) property lookup during
deserialization. The motivation was to accelerate `deserializePartial()` and
`deserializeField()` — hot-path operations used by index lookups, query
evaluation, and binary field comparison — which require scanning the entire
header in V1 (O(n) per field).

## Approaches Investigated

### Approach 1: Perfect hashing with brute-force seed search (Tracks 4-6)

**Design:** For each entity, find a hash seed that produces a collision-free
mapping from property names to slot indices. Seed search iterates up to
10,000 candidates per capacity level, doubling capacity on failure.

**Result:** JMH benchmarks showed a **5x write path slowdown**. The
brute-force seed search dominated serialization time. Rejected.

### Approach 2: Bucketized cuckoo hashing (Track 7-8)

**Design:** Cuckoo hashing with bucket size b=4, two hash functions derived
from a single seed (XOR with `0x85ebca6b`), ~85% load factor. Greedy
placement with displacement chains (up to 500 evictions) and seed retry
(up to 10 attempts).

**Result:** CCX33 benchmarks (Track 8) showed **2-2.5x write path
regression** vs V1 (e.g., 6,649 ns vs 2,715 ns at 50 properties).
Displacement chains and dual hash computation during construction were
the bottleneck. Partial deserialization showed only 23% improvement at
50 properties. Rejected.

### Approach 3: Plain linear probing with Fibonacci hashing (Tracks 9-10)

**Design:** Single MurmurHash3 hash function, sequential slot probing,
power-of-two capacity with Fibonacci index computation, ~62.5% load factor
(average 1.83 probes for successful lookup). 3-byte slots (1-byte hash8
prefix + 2-byte offset). 3-tier routing: linear (0-2 properties), linear
(3-12), hash table (13+). Track 10 applied GC optimizations: tempBuffer
reuse, slotHash8 elimination, pre-encoded UTF-8 reuse.

**Result:** See final benchmark below. Write regression reduced to ~2x but
still significant. This was the simplest possible hash table implementation
— further optimization is blocked by structural overhead.

### Alternative approaches researched but not implemented (Track 9)

- **Robin Hood hashing** — hash8 fast-reject already provides the same
  benefit (avoiding false key comparisons). Worst-case improvement is only
  2-3 probes at N<=100.
- **Swiss Table metadata separation** — counterproductive at our table sizes
  (39-300 bytes, 1-5 cache lines). Separating hash8 from offsets means two
  accesses instead of one contiguous read.
- **SWAR (SIMD-in-a-register)** — 3-byte interleaved slot layout doesn't
  pack neatly into a `long`. Rearranging hurts locality at these sizes.
- **Quadratic probing** — loses linear probing's sequential cache-line
  utilization. At load factor 0.625, primary clustering is mild.
- **Minimal perfect hashing (ShockHash, PtrHash, qlibs/mph)** — latest
  2025 approaches still require brute-force construction.

## Final Benchmark Results (CCX33, 8 dedicated AMD vCPUs, 32 GB RAM)

JMH 1.37, OpenJDK 21.0.10, `-f 2 -wi 3 -w 2s -i 5 -r 2s`, `-prof gc`.

### Serialization (write path, ns/op — lower is better)

| Properties | V1       | V2       | V2/V1 | Assessment          |
|------------|----------|----------|-------|---------------------|
| 5 (linear) | 157      | 181      | 1.16x | +16% regression     |
| 13 (hash)  | 376      | 654      | 1.74x | +74% regression     |
| 20 (hash)  | 538      | 957      | 1.78x | +78% regression     |
| 50 (hash)  | 1,300    | 2,650    | 2.04x | +104% regression    |

### Full deserialization (read path, ns/op)

| Properties | V1       | V2       | V2/V1 | Assessment          |
|------------|----------|----------|-------|---------------------|
| 5          | 1,423    | 1,310    | 0.92x | noise               |
| 13         | 2,185    | 2,163    | 0.99x | noise               |
| 20         | 2,557    | 2,612    | 1.02x | noise               |
| 50         | 5,512    | 5,527    | 1.00x | noise               |

### Partial deserialization (single field, worst-case position, ns/op)

| Properties | V1       | V2       | V2/V1 | Assessment          |
|------------|----------|----------|-------|---------------------|
| 5          | 866      | 971      | 1.12x | noise               |
| 13         | 951      | 1,017    | 1.07x | noise               |
| 20         | 1,007    | 965      | 0.96x | noise               |
| 50         | 1,276    | 901      | 0.71x | 29% improvement     |

### GC allocations (serialize path, B/op)

| Properties | V1       | V2       | V2/V1 | Notes                           |
|------------|----------|----------|-------|---------------------------------|
| 5 (linear) | 592      | 536      | 0.91x | V2 wins (tempBuffer reuse)      |
| 13 (hash)  | 1,232    | 2,048    | 1.66x | hash table construction arrays  |
| 20 (hash)  | 1,800    | 2,312    | 1.28x | hash table construction arrays  |
| 50 (hash)  | 3,960    | 6,944    | 1.75x | hash table construction arrays  |

## Analysis

### Why V2 doesn't pay off at these entity sizes

1. **Write path regression is structural.** Hash table construction requires:
   computing MurmurHash3 per property name, encoding names to byte arrays,
   allocating slot arrays, performing linear probing, and backpatching
   offsets. This overhead is inherent to any hash-based approach and cannot
   be optimized away — Track 10 already eliminated the low-hanging fruit
   (per-property BytesContainer allocation, intermediate arrays, double
   UTF-8 encoding).

2. **Full deserialization shows zero improvement.** Both V1 and V2 must
   visit every property when loading an entity for the first time. The hash
   table structure doesn't help because there's no selective access — all
   properties are decoded.

3. **Partial deserialization only wins at 50+ properties.** At 13 and 20
   properties, V2 is within noise of V1. The theoretical O(1) vs O(n)
   advantage is real but the constant factors (hash computation, slot
   lookup, offset indirection) are large enough to offset the linear scan
   cost at small N. The benchmark tests the worst case for V1 (last
   property); in practice, average V1 scan is O(n/2).

4. **Entity sizes in practice.** YouTrackDB entities (issue tracker domain)
   typically have 5-30 properties. The 50+ property sweet spot where V2's
   partial deserialization advantage materializes is uncommon.

5. **Cache effects favor linear scan.** At 20 properties with ~30 bytes
   average per entry, the KV region is ~600 bytes (~10 cache lines). Modern
   CPUs prefetch sequential memory effectively, making linear scan
   competitive with hash-indexed random access at these sizes.

### Where V2 would make sense

- Entities with 100+ properties (rare in this codebase)
- Workloads dominated by single-field partial deserialization on large
  entities (e.g., a column-oriented access pattern)
- If write frequency is very low relative to partial read frequency
  (high read fan-out amortizes the 2x write cost)

## Conclusion

The V2 open-addressing hash map serializer does not justify the trade-off
for YouTrackDB's typical workload profile. The write path regression
(1.7-2x at 13-50 properties) is too large relative to the modest read
improvement (29% partial deserialize at 50 properties only). Full
deserialization — the most common read path — shows no improvement.

Three fundamentally different hash table strategies were evaluated (perfect
hashing, bucketized cuckoo, linear probing) across 10 implementation tracks.
All share the same core limitation: at entity sizes of 5-50 properties, hash
table construction overhead dominates the write path, while the read path
gains are marginal because the data fits in a small number of cache lines
regardless of access pattern.

**Recommendation:** Do not ship V2. The V1 linear-scan format is
well-suited to the entity sizes encountered in practice.
