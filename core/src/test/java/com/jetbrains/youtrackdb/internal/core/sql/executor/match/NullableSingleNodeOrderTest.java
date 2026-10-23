package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Complete nullable order and logical entry consumption on both single-node query paths. */
public class NullableSingleNodeOrderTest extends GraphBaseTest {

  private record Row(RID rid, Integer score, String status) {
  }

  private record Page(int skip, int limit, String status) {
  }

  private final AtomicLong nullReads = new AtomicLong();
  private final AtomicLong keyReads = new AtomicLong();
  private final AtomicLong opened = new AtomicLong();
  private final AtomicLong closed = new AtomicLong();

  private List<Row> seed(int nonNull) {
    session.execute("CREATE CLASS NullablePage EXTENDS V").close();
    session.execute("CREATE PROPERTY NullablePage.score INTEGER").close();
    session.execute("CREATE INDEX NullablePage_score ON NullablePage (score) NOTUNIQUE").close();
    for (var i = 0; i < 4096; i++) {
      var vertex = graph.addVertex(T.label, "NullablePage", "status", i % 3 == 0 ? "done" : "open");
      if (i < nonNull) {
        vertex.property("score", 7);
      }
    }
    graph.tx().commit();
    // Include explicit nulls as well as absent properties. Build the reference by class scan.
    session.begin();
    var records = session.browseClass("NullablePage");
    var position = 0;
    while (records.hasNext()) {
      var record = records.next();
      if (position++ >= nonNull && position % 2 == 0) {
        record.setProperty("score", null);
      }
    }
    session.commit();
    return reference();
  }

  private List<Row> reference() {
    var rows = new ArrayList<Row>();
    session.begin();
    var records = session.browseClass("NullablePage");
    while (records.hasNext()) {
      var record = records.next();
      rows.add(new Row(record.getIdentity(), record.getProperty("score"),
          record.getProperty("status")));
    }
    session.commit();
    // The fixture must distinguish numeric RID order from printed-string order.
    assertThat(rows.stream().map(Row::rid).sorted().toList())
        .isNotEqualTo(rows.stream().map(Row::rid).sorted(Comparator.comparing(Object::toString))
            .toList());
    return rows;
  }

  private static List<Row> ordered(List<Row> rows, boolean first) {
    Comparator<Integer> scores = first ? Comparator.nullsFirst(Comparator.reverseOrder())
        : Comparator.nullsLast(Comparator.reverseOrder());
    return rows.stream().sorted(Comparator.comparing(Row::score, scores)
        .thenComparing(Row::rid, Comparator.reverseOrder())).toList();
  }

  private static List<RID> expected(List<Row> ordered, Page page) {
    return ordered.stream().filter(row -> page.status == null || page.status.equals(row.status))
        .skip(page.skip).limit(page.limit).map(Row::rid).toList();
  }

  private static long frontier(List<Row> ordered, Page page) {
    var matching = 0;
    for (var i = 0; i < ordered.size(); i++) {
      if (page.status == null || page.status.equals(ordered.get(i).status)) {
        if (++matching == page.skip + page.limit) {
          return i + 1L;
        }
      }
    }
    return ordered.size();
  }

  private String query(boolean first, Page page) {
    return "MATCH {class: NullablePage, as: n"
        + (page.status == null ? "" : ", where: (status = '" + page.status + "')")
        + "} RETURN n ORDER BY n.score DESC NULLS " + (first ? "FIRST" : "LAST")
        + ", n.@rid DESC SKIP " + page.skip + " LIMIT " + page.limit;
  }

  private InternalExecutionPlan plan(String sql) {
    return ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(new BasicCommandContext(session), false);
  }

  private void resetCounts() {
    nullReads.set(0);
    keyReads.set(0);
    opened.set(0);
    closed.set(0);
  }

  private void instrument(List<ExecutionStep> steps) throws Exception {
    for (var step : steps) {
      if (step instanceof FetchFromIndexStep) {
        var field = FetchFromIndexStep.class.getDeclaredField("desc");
        field.setAccessible(true);
        var descriptor = (IndexSearchDescriptor) field.get(step);
        var original = descriptor.getIndex();
        var proxy = (Index) Proxy.newProxyInstance(Index.class.getClassLoader(),
            new Class<?>[] {Index.class}, (object, method, args) -> {
              Object value;
              try {
                value = method.invoke(original, args);
              } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
              }
              if (value instanceof Stream<?> stream && List.of("stream", "descStream", "getRids",
                  "getNullKeyRids", "getNullKeyRidsInOrder", "streamEntriesBetween",
                  "streamEntriesMajor", "streamEntriesMinor").contains(method.getName())) {
                opened.incrementAndGet();
                var counter = method.getName().startsWith("getNull") ? nullReads : keyReads;
                return stream.peek(entry -> counter.incrementAndGet())
                    .onClose(closed::incrementAndGet);
              }
              return value;
            });
        var index = IndexSearchDescriptor.class.getDeclaredField("index");
        index.setAccessible(true);
        index.set(descriptor, proxy);
      }
      instrument(step.getSubSteps());
    }
  }

  private List<RID> drain(InternalExecutionPlan plan) {
    var result = new ArrayList<RID>();
    var context = plan.getContext();
    var stream = plan.start();
    try {
      while (stream.hasNext(context)) {
        result.add(stream.next(context).getVertex("n").getIdentity());
      }
    } finally {
      stream.close(context);
      plan.close();
    }
    return result;
  }

  private void assertReads(long frontier) {
    assertThat(opened.get()).as("instrumentation must observe actual index streams").isPositive();
    assertThat(closed.get()).isEqualTo(opened.get());
    assertThat(nullReads.get() + keyReads.get())
        .as("logical advances stop at the matching frontier")
        .isBetween(frontier, frontier + 4);
  }

  private void checkBoth(List<Row> rows, boolean first, Page page) throws Exception {
    var order = ordered(rows, first);
    var expected = expected(order, page);
    var frontier = frontier(order, page);
    session.begin();
    var direct = plan(query(first, page));
    instrument(direct.getSteps());
    resetCounts();
    assertThat(drain(direct)).containsExactlyElementsOf(expected);
    assertReads(frontier);
    assertThat(
        (Object) direct.getContext().getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED))
        .isEqualTo(true);
    session.rollback();

    var source = graph.traversal().with(YTDBQueryConfigParam.orderByNullsPlacementDesc,
        first ? "FIRST" : "LAST");
    var traversal = source.V().hasLabel("NullablePage");
    if (page.status != null) {
      traversal = traversal.has("status", page.status);
    }
    traversal = traversal.order().by("score", Order.desc).range(page.skip, page.skip + page.limit);
    traversal.asAdmin().applyStrategies();
    assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    var boundary = (YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep();
    instrument(boundary.getPlan().getSteps());
    resetCounts();
    assertThat(traversal.toList().stream().map(v -> (RID) v.id()).toList())
        .containsExactlyElementsOf(expected);
    traversal.close();
    assertReads(frontier);
    graph.tx().rollback();
  }

  /** All-null data stops inside its large null group for both placements and query paths. */
  @Test
  public void allNullPagesStopWithoutReadingTheSuffix() throws Exception {
    var rows = seed(0);
    for (var first : List.of(false, true)) {
      checkBoth(rows, first, new Page(0, 5, null));
      checkBoth(rows, first, new Page(17, 5, null));
    }
  }

  /** Small non-null prefix covers boundary pages, filters, no matches, and null placement. */
  @Test
  public void boundaryAndFilteredPagesUseMatchingRowFrontiers() throws Exception {
    var rows = seed(9);
    for (var first : List.of(false, true)) {
      for (var page : List.of(new Page(0, 5, null), new Page(0, 15, null),
          new Page(7, 5, null), new Page(20, 5, null), new Page(1, 5, "done"),
          new Page(5, 5, "done"), new Page(0, 5, "absent"), new Page(1365, 5, "done"))) {
        checkBoth(rows, first, page);
      }
    }
  }

  /** A large equal non-null group stops within its RID ties rather than sorting all entries. */
  @Test
  public void equalNonNullPageLeavesLargeUnreadSuffix() throws Exception {
    checkBoth(seed(2048), false, new Page(13, 5, null));
  }

  /** Warm translated plans recheck pending null puts and consume all input for the full sort. */
  @Test
  public void translatedPlanReuseRechecksPendingChanges() throws Exception {
    // A range lookup is cacheable. Sort-only VALUES scans deliberately are not cacheable.
    var rows = seed(4090).stream().filter(row -> row.score != null).toList();
    var page = new Page(7, 5, null);
    InternalExecutionPlan template = null;
    for (var pending : List.of(false, true, false)) {
      if (pending) {
        var vertex = graph.addVertex(T.label, "NullablePage", "status", "done");
        // The range rejects this pending null, but it still disables runtime order permission.
        assertThat(vertex.property("score").isPresent()).isFalse();
      }
      var traversal = graph.traversal().V().hasLabel("NullablePage")
          .has("score", org.apache.tinkerpop.gremlin.process.traversal.P.gte(7))
          .order().by("score", Order.desc).range(7, 12);
      // Apply the default numeric tie-break and cache-enabled translator explicitly. This
      // isolates template reuse from the other traversal strategies.
      YTDBOrderRidTieBreakStrategy.instance().apply(traversal.asAdmin());
      GremlinToMatchStrategy.instance().apply(traversal.asAdmin());
      assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      var boundary = (YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep();
      if (template == null) {
        template = boundary.getPlan();
        instrument(template.getSteps());
      } else {
        assertThat(boundary.getPlan()).as("the physical template must actually be reused")
            .isSameAs(template);
      }
      resetCounts();
      assertThat(traversal.toList().stream().map(v -> (RID) v.id()).toList())
          .containsExactlyElementsOf(expected(ordered(rows, false), page));
      assertThat((Object) boundary.getPlan().getContext()
          .getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isEqualTo(!pending);
      traversal.close();
      if (pending) {
        assertThat(nullReads.get() + keyReads.get()).isGreaterThanOrEqualTo(rows.size());
      } else {
        assertReads(frontier(ordered(rows, false), page));
      }
      graph.tx().rollback();
    }
  }

  /** MATCH null equality is empty, while Gremlin eq(null) selects the complete IS NULL group. */
  @Test
  public void nullEqualityUsesEachQueryLanguagesSemantics() throws Exception {
    var rows = seed(9);
    var params = new java.util.HashMap<String, Object>();
    params.put("key", null);
    var sql = "MATCH {class: NullablePage, as: n, where: (score = :key)} RETURN n"
        + " ORDER BY n.score DESC, n.@rid DESC LIMIT 5";
    try (var result = session.query(sql, params)) {
      assertThat(result.stream().toList()).isEmpty();
    }
    var traversal = graph.traversal().V().hasLabel("NullablePage")
        .has("score", org.apache.tinkerpop.gremlin.process.traversal.P.eq(null))
        .order().by("score", Order.desc);
    traversal.asAdmin().applyStrategies();
    assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    assertThat(traversal.toList().stream().map(v -> (RID) v.id()).toList())
        .containsExactlyElementsOf(ordered(rows, false).stream()
            .filter(row -> row.score == null).map(Row::rid).toList());
    traversal.close();
    graph.tx().rollback();
  }

  /** IN and OR lookup ranges retain the full sort on both translated and direct query paths. */
  @Test
  public void multiRangeShapesDoNotGainOrderCertification() throws Exception {
    var rows = seed(9);
    var expected = ordered(rows, false).stream().filter(row -> row.score != null)
        .map(Row::rid).toList();
    for (var condition : List.of("score IN [7, 8]", "score = 7 OR score = 8")) {
      session.begin();
      var direct = plan("MATCH {class: NullablePage, as: n, where: (" + condition
          + ")} RETURN n ORDER BY n.score DESC, n.@rid DESC");
      assertThat(drain(direct)).containsExactlyElementsOf(expected);
      assertThat((Object) direct.getContext()
          .getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isNotEqualTo(true);
      session.rollback();
    }
    for (var predicate : List.of(
        org.apache.tinkerpop.gremlin.process.traversal.P.within(7, 8),
        org.apache.tinkerpop.gremlin.process.traversal.P.eq(7)
            .or(org.apache.tinkerpop.gremlin.process.traversal.P.eq(8)))) {
      var traversal = graph.traversal().V().hasLabel("NullablePage").has("score", predicate)
          .order().by("score", Order.desc);
      traversal.asAdmin().applyStrategies();
      assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      var boundary = (YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep();
      assertThat(traversal.toList().stream().map(v -> (RID) v.id()).toList())
          .containsExactlyElementsOf(expected);
      assertThat((Object) boundary.getPlan().getContext()
          .getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isNotEqualTo(true);
      traversal.close();
      graph.tx().rollback();
    }
  }

  /** Reused clean templates recheck transaction changes and consume all input for pending nulls. */
  @Test
  public void copiedPlanRechecksPendingRecordChangesOnEveryExecution() throws Exception {
    var rows = seed(9);
    var page = new Page(7, 5, null);
    session.begin();
    var template = plan(query(false, page));
    instrument(template.getSteps());
    for (var pending : List.of(false, true, false)) {
      if (pending) {
        var record = session.newVertex("NullablePage");
        record.setProperty("status", "done");
        rows = new ArrayList<>(rows);
        rows.add(new Row(record.getIdentity(), null, "done"));
      }
      var copy = template.copy(new BasicCommandContext(session));
      resetCounts();
      assertThat(drain(copy)).containsExactlyElementsOf(expected(ordered(rows, false), page));
      assertThat(
          (Object) copy.getContext().getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED))
          .isEqualTo(!pending);
      if (pending) {
        assertThat(nullReads.get() + keyReads.get()).isGreaterThanOrEqualTo(4096);
        session.rollback();
        rows = rows.subList(0, rows.size() - 1);
        session.begin();
      } else {
        assertReads(frontier(ordered(rows, false), page));
      }
    }
    template.close();
    session.rollback();
  }
}
