/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.fetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Standalone unit tests for {@link FetchPlan}'s parser, {@link FetchPlan#getDepthLevel} depth
 * lookup, and {@link FetchPlan#has} presence predicate. No database session is required — the
 * class is pure string parsing backed by two {@code HashMap}s.
 *
 * <p>Every test pins an observable parser outcome (successful branch or thrown exception) or a
 * behavioural contract of {@code getDepthLevel} / {@code has}. Numeric levels are chosen so that
 * a mutation swapping two branches' bookkeeping produces a different return value — avoiding
 * assertion-weak pins that would pass under either branch.
 *
 * <p>WHEN-FIXED: Track 22 — delete core/fetch/ package (0 callers outside self + DepthFetchPlanTest).
 */
public class FetchPlanParserTest {

  // ---------------------------------------------------------------------------
  // Constructor — null / empty / default plan
  // ---------------------------------------------------------------------------

  @Test
  public void nullPlanProducesWildcardOnlyFetchPlan() {
    // A null plan is a legal no-op: the only entry is the built-in wildcard at depth 0.
    var plan = new FetchPlan(null);
    assertEquals("wildcard default", 0, plan.getDepthLevel("anyField", 0));
    assertFalse("no fields known", plan.has("anyField", 0));
  }

  @Test
  public void emptyPlanProducesWildcardOnlyFetchPlan() {
    var plan = new FetchPlan("");
    assertEquals(0, plan.getDepthLevel("anyField", 0));
    assertFalse(plan.has("anyField", 0));
  }

  // ---------------------------------------------------------------------------
  // Constructor — `field:N` single-level branch
  // ---------------------------------------------------------------------------

  @Test
  public void simpleFieldEntryAppliesOnlyAtLevelZero() {
    // `ref:3` → only level 0 matches the configured depth; subsequent levels fall through
    // to the else branch and match by key equality (still returning 3 per production semantics
    // for out-of-range keys) but has() returns true because the key-equality check fires in
    // either branch.
    var plan = new FetchPlan("ref:3");
    assertEquals("in-range key equality → configured level", 3, plan.getDepthLevel("ref", 0));
    assertTrue(plan.has("ref", 0));
    assertFalse("non-configured field falls through to default 0",
        plan.has("other", 0));
    assertEquals("non-configured field → default wildcard 0",
        0, plan.getDepthLevel("other", 0));
  }

  @Test
  public void negativeLevelForSimpleFieldSetsInfiniteRange() {
    // `ref:-1` → level=-1 branch emits FetchPlanLevel(0, -1, -1) so the range covers every level
    // from 0 to infinity. getDepthLevel returns the configured -1 for any level.
    var plan = new FetchPlan("ref:-1");
    assertEquals(-1, plan.getDepthLevel("ref", 0));
    assertEquals("infinite range still matches at deep levels", -1,
        plan.getDepthLevel("ref", 100));
    assertTrue(plan.has("ref", 0));
    assertTrue(plan.has("ref", 100));
  }

  // ---------------------------------------------------------------------------
  // Constructor — `[from-to]field:N` range branch
  // ---------------------------------------------------------------------------

  @Test
  public void rangeBracketAppliesInclusiveDepthBounds() {
    // `[2-4]ref:7` → smartSplit("2-4", '-', ' ') returns ["2","4"], so depth range is [2,4].
    // Level 7 is returned only when iCurrentLevel is in [2,4]; levels outside hit the else
    // branch which falls back to default 0 for a non-matching key, or the configured level
    // for a matching key (the "out-of-range but key equals" branch).
    var plan = new FetchPlan("[2-4]ref:7");
    assertEquals("in-range inclusive lower bound", 7, plan.getDepthLevel("ref", 2));
    assertEquals("in-range middle", 7, plan.getDepthLevel("ref", 3));
    assertEquals("in-range inclusive upper bound", 7, plan.getDepthLevel("ref", 4));
    // Out of range: key equals → returns configured level by the else branch.
    assertEquals("out-of-range key equality still returns level via else branch",
        7, plan.getDepthLevel("ref", 0));
    assertEquals(7, plan.getDepthLevel("ref", 5));
    // Non-matching field at any level falls through to default.
    assertEquals(0, plan.getDepthLevel("other", 3));
  }

  @Test
  public void rangeBracketWithOmittedUpperBoundDefaultsToInfinity() {
    // `[3-]ref:5` → indexRanges = ["3",""], rangeFrom=3, rangeTo=-1 (empty → -1).
    // Depth range is [3, ∞).
    var plan = new FetchPlan("[3-]ref:5");
    assertEquals(5, plan.getDepthLevel("ref", 3));
    assertEquals("infinite upper bound matches deep levels", 5,
        plan.getDepthLevel("ref", 100));
    // Below lower bound still matches via key-equality in the else branch per production
    // semantics (pinned here so a future mutation that tightens this branch is caught).
    assertEquals(5, plan.getDepthLevel("ref", 2));
  }

  @Test
  public void rangeBracketWithStarProducesInfiniteRange() {
    // `[*]ref:-1` → range=="*" branch emits FetchPlanLevel(0, -1, level). Used by the existing
    // DepthFetchPlanTest#testFullDepthFetchPlan as the "follow all links deeply" pattern.
    var plan = new FetchPlan("[*]ref:-1");
    assertEquals(-1, plan.getDepthLevel("ref", 0));
    assertEquals(-1, plan.getDepthLevel("ref", 10));
  }

  @Test
  public void rangeBracketWithSingleValueProducesPointRange() {
    // `[5]ref:2` — no dash, not "*" → FetchPlanLevel(5,5,2). Level 5 matches, 4/6 do not
    // (except via the out-of-range else branch's key-equality fallback which still returns 2).
    var plan = new FetchPlan("[5]ref:2");
    assertEquals("exact point level", 2, plan.getDepthLevel("ref", 5));
    // Key equality still fires in the else branch: production semantics return configured level
    // regardless of range mismatch. Pinned so the branch is observable.
    assertEquals(2, plan.getDepthLevel("ref", 0));
    assertEquals(2, plan.getDepthLevel("ref", 10));
  }

  // ---------------------------------------------------------------------------
  // Constructor — `field*:N` startsWith branch
  // ---------------------------------------------------------------------------

  @Test
  public void trailingStarRegistersPrefixMatchIntoStartsWithMap() {
    // `field*:3` → key.length() > 1 AND endsWith("*") → strips "*", stores "field" into
    // fetchPlanStartsWith. Any field whose name begins with "field" matches.
    var plan = new FetchPlan("field*:3");
    assertEquals("prefix match returns configured level", 3,
        plan.getDepthLevel("fieldName", 0));
    assertEquals(3, plan.getDepthLevel("fieldX", 0));
    assertTrue(plan.has("fieldAnything", 0));
    // "" prefix matches any string (startsWith("") is always true) but "field" requires the
    // prefix to be present.
    assertEquals("non-prefix field falls through to default", 0,
        plan.getDepthLevel("other", 0));
    assertFalse(plan.has("other", 0));
  }

  @Test
  public void trailingStarWithNegativeLevelSetsInfiniteDepth() {
    // `field*:-1` exercises the startsWith-map branch with level=-1 (infinite-depth case).
    var plan = new FetchPlan("field*:-1");
    assertEquals(-1, plan.getDepthLevel("fieldName", 0));
    assertEquals(-1, plan.getDepthLevel("fieldName", 100));
  }

  // ---------------------------------------------------------------------------
  // Constructor — `*:N` default-wildcard override
  // ---------------------------------------------------------------------------

  @Test
  public void bareStarEntryOverridesDefaultWildcardLevel() {
    // `*:2` — key="*" has length 1, so the `key.length() > 1 && endsWith("*")` guard is false.
    // fetchPlan.put("*", fp) OVERWRITES the constructor-installed wildcard at depth 0.
    var plan = new FetchPlan("*:2");
    assertEquals("wildcard overridden to 2", 2, plan.getDepthLevel("anyField", 0));
    // Deep levels fall through to the defDepthLevel read at getDepthLevel entry — which is the
    // overridden wildcard's level, 2.
    assertEquals("wildcard still applies as default for deep levels", 2,
        plan.getDepthLevel("anyField", 100));
  }

  @Test
  public void bareStarEntryWithNegativeOneYieldsInfiniteWildcard() {
    // `*:-1` — same wildcard-override branch, level=-1 path.
    var plan = new FetchPlan("*:-1");
    assertEquals(-1, plan.getDepthLevel("anyField", 0));
    assertEquals(-1, plan.getDepthLevel("anyField", 5));
  }

  // ---------------------------------------------------------------------------
  // Constructor — space-separated compound plan
  // ---------------------------------------------------------------------------

  @Test
  public void spaceSeparatedEntriesAreAllRegistered() {
    // "ref:1 other:-1 prefix*:3" exercises three different branches in a single constructor
    // call. Each clause writes to the correct backing map.
    var plan = new FetchPlan("ref:1 other:-1 prefix*:3");
    assertEquals(1, plan.getDepthLevel("ref", 0));
    assertEquals(-1, plan.getDepthLevel("other", 0));
    assertEquals(-1, plan.getDepthLevel("other", 100));
    assertEquals(3, plan.getDepthLevel("prefixField", 0));
    // A field not covered by any clause returns the unchanged default 0.
    assertEquals(0, plan.getDepthLevel("unrelated", 0));
  }

  // ---------------------------------------------------------------------------
  // Constructor — error paths
  // ---------------------------------------------------------------------------

  @Test
  public void missingColonRaisesIllegalArgumentException() {
    // `ref1` has no ':' — parts.size() becomes 1 → "Wrong fetch plan".
    var ex = assertThrows(IllegalArgumentException.class, () -> new FetchPlan("ref1"));
    assertTrue("error names the offending part",
        ex.getMessage().contains("ref1"));
    assertTrue("error identifies the failure reason",
        ex.getMessage().toLowerCase().contains("wrong fetch plan"));
  }

  @Test
  public void missingClosingBracketRaisesIllegalArgumentException() {
    // `[5ref:3` — key starts with "[" but has no "]" → endLevel == -1.
    var ex = assertThrows(IllegalArgumentException.class, () -> new FetchPlan("[5ref:3"));
    assertTrue(ex.getMessage().toLowerCase().contains("missing closing"));
    assertTrue(ex.getMessage().contains("["));
  }

  @Test
  public void dottedKeyInsideRangeBracketsRaisesIllegalArgumentException() {
    // `[5]foo.bar:3` — after range-strip the remaining key contains a dot → "Nested levels".
    var ex = assertThrows(IllegalArgumentException.class, () -> new FetchPlan("[5]foo.bar:3"));
    assertTrue("error explains the dot restriction",
        ex.getMessage().toLowerCase().contains("nested"));
  }

  @Test
  public void nonNumericLevelAfterColonRaisesNumberFormatException() {
    // `ref:x` — Integer.parseInt("x") throws NumberFormatException. Pinned so a future wrapping
    // of the cause (e.g., IllegalArgumentException) is detected.
    assertThrows(NumberFormatException.class, () -> new FetchPlan("ref:x"));
  }

  @Test
  public void extraColonInsideEntryRaisesWrongFetchPlan() {
    // `a:b:c` — split(':') yields 3 parts, not 2 → "Wrong fetch plan".
    var ex = assertThrows(IllegalArgumentException.class, () -> new FetchPlan("a:b:c"));
    assertTrue(ex.getMessage().toLowerCase().contains("wrong fetch plan"));
  }

  // ---------------------------------------------------------------------------
  // getDepthLevel — nested path splitting via dots
  // ---------------------------------------------------------------------------

  @Test
  public void nestedFieldPathMatchesLastPartAgainstConfiguredKey() {
    // Plan "ref:5" with probe path "parent.ref" at level 1 — parent loop does not find a
    // match on "parent" / "parent.ref", but fpParts[1]="ref" equals key "ref". Level 1 is
    // inside the in-range branch (because "ref" has FetchPlanLevel(0,0,5) and the probe
    // iterates fpParts indexes 0..1; at i=1, the range check is 1>=0 && 1<=0 → false, so the
    // part-equality block is SKIPPED by design. This pin documents the less-obvious fact that
    // nested path matching happens only within the configured depth range. Result: the outer
    // "ref" is NOT matched at level 1, so the default 0 applies.
    var plan = new FetchPlan("ref:5");
    assertEquals("nested part at level 1 is outside (0,0) range → default",
        0, plan.getDepthLevel("parent.ref", 1));
  }

  @Test
  public void getDepthLevelStartsWithKeyMatchUsesFixedLevelOne() {
    // When an fpLevelKey startsWith the probe path (not vice versa), production returns 1,
    // not the configured level. This exercises the second branch in getDepthLevel's in-range
    // block.
    var plan = new FetchPlan("longerKey:7");
    // "longerKey".startsWith("long") → true → return 1.
    assertEquals(1, plan.getDepthLevel("long", 0));
    assertTrue(plan.has("long", 0));
  }

  @Test
  public void getDepthLevelOutOfRangeStartsWithMatchAlsoReturnsOne() {
    // Out-of-range (else branch) also has the startsWith check. Configured range (0,0) with
    // probe at level 1 takes the else path, and the fpLevelKey.startsWith(probe) check returns 1.
    var plan = new FetchPlan("longerKey:7");
    assertEquals(1, plan.getDepthLevel("long", 1));
  }

  @Test
  public void getDepthLevelStartsWithMapIteratedAfterMainMapMisses() {
    // Plan with only a prefix-map entry: no default-wildcard override. The main loop iterates
    // over {"*": (0,0,0)} which does not match "fieldAnything" — falls through to the
    // fetchPlanStartsWith loop and returns N.
    var plan = new FetchPlan("field*:9");
    assertEquals(9, plan.getDepthLevel("field", 0));
    assertEquals(9, plan.getDepthLevel("fieldSuffix", 0));
    // Default range for prefix-map entries is (0,0), so levels > 0 fall outside the range
    // and the startsWith-map branch does NOT return the configured level. The main map also
    // misses, so the probe lands on defDepthLevel=0.
    assertEquals("prefix entry out of range at level 5 → default", 0,
        plan.getDepthLevel("fieldAtDepth", 5));
  }

  @Test
  public void getDepthLevelStartsWithMapRespectsDepthRange() {
    // `[0-0]field*:9` — the startsWith-map entry has (0,0,9) range. At level 1 the outer
    // range check fails and the method falls through to defDepthLevel.
    var plan = new FetchPlan("[0-0]field*:9");
    assertEquals(9, plan.getDepthLevel("fieldA", 0));
    // Level 1 is outside the prefix-map entry's range, so the prefix-map loop skips the
    // iteration and the method returns the default wildcard 0.
    assertEquals(0, plan.getDepthLevel("fieldA", 1));
  }

  // ---------------------------------------------------------------------------
  // has() — presence predicate
  // ---------------------------------------------------------------------------

  @Test
  public void hasReturnsFalseForFieldNotInAnyMapAndNotPrefixed() {
    var plan = new FetchPlan("ref:1");
    assertFalse(plan.has("other", 0));
    assertFalse(plan.has("other.sub", 5));
  }

  @Test
  public void hasReturnsTrueWhenMainLoopKeyEqualsProbeInRange() {
    var plan = new FetchPlan("ref:1");
    assertTrue(plan.has("ref", 0));
  }

  @Test
  public void hasReturnsTrueWhenKeyStartsWithProbeInRange() {
    // Probe "r" is startsWith'd by key "ref" → true.
    var plan = new FetchPlan("ref:1");
    assertTrue(plan.has("r", 0));
  }

  @Test
  public void hasReturnsTrueForPrefixMapEntryInRange() {
    var plan = new FetchPlan("field*:3");
    assertTrue(plan.has("fieldAnything", 0));
  }

  @Test
  public void hasReturnsFalseForPrefixMapEntryOutOfRange() {
    // Prefix map entry with narrow range.
    var plan = new FetchPlan("[0-0]field*:3");
    assertTrue(plan.has("fieldX", 0));
    // Level 1 exits the range → startsWith loop skips, main loop also misses → false.
    assertFalse(plan.has("fieldX", 1));
  }

  @Test
  public void hasReturnsTrueForOutOfRangeButKeyEquality() {
    // Plan's only configured key "ref" at (0,0,5). Probe at level 2: in-range check fails for
    // both "*" and "ref" entries; "ref" entry falls into the else branch and its key equals
    // "ref" → returns true. Pins the out-of-range else-branch semantics of has().
    var plan = new FetchPlan("ref:5");
    assertTrue(plan.has("ref", 2));
  }

  // ---------------------------------------------------------------------------
  // Nested-path iteration
  // ---------------------------------------------------------------------------

  @Test
  public void nestedPathMatchFiresInsideExplicitDeepRange() {
    // Plan `[0-5]ref:3` — fpParts iteration at i=1 checks range 1>=0 && 1<=5 → in range, then
    // fpParts[1]="ref".equals("ref") → returns 3. Without the explicit range, the default
    // (0,0) range would skip this path.
    var plan = new FetchPlan("[0-5]ref:3");
    assertEquals(3, plan.getDepthLevel("parent.ref", 1));
    assertTrue(plan.has("parent.ref", 1));
  }
}
