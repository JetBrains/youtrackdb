<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
overall: PASS
verdicts:
  - {id: T1, sev: should-fix, verdict: VERIFIED, loc: "track-5.md D21 Risks/Caveats (2), Plan of Work, Validation"}
  - {id: T2, sev: suggestion, verdict: VERIFIED, loc: "track-5.md Context and Orientation"}
  - {id: T3, sev: suggestion, verdict: VERIFIED, loc: "track-5.md D21 Risks/Caveats method-relative anchors"}
index: []
flags: [CONTRACT_OK]
-->

## Findings
<!-- Pure-verdict pass: no new findings surfaced. -->

## Verdicts

#### Verify T1: same-tx query against a tx-created (provisional-collection) class breaks in the fetch-step collection-scan setup, not the index planner
- **Original issue**: the track framed the "query against a tx-created class in same tx" gap as an index-planner problem (`getIndexId() < 0` guard only); the real break is in `FetchFromClassExecutionStep`'s polymorphic-collection-id scan setup, which adds a provisional (`<= -2`) id to the scan set because `getCollectionNameById` returns null, then iterates a nonexistent collection.
- **Fix applied**: three coordinated edits.
  - D21 Risks/Caveats (2) (line 60): "D13's skip-unbuilt treatment must extend to every snapshot reader that resolves a collection for a provisional-collection class, not the planner alone: the fetch-step collection-scan setup (`FetchFromClassExecutionStep`, which adds a `<= -2` id to the scan set because `getCollectionNameById` returns null), security id→name resolution, and serialization, alongside the WHERE-block planner."
  - Plan of Work (lines 132-137): names the same three readers (fetch-step scan setup, security id→name, serialization) as the sites that must fall through to the merged tx scan.
  - New Validation line (lines 212-214): "Run a query against a tx-created class inside the same transaction; it returns the transaction's own rows through the scan fallthrough without an engine-not-found or collection-not-found error (D13 extended to provisional-collection classes)."
  - In-scope Signatures (line 247): now names "the D13 planner extension for provisional-collection classes".
- **Re-check**:
  - Location: track-5.md D21 Risks/Caveats (2), Plan of Work, Validation and Acceptance, Interfaces/In-scope.
  - Current state: the fix names `FetchFromClassExecutionStep` and the exact break mechanism (`<= -2` id added because `getCollectionNameById` returns null), distinguishes it from the index-planner guard, and adds an acceptance line asserting no collection-not-found error via the scan fallthrough — exactly the proposed fix.
  - Criteria met: the collection-scan path is now named as a distinct guard site; the merged-tx fallthrough for the tx-created class's own rows is required; the acceptance line names the fetch-step path.
- **Regression check**: checked that the added scan-reader enumeration is consistent with C13's traced path (fetch-step ctor `:106`/`:110`/`:111`/`:112`) and does not contradict D13's preserved read-merge for already-built indexes (line 53, unchanged) or C14 (built classes still route correctly). Clean.
- **Verdict**: VERIFIED

#### Verify T2: the cached index set is owned by SchemaImmutableClass; ClassIndexManager is a static reader
- **Original issue**: Context and D15-caveat attributed the cached `indexes` set to `ClassIndexManager`, but that class is a fieldless static utility; the set lives on `SchemaImmutableClass.indexes` (C9: field `:91`, materialized in `init` `:135`, read via `getRawIndexes()` `:690`).
- **Fix applied**: Context and Orientation (lines 75-79) now reads: "the cached `indexes` set `ClassIndexManager` consults is materialized once on `SchemaImmutableClass` at snapshot init (`ClassIndexManager` is a static utility that reads it via `getRawIndexes()`)."
- **Re-check**:
  - Location: track-5.md Context and Orientation.
  - Current state: ownership correctly reattributed to `SchemaImmutableClass`; `ClassIndexManager` described as a static utility reading via `getRawIndexes()` — matches C9's confirmed shape.
  - Criteria met: the force-rebuild rationale (rebuilding the snapshot rebuilds `SchemaImmutableClass.indexes`) stays intact while pointing at the correct field.
  - Residual (suggestion-level, non-blocking): the D15 Risks/Caveats sentence (line 39) still reads "`ClassIndexManager` reads a cached index set materialized once at snapshot init" — the same imprecise attribution the iter-1 finding named at both locations. The orchestrator's resolution note covered only the Context sentence. This is a suggestion, does not gate, and the Context correction is enough to point the implementer right; noting it for optional decomposition-time cleanup.
- **Regression check**: checked that the reworded sentence keeps the D15 lazy-force-rebuild dependency (line 39 / Plan of Work) coherent. Clean.
- **Verdict**: VERIFIED

#### Verify T3: citation drift — load-bearing anchors converted to method-relative; nitpicks left as accepted suggestions
- **Original issue**: three minor citation drifts — `SchemaProxy.makeSnapshot` at `:75` vs cited `:78`; `IndexException` trailing period; the `dropIndex` comment characterized as "reads as if the Track 4 commit already drops the index" when it already attributes the drop to a later track.
- **Fix applied**: the load-bearing brittle absolute line anchors in D21 Risks/Caveats were replaced with method-relative anchors — the commit-path guard (Risk 1) now names `MetadataDefault.getImmutableSchemaSnapshot`, `computeCommitWorkingSet`, `reconcileCollections`, `forceSnapshot`, `getImmutableSchemaClass`, `getCollectionForNewInstance`, `doGetAndCheckCollection` by method rather than by absolute line. The remaining nitpicks (the `:78` prose citation, `IndexException` wording, drop-comment phrasing) were left as accepted suggestions.
- **Re-check**:
  - Location: track-5.md D21 Risks/Caveats (line 60).
  - Current state: Risk 1 carries no brittle absolute line anchor for the commit chain; methods are named. The one new absolute anchor the edit introduced — `EntityImpl.java:4194-4210` for the version-keyed `immutableClazz` cache (Risk 3) — was PSI-verified: `getImmutableSchemaClass(session, immutableSchema)` spans 4187-4213, and lines 4194-4210 cover exactly the cache-and-re-resolve logic (`immutableClazz` set `:4205`, version stored `:4204`, re-resolve on version advance `:4207-4209`). The anchor is accurate.
  - Criteria met: load-bearing anchors are now method-relative; the sole newly-introduced absolute anchor is correct.
  - Residual (suggestion-level, non-blocking, correctly accepted): the D21 Rationale prose still cites `SchemaProxy.java:78` (line 59) rather than the declaration `:75`; this is informational prose, not a load-bearing edit target, and was explicitly left as an accepted suggestion.
- **Regression check**: PSI-confirmed the one new absolute anchor (`EntityImpl.java:4194-4210`) is inside the correct method and covers the cited logic — no spurious anchor introduced. Clean.
- **Verdict**: VERIFIED

## Summary
**PASS.** All three iteration-1 technical findings are resolved: T1 (should-fix) VERIFIED — the fetch-step collection-scan guard is now named as a distinct site alongside security id→name and serialization, with a matching acceptance line; T2 and T3 (suggestions) VERIFIED. Two suggestion-level residuals noted (the D15-caveat sentence still carries the old `ClassIndexManager`-owns-the-cache phrasing; the D21 Rationale prose still cites `SchemaProxy.java:78`) — neither gates, both were within the orchestrator's accepted-suggestion latitude, flagged only for optional decomposition-time cleanup. The one new absolute line anchor the edits introduced (`EntityImpl.java:4194-4210`) was PSI-verified accurate. No regressions. 0 new findings.
