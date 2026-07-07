package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for merging two conditions on the same indexed field into one index lookup.
 *
 * <p>{@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition#mergeUsingAnd}
 * used to store the whole {@code MergeResult} record as the merged condition's right operand, so
 * the index searched for a key equal to that record and matched nothing — a satisfiable query
 * silently returned zero rows. These tests pin the corrected behaviour for the cases that actually
 * merge: duplicate equalities and two bounds in the same direction.
 */
public class RepeatedEqualityOnIndexTest extends TestUtilsFixture {

  private String className;

  @Before
  public void setUpSchema() {
    var clazz = createClassInstance();
    className = clazz.getName();
    clazz.createProperty("n", PropertyType.INTEGER);
    clazz.createIndex(className + ".n", SchemaClass.INDEX_TYPE.NOTUNIQUE, "n");
    session.begin();
    for (var i = 0; i < 10; i++) {
      session.newInstance(className).setProperty("n", i);
    }
    session.commit();
  }

  private Set<Integer> queryValues(String where) {
    var seen = new HashSet<Integer>();
    try (var rs = session.query("SELECT FROM " + className + " WHERE " + where)) {
      // The merge must produce a real index lookup, not a full scan; otherwise a regression that
      // silently falls back to a scan would still return the right values and hide itself.
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected FetchFromIndexStep for '" + where + "', got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof FetchFromIndexStep);
      while (rs.hasNext()) {
        seen.add(rs.next().getProperty("n"));
      }
    }
    return seen;
  }

  /** Duplicate equality on an indexed field must return the matching row through the index. */
  @Test
  public void duplicateEquality_returnsMatchingRow() {
    assertEquals(Set.of(5), queryValues("n = 5 AND n = 5"));
  }

  /** Two lower bounds merge to the tighter one; both operand orders must give the same rows. */
  @Test
  public void lowerBounds_mergeToTighter() {
    assertEquals(new HashSet<>(List.of(5, 6, 7, 8, 9)), queryValues("n >= 2 AND n >= 5"));
    assertEquals(new HashSet<>(List.of(5, 6, 7, 8, 9)), queryValues("n >= 5 AND n >= 2"));
  }

  /** Two upper bounds merge to the tighter one; both operand orders must give the same rows. */
  @Test
  public void upperBounds_mergeToTighter() {
    assertEquals(new HashSet<>(List.of(0, 1, 2, 3, 4)), queryValues("n <= 4 AND n <= 7"));
    assertEquals(new HashSet<>(List.of(0, 1, 2, 3, 4)), queryValues("n <= 7 AND n <= 4"));
  }

  /**
   * Two lower bounds with different operators merge to the tighter one and take the merge result's
   * operator, not the first condition's ({@code n > 3 AND n >= 5} becomes {@code n >= 5}, not
   * {@code n > 5}). Pins {@code mergeUsingAnd} reading the operator from the merge result: keeping
   * the original {@code >} would wrongly drop {@code n = 5}. The same-operator bound tests above
   * never change the operator, so they cannot catch that half of the fix.
   */
  @Test
  public void lowerBounds_differentOperators_takeMergedOperator() {
    assertEquals(new HashSet<>(List.of(5, 6, 7, 8, 9)), queryValues("n > 3 AND n >= 5"));
  }

  /**
   * Two upper bounds with different operators merge to the tighter one and take the merge result's
   * operator ({@code n < 8 AND n <= 5} becomes {@code n <= 5}, not {@code n < 5}); keeping the
   * original {@code <} would wrongly drop {@code n = 5}.
   */
  @Test
  public void upperBounds_differentOperators_takeMergedOperator() {
    assertEquals(new HashSet<>(List.of(0, 1, 2, 3, 4, 5)), queryValues("n < 8 AND n <= 5"));
  }

  /**
   * A bound merged with a {@code !=} on the boundary changes the operator: {@code n >= 5 AND n != 5}
   * becomes {@code n > 5} and {@code n <= 4 AND n != 4} becomes {@code n < 4}. This is the {@code GE
   * + NE -> GT} (and {@code LE + NE -> LT}) case the merge fix's comment names as its motivating
   * example; the range-bound tests above never exercise the {@code !=} branch, so without this the
   * operator-from-merge-result half of the fix is only partly pinned.
   */
  @Test
  public void boundMergedWithNotEqualOnBoundary_takesMergedOperator() {
    assertEquals(new HashSet<>(List.of(6, 7, 8, 9)), queryValues("n >= 5 AND n != 5"));
    assertEquals(new HashSet<>(List.of(0, 1, 2, 3)), queryValues("n <= 4 AND n != 4"));
  }

  /**
   * Equal same-operator bounds merge to a single bound: {@code n <= 5 AND n <= 5} keeps {@code <= 5}
   * and {@code n >= 5 AND n >= 5} keeps {@code >= 5}. Exercises the {@code result == 0} branch of the
   * bound merge — the boundary where an off-by-one between {@code <=} and {@code <} would flip which
   * operand survives; the distinct-bound tests above never hit it.
   */
  @Test
  public void equalBounds_keepSingleBound() {
    assertEquals(new HashSet<>(List.of(0, 1, 2, 3, 4, 5)), queryValues("n <= 5 AND n <= 5"));
    assertEquals(new HashSet<>(List.of(5, 6, 7, 8, 9)), queryValues("n >= 5 AND n >= 5"));
  }

  /**
   * An empty range ({@code n >= 5 AND n <= 4}) does not merge — the lower and upper bounds are kept
   * as a range with no satisfying value — and must return zero rows rather than the wrong rows.
   * Pins that "returns nothing" here is the correct answer, not the silent-zero-rows symptom the
   * merge fix removed.
   */
  @Test
  public void emptyRange_returnsNoRows() {
    try (var rs = session.query("SELECT FROM " + className + " WHERE n >= 5 AND n <= 4")) {
      // The bounds stay a real index range scan, not a silent full-scan fallback.
      var firstStep = rs.getExecutionPlan().getSteps().getFirst();
      assertTrue(
          "expected FetchFromIndexStep for an empty range, got "
              + firstStep.getClass().getSimpleName(),
          firstStep instanceof FetchFromIndexStep);
      assertFalse("an empty range must return no rows", rs.hasNext());
    }
  }
}
