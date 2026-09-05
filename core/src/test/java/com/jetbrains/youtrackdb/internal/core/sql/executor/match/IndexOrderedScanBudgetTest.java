package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests the RUNTIME SCAN BUDGET of {@link IndexOrderedEdgeStep}.
 *
 * <p>Every decision to run an ordered index scan rests on an estimated density, and a skewed
 * graph breaks the estimate: the reachable targets can sit entirely at the far end of the index,
 * so a scan the estimate priced at a hundred entries walks thousands before its first match. The
 * budget makes that survivable. The scan may spend what the fallback costs, which is the entry
 * count {@link IndexOrderedCostModel#entriesWorthTheLoadAlternative} returns (record-read cost
 * over {@link IndexOrderedCostModel#scanCostPerEntry} at the shipped constants). Past that it
 * abandons the scan, loads from the sources and lets the ORDER BY sort in memory.
 *
 * <p>The assertions read the RUNTIME PATH and the CONSUMED-ENTRY COUNT the step recorded, not
 * only the row set. A row-only test passes with the budget deleted, because the rows are
 * identical either way, and a path-only test passes with the budget checked too late to bound
 * anything: a membership filter hides the entries it drops, so one delivery attempt used to be
 * able to walk the whole index before the check fired.
 */
@Category(SequentialTest.class)
public class IndexOrderedScanBudgetTest extends DbTestBase {

  /** Reachable messages, which is also the record count the fallback would read. */
  private static final int REACHABLE = 20;

  /** Unreachable messages seeded ahead of them in ascending key order. */
  private static final int ORPHANS = 2000;

  /**
   * SKEWED fixture, sized against the budget arithmetic rather than by eye.
   *
   * <p>One author owns {@value #REACHABLE} messages dated in 2026, and {@value #ORPHANS}
   * unreachable messages fill 2025, so an ASCENDING scan meets every orphan before the first
   * reachable message while a DESCENDING scan meets the reachable ones at once.
   *
   * <p>Three conditions have to hold together, and the sizes are what make them hold. The
   * fallback reads 20 records, so the budget is
   * {@code entriesWorthTheLoadAlternative(20)} and the 2000 orphans overspend it. The index
   * holds 2020 entries, so under {@code LIMIT 1} the estimated scan is 101 entries, which the
   * admission rule accepts with room for the histogram skew clamp. And loading 20 records costs
   * less than a full orphan walk on the union path, so the cost model picks the scan rather than
   * the fallback. A shape the planner refuses would never reach the budget, and a shape whose
   * orphan block fits the budget would never bail out.
   */
  private void seedSkewed() {
    var message = session.createVertexClass("Message");
    message.createProperty("creationDate", PropertyType.STRING);
    message.getProperty("creationDate").createIndex(INDEX_TYPE.NOTUNIQUE);
    session.createVertexClass("Author");
    session.createEdgeClass("wrote");

    session.begin();
    session.execute("CREATE VERTEX Author SET name = 'author0'").close();
    for (var i = 0; i < ORPHANS; i++) {
      session.execute(
          "CREATE VERTEX Message SET creationDate = '2025-" + slot(i) + "', mid = 'orphan"
              + i + "'")
          .close();
    }
    for (var i = 0; i < REACHABLE; i++) {
      var mid = "m" + slot(i);
      // A distinct date per message, so the ordered result has no ties and the assertions below
      // can name an exact sequence.
      session.execute(
          "CREATE VERTEX Message SET creationDate = '2026-" + slot(i) + "', mid = '" + mid + "'")
          .close();
      session.execute(
          "CREATE EDGE wrote FROM (SELECT FROM Author WHERE name = 'author0')"
              + " TO (SELECT FROM Message WHERE mid = '" + mid + "')")
          .close();
    }
    session.commit();
  }

  /** Zero-padded so the string dates and ids sort in creation order. */
  private static String slot(int i) {
    return String.format("%04d", i);
  }

  private static String orderedQuery(String direction, int limit) {
    return "MATCH {class: Author, as: a, where: (name LIKE 'author%')}"
        + ".out('wrote'){class: Message, as: m}"
        + " RETURN a.name as an, m.mid as mid ORDER BY m.creationDate " + direction
        + " LIMIT " + limit;
  }

  @Nullable private static IndexOrderedEdgeStep findStep(List<ExecutionStep> steps) {
    for (var step : steps) {
      if (step instanceof IndexOrderedEdgeStep ordered) {
        return ordered;
      }
      var nested = findStep(step.getSubSteps());
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static IndexOrderedEdgeStep stepOf(ResultSet result) {
    var plan = result.getExecutionPlan();
    assertThat(plan).as("the query must have produced an execution plan").isNotNull();
    var step = findStep(plan.getSteps());
    assertThat(step)
        .as("the plan must hold an index-ordered step:\n" + plan.prettyPrint(0, 2))
        .isNotNull();
    return step;
  }

  private static List<String> drain(ResultSet result, String column) {
    var rows = new ArrayList<String>();
    while (result.hasNext()) {
      rows.add(String.valueOf((Object) result.next().getProperty(column)));
    }
    return rows;
  }

  /**
   * ASCENDING over the skewed fixture: the scan must cross 2000 orphans before its first
   * reachable message, and its budget is 1458 entries. It abandons itself, and the row is still
   * the correct earliest message, which is what shows the bail-out happened before any row was
   * emitted rather than part way through.
   */
  @Test
  public void ascendingScanBailsOutWhenItOverspendsItsBudget() {
    seedSkewed();
    try (var result = session.query(orderedQuery("ASC", 1))) {
      var rows = drain(result, "mid");
      assertThat(stepOf(result).getChosenRuntimePath())
          .as("the scan must abandon itself rather than cross the whole orphan block")
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT);
      assertThat(rows)
          .as("the fallback plus the in-memory sort still return the earliest message")
          .containsExactly("m" + slot(0));
    }
  }

  /**
   * THE BOUND ITSELF, measured on the scan's own terms. The consumed-entry count must stop at
   * the budget, not at the end of the index.
   *
   * <p>This is the assertion the earlier budget check could not satisfy. It tested the bound
   * once per DELIVERED row, and a membership filter delivers nothing while it walks a
   * non-matching block, so the first delivery attempt consumed the entire orphan block first. A
   * bound is only a bound if the scan stops itself, so the overshoot asserted here is a small
   * constant rather than the size of the block.
   */
  @Test
  public void consumedEntriesStopAtTheBudgetRatherThanAtTheEndOfTheIndex() {
    seedSkewed();
    try (var result = session.query(orderedQuery("ASC", 1))) {
      drain(result, "mid");
      var step = stepOf(result);

      assertThat(step.lastScanBudget())
          .as("the budget is the entry count 20 loadable records are worth")
          .isEqualTo(1458L);
      assertThat(step.lastScanConsumedEntries())
          .as("the scan must really have spent its budget, or the bail-out proves nothing")
          .isGreaterThanOrEqualTo(step.lastScanBudget());
      // The stop fires on the entry that breaks the bound, and each sub-stream of the scan must
      // consume one element to test it, so the overshoot is a handful of entries rather than the
      // 542 remaining orphans.
      assertThat(step.lastScanConsumedEntries())
          .as("and it must stop there rather than walking all " + ORPHANS + " orphans")
          .isLessThanOrEqualTo(step.lastScanBudget() + 8);
    }
  }

  /**
   * DESCENDING over the same fixture is the control. The reachable messages are the newest, so
   * the scan meets one immediately, spends almost none of its budget and keeps the index scan it
   * was planned with. Without this case the two tests above would pass on a step that always
   * bails out.
   */
  @Test
  public void descendingScanKeepsTheIndexScanAndSpendsAlmostNothing() {
    seedSkewed();
    try (var result = session.query(orderedQuery("DESC", 1))) {
      var rows = drain(result, "mid");
      var step = stepOf(result);

      assertThat(step.getChosenRuntimePath())
          .as("a scan that finds its row immediately must not bail out")
          .isNotEqualTo(IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT);
      assertThat(step.lastScanConsumedEntries())
          .as("it reaches the newest message within a few entries")
          .isLessThan(50L);
      assertThat(rows)
          .as("the newest message, which is the last one seeded")
          .containsExactly("m" + slot(REACHABLE - 1));
    }
  }

  /**
   * THE PRE-EMISSION BUFFER IS CAPPED by the configured maximum heap elements per operation.
   *
   * <p>With the cap equal to the row target, the accepted DESC scan still emits the newest
   * message under {@code PRE_SORTED}, so OrderBy must not materialise a heap. Setting the cap
   * below a LIMIT that the runtime cost model refuses (load-and-sort) would trip OrderBy's own
   * heap guard instead — that is a different path and not what this asserts.
   */
  @Test
  public void aTightHeapCapBoundsTheBufferWithoutChangingRows() {
    seedSkewed();
    var previous = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValue();
    GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(1);
    try {
      try (var result = session.query(orderedQuery("DESC", 1))) {
        assertThat(stepOf(result).getChosenRuntimePath())
            .as("DESC LIMIT 1 must keep the ordered scan (PRE_SORTED), not fall back to OrderBy")
            .isNotEqualTo(IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT)
            .isNotEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
        assertThat(drain(result, "mid"))
            .as("newest message under a one-row buffer")
            .containsExactly("m" + slot(REACHABLE - 1));
      }
    } finally {
      GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.setValue(previous);
    }
  }

  /**
   * The bail-out must not change the ROW SET, only the way it is produced. A large LIMIT is
   * refused by the plan-time gate (plain MATCH + OrderBy), so it is an independent reference
   * for the sequence; the bailed-out LIMIT 1 cut must be its first element.
   */
  @Test
  public void bailOutAgreesWithThePlanThatNeverScans() {
    seedSkewed();
    List<String> reference;
    try (var result = session.query(orderedQuery("ASC", 100))) {
      reference = drain(result, "mid");
      // Large LIMIT declines IndexOrdered entirely — no INDEX ORDERED step in the plan.
      assertThat(findStep(result.getExecutionPlan().getSteps()))
          .as("LIMIT 100 must stay on plain MATCH + OrderBy, not an index-ordered scan")
          .isNull();
    }
    assertThat(reference).as("every reachable message, none of the orphans").hasSize(REACHABLE);

    try (var result = session.query(orderedQuery("ASC", 1))) {
      assertThat(stepOf(result).getChosenRuntimePath())
          .as("LIMIT 1 over the skewed fixture must bail out of the ordered scan")
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT);
      assertThat(drain(result, "mid"))
          .as("the bailed-out cut is the first row of the reference sequence")
          .containsExactly(reference.getFirst());
    }
  }
}
