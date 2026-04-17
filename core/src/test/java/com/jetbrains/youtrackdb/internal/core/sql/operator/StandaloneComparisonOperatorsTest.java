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
import java.util.regex.Pattern;
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
  public void testLikePercentWildcardAtEnd() {
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
    // ? matches exactly one character
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "hello", "hell?"));
    // ? should not match zero characters
    Assert.assertEquals(false, eval(like, "hell", "hell?"));
    // ? should not match multiple characters
    Assert.assertEquals(false, eval(like, "helloo", "hell?"));
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
    var like = new QueryOperatorLike();
    // Dot should be literal, not regex wildcard
    Assert.assertEquals(true, eval(like, "file.txt", "file.txt"));
    Assert.assertEquals(false, eval(like, "fileXtxt", "file.txt"));
    // Asterisk should be literal, not regex quantifier
    Assert.assertEquals(true, eval(like, "a*b", "a*b"));
    Assert.assertEquals(false, eval(like, "aXXXb", "a*b"));
    // Plus should be literal, not regex quantifier
    Assert.assertEquals(true, eval(like, "a+b", "a+b"));
    Assert.assertEquals(false, eval(like, "aab", "a+b"));
  }

  /**
   * QueryHelper.like escapes 11 regex-special characters before calling
   * String.matches(): \ [ ] { } ( ) | * + $ ^ . Each must be treated as a
   * literal, not as its regex meaning. A regression removing any escape()
   * call causes matches() to throw PatternSyntaxException at runtime for
   * realistic inputs (JSON braces, paths with parens, currency strings).
   */
  @Test
  public void testLikeEscapesSquareBrackets() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "a[b]c", "a[b]c"));
    Assert.assertEquals(false, eval(like, "aXc", "a[b]c"));
  }

  @Test
  public void testLikeEscapesCurlyBraces() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "a{3}b", "a{3}b"));
    Assert.assertEquals(false, eval(like, "aaab", "a{3}b"));
  }

  @Test
  public void testLikeEscapesParentheses() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "(foo)", "(foo)"));
    Assert.assertEquals(false, eval(like, "foo", "(foo)"));
  }

  @Test
  public void testLikeEscapesPipeAlternation() {
    var like = new QueryOperatorLike();
    // '|' must be treated as literal — NOT regex alternation between 'a' and 'b'.
    Assert.assertEquals(true, eval(like, "a|b", "a|b"));
    Assert.assertEquals(false, eval(like, "a", "a|b"));
    Assert.assertEquals(false, eval(like, "b", "a|b"));
  }

  @Test
  public void testLikeEscapesDollarAnchor() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "$10", "$10"));
    Assert.assertEquals(false, eval(like, "10", "$10"));
  }

  @Test
  public void testLikeEscapesCaretAnchor() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "^hat", "^hat"));
    Assert.assertEquals(false, eval(like, "hat", "^hat"));
  }

  @Test
  public void testLikeEscapesBackslash() {
    var like = new QueryOperatorLike();
    // Literal backslash on both sides must compare equal.
    Assert.assertEquals(true, eval(like, "a\\b", "a\\b"));
  }

  @Test
  public void testLikeEmptyStringLeftReturnsFalse() {
    // QueryHelper.like returns false for empty left operand
    var like = new QueryOperatorLike();
    Assert.assertEquals(false, eval(like, "", "hello"));
  }

  @Test
  public void testLikeEmptyStringRightReturnsFalse() {
    // QueryHelper.like returns false for empty pattern
    var like = new QueryOperatorLike();
    Assert.assertEquals(false, eval(like, "hello", ""));
  }

  @Test
  public void testLikePercentOnlyMatchesAnything() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "anything", "%"));
  }

  @Test
  public void testLikeQuestionMarkOnlyMatchesSingleChar() {
    var like = new QueryOperatorLike();
    Assert.assertEquals(true, eval(like, "x", "?"));
    Assert.assertEquals(false, eval(like, "xy", "?"));
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
  public void testContainsKeyMapRightKeyAbsent() {
    var op = new QueryOperatorContainsKey();
    Map<String, Object> map = new HashMap<>();
    map.put("name", "John");
    Assert.assertEquals(false, eval(op, "age", map));
  }

  @Test
  public void testContainsKeyEmptyMap() {
    var op = new QueryOperatorContainsKey();
    Assert.assertEquals(false, eval(op, Collections.emptyMap(), "anything"));
  }

  @Test
  public void testContainsKeyWithNullValueStillFound() {
    // containsKey checks key presence, not value
    var op = new QueryOperatorContainsKey();
    Map<String, Object> map = new HashMap<>();
    map.put("key", null);
    Assert.assertEquals(true, eval(op, map, "key"));
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
  public void testContainsTextEmptyStringRightAlwaysMatches() {
    // String.indexOf("") returns 0, so empty search term matches any non-null string
    var op = new QueryOperatorContainsText();
    Assert.assertEquals(true, eval(op, "hello", ""));
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
    // because it never checks the ignoreCase field — pre-existing inconsistency.
    // WHEN-FIXED: when QueryOperatorContainsText.evaluateRecord starts honoring
    // the ignoreCase flag, the ignoreCase=false path will remain case-sensitive
    // and the ignoreCase=true path will become case-insensitive. Update the
    // assertion below accordingly and delete this WHEN-FIXED block.
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

  @Test(expected = NullPointerException.class)
  public void testAndNullRightThrowsNpe() {
    // AND does not guard against null right operand:
    // (Boolean) null unboxing throws NPE at QueryOperatorAnd line 52.
    // WHEN-FIXED: when QueryOperatorAnd adds a null-right guard (symmetric with
    // the existing null-left guard that returns false), change this to
    // assertEquals(false, eval(and, true, null)) and delete this WHEN-FIXED block.
    var and = new QueryOperatorAnd();
    eval(and, true, null);
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

  @Test(expected = NullPointerException.class)
  public void testOrNullRightThrowsNpe() {
    // OR does not guard against null right operand:
    // (Boolean) null unboxing throws NPE at QueryOperatorOr line 52.
    // WHEN-FIXED: when QueryOperatorOr adds a null-right guard (symmetric with
    // the existing null-left guard that returns false), change this to
    // assertEquals(false, eval(or, false, null)) and delete this WHEN-FIXED block.
    var or = new QueryOperatorOr();
    eval(or, false, null);
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
    Assert.assertEquals(IndexReuseType.NO_INDEX, or.getIndexReuseType("a", null));
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
    var key = "MATCHES_" + "a.*".hashCode();
    Object cached = context.getVariable(key);
    Assert.assertNotNull(cached);
    Assert.assertTrue("Cached value should be a Pattern", cached instanceof Pattern);
    Assert.assertEquals("a.*", ((Pattern) cached).pattern());
    // Second call should reuse the same Pattern object
    matches.evaluateRecord(null, null, null, "axyz", "a.*", context, SERIALIZER);
    Assert.assertSame("Pattern should be reused from cache", cached, context.getVariable(key));
  }

  @Test
  public void testMatchesIndexReuseType() {
    var matches = new QueryOperatorMatches();
    Assert.assertEquals(IndexReuseType.NO_INDEX, matches.getIndexReuseType("a", "b"));
  }

  /**
   * A malformed regex must surface PatternSyntaxException from Pattern.compile()
   * — there is no try/catch around compile in QueryOperatorMatches.matches().
   * Documents the caller contract: invalid regex bubbles up as a runtime
   * exception rather than silently returning false.
   */
  @Test(expected = java.util.regex.PatternSyntaxException.class)
  public void testMatchesInvalidRegexThrowsPatternSyntaxException() {
    var matches = new QueryOperatorMatches();
    evalWithContext(matches, "anything", "[unclosed");
  }

  /**
   * With null context and non-null operands, the pattern cache lookup
   * (iContext.getVariable) NPEs on a cache miss. Documents that the operator
   * requires a CommandContext for successful evaluation.
   */
  @Test(expected = NullPointerException.class)
  public void testMatchesNullContextThrowsOnCacheMiss() {
    var matches = new QueryOperatorMatches();
    // eval() helper passes null for context
    eval(matches, "hello", "h.*");
  }
}
