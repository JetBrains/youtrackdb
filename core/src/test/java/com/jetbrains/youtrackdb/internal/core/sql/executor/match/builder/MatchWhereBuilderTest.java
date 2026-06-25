package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBetweenCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsTextCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNotDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link MatchWhereBuilder}.
 *
 * <p>Each operation is verified by AST shape (the parser-emitted node classes) plus a sample of
 * the rendered SQL string via {@code toString(emptyMap, sb)}. Boolean combinators are tested at
 * cardinalities 0 / 1 / 2 / 3 to lock in the empty-throws / single-passthrough / multi-block
 * semantics. The {@code not} test asserts the produced {@link SQLNotBlock} has {@code negate=true}
 * because the default of {@code false} is a silent pass-through bug.
 */
public class MatchWhereBuilderTest {

  private final MatchWhereBuilder b = new MatchWhereBuilder();

  // ── eq / op ──

  @Test
  public void eq_producesBinaryConditionWithEqualsOperator() {
    var rhs = MatchLiteralBuilder.toLiteral("Karl");
    var expr = b.eq("firstName", rhs);

    assertTrue(expr instanceof SQLBinaryCondition);
    var bin = (SQLBinaryCondition) expr;
    assertSame("eq must use the EqualsOperator singleton", SQLEqualsOperator.INSTANCE,
        bin.getOperator());
    assertSame("right-hand expression must be passed through unchanged", rhs, bin.getRight());
    assertEquals("firstName = \"Karl\"", render(expr));
  }

  @Test
  public void op_producesBinaryConditionWithGivenOperator() {
    var rhs = MatchLiteralBuilder.toLiteral(30L);
    var expr = b.op("age", SQLGtOperator.INSTANCE, rhs);

    assertTrue(expr instanceof SQLBinaryCondition);
    var bin = (SQLBinaryCondition) expr;
    assertSame(SQLGtOperator.INSTANCE, bin.getOperator());
    assertEquals("age > 30", render(expr));
  }

  // ── in / notIn ──

  @Test
  public void in_producesInConditionWithLiteralCollection() throws Exception {
    var values = List.of(
        MatchLiteralBuilder.toLiteral(1L),
        MatchLiteralBuilder.toLiteral(2L),
        MatchLiteralBuilder.toLiteral(3L));
    var expr = b.in("status", values);

    assertTrue(expr instanceof SQLInCondition);
    // Operator field must be populated so SQLInCondition.supportsBasicCalculation() doesn't NPE.
    var operatorField = readField(expr, "operator", Object.class);
    assertNotNull("SQLInCondition.operator must not be null", operatorField);
    assertEquals("status IN [1, 2, 3]", render(expr));
  }

  @Test
  public void in_emptyValues_producesEmptyCollection() {
    var expr = b.in("status", List.of());
    assertTrue(expr instanceof SQLInCondition);
    assertEquals("status IN []", render(expr));
  }

  @Test
  public void notIn_composesAsNotOverIn() {
    var values = List.of(MatchLiteralBuilder.toLiteral(7L));
    var expr = b.notIn("status", values);

    assertTrue("notIn must compose via SQLNotBlock", expr instanceof SQLNotBlock);
    var not = (SQLNotBlock) expr;
    assertTrue("inner block must be the SQLInCondition", not.getSub() instanceof SQLInCondition);
    assertTrue("negate flag must be set so the NOT actually applies", not.isNegate());
  }

  // ── between ──

  @Test
  public void between_producesSQLBetweenCondition() {
    var lo = MatchLiteralBuilder.toLiteral(10L);
    var hi = MatchLiteralBuilder.toLiteral(20L);
    var expr = b.between("score", lo, hi);

    assertTrue("between must produce SQLBetweenCondition (range-aware index path)",
        expr instanceof SQLBetweenCondition);
    var btw = (SQLBetweenCondition) expr;
    assertSame(lo, btw.getSecond());
    assertSame(hi, btw.getThird());
    assertEquals("score BETWEEN 10 AND 20", render(expr));
  }

  // ── containsText ──

  @Test
  public void containsText_producesSQLContainsTextCondition() {
    // Full-string render assertion to catch a swap of operands (substring "contains"
    // would silently pass on `"needle" CONTAINSTEXT body`).
    var expr = b.containsText("body", "needle");
    assertTrue(expr instanceof SQLContainsTextCondition);
    assertEquals("body CONTAINSTEXT \"needle\"", render(expr));
  }

  // ── startsWith (prefix range, p⁺ increment) ──

  /**
   * {@code startsWith("name", "Jo")} produces the half-open range
   * {@code name >= "Jo" AND name < "Jp"} — an SQLAndBlock of two range conditions.
   * The upper bound "Jp" is "Jo" with its last code point ('o' → 'p') incremented.
   */
  @Test
  public void startsWith_producesHalfOpenRangeAndBlock() {
    var expr = b.startsWith("name", "Jo");
    assertTrue("startsWith must produce an SQLAndBlock of two range conditions",
        expr instanceof SQLAndBlock);
    var and = (SQLAndBlock) expr;
    assertEquals("range must have exactly two conditions", 2, and.getSubBlocks().size());
    assertEquals("name >= \"Jo\" AND name < \"Jp\"", render(expr));
  }

  /**
   * The upper bound increments the LAST code point, not the whole string:
   * {@code "ab"} → {@code "ac"} (lower bound unchanged, only the trailing char
   * bumped). Guards against a regression that incremented the wrong position.
   */
  @Test
  public void startsWith_incrementsOnlyLastCodePoint() {
    var expr = b.startsWith("name", "ab");
    assertEquals("name >= \"ab\" AND name < \"ac\"", render(expr));
  }

  /**
   * Surrogate-pair handling: the prefix ends in a supplementary-plane code point
   * (U+1F600, GRINNING FACE, encoded as a UTF-16 surrogate pair). p⁺ must
   * increment the full code point to U+1F601, not corrupt half the pair. The
   * assertion compares the decoded upper-bound code point rather than raw chars so
   * the intent is explicit.
   */
  @Test
  public void startsWith_surrogatePairLastCodePoint_incrementsFullCodePoint() {
    var grinning = new String(Character.toChars(0x1F600));
    var expr = b.startsWith("emoji", grinning);
    var upper = upperBoundLiteral(expr);
    assertEquals("upper bound must be the next supplementary code point",
        0x1F601, upper.codePointAt(0));
    assertEquals("upper bound must be a single code point (one surrogate pair)",
        2, upper.length());
  }

  /**
   * Overflow carry: a prefix whose last code point is the maximum
   * (U+10FFFF) cannot be bumped in place, so the increment carries into the
   * preceding code point and drops the maxed trailing one. {@code "a" + U+10FFFF}
   * → {@code "b"}.
   */
  @Test
  public void startsWith_maxCodePointTail_carriesIntoPrecedingCodePoint() {
    var prefix = "a" + new String(Character.toChars(Character.MAX_CODE_POINT));
    var expr = b.startsWith("name", prefix);
    var upper = upperBoundLiteral(expr);
    assertEquals("carry must drop the maxed tail and bump the preceding code point",
        "b", upper);
  }

  /**
   * Pathological all-max prefix: a single U+10FFFF code point has no finite upper
   * bound (every string starting with it is unbounded above), so the builder
   * throws and the caller declines. This input cannot arise from a realistic
   * prefix.
   */
  @Test
  public void startsWith_allMaxCodePoints_throws() {
    var prefix = new String(Character.toChars(Character.MAX_CODE_POINT));
    assertThrows(IllegalArgumentException.class, () -> b.startsWith("name", prefix));
  }

  /**
   * Empty prefix throws: the half-open range upper bound p⁺ is undefined for the
   * empty string. The Gremlin adapter checks for empty and declines before
   * reaching this method, but the guard pins the contract.
   */
  @Test
  public void startsWith_emptyPrefix_throws() {
    assertThrows(IllegalArgumentException.class, () -> b.startsWith("name", ""));
  }

  /** Extracts the upper-bound string literal from a startsWith range AndBlock. */
  private static String upperBoundLiteral(SQLBooleanExpression rangeAndBlock) {
    var and = (SQLAndBlock) rangeAndBlock;
    var upperCondition = and.getSubBlocks().get(1);
    var rendered = render(upperCondition); // e.g. `name < "Jp"`
    var firstQuote = rendered.indexOf('"');
    var lastQuote = rendered.lastIndexOf('"');
    return rendered.substring(firstQuote + 1, lastQuote);
  }

  // ── and / or — cardinality matrix 0/1/2/3 ──

  @Test
  public void and_zeroOperands_throwsIllegalState() {
    assertThrows(IllegalStateException.class, () -> b.and());
  }

  @Test
  public void and_singleOperand_returnsOperandUnwrapped() {
    var c = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var result = b.and(c);
    assertSame("single-operand and(c) must return c without wrapping", c, result);
  }

  @Test
  public void and_twoOperands_returnsSQLAndBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var result = b.and(c1, c2);
    assertTrue(result instanceof SQLAndBlock);
    assertEquals("a = 1 AND b = 2", render(result));
  }

  @Test
  public void and_threeOperands_returnsSQLAndBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var c3 = b.eq("c", MatchLiteralBuilder.toLiteral(3L));
    var result = b.and(c1, c2, c3);
    assertTrue(result instanceof SQLAndBlock);
    assertEquals("a = 1 AND b = 2 AND c = 3", render(result));
  }

  @Test
  public void or_zeroOperands_throwsIllegalState() {
    assertThrows(IllegalStateException.class, () -> b.or());
  }

  @Test
  public void or_singleOperand_returnsOperandUnwrapped() {
    // Single-operand or(c) must NOT wrap in SQLOrBlock — parser parity contract.
    // GqlMatchStatement.buildWhereClause depends on this unwrap behavior to avoid
    // shifting plan tree shape when a where clause has a single property.
    var c = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var result = b.or(c);
    assertSame(c, result);
    assertFalse(
        "single-operand or(c) must NOT wrap in SQLOrBlock", result instanceof SQLOrBlock);
  }

  @Test
  public void or_twoOperands_returnsSQLOrBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var result = b.or(c1, c2);
    assertTrue(result instanceof SQLOrBlock);
    assertEquals("a = 1 OR b = 2", render(result));
  }

  // ── not ──

  @Test
  public void not_setsSubAndNegateTrue() {
    var inner = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var result = b.not(inner);
    assertTrue(result instanceof SQLNotBlock);
    var not = (SQLNotBlock) result;
    assertSame(inner, not.getSub());
    assertTrue("negate must be true; the default of false is a silent pass-through",
        not.isNegate());
    assertEquals("NOT a = 1", render(result));
  }

  // ── wrap ──

  @Test
  public void wrap_producesSQLWhereClauseCarryingTheExpression() {
    var inner = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var clause = b.wrap(inner);
    assertTrue(clause instanceof SQLWhereClause);
    assertEquals("a = 1", render(clause));
  }

  // ── andOptional — null-tolerant AND merge ──

  @Test
  public void andOptional_emptyInput_returnsNull() {
    // No operands → no filter to write; callers translate null into "skip writing".
    assertNull(b.andOptional());
  }

  @Test
  public void andOptional_allNullOperands_returnsNull() {
    // (null, null) is functionally the same as the empty case — no-op merge.
    assertNull(b.andOptional(null, null));
  }

  @Test
  public void andOptional_singleNonNullOperand_returnsItUnwrapped() {
    // Mirrors and(c)'s parser-parity: a lone operand never gets wrapped in an SQLAndBlock.
    var c = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    assertSame(c, b.andOptional(c));
  }

  @Test
  public void andOptional_nullPlusNonNull_returnsTheNonNull() {
    // The "empty prior → write directly" rule from the recogniser merge contract: a null
    // contribution drops away, the surviving operand passes through unwrapped.
    var c = b.eq("x", MatchLiteralBuilder.toLiteral(1L));
    assertSame(c, b.andOptional(null, c));
    assertSame(c, b.andOptional(c, null));
  }

  @Test
  public void andOptional_twoNonNullOperands_producesSQLAndBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var result = b.andOptional(c1, c2);
    assertTrue(result instanceof SQLAndBlock);
    assertEquals("a = 1 AND b = 2", render(result));
  }

  @Test
  public void andOptional_threeOperandsWithMiddleNull_dropsNullKeepsSurvivors() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c3 = b.eq("c", MatchLiteralBuilder.toLiteral(3L));
    var result = b.andOptional(c1, null, c3);
    // Two effective operands → SQLAndBlock; the null is filtered out, not preserved as a
    // sub-block (a literal null sub-block would NPE downstream evaluation).
    assertTrue(result instanceof SQLAndBlock);
    assertEquals("a = 1 AND c = 3", render(result));
  }

  @Test
  public void andOptional_fourNonNullOperands_collapsesToSingleSQLAndBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var c3 = b.eq("c", MatchLiteralBuilder.toLiteral(3L));
    var c4 = b.eq("d", MatchLiteralBuilder.toLiteral(4L));
    var result = b.andOptional(c1, c2, c3, c4);
    assertTrue("multi-operand andOptional must produce a single SQLAndBlock, not nested",
        result instanceof SQLAndBlock);
    assertEquals("a = 1 AND b = 2 AND c = 3 AND d = 4", render(result));
  }

  // ── isNull — both left-side shapes ──

  @Test
  public void isNull_propertyIdentifier_producesIsNullCondition() {
    var expr = b.isNull("name");
    assertTrue("isNull(String) must produce SQLIsNullCondition",
        expr instanceof SQLIsNullCondition);
    assertEquals("name is null", render(expr));
  }

  @Test
  public void isNull_recordAttributeViaIsNullAttribute_producesIsNullCondition() {
    // hasNot("@class") path: record-attribute left-side flows through isNullAttribute.
    var expr = b.isNullAttribute("@class");
    assertTrue(expr instanceof SQLIsNullCondition);
    assertEquals("@class is null", render(expr));
  }

  @Test
  public void isNull_arbitraryExpression_isPassedThroughUnchanged() {
    // The SQLExpression overload accepts any pre-built expression — the predicate
    // adapter uses this for non-trivial left-sides built upstream.
    var inner = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier("custom"));
    var expr = b.isNull(inner);
    assertTrue(expr instanceof SQLIsNullCondition);
    assertSame(inner, ((SQLIsNullCondition) expr).getExpression());
    assertEquals("custom is null", render(expr));
  }

  @Test
  public void isNull_canBeNegatedToEmulateNotNull() {
    // Pins the "neq(non-null) ↦ or(isNull, op(NE))" predicate-adapter path: isNull is
    // composable with not(), or(), and the rest of the WHERE algebra.
    var expr = b.not(b.isNull("name"));
    assertTrue(expr instanceof SQLNotBlock);
    assertEquals("NOT name is null", render(expr));
  }

  // ── isDefined / isNotDefined (entity-presence, distinct from IS NULL) ──

  /**
   * {@code isDefined(field)} wraps the pre-existing {@link SQLIsDefinedCondition}
   * AST node — the operator's evaluator routes through
   * {@code SQLExpression.isDefinedFor}, which asks the record layer whether the
   * property exists (regardless of value). This is the entity-presence predicate
   * the Gremlin translator's {@code has(key)} mapping needs: TinkerPop's
   * {@code Property.isPresent()} returns true for null-valued YTDB properties,
   * which {@code IS NOT NULL} would not match (per PR #1038 review #6 +
   * andrii0lomakin's "do not unify" position).
   */
  @Test
  public void isDefined_producesSQLIsDefinedCondition() {
    var expr = b.isDefined("name");
    assertTrue("isDefined(String) must produce SQLIsDefinedCondition",
        expr instanceof SQLIsDefinedCondition);
    assertEquals("name is defined", render(expr));
  }

  /** Symmetric to {@link #isDefined_producesSQLIsDefinedCondition}. */
  @Test
  public void isNotDefined_producesSQLIsNotDefinedCondition() {
    var expr = b.isNotDefined("name");
    assertTrue("isNotDefined(String) must produce SQLIsNotDefinedCondition",
        expr instanceof SQLIsNotDefinedCondition);
    assertEquals("name is not defined", render(expr));
  }

  /**
   * The factory wires the property name through a fresh {@link SQLExpression} —
   * pin the round-trip so a regression that lost the expression child would
   * surface as a NullPointerException at render time rather than a silent
   * empty render.
   */
  @Test
  public void isDefined_expressionChildIsSetCorrectly() {
    var expr = b.isDefined("foo");
    assertNotNull(((SQLIsDefinedCondition) expr).getExpression());
  }

  @Test
  public void isNotDefined_expressionChildIsSetCorrectly() {
    var expr = b.isNotDefined("foo");
    assertNotNull(((SQLIsNotDefinedCondition) expr).getExpression());
  }

  // ── Combined: deeply nested AND/OR ──

  @Test
  public void deeplyNestedBooleanExpression_buildsCorrectAstShape() {
    var a = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c = b.eq("c", MatchLiteralBuilder.toLiteral(3L));
    var d = b.eq("d", MatchLiteralBuilder.toLiteral(4L));
    // Expected AST: AndBlock[a = 1, OrBlock[c = 3, d = 4]]. Pin the structure
    // directly — the rendered string `a = 1 AND c = 3 OR d = 4` does NOT carry
    // precedence parentheses and would re-parse as `(a = 1 AND c = 3) OR d = 4`
    // under SQL's standard AND-tighter-than-OR rule, so an assertion on the
    // rendered string cannot prove the structural intent. The runtime
    // evaluator dispatches off the AST, not the rendered form, so structural
    // pinning is what actually matters.
    var combined = b.and(a, b.or(c, d));
    assertTrue(combined instanceof SQLAndBlock);
    var andBlock = (SQLAndBlock) combined;
    assertEquals("AND has two children", 2, andBlock.getSubBlocks().size());
    assertEquals(
        "first child is `a = 1`", "a = 1", render(andBlock.getSubBlocks().get(0)));
    assertTrue(
        "second child is the OR sub-block — preserves operator-precedence intent",
        andBlock.getSubBlocks().get(1) instanceof SQLOrBlock);
    var orBlock = (SQLOrBlock) andBlock.getSubBlocks().get(1);
    assertEquals("OR has two children", 2, orBlock.getSubBlocks().size());
    assertEquals("c = 3", render(orBlock.getSubBlocks().get(0)));
    assertEquals("d = 4", render(orBlock.getSubBlocks().get(1)));
  }

  // ── helpers ──

  private static String render(SQLBooleanExpression expr) {
    var sb = new StringBuilder();
    expr.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  private static String render(SQLWhereClause clause) {
    var sb = new StringBuilder();
    clause.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static <T> T readField(Object owner, String fieldName, Class<T> type) throws Exception {
    Class<?> c = owner.getClass();
    while (c != null) {
      try {
        Field f = c.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (T) f.get(owner);
      } catch (NoSuchFieldException ignored) {
        c = c.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName + " on " + owner.getClass());
  }
}
