/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContains;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContainsKey;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContainsValue;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorEquals;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorLike;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorMajor;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorMinor;
import org.junit.Test;

/**
 * Tests the {@link IndexSearchResult} merge / canBeMerge / equals / hashCode semantics that drive
 * composite-index candidate selection in the SQL layer.
 *
 * <p>Most straightforward merge flows (equals+equals, equals+range) are already exercised
 * indirectly via {@link com.jetbrains.youtrackdb.internal.core.sql.filter.FilterOptimizerTest} and
 * Track 5's operator/filter tests. This suite targets the remaining uncovered branches:
 *
 * <ul>
 *   <li>{@link IndexSearchResult#canBeMerged(IndexSearchResult)} — the {@code isLong()} guard
 *       (rejects chained-field references like {@code a.b}), operator-pair symmetry, and
 *       negative results for incompatible operators.</li>
 *   <li>{@link IndexSearchResult#merge(IndexSearchResult)} — the four-branch dispatch that
 *       promotes equality operators to "main" position, carries forward
 *       {@code containsNullValues}, and preserves field-value pair ordering.</li>
 *   <li>{@link IndexSearchResult#equals(Object)} / {@link IndexSearchResult#hashCode()} — the
 *       contract is not a full structural equals (pairs are compared via
 *       {@code that.fieldValuePairs.get(entry.getKey()).equals(entry.getValue())} which assumes
 *       the key is present). The null-aware edge cases are pinned here.</li>
 *   <li>{@link IndexSearchResult#isIndexEqualityOperator(
 *       com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator)} — the static dispatch
 *       over the four accepted operator classes.</li>
 * </ul>
 *
 * <p>This class extends {@link DbTestBase} because building a "long" {@link
 * SQLFilterItemField.FieldChain} (i.e. {@code a.b} with method dispatch) requires
 * {@link SQLEngine#parseCondition(String,
 * com.jetbrains.youtrackdb.internal.core.command.CommandContext)}, which in turn needs a database
 * session to look up SQLMethod registrations. Short chains (single field names) can be built
 * without a session via the non-parser constructor, but that path gives {@code isLong()==false}
 * only — insufficient for the canBeMerged branch coverage.
 */
public class IndexSearchResultTest extends DbTestBase {

  // ---------------------------------------------------------------------------
  // Helpers — build FieldChain instances with varying isLong() values
  // ---------------------------------------------------------------------------

  /** Single-name field chain — isLong() is false. */
  private SQLFilterItemField.FieldChain shortChain(String name) {
    return new SQLFilterItemField(null, name, null).getFieldChain();
  }

  /**
   * Chained field ({@code a.b}) — isLong() is true. The parser adds an {@link
   * com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodField} operation for the
   * second part, which {@link SQLFilterItemField#isFieldChain()} accepts. Method operations like
   * {@code asFloat()} would instead cause {@link SQLFilterItemField#getFieldChain()} to throw
   * {@link IllegalStateException}, so we stick with a plain dotted path.
   */
  private SQLFilterItemField.FieldChain longChain() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var condition = SQLEngine.parseCondition("a.b = 3", context).getRootCondition();
    return ((SQLFilterItemField) condition.getLeft()).getFieldChain();
  }

  // ---------------------------------------------------------------------------
  // isIndexEqualityOperator — static dispatch
  // ---------------------------------------------------------------------------

  @Test
  public void isIndexEqualityOperatorAcceptsEqualsContainsKeyValue() {
    // Four classes are accepted: Equals, Contains, ContainsKey, ContainsValue.
    assertTrue(IndexSearchResult.isIndexEqualityOperator(new QueryOperatorEquals()));
    assertTrue(IndexSearchResult.isIndexEqualityOperator(new QueryOperatorContains()));
    assertTrue(IndexSearchResult.isIndexEqualityOperator(new QueryOperatorContainsKey()));
    assertTrue(IndexSearchResult.isIndexEqualityOperator(new QueryOperatorContainsValue()));
  }

  @Test
  public void isIndexEqualityOperatorRejectsRangeAndLike() {
    // Range / Like are not equality operators even though they might appear in indexed queries.
    assertFalse(IndexSearchResult.isIndexEqualityOperator(new QueryOperatorMajor()));
    assertFalse(IndexSearchResult.isIndexEqualityOperator(new QueryOperatorMinor()));
    assertFalse(IndexSearchResult.isIndexEqualityOperator(new QueryOperatorLike()));
  }

  // ---------------------------------------------------------------------------
  // canBeMerged
  // ---------------------------------------------------------------------------

  @Test
  public void canBeMergedRejectsLongLeftChain() {
    // If EITHER side's lastField is a long chain, canBeMerged returns false unconditionally.
    var longSide = new IndexSearchResult(new QueryOperatorEquals(), longChain(), 3);
    var shortSide = new IndexSearchResult(new QueryOperatorEquals(), shortChain("b"), 4);
    assertFalse(callCanBeMerged(longSide, shortSide));
  }

  @Test
  public void canBeMergedRejectsLongRightChain() {
    // Mirror of the above — the guard is symmetric.
    var shortSide = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 3);
    var longSide = new IndexSearchResult(new QueryOperatorEquals(), longChain(), 4);
    assertFalse(callCanBeMerged(shortSide, longSide));
  }

  @Test
  public void canBeMergedAcceptsEqualsPlusRange() {
    // When one side is an equality operator and the other is a range (canBeMerged() → true
    // for both), the result is true. This is the foundational composite-index case.
    var eq = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 3);
    var range = new IndexSearchResult(new QueryOperatorMajor(), shortChain("b"), 0);
    assertTrue(callCanBeMerged(eq, range));
    // Symmetric: range-on-the-left also passes because isIndexEqualityOperator matches the
    // RIGHT side.
    assertTrue(callCanBeMerged(range, eq));
  }

  @Test
  public void canBeMergedAcceptsTwoEqualsOperators() {
    // Equality on both sides — both checks return true.
    var a = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var b = new IndexSearchResult(new QueryOperatorEquals(), shortChain("b"), 2);
    assertTrue(callCanBeMerged(a, b));
  }

  @Test
  public void canBeMergedRejectsTwoRangeOperators() {
    // Neither side is an equality operator → fails the final guard. Two range comparisons on
    // different fields cannot be merged into a single composite search.
    var r1 = new IndexSearchResult(new QueryOperatorMajor(), shortChain("a"), 0);
    var r2 = new IndexSearchResult(new QueryOperatorMinor(), shortChain("b"), 10);
    assertFalse(callCanBeMerged(r1, r2));
  }

  @Test
  public void canBeMergedAcceptsContainsKeyWithRange() {
    // ContainsKey is treated as equality in this subsystem — pairs with a range operator.
    var eq = new IndexSearchResult(new QueryOperatorContainsKey(), shortChain("m"), "k");
    var range = new IndexSearchResult(new QueryOperatorMajor(), shortChain("b"), 10);
    assertTrue(callCanBeMerged(eq, range));
  }

  @Test
  public void canBeMergedAcceptsContainsValueWithRange() {
    // Pin ContainsValue parallel to ContainsKey — the branch must treat both as equality.
    var eq = new IndexSearchResult(new QueryOperatorContainsValue(), shortChain("m"), "v");
    var range = new IndexSearchResult(new QueryOperatorMajor(), shortChain("b"), 10);
    assertTrue(callCanBeMerged(eq, range));
  }

  @Test
  public void canBeMergedAcceptsContainsWithRange() {
    // Pin Contains parallel to ContainsKey/Value.
    var eq = new IndexSearchResult(new QueryOperatorContains(), shortChain("c"), "x");
    var range = new IndexSearchResult(new QueryOperatorMajor(), shortChain("b"), 10);
    assertTrue(callCanBeMerged(eq, range));
  }

  // ---------------------------------------------------------------------------
  // merge — four-branch dispatch
  // ---------------------------------------------------------------------------

  @Test
  public void mergeTwoEqualsPromotesLeftToMain() {
    // When BOTH sides are equals, branch 1 fires (searchResult.lastOperator is Equals →
    // mergeFields(this=left, searchResult=right)). Main = LEFT. Pin the exact layout.
    var left = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var right = new IndexSearchResult(new QueryOperatorEquals(), shortChain("b"), 2);
    var merged = left.merge(right);
    assertSame(left.lastOperator, merged.lastOperator);
    assertEquals("a", merged.lastField.getItemName(0));
    assertEquals(1, merged.lastValue);
    // The right side's field name → lastValue pair moves into fieldValuePairs.
    assertEquals(2, merged.fieldValuePairs.get("b"));
    assertEquals(2, merged.fields().size());
  }

  @Test
  public void mergeRangeLeftAndEqualsRightKeepsLeftAsMain() {
    // Branch 1 (right is Equals) — regardless of left's operator, main = left.
    // This is the foundational composite-index case where the range side drives the index and
    // the equality side becomes an auxiliary.
    var left = new IndexSearchResult(new QueryOperatorMajor(), shortChain("a"), 10);
    var right = new IndexSearchResult(new QueryOperatorEquals(), shortChain("b"), 3);
    var merged = left.merge(right);
    assertSame(left.lastOperator, merged.lastOperator);
    assertEquals("a", merged.lastField.getItemName(0));
    assertEquals(10, merged.lastValue);
    assertEquals(3, merged.fieldValuePairs.get("b"));
  }

  @Test
  public void mergeEqualsLeftAndRangeRightPromotesRightToMain() {
    // Branch 2: searchResult (right) is not Equals, but this (left) IS Equals →
    // mergeFields(searchResult=right, this=left) → main = right (range).
    var left = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var right = new IndexSearchResult(new QueryOperatorMajor(), shortChain("b"), 10);
    var merged = left.merge(right);
    assertSame(right.lastOperator, merged.lastOperator);
    assertEquals("b", merged.lastField.getItemName(0));
    assertEquals(10, merged.lastValue);
    assertEquals(1, merged.fieldValuePairs.get("a"));
  }

  @Test
  public void mergeRangeLeftAndContainsKeyRightKeepsLeftAsMain() {
    // Branch 3: neither side is Equals, but searchResult (right) IS an index-equality operator
    // (ContainsKey here) → mergeFields(this=left, searchResult=right) → main = left (range).
    var left = new IndexSearchResult(new QueryOperatorMajor(), shortChain("a"), 10);
    var right = new IndexSearchResult(new QueryOperatorContainsKey(), shortChain("m"), "k");
    var merged = left.merge(right);
    assertSame(left.lastOperator, merged.lastOperator);
    assertEquals("a", merged.lastField.getItemName(0));
    assertEquals(10, merged.lastValue);
    // The right side's ContainsKey "m" → "k" moves into fieldValuePairs.
    assertEquals("k", merged.fieldValuePairs.get("m"));
  }

  @Test
  public void mergeFallbackBranchPromotesRightWhenNeitherIsEquality() {
    // Branch 4 (fallback): neither side is an equality-style operator (Equals / ContainsKey /
    // ContainsValue / Contains). mergeFields(searchResult=right, this=left) → main = right.
    // In practice this path is unreachable from canBeMerged's filter (which would return
    // false), but merge() itself does NOT guard on canBeMerged — pin the branch here.
    var left = new IndexSearchResult(new QueryOperatorMajor(), shortChain("a"), 10);
    var right = new IndexSearchResult(new QueryOperatorMinor(), shortChain("b"), 20);
    var merged = left.merge(right);
    assertSame(right.lastOperator, merged.lastOperator);
    assertEquals("b", merged.lastField.getItemName(0));
    assertEquals(20, merged.lastValue);
    // The left side's field moves into fieldValuePairs.
    assertEquals(10, merged.fieldValuePairs.get("a"));
  }

  @Test
  public void mergePropagatesContainsNullValuesFromEitherSide() {
    // The merged result's containsNullValues is the OR of both inputs. Here only the right side
    // was constructed with a null value — the merged result must reflect that.
    var left = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var right = new IndexSearchResult(new QueryOperatorEquals(), shortChain("b"), null);
    assertFalse("precondition — left was built with a non-null value",
        left.containsNullValues);
    assertTrue("precondition — right was built with a null value",
        right.containsNullValues);
    var merged = left.merge(right);
    assertTrue(merged.containsNullValues);
  }

  @Test
  public void mergeWithoutNullPropagationIsFalse() {
    // Conversely — neither side contains nulls → merged.containsNullValues is false.
    var left = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var right = new IndexSearchResult(new QueryOperatorEquals(), shortChain("b"), 2);
    assertFalse(left.merge(right).containsNullValues);
  }

  @Test
  public void mergePropagatesNullFromLeftInBranch2() {
    // Branch 2 fires when searchResult (right) is NOT Equals but this (left) IS Equals. The
    // mergeFields call is mergeFields(searchResult=right, this=left) — so inside mergeFields,
    // the LOCAL searchResult parameter is bound to left (the Equals side), and the outer-class
    // `this` reference still resolves to left (the receiver of merge()). The OR therefore reads
    // `left.containsNullValues || left.containsNullValues` — right.containsNullValues is
    // silently dropped.
    //
    // WHEN-FIXED (Track 22): mergeFields should compute containsNullValues from BOTH inputs,
    // not `this` (the outer caller). With the bug, this test passes because we pin null on the
    // LEFT side; a fix that correctly OR's `mainSearchResult.containsNullValues` with
    // `searchResult.containsNullValues` preserves the same result for this input (both
    // properly OR'd). But the symmetric test below — null on RIGHT, non-null on LEFT —
    // currently drops right's null flag, and the fix would flip it red.
    var left = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), null);
    var right = new IndexSearchResult(new QueryOperatorMajor(), shortChain("b"), 10);
    assertTrue("precondition — left carries null", left.containsNullValues);
    assertFalse("precondition — right does not", right.containsNullValues);
    assertTrue(left.merge(right).containsNullValues);
  }

  @Test
  public void mergeDropsRightNullInBranch2BugPin() {
    // WHEN-FIXED (Track 22): mergeFields uses `this.containsNullValues` (the outer caller =
    // left) instead of `mainSearchResult.containsNullValues`. Branch 2 puts RIGHT as main, but
    // the OR reads left.containsNullValues || left.containsNullValues — right's null flag is
    // lost. Pin this by constructing left=non-null + right=null (range), expect false result
    // under current buggy code. A fix would make this test flip red.
    var left = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var right = new IndexSearchResult(new QueryOperatorMajor(), shortChain("b"), null);
    assertFalse("precondition — left non-null", left.containsNullValues);
    assertTrue("precondition — right carries null", right.containsNullValues);
    assertFalse(
        "bug pin: branch 2 drops right.containsNullValues because mergeFields uses this.containsNullValues",
        left.merge(right).containsNullValues);
  }

  @Test
  public void mergeCarriesAccumulatedFieldPairsFromBothSides() {
    // Pre-populate fieldValuePairs on both sides and verify that the merged result contains
    // every entry plus the "other" side's last pair.
    // Branch 1 fires (right is Equals) → main = left (a).
    var a = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    a.fieldValuePairs.put("x", 100);
    var b = new IndexSearchResult(new QueryOperatorEquals(), shortChain("b"), 2);
    b.fieldValuePairs.put("y", 200);
    var merged = a.merge(b);
    // b's accumulator entries survive.
    assertEquals(200, merged.fieldValuePairs.get("y"));
    // a's accumulator entries survive and overwrite any b collisions (a overwrites b, per
    // putAll ordering).
    assertEquals(100, merged.fieldValuePairs.get("x"));
    // b's lastField ("b") → lastValue is added to fieldValuePairs.
    assertEquals(2, merged.fieldValuePairs.get("b"));
    // a's lastField ("a") stays in the main slot — NOT in fieldValuePairs.
    assertEquals("a", merged.lastField.getItemName(0));
    assertEquals(1, merged.lastValue);
  }

  @Test
  public void mergeReturnsFreshInstance() {
    // merge must not mutate either operand — it always allocates a new IndexSearchResult.
    var left = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var right = new IndexSearchResult(new QueryOperatorEquals(), shortChain("b"), 2);
    var merged = left.merge(right);
    assertNotSame(left, merged);
    assertNotSame(right, merged);
  }

  // ---------------------------------------------------------------------------
  // fields()
  // ---------------------------------------------------------------------------

  @Test
  public void fieldsReturnsPairedKeysPlusLastField() {
    // fields() is the union of fieldValuePairs.keySet() and a trailing lastField.getItemName(0).
    var r = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    r.fieldValuePairs.put("x", 99);
    var fields = r.fields();
    assertEquals(2, fields.size());
    assertTrue(fields.contains("x"));
    assertTrue(fields.contains("a"));
  }

  @Test
  public void fieldsOnLoneLastFieldHasOneEntry() {
    var r = new IndexSearchResult(new QueryOperatorEquals(), shortChain("only"), 1);
    assertEquals(1, r.fields().size());
    assertEquals("only", r.fields().get(0));
  }

  // ---------------------------------------------------------------------------
  // equals / hashCode
  // ---------------------------------------------------------------------------

  @Test
  public void equalsReturnsTrueForIdenticalInstance() {
    var r = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    assertEquals(r, r);
  }

  @Test
  public void equalsReturnsFalseForNullAndWrongType() {
    var r = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    assertNotEquals(r, null);
    // Flip arg order so IndexSearchResult.equals is called (not String.equals). assertNotEquals's
    // first arg is the "expected" side — the call internally does actual.equals(expected), so
    // actual MUST be the receiver we want to test.
    assertNotEquals("not-an-index-search-result", r);
  }

  @Test
  public void equalsReturnsFalseForDistinctFieldChainInstances() {
    // Two FieldChain instances built from different SQLFilterItemField objects with the same
    // name are NOT equal because FieldChain inherits Object.equals (reference equality). This
    // test pins the inequality path caused by distinct chain instances — even when the string
    // field name matches. Renamed from the earlier equalsDistinguishesByContainsNullValues,
    // which could not actually reach the containsNullValues check because lastField already
    // differs.
    var a = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var b = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    assertNotEquals(a, b);
  }

  @Test
  public void equalsDistinguishesByContainsNullValuesWithSharedChainAndOperator() {
    // To actually reach the containsNullValues comparison, reuse the SAME FieldChain and the
    // SAME QueryOperatorEquals instance. Then pin that the containsNullValues flag drives the
    // equality decision — but observe that equals's line-161 `lastValue.equals(that.lastValue)`
    // NPEs when lastValue is null. So we pin this path via two instances where ONE has a
    // null value (containsNullValues=true) and the OTHER has a non-null value — which also
    // differs on lastValue, surfacing the inequality without hitting the NPE branch.
    var chain = shortChain("a");
    var op = new QueryOperatorEquals();
    var withValue = new IndexSearchResult(op, chain, 1);
    var withNull = new IndexSearchResult(op, chain, null);
    // Different lastValue + different containsNullValues → unequal either way. equals returns
    // false at the lastValue check BEFORE reaching containsNullValues.
    assertNotEquals(withValue, withNull);
  }

  @Test
  public void equalsNpesWhenLastValueIsNullBugPin() {
    // WHEN-FIXED (Track 22): IndexSearchResult.equals dereferences lastValue at line 161
    // (`lastValue.equals(that.lastValue)`) without a null guard. When lastValue was constructed
    // as null, the comparison NPEs instead of returning true/false. Pin the NPE so that a
    // future null-safety fix (e.g. Objects.equals) makes this test flip red.
    var chain = shortChain("a");
    var op = new QueryOperatorEquals();
    var a = new IndexSearchResult(op, chain, null);
    var b = new IndexSearchResult(op, chain, null);
    try {
      a.equals(b);
      fail("expected NullPointerException — lastValue dereference bug");
    } catch (NullPointerException expected) {
      // OK — bug observable.
    }
  }

  @Test
  public void equalsNpesWhenOtherSideMissingFieldValuePairBugPin() {
    // WHEN-FIXED (Track 22): equals at line 150 does `that.fieldValuePairs.get(entry.getKey())
    // .equals(entry.getValue())` without guarding against a missing key on `that`. If `this`
    // has a key in fieldValuePairs that `that` does not, `that.fieldValuePairs.get(key)` returns
    // null and `.equals(value)` NPEs. Pin so Objects.equals or a proper missing-key branch
    // flips this red.
    var chain = shortChain("a");
    var op = new QueryOperatorEquals();
    var a = new IndexSearchResult(op, chain, 1);
    a.fieldValuePairs.put("extra-key", 99);
    var b = new IndexSearchResult(op, chain, 1);
    // b does NOT have "extra-key".
    try {
      a.equals(b);
      fail("expected NullPointerException — fieldValuePairs missing-key bug");
    } catch (NullPointerException expected) {
      // OK — bug observable.
    }
  }

  @Test
  public void equalsFindsFieldMismatchWhenLastValuesDiffer() {
    // Even with equal (identical-name) short chains, mismatched lastValue yields inequality —
    // but lastField still differs by Object identity. Combine: same lastField reuse makes the
    // contract directly observable via lastValue.
    var chain = shortChain("a");
    var a = new IndexSearchResult(new QueryOperatorEquals(), chain, 1);
    var b = new IndexSearchResult(new QueryOperatorEquals(), chain, 2);
    assertNotEquals(a, b);
  }

  @Test
  public void equalsReturnsTrueForSameLastFieldLastValueAndOperator() {
    // When lastField AND lastOperator are both reused, lastValue/containsNullValues drive the
    // comparison. QueryOperatorEquals does NOT override equals so two distinct instances are
    // unequal — we must reuse the same operator reference. fieldValuePairs are both empty so
    // the loop is a no-op.
    var chain = shortChain("a");
    var op = new QueryOperatorEquals();
    var a = new IndexSearchResult(op, chain, 1);
    var b = new IndexSearchResult(op, chain, 1);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void hashCodeIncludesFieldValuePairsAndLastFields() {
    // hashCode accumulates: lastOperator, each fieldValuePairs entry, lastField, lastValue,
    // containsNullValues. Pin stability — two instances with identical state must hash equal.
    var chain = shortChain("a");
    var op = new QueryOperatorEquals();
    var a = new IndexSearchResult(op, chain, 1);
    a.fieldValuePairs.put("x", 100);
    var b = new IndexSearchResult(op, chain, 1);
    b.fieldValuePairs.put("x", 100);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void hashCodeHandlesNullLastValueAndNullEntries() {
    // Null entries in fieldValuePairs are SKIPPED by hashCode (the null-guard inside the loop).
    // lastValue null is also skipped. Pin that a null-value instance has a stable, consistent
    // hash across calls.
    var r = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), null);
    r.fieldValuePairs.put("nullKey", null);
    assertEquals(r.hashCode(), r.hashCode());
    // Construction with a null value sets containsNullValues=true — pin that the constructor
    // correctly derives it.
    assertTrue(r.containsNullValues);
  }

  @Test
  public void constructorPreservesOperatorFieldAndValue() {
    // Sanity — the public fields exposed by IndexSearchResult must match ctor inputs.
    var op = new QueryOperatorEquals();
    var chain = shortChain("a");
    var r = new IndexSearchResult(op, chain, 99);
    assertSame(op, r.lastOperator);
    assertSame(chain, r.lastField);
    assertEquals(99, r.lastValue);
    assertFalse(r.containsNullValues);
    assertNotNull(r.fieldValuePairs);
    assertTrue(r.fieldValuePairs.isEmpty());
  }

  @Test
  public void constructorWithNullValueSetsContainsNullValues() {
    // containsNullValues is derived in the constructor from value == null — pin that branch.
    var r = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), null);
    assertTrue(r.containsNullValues);
  }

  // ---------------------------------------------------------------------------
  // Helper — canBeMerged is package-private, but we're in the same package
  // ---------------------------------------------------------------------------

  private boolean callCanBeMerged(IndexSearchResult a, IndexSearchResult b) {
    // Wrapper to improve readability. canBeMerged is package-private — accessible because this
    // test lives in com.jetbrains.youtrackdb.internal.core.sql.
    return a.canBeMerged(b);
  }
}
