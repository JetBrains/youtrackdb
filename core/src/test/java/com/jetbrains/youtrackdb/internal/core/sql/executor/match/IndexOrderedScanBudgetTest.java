package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.GlobalConfigurationScope;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ScanFactorFunctionScope;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
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

  private static String orderedMutationQuery(
      String sourceRid, boolean downstream, String functionName) {
    return "MATCH {class: Author, as: a, where: (@rid = " + sourceRid
        + " AND " + functionName + "() = true)}"
        + ".out('wrote'){class: Message, as: m}"
        + (downstream ? ".out('hasReply'){class: Reply, as: r}" : "")
        + " RETURN m.mid as mid ORDER BY m.creationDate ASC LIMIT 2";
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

  /** Invalid scan CPU factors select the ordinary load-and-sort plan and exact first row. */
  @Test
  public void invalidScanCpuFactorsUseOrdinaryLoadAndSortPlan() {
    seedSkewed();
    var configuration = GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR;
    try (var ignored = GlobalConfigurationScope.capture(configuration)) {
      for (var factor : List.of(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
        configuration.setValue(factor);
        try (var result = session.query(orderedQuery("ASC", 1))) {
          assertThat(drain(result, "mid")).containsExactly("m" + slot(0));
          assertOrdinarySortPlan(result, "invalid scan CPU factor " + factor);
        }
      }
    }
  }

  /**
   * A factor invalidated after planning uses unsorted loading without downstream work and local
   * sorting before a downstream edge. Both paths avoid opening an index cursor.
   */
  @Test
  public void singleSourcePostPlanInvalidFactorUsesRuntimeFallbacks() {
    seedSkewed();
    session.createVertexClass("Reply");
    session.createEdgeClass("hasReply");
    session.begin();
    for (var i = 0; i < REACHABLE; i++) {
      session.execute("CREATE VERTEX Reply SET mid = 'm" + slot(i) + "'").close();
      session.execute(
          "CREATE EDGE hasReply FROM (SELECT FROM Message WHERE mid = 'm" + slot(i)
              + "') TO (SELECT FROM Reply WHERE mid = 'm" + slot(i) + "')")
          .close();
    }
    session.commit();

    String sourceRid;
    try (var author = session.query("SELECT FROM Author WHERE name = 'author0'")) {
      sourceRid = author.next().getIdentity().toString();
    }

    var configuration = GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR;
    var previous = configuration.getValue();
    var previouslyChanged = configuration.isChanged();
    String functionName;
    try (var function = new ScanFactorFunctionScope(Double.NaN);
        var ignored = GlobalConfigurationScope.set(configuration, 1.0)) {
      functionName = function.name();
      try (var result = session.query(orderedMutationQuery(sourceRid, false, functionName))) {
        var step = stepOf(result);
        assertThat(configuration.getValueAsDouble()).isNaN();
        assertThat(drain(result, "mid"))
            .containsExactly("m" + slot(0), "m" + slot(1));
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
        assertThat(step.lastScanConsumedEntries()).isEqualTo(-1L);
      }

      configuration.setValue(1.0);
      try (var result = session.query(orderedMutationQuery(sourceRid, true, functionName))) {
        var step = stepOf(result);
        assertThat(configuration.getValueAsDouble()).isNaN();
        assertThat(drain(result, "mid"))
            .containsExactly("m" + slot(0), "m" + slot(1));
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
        assertThat(step.lastScanConsumedEntries()).isEqualTo(-1L);
      }
    }
    assertThat(configuration.isChanged()).isEqualTo(previouslyChanged);
    assertThat((Object) configuration.getValue()).isEqualTo(previous);
    assertThat(SQLEngine.getFunctionOrNull(session, functionName)).isNull();
  }

  /** A normal positive factor keeps the ordered step and returns the exact first row. */
  @Test
  public void normalPositiveScanCpuFactorKeepsOrderedPlan() {
    seedSkewed();
    try (var result = session.query(orderedQuery("ASC", 1))) {
      var step = stepOf(result);
      assertThat(drain(result, "mid")).containsExactly("m" + slot(0));
      assertThat(step.getChosenRuntimePath())
          .as("the normal factor must execute the planned ordered scan")
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT);
    }
  }

  /** A huge valid factor with a zero budget selects ordinary load-and-sort and exact rows. */
  @Test
  public void hugeValidScanCpuFactorWithZeroBudgetUsesOrdinaryPlan() {
    seedSkewed();
    withScanCpuFactor(1.0e9, () -> {
      assertThat(IndexOrderedCostModel.hasValidScanCpuFactor()).isTrue();
      assertThat(IndexOrderedCostModel.entriesWorthTheLoadAlternative(REACHABLE)).isZero();
      try (var result = session.query(orderedQuery("ASC", 2))) {
        assertThat(drain(result, "mid"))
            .containsExactly("m" + slot(0), "m" + slot(1));
        assertOrdinarySortPlan(result, "huge valid factor with zero budget");
      }
    });
  }

  private static void assertOrdinarySortPlan(ResultSet result, String reason) {
    var plan = result.getExecutionPlan();
    assertThat(plan).as("the query must have produced an execution plan").isNotNull();
    assertThat(findStep(plan.getSteps()))
        .as(reason + " must not force an index-ordered step:\n" + plan.prettyPrint(0, 2))
        .isNull();
    assertThat(plan.prettyPrint(0, 2)).as(reason + " must retain ORDER BY").contains("ORDER BY");
  }

  private static void withScanCpuFactor(double factor, Runnable action) {
    var configuration = GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR;
    try (var ignored = GlobalConfigurationScope.set(configuration, factor)) {
      action.run();
    }
  }

  /**
   * ASCENDING over the skewed fixture must cross every orphan before its first reachable message.
   * The model derives its budget from the reachable record count. The scan abandons itself first.
   * The correct earliest row confirms that no ordered row escaped before the bail-out.
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

      var recordReadCost =
          GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ.getValueAsDouble()
              + GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU.getValueAsDouble();
      var scanCostPerEntry =
          GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ.getValueAsDouble()
              / GlobalConfiguration.QUERY_INDEX_ORDERED_ENTRIES_PER_PAGE.getValueAsInteger()
              + GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR.getValueAsDouble()
                  * GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU.getValueAsDouble();
      var expectedBudget = (long) (REACHABLE * recordReadCost / scanCostPerEntry);
      assertThat(step.lastScanBudget())
          .as("the budget is the entry count reachable records are worth")
          .isEqualTo(expectedBudget);
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
   * A downstream edge can reject the row that satisfied the prefill. The ordered continuation
   * must cross a gap larger than two former budget windows to find the requested valid row.
   */
  @Test
  public void downstreamRejectionContinuesBeyondFormerFiniteWindows() {
    var message = session.createVertexClass("Message");
    message.createProperty("creationDate", PropertyType.STRING);
    message.getProperty("creationDate").createIndex(INDEX_TYPE.NOTUNIQUE);
    session.createVertexClass("Author");
    session.createVertexClass("Reply");
    session.createEdgeClass("wrote");
    session.createEdgeClass("hasReply");

    var gapSize = 6000;
    session.begin();
    session.execute("CREATE VERTEX Author SET name = 'author0'").close();
    session.execute("CREATE VERTEX Message SET creationDate = '9999', mid = 'rejected'").close();
    session.execute(
        "CREATE EDGE wrote FROM (SELECT FROM Author WHERE name = 'author0')"
            + " TO (SELECT FROM Message WHERE mid = 'rejected')")
        .close();
    for (var i = 0; i < gapSize; i++) {
      session.execute(
          "CREATE VERTEX Message SET creationDate = '5000-" + slot(i)
              + "', mid = 'gap" + i + "'")
          .close();
    }
    for (var i = 0; i < REACHABLE - 1; i++) {
      session.execute(
          "CREATE VERTEX Message SET creationDate = '1000-" + slot(i)
              + "', mid = 'low" + i + "'")
          .close();
      session.execute(
          "CREATE EDGE wrote FROM (SELECT FROM Author WHERE name = 'author0')"
              + " TO (SELECT FROM Message WHERE mid = 'low" + i + "')")
          .close();
    }
    session.execute("CREATE VERTEX Reply SET content = 'valid'").close();
    session.execute(
        "CREATE EDGE hasReply FROM (SELECT FROM Message WHERE mid = 'low18')"
            + " TO (SELECT FROM Reply WHERE content = 'valid')")
        .close();
    session.commit();

    var query =
        "MATCH {class: Author, as: a, where: (name = 'author0')}"
            + ".out('wrote'){class: Message, as: m}"
            + ".out('hasReply'){class: Reply, as: r}"
            + " RETURN m.mid AS mid ORDER BY m.creationDate DESC LIMIT 1";
    // Count actual index stream advances. The step's observation is only a prefill snapshot.
    session.begin();
    var cache = YqlExecutionPlanCache.instance(session);
    cache.invalidate();
    var statement = (SQLMatchStatement) SQLEngine.parse(query, session);
    var cold = statement.createExecutionPlan(new BasicCommandContext(session), false);
    assertThat(findStep(cold.getSteps())).isNotNull();
    cold.close();
    assertThat(cache.contains(query)).isTrue();
    var hits = cache.getHits();
    var warm = statement.createExecutionPlan(new BasicCommandContext(session), false);
    assertThat(cache.getHits()).isEqualTo(hits + 1);
    var fresh = statement.createExecutionPlanNoCache(new BasicCommandContext(session), false);
    var warmReads = characterizeContinuation(warm, gapSize);
    var freshReads = characterizeContinuation(fresh, gapSize);
    assertThat(warmReads).isEqualTo(freshReads);
    System.out.printf("Post-prefill index reads: warm=%d, fresh=%d, gap=%d%n",
        warmReads, freshReads, gapSize);
    session.rollback();
  }

  /** Dirty ordering indexes still scan a short prefix and include pending inserts and deletes. */
  @Test
  public void dirtyIndexStopsAtLimitWithoutRecountInEveryCostedRuntimeMode() throws Exception {
    seedSkewed();
    session.begin();
    var sourceRid = authorRid();
    var tx = session.getTransactionInternal();
    var index = session.getSharedContext().getIndexManager().getIndex("Message.creationDate");
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.0)) {
      session.execute("DELETE VERTEX Message WHERE mid = 'm0019'").close();
      for (var i = 0; i < 2; i++) {
        session.execute("CREATE VERTEX Message SET creationDate = '2027-" + i
            + "', mid = 'pending" + i + "'").close();
      }
      session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
          + " TO (SELECT FROM Message WHERE mid = 'pending1')").close();
      tx.preProcessRecordsAndExecuteCallCallbacks();
      assertThat(tx.getIndexChanges(index.getName())).isNotNull();
      assertThat(index.estimateEntryCount(session)).isEqualTo(ORPHANS + REACHABLE);
      assertThat(index.size(session)).isEqualTo(ORPHANS + REACHABLE + 1);

      var bound = orderedQuery("DESC", 1);
      var unbound = bound.replace("a.name as an, ", "");
      var singleton = unbound.replace("name LIKE 'author%'", "@rid = " + sourceRid)
          .replace("RETURN ", "RETURN DISTINCT ");
      var queries = List.of(singleton, bound, unbound);
      var modes = List.of("MATCH DESC", "(FILTERED_BOUND)", "(FILTERED_UNBOUND)");
      for (var i = 0; i < queries.size(); i++) {
        assertDirtySlice(queries.get(i), modes.get(i), null, "pending1");
        assertDirtySlice(queries.get(i), modes.get(i), 0L, "pending1");
      }

      session.execute("DELETE VERTEX Message WHERE mid = 'pending1'").close();
      tx.preProcessRecordsAndExecuteCallCallbacks();
      for (var i = 0; i < queries.size(); i++) {
        assertDirtySlice(queries.get(i), modes.get(i), null, "m0018");
        assertDirtySlice(queries.get(i), modes.get(i), -1L, "m0018");
      }
    } finally {
      session.rollback();
    }
    try (var result = session.query(orderedQuery("DESC", 1))) {
      assertThat(drain(result, "mid")).containsExactly("m0019");
    }
  }

  /**
   * Compound LIMIT sorts the leading tie but stops before the worse-key tail for both
   * DISTINCT $paths and a plain custom RETURN.
   */
  @Test
  public void compoundLimitStopsAfterSortingTheLeadingTie() throws ReflectiveOperationException {
    seedSkewed();
    session.begin();
    session.execute("UPDATE Message SET creationDate = '2027' WHERE mid IN ['m0018', 'm0019']")
        .close();
    session.commit();

    // Assign secondary keys in cursor order so passing the tie through would return the wrong row.
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex("Message.creationDate");
    String winner;
    try (var entries = index.descStream(session)) {
      var tie = entries.limit(2).toList();
      assertThat(tie).hasSize(2).allSatisfy(entry -> assertThat(entry.first()).isEqualTo("2027"));
      session.execute("UPDATE " + tie.getFirst().second() + " SET rank = 9").close();
      session.execute("UPDATE " + tie.getLast().second() + " SET rank = 1").close();
      winner = session.loadEntity(tie.getLast().second()).getProperty("mid");
    }
    session.commit();

    session.begin();
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.0)) {
      var match = "MATCH {class: Author, as: a, where: (@rid = " + authorRid() + ")}"
          + ".out('wrote'){class: Message, as: m} RETURN ";
      var order = " ORDER BY m.creationDate DESC, m.rank ASC LIMIT 1";
      for (var projection : List.of("DISTINCT $paths", "m.mid AS mid")) {
        var ctx = new BasicCommandContext(session);
        var statement = (SQLMatchStatement) SQLEngine.parse(match + projection + order, session);
        var plan = statement.createExecutionPlanNoCache(ctx, false);
        var step = findStep(plan.getSteps());
        assertThat(step).as(plan.prettyPrint(0, 2)).isNotNull();
        var reads = new AtomicLong();
        var closed = new AtomicLong();
        try {
          observeIndex(step, reads, closed, null);
          var stream = plan.start();
          try {
            var mids = stream.stream(ctx).map(row -> row.hasProperty("m")
                ? row.getVertex("m").<String>getProperty("mid")
                : row.<String>getProperty("mid")).toList();
            assertThat(mids).as(projection).containsExactly(winner);
          } finally {
            stream.close(ctx);
          }
        } finally {
          plan.close();
        }
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN);
        assertThat(reads.get()).as(projection + " cursor advances through close").isBetween(3L, 8L);
        assertThat(closed.get()).isPositive();
      }
    } finally {
      session.rollback();
    }
  }

  private String authorRid() {
    try (var source = session.query("SELECT FROM Author")) {
      return source.next().getIdentity().toString();
    }
  }

  private void assertDirtySlice(String query, String mode, @Nullable Long estimate, String expected)
      throws ReflectiveOperationException {
    var ctx = new BasicCommandContext(session);
    var plan = ((SQLMatchStatement) SQLEngine.parse(query, session))
        .createExecutionPlanNoCache(ctx, false);
    var step = findStep(plan.getSteps());
    assertThat(step).as(plan.prettyPrint(0, 2)).isNotNull();
    assertThat(step.prettyPrint(0, 2)).contains(mode);
    var reads = new AtomicLong();
    var closed = new AtomicLong();
    try {
      observeIndex(step, reads, closed, estimate);
      var stream = plan.start();
      try {
        assertThat(stream.stream(ctx).map(row -> row.<String>getProperty("mid")).toList())
            .containsExactly(expected);
      } finally {
        stream.close(ctx);
      }
    } finally {
      plan.close();
    }
    boolean singleton = query.contains("@rid");
    if (estimate == null) {
      assertThat(step.getChosenRuntimePath()).isEqualTo(singleton
          ? IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN
          : IndexOrderedEdgeStep.RuntimePath.UNION_SCAN);
      assertThat(reads.get()).as("total cursor advances through close").isBetween(1L, 8L);
      assertThat(closed.get()).isPositive();
    } else {
      assertThat(step.getChosenRuntimePath()).isEqualTo(singleton
          ? IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED
          : IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
      assertThat(reads.get()).isZero();
      assertThat(closed.get()).isZero();
    }
  }

  /** With parallel links, SKIP 1 takes the second occurrence of A rather than the next target. */
  @Test
  public void singletonParallelLinksPreserveOccurrencesAcrossTheSlice() {
    seedSkewed();
    session.begin();
    var query = orderedQuery("DESC", 1).replace("name LIKE 'author%'", "@rid = " + authorRid())
        .replace(" LIMIT", " SKIP 1 LIMIT");
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.0)) {
      try (var result = session.query(query)) {
        var step = stepOf(result);
        assertThat(drain(result, "mid")).containsExactly("m0018");
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN);
      }

      session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
          + " TO (SELECT FROM Message WHERE mid = 'm0019')").close();
      try (var result = session.query(query)) {
        var step = stepOf(result);
        assertThat(drain(result, "mid")).containsExactly("m0019");
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      }
    } finally {
      session.rollback();
    }
  }

  private static void observeIndex(IndexOrderedEdgeStep step, AtomicLong reads, AtomicLong closed,
      @Nullable Long estimate) throws ReflectiveOperationException {
    var indexField = IndexOrderedEdgeStep.class.getDeclaredField("index");
    indexField.setAccessible(true);
    var index = (Index) indexField.get(step);
    var counting = (Index) Proxy.newProxyInstance(Index.class.getClassLoader(),
        new Class<?>[] {Index.class}, (proxy, method, args) -> {
          // Reject before delegation. IndexMultiValues.size can count a stream internally.
          if (method.getName().equals("size")) {
            throw new AssertionError("An ordered MATCH strategy must not call Index.size()");
          }
          if (estimate != null) {
            if (method.getName().equals("estimateEntryCount")) {
              return estimate;
            }
            if (method.getName().equals("getHistogram")) {
              throw new AssertionError("An unusable estimate must skip histogram lookup");
            }
          }
          Object value;
          try {
            value = method.invoke(index, args);
          } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
          }
          if (value instanceof Stream<?> stream) {
            return stream.peek(entry -> reads.incrementAndGet()).onClose(closed::incrementAndGet);
          }
          return value;
        });
    indexField.set(step, counting);
  }

  private static long characterizeContinuation(InternalExecutionPlan plan, int gapSize) {
    var step = findStep(plan.getSteps());
    var reads = new AtomicLong();
    var closed = new AtomicLong();
    try {
      observeIndex(step, reads, closed, null);
      var ctx = plan.getContext();
      var stream = plan.start();
      var rows = new ArrayList<String>();
      try {
        while (stream.hasNext(ctx)) {
          rows.add(stream.next(ctx).getProperty("mid"));
        }
      } finally {
        stream.close(ctx);
      }
      assertThat(step.getChosenRuntimePath())
          .as("the native filtered index scan must exercise its continuation")
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.UNION_SCAN);
      assertThat(step.lastScanBudget()).as("the scan must start with a finite budget").isPositive();
      assertThat((long) gapSize)
          .as("the valid target must lie beyond two former continuation windows")
          .isGreaterThan(step.lastScanBudget() * 2);
      assertThat(rows)
          .as("the scan must continue after the prefetched target fails hasReply")
          .containsExactly("low18");
      assertThat(reads.get()).as("total index advances include post-prefill rejected entries")
          .isGreaterThan(step.lastScanBudget() * 2).isGreaterThanOrEqualTo(gapSize + 2L);
      assertThat(step.lastScanConsumedEntries()).as("prefill is not a bound on total scan cost")
          .isLessThan(reads.get());
      System.out.printf("Continuation budget=%d, prefill=%d, total=%d%n",
          step.lastScanBudget(), step.lastScanConsumedEntries(), reads.get());
      return reads.get();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    } finally {
      plan.close();
      assertThat(closed.get()).isPositive();
    }
  }

  /** A zero-bound template must reprice positive copies and bail out before emitting any row. */
  @Test
  public void zeroThenPositiveCopiesResolveCurrentTargetBeforeBudgetFallback() {
    seedSkewed();
    session.begin();
    String sourceRid;
    try (var source = session.query("SELECT FROM Author WHERE name = 'author0'")) {
      sourceRid = source.next().getIdentity().toString();
    }
    // A pinned source admits LIMIT 0 without the FILTERED plan-time cost gate. Zero cost bias
    // keeps scanning for unbounded copies too, so the test proves the cursor budget is lifted.
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.0)) {
      var query = orderedQuery("ASC", 0).replace("name LIKE 'author%'", "@rid = " + sourceRid)
          .replace("LIMIT 0", "SKIP :skip LIMIT :limit");
      var buildCtx = new BasicCommandContext(session);
      buildCtx.setInputParameters(Map.of("skip", 0, "limit", 0));
      var statement = (SQLMatchStatement) SQLEngine.parse(query, session);
      var template = statement.createExecutionPlanNoCache(buildCtx, false);
      assertThat(findStep(template.getSteps())).isNotNull();
      assertThat(template.canBeCached()).isFalse();
      for (var limit : List.of(0, 1, -1, 1)) {
        var ctx = new BasicCommandContext(session);
        ctx.setInputParameters(Map.of("skip", limit == 1 ? 1 : 0, "limit", limit));
        var copy = template.copy(ctx);
        var actual = new ArrayList<String>();
        try {
          var stream = copy.start();
          try {
            while (stream.hasNext(ctx)) {
              actual.add(stream.next(ctx).getProperty("mid"));
            }
          } finally {
            stream.close(ctx);
          }
        } finally {
          copy.close();
        }
        if (limit == 1) {
          var step = findStep(copy.getSteps());
          assertThat(step.getChosenRuntimePath())
              .isEqualTo(IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT);
          assertThat(step.lastScanConsumedEntries()).isBetween(step.lastScanBudget(),
              step.lastScanBudget() + 8);
          assertThat(actual).containsExactly("m" + slot(1));
        } else {
          assertThat(actual).hasSize(limit == 0 ? 0 : REACHABLE);
          assertThat(findStep(copy.getSteps()).getChosenRuntimePath())
              .isEqualTo(IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN);
        }
        try (var fresh = session.query(query, ctx.getInputParameters())) {
          assertThat(actual).isEqualTo(drain(fresh, "mid"));
        }
      }
      assertThat(findStep(template.getSteps()).getChosenRuntimePath()).isNull();
    }
    session.rollback();
  }

  /**
   * Nonzero costs choose a budgeted scan for target 2 and loading for an unbounded or target-20
   * copy. Changing only SKIP must reprice the same template too. Every slice matches a fresh plan.
   */
  @Test
  public void alternatingCopyBoundsRepriceScanVersusLoadWithNonzeroCosts() {
    seedSkewed();
    session.begin();
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 1.2);
        var random = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ, 4.0);
        var sequential = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ, 1.0);
        var cpu = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU, 0.01);
        var depth = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_STATS_DEFAULT_INDEX_TREE_DEPTH, 4);
        var factor = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR, 5.0);
        var page = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_ENTRIES_PER_PAGE, 200);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 10);
        var maximum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN, 5_000_000L)) {
      String sourceRid;
      try (var source = session.query("SELECT FROM Author WHERE name = 'author0'")) {
        sourceRid = source.next().getIdentity().toString();
      }
      var query = orderedQuery("ASC", 1).replace("name LIKE 'author%'", "@rid = " + sourceRid)
          .replace("LIMIT 1", "SKIP :skip LIMIT :limit");
      var statement = (SQLMatchStatement) SQLEngine.parse(query, session);
      var buildCtx = new BasicCommandContext(session);
      buildCtx.setInputParameters(Map.of("skip", 1, "limit", 1));
      var template = statement.createExecutionPlanNoCache(buildCtx, false);
      var slices = List.of(Map.of("skip", 1, "limit", 1), Map.of("skip", 0, "limit", -1),
          Map.of("skip", 1, "limit", 1), Map.of("skip", 19, "limit", 1),
          Map.of("skip", 0, "limit", 2), Map.of("skip", 1, "limit", 1));
      var all = new ArrayList<String>();
      for (var i = 0; i < REACHABLE; i++) {
        all.add("m" + slot(i));
      }
      var expected = List.of(List.of("m0001"), all, List.of("m0001"), List.of("m0019"),
          List.of("m0000", "m0001"), List.of("m0001"));
      try {
        assertThat(findStep(template.getSteps())).isNotNull();
        assertThat(template.canBeCached()).isFalse();
        for (var i = 0; i < slices.size(); i++) {
          var ctx = new BasicCommandContext(session);
          ctx.setInputParameters(new java.util.HashMap<>(slices.get(i)));
          var path = i == 1 || i == 3 ? IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED
              : IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT;
          var actual = runWithPath(template.copy(ctx), path);
          assertThat(actual).as("copy slice %s", slices.get(i)).isEqualTo(expected.get(i));
          var freshCtx = new BasicCommandContext(session);
          freshCtx.setInputParameters(new java.util.HashMap<>(slices.get(i)));
          // Fresh unbounded filtered plans use ordinary MATCH. Copies retain ordered admission.
          var freshPath = slices.get(i).get("limit") < 0 ? null : path;
          assertThat(runWithPath(statement.createExecutionPlanNoCache(freshCtx, false), freshPath))
              .isEqualTo(expected.get(i)).isEqualTo(actual);
        }
        assertThat(findStep(template.getSteps()).getChosenRuntimePath()).isNull();
        assertThat(findStep(template.getSteps()).lastScanConsumedEntries()).isEqualTo(-1);
      } finally {
        template.close();
      }
    } finally {
      session.rollback();
    }
  }

  private static List<String> runWithPath(InternalExecutionPlan plan,
      @Nullable IndexOrderedEdgeStep.RuntimePath expectedPath) {
    var ctx = plan.getContext();
    var rows = new ArrayList<String>();
    try {
      var stream = plan.start();
      try {
        while (stream.hasNext(ctx)) {
          rows.add(stream.next(ctx).getProperty("mid"));
        }
        var step = findStep(plan.getSteps());
        if (expectedPath == null) {
          assertThat(step).isNull();
        } else {
          assertThat(step).isNotNull();
          assertThat(step.getChosenRuntimePath()).isEqualTo(expectedPath);
          if (expectedPath == IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT) {
            assertThat(step.lastScanBudget()).isPositive();
            assertThat(step.lastScanConsumedEntries())
                .isBetween(step.lastScanBudget(), step.lastScanBudget() + 8);
          } else {
            assertThat(step.lastScanConsumedEntries()).isEqualTo(-1);
          }
        }
      } finally {
        stream.close(ctx);
      }
      return rows;
    } finally {
      plan.close();
    }
  }

  /** Clone and re-arm must not expose observations from a previous scan. */
  @Test
  public void cloneAndRearmClearLastScanObservations() {
    seedSkewed();
    try (var result = session.query(orderedQuery("ASC", 1))) {
      drain(result, "mid");
      var step = stepOf(result);
      assertThat(step.lastScanBudget()).isGreaterThanOrEqualTo(0L);
      assertThat(step.lastScanConsumedEntries()).isGreaterThanOrEqualTo(0L);

      var clone = step.copy(step.ctx);
      assertThat(clone.lastScanBudget()).as("a clone has no previous scan budget").isEqualTo(-1L);
      assertThat(clone.lastScanConsumedEntries())
          .as("a clone has no previous consumed-entry count")
          .isEqualTo(-1L);

      step.reset();
      assertThat(step.getChosenRuntimePath()).as("re-arm clears the previous path").isNull();
      assertThat(step.lastScanBudget()).as("re-arm clears the previous scan budget").isEqualTo(-1L);
      assertThat(step.lastScanConsumedEntries())
          .as("re-arm clears the previous consumed-entry count")
          .isEqualTo(-1L);
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
