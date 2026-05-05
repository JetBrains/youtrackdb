# Track 15 — Phase C Code Review (iter-2 gate check)

Five sub-agents (CQ/BC/TB/TC/TS) re-checked iter-1 fixes against
`git diff 587dfae4e6..HEAD` and re-assessed deferred items.

## Verdict per dimension

| Dim | Verdict | Notes |
|---|---|---|
| BC | PASS | BC1/BC2/BC3 VERIFIED. BC4–BC7 confirmed suggestion-tier. No new findings. |
| TB | PASS | TB1/TB2 blockers VERIFIED via PSI. Deferred TB3–TB12 confirmed deferrable. No new findings. |
| CQ | FAIL | CQ1 partial — sweep missed hyphenated/`Phase A` variants. CQ2/TS2 VERIFIED. New: **CQ11 should-fix**, plus CQ12/CQ13 suggestions. |
| TC | FAIL | TC1 confirmed STILL OPEN (escalated to should-fix). TC2 REJECTED — production guards reject null at write time, gap unreachable for Link converters. TC13 (Embedded Set/Map symmetry) added as suggestion. |
| TS | FAIL | TS1 confirmed STILL OPEN (escalated to should-fix). TS2 VERIFIED. TS3–TS9 confirmed deferrable. |

## Iter-2 fixes applied (commit pending)

1. **TS1 [should-fix]** — `EntityImplTest#testRemovingReadonlyField` and
   `#testUndo` now wrap their bodies in `try { ... } finally {
   youTrackDB.drop(dbName); }` so the side-DB is dropped even on failure.
   Mirrors the existing `testKeepSchemafullFieldTypeSerialization` pattern.

2. **TC1 [should-fix]** — Added
   `LinksRewriterTest#testVisitFieldOnNullValueReturnsNull` pinning the
   `valuesConverter == null` arm exercised by the most-frequent input
   shape (cleared-link payload). Verified `ImportConvertersFactory.
   getConverter(null, …)` returns null because no `instanceof` arm
   matches a null value.

3. **CQ11 [should-fix]** — Swept 7 ephemeral-identifier residues from
   the iter-1 fix's narrow regex:
   - `EntityHelperDeadCodeTest` × 3 sites (`Phase A confirmed`,
     `Step-1 pin`, `Phase A's PSI audit / Step-2 implementation`)
   - `EntityComparatorDeadCodeTest` × 1 (`Phase A PSI audit`,
     `step 1 lands`)
   - `RecordVersionHelperDeadCodeTest` × 1 (`Phase A's PSI audit`)
   - `EntityImplTest` × 1 (`Pre-Track-22 PSI audit`)

   All rewrites name the durable artifact (mcp-steroid PSI all-scope
   ReferencesSearch, the dead-code pinning class, the live-surface
   tests) instead of citing workflow phase/step/track labels.

   Verification grep:
   `grep -rnE 'Track[ -]?[0-9]+|Step[ -]?[0-9]+|\bPhase [A-Z]\b' core/src/test/java/.../{record,db/tool}`
   returns zero hits across the 42 Track 15 files. Three pre-existing
   hits in `EntityImplPageFrameTest.java` are out-of-scope (introduced
   by PR #926, not by this track).

## Findings deferred to Track 22 absorption queue

- **CQ12 [suggestion]** —
  `DatabaseExportImportRoundTripTest.roundTripPreservesEntityContentForUnambiguousTypes`
  is 231 lines; extract `buildNameKeyedRidMapper` and
  `assertEntityRoundTrip` helpers in the cleanup pass.
- **CQ13 [suggestion]** — `EntityComparatorDeadCodeTest` lowercase
  `step 1` ambiguity already addressed inline by the CQ11 sweep.
- **TC13 [suggestion]** — `EmbeddedSetConverterTest` and
  `EmbeddedMapConverterTest` lack the null-element symmetry test that
  `EmbeddedListConverterTest.testListWithNullElementReturnedByReferenceWhenNoChange`
  has. Verify reachability of `add(null)` / `put(k, null)` on the
  embedded wrappers before adding (the abstract base's null arm may be
  unreachable through the wrappers' `checkValue` chain).

## Verification

- `./mvnw -pl core test-compile` PASS.
- Touched tests run in isolation (46 tests across 5 classes): all PASS.
- Spotless apply clean.
- Ephemeral-identifier sweep clean across all 42 Track 15 files.

## Iter-2 totals

- Open blockers: 0
- Open should-fix at iter-1 entry: 7 (TS1, TC1, CQ11 net-new from misses)
- Should-fix fixed in iter-2: 3 (TS1, TC1, CQ11)
- Should-fix open after iter-2: 0
- Suggestions deferred to Track 22: 3 (CQ12, TC13, plus CQ13 inline-addressed)

**Summary: PASS pending iter-3 gate check** (or accept as merge-ready
since all gate-blocking items are fixed and all open items are
suggestion-tier).
