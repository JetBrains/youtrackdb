<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: PF1, sev: should-fix, loc: SQLContainsTextCondition.java:45, anchor: "### PF1 ", cert: C1, basis: "per-record ResultInternal alloc + schema/property collate resolution on the CONTAINSTEXT/ENDSWITH full-scan path; Identifiable overload is strictly heavier than the sibling SQLBinaryCondition"}
  - {id: PF2, sev: suggestion, loc: SQLMatchesCondition.java:85, anchor: "### PF2 ", cert: C2, basis: "find-mode runs user-supplied regex per record with unbounded backtracking; one pathological pattern can stall a full scan"}
  - {id: PF3, sev: suggestion, loc: SQLContainsTextCondition.java:142, anchor: "### PF3 ", cert: C3, basis: "constant right operand re-lowercased per record on ci properties; one avoidable String alloc per row"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 3}
cert_index:
  - {id: C1, verdict: MATCHES, anchor: "#### C1 "}
  - {id: C2, verdict: MATCHES, anchor: "#### C2 "}
  - {id: C3, verdict: MATCHES, anchor: "#### C3 "}
  - {id: C4, verdict: WRONG, anchor: "#### C4 "}
  - {id: C5, verdict: WRONG, anchor: "#### C5 "}
  - {id: C6, verdict: WRONG, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [should-fix] Per-record ResultInternal allocation and collate resolution on the CONTAINSTEXT / ENDSWITH full-scan path

The `evaluate(Identifiable)` overload allocates a `new ResultInternal(session, currentRecord)` every row solely to reach the `getCollate(Result)` overload, then resolves the property collation by walking to the record's schema class and doing a property-map lookup. Both nodes report `isIndexAware() == false`, so on a class without a usable index this runs once per scanned row.

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLContainsTextCondition.java` (line 45), mirrored in `SQLEndsWithCondition.java` (line 64). The `any()` / `all()` paths (`SQLContainsTextCondition.java` lines 78, 95) resolve collation per property per row via `collateForProperty` (line 125).
- **Issue**: `resolveCollate` calls `left.getCollate(record, ctx)`, which reaches `SQLSuffixIdentifier.getCollate` (`SQLSuffixIdentifier.java:505`): `getImmutableSchemaClass(session)` plus `schemaClass.getProperty(name)` per row. The resolved collate is invariant for a fixed (schema class, property name) pair, yet it is recomputed every row. On the Identifiable overload each row also allocates a `ResultInternal` wrapper. `collateForProperty` re-resolves the schema class once per property inside the `any()` / `all()` loop, though the record's class is the same for all its properties.
- **Evidence**: COST TRACE C1. The sibling `SQLBinaryCondition.evaluate(Identifiable)` applies no collation on this overload by design (`SQLBinaryCondition.java:46-47`), so the two new nodes are strictly heavier per row on the MATCH edge-filter path — one short-lived object plus one schema-class-and-property resolution each. SCALE CHECK C1: negligible at 100 rows; at a 1M-row full scan, N `ResultInternal` allocations (young-gen GC pressure) plus N schema/property lookups on the CONTAINSTEXT/ENDSWITH hot path the track's Decision Log R2 flags.
- **Impact**: added GC and CPU per row on unindexed CONTAINSTEXT/ENDSWITH scans; grows linearly with class size.
- **Suggestion**: add a collate resolution that reads the collation from the raw entity so the Identifiable overload drops the per-row `ResultInternal` wrapper. Optionally memoize the resolved collate keyed on the record's schema class within the query, guarding the polymorphic / schema-less case where the class (and so the collate) varies row to row. The Result-path resolution matches the accepted `SQLBinaryCondition` cost and needs no change; the wrapper allocation on the Identifiable path is the novel cost.

### PF2 [suggestion] find-mode regex runs user-supplied patterns per record with unbounded backtracking

`matchesRegex` feeds an arbitrary caller pattern (Gremlin `Text.regex`) into `Matcher.find()`, evaluated once per row. `SQLMatchesCondition` is not index-aware for this predicate, so a full scan runs the matcher on every value.

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchesCondition.java` (line 85), reached through `MatchWhereBuilder.matchesRegex`.
- **Issue**: a pattern with nested quantifiers (for example `(a+)+`) against a long value backtracks super-linearly and stalls the scan thread on a single row. `find()` attempts a match at every start offset, so on a non-matching input it can do more work than the pre-existing `matches()`, which anchors at offset 0.
- **Evidence**: COST TRACE C2. Pattern compilation is already cached (`SQLMatchesCondition.java:73-77` stores the compiled `Pattern` in a ctx variable), so only match cost is per-row. SCALE CHECK C2: ordinary patterns run linearly and matter at no scale; a single adversarial pattern makes per-row latency unbounded independent of row count, so the verdict is MATTERS AT SCALE for the exposure rather than for typical workloads.
- **Impact**: worst-case throughput collapse — one row can wedge a query for the pattern's backtracking duration.
- **Suggestion**: consider a value-length guard, an interruptible or timeboxed match, or an explicit note in the predicate translation that user regexes run unbounded. The DoS framing belongs to the security reviewer; this is the throughput/latency angle.

### PF3 [suggestion] Constant right operand re-transformed per record on ci properties

On a `ci`-collated property, the right operand (the substring / suffix literal) is lowercased every row even though it is constant across the scan.

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLContainsTextCondition.java` (`containsCollated`, lines 142-147); the same shape in `SQLEndsWithCondition.java` (`endsWithCollated`, line 96).
- **Issue**: `collate.transform(substring)` runs per row and, for `CaseInsensitiveCollate`, allocates a new `substring.toLowerCase(Locale.ENGLISH)` String (`CaseInsensitiveCollate.java:46`). The left value transform is unavoidable (it differs per row); the right operand transform repeats identical work.
- **Evidence**: COST TRACE C3. Default-collated properties are unaffected — `DefaultCollate.transform` returns the argument unchanged with no allocation (`DefaultCollate.java:40`) — see refuted claim C6. SCALE CHECK C3: only `ci` properties; roughly one avoidable String allocation per row, halving the transform allocations on the ci path. At 1M rows that is 1M avoidable `toLowerCase` allocations. Verdict: MATTERS AT SCALE on ci scans.
- **Impact**: extra young-gen allocation and `toLowerCase` CPU on ci-property CONTAINSTEXT/ENDSWITH scans.
- **Suggestion**: pre-transform the right operand once when `right.isEarlyCalculated(ctx)` and reuse it across rows. This mirrors a pre-existing pattern in `SQLBinaryCondition` (lines 107-108 also transform both operands per row), so the improvement is a codebase-wide one rather than a defect introduced here — hence suggestion.

## Evidence base

Phase-4 scale validation. Confirmed findings compress to one line; refuted or non-passing candidate claims render in full (YTDB-1069 roster rendering).

#### C1 MATCHES
`SQLContainsTextCondition.java:45` / `SQLEndsWithCondition.java:64` allocate a `ResultInternal` per row and resolve `getCollate` (`SQLSuffixIdentifier.java:505`: `getImmutableSchemaClass` + `getProperty`) per row on `isIndexAware()==false` scans; `SQLBinaryCondition.evaluate(Identifiable)` (lines 46-47) applies no collation, confirming the Identifiable overload is strictly heavier — SCALE: MATTERS AT SCALE (N allocs + N schema lookups at 1M rows).

#### C2 MATCHES
`SQLMatchesCondition.java:85` runs `Matcher.find()` per row on a caller-supplied pattern reachable through `MatchWhereBuilder.matchesRegex`; Pattern is compile-cached (lines 73-77) but match cost is per-row and super-linear for pathological patterns — SCALE: MATTERS AT SCALE for the exposure (one adversarial pattern → unbounded per-row latency).

#### C3 MATCHES
`containsCollated` (`SQLContainsTextCondition.java:142-147`) / `endsWithCollated` (`SQLEndsWithCondition.java:96`) call `collate.transform(substring)` per row; `CaseInsensitiveCollate.transform` (line 46) allocates a lowercased String for a constant operand — SCALE: MATTERS AT SCALE on ci scans (1M avoidable allocs at 1M rows).

#### C4 WRONG
Candidate claim: "the per-record collate resolution is a novel performance regression." Refuted for the Result path. `SQLBinaryCondition.evaluate(Result)` (`SQLBinaryCondition.java:102-108`) already resolves `left.getCollate` / `right.getCollate` and transforms both operands per row for every `=` / `<` / `>` comparison. The CONTAINSTEXT / ENDSWITH `evaluate(Result)` path faithfully mirrors that accepted cost, so the added Result-path work is not novel. The genuinely new per-row cost is confined to the Identifiable overload's `ResultInternal` wrapper allocation, which the sibling avoids (lines 46-47). PF1 was written against that residual, not against the full "novel regression" framing.

#### C5 WRONG
Candidate claim: "the `\"MATCHES_\" + regex.hashCode()` cache-key String concatenation per row is introduced by this step." Refuted as pre-existing. `SQLMatchesCondition.java:73` is unchanged context in the diff; the concatenation predates the change. Step 1 added only the `findMode` parameter to `matches(...)` and the `find()` / `matches()` branch. Not attributable to this step. Noted for a later pass: building `"MATCHES_" + regex.hashCode()` per row on the scan path is a real standing inefficiency (a cached key field, or an identity/int-keyed lookup, would remove the per-row String allocation), but it is out of scope for this review.

#### C6 WRONG
Candidate claim: "the collate transform adds allocation on default-collated properties, contradicting the track's no-op-on-default claim." Refuted. A declared property with default collation returns a `DefaultCollate` instance, not null (`SchemaPropertyImpl.java:73` initializes `collate = new DefaultCollate()`), so `containsCollated`'s `collate != null` branch does run on default properties — but `DefaultCollate.transform` returns the argument object unchanged (`DefaultCollate.java:40`), allocating nothing. The default path therefore costs two virtual `transform` calls and no allocation; the "no-op on default" claim holds for allocation and behavior. The residual per-row schema resolution on default properties is the same cost captured by PF1, not a separate transform-allocation defect.
