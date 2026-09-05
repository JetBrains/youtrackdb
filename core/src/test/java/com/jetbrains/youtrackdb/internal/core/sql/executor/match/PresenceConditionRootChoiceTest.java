package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * PIN for finding PF300, the MATCH root estimator's blindness to a presence condition.
 *
 * <p>{@code estimateRootEntries} gives an unfiltered alias {@code classCount + 1} and a filtered
 * alias its estimated selectivity capped at {@code classCount}, so ANY filter, however
 * unselective, wins the root slot by at least one. An {@code IS DEFINED} conjunct filters almost
 * nothing, yet it wins that slot. When the source and the target of a hop share one class, the
 * target therefore becomes the pattern root, the edge is scheduled in reverse, and no edge
 * targets the ordered alias any more, so the index-ordered traversal is never detected.
 *
 * <p>THIS BEHAVIOR IS PINNED, NOT FIXED. The finding is inert under the shipped
 * productive-order default, which emits no presence conjunct on an order key, so only the
 * portable opt-out and hand-written MATCH reach it. Charging the conjunct an honest selectivity
 * would move the root of every MATCH query that carries one, including hand-written ones, and
 * nothing available here can measure whether the new root is better. The pin exists so the
 * mechanism is documented and a later change to it is visible.
 *
 * <p>ROWS ARE THE SAME EITHER WAY, which the last test asserts. Only the plan differs.
 */
public class PresenceConditionRootChoiceTest extends DbTestBase {

  /**
   * Sixty persons in one class, each knowing the next three by id order. The index on
   * {@code Person.id} is large enough, and the LIMIT small enough, that the ordered plan is
   * admissible when nothing stops the planner from rooting at the source.
   */
  private void seedSameClassHop() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING);
    person.getProperty("id").createIndex(INDEX_TYPE.NOTUNIQUE);
    session.createEdgeClass("knows");

    session.begin();
    for (var i = 0; i < 60; i++) {
      session.execute("CREATE VERTEX Person SET id = 'p" + pad(i) + "'").close();
    }
    for (var i = 0; i < 60; i++) {
      for (var d = 1; d <= 3; d++) {
        session.execute(
            "CREATE EDGE knows FROM (SELECT FROM Person WHERE id = 'p" + pad(i) + "')"
                + " TO (SELECT FROM Person WHERE id = 'p" + pad((i + d) % 60) + "')")
            .close();
      }
    }
    session.commit();
  }

  private static String pad(int i) {
    return String.format("%02d", i);
  }

  private static final String WITHOUT_PRESENCE =
      "MATCH {class: Person, as: src}.out('knows'){class: Person, as: dst}"
          + " RETURN dst.id as did ORDER BY dst.id LIMIT 3";

  private static final String WITH_PRESENCE =
      "MATCH {class: Person, as: src}"
          + ".out('knows'){class: Person, as: dst, where: (id IS DEFINED)}"
          + " RETURN dst.id as did ORDER BY dst.id LIMIT 3";

  private String plan(String query) {
    try (var rs = session.query("EXPLAIN " + query)) {
      return String.valueOf((Object) rs.next().getProperty("executionPlanAsString"));
    }
  }

  private List<String> ids(String query) {
    var rows = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> rows.add(String.valueOf((Object) row.getProperty("did"))));
    }
    return rows;
  }

  /**
   * Control: with no conjunct on the target, both aliases score {@code classCount + 1}, the
   * source keeps the root slot, and the ordered index traversal is detected.
   */
  @Test
  public void sameClassHopWithoutAPresenceConditionKeepsTheOrderedPlan() {
    seedSameClassHop();
    assertThat(plan(WITHOUT_PRESENCE))
        .as("the source roots the pattern, so an edge targets the ordered alias")
        .contains("INDEX ORDERED MATCH");
  }

  /**
   * PF300: adding only {@code id IS DEFINED} to the target moves the root to the target and the
   * ordered plan disappears. The conjunct excludes no record of this fixture, so the estimator
   * has traded a plan for selectivity that does not exist.
   */
  @Test
  public void sameClassHopWithAPresenceConditionLosesTheOrderedPlan() {
    seedSameClassHop();
    assertThat(plan(WITH_PRESENCE))
        .as("PF300 PIN: an unselective presence conjunct wins the root and costs the plan")
        .doesNotContain("INDEX ORDERED MATCH");
  }

  /**
   * The two plans return the same rows, which is why PF300 is a plan-quality finding and not a
   * correctness one. Every person of this fixture carries an id, so the conjunct is satisfied by
   * all of them.
   */
  @Test
  public void bothPlansReturnTheSameRows() {
    seedSameClassHop();
    assertThat(ids(WITH_PRESENCE))
        .as("the presence conjunct changes the plan, never the rows")
        .isEqualTo(ids(WITHOUT_PRESENCE));
    assertThat(ids(WITHOUT_PRESENCE))
        .as("three lowest ids, each reached from three sources, so the cut sits inside p01")
        .containsExactly("p00", "p00", "p00");
  }
}
