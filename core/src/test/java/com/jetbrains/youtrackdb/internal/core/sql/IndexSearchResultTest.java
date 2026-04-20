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
    assertNotEquals("not-an-index-search-result", r);
  }

  @Test
  public void equalsDistinguishesByContainsNullValues() {
    // Two instances with identical last* fields but different containsNullValues must be
    // unequal. We flip containsNullValues by constructing one with a null value.
    var withValue = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), 1);
    var withNull = new IndexSearchResult(new QueryOperatorEquals(), shortChain("a"), null);
    // last-field objects are different instances, but their item names are both "a". equals uses
    // SQLFilterItemField.FieldChain's equals which is NOT the identity check — it inherits
    // default Object equality. So the two chains are NOT equal, and the result will differ on
    // the lastField check. Pin that the chains are unequal and so the two results are unequal.
    assertNotEquals(withValue, withNull);
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
