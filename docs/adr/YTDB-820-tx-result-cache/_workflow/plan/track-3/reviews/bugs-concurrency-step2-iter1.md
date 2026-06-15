<!-- MANIFEST
findings: 3   severity: {blocker: 1, should-fix: 1, suggestion: 1}
index:
  - {id: BC1, sev: blocker, loc: DatabaseSessionEmbedded.java:898, anchor: "### BC1 ", cert: C1, basis: "single-alias MATCH that classifies K0_NONE (statement SKIP/LIMIT, while:/optional:) is mapped to raw records and gets a returnProjector, but the K0_NONE view replay path never applies the projector, so the view emits raw entity rows instead of the RETURN tuple — I10 cardinality/shape divergence vs fresh execution"}
  - {id: BC2, sev: should-fix, loc: ShapeClassifier.java:496, anchor: "### BC2 ", cert: C2, basis: "orderByIsAliasLocal admits a recordAttr ORDER BY (@rid/@class/@version/...) as record-local, but the projected RETURN tuple (e.g. {u, u.name}) carries no @rid key, so projectForCompare-based sort reads null for both heads and mis-orders vs a fresh MATCH that sorts on the raw record's attribute; unverified by tests"}
  - {id: BC3, sev: suggestion, loc: DatabaseSessionEmbedded.java:208, anchor: "### BC3 ", cert: C3, basis: "rawAliasRecordMapper / buildMatchReturnProjector guard a null bound record only with a Java assert; with -ea off a null entity yields a non-identifiable empty ResultInternal rather than a fail-fast, silently producing a wrong row"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
flags: [PSI_USED]
-->

## Findings

### BC1 [blocker] A single-alias MATCH that classifies K0_NONE is mapped to raw records but never re-projected, so the view emits raw entities instead of the RETURN tuple

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (line 894-928)

**Issue**: The Etap-A populate logic installs the raw-record mapper and the
`returnProjector` whenever the statement is a single-alias MATCH, gated only on
`singleAliasOrigin(match) != null`. That predicate does **not** check the
K0_NONE classify gates. A single-alias MATCH that routes to `K0_NONE` —
`MATCH {as:u, class:X} RETURN u, u.name LIMIT 5` is the cleanest reproducer, and
`SKIP`, `optional:`, `while:`/`maxDepth:` reproduce it too — still reaches
`populateAndBuildView` (the gate at line 822 lets `K0_NONE` through). For it:

1. `matchOrigin = singleAliasOrigin(match)` is non-null (one expression, origin
   present, no traversal items), so `lifted = lifted.map(rawAliasRecordMapper(alias))`
   replaces every projected RETURN tuple in the stream with a raw
   `new ResultInternal(this, entity)` record (line 906-911).
2. `entry.setReturnProjector(buildMatchReturnProjector(...))` is installed
   (line 925-927).
3. `buildView` sees `shape == K0_NONE`, so `delta == null`
   (`DatabaseSessionEmbedded.java:1133`), and `CachedResultSetView.computeNext`
   routes to `computeNextK0None` (`CachedResultSetView.java:198-199`).
4. `computeNextK0None` (`CachedResultSetView.java:222-233`) returns cached and
   freshly-pulled rows **directly**, with no `project()` call — only
   `computeNextRecord` (the RECORD branch) applies the projector.

The net effect: the view emits raw entity rows (`ResultInternal(session, record)`)
instead of the projected `{u, u.name}` RETURN tuples. The consumer of a cached
single-alias MATCH with a statement-level `LIMIT`/`SKIP` (or an `optional:` /
`while:` node) sees a different row shape and column set than a parallel uncached
`query()` would return. That is a direct I10 transparency violation, and the
shape difference (`getPropertyNames()` returns the record's own properties, not
`u` / `u.name`) is observable on the first cache hit.

**Evidence**: See `#### C1`. PSI find-usages confirms `singleAliasOrigin`
(`DatabaseSessionEmbedded`) and `isSingleAliasRecordFold` (`ShapeClassifier`) are
distinct predicates with different acceptance sets; the classify side runs the
K0_NONE gates before the single-alias split, the populate side does not. The
new `MatchEtapAEquivalenceTest` only exercises the RECORD-classified `matchSql()`
(`WHERE` + `ORDER BY`, no SKIP/LIMIT), so this path has no coverage.

**Refutation considered**: Could a single-alias MATCH never classify K0_NONE? No
— `classifyMatch` (`ShapeClassifier.java:218-248`) returns `K0_NONE` on
statement-level SKIP/LIMIT and on `while:`/`maxDepth:`/`optional:` nodes *before*
`isSingleAliasRecordFold` is consulted (lines 250+), and a single-alias pattern
with a statement-level LIMIT satisfies `singleAliasOrigin` (size 1, origin
present, `items.isEmpty()`). Could the K0_NONE view apply the projector anyway?
No — `computeNextK0None` has no `project()` call and the `delta == null` branch is
unconditional for K0_NONE. Could `executeUncached` be returned instead of a view?
No — `populateAndBuildView` builds a `CachedResultSetView` for every
`LocalResultSet`, K0_NONE included.

**Suggestion**: Gate the mapper-install / projector-install on the actual shape,
not on `singleAliasOrigin != null`. Two equivalent fixes: (a) only lift the
stream and set the projector when `shape == CacheableShape.RECORD` (pass `shape`,
already a parameter, into the guard); or (b) reuse the classifier's own
`isSingleAliasRecordFold` predicate at the populate site so the populate decision
and the classify decision cannot diverge. Add a regression row to
`MatchEtapAEquivalenceTest` for a single-alias MATCH with `LIMIT` (classifies
K0_NONE) asserting the cached view's row shape equals the uncached run.

### BC2 [should-fix] A record-attribute ORDER BY (`@rid`, `@class`, ...) is admitted as record-local but is unresolvable on the projected tuple, mis-sorting vs fresh execution

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (line 496-520)

**Issue**: `orderByIsAliasLocal` accepts any ORDER BY item whose
`getRecordAttr() != null` as record-local (the final fall-through: not a RID
sort, not an alias path, `recordAttr` present → loop continues → returns true).
A `RECORD_ATTRIBUTE` token is `@rid`, `@class`, `@version`, `@size`, `@type`,
`@raw`, `@rid_id`, `@rid_pos`, or `@fields` (grammar `YouTrackDBSql.jjt:388`).
So `MATCH {as:u, class:X} RETURN u, u.name ORDER BY @rid` folds onto RECORD.

At sort/compare time the view runs `orderBy.compare(projectForCompare(a),
projectForCompare(b), ctx)` — both heads are first projected to the RETURN tuple
`{u: entity, "u.name": value}` (`CachedResultSetView.java:317`,
`DeltaBuilder.java:373`). `SQLOrderByItem.compare` then reads
`a.getProperty("@rid")` (`SQLOrderByItem.java:98-100`). The projected tuple is a
plain `ResultInternal` with a `content` map keyed by `u` and `u.name` and no
backing `identifiable`, so `getProperty("@rid")` finds nothing in `content`,
`isEntity()` is false, and it returns `null` (`ResultInternal.java:462-475`).
Both heads resolve `null`, the comparator treats every row as equal, and the
cache emits in cache/inject order. A fresh MATCH sorts the same query on the raw
record's `@rid` — the SELECT planner explicitly orders before the final
projection so "sort keys ... not present in the final projection are still
accessible" (`SelectExecutionPlanner.java:344-347`). The two orders diverge.

**Evidence**: See `#### C2`. The aliased case `ORDER BY u.name`
(`alias = "u"`, `modifier = .name`) is sound — `compare` reads `getProperty("u")`
then applies `.name`, and the projected tuple carries `u`. The bare-identifier
case `ORDER BY name` parses to `alias = "name"` (grammar line 3074), which fails
`alias.equals(boundAlias)` and correctly stays MATCH_TUPLE_MULTI. Only the
`recordAttr` branch is the leak: it admits `@`-attributes the projected tuple
cannot resolve. No test covers a `recordAttr` ORDER BY on the fold.

**Refutation considered**: Does the fresh MATCH also resolve `@rid` to null on
its pre-projection row, making the divergence vacuous? For a single-alias MATCH
the pre-projection row is the alias-keyed `{u: entity}` row, and the fresh
planner's `handleProjectionsBeforeOrderBy` makes the ORDER BY expression
available as a column before sorting — so the fresh path resolves `@rid` against
the bound entity, not against an empty projected tuple. The cache path resolves
against the post-projection tuple that has lost the record identity. They are not
symmetric, so the divergence is real, not vacuous. I did not build a running
repro, so I cannot rule out that some `@`-attribute (e.g. one the projection
copies) coincidentally survives; the conservative reading is that the gate is too
permissive and untested.

**Suggestion**: Restrict the `recordAttr` branch — either reject `recordAttr`
ORDER BY entirely from the fold (route to MATCH_TUPLE_MULTI), or admit it only if
the projection demonstrably carries the attribute (it does not for a bare alias
RETURN). Back the decision with an equivalence test: `RETURN u, u.name ORDER BY
@rid` cached vs uncached over records whose insertion order differs from `@rid`
order.

### BC3 [suggestion] Null bound record in the mapper / projector is guarded only by a Java assert, so with `-ea` off it silently yields a wrong row

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (line 186-213)

**Issue**: `rawAliasRecordMapper` resolves `entity = projectedRow.getEntity(alias)`
and guards `entity != null` with a Java `assert` (line 210-211), then calls
`new ResultInternal(this, entity)`. `buildMatchReturnProjector` symmetrically
reads `entity = rawRow.asEntityOrNull()` (line 192) with no guard before
`aliasRow.setProperty(alias, entity)`. With assertions disabled (production), a
null `entity` does not fail fast:
`new ResultInternal(session, null)` → `setIdentifiable(null)` leaves
`identifiable` null (`ResultInternal.java:874-885`), producing a
non-identifiable, empty row. That row has no RID, so it is invisible to the
skip-set and emits as an empty tuple — a silent wrong result rather than an
error.

**Evidence**: See `#### C3`. For a well-formed single-alias MATCH the alias
always binds a class-constrained vertex, so `getEntity(alias)` /
`asEntityOrNull()` returning null implies the record was deleted or unreadable
mid-pull — an edge the design treats as not-reached. The test class documents the
assert as "protects tests, not production" and runs with `-ea`, so the guard is
intentional. The note is that the production failure mode (empty row) is silent.

**Refutation considered**: Is the null case actually reachable? Only if a bound
record vanishes between stream production and mapping, which the RID skip-set is
designed to suppress for deleted records — so this is a defensive-depth note, not
a demonstrated live bug. That is why it is a suggestion, not a should-fix.

**Suggestion**: If the invariant is real, make the production failure explicit
(throw an `IllegalStateException` with the alias/RID context) instead of relying
on `assert`, so a future shape-broadening that lets a null binding through fails
loudly rather than emitting a phantom empty row. No change needed if the team is
satisfied the binding can never be null on this path.

## Evidence base

#### C1 single-alias-K0_NONE populate installs mapper+projector that the K0_NONE view never applies — CONFIRMED

Survived refutation. Trace: `serveThroughCache` line 822 lets `K0_NONE` through
to `populateAndBuildView`; that method's Etap-A block (lines 894-928) gates
mapper-lift and `setReturnProjector` on `singleAliasOrigin(match) != null` with
no shape check; `singleAliasOrigin` (lines 148-159) checks only
`expressions.size()==1 && origin != null && items.isEmpty()`, none of the K0_NONE
gates; `classifyMatch` (`ShapeClassifier.java:218-248`) returns K0_NONE on
SKIP/LIMIT and `while:`/`optional:` before the single-alias split; `buildView`
sets `delta=null` for non-RECORD (line 1133) and `computeNextK0None`
(`CachedResultSetView.java:222-233`) emits rows with no `project()` call. PSI
find-usages: `computeNextK0None` is the sole `delta==null` non-aggregate emit
path and contains no projector reference; `project()` is referenced only inside
`computeNextRecord` and `projectForCompare`. Reproducer:
`MATCH {as:u, class:X} RETURN u, u.name LIMIT 5`.

#### C2 recordAttr ORDER BY admitted to the fold but unresolvable on the projected tuple — CONFIRMED

Survived refutation. `orderByIsAliasLocal` (`ShapeClassifier.java:496-520`)
returns true for `item.getRecordAttr() != null`. `RECORD_ATTRIBUTE` =
`@rid|@class|@version|@size|@type|@raw|@rid_id|@rid_pos|@fields`
(`YouTrackDBSql.jjt:388`), parsed into `recordAttr`
(`YouTrackDBSql.jjt:3080`). View compare projects both heads then reads
`a.getProperty("@rid")` (`SQLOrderByItem.java:98-100`); the projected
`ResultInternal` has `content={u, "u.name"}`, no identifiable, so `getProperty`
returns null (`ResultInternal.java:462-475`). Fresh path sorts pre-projection
(`SelectExecutionPlanner.java:344-347`). Aliased `u.name` and bare `name` cases
checked and found sound/correctly-rejected respectively (grammar lines 3074-3080,
`SQLOrderByItem.compare` alias+modifier path).

#### C3 null-binding guard is assert-only — CONFIRMED

Survived refutation as a defensive-depth suggestion. `rawAliasRecordMapper`
(`DatabaseSessionEmbedded.java:207-213`) and `buildMatchReturnProjector`
(lines 186-195) both rely on `assert`/no-guard for a null bound record;
`new ResultInternal(session, null)` → `setIdentifiable(null)`
(`ResultInternal.java:874-885`) yields a non-identifiable empty row rather than
throwing. Reachability requires a vanished binding the RID skip-set normally
suppresses, hence suggestion severity.
