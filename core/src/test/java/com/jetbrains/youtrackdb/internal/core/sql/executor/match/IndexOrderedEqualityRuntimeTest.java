package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.GlobalConfigurationScope;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Query-level path and row checks for singleton ordered MATCH equality fetch. */
@Category(SequentialTest.class)
public class IndexOrderedEqualityRuntimeTest extends DbTestBase {

  private String source;

  private void seed() {
    session.execute("CREATE CLASS Author EXTENDS V").close();
    session.execute("CREATE CLASS Message EXTENDS V").close();
    session.execute("CREATE PROPERTY Message.date INTEGER").close();
    session.execute("CREATE PROPERTY Message.tag STRING").close();
    session.execute("CREATE INDEX Message_date ON Message (date) NOTUNIQUE").close();
    session.execute("CREATE INDEX Message_tag ON Message (tag) NOTUNIQUE").close();
    session.execute("CREATE CLASS wrote EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX Author").close();
    for (int i = 0; i < 200; i++) {
      session.execute("CREATE VERTEX Message SET date = " + i + ", tag = '"
          + (i == 150 ? "rare" : "common") + "'").close();
    }
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message)").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date = 150)").close();
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
    try (var rows = session.query("SELECT FROM Author")) {
      source = rows.next().getIdentity().toString();
    }
  }

  private String query(String direction) {
    return "MATCH {class: Author, as: a, where: (@rid = " + source + ")}"
        + ".out('wrote'){class: Message, as: m, where: (tag = :tag)}"
        + " RETURN m.date AS d ORDER BY m.date " + direction + " LIMIT 5";
  }

  private static IndexOrderedEdgeStep find(List<ExecutionStep> steps) {
    for (var step : steps) {
      if (step instanceof IndexOrderedEdgeStep ordered) {
        return ordered;
      }
      var nested = find(step.getSubSteps());
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private List<Integer> run(String tag, String direction, IndexOrderedEdgeStep.RuntimePath path) {
    try (var rows = session.query(query(direction), Map.of("tag", tag))) {
      var values = new ArrayList<Integer>();
      while (rows.hasNext()) {
        values.add(rows.next().getProperty("d"));
      }
      var plan = rows.getExecutionPlan();
      var step = find(plan.getSteps());
      assertThat(step).as(plan.prettyPrint(0, 2)).isNotNull();
      assertThat(step.getChosenRuntimePath())
          .as("%s scan=%s budget=%s", plan.prettyPrint(0, 2),
              step.lastScanConsumedEntries(), step.lastScanBudget())
          .isEqualTo(path);
      if (path == IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH) {
        assertThat(step.lastScanConsumedEntries()).isEqualTo(-1);
        assertThat(plan.prettyPrint(0, 2)).contains("equality-fetch");
      } else if (path == IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN) {
        assertThat(step.lastScanConsumedEntries()).isBetween(1L, 10L);
      }
      return values;
    }
  }

  /** A miss never opens the ordering cursor. A rare hit preserves parallel edges and order. */
  @Test
  public void selectiveLookupAndMissUseEqualityFetch() {
    seed();
    assertThat(run("absent", "ASC", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH)).isEmpty();
    assertThat(run("rare", "ASC", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH))
        .containsExactly(150, 150);
    assertThat(run("rare", "DESC", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH))
        .containsExactly(150, 150);
  }

  /** A common value still uses the ordering cursor and stops at the requested prefix. */
  @Test
  public void commonValueKeepsEarlyOrderedScan() {
    seed();
    assertThat(run("common", "ASC", IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN))
        .containsExactly(0, 1, 2, 3, 4);
  }

  /** The compact ordered membership must expand repeats but emit singleton members once. */
  @Test
  public void orderedScanKeepsParallelSourceLinks() {
    seed();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date = 0)").close();
    assertThat(run("common", "ASC", IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN))
        .containsExactly(0, 0, 1, 2, 3);
  }

  /** The same cached text must resolve its current parameter in either execution order. */
  @Test
  public void cachedHitAndMissResolveCurrentBindings() {
    seed();
    for (var tag : List.of("rare", "absent", "absent", "rare")) {
      assertThat(run(tag, "ASC", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH))
          .isEqualTo(tag.equals("rare") ? List.of(150, 150) : List.of());
    }
  }

  /** An underestimated common value exhausts its account and discards the buffered prefix. */
  @Test
  public void equalityExhaustionLoadsWithoutPrefix() throws Exception {
    seed();
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "common"));
    var plan = ((SQLMatchStatement) SQLEngine.parse(query("ASC"), session))
        .createExecutionPlanNoCache(context, false);
    var step = find(plan.getSteps());
    var descriptor = spy(step.targetIndexDescriptor());
    doReturn(1L).when(descriptor).estimateHits(context);
    var field = IndexOrderedEdgeStep.class.getDeclaredField("targetIndexDescriptor");
    field.setAccessible(true);
    field.set(step, descriptor);
    var values = new ArrayList<Integer>();
    try {
      var stream = plan.start();
      try {
        while (stream.hasNext(context)) {
          values.add(stream.next(context).getProperty("d"));
        }
      } finally {
        stream.close(context);
      }
      assertThat(values).containsExactly(0, 1, 2, 3, 4);
      assertThat(step.getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertThat(step.lastScanConsumedEntries()).isZero();
    } finally {
      plan.close();
    }
  }

  private String downstreamQuery() {
    return query("ASC").replace(" RETURN", ".out('replyOf'){as: z} RETURN")
        .replace("LIMIT 5", "LIMIT 1");
  }

  private void seedDownstream() {
    seed();
    session.execute("CREATE CLASS replyOf EXTENDS E").close();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date = 160").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE date = 160)"
        + " TO (SELECT FROM Author)").close();
  }

  private List<Integer> dates(String sql, boolean orderedLoad) {
    try (var rows = session.query(sql, Map.of("tag", "rare"))) {
      var values = new ArrayList<Integer>();
      while (rows.hasNext()) {
        values.add(rows.next().getProperty("d"));
      }
      if (orderedLoad) {
        assertThat(find(rows.getExecutionPlan().getSteps()).getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
      }
      return values;
    }
  }

  /** A stale equality count reaches sorted load without losing the later accepted candidate. */
  @Test
  public void downstreamRejectionRetainsLaterEqualityRows() {
    seedDownstream();
    var sql = downstreamQuery();
    assertThat(dates(sql, true)).containsExactly(160);
    assertThat(dates(sql.replace(" LIMIT 1", ""), false)).containsExactly(160);
  }

  /** Sorted load retains all primary ties for the secondary key when LIMIT is one. */
  @Test
  public void downstreamSecondaryOrderRetainsPrimaryTies() {
    seedDownstream();
    session.execute("UPDATE Message SET name = 'a' WHERE date = 150").close();
    session.execute("UPDATE Message SET date = 150, name = 'b' WHERE date = 160").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE name = 'a')"
        + " TO (SELECT FROM Author)").close();
    var sql = downstreamQuery().replace("m.date AS d", "m.name AS n")
        .replace("m.date ASC", "m.date ASC, m.name DESC");
    try (var rows = session.query(sql, Map.of("tag", "rare"))) {
      assertThat(rows.next().<String>getProperty("n")).isEqualTo("b");
      assertThat(rows.hasNext()).isFalse();
      assertThat(find(rows.getExecutionPlan().getSteps()).getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
    }
    try (var rows = session.query(sql.replace(" LIMIT 1", ""), Map.of("tag", "rare"))) {
      assertThat(rows.next().<String>getProperty("n")).isEqualTo("b");
    }
  }

  /** Observe sorted load itself so OrderByStep cannot repair missing local rows or sorting. */
  @Test
  public void downstreamLoadSortEmitsEveryRowInLocalOrder() {
    seedDownstream();
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "rare"));
    var sql = downstreamQuery();
    var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(context, false);
    var step = find(plan.getSteps());
    var values = new ArrayList<Integer>();
    try {
      var stream = step.start(context);
      try {
        while (stream.hasNext(context)) {
          values.add(stream.next(context).getEntity("m").getProperty("date"));
        }
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
        assertThat((Object) context.getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED))
            .isEqualTo(Boolean.TRUE);
        assertThat(values).containsExactly(150, 150, 160);
      } finally {
        stream.close(context);
      }
    } finally {
      plan.close();
    }
  }

  /** Runtime pricing must not ask countClass to scan pending transaction operations. */
  @Test
  public void equalityPricingDoesNotCountTransactionalClass() {
    seed();
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "rare"));
    var plan = ((SQLMatchStatement) SQLEngine.parse(query("ASC"), session))
        .createExecutionPlanNoCache(context, false);
    var observedSession = spy(session);
    context.setDatabaseSession(observedSession);
    try {
      var stream = plan.start();
      try {
        while (stream.hasNext(context)) {
          stream.next(context);
        }
        assertThat(find(plan.getSteps()).getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
        verify(observedSession, never()).countClass("Message");
      } finally {
        stream.close(context);
      }
    } finally {
      plan.close();
    }
  }

  /** Refusing all candidate memory chooses the terminal load without losing parallel rows. */
  @Test
  public void memoryRefusalUsesLoad() {
    seed();
    var cap = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES;
    try (var ignored = GlobalConfigurationScope.capture(cap)) {
      cap.setValue(0);
      assertThat(run("rare", "ASC", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED))
          .containsExactly(150, 150);
    }
  }
}
