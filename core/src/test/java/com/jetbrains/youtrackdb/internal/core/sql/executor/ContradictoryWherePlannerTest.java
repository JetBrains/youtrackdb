package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
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
   * Redundant equalities with the same literal must not be classified as unsatisfiable at plan
   * time (no {@link EmptyStep} short-circuit).
   */
  @Test
  public void duplicateIndexedEquality_sameLiteral_doesNotShortCircuit() {
    try (var rs =
        session.query(
            "SELECT FROM " + className + " WHERE indexed = 'v1' AND indexed = 'v1'")) {
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertFalse(
          "duplicate same-value equalities must not use EmptyStep",
          firstStep instanceof EmptyStep);
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
}
