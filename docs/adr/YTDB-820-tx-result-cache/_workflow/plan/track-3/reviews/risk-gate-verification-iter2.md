<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
  - {id: R7, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 3 risk gate-verification (iteration 2)

All seven Phase A risk findings were ACCEPTED and the amendments verified against
the re-read track file and the current source. Source citations spot-checked through
the open IDE project (mcp-steroid reachable; `steroid_execute_code` not invoked, so
symbol re-checks are read-backed with a `(grep-only)` caveat on reference accuracy).

## Verdicts

#### Verify R1: edge-class extraction under-specified
- **Original issue**: the tombstone floor's edge-mutation story was unstated — no
  contract for extracting the edge class, and no version backstop to catch a missed
  edge op.
- **Fix applied**: Context "Traversal edges" bullet (lines 58-72) and Plan-of-Work
  step 3 (lines 168-185) now pin the contract: edge class is the first param of a
  `SQLMethodCall` on `SQLMatchPathItem`; recognize `out/in/both/outE/inE/bothE`; fold
  the parser `null`→`"E"` default; handle multi-param `out('E1','E2')`; route a
  non-static label to K0_NONE (deferred to step 1). Validation (lines 292-296) adds a
  per-method-name `effectiveFromClasses` unit test plus the unnamed-`out()`→`E` case.
- **Re-check**:
  - Track-file location: lines 58-72 (Context), 168-185 (step 3), 292-296 (Validation).
  - Source: `SQLMatchPathItem.java` confirms `protected SQLMethodCall method` (line 21)
    and `graphPath` injecting `edgeName.value = "E"` when `edgeName == null` (lines
    32-36), with `methodName.value = direction` and the edge name added as the first
    param (lines 37-43). The cited `SQLMatchPathItem.java:21-44` range is accurate. The
    all-edges-record-based facts (`addEdgeInternal:1563-1564`, `newEdgeInternal:1506`,
    `createLink` at 1571-1574) are confirmed in `DatabaseSessionEmbedded.java`.
  - Current state: the contract is now fully specified — direction source, edge-class
    source, the `null`→`"E"` fold, multi-param handling, and the K0_NONE escape for a
    non-static label. The no-version-backstop risk is addressed by routing the
    unresolvable cases to K0_NONE and tombstoning every edge mutation.
  - Criteria met: the floor's edge-mutation path is no longer under-specified; an
    extraction miss is caught at the metadata layer by the per-method unit test.
- **Regression check**: checked steps 1, 3, 4-pass-1, and Validation for consistency —
  the K0_NONE label gate (step 1), the closure builder (step 3), and the edge-class
  DELETE / edge CREATE tombstone (step 4) tell one consistent story. Clean.
- **Verdict**: VERIFIED

#### Verify R2: SELECT-only populate helpers stale for MATCH
- **Original issue**: `effectiveFromClasses` / `whereClauseOf` / `orderByOf` are
  `instanceof SQLSelectStatement`-gated, so a MATCH entry built through them would get
  an empty filter (`Set.of()`) and null WHERE/ORDER BY, reconcile no mutation, and
  replay a stale frozen result (silently, since `MATCH_TUPLE_MULTI` has no version
  backstop).
- **Fix applied**: step 2 (lines 144-155) and step 3 (lines 156-167) extend the three
  helpers for `SQLMatchStatement` (or route through a dedicated MATCH populate path);
  Interfaces (lines 328-332) lists the five DatabaseSessionEmbedded edits including the
  three helper extensions; Validation (lines 268-269) asserts the Etap-A entry's
  `effectiveFromClasses` is non-empty.
- **Re-check**:
  - Source: `DatabaseSessionEmbedded.java` lines 1134-1151 confirm all three helpers
    are `SQLSelectStatement`-gated and return `Set.of()` / `null` for a MATCH. The track
    cites `1134-1151` — accurate.
  - Current state: the staleness is named explicitly in step 2, the fix is specified
    two ways (extend the helpers or a dedicated MATCH populate path), and the non-empty
    assertion is a Validation acceptance.
  - Criteria met: the silently-stale-Etap-A path is closed; the non-empty
    `effectiveFromClasses` assertion fails loudly if a future edit regresses it.
- **Regression check**: checked the multi-alias `effectiveFromClasses` union (step 3,
  via `computeMatchEffectiveFromClasses`) does not conflict with the single-alias Etap-A
  helper extension — they are distinct paths (RECORD-shape entry vs MATCH_TUPLE_MULTI).
  Clean.
- **Verdict**: VERIFIED

#### Verify R3: tombstone build placed in lookup, deltas build in buildView
- **Original issue**: the original sketch put the tombstone build in `lookup`, but RECORD
  and aggregate deltas build in `buildView`; `lookup(CacheKey, long)` carries no
  `FrontendTransactionImpl` / `CommandContext`, so a build there would force a signature
  change and break the "lookup does no AST work" hit-path contract.
- **Fix applied**: step 5 (lines 207-228) builds `buildForMatchMulti` in `buildView`,
  adds a package-visible `removeForTombstone(key)` cache helper, and leaves `lookup`
  unchanged; Interfaces key signatures (line 356) state the build is invoked from
  `buildView`, not `lookup`.
- **Re-check**:
  - Source: `buildView` (line 1084) is where RECORD (`buildForRecord`, 1105-1106) and
    aggregate (`buildForAggregate`, 1101) deltas build — confirmed. `lookup` signature is
    `lookup(CacheKey, long)` (`QueryResultCache.java:107`), no tx/ctx — confirmed. The
    track cites `buildView` at `1105-1108` and `lookup`'s no-ctx contract; the line number
    has drifted (actual `buildView` header is 1084) but the substantive placement is
    correct and the cited 1105-1108 falls inside the method body.
  - Current state: the build site is now `buildView`, matching the RECORD/aggregate
    pattern; `removeForTombstone` is the new helper; `lookup` unchanged.
  - Criteria met: no `lookup` signature change, hit-path contract preserved.
- **Regression check**: line-number drift on the `buildView` citation (1105-1108 vs the
  1084 method header) is cosmetic prose drift, not a correctness defect — the body lines
  cited are inside the method and the named delta-build site is right. Not a blocker; no
  new finding. Clean.
- **Verdict**: VERIFIED

#### Verify R4: viewOwnsGuard leak on a tombstoning MATCH HIT
- **Original issue**: the HIT path sets `viewOwnsGuard = true` unconditionally
  (`serveThroughCache` line 790); a TOMBSTONE-driven uncached re-execution returns no
  `CachedResultSetView`, so an unconditional `true` would leave the guard held and the
  `finally` would skip release, leaking the cache-code depth bump for the rest of the
  transaction (every later `query()` bypasses the cache).
- **Fix applied**: step 5 (lines 215-221) adds the MATCH branch as a separate gate and
  sets `viewOwnsGuard = result instanceof CachedResultSetView` — the same instanceof test
  the RECORD/aggregate branches use; Validation/step note a guard-not-leaked regression
  test (the I7 / tombstone rows in Validation exercise the uncached re-execution).
- **Re-check**:
  - Source: `serveThroughCache` confirms `viewOwnsGuard = true` on the HIT path
    (line 790) and `viewOwnsGuard = aggregateResult instanceof CachedResultSetView`
    (809) / `viewOwnsGuard = result instanceof CachedResultSetView` (824) on the populate
    branches, with the `finally` releasing only when `!viewOwnsGuard` (832-834). The track
    cites lines 809/824 — accurate.
  - Current state: the fix routes the MATCH HIT through the instanceof test, so a
    TOMBSTONE → `executeUncached` outcome (not a `CachedResultSetView`) leaves
    `viewOwnsGuard == false` and the `finally` releases the guard exactly once.
  - Criteria met: the guard-leak path is closed; the instanceof pattern matches the two
    existing branches verbatim.
- **Regression check**: checked the interaction with the separate-gate placement — the
  MATCH branch must run its own build + guard-transfer rather than fall through the RECORD
  `instanceof` at 824; step 5 and Interfaces (line 328, edit 1) both specify a separate
  gate, consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R5: tombstone-on-CREATE predicate inconsistent
- **Original issue**: the tombstone-on-CREATE predicate was stated two ways — "any
  CREATE" in one place vs "a CREATE of a class in `effectiveFromClasses`" elsewhere. An
  any-CREATE predicate over-tombstones (a CREATE of an unrelated class cannot add a
  tuple).
- **Fix applied**: Purpose (lines 18-21) reconciled to the scoped predicate ("a CREATE
  of a class in `effectiveFromClasses` ... A CREATE of a class outside the pattern's read
  set does not tombstone"); step 4 pass-1 (lines 188-195) states the scoped predicate;
  Validation (lines 282-283) adds an "out-of-pattern-class CREATE does NOT tombstone"
  acceptance.
- **Re-check**:
  - Track-file location: Purpose 18-21, step 4 188-195, Validation 282-283.
  - Current state: every mention of the CREATE tombstone predicate is now the scoped
    `effectiveFromClasses`-class form; the negative acceptance row pins it.
  - Criteria met: the predicate is consistent across Purpose, Plan of Work, and
    Validation; no any-CREATE residue remains.
- **Regression check**: grepped the track for "CREATE" mentions — Purpose (scoped), step
  4 pass-1 (scoped), Validation (scoped + negative row), and the I7 row (edge CREATE, a
  distinct edge-class case) are mutually consistent. The mermaid diagram label
  "CREATE / edge DELETE / update-into-match" (line 87) reads as shorthand for the scoped
  CREATE; not contradictory. Clean.
- **Verdict**: VERIFIED

#### Verify R6: edge CREATE also UPDATEs endpoint vertices — correctness rides on the pre-scan short-circuit
- **Original issue**: an edge CREATE co-emits UPDATEs on both endpoint vertices via
  `createLink`; if pass 2 saw those endpoint UPDATEs before the pass-1 tombstone fired, an
  edge CREATE between two already-cached vertices could be mis-reconciled instead of
  tombstoned.
- **Fix applied**: step 4 pass-1 (lines 188-195) states the short-circuit fires before
  pass 2 processes the endpoint UPDATEs (Context lines 70-72 also state it); Validation
  (lines 280-281) adds the "edge CREATE whose endpoints are BOTH already in cached tuples
  → tombstoned" row.
- **Re-check**:
  - Source: `addEdgeInternal` calls `createLink` on both endpoints (lines 1571-1574),
    confirmed; `newEdgeInternal` always adds a CREATED edge-class op (line 1506+),
    confirmed. So an edge CREATE always surfaces an edge-class op that pass 1's scoped
    CREATE / edge-class-DELETE check can trip before pass 2 runs.
  - Current state: the ordering requirement is explicit in both Context and step 4; the
    end-to-end row that catches an edge-class-extraction miss is in Validation.
  - Criteria met: correctness no longer rides on an unstated ordering — the
    short-circuit-before-pass-2 sequencing is named, and a correct edge-class fold (R1) is
    what makes the pass-1 CREATE check see the edge op.
- **Regression check**: checked the dependency chain R1 (edge-class fold) → R6 (pass-1
  short-circuit) — if the edge class were not folded into `effectiveFromClasses`, the
  edge-class op would be class-filtered out and pass 1 would miss it. R1's fix supplies the
  fold, so R6's short-circuit has the op to trip on. The two findings' fixes are mutually
  load-bearing and both landed. Clean.
- **Verdict**: VERIFIED

#### Verify R7: I4 matrix corners missing
- **Original issue**: the I4 MATCH test matrix omitted the cross-class-deref corner
  (classification AND mutation-correctness), the unnamed-`out()` edge case, and a negative
  no-deref row.
- **Fix applied**: Validation adds the cross-class-dereference WHERE
  (`where:(assignee.name=?)`) asserting both K0_NONE classification and correctness when
  the dereferenced record mutates (lines 288-291), the negative `where:(i.title=?)` row
  that does NOT route to K0_NONE (line 291), and the unnamed-`out()`→`E` base-closure case
  in the edge-class extraction unit test (lines 292-296). Step 1 (lines 129-138) defines
  the link-path-dereference gate distinct from the cross-alias-state gate.
- **Re-check**:
  - Track-file location: Validation 288-296, step 1 link-path-deref gate 129-138.
  - Current state: all three corners are present — positive deref (classify + mutate),
    negative no-deref, and the unnamed-`out()` base-closure unit case. The step-1 gate
    distinguishes the link-path-deref walk from `getMatchPatternInvolvedAliases` (which
    Interfaces line 363 confirms does NOT detect link-path derefs).
  - Criteria met: the matrix corners are covered; the deref gate's detection mechanism
    is specified (a dedicated walk for a path expression whose head is a property/link).
- **Regression check**: checked that the negative `where:(i.title=?)` row does not
  contradict the link-path-deref gate — `i.title` is a property of the bound alias `i`
  (not a deref into an out-of-pattern class), so it correctly stays cacheable; the gate
  fires only when the path head is a property/link dereferencing outside the pattern. The
  positive/negative pair is coherent. Clean.
- **Verdict**: VERIFIED

## New findings

None. The verification pass surfaced no fresh issue.

## Findings

(none — pure-verdict pass)

## Summary

PASS. All seven ACCEPTED Phase A risk findings (R1-R7) are VERIFIED: each amendment
landed in the re-read track file and is consistent with the current source. No
regression and no new finding. The one cosmetic note (R3's `buildView` line-number
citation drifted to 1105-1108 against the 1084 method header) is prose drift inside
the method body, not a correctness defect, and does not block.
