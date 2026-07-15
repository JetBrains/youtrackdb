<!--MANIFEST
dimension: bugs-concurrency
iteration: 1
verdict: CHANGES-REQUESTED
findings_total: 3
findings_by_sev: {blocker: 0, should-fix: 2, suggestion: 1}
evidence_base: {certs: 3}
cert_index: [C1, C2, C3]
flags: [reference-accuracy-caveat]
index:
  - id: BC1
    sev: should-fix
    anchor: "### BC1"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlanner.java:2296
    cert: C1
    basis: grep + Read (PSI unavailable — IDE on develop; symbols uniquely named, no polymorphic dispatch)
  - id: BC2
    sev: should-fix
    anchor: "### BC2"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlanner.java:2259
    cert: C2
    basis: grep + Read (PSI unavailable — IDE on develop; symbols uniquely named, no polymorphic dispatch)
  - id: BC3
    sev: suggestion
    anchor: "### BC3"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/LoaderExecutionStream.java:66
    cert: C3
    basis: grep + Read (PSI unavailable — IDE on develop; symbols uniquely named, no polymorphic dispatch)
-->

## Findings

### BC1 [should-fix] A malformed RID string in `@rid = '<str>'` / `@rid IN ['<str>']` throws instead of returning empty — a result divergence from the scan it replaces

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlanner.java` (line 2296, the `case String` arm of `toRecordIdCandidate`)
- **Issue**: `toRecordIdCandidate` maps a `String` value via `RecordIdInternal.fromString(s, false)`, which throws `IllegalArgumentException` for a string with no `#`/`:` separator (`RecordIdInternal.java:135-141`) and `NumberFormatException` for a non-numeric collection id / position (`:153`, `:155`). This exception is not caught anywhere on the plan-time path, so it aborts the whole query. The scan-plus-filter this fast path replaces returns an **empty result** for the same query: the old path evaluates `@rid = 'garbage'` per record via `QueryOperatorEquals.equals`, whose "ALL OTHER CASES" conversion is wrapped in `try { ... } catch (Exception ignore) { return false; }` (`QueryOperatorEquals.java:100-112`), so a malformed comparison value simply matches nothing. The focus note calls out exactly this class of defect: "any case where the fast path changes results vs the old scan+filter."
- **Failure scenario**: `SELECT FROM C WHERE @rid = 'garbage'` (or `@rid IN ['garbage']`, or a well-formed-looking `'#ab:cd'`). The value side is a string literal, so `extractAndRemoveRidEquality` extracts it, `isEarlyCalculated` is true (`SQLBaseExpression.java:397`, `string != null → true`), the fast path fires, and `toRecordIdCandidate("garbage")` throws `IllegalArgumentException`. Before this change the same query returned zero rows without error. The `case String` arm is genuinely reachable in production — a quoted-string RID equality/IN is a valid query shape — and the track's own Surprises section records that no test exercises it, so the throw is untested.
- **Refutation considered**: (1) Could an earlier stage reject the query? No — `handleFetchFromTarget` (`:1399-1414`) dispatches any non-`$` identifier straight to `handleClassAsTarget` with no value-type validation, and `extractAndRemoveRidEquality` matches on the *left* side being `@rid` only (`tryExtractRidValue`, `SQLWhereClause.java:1194-1211`), never the value type. (2) Could `fromString` not throw? No — the throws are on the direct code path for any separator-less or non-numeric string. (3) Is this pre-existing behavior of `SQLRid.toRecordId` (which the switch mirrors)? The switch is faithfully copied, but `SQLRid.toRecordId` was never on the plain-SELECT `@rid = <string-literal>` path before this change — that path went through the scan + `QueryOperatorEquals`, which swallows the conversion failure. This change newly routes the string-literal value through `fromString`.
- **Suggestion**: Wrap the `RecordIdInternal.fromString(s, false)` call so a parse failure yields "not a candidate" (drop it, same as the `default` arm) rather than propagating: `case String s -> { try { yield RecordIdInternal.fromString(s, false); } catch (RuntimeException e) { yield null; } }`. Dropping a malformed string then feeds the empty-members branch → `EmptyStep`, restoring result parity with the old scan (empty, not thrown). Add a regression test for `@rid = '<malformed>'` (this also covers the untested `case String` arm the track flagged).
- **Certainty**: high. See C1.

### BC2 [should-fix] A nonexistent class name with an `@rid` predicate now returns empty instead of throwing "Class not present"

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlanner.java` (line 2259-2273, the membership filter + empty-members branch)
- **Issue**: When the target class does not exist, `resolveClassToCollectionIds` returns `null` (`:3834-3835`, `schema.getClass(className) == null`). The handler treats `null` as "no members" (`:2261`, the `if (classCollectionIds != null)` guard leaves `members` empty), chains `EmptyStep`, and returns `true` — short-circuiting the whole class-target chain. The old path throws `CommandExecutionException("Class or View not present in the schema: " + className)` (`handleClassAsTarget`, `:2151`) because that existence check lives *after* the fast path in the same method and is now bypassed by the early `return true`. `handleFetchFromTarget` performs no class-existence validation before dispatch (`:1399-1414`), so the fast path is the first and only place the class is looked up for this query shape.
- **Failure scenario**: `SELECT FROM Prson WHERE @rid = #12:0` (a typo'd class name) previously failed loudly with "Class or View not present in the schema: Prson"; it now silently returns an empty result set. The divergence is scoped to queries that also carry an early-calculable `@rid = / IN` predicate (only then does the fast path fire); the same typo in `SELECT FROM Prson` with no WHERE, or with a non-`@rid` WHERE, still throws. The result is an inconsistency: the presence of an `@rid` predicate flips a hard error into a silent empty result, which can mask a mistyped class name.
- **Refutation considered**: (1) Could class existence be validated earlier? No — checked `handleFetchFromTarget` (`:1390-1448`); the only existence check on this path is inside `handleClassAsTarget` at `:2146-2153`, which the fast path's early `return` skips. (2) Could `resolveClassToCollectionIds` throw rather than return null for a missing class? No — it returns null explicitly (`:3835`). (3) Is empty arguably the "more correct" answer? Debatable, but it is a behavior change from the documented scan path, and the track's acceptance criteria assert result-parity with the old scan for optimized shapes; "class not present" is a distinct pre-existing contract that this change silently drops for one query shape.
- **Suggestion**: Distinguish "class resolved but candidate is a non-member" (→ `EmptyStep`, correct) from "class does not exist" (→ fall through with `return false`, so the existing scan path reaches its `:2151` throw and preserves the error contract). Concretely: when `resolveClassToCollectionIds` returns `null`, `return false` rather than chaining `EmptyStep`. Add a test asserting `SELECT FROM <nonexistent> WHERE @rid = <literal>` still throws (matching the no-WHERE behavior).
- **Certainty**: high. See C2.

### BC3 [suggestion] `IN`-list fetch inherits `LoaderExecutionStream`'s early-termination on a not-found member RID (pre-existing, but newly reachable)

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/LoaderExecutionStream.java` (line 66-68)
- **Issue**: `LoaderExecutionStream.fetchNext` catches `RecordNotFoundException` and `return`s (`:66-68`) — it does **not** `continue` the loop to the next RID. So when a RID in the fetch list is a member by collection id but points to a nonexistent/deleted position, the stream terminates and every RID *after* it in the list is silently dropped. This is pre-existing `FetchFromRidsStep` behavior (also reachable via `handleRidsAsTarget`), so it is out of this track's direct scope, but the new `@rid IN [...]` fast path newly routes class-target IN-lists through it. The membership filter (`:2261-2266`) checks only the *collection id*, not record existence, so a member-collection-but-deleted RID passes the filter and enters the fetch list.
- **Failure scenario**: `SELECT FROM C WHERE @rid IN [#12:5, #12:0]` where `#12:5` was deleted (position no longer exists) but `#12:0` exists and is a member. The old scan-plus-filter returns the `#12:0` row (the scan never yields the deleted position, and `#12:0` matches). The new fast path builds the fetch list `[#12:5, #12:0]`; `LoaderExecutionStream` hits `RecordNotFoundException` on `#12:5`, returns, and never reaches `#12:0` — so the query returns zero rows instead of one. Ordering-dependent: had the list been `[#12:0, #12:5]`, `#12:0` would be returned first. A single-RID `@rid = <deleted>` is unaffected (nothing follows it), which is why this only bites the IN form.
- **Refutation considered**: (1) Is `LoaderExecutionStream` in scope? It is not one of the three in-scope files, so I flag it as suggestion-tier context rather than a blocker on this diff — but the track newly makes the bug reachable for a supported query shape, so it belongs in the record. (2) Could the membership filter already exclude deleted RIDs? No — it tests collection-id membership only (`:2263`), never existence; a deleted RID keeps its collection id. (3) Could a dedup / ordering guarantee mask it? No — `LinkedHashSet` preserves insertion (list) order, so a not-found RID appearing before a valid one deterministically truncates the result.
- **Suggestion**: The clean fix is in `LoaderExecutionStream.fetchNext` — replace the `return` in the `catch (RecordNotFoundException e)` block with a `continue`-style skip (loop to the next RID) so a missing member does not truncate the stream. That is out of this track's file scope; at minimum, note the interaction in the track's Surprises/Invariants and consider a follow-up issue, since the IN-list fast path now exposes it. If a narrower in-scope mitigation is preferred, the handler cannot cheaply pre-check existence at plan time without a load, so the stream-level fix is the right home.
- **Certainty**: medium (the divergence is real and confirmed by reading `LoaderExecutionStream`; the severity is bounded by how often a member-collection RID names a deleted position, and the fix lives outside the reviewed files).

## Evidence base

Refutation-check roster (Phase 4). CONFIRMED-as-issue claims compress to one line; refuted/partial claims appear in full.

#### C1: `@rid = '<malformed-string>'` throws in the fast path vs empty in the old scan — CONFIRMED
CONFIRMED — extraction matches any value side (`SQLWhereClause.java:1194-1211`); string literal is early-calc (`SQLBaseExpression.java:397`); `toRecordIdCandidate` → `RecordIdInternal.fromString` throws (`:135-141`); old path swallows via `QueryOperatorEquals.equals` `catch(Exception){return false;}` (`:100-112`). grep + Read; symbols uniquely named, no polymorphic dispatch — reference accuracy not load-bearing for this claim.

#### C2: nonexistent class + `@rid` predicate returns empty vs old "Class not present" throw — CONFIRMED
CONFIRMED — `resolveClassToCollectionIds` returns null for a missing class (`:3834-3835`); handler maps null → empty members → `EmptyStep` + `return true` (`:2261-2273`); old throw at `:2151` is bypassed; `handleFetchFromTarget` does no pre-dispatch existence check (`:1399-1414`). grep + Read; no symbol search underpins this — the dispatch and the throw are both read in full.

#### C3: `IN`-list not-found member RID truncates the result via `LoaderExecutionStream` — CONFIRMED (pre-existing, newly reachable)
CONFIRMED — `LoaderExecutionStream.fetchNext` `return`s (not `continue`) on `RecordNotFoundException` (`:66-68`), so a not-found RID drops all subsequent list entries; membership filter checks collection id only (`:2263`), not existence, so a deleted member-collection RID enters the fetch list; `LinkedHashSet` preserves list order so truncation is deterministic and order-dependent. Out of the three in-scope files (lives in `LoaderExecutionStream`), hence suggestion-tier. grep + Read; reference accuracy not load-bearing.

**Reference-accuracy caveat (all findings).** PSI was unavailable (the IDE is open on `develop`, not this worktree branch), so every premise rests on grep + Read against the worktree files. The change is purely additive with uniquely-named symbols and no polymorphic/reflective dispatch, so grep reference-accuracy is reliable; no finding above hinges on a "no other caller" or override-completeness claim.
