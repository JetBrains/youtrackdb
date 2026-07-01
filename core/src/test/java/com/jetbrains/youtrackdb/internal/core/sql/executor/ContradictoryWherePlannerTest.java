package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
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

    session.begin();
    for (var i = 0; i < 10; i++) {
      session.newInstance(className).setProperty("indexed", "v" + i);
    }
    session.commit();
  }

  /**
   * Contradictory literal equalities on an indexed field must emit {@link EmptyStep} and return
   * zero rows without scanning the class.
   */
  @Test
  public void contradictoryIndexedEqualities_shortCircuitsToEmptyStep() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = 'v1' AND indexed = 'v2'")) {
      var plan = rs.getExecutionPlan();
      var firstStep = plan.getSteps().getFirst();
      assertTrue(
          "expected EmptyStep for contradictory predicate, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse("contradictory predicate must return no rows", rs.hasNext());
    }
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
    try (var rs =
        session.query(
            "SELECT FROM "
                + className
                + " WHERE indexed = 'v1' AND indexed = 'v2' AND indexed = 'v3'")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected EmptyStep for three distinct equalities, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
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
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE 'v1' = indexed AND 'v2' = indexed")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected EmptyStep for left-literal contradiction, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
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
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = 'v1' AND indexed IS NULL")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected EmptyStep for '= literal AND IS NULL', got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof EmptyStep);
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Two {@code IS NULL} conditions on the same field are satisfiable (the field is simply null), so
   * the detector must not treat them as contradictory: no {@link EmptyStep} short-circuit.
   */
  @Test
  public void doubleIsNullSameField_isNotContradictory() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed IS NULL AND indexed IS NULL")) {
      assertFalse(
          "two IS NULL on the same field must not short-circuit to empty",
          rs.getExecutionPlan().getSteps().getFirst() instanceof EmptyStep);
    }
  }
}
