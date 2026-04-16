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
package com.jetbrains.youtrackdb.internal.core.sql.operator;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrackdb.internal.core.sql.SQLHelper;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for standalone comparison operators that do NOT require a database session. Uses
 * BasicCommandContext (no session) for operators that need a context for variable caching.
 *
 * <p>Covers: Like, ContainsKey, ContainsText, And, Or, Not, Is, Matches.
 */
public class StandaloneComparisonOperatorsTest {

  private static final EntitySerializer SERIALIZER =
      RecordSerializerBinary.INSTANCE.getCurrentSerializer();

  private Object eval(QueryOperator op, Object left, Object right) {
    return op.evaluateRecord(null, null, null, left, right, null, SERIALIZER);
  }

  private Object evalWithContext(QueryOperator op, Object left, Object right) {
    return op.evaluateRecord(
        null, null, null, left, right, new BasicCommandContext(), SERIALIZER);
  }

  // ===== QueryOperatorLike =====

  @Test
  public void testLikeExactMatch() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "hello", "hello"));
  }

  @Test
  public void testLikePercentWildcard() {
    // % matches any sequence of characters
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "hello world", "%world"));
  }

  @Test
  public void testLikePercentWildcardAtStart() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "hello world", "hello%"));
  }

  @Test
  public void testLikePercentWildcardBothEnds() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "hello world", "%lo wo%"));
  }

  @Test
  public void testLikeQuestionMarkWildcard() {
    // ? matches a single character
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "hello", "hell?"));
  }

  @Test
  public void testLikeNoMatch() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(false, eval(like, "hello", "world"));
  }

  @Test
  public void testLikeCaseInsensitive() {
    // QueryHelper.like() converts both operands to lowercase
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "HELLO", "hello"));
  }

  @Test
  public void testLikeNullLeftReturnsFalse() {
    // EqualityNotNulls returns false for null operands
    var like = new QueryOperatorLike();
    Assert.assertEquals(false, eval(like, null, "hello"));
  }

  @Test
  public void testLikeNullRightReturnsFalse() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(false, eval(like, "hello", null));
  }

  @Test
  public void testLikeMultiValueReturnsFalse() {
    // MultiValue left or right returns false
    var like = new QueryOperatorLike();
    Assert.assertEquals(false, eval(like, Arrays.asList("a", "b"), "a"));
  }

  @Test
  public void testLikeSpecialRegexCharsEscaped() {
    // Dots and other regex chars should be escaped by QueryHelper.like
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "file.txt", "file.txt"));
    // Without escaping, "." would match any character
    Assert.assertEquals(false, eval(like, "fileXtxt", "file.txt"));
  }

  // ===== QueryOperatorContainsKey =====

  @Test
  public void testContainsKeyMapLeftKeyPresent() {
    var op = new QueryOperatorContainsKey();
    Map<String, Object> map = new HashMap<>();
    map.put("name", "John");
    Assert.assertEquals(true, eval(op, map, "name"));
  }

  @Test
  public void testContainsKeyMapLeftKeyAbsent() {
    var op = new QueryOperatorContainsKey();
    Map<String, Object> map = new HashMap<>();
    map.put("name", "John");
    Assert.assertEquals(false, eval(op, map, "age"));
  }

  @Test
  public void testContainsKeyMapRightKeyPresent() {
    // ContainsKey checks both directions — map can be on either side
    var op = new QueryOperatorContainsKey();
    Map<String, Object> map = new HashMap<>();
    map.put("name", "John");
    Assert.assertEquals(true, eval(op, "name", map));
  }

  @Test
  public void testContainsKeyEmptyMap() {
    var op = new QueryOperatorContainsKey();
    Assert.assertEquals(false, eval(op, Collections.emptyMap(), "anything"));
  }

  @Test
  public void testContainsKeyNeitherIsMapReturnsFalse() {
    var op = new QueryOperatorContainsKey();
    Assert.assertEquals(false, eval(op, "hello", "world"));
  }

  @Test
  public void testContainsKeyNullOperandReturnsFalse() {
    var op = new QueryOperatorContainsKey();
    Assert.assertEquals(false, eval(op, null, "key"));
  }

  @Test
  public void testContainsKeyIndexReuseType() {
    var op = new QueryOperatorContainsKey();
    Assert.assertEquals(IndexReuseType.INDEX_METHOD, op.getIndexReuseType("a", "b"));
  }

  // ===== QueryOperatorContainsText =====

  @Test
  public void testContainsTextSubstringPresent() {
    var op = new QueryOperatorContainsText();
    Assert.assertEquals(true, eval(op, "hello world", "world"));
  }

  @Test
  public void testContainsTextSubstringAbsent() {
    var op = new QueryOperatorContainsText();
    Assert.assertEquals(false, eval(op, "hello world", "xyz"));
  }

  @Test
  public void testContainsTextExactMatch() {
    var op = new QueryOperatorContainsText();
    Assert.assertEquals(true, eval(op, "hello", "hello"));
  }

  @Test
  public void testContainsTextNullLeftReturnsFalse() {
    var op = new QueryOperatorContainsText();
    Assert.assertEquals(false, eval(op, null, "hello"));
  }

  @Test
  public void testContainsTextNullRightReturnsFalse() {
    var op = new QueryOperatorContainsText();
    Assert.assertEquals(false, eval(op, "hello", null));
  }

  @Test
  public void testContainsTextCaseSensitiveByDefault() {
    // evaluateRecord uses raw indexOf — case sensitive despite ignoreCase field
    // (ignoreCase is never consulted in evaluateRecord — pre-existing inconsistency)
    var op = new QueryOperatorContainsText();
    Assert.assertEquals(false, eval(op, "Hello World", "WORLD"));
  }

  @Test
  public void testContainsTextIgnoreCaseFieldNotConsulted() {
    // Even with ignoreCase=false, the evaluateRecord method behaves the same
    // because it never checks the ignoreCase field — pre-existing inconsistency
    var op = new QueryOperatorContainsText(false);
    Assert.assertFalse(op.isIgnoreCase());
    Assert.assertEquals(false, eval(op, "Hello World", "WORLD"));
  }

  @Test
  public void testContainsTextDefaultIgnoreCaseTrue() {
    var op = new QueryOperatorContainsText();
    Assert.assertTrue(op.isIgnoreCase());
  }

  @Test
  public void testContainsTextCustomSyntax() {
    var op = new QueryOperatorContainsText();
    Assert.assertEquals("<left> CONTAINSTEXT[( noignorecase ] )] <right>", op.getSyntax());
  }

  @Test
  public void testContainsTextIndexReuseType() {
    var op = new QueryOperatorContainsText();
    Assert.assertEquals(IndexReuseType.INDEX_METHOD, op.getIndexReuseType("a", "b"));
  }

  // ===== QueryOperatorAnd =====

  @Test
  public void testAndTrueAndTrue() {
    var and = new QueryOperatorAnd();
    Assert.assertEquals(true, eval(and, true, true));
  }

  @Test
  public void testAndTrueAndFalse() {
    var and = new QueryOperatorAnd();
    Assert.assertEquals(false, eval(and, true, false));
  }

  @Test
  public void testAndFalseAndTrue() {
    var and = new QueryOperatorAnd();
    Assert.assertEquals(false, eval(and, false, true));
  }

  @Test
  public void testAndFalseAndFalse() {
    var and = new QueryOperatorAnd();
    Assert.assertEquals(false, eval(and, false, false));
  }

  @Test
  public void testAndNullLeftReturnsFalse() {
    var and = new QueryOperatorAnd();
    Assert.assertEquals(false, eval(and, null, true));
  }

  @Test
  public void testAndCanShortCircuitOnFalse() {
    var and = new QueryOperatorAnd();
    Assert.assertTrue(and.canShortCircuit(Boolean.FALSE));
    Assert.assertFalse(and.canShortCircuit(Boolean.TRUE));
    Assert.assertFalse(and.canShortCircuit(null));
  }

  @Test
  public void testAndIndexReuseType() {
    var and = new QueryOperatorAnd();
    Assert.assertEquals(IndexReuseType.INDEX_INTERSECTION, and.getIndexReuseType("a", "b"));
    Assert.assertEquals(IndexReuseType.NO_INDEX, and.getIndexReuseType(null, "b"));
    Assert.assertEquals(IndexReuseType.NO_INDEX, and.getIndexReuseType("a", null));
  }

  @Test
  public void testAndRidRangeWithNonConditionOperands() {
    // When operands are not SQLFilterCondition, RID ranges are null
    var and = new QueryOperatorAnd();
    Assert.assertNull(and.getBeginRidRange(null, "a", "b"));
    Assert.assertNull(and.getEndRidRange(null, "a", "b"));
  }

  // ===== QueryOperatorOr =====

  @Test
  public void testOrTrueOrTrue() {
    var or = new QueryOperatorOr();
    Assert.assertEquals(true, eval(or, true, true));
  }

  @Test
  public void testOrTrueOrFalse() {
    var or = new QueryOperatorOr();
    Assert.assertEquals(true, eval(or, true, false));
  }

  @Test
  public void testOrFalseOrTrue() {
    var or = new QueryOperatorOr();
    Assert.assertEquals(true, eval(or, false, true));
  }

  @Test
  public void testOrFalseOrFalse() {
    var or = new QueryOperatorOr();
    Assert.assertEquals(false, eval(or, false, false));
  }

  @Test
  public void testOrNullLeftReturnsFalse() {
    var or = new QueryOperatorOr();
    Assert.assertEquals(false, eval(or, null, true));
  }

  @Test
  public void testOrCanShortCircuitOnTrue() {
    var or = new QueryOperatorOr();
    Assert.assertTrue(or.canShortCircuit(Boolean.TRUE));
    Assert.assertFalse(or.canShortCircuit(Boolean.FALSE));
    Assert.assertFalse(or.canShortCircuit(null));
  }

  @Test
  public void testOrIndexReuseType() {
    var or = new QueryOperatorOr();
    Assert.assertEquals(IndexReuseType.INDEX_UNION, or.getIndexReuseType("a", "b"));
    Assert.assertEquals(IndexReuseType.NO_INDEX, or.getIndexReuseType(null, "b"));
  }

  @Test
  public void testOrRidRangeWithNonConditionOperands() {
    var or = new QueryOperatorOr();
    Assert.assertNull(or.getBeginRidRange(null, "a", "b"));
    Assert.assertNull(or.getEndRidRange(null, "a", "b"));
  }

  // ===== QueryOperatorNot =====

  @Test
  public void testNotTrueReturnsFalse() {
    var not = new QueryOperatorNot();
    Assert.assertEquals(false, eval(not, true, null));
  }

  @Test
  public void testNotFalseReturnsTrue() {
    var not = new QueryOperatorNot();
    Assert.assertEquals(true, eval(not, false, null));
  }

  @Test
  public void testNotNullReturnsFalse() {
    var not = new QueryOperatorNot();
    Assert.assertEquals(false, eval(not, null, null));
  }

  @Test
  public void testNotWrappingAnotherOperator() {
    // NOT wrapping AND: NOT(true AND false) = NOT(false) = true
    var and = new QueryOperatorAnd();
    var notAnd = new QueryOperatorNot(and);
    Assert.assertEquals(true, eval(notAnd, true, false));
  }

  @Test
  public void testNotWrappingAnotherOperatorTrueResult() {
    // NOT(true AND true) = NOT(true) = false
    var and = new QueryOperatorAnd();
    var notAnd = new QueryOperatorNot(and);
    Assert.assertEquals(false, eval(notAnd, true, true));
  }

  @Test
  public void testNotIsUnary() {
    Assert.assertTrue(new QueryOperatorNot().isUnary());
  }

  @Test
  public void testNotGetNextReturnsWrappedOperator() {
    var and = new QueryOperatorAnd();
    var notAnd = new QueryOperatorNot(and);
    Assert.assertSame(and, notAnd.getNext());
  }

  @Test
  public void testNotGetNextNullWhenStandalone() {
    var not = new QueryOperatorNot();
    Assert.assertNull(not.getNext());
  }

  @Test
  public void testNotIndexReuseType() {
    var not = new QueryOperatorNot();
    Assert.assertEquals(IndexReuseType.NO_INDEX, not.getIndexReuseType(null, null));
  }

  @Test
  public void testNotRidRangeWithNonConditionOperands() {
    var not = new QueryOperatorNot();
    Assert.assertNull(not.getBeginRidRange(null, "a", "b"));
    Assert.assertNull(not.getEndRidRange(null, "a", "b"));
  }

  // ===== QueryOperatorIs =====
  // IS operator requires a non-null SQLFilterCondition because evaluateExpression
  // checks iCondition.getLeft(). We create a minimal condition with String left/right
  // so the SQLFilterItemField DEFINED-handling block is skipped.

  private Object evalIs(Object left, Object right) {
    var is = new QueryOperatorIs();
    // Create a condition with simple string left — not a SQLFilterItemField,
    // so the DEFINED/NOT DEFINED handling block is skipped
    var condition = new SQLFilterCondition("stub", is, right);
    return is.evaluateRecord(null, null, condition, left, right, null, SERIALIZER);
  }

  @Test
  public void testIsNullIsNull() {
    // null IS null → identity comparison: null == null → true
    Assert.assertEquals(true, evalIs(null, null));
  }

  @Test
  public void testIsNonNullIsNull() {
    // "hello" IS null → identity: "hello" == null → false
    Assert.assertEquals(false, evalIs("hello", null));
  }

  @Test
  public void testIsNullIsNonNull() {
    Assert.assertEquals(false, evalIs(null, "hello"));
  }

  @Test
  public void testIsNotNullSentinelLeftNonNull() {
    // "hello" IS NOT_NULL → iLeft != null → true
    Assert.assertEquals(true, evalIs("hello", SQLHelper.NOT_NULL));
  }

  @Test
  public void testIsNotNullSentinelLeftNull() {
    Assert.assertEquals(false, evalIs(null, SQLHelper.NOT_NULL));
  }

  @Test
  public void testIsNotNullSentinelOnLeftSide() {
    // NOT_NULL IS "hello" → iRight != null → true
    var is = new QueryOperatorIs();
    var condition = new SQLFilterCondition("stub", is, "hello");
    Assert.assertEquals(
        true, is.evaluateRecord(null, null, condition, SQLHelper.NOT_NULL, "hello", null,
            SERIALIZER));
  }

  @Test
  public void testIsSameObjectIdentity() {
    // Identity comparison for non-null, non-sentinel objects
    var obj = new Object();
    Assert.assertEquals(true, evalIs(obj, obj));
  }

  @Test
  public void testIsDifferentObjectsReturnsFalse() {
    // Different objects, even if equal, return false (identity comparison)
    Assert.assertEquals(false, evalIs("hello", new String("hello")));
  }

  @Test
  public void testIsIndexReuseType() {
    var is = new QueryOperatorIs();
    Assert.assertEquals(IndexReuseType.INDEX_METHOD, is.getIndexReuseType("a", null));
    Assert.assertEquals(IndexReuseType.NO_INDEX, is.getIndexReuseType("a", "b"));
  }

  // ===== QueryOperatorMatches =====

  @Test
  public void testMatchesSimplePattern() {
    var matches = new QueryOperatorMatches();
    Assert.assertEquals(true, evalWithContext(matches, "hello", "hello"));
  }

  @Test
  public void testMatchesRegexPattern() {
    var matches = new QueryOperatorMatches();
    Assert.assertEquals(true, evalWithContext(matches, "hello123", "hello\\d+"));
  }

  @Test
  public void testMatchesRegexNoMatch() {
    var matches = new QueryOperatorMatches();
    Assert.assertEquals(false, evalWithContext(matches, "hello", "\\d+"));
  }

  @Test
  public void testMatchesFullStringRequired() {
    // matches() requires full string match, not partial
    var matches = new QueryOperatorMatches();
    Assert.assertEquals(false, evalWithContext(matches, "hello world", "hello"));
  }

  @Test
  public void testMatchesNullLeftReturnsFalse() {
    var matches = new QueryOperatorMatches();
    Assert.assertEquals(false, evalWithContext(matches, null, "hello"));
  }

  @Test
  public void testMatchesNullRightReturnsFalse() {
    var matches = new QueryOperatorMatches();
    Assert.assertEquals(false, evalWithContext(matches, "hello", null));
  }

  @Test
  public void testMatchesPatternCaching() {
    // Matches caches compiled patterns in the context. Calling twice with
    // the same pattern should reuse the cached Pattern object.
    var matches = new QueryOperatorMatches();
    var context = new BasicCommandContext();
    matches.evaluateRecord(null, null, null, "abc", "a.*", context, SERIALIZER);
    matches.evaluateRecord(null, null, null, "axyz", "a.*", context, SERIALIZER);
    // Verify the pattern was cached (key is "MATCHES_" + hashCode)
    var key = "MATCHES_" + "a.*".hashCode();
    Assert.assertNotNull(context.getVariable(key));
  }

  @Test
  public void testMatchesIndexReuseType() {
    var matches = new QueryOperatorMatches();
    Assert.assertEquals(IndexReuseType.NO_INDEX, matches.getIndexReuseType("a", "b"));
  }
}
