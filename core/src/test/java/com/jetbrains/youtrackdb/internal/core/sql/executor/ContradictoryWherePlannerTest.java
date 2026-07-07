package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for plan-time detection of contradictory WHERE equalities on the same field.
 *
 * <p>{@code WHERE indexed = 'x' AND indexed = 'y'} must short-circuit to {@link EmptyStep}
 * instead of falling back to a full class scan when the predicate is provably empty.
 */
public class ContradictoryWherePlannerTest extends TestUtilsFixture {

  private String className;

  @Before
  public void setUpSchema() {
    var clazz = createClassInstance();
    className = clazz.getName();
    clazz.createProperty("indexed", PropertyType.STRING);
    clazz.createIndex(
        className + ".indexed", SchemaClass.INDEX_TYPE.NOTUNIQUE, "indexed");

    // Seed 10 rows (indexed = v0..v9) so a failed short-circuit would fall back to a scan that
    // returns rows, rather than a vacuously-empty class that would pass an EmptyStep assertion for
    // the wrong reason.
    session.begin();
    for (var i = 0; i < 10; i++) {
      session.newInstance(className).setProperty("indexed", "v" + i);
    }
    session.commit();
  }

  /**
   * Assert that {@code SELECT FROM <className> WHERE <where>} short-circuits to {@link EmptyStep}
   * and returns no rows. Factors out the plan-shape-plus-empty assertion repeated across the
   * contradiction cases.
   */
  private void assertShortCircuitsToEmpty(String where) {
    assertShortCircuitsToEmpty(className, where);
  }

  /** As {@link #assertShortCircuitsToEmpty(String)} but against an arbitrary class. */
  private void assertShortCircuitsToEmpty(String clazz, String where) {
    try (var rs = session.query("SELECT FROM " + clazz + " WHERE " + where)) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected EmptyStep for '" + where + "', got " + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse("'" + where + "' must return no rows", rs.hasNext());
    }
  }

  /**
   * Assert that {@code SELECT FROM <clazz> WHERE <where>} is satisfiable — it must not short-circuit
   * to {@link EmptyStep} — and returns exactly one row whose {@code prop} equals {@code expected}.
   * Used by the coercion carve-out cases, where a regression to raw {@code Objects.equals} would
   * wrongly emit {@link EmptyStep} and drop the row.
   */
  private void assertSatisfiableReturns(String clazz, String where, String prop, Object expected) {
    try (var rs = session.query("SELECT FROM " + clazz + " WHERE " + where)) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertFalse(
          "'" + where + "' is satisfiable and must not short-circuit, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertTrue("'" + where + "' must return the matching row", rs.hasNext());
      assertEquals(expected, rs.next().getProperty(prop));
      assertFalse("'" + where + "' must return exactly one row", rs.hasNext());
    }
  }

  /**
   * Contradictory literal equalities on an indexed field must emit {@link EmptyStep} and return
   * zero rows without scanning the class.
   */
  @Test
  public void contradictoryIndexedEqualities_shortCircuitsToEmptyStep() {
    assertShortCircuitsToEmpty("indexed = 'v1' AND indexed = 'v2'");
  }

  /**
   * A single indexed equality must still use the index lookup path.
   */
  @Test
  public void singleIndexedEquality_usesIndexLookup() {
    try (var rs = session.query("SELECT FROM " + className + " WHERE indexed = 'v1'")) {
      var plan = rs.getExecutionPlan();
      var firstStep = plan.getSteps().getFirst();
      assertTrue(
          "expected FetchFromIndexStep for singleton equality, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof FetchFromIndexStep);
      assertTrue(rs.hasNext());
      assertEquals("v1", rs.next().getProperty("indexed"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Redundant equalities with the same literal are satisfiable, so the detector must not flag them:
   * the plan keeps the index lookup and returns the single matching row rather than short-circuiting
   * to {@link EmptyStep}. Returning the row also depends on the same-field merge fix in
   * {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition#mergeUsingAnd}; see
   * {@link RepeatedEqualityOnIndexTest} for the broader merge cases.
   */
  @Test
  public void duplicateIndexedEquality_sameLiteral_usesIndexAndReturnsRow() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = 'v1' AND indexed = 'v1'")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "duplicate same-value equalities must keep the index lookup, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof FetchFromIndexStep);
      assertTrue(rs.hasNext());
      assertEquals("v1", rs.next().getProperty("indexed"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * OR with one unsatisfiable branch and one satisfiable branch must still return matches from the
   * satisfiable branch.
   */
  @Test
  public void orWithOneContradictoryBranch_stillReturnsMatches() {
    try (var rs =
        session.query(
            "SELECT FROM "
                + className
                + " WHERE (indexed = 'v1' AND indexed = 'v2') OR indexed = 'v0'")) {
      assertFalse(
          "OR with a satisfiable branch must not short-circuit to empty",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertTrue(rs.hasNext());
      assertEquals("v0", rs.next().getProperty("indexed"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Two equalities with the same numeric value but different literal types
   * ({@code num = 1 AND num = 1.0}) are satisfiable, because the runtime filter coerces numbers.
   * The planner must not treat them as contradictory: it must not short-circuit to
   * {@link EmptyStep} and must return the {@code num = 1} row. Guards against a plan-time comparison
   * that uses strict {@code Objects.equals} instead of the engine's coercing equality — that would
   * emit {@link EmptyStep} here and drop the row.
   *
   * <p>The field is left non-indexed so the query runs through a full scan plus filter, keeping the
   * assertion focused on the coercion fix in the detector. The indexed same-field merge path is
   * covered separately by {@link RepeatedEqualityOnIndexTest}.
   */
  @Test
  public void numericCoercion_sameValueDifferentType_isNotContradictory() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("num", PropertyType.INTEGER);
    session.begin();
    session.newInstance(cn).setProperty("num", 1);
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE num = 1 AND num = 1.0")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertFalse(
          "num = 1 AND num = 1.0 is satisfiable under numeric coercion, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertTrue("expected the num=1 record to be returned", rs.hasNext());
      assertEquals(1, (int) rs.next().getProperty("num"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Three distinct equality literals on the same field ({@code = 'v1' AND = 'v2' AND = 'v3'}) are
   * contradictory and must short-circuit to {@link EmptyStep}. Exercises the multi-value branch of
   * the detector (more than one comparison against the first value).
   */
  @Test
  public void threeDistinctValues_shortCircuitsToEmptyStep() {
    assertShortCircuitsToEmpty("indexed = 'v1' AND indexed = 'v2' AND indexed = 'v3'");
  }

  /**
   * Contradiction detection is not gated on the field being indexed: a contradictory predicate on a
   * non-indexed field must also short-circuit to {@link EmptyStep} instead of a full class scan.
   */
  @Test
  public void contradictionOnNonIndexedField_shortCircuitsToEmptyStep() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("plain", PropertyType.STRING);
    session.begin();
    session.newInstance(cn).setProperty("plain", "x");
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE plain = 'a' AND plain = 'b'")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected EmptyStep for contradiction on a non-indexed field, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Contradictory equalities supplied through bound named parameters must also short-circuit to
   * {@link EmptyStep}: parameters are known at plan time, so the detector resolves them the same way
   * as inline literals.
   */
  @Test
  public void contradictoryBoundParameters_shortCircuitsToEmptyStep() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = :a AND indexed = :b",
            Map.of("a", "v1", "b", "v2"))) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected EmptyStep for contradictory bound parameters, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
  }

  /**
   * {@code count(*)} over a contradictory predicate must still return exactly one row holding zero:
   * the short-circuit feeds an empty stream to the aggregate, which is the same input the old
   * full-scan-plus-filter path produced. Guards against the short-circuit swallowing the aggregate
   * row.
   */
  @Test
  public void countStarOverContradiction_returnsSingleZeroRow() {
    try (var rs =
        session.query(
            "SELECT count(*) FROM " + className + " WHERE indexed = 'v1' AND indexed = 'v2'")) {
      // The aggregate sits above the source, so EmptyStep is not the first step; assert it is
      // present in the chain so a regression that counted via a full scan + filter (same 0L result)
      // would not pass silently.
      assertTrue(
          "count(*) over a contradiction must short-circuit to EmptyStep",
          rs.getExecutionPlan().getSteps().stream().anyMatch(s -> s instanceof EmptyStep));
      assertTrue("count(*) with no matches must still return one row", rs.hasNext());
      assertEquals(0L, (Object) rs.next().getProperty("count(*)"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * The coercion carve-out must also hold when the field is indexed: {@code num = 1 AND num = 1.0}
   * on an indexed INTEGER field is satisfiable, so it must keep the index lookup and return the
   * {@code num = 1} row rather than short-circuiting to {@link EmptyStep}. Covers the intersection
   * of the coercion detector and the same-field index merge, which the non-indexed
   * {@link #numericCoercion_sameValueDifferentType_isNotContradictory} case does not exercise.
   */
  @Test
  public void indexedNumericCoercion_sameValueDifferentType_returnsRow() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("num", PropertyType.INTEGER);
    clazz.createIndex(cn + ".num", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");
    session.begin();
    session.newInstance(cn).setProperty("num", 1);
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE num = 1 AND num = 1.0")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "num = 1 AND num = 1.0 must keep the index lookup, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof FetchFromIndexStep);
      assertTrue("expected the num=1 record to be returned", rs.hasNext());
      assertEquals(1, (int) rs.next().getProperty("num"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * An equality against the {@code null} literal is always false ({@code = null} matches nothing at
   * runtime, {@code IS NULL} is the null test), so a block containing it is unsatisfiable. Both
   * {@code indexed = 'v1' AND indexed = null} and a lone {@code indexed = null} must short-circuit
   * to {@link EmptyStep} and return no rows.
   */
  @Test
  public void nullLiteralEquality_shortCircuitsToEmptyStep() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = 'v1' AND indexed = null")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected EmptyStep for equality against null, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
    try (var rs = session.query("SELECT FROM " + className + " WHERE indexed = null")) {
      assertTrue(
          "lone equality against null must short-circuit to EmptyStep",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
  }

  /**
   * A target class that does not resolve must error the same way whatever the WHERE looks like. A
   * provably-empty predicate ({@code field = null}) must not turn the class-not-found error into a
   * silent empty result. Before the {@code targetClass != null} guard in
   * {@link SelectExecutionPlanner}, {@code SELECT FROM <missing> WHERE f = null} short-circuited to
   * {@link EmptyStep} and returned zero rows, while the same query with no WHERE threw. This pins
   * the throw for both shapes so a typo'd class name never hides behind an empty predicate.
   */
  @Test
  public void unsatisfiableWhereOnMissingClass_throwsClassNotFound() {
    var missingClass = "NoSuchClassForContradictionTest";
    // Baseline: a missing class with no WHERE throws "Class not found".
    var noWhere =
        assertThrows(
            CommandExecutionException.class,
            () -> {
              try (var rs = session.query("SELECT FROM " + missingClass)) {
                rs.hasNext();
              }
            });
    assertTrue(
        "expected a class-not-found error, got: " + noWhere.getMessage(),
        noWhere.getMessage().contains("Class not found"));
    // Regression: the contradictory `= null` predicate must throw the same error, not swallow it
    // into an empty result via EmptyStep.
    var contradictoryWhere =
        assertThrows(
            CommandExecutionException.class,
            () -> {
              try (var rs =
                  session.query("SELECT FROM " + missingClass + " WHERE indexed = null")) {
                rs.hasNext();
              }
            });
    assertTrue(
        "expected a class-not-found error, got: " + contradictoryWhere.getMessage(),
        contradictoryWhere.getMessage().contains("Class not found"));
  }

  /**
   * A block emptied only by a {@code = null} equality must not empty the whole WHERE when another
   * OR branch is satisfiable: {@code indexed = null OR indexed = 'v0'} must still return {@code v0}.
   */
  @Test
  public void nullEqualityInOneBranch_stillReturnsOtherBranch() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = null OR indexed = 'v0'")) {
      assertFalse(
          "OR with a satisfiable branch must not short-circuit to empty",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertTrue(rs.hasNext());
      assertEquals("v0", rs.next().getProperty("indexed"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * The property may sit on the left of the literal: {@code 'v1' = indexed AND 'v2' = indexed} is
   * contradictory and must short-circuit to {@link EmptyStep}, the same as the right-hand form.
   */
  @Test
  public void leftLiteralContradiction_shortCircuitsToEmptyStep() {
    assertShortCircuitsToEmpty("'v1' = indexed AND 'v2' = indexed");
  }

  /**
   * Left-literal equalities with the same value are satisfiable and must not be flagged: the query
   * must not short-circuit to {@link EmptyStep} and must return the matching row. Guards the
   * left-hand detection path against over-flagging.
   */
  @Test
  public void leftLiteralSameValue_isNotContradictory() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE 'v1' = indexed AND 'v1' = indexed")) {
      assertFalse(
          "duplicate left-literal equalities must not short-circuit to empty",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertTrue(rs.hasNext());
      assertEquals("v1", rs.next().getProperty("indexed"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * A non-null equality and an {@code IS NULL} on the same field cannot both hold — the field would
   * have to be a value and null at once — so the predicate must short-circuit to {@link EmptyStep}.
   */
  @Test
  public void equalityAndIsNullSameField_shortCircuitsToEmptyStep() {
    assertShortCircuitsToEmpty("indexed = 'v1' AND indexed IS NULL");
  }

  /**
   * The IS NULL / equality cross-check must also fire when the equality's value is a parameter bound
   * to {@code null}. {@code indexed IS NULL AND indexed = :p} with {@code :p = null} records a
   * {@code field = null} equality in the value map, which combined with {@code IS NULL} still cannot
   * match: IS NULL forces the field null, and {@code = null} matches nothing. This covers the
   * null-parameter arm of the cross-check that the inline {@code = null} literal
   * ({@link #equalityAndIsNullSameField_shortCircuitsToEmptyStep}) never reaches, because the
   * literal form short-circuits earlier via {@code isEqualityWithNullLiteral}, before the value map
   * is built.
   */
  @Test
  public void isNullPlusNullBoundParameter_shortCircuitsToEmptyStep() {
    var params = new HashMap<String, Object>();
    params.put("p", null);
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed IS NULL AND indexed = :p", params)) {
      assertTrue(
          "IS NULL plus a null-bound parameter on the same field must short-circuit to EmptyStep",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Two {@code IS NULL} conditions on the same field are satisfiable — the field is simply null —
   * so the detector must not treat them as contradictory. With a null-valued row present the plan
   * must stay non-empty and return that row, proving the "satisfiable" claim against real data
   * rather than only at plan-shape level.
   */
  @Test
  public void doubleIsNullSameField_returnsNullRow() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("p", PropertyType.STRING);
    session.begin();
    session.newInstance(cn).setProperty("p", "x");
    session.newInstance(cn); // second row leaves p null
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE p IS NULL AND p IS NULL")) {
      assertFalse(
          "two IS NULL on the same field must not short-circuit to empty",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertTrue("the null-valued row must be returned", rs.hasNext());
      assertNull("IS NULL must match the row whose property is null", rs.next().getProperty("p"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * {@code field = null} is always false even when a null-valued row exists: {@code = null} matches
   * nothing, {@code IS NULL} is the null test. With one null row and one valued row present,
   * {@code p = null} must short-circuit to {@link EmptyStep} and return nothing, while
   * {@code p IS NULL} returns the null row. Proves the short-circuit matches runtime semantics on
   * real null data, not only the plan shape.
   */
  @Test
  public void equalityAgainstNull_isEmptyEvenWhenNullRowExists() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("p", PropertyType.STRING);
    session.begin();
    session.newInstance(cn).setProperty("p", "x");
    session.newInstance(cn); // null-valued row
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE p = null")) {
      assertTrue(
          "p = null must short-circuit to EmptyStep",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertFalse("p = null must match no row, even the null one", rs.hasNext());
    }
    try (var rs = session.query("SELECT FROM " + cn + " WHERE p IS NULL")) {
      assertTrue("p IS NULL must match the null-valued row", rs.hasNext());
      assertNull(rs.next().getProperty("p"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Two equalities against numeric string literals with the same value but different text
   * ({@code num = '5' AND num = '05'}) are satisfiable on an INTEGER property: the runtime coerces
   * each literal to the field type, so both become {@code 5}. The detector must coerce to the
   * declared type before comparing; a raw string comparison ('5' vs '05') would wrongly short
   * circuit to {@link EmptyStep} and drop the matching row.
   */
  @Test
  public void numericStringLiterals_sameCoercedValue_isNotContradictory() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("num", PropertyType.INTEGER);
    session.begin();
    session.newInstance(cn).setProperty("num", 5);
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE num = '5' AND num = '05'")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertFalse(
          "num = '5' AND num = '05' coerces to 5 = 5 and is satisfiable, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertTrue("expected the num=5 record to be returned", rs.hasNext());
      assertEquals(5, (int) rs.next().getProperty("num"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * The contradiction check needs the declared type to reproduce the runtime coercion. On a
   * schemaless field the type is unknown, so {@code n = '5' AND n = '05'} must not be flagged
   * contradictory: the stored INTEGER {@code 5} matches both literals, and the row must be returned
   * rather than dropped by a raw string comparison.
   */
  @Test
  public void numericStringLiterals_schemalessField_isNotContradictory() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    session.begin();
    session.newInstance(cn).setProperty("n", 5); // no declared property: schemaless
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE n = '5' AND n = '05'")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertFalse(
          "a schemaless field must not be flagged contradictory, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertTrue("expected the schemaless n=5 record to be returned", rs.hasNext());
      assertEquals(5, (int) rs.next().getProperty("n"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * On a case-insensitive (ci) STRING property the runtime filter lowercases both operands before
   * comparing, so {@code name = 'A' AND name = 'a'} both reduce to 'a' and match the stored row —
   * the predicate is satisfiable. The detector must apply the property's collate the same way;
   * without it 'A' and 'a' look distinct and the block is wrongly emptied, silently dropping the
   * row. Both operand orders are covered on a non-indexed field so the query runs through the
   * filter.
   */
  @Test
  public void caseInsensitiveCollation_sameValueDifferentCase_isNotContradictory() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.begin();
    session.newInstance(cn).setProperty("name", "a");
    session.commit();

    assertSatisfiableReturns(cn, "name = 'A' AND name = 'a'", "name", "a");
    assertSatisfiableReturns(cn, "'A' = name AND 'a' = name", "name", "a");
  }

  /**
   * The collate carve-out must also hold when the ci property is indexed: {@code name = 'A' AND
   * name = 'a'} is satisfiable — the values are equal under the collate — and must keep the index
   * lookup. The same-field merge
   * ({@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition#mergeUsingAnd})
   * applies the property collate to the two equality operands, so both reduce to {@code 'a'} and
   * fold into a single index key. Asserts {@link FetchFromIndexStep} so a regression that dropped
   * the collated merge back to a full scan, while still returning the row, would fail the test.
   */
  @Test
  public void caseInsensitiveCollation_indexed_sameValueDifferentCase_returnsRow() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("name", PropertyType.STRING).setCollate("ci");
    clazz.createIndex(cn + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    session.begin();
    session.newInstance(cn).setProperty("name", "a");
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE name = 'A' AND name = 'a'")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "ci-collated 'A'/'a' must keep the index lookup, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof FetchFromIndexStep);
      assertTrue("expected the collated row to be returned", rs.hasNext());
      assertEquals("a", rs.next().getProperty("name"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Genuinely distinct values on a ci property are still contradictory: {@code name = 'a' AND
   * name = 'b'} lowercase to 'a' and 'b', which differ, so the block is provably empty and must
   * short-circuit. Confirms the collate fix did not disable detection for real contradictions.
   */
  @Test
  public void caseInsensitiveCollation_distinctValues_shortCircuitsToEmptyStep() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.begin();
    session.newInstance(cn).setProperty("name", "a");
    session.commit();

    assertShortCircuitsToEmpty(cn, "name = 'a' AND name = 'b'");
    // Case variants of two distinct values remain distinct after lowercasing.
    assertShortCircuitsToEmpty(cn, "name = 'A' AND name = 'B'");
  }

  /**
   * BOOLEAN coercion: {@code flag = true AND flag = false} is contradictory (EmptyStep), while
   * {@code flag = true AND flag = 'true'} is satisfiable because the string literal coerces to
   * {@code Boolean.TRUE}. Guards the non-INTEGER coercion branch the INTEGER cases never reach.
   */
  @Test
  public void booleanCoercion_contradictionShortCircuits_stringFormIsSatisfiable() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("flag", PropertyType.BOOLEAN);
    session.begin();
    session.newInstance(cn).setProperty("flag", true);
    session.commit();

    assertShortCircuitsToEmpty(cn, "flag = true AND flag = false");
    assertSatisfiableReturns(cn, "flag = true AND flag = 'true'", "flag", true);
  }

  /**
   * LONG coercion: distinct values beyond the int range short-circuit (also confirming the check
   * does not truncate to int), while an Integer/String literal pair for the same value
   * ({@code n = 5 AND n = '5'}) stays satisfiable.
   */
  @Test
  public void longCoercion_distinctShortCircuits_mixedLiteralSatisfiable() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("n", PropertyType.LONG);
    session.begin();
    session.newInstance(cn).setProperty("n", 5L);
    session.commit();

    assertShortCircuitsToEmpty(cn, "n = 3000000000 AND n = 3000000001");
    assertSatisfiableReturns(cn, "n = 5 AND n = '5'", "n", 5L);
  }

  /**
   * DOUBLE coercion: {@code d = 5.0 AND d = 6.0} is contradictory, while {@code d = 5 AND d = 5.0}
   * coerces the integer literal to 5.0 and stays satisfiable.
   */
  @Test
  public void doubleCoercion_distinctShortCircuits_intFormSatisfiable() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("d", PropertyType.DOUBLE);
    session.begin();
    session.newInstance(cn).setProperty("d", 5.0);
    session.commit();

    assertShortCircuitsToEmpty(cn, "d = 5.0 AND d = 6.0");
    assertSatisfiableReturns(cn, "d = 5 AND d = 5.0", "d", 5.0);
  }

  /**
   * DECIMAL coercion: {@code dec = 5 AND dec = 6} is contradictory, while {@code dec = 5 AND
   * dec = '5'} coerces both to the same BigDecimal and stays satisfiable.
   */
  @Test
  public void decimalCoercion_distinctShortCircuits_stringFormSatisfiable() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("dec", PropertyType.DECIMAL);
    session.begin();
    session.newInstance(cn).setProperty("dec", new BigDecimal("5"));
    session.commit();

    assertShortCircuitsToEmpty(cn, "dec = 5 AND dec = 6");
    assertSatisfiableReturns(cn, "dec = 5 AND dec = '5'", "dec", new BigDecimal("5"));
  }

  /**
   * DATETIME coercion via bound parameters: two distinct instants short-circuit, while the same
   * instant supplied twice (as separate Date objects) stays satisfiable. Uses parameters so the
   * detector resolves real Date values, exercising the DATE/DATETIME branch of the coercion path
   * without depending on date-literal syntax.
   */
  @Test
  public void dateTimeCoercion_distinctShortCircuits_sameInstantSatisfiable() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("ts", PropertyType.DATETIME);
    var instant = new Date(1_600_000_000_000L);
    session.begin();
    session.newInstance(cn).setProperty("ts", instant);
    session.commit();

    try (var rs =
        session.query(
            "SELECT FROM " + cn + " WHERE ts = :a AND ts = :b",
            Map.of("a", instant, "b", new Date(1_700_000_000_000L)))) {
      assertTrue(
          "distinct instants must short-circuit to EmptyStep",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
    try (var rs =
        session.query(
            "SELECT FROM " + cn + " WHERE ts = :a AND ts = :b",
            Map.of("a", instant, "b", new Date(1_600_000_000_000L)))) {
      assertFalse(
          "the same instant must not short-circuit",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertTrue("the matching row must be returned", rs.hasNext());
    }
  }

  /**
   * GROUP BY over a contradiction returns zero groups — the opposite cardinality from bare
   * {@code count(*)}, which returns one zero row. The empty stream from the short-circuit must feed
   * the grouping so no group is emitted.
   */
  @Test
  public void groupByOverContradiction_returnsNoGroups() {
    try (var rs =
        session.query(
            "SELECT indexed, count(*) FROM " + className
                + " WHERE indexed = 'v1' AND indexed = 'v2' GROUP BY indexed")) {
      assertTrue(
          "GROUP BY over a contradiction must short-circuit to EmptyStep",
          rs.getExecutionPlan().getSteps().stream().anyMatch(s -> s instanceof EmptyStep));
      assertFalse("GROUP BY over an empty stream must emit no groups", rs.hasNext());
    }
  }

  /** DISTINCT over a contradiction returns no rows. */
  @Test
  public void distinctOverContradiction_returnsNoRows() {
    try (var rs =
        session.query(
            "SELECT DISTINCT indexed FROM " + className
                + " WHERE indexed = 'v1' AND indexed = 'v2'")) {
      assertTrue(
          rs.getExecutionPlan().getSteps().stream().anyMatch(s -> s instanceof EmptyStep));
      assertFalse(rs.hasNext());
    }
  }

  /** ORDER BY with LIMIT/SKIP composes with the empty stream without error and yields no rows. */
  @Test
  public void orderByLimitOverContradiction_returnsNoRows() {
    try (var rs =
        session.query(
            "SELECT FROM " + className
                + " WHERE indexed = 'v1' AND indexed = 'v2' ORDER BY indexed LIMIT 5 SKIP 3")) {
      assertTrue(
          rs.getExecutionPlan().getSteps().stream().anyMatch(s -> s instanceof EmptyStep));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * A constant-fold value operand that throws when evaluated ({@code num = 1/0}) must not abort
   * planning. On an empty class the runtime filter never evaluates the operand, so before the
   * short-circuit the query returned no rows; the plan-time detector must preserve that by catching
   * the evaluation failure and falling through, rather than throwing {@code ArithmeticException}
   * out of the planner. Both the contradiction-candidate form and the single-equality form (which
   * still evaluates the operand while populating the value map) are covered.
   */
  @Test
  public void throwingEarlyCalcOperand_doesNotAbortPlanning() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("num", PropertyType.INTEGER);
    // No rows seeded: mirrors the empty / no-row-scanned case that surfaced the regression.
    try (var rs = session.query("SELECT FROM " + cn + " WHERE num = 1/0 AND num = 2/0")) {
      assertFalse("empty class must yield no rows and no exception", rs.hasNext());
    }
    try (var rs = session.query("SELECT FROM " + cn + " WHERE num = 1/0")) {
      assertFalse("empty class must yield no rows and no exception", rs.hasNext());
    }
  }

  /**
   * Two non-null equality literals that cannot be coerced to the declared type
   * ({@code num = 'abc' AND num = 'def'} on an INTEGER field) make the type-aware comparison in
   * {@code literalsProvablyDistinct} throw while coercing. That failure must be swallowed and read
   * as "not provably distinct", so the block falls through to normal planning instead of aborting
   * the plan or short-circuiting to {@link EmptyStep}. Exercises the coercion-throws catch that the
   * satisfiable coercion cases, where coercion succeeds, never reach. The class is empty, so the
   * runtime filter never evaluates the uncoercible operands either: the query plans and runs to zero
   * rows without throwing.
   */
  @Test
  public void uncoercibleEqualityLiterals_coercionThrows_fallsThroughWithoutShortCircuit() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("num", PropertyType.INTEGER); // non-indexed: full scan + filter
    // No rows seeded: the empty scan keeps the runtime filter from evaluating 'abc' / 'def' too.
    try (var rs = session.query("SELECT FROM " + cn + " WHERE num = 'abc' AND num = 'def'")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertFalse(
          "an uncoercible-literal contradiction candidate must not short-circuit, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse("empty class must yield no rows and no exception", rs.hasNext());
    }
  }

  /**
   * Operands the detector cannot resolve to a plan-time literal must fall through to normal
   * planning, not be read as contradictions. {@code indexed = indexed} (a field on both sides) is
   * always true, so combined with {@code indexed = 'v2'} the query must return the v2 row rather
   * than short-circuiting; {@code indexed = other} (another identifier) must likewise not
   * short-circuit.
   */
  @Test
  public void nonResolvableOperand_fallsThroughWithoutShortCircuit() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = indexed AND indexed = 'v2'")) {
      assertFalse(
          "field = field must not be read as a contradiction",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertTrue(rs.hasNext());
      assertEquals("v2", rs.next().getProperty("indexed"));
      assertFalse(rs.hasNext());
    }
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = other AND indexed = 'v2'")) {
      assertFalse(
          "field = otherField must not be read as a contradiction",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
    }
  }

  /**
   * The detector groups equality candidates by property, so equalities on two different fields never
   * contradict each other: {@code a = 'x' AND b = 'y'} is satisfiable and must plan normally and
   * return the matching row. Guards against a future change that mis-keys the value map and
   * cross-fires across properties.
   */
  @Test
  public void distinctFieldsEachSatisfiable_doesNotShortCircuit() {
    var clazz = createClassInstance();
    var cn = clazz.getName();
    clazz.createProperty("a", PropertyType.STRING);
    clazz.createProperty("b", PropertyType.STRING);
    session.begin();
    var e = session.newInstance(cn);
    e.setProperty("a", "x");
    e.setProperty("b", "y");
    session.commit();

    try (var rs = session.query("SELECT FROM " + cn + " WHERE a = 'x' AND b = 'y'")) {
      assertFalse(
          "equalities on distinct fields must not short-circuit to empty",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
      assertTrue("the matching row must be returned", rs.hasNext());
      var row = rs.next();
      assertEquals("x", row.getProperty("a"));
      assertEquals("y", row.getProperty("b"));
      assertFalse(rs.hasNext());
    }
  }
}
