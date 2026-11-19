package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.GlobalConfigurationScope;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Exercises scheduled prefilter reuse on both single-source LinkBag load paths. */
@Category(SequentialTest.class)
public class IndexOrderedLoadPrefilterTest extends DbTestBase {

  private String source;

  private void seed() {
    session.execute("CREATE CLASS Author EXTENDS V").close();
    session.execute("CREATE CLASS Message EXTENDS V").close();
    session.execute("CREATE PROPERTY Message.date INTEGER").close();
    session.execute("CREATE PROPERTY Message.tag STRING").close();
    session.execute("CREATE INDEX Message_date ON Message (date) NOTUNIQUE").close();
    session.execute("CREATE INDEX Message_tag ON Message (tag) NOTUNIQUE").close();
    session.execute("CREATE CLASS wrote EXTENDS E").close();
    session.execute("CREATE CLASS Reply EXTENDS V").close();
    session.execute("CREATE CLASS nextReply EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX Author").close();
    for (int i = 0; i < 1000; i++) {
      session.execute("CREATE VERTEX Message SET date = " + i + ", tag = '"
          + (i == 0 ? "rare" : "common") + "'").close();
    }
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date < 80)").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date = 0)").close();
    session.execute("CREATE VERTEX Reply SET id = 1").close();
    // Wrong-class links must not enter the membership metric population.
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Reply)").close();
    session.execute("CREATE EDGE nextReply FROM (SELECT FROM Message WHERE date < 80)"
        + " TO (SELECT FROM Reply)").close();
    session.commit();
    session.execute("ANALYZE INDEX Message_tag").close();
    session.begin();
    try (var row = session.query("SELECT FROM Author")) {
      source = row.next().getIdentity().toString();
    }
  }

  private String query(boolean downstream, boolean filter) {
    return "MATCH {class: Author, as: a, where: (@rid = " + source + ")}"
        + ".out('wrote'){class: Message, as: m"
        + (filter ? ", where: (tag = :tag)" : "") + "}"
        + (downstream ? ".out('nextReply'){class: Reply, as: r}" : "")
        + " RETURN m.date AS d ORDER BY m.date ASC LIMIT 5";
  }

  private InternalExecutionPlan plan(boolean downstream, boolean filter, String tag) {
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", tag));
    return ((SQLMatchStatement) SQLEngine.parse(query(downstream, filter), session))
        .createExecutionPlanNoCache(context, false);
  }

  private static IndexOrderedEdgeStep ordered(InternalExecutionPlan plan) {
    return plan.getSteps().stream().filter(IndexOrderedEdgeStep.class::isInstance)
        .map(IndexOrderedEdgeStep.class::cast).findFirst()
        .orElseThrow(() -> new AssertionError(plan.prettyPrint(0, 2)));
  }

  private interface PlanCloser extends AutoCloseable {
    @Override
    void close();
  }

  private static PlanCloser closePlan(InternalExecutionPlan plan) {
    return plan::close;
  }

  private static List<Integer> run(InternalExecutionPlan plan) {
    var rows = new ArrayList<Integer>();
    var stream = plan.start();
    try {
      while (stream.hasNext(plan.getContext())) {
        rows.add(stream.next(plan.getContext()).getProperty("d"));
      }
    } finally {
      stream.close(plan.getContext());
    }
    return rows;
  }

  /** An optional back-reference must not remove messages with no matching reply. */
  @Test
  public void optionalDownstreamBackReferenceKeepsUnmatchedMessages() {
    seed();
    session.execute("CREATE EDGE nextReply FROM (SELECT FROM Message WHERE date = 0)"
        + " TO " + source).close();
    session.commit();
    session.begin();
    var sql = query(true, false).replace(
        "{class: Reply, as: r}",
        "{class: Reply, as: r, where: (@rid = $matched.a.@rid), optional: true}");
    var context = new BasicCommandContext(session);
    var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(context, false);
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
      var step = ordered(plan);
      assertThat(run(plan)).containsExactly(0, 0, 1, 2, 3);
      assertThat(step.getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
    }
  }

  /** A rebound unlimited query preserves optional rows on the unsorted load path. */
  @Test
  public void unsortedOptionalBackReferenceKeepsUnmatchedMessages() {
    seed();
    session.execute("CREATE EDGE nextReply FROM (SELECT FROM Message WHERE date = 0)"
        + " TO " + source).close();
    session.commit();
    session.begin();
    var sql = query(true, false).replace("LIMIT 5", "LIMIT :limit").replace(
        "{class: Reply, as: r}",
        "{class: Reply, as: r, where: (@rid = $matched.a.@rid), optional: true}");
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("limit", 5));
    var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(context, false);
    // Keep the accepted ordered plan and disable LIMIT for this execution.
    context.setInputParameters(Map.of("limit", -1));
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
      var step = ordered(plan);
      var rows = run(plan);
      assertThat(rows).as(plan.prettyPrint(0, 2)).hasSize(81);
      assertThat(rows.subList(0, 5)).containsExactly(0, 0, 1, 2, 3);
      assertThat(rows.getLast()).isEqualTo(79);
      assertThat(ordered(plan).getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
    }
  }

  /** A bound target RID uses singleton admission on both load paths. */
  @Test
  public void targetRidEqualityKeepsDirectAdmission() {
    targetRidEquality(false, false);
  }

  /** An indexed common-value conjunct must not replace singleton RID admission. */
  @Test
  public void targetRidWithIndexedConjunctKeepsDirectAdmission() {
    targetRidEquality(false, true);
  }

  /** Locally sorted loading also keeps the singleton RID descriptor. */
  @Test
  public void sortedTargetRidEqualityKeepsDirectAdmission() {
    targetRidEquality(true, false);
  }

  /** Locally sorted loading ignores index admission for a RID with an indexed conjunct. */
  @Test
  public void sortedTargetRidWithIndexedConjunctKeepsDirectAdmission() {
    targetRidEquality(true, true);
  }

  private void targetRidEquality(boolean downstream, boolean indexedConjunct) {
    seed();
    Object messageRid;
    try (var rows = session.query("SELECT FROM Message WHERE date = 1")) {
      messageRid = rows.next().getIdentity();
    }
    var sql = query(downstream, false).replace(
        "as: m}", "as: m, where: (@rid = :messageRid"
            + (indexedConjunct ? " AND tag = 'common'" : "") + ")}");
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("messageRid", messageRid));
    var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(context, false);
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
      assertThat(run(plan)).containsExactly(1);
      var step = ordered(plan);
      assertThat(step.getChosenRuntimePath()).isEqualTo(downstream
          ? IndexOrderedEdgeStep.RuntimePath.LOAD_SORT
          : IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertMembership(step, 80);
      assertThat(step.executionTraversal().getPreFilterRidSetSize()).isEqualTo(1);
    }
  }

  /** Rare-value loading preserves duplicate edges and builds the scheduled index RID set. */
  @Test
  public void unsortedLoadUsesScheduledIndexPrefilter() {
    seed();
    var plan = plan(false, true, "rare");
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
      var step = ordered(plan);
      assertThat(step.targetIndexDescriptor().getIndex().getName()).isEqualTo("Message_tag");
      assertThat(run(plan)).containsExactly(0, 0);
      assertThat(step.getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertMembership(step, 79);
      assertThat(step.executionTraversal().getRootSourceRows()).isEqualTo(1);
      assertThat(step.executionTraversal().getPreFilterRidSetSize()).isEqualTo(1);
    }
  }

  /** The locally sorted load also keeps one row per edge before downstream traversal. */
  @Test
  public void sortedLoadUsesScheduledIndexPrefilter() {
    seed();
    var plan = plan(true, true, "rare");
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
      var step = ordered(plan);
      assertThat(run(plan)).containsExactly(0, 0);
      assertThat(step.getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
      assertMembership(step, 79);
      assertThat(step.executionTraversal().getPreFilterRidSetSize()).isEqualTo(1);
    }
  }

  private static void assertMembership(IndexOrderedEdgeStep step, long filtered) {
    var traversal = step.executionTraversal();
    assertThat(traversal.getPreFilterAppliedCount()).isEqualTo(1);
    assertThat(traversal.getPreFilterTotalProbed()).isEqualTo(81);
    assertThat(traversal.getPreFilterTotalFiltered()).isEqualTo(filtered);
  }

  /** Uncommitted tag changes must be visible to unsorted loading and ordinary MATCH alike. */
  @Test
  public void unsortedPrefilterSeesPendingTargetUpdates() {
    pendingTargetUpdates(false);
  }

  /** Uncommitted tag changes must be visible to locally sorted loading and ordinary MATCH. */
  @Test
  public void sortedPrefilterSeesPendingTargetUpdates() {
    pendingTargetUpdates(true);
  }

  private void pendingTargetUpdates(boolean downstream) {
    seed();
    session.execute("UPDATE Message SET tag = 'common' WHERE date = 0").close();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date = 1").close();
    List<Integer> plain;
    var plainSql = query(downstream, true).replace(" ORDER BY m.date ASC LIMIT 5", "");
    try (var rows = session.query(plainSql, Map.of("tag", "rare"))) {
      plain = rows.stream().map(row -> (Integer) row.getProperty("d")).toList();
    }
    assertThat(plain).containsExactly(1);
    var plan = plan(downstream, true, "rare");
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
      assertThat(run(plan)).isEqualTo(plain);
      var step = ordered(plan);
      assertThat(step.getChosenRuntimePath()).isEqualTo(downstream
          ? IndexOrderedEdgeStep.RuntimePath.LOAD_SORT
          : IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertMembership(step, 80);
    }
  }

  /** Common values fail the ordinary selectivity gate and still return the requested rows. */
  @Test
  public void commonValueSkipsIndexPrefilter() {
    seed();
    var plan = plan(false, true, "common");
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
      assertThat(run(plan)).containsExactly(1, 2, 3, 4, 5);
      assertSkipped(ordered(plan), PreFilterSkipReason.SELECTIVITY_TOO_LOW);
    }
  }

  /** Small bags do not build a prefilter even when the target condition is selective. */
  @Test
  public void configuredMinimumSkipsPrefilterBuild() {
    seed();
    var plan = plan(false, true, "rare");
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE, 100000)) {
      assertThat(run(plan)).containsExactly(0, 0);
      assertSkipped(ordered(plan), PreFilterSkipReason.LINKBAG_TOO_SMALL);
    }
  }

  private static void assertSkipped(IndexOrderedEdgeStep step, PreFilterSkipReason reason) {
    var traversal = step.executionTraversal();
    assertThat(traversal.getPreFilterRidSetSize()).isZero();
    assertThat(traversal.getPreFilterAppliedCount()).isZero();
    assertThat(traversal.getPreFilterSkippedCount()).isEqualTo(1);
    assertThat(traversal.getLastSkipReason()).isEqualTo(reason);
    assertThat(traversal.getPreFilterTotalProbed()).isZero();
  }

  /** An edge without a target filter has no lookup descriptor and keeps every edge row. */
  @Test
  public void unfilteredLoadKeepsDuplicates() {
    seed();
    var plan = plan(false, false, "rare");
    try (var close = closePlan(plan);
        var ignored = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
      assertThat(ordered(plan).targetIndexDescriptor()).isNull();
      assertThat(run(plan)).containsExactly(0, 0, 1, 2, 3);
    }
  }

  /** Plan copies retain lookup metadata but never reuse a previous execution's RID set. */
  @Test
  public void copiedPlanRebindsHitAndMissWithoutSharingTraversalCache() {
    seed();
    var template = plan(false, true, "rare");
    try (var closeTemplate = closePlan(template)) {
      var firstContext = new BasicCommandContext(session);
      firstContext.setInputParameters(Map.of("tag", "rare"));
      var secondContext = new BasicCommandContext(session);
      secondContext.setInputParameters(Map.of("tag", "absent"));
      var first = template.copy(firstContext);
      var second = template.copy(secondContext);
      try (var closeFirst = closePlan(first);
          var closeSecond = closePlan(second);
          var ignored = GlobalConfigurationScope.set(
              GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 100000)) {
        assertThat(ordered(first).targetIndexDescriptor())
            .isSameAs(ordered(template).targetIndexDescriptor());
        assertThat(run(first)).containsExactly(0, 0);
        assertThat(run(second)).isEmpty();
        assertThat(ordered(first).executionTraversal())
            .isNotSameAs(ordered(second).executionTraversal());
        assertThat(ordered(first).executionTraversal().getPreFilterRidSetSize()).isEqualTo(1);
        assertThat(ordered(second).executionTraversal().getPreFilterRidSetSize()).isZero();
        assertThat(ordered(template).executionTraversal()).isNull();
      }
    }
  }
}
