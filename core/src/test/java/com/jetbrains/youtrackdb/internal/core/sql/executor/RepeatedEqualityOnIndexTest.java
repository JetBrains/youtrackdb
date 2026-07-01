package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
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
}
