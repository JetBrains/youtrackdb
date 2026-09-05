package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Tests that an ORDER BY step reads a parameterized SKIP or LIMIT on every execution, recorded as
 * BG1000.
 *
 * <p>The ordering step used to store the resolved SKIP + LIMIT number, and it belongs to a
 * cacheable plan. Re-executing the same statement text with a larger parameter therefore reused
 * the bound of the FIRST execution and returned that execution's row count, which is a silent
 * wrong result rather than an error. Every test below executes one statement text more than once
 * with different parameter values.
 */
public class OrderByParameterizedBoundTest extends DbTestBase {

  /** Five people with unique, lexicographically ordered ids: a, b, c, d, e. */
  private void seedFivePeople() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING);
    session.begin();
    for (var id : new String[] {"a", "b", "c", "d", "e"}) {
      session.execute("CREATE VERTEX Person SET id = '" + id + "'").close();
    }
    session.commit();
  }

  private List<String> ids(String query, Map<String, Object> params, String column) {
    var result = new ArrayList<String>();
    try (var rs = session.query(query, params)) {
      rs.forEachRemaining(row -> result.add(String.valueOf((Object) row.getProperty(column))));
    }
    return result;
  }

  /**
   * The plain SELECT path. The same statement text runs with LIMIT 2 and then LIMIT 4, and the
   * second execution must return four rows. Before the fix it returned two, because the cached
   * ordering step still held the bound of the first execution.
   */
  @Test
  public void selectWithParameterizedLimitHonoursEachExecution() {
    seedFivePeople();
    var query = "SELECT id FROM Person ORDER BY id LIMIT :n";
    assertThat(ids(query, Map.of("n", 2), "id")).containsExactly("a", "b");
    assertThat(ids(query, Map.of("n", 4), "id"))
        .as("the second execution must read its own LIMIT, not the cached one")
        .containsExactly("a", "b", "c", "d");
    assertThat(ids(query, Map.of("n", 1), "id"))
        .as("shrinking the bound again must also take effect")
        .containsExactly("a");
    assertThat(ids(query, Map.of("n", 5), "id")).hasSize(5);
  }

  /**
   * The parameter on SKIP rather than on LIMIT. With SKIP 3 and LIMIT 2 the sort has to keep five
   * rows, so a bound frozen at the first execution's SKIP 0 dropped the tail the second execution
   * asked for and returned nothing.
   */
  @Test
  public void selectWithParameterizedSkipHonoursEachExecution() {
    seedFivePeople();
    var query = "SELECT id FROM Person ORDER BY id SKIP :s LIMIT 2";
    assertThat(ids(query, Map.of("s", 0), "id")).containsExactly("a", "b");
    assertThat(ids(query, Map.of("s", 3), "id"))
        .as("the second execution must read its own SKIP, not the cached one")
        .containsExactly("d", "e");
  }

  /**
   * The MATCH path, which builds its own ordering step. Same statement text, growing bound.
   */
  @Test
  public void matchWithParameterizedLimitHonoursEachExecution() {
    seedFivePeople();
    var query = "MATCH {as: p, class: Person} RETURN p.id ORDER BY p.id LIMIT :n";
    assertThat(ids(query, Map.of("n", 2), "p.id")).containsExactly("a", "b");
    assertThat(ids(query, Map.of("n", 4), "p.id"))
        .as("the cached MATCH plan must read its own LIMIT on the second execution")
        .containsExactly("a", "b", "c", "d");
  }

  /**
   * A descending order with a parameterized bound, to show the resolution is independent of
   * direction and returns the correct end of the sorted sequence each time.
   */
  @Test
  public void descendingOrderWithParameterizedLimitHonoursEachExecution() {
    seedFivePeople();
    var query = "SELECT id FROM Person ORDER BY id DESC LIMIT :n";
    assertThat(ids(query, Map.of("n", 1), "id")).containsExactly("e");
    assertThat(ids(query, Map.of("n", 3), "id")).containsExactly("e", "d", "c");
  }

  /**
   * AN OVERFLOWING BOUND FALLS BACK TO AN UNBOUNDED SORT rather than raising.
   *
   * <p>The sum of SKIP and LIMIT is the heap size. Two large values summed in {@code int}
   * wrapped to a negative number, which is neither absent nor zero nor above the element cap,
   * so it reached the priority queue and raised an argument error. The old constructor clamped
   * any negative bound away, and that guard was lost when the bound moved to per-execution
   * resolution. A bound past what a heap can hold means the same thing as no bound.
   *
   * <p>The query returns nothing, because the SKIP is past the end of the fixture. Returning
   * nothing is the correct answer; raising is not.
   */
  @Test
  public void anOverflowingSkipPlusLimitSortsUnboundedInsteadOfRaising() {
    seedFivePeople();
    assertThat(ids("SELECT id FROM Person ORDER BY id SKIP :s LIMIT :n",
        Map.of("s", 2_000_000_000, "n", 2_000_000_000), "id"))
        .as("a bound that overflows int must sort unbounded and skip past every row")
        .isEmpty();
  }

  /**
   * A constant bound must keep working exactly as before, so the per-execution resolution does
   * not regress the ordinary case.
   */
  @Test
  public void constantLimitStillBoundsTheSort() {
    seedFivePeople();
    var query = "SELECT id FROM Person ORDER BY id LIMIT 2";
    assertThat(ids(query, Map.of(), "id")).containsExactly("a", "b");
    assertThat(ids(query, Map.of(), "id")).containsExactly("a", "b");
  }
}
