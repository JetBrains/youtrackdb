# Query Engine Optimizations for LDBC Benchmark Queries

This document describes engine-level optimizations that would improve performance
of the LDBC SNB Interactive read queries executed by the YouTrackDB SQL engine.
Each optimization is identified from EXPLAIN/PROFILE analysis of the 20 benchmark
queries (IS1–IS7, IC1–IC13) against the SF 0.1 dataset.

Items are sorted by **highest priority first, then smallest effort first**.
Subsequent additions should follow this ordering.

## Table of Contents

Sorted by priority (P0 first), then effort (Low first), then number of affected queries (most first).

1. [Conditional Aggregation in SQL Parser](#1-conditional-aggregation-in-sql-parser) — P0, Medium, 2 queries
2. [Correlated Subquery Materialization](#3-correlated-subquery-materialization) — P0, High, 3 queries
3. [Predicate Push-Down into `expand()` Traversals](#2-predicate-push-down-into-expand-traversals) — P1, Medium, 3 queries
4. [Index-Assisted Filtering in Mid-Traversal MATCH Steps](#8-index-assisted-filtering-in-mid-traversal-match-steps) — P1, High, 4 queries
5. [Index-Assisted Date Range Filtering in Correlated Subqueries](#7-index-assisted-date-range-filtering-in-correlated-subqueries) — P1, High, 2 queries
6. [Cost-Based MATCH Edge Reordering](#6-cost-based-match-edge-reordering) — P2, Medium, 3 queries
7. [Hash Join for NOT and Multi-Branch MATCH Patterns](#9-hash-join-for-not-and-multi-branch-match-patterns) — P2, High, 3 queries
8. [Top-N Heap in ORDER BY + LIMIT](#4-top-n-heap-in-order-by--limit) — P3, Low, 3 queries
9. [Automatic Base-Class Query Pruning](#5-automatic-base-class-query-pruning) — P3, Low, 1 query

---

## 1. Conditional Aggregation in SQL Parser

**Blocked queries**: IC3, IC10
**Priority**: P0
**Effort**: Medium

### Problem

The YouTrackDB SQL parser (`YouTrackDBSql.jjt`) does not accept function
expressions like `if()` or `CASE WHEN` as arguments to aggregate functions
(`sum()`, `count()`). This prevents writing conditional aggregation patterns
such as:

```sql
SELECT
  sum(if(out('IS_LOCATED_IN').name CONTAINS :countryX, 1, 0)) as xCount,
  sum(if(out('IS_LOCATED_IN').name CONTAINS :countryY, 1, 0)) as yCount
FROM (
  SELECT expand(in('HAS_CREATOR')) FROM Person
  WHERE @rid = $parent.$current.personVertex
) WHERE creationDate >= :startDate AND creationDate < :endDate
```

### Current Workaround

IC3 and IC10 must use **two separate correlated subqueries** that each scan the
identical record set. For IC3, this means every friend's messages are scanned
twice — once for countryX and once for countryY. For IC10, every FoF's posts are
scanned twice — once for matching tags and once for non-matching.

### Proposed Change

Extend the parser grammar to allow any scalar expression (including function
calls like `if()`, `eval()`, and `CASE WHEN ... THEN ... END`) as an argument
to aggregate functions. The relevant production rules in `YouTrackDBSql.jjt`
would need to accept `Expression()` inside `AggregateFunction()` where currently
only `Identifier()` or `*` is allowed.

The execution layer (`AggregateProjectionCalculationStep`) already evaluates
arbitrary expressions per row before aggregation — the limitation is purely at
the parser level.

### Expected Impact

- **IC3**: 2× fewer message scans per friend. IC3 is the slowest query in the
  benchmark (~1.6 ops/s multi-threaded on CCX33). Each friend currently triggers
  two full scans of their messages with location traversals.
- **IC10**: 2× fewer post scans per friend-of-friend. Each FoF currently
  triggers two full scans of their posts with tag set comparisons.

---

## 2. Predicate Push-Down into `expand()` Traversals

**Affected queries**: IC5, IC3, IC10
**Priority**: P1
**Effort**: Medium

### Problem

When a correlated subquery does `SELECT expand(out('CONTAINER_OF')) FROM Forum`
followed by `WHERE out('HAS_CREATOR').@rid = ...`, the engine:

1. Expands **all** outgoing CONTAINER_OF edges (all posts in the forum).
2. Materializes each post record.
3. Evaluates the WHERE predicate on each materialized record.

For a forum with 10,000 posts where only 3 belong to the target person, the
engine loads 10,000 records and discards 9,997.

### Proposed Change

Implement **predicate push-down** for `expand()` results:

When the pattern is:
```sql
SELECT ... FROM (SELECT expand(out/in('EdgeClass')) FROM Vertex WHERE @rid = ...)
WHERE <predicate on expanded records>
```

The optimizer should analyze the WHERE predicate and, when possible, push it into
the edge traversal iterator. Specifically:

**Case A — RID equality**: `WHERE out('HAS_CREATOR').@rid = $parent.$current.X`
can be converted to a link-index lookup: traverse only CONTAINER_OF edges whose
target has a HAS_CREATOR edge pointing to the specified RID.

**Case B — Property filter on expanded record**: `WHERE creationDate >= :start`
can be evaluated during iteration, skipping record deserialization for
non-matching edges when the property is available in the edge or target vertex
index.

**Case C — Class filter**: `WHERE @class = 'Post'` can be pushed into the
expand to only follow edges pointing to Post records, skipping Comment records
entirely.

### Implementation Location

The `expand()` function is implemented in
`com.jetbrains.youtrackdb.internal.core.sql.functions.DefaultSQLFunctionFactory`.
The push-down would need to be implemented in the execution planner that
constructs the step chain for subqueries containing `expand()`.

### Expected Impact

- **IC5**: Instead of scanning all posts in a forum (avg ~10 per forum at SF0.1,
  but up to thousands at higher scale factors), only materialize posts by the
  target person. Orders-of-magnitude improvement at scale.
- **IC3/IC10**: The class filter push-down (`@class = 'Post'`) would skip
  Comment records during expansion.

---

## 3. Correlated Subquery Materialization

**Affected queries**: IC3, IC5, IC10
**Priority**: P0
**Effort**: High

### Problem

LET subqueries that reference `$parent.$current` are re-planned and re-executed
independently for every outer row. When multiple LET clauses operate on the same
base record set, the engine performs redundant scans.

**IC10 example** — two LET clauses scanning the same person's posts:

```sql
LET $posScore = (
  SELECT count(*) as cnt FROM (
    SELECT expand(in('HAS_CREATOR')) FROM Person
    WHERE @rid = $parent.$current.fofVertex    -- ← same base scan
  ) WHERE @class = 'Post' AND <tag condition>
),
$negScore = (
  SELECT count(*) as cnt FROM (
    SELECT expand(in('HAS_CREATOR')) FROM Person
    WHERE @rid = $parent.$current.fofVertex    -- ← same base scan (again)
  ) WHERE @class = 'Post' AND NOT <tag condition>
)
```

Both `$posScore` and `$negScore` expand `in('HAS_CREATOR')` for the same person
vertex. The engine executes two independent edge traversals and two independent
filter passes over the same records.

### Proposed Change

Introduce a **shared materialization pass** in the LET execution logic:

1. During planning, detect when multiple LET clauses share the same
   `FROM (SELECT expand(...) FROM ... WHERE @rid = $parent.$current.X)` pattern.
2. Execute the shared `expand()` once and materialize the results into a
   temporary in-memory collection.
3. Evaluate each LET clause's WHERE/projection against the materialized set.

The detection can be structural: compare the AST of the `FROM` subquery across
LET clauses. If they are identical (same expand direction, same class, same
`$parent.$current` reference), they are candidates for sharing.

### Implementation Considerations

- The materialized set should be bounded (e.g., configurable max records) to
  avoid memory pressure for prolific posters/commenters.
- The optimization applies only when multiple LETs share the same base; a single
  LET clause gains nothing.
- Thread safety: in multi-threaded JMH benchmarks, the `@State(Scope.Benchmark)`
  means multiple threads share the same state but execute queries independently.
  Materialization is per-query-execution, so no sharing across threads is needed.

### Expected Impact

- **IC3**: Eliminates one of two full message scans per friend (each including
  `out('IS_LOCATED_IN')` traversal per message).
- **IC10**: Eliminates one of two full post scans per FoF (each including
  `set(out('HAS_TAG').name)` computation per post).
- **IC5**: If combined with optimization #2 (predicate push-down), could share
  the `expand()` result across multiple filter passes.

---

## 4. Top-N Heap in ORDER BY + LIMIT

**Affected queries**: IC2, IC8, IC9
**Priority**: P3
**Effort**: Low

### Problem

Queries with `ORDER BY ... LIMIT N` first materialize all results, sort them,
then take the top N. The PROFILE output shows `(buffer size: 20)` which suggests
partial support for bounded sorting, but it's unclear if this uses a fixed-size
priority queue (heap) or a full sort with early termination.

**IC8 example**: A person with 500 messages, each with multiple replies,
produces thousands of (creator, comment) rows. All are materialized, sorted by
`commentCreationDate DESC`, then only the top 20 are returned.

### Proposed Change

Verify and optimize the `OrderByStep` implementation:

1. When a downstream `LimitStep` is present, pass the limit value to
   `OrderByStep` at planning time.
2. In `OrderByStep.internalStart()`, use a **bounded min-heap** (priority queue)
   of size N instead of collecting all rows into a list.
3. For each incoming row, compare against the heap's minimum. If the new row
   ranks higher, replace the minimum and re-heapify. Otherwise, discard
   immediately.

This reduces memory from O(|all results|) to O(N) and time from
O(|results| × log(|results|)) to O(|results| × log(N)).

### Implementation Location

`OrderByStep` is in
`com.jetbrains.youtrackdb.internal.core.sql.executor.OrderByStep`.
The `(buffer size: 20)` in the PROFILE output suggests the limit is already
propagated; verify that the actual sort implementation leverages it.

### Expected Impact

- **IC2, IC8, IC9**: Moderate improvement. These queries produce hundreds to
  thousands of intermediate rows but only need 20. The heap avoids materializing
  and sorting the full set.
- At higher scale factors (SF1, SF10), the impact grows significantly as
  intermediate result sets grow by 10–100×.

---

## 5. Automatic Base-Class Query Pruning

**Fixed in query**: IS4 (manual fix: `FROM V` → `FROM Message`)
**Priority**: P3
**Effort**: Low

### Problem

When a SELECT targets the base vertex class `V` with a `WHERE id = :value`
predicate, the engine constructs a `PARALLEL` execution plan that scans the
`id` index on **every vertex subclass** (Place, Organisation, TagClass, Tag,
Person, Forum, Message). The PROFILE showed 7 parallel index lookups for IS4,
most returning zero results.

This happens because `V` is the root of the class hierarchy, and the engine
doesn't know which subclass the record belongs to without checking all of them.

### Proposed Change

In `SelectExecutionPlanner`, when the target class is a base class (V or E) and
the WHERE clause contains an equality predicate on a UNIQUE indexed property:

1. Check if only one subclass hierarchy has a matching UNIQUE index for that
   property.
2. If so, rewrite the plan to query only that subclass (and its sub-hierarchy).
3. If multiple subclasses have matching indexes, keep the parallel scan but
   annotate it for short-circuit: stop after the first match (UNIQUE guarantees
   at most one).

**Short-circuit optimization**: Even without subclass pruning, the parallel scan
should short-circuit after finding a match. Currently, all branches run to
completion even though UNIQUE guarantees at most one result across all classes.

### Implementation Location

`SelectExecutionPlanner` in
`com.jetbrains.youtrackdb.internal.core.sql.executor/SelectExecutionPlanner.java`.
The parallel plan construction happens in the method that handles `FROM V/E`
with WHERE clauses.

### Expected Impact

- **IS4-like queries**: 7× fewer index lookups for `FROM V WHERE id = ...`.
- **General**: Any query using `FROM V` or `FROM E` with a UNIQUE-indexed
  predicate benefits automatically.
- The manual fix (`FROM V` → `FROM Message`) is the recommended workaround
  until this optimization is implemented.

---

## 6. Cost-Based MATCH Edge Reordering

**Affected queries**: IC4, IC6, IC12
**Priority**: P2
**Effort**: Medium

### Problem

The MATCH execution planner (`MatchExecutionPlanner`) performs topological
scheduling with cost-driven root selection, but intermediate edge traversal
order within a connected component is determined by the topological sort, not by
estimated selectivity.

**IC6 example** — the MATCH has two branches from `post`:

```sql
MATCH ...
  .in('HAS_CREATOR'){class: Post, as: post}
  .out('HAS_TAG'){as: tag, where: (name <> :tagName)},
  {as: post}.out('HAS_TAG'){where: (name = :tagName)}
```

The current planner traverses these in declaration order. The second branch
(`name = :tagName`) is highly selective (single tag match), while the first
(`name <> :tagName`) is broad. If the selective branch were evaluated first, the
engine could prune non-matching posts earlier.

### Proposed Change

In `getTopologicalSortedSchedule()`, after building the traversal order, perform
a **selectivity-based reordering pass** for edges emanating from the same node:

1. For each node with multiple outgoing edges in the schedule, estimate the
   selectivity of each edge's WHERE clause.
2. Re-order so that the most selective edge is traversed first.
3. For edges with equality predicates on indexed properties, assign very low
   cardinality estimates.

Selectivity estimation heuristics:
- `WHERE (name = :value)` with an index → selectivity ≈ 1/distinct_values
- `WHERE (name <> :value)` → selectivity ≈ (distinct_values - 1)/distinct_values
- `WHERE (@class = 'Post')` → selectivity ≈ Post.count / Message.count
- `WHERE ($depth < N)` → depends on average fan-out

### Expected Impact

- **IC6**: Evaluate the tag-name equality first, reducing the post set before
  the broader tag traversal.
- **IC4**: The NOT pattern could benefit if the old-post filter is evaluated
  on a pre-filtered set.
- **IC12**: The `IS_SUBCLASS_OF` while-traversal with `name = :tagClassName`
  filter could be reordered to prune the tag class hierarchy earlier.

---

## 7. Index-Assisted Date Range Filtering in Correlated Subqueries

**Affected queries**: IC3, IC4
**Priority**: P1
**Effort**: High

### Problem

IC3 and IC4 filter messages by `creationDate >= :startDate AND creationDate <
:endDate` inside correlated subqueries. The `Message.creationDate` index exists
in the schema, but the subquery starts from `expand(in('HAS_CREATOR'))` which
yields an unordered stream of edge targets. The creationDate index cannot be
used because the scan entry point is edge-based, not index-based.

**IC3 subquery flow**:
```
Person (@rid = X) → in('HAS_CREATOR') → [all messages by X] → filter by date → filter by country
```

The date filter is applied post-expansion, after all messages are loaded.

### Proposed Change

Implement **index intersection** for correlated subqueries:

When the pattern is:
```sql
SELECT expand(in('HAS_CREATOR')) FROM Person WHERE @rid = :rid
```
followed by `WHERE creationDate >= :start AND creationDate < :end`,

the engine should:

1. Retrieve the set of RIDs from `in('HAS_CREATOR')` for the given person.
2. Retrieve the set of RIDs from the `Message.creationDate` index for the
   date range `[start, end)`.
3. Intersect both sets.
4. Fetch only the intersected records.

This is a **two-index intersection** strategy. It requires:
- RID-level access to both the edge collection and the property index.
- An efficient intersection algorithm (e.g., merge join on sorted RID lists,
  or bitmap intersection).

### Alternative: Composite Index

A simpler but less general approach: create a composite index on
`(HAS_CREATOR.out, creationDate)` — effectively indexing "messages by creator
and date". This would allow a single index range scan instead of intersection.

However, this requires schema changes and is specific to this query pattern.

### Expected Impact

- **IC3**: Currently the slowest query. Each friend's messages (~200 avg at
  SF0.1) are fully loaded and then date-filtered. With index intersection,
  only messages in the date range would be loaded.
- **IC4**: Same pattern — friends' posts filtered by date range.
- At SF1 (10× data), each person has ~2,000 messages. Date filtering typically
  selects 5–10% of messages, so index intersection would skip 90–95% of record
  loads.

---

## 8. Index-Assisted Filtering in Mid-Traversal MATCH Steps

**Affected queries**: IC2, IC4, IC5, IC11
**Priority**: P1
**Effort**: High

### Problem

The MATCH executor traverses edges using adjacency lists (O(1) per edge via
YouTrackDB's link-based storage), then applies WHERE clause filters as a
post-traversal predicate. Property indexes that exist on traversed vertices
or edges are **not consulted** during MATCH edge traversal.

This is confirmed by the MATCH executor internals:
`MatchEdgeTraverser.traversePatternEdge()` delegates to `item.getMethod().execute()`
which follows adjacency pointers, then `computeNext()` evaluates the WHERE
predicate on each candidate record. The index infrastructure is bypassed entirely.

### Per-Query Impact Assessment

| Query | Traversal Step | WHERE Filter | Index Available | Estimated Impact |
|-------|---------------|--------------|-----------------|-----------------|
| **IC2** | `{friend}.in('HAS_CREATOR'){msg}` | `creationDate < :maxDate` | `Message.creationDate` NOTUNIQUE | **High** — avg ~200 messages per person, date filter selects ~50% |
| **IC4** | `{friend}.in('HAS_CREATOR'){newPost, class: Post}` | `creationDate >= :start AND < :end` | `Message.creationDate` NOTUNIQUE | **High** — date window typically selects 5–10% of posts |
| **IC5** | `{person}.inE('HAS_MEMBER'){membership}` | `joinDate >= :minDate` | `HAS_MEMBER.joinDate` NOTUNIQUE | **Medium** — most persons are members of many forums |
| **IC11** | `{person}.outE('WORK_AT'){workEdge}` | `workFrom < :workFromYear` | `WORK_AT.workFrom` NOTUNIQUE | **Low** — few work edges per person (~2 avg) |
| **IS3** | `{p}.outE('KNOWS'){k}` | (none, but ORDER BY k.creationDate) | `KNOWS.creationDate` NOTUNIQUE | **Low** — few KNOWS edges, sort is cheap |

### Proposed Change

For MATCH steps where the target vertex/edge has a WHERE clause that matches an
available index, the engine should attempt an **index-guided traversal**:

**Strategy A — Index intersection** (for vertex properties):

When the pattern is `{source}.in('EDGE_TYPE'){target, where: (prop OP :value)}`:

1. From the adjacency list, collect candidate target RIDs.
2. From the property index (`target.prop`), collect matching RIDs for the range.
3. Intersect both RID sets.
4. Fetch only intersected records.

This avoids loading and deserializing records that don't match the predicate.

**Strategy B — Sorted edge iteration** (for edge properties):

When the pattern is `{source}.outE('EDGE_TYPE'){edge, where: (prop OP :value)}`:

If the edge class has an index on the filtered property, iterate edges in index
order and apply early termination for range predicates.

**Strategy C — Selective prefetch with index probe**:

For small index result sets (estimated < 100 records), prefetch the index range
result and use it as a hash set for O(1) membership checks during adjacency
traversal.

### Implementation Location

The change would be in `MatchEdgeTraverser.executeTraversal()` (simple mode,
line 332+ of `MatchEdgeTraverser.java`). After collecting candidates via
`traversePatternEdge()`, before applying the WHERE filter, probe the relevant
index to pre-filter candidates.

The `PatternEdge` already carries the parsed WHERE expression. The planner
needs to analyze this expression for index-eligible predicates and annotate the
`EdgeTraversal` with the index to use.

### Estimated Speedup

At SF 0.1:
- **IC2**: ~2× (skip ~50% of messages via date index)
- **IC4**: ~10–20× (skip ~90% of messages outside date window)
- **IC5**: ~2–3× (skip older memberships)

At SF 1 (10× data):
- **IC2**: ~2× (same selectivity ratio)
- **IC4**: ~10–20× (same, but absolute savings are 10× larger)
- **IC5**: ~5× (more memberships per person to filter)

At SF 10+ the impact grows further as cardinalities increase while selectivity
ratios remain constant.

---

## 9. Hash Join for NOT and Multi-Branch MATCH Patterns

**Affected queries**: IC4, IC7, IC12
**Priority**: P2
**Effort**: High

### Problem

The current MATCH executor uses **nested-loop** evaluation for all pattern
branches, including NOT patterns. The NOT pattern in IC4:

```sql
NOT {as: friend}
  .in('HAS_CREATOR'){class: Post, as: oldPost,
    where: (creationDate < :startDate)}
  .out('HAS_TAG'){as: tag}
```

is evaluated by `FilterNotMatchPatternStep`, which **re-executes the entire NOT
sub-pattern for every upstream row**. This is O(|upstream| × |NOT-pattern-cost|).

For IC4, the upstream produces (friend, newPost, tag) triples. For each triple,
the NOT pattern checks if the same friend has any old post with the same tag —
traversing the friend's old posts and their tags repeatedly.

### Proposed Change

Implement a **hash-based anti-join** step:

1. **Materialization phase**: Before processing upstream rows, execute the NOT
   pattern independently to collect a set of (friend, tag) pairs that exist in
   old posts.
2. **Probe phase**: For each upstream (friend, newPost, tag) triple, probe the
   hash set. If found, discard the row; if not, keep it.

This converts the O(|upstream| × |NOT-cost|) nested-loop into
O(|upstream| + |NOT-result-set|) with a hash table lookup.

### Generalization

The same hash-join approach applies to any MATCH with multiple branches that
share aliases:

- **Multi-branch MATCH**: When two branches from the same node need to be joined,
  a hash join on the shared alias is more efficient than nested-loop.
- **Semi-join for EXISTS patterns**: Similar to anti-join for NOT, but keeping
  rows that match.

### Implementation Considerations

- Hash table size must be bounded. For large NOT-result-sets, a hybrid approach
  (hash join up to a threshold, fall back to nested-loop) prevents OOM.
- The planner must detect when a NOT pattern's result set is small enough to
  materialize. The existing cardinality estimation infrastructure
  (`estimateRootEntries()`) can be extended for this.

### Expected Impact

- **IC4**: The NOT pattern scans a friend's old posts for each (friend, tag)
  pair. With hash anti-join, old posts are scanned once per friend (not per tag).
- **IC12**: The deep tag-class hierarchy traversal could benefit from
  materializing the matching tag classes first.

---

## Summary

Sorted by priority (P0 first), then effort (Low first), then number of affected queries (most first).

| # | Optimization | Priority | Effort | Queries | Affected Queries |
|---|-------------|----------|--------|---------|-----------------|
| 1 | Conditional aggregation in parser | P0 | Medium | 2 | IC3, IC10 |
| 3 | Correlated subquery materialization | P0 | High | 3 | IC3, IC5, IC10 |
| 2 | Predicate push-down into expand() | P1 | Medium | 3 | IC3, IC5, IC10 |
| 8 | Index-assisted mid-traversal filtering | P1 | High | 4 | IC2, IC4, IC5, IC11 |
| 7 | Index intersection for date ranges | P1 | High | 2 | IC3, IC4 |
| 6 | Cost-based MATCH edge reordering | P2 | Medium | 3 | IC4, IC6, IC12 |
| 9 | Hash join for NOT/multi-branch | P2 | High | 3 | IC4, IC7, IC12 |
| 4 | Top-N heap in ORDER BY + LIMIT | P3 | Low | 3 | IC2, IC8, IC9 |
| 5 | Base-class query pruning | P3 | Low | 1 | IS4-like queries |
