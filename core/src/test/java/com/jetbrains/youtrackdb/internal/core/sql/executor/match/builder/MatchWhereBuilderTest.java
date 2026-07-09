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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNotDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
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

  /**
   * {@link MatchWhereBuilder#eq} produces a {@link SQLBinaryCondition} with
   * {@link SQLEqualsOperator#INSTANCE} and the expected render output.
   */
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

  /**
   * {@link MatchWhereBuilder#op} passes through the supplied {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator}.
   */
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

  /**
   * {@link MatchWhereBuilder#in} produces a {@link SQLInCondition} with a literal collection
   * and a populated {@link SQLInOperator}.
   */
  @Test
  public void in_producesInConditionWithLiteralCollection() {
    var values = List.of(
        MatchLiteralBuilder.toLiteral(1L),
        MatchLiteralBuilder.toLiteral(2L),
        MatchLiteralBuilder.toLiteral(3L));
    var expr = b.in("status", values);

    assertTrue(expr instanceof SQLInCondition);
    var in = (SQLInCondition) expr;
    // Operator must be populated so SQLInCondition.supportsBasicCalculation() doesn't NPE.
    assertNotNull("SQLInCondition.operator must not be null", in.getOperator());
    assertTrue("operator must be a SQLInOperator instance",
        in.getOperator() instanceof SQLInOperator);
    assertEquals("status IN [1, 2, 3]", render(expr));
  }

  /**
   * {@link SQLInCondition#supportsBasicCalculation()} dereferences {@code operator}
   * without a null guard — a builder-built {@code IN} must populate it the same way
   * the parser does, or plan-time index inference NPEs.
   */
  @Test
  public void in_operatorPopulated_supportsBasicCalculationWithoutNpe() {
    var expr = b.in(
        "status",
        List.of(
            MatchLiteralBuilder.toLiteral(1L),
            MatchLiteralBuilder.toLiteral(2L)));
    assertTrue(((SQLInCondition) expr).supportsBasicCalculation());
  }

  /** An empty value list renders as {@code field IN []}. */
  @Test
  public void in_emptyValues_producesEmptyCollection() {
    var expr = b.in("status", List.of());
    assertTrue(expr instanceof SQLInCondition);
    assertEquals("status IN []", render(expr));
  }

  /** {@link MatchWhereBuilder#notIn} composes {@code NOT (field IN …)} via {@link SQLNotBlock}. */
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

  /** {@link MatchWhereBuilder#between} produces a {@link SQLBetweenCondition} with correct bounds. */
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

  /**
   * Full-string render assertion to catch a swap of operands (substring {@code "contains"}
   * would silently pass on {@code "needle" CONTAINSTEXT body}).
   */
  @Test
  public void containsText_producesSQLContainsTextCondition() {
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

  /** Zero-operand {@link MatchWhereBuilder#and} throws {@link IllegalStateException}. */
  @Test
  public void and_zeroOperands_throwsIllegalState() {
    assertThrows(IllegalStateException.class, () -> b.and());
  }

  /** Single-operand {@link MatchWhereBuilder#and} returns the operand unwrapped. */
  @Test
  public void and_singleOperand_returnsOperandUnwrapped() {
    var c = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var result = b.and(c);
    assertSame("single-operand and(c) must return c without wrapping", c, result);
  }

  /** Two-operand {@link MatchWhereBuilder#and} produces an {@link SQLAndBlock}. */
  @Test
  public void and_twoOperands_returnsSQLAndBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var result = b.and(c1, c2);
    assertTrue(result instanceof SQLAndBlock);
    assertEquals("a = 1 AND b = 2", render(result));
  }

  /** Three-operand {@link MatchWhereBuilder#and} produces a flat {@link SQLAndBlock}. */
  @Test
  public void and_threeOperands_returnsSQLAndBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var c3 = b.eq("c", MatchLiteralBuilder.toLiteral(3L));
    var result = b.and(c1, c2, c3);
    assertTrue(result instanceof SQLAndBlock);
    assertEquals("a = 1 AND b = 2 AND c = 3", render(result));
  }

  /** Zero-operand {@link MatchWhereBuilder#or} throws {@link IllegalStateException}. */
  @Test
  public void or_zeroOperands_throwsIllegalState() {
    assertThrows(IllegalStateException.class, () -> b.or());
  }

  /**
   * Single-operand {@link MatchWhereBuilder#or(SQLBooleanExpression...)} must NOT wrap in
   * {@link SQLOrBlock} — parser parity contract. {@code GqlMatchStatement.buildWhereClause}
   * depends on this unwrap behavior to avoid shifting plan tree shape when a where clause has a
   * single property.
   */
  @Test
  public void or_singleOperand_returnsOperandUnwrapped() {
    var c = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var result = b.or(c);
    assertSame(c, result);
    assertFalse(
        "single-operand or(c) must NOT wrap in SQLOrBlock", result instanceof SQLOrBlock);
  }

  /** Two-operand {@link MatchWhereBuilder#or} produces an {@link SQLOrBlock}. */
  @Test
  public void or_twoOperands_returnsSQLOrBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var result = b.or(c1, c2);
    assertTrue(result instanceof SQLOrBlock);
    assertEquals("a = 1 OR b = 2", render(result));
  }

  /** Three-operand {@link MatchWhereBuilder#or} produces a flat {@link SQLOrBlock}. */
  @Test
  public void or_threeOperands_returnsSQLOrBlock() {
    var a = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c = b.eq("c", MatchLiteralBuilder.toLiteral(3L));
    var d = b.eq("d", MatchLiteralBuilder.toLiteral(4L));
    var result = b.or(a, c, d);
    assertTrue(result instanceof SQLOrBlock);
    assertEquals("a = 1 OR c = 3 OR d = 4", render(result));
  }

  // ── not ──

  /**
   * {@link MatchWhereBuilder#not} wraps the inner expression in {@link SQLNotBlock} with
   * {@code negate=true}.
   */
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

  /** {@link MatchWhereBuilder#wrap} produces a {@link SQLWhereClause} carrying the expression. */
  @Test
  public void wrap_producesSQLWhereClauseCarryingTheExpression() {
    var inner = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var clause = b.wrap(inner);
    assertTrue(clause instanceof SQLWhereClause);
    assertEquals("a = 1", render(clause));
  }

  // ── andOptional — null-tolerant AND merge ──

  /** No operands → no filter to write; callers translate null into skip writing. */
  @Test
  public void andOptional_emptyInput_returnsNull() {
    assertNull(b.andOptional());
  }

  /** {@code (null, null)} is functionally the same as the empty case — no-op merge. */
  @Test
  public void andOptional_allNullOperands_returnsNull() {
    assertNull(b.andOptional(null, null));
  }

  /**
   * Mirrors {@link MatchWhereBuilder#and(SQLBooleanExpression...)} parser-parity: a lone operand
   * never gets wrapped in an {@link SQLAndBlock}.
   */
  @Test
  public void andOptional_singleNonNullOperand_returnsItUnwrapped() {
    var c = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    assertSame(c, b.andOptional(c));
  }

  /**
   * The empty-prior → write-directly rule from the recogniser merge contract: a null contribution
   * drops away, the surviving operand passes through unwrapped.
   */
  @Test
  public void andOptional_nullPlusNonNull_returnsTheNonNull() {
    var c = b.eq("x", MatchLiteralBuilder.toLiteral(1L));
    assertSame(c, b.andOptional(null, c));
    assertSame(c, b.andOptional(c, null));
  }

  /** Two non-null operands produce a flat {@link SQLAndBlock}. */
  @Test
  public void andOptional_twoNonNullOperands_producesSQLAndBlock() {
    var c1 = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c2 = b.eq("b", MatchLiteralBuilder.toLiteral(2L));
    var result = b.andOptional(c1, c2);
    assertTrue(result instanceof SQLAndBlock);
    assertEquals("a = 1 AND b = 2", render(result));
  }

  /**
   * Null operands are filtered out; two surviving operands produce a flat {@link SQLAndBlock}
   * without a null sub-block.
   */
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

  /** Four non-null operands collapse into a single flat {@link SQLAndBlock}. */
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

  /** {@link MatchWhereBuilder#isNull(String)} produces a {@link SQLIsNullCondition}. */
  @Test
  public void isNull_propertyIdentifier_producesIsNullCondition() {
    var expr = b.isNull("name");
    assertTrue("isNull(String) must produce SQLIsNullCondition",
        expr instanceof SQLIsNullCondition);
    assertEquals("name is null", render(expr));
  }

  /** Record-attribute left-side ({@code @class}) via {@link MatchWhereBuilder#isNullAttribute}. */
  @Test
  public void isNull_recordAttributeViaIsNullAttribute_producesIsNullCondition() {
    var expr = b.isNullAttribute("@class");
    assertTrue(expr instanceof SQLIsNullCondition);
    assertEquals("@class is null", render(expr));
  }

  /**
   * The {@link SQLExpression} overload accepts any pre-built expression — the predicate adapter
   * uses this for non-trivial left-sides built upstream.
   */
  @Test
  public void isNull_arbitraryExpression_isPassedThroughUnchanged() {
    var inner = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier("custom"));
    var expr = b.isNull(inner);
    assertTrue(expr instanceof SQLIsNullCondition);
    assertSame(inner, ((SQLIsNullCondition) expr).getExpression());
    assertEquals("custom is null", render(expr));
  }

  /**
   * Pins the {@code neq(non-null) ↦ or(isNull, op(NE))} predicate-adapter path: {@link
   * MatchWhereBuilder#isNull(String)} is composable with {@link MatchWhereBuilder#not} and
   * {@link MatchWhereBuilder#or}.
   */
  @Test
  public void isNull_canBeNegatedToEmulateNotNull() {
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
    var inner = ((SQLIsDefinedCondition) expr).getExpression();
    assertNotNull(inner);
    assertEquals("foo", render(inner));
    var nullInner = ((SQLIsNullCondition) b.isNull("foo")).getExpression();
    assertEquals(render(nullInner), render(inner));
  }

  /**
   * Symmetric to {@link #isDefined_expressionChildIsSetCorrectly} for
   * {@link MatchWhereBuilder#isNotDefined}.
   */
  @Test
  public void isNotDefined_expressionChildIsSetCorrectly() {
    var expr = b.isNotDefined("foo");
    var inner = ((SQLIsNotDefinedCondition) expr).getExpression();
    assertNotNull(inner);
    assertEquals("foo", render(inner));
  }

  /** Presence operators must stay distinct from value-layer null checks in render output. */
  @Test
  public void presencePredicates_renderDistinctFromNullChecks() {
    assertEquals("name is defined", render(b.isDefined("name")));
    assertEquals("name is not defined", render(b.isNotDefined("name")));
    assertEquals("name is null", render(b.isNull("name")));
  }

  // ── Combined: deeply nested AND/OR ──

  /**
   * Expected AST: {@code AndBlock[a = 1, OrBlock[c = 3, d = 4]]}. Pin the structure directly —
   * the rendered string {@code a = 1 AND c = 3 OR d = 4} does NOT carry precedence parentheses
   * and would re-parse as {@code (a = 1 AND c = 3) OR d = 4} under SQL's standard
   * AND-tighter-than-OR rule.
   */
  @Test
  public void deeplyNestedBooleanExpression_buildsCorrectAstShape() {
    var a = b.eq("a", MatchLiteralBuilder.toLiteral(1L));
    var c = b.eq("c", MatchLiteralBuilder.toLiteral(3L));
    var d = b.eq("d", MatchLiteralBuilder.toLiteral(4L));
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

  // ── classEquals — exact-class @class narrowing ──

  /**
   * {@link MatchWhereBuilder#classEquals} produces a {@link SQLBinaryCondition} with
   * {@link SQLEqualsOperator#INSTANCE} rendering the {@code @class} record attribute, the equals
   * operator, and the class name. Asserting on {@code @class} (not {@code class:}) pins the
   * exact-leaf-class semantics — the record's own class, so subclasses are excluded — which is the
   * whole point of the narrowing.
   */
  @Test
  public void classEquals_producesExactClassEqualityCondition() {
    var expr = b.classEquals("Person");

    assertTrue(expr instanceof SQLBinaryCondition);
    var bin = (SQLBinaryCondition) expr;
    assertSame("classEquals must use the EqualsOperator singleton", SQLEqualsOperator.INSTANCE,
        bin.getOperator());
    var rendered = render(expr);
    assertTrue("must be an exact @class equality predicate; was: " + rendered,
        rendered.contains("@class") && rendered.contains("=") && rendered.contains("Person"));
  }

  /**
   * {@link MatchWhereBuilder#classEqualsWhere} wraps the same condition in an {@link SQLWhereClause}
   * so it drops straight into the MATCH IR's {@code aliasFilters} map. The rendered clause carries
   * the {@code @class} predicate.
   */
  @Test
  public void classEqualsWhere_wrapsConditionInWhereClause() {
    var rendered = render(b.classEqualsWhere("Person"));

    assertTrue("the where clause must carry the @class predicate; was: " + rendered,
        rendered.contains("@class") && rendered.contains("Person"));
  }

  /**
   * A null class name throws {@link IllegalArgumentException}: a caller reaches classEquals only with
   * a concrete user-named class, so a null name is a caller bug that must fail loud rather than
   * produce a broken predicate.
   */
  @Test
  public void classEquals_nullName_throws() {
    var ex = assertThrows(IllegalArgumentException.class, () -> b.classEquals(null));
    assertTrue(ex.getMessage().contains("non-blank"));
  }

  /**
   * An empty class name throws {@link IllegalArgumentException} — the {@code isBlank()} arm of the
   * guard, distinct from the null arm above.
   */
  @Test
  public void classEquals_emptyName_throws() {
    assertThrows(IllegalArgumentException.class, () -> b.classEquals(""));
  }

  /**
   * A whitespace-only class name throws {@link IllegalArgumentException} — also the {@code isBlank()}
   * arm, since a blank {@code @class = ''} predicate would be silently wrong.
   */
  @Test
  public void classEquals_whitespaceName_throws() {
    assertThrows(IllegalArgumentException.class, () -> b.classEquals("   "));
  }

  // ── helpers ──

  /** Renders a {@link SQLBooleanExpression} to its SQL text form. */
  private static String render(SQLBooleanExpression expr) {
    var sb = new StringBuilder();
    expr.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  /** Renders a {@link SQLExpression} to its SQL text form. */
  private static String render(SQLExpression expr) {
    var sb = new StringBuilder();
    expr.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  /** Renders a {@link SQLWhereClause} to its SQL text form. */
  private static String render(SQLWhereClause clause) {
    var sb = new StringBuilder();
    clause.toString(new HashMap<>(), sb);
    return sb.toString();
  }
}
