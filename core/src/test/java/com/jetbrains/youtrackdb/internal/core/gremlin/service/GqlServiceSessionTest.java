package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlStatement;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.query.RegisteredQuery;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CallStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Real GQL ownership and warning emission require sequential global-configuration changes. */
@Category(SequentialTest.class)
public class GqlServiceSessionTest extends GraphBaseTest {

  private static final String QUERY = "MATCH (p:GqlOwned)";
  private Object previousThreshold;

  @Override
  public void beforeTest() throws Exception {
    previousThreshold = GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.getValue();
    // Sessions capture the threshold during construction.
    GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.setValue(2);
    super.beforeTest();
  }

  @Override
  public void afterTest() {
    try {
      super.afterTest();
    } finally {
      GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.setValue(previousThreshold);
    }
  }

  /** Three GQL executions or mixed YQL/MATCH/GQL entries warn, then retire at either tx end. */
  @Test
  public void warningsCountLiveExecutionsNotTemplatesOrUnopenedTraversals() throws Exception {
    seed();
    for (var commit : new boolean[] {true, false}) {
      for (var mixed : new boolean[] {false, true}) {
        var db = graphSession();
        var active = db.getActiveQueries();
        var cache = GqlExecutionPlanCache.instance(db);
        cache.invalidate();
        var templateSource = GqlPlanner.getStatement(QUERY, db)
            .createExecutionPlan(new GqlExecutionContext(db));
        templateSource.close();
        assertThat(cache.contains(QUERY)).isTrue();
        assertThat(db.getActiveQueries()).isEmpty();
        try (var logs = LogRecordCollector.attachTo(db.getClass());
            var unopened = graph.traversal().gql(QUERY);
            var first = graph.traversal().gql(QUERY);
            var second = mixed ? graph.traversal().V().values("x") : graph.traversal().gql(QUERY)) {
          unopened.asAdmin().applyStrategies();
          assertThat(warnings(logs)).isEmpty();
          assertThat(db.getActiveQueries()).isEmpty();
          assertThat(first.hasNext()).isTrue();
          assertThat(second.hasNext()).isTrue();
          assertThat(db.getActiveQueries()).hasSize(2);
          assertThat(warnings(logs)).isEmpty();
          if (mixed) {
            try (var third = db.query("select 42 as answer")) {
              assertThat(third.hasNext()).isTrue();
              assertWarning(logs);
              var held = List.copyOf(db.getActiveQueries().values());
              assertThat(held).hasSize(3);
              endTransaction(commit);
              assertThat(active).isEmpty();
              held.forEach(RegisteredQuery::close);
            }
          } else {
            try (var third = graph.traversal().gql(QUERY)) {
              assertThat(third.hasNext()).isTrue();
              assertWarning(logs);
              var held = List.copyOf(db.getActiveQueries().values());
              assertThat(held).hasSize(3);
              endTransaction(commit);
              assertThat(active).isEmpty();
              held.forEach(RegisteredQuery::close);
            }
          }
          assertThat(unopened.asAdmin().getSteps()).anyMatch(CallStep.class::isInstance);
          assertThat(graph.tx().isOpen()).isFalse();
        }
      }
    }
  }

  /** Cache misses and hits yield distinct live plans that close without damaging the template. */
  @Test
  public void cachedAndUncachedInvocationsOwnIndependentPlansAtCommitAndRollback()
      throws Exception {
    seed();
    for (var commit : new boolean[] {true, false}) {
      var db = graphSession();
      var cache = GqlExecutionPlanCache.instance(db);
      cache.invalidate();
      var misses = cache.getMisses();
      var first = openReader();
      var hits = cache.getHits();
      var second = openReader();
      assertThat(cache.getMisses()).isGreaterThan(misses);
      assertThat(cache.getHits()).isGreaterThan(hits);
      var firstPlan = plan(first);
      var secondPlan = plan(second);
      assertThat(firstPlan).isNotSameAs(secondPlan);
      var active = db.getActiveQueries();
      var held = List.copyOf(active.values());
      assertThat(held).hasSize(2);
      assertThat(first.next()).isInstanceOf(Map.class);
      endTransaction(commit);
      assertThat(active).isEmpty();
      assertPlanClosed(firstPlan);
      assertPlanClosed(secondPlan);
      Assert.assertFalse(first.hasNext());
      Assert.assertFalse(second.hasNext());
      Assert.assertThrows(NoSuchElementException.class, first::next);
      first.close();
      second.close();
      held.forEach(RegisteredQuery::close);
      // A later invocation copies the same template into an independently usable execution.
      var later = openReader();
      assertThat(plan(later)).isNotSameAs(firstPlan).isNotSameAs(secondPlan);
      var count = 0;
      while (later.hasNext()) {
        assertThat(later.next()).isInstanceOf(Map.class);
        count++;
        if (count == 4) {
          assertThat(graphSession().getActiveQueries()).hasSize(1);
        }
      }
      assertThat(count).isEqualTo(4);
      assertThat(graphSession().getActiveQueries()).isEmpty();
      later.close();
      graph.tx().rollback();
    }
  }

  /** Direct readers stop immediately. Read-ahead and a barrier can drain already-pulled scalars. */
  @Test
  public void transactionEndStopsPlanReadsButAllowsTraversalBuffersToDrain() throws Exception {
    seed();
    for (var commit : new boolean[] {true, false}) {
      for (var mode = 0; mode < 3; mode++) {
        var db = graphSession();
        var statement = GqlPlanner.getStatement(QUERY, db);
        var observedStatement = mock(GqlStatement.class);
        var probes = new ArrayList<StreamProbe>();
        var plans = new ArrayList<GqlExecutionPlan>();
        when(observedStatement.createExecutionPlan(any())).thenAnswer(call -> {
          var live = spy(statement.createExecutionPlan(call.getArgument(0)));
          plans.add(live);
          doAnswer(start -> {
            var probe = new StreamProbe((GqlExecutionStream) start.callRealMethod());
            probes.add(probe);
            return probe;
          }).when(live).start(db);
          return live;
        });
        try (var planner = mockStatic(GqlPlanner.class, CALLS_REAL_METHODS);
            var traversal = mode == 2 ? graph.traversal().gql(QUERY).barrier(100)
                : graph.traversal().gql(QUERY)) {
          // Only observe plan creation and adapt payloads. The plan and stream remain real.
          planner.when(() -> GqlPlanner.getStatement(QUERY, db)).thenReturn(observedStatement);
          GqlResultIterator direct = null;
          if (mode == 0) {
            direct = openReader();
            assertThat(direct.next()).isEqualTo(0);
          } else {
            assertThat(traversal.hasNext()).isTrue();
          }
          assertThat(probes).hasSize(1);
          var probe = probes.getFirst();
          var reads = probe.reads;
          var active = db.getActiveQueries();
          var held = List.copyOf(active.values());
          endTransaction(commit);
          assertThat(active).isEmpty();
          assertThat(probe.closes).isEqualTo(1);
          assertPlanClosed(plans.getFirst());
          if (mode == 0) {
            assertThat(direct.hasNext()).isFalse();
            Assert.assertThrows(NoSuchElementException.class, direct::next);
            direct.close();
          } else {
            assertThat(traversal.next()).isEqualTo(0);
            assertThat(traversal.toList()).isEqualTo(mode == 2 ? List.of(1, 2, 3) : List.of());
            assertThat(traversal.hasNext()).isFalse();
          }
          held.forEach(RegisteredQuery::close);
          assertThat(probe.reads).isEqualTo(reads);
          verify(plans.getFirst(), times(1)).start(db);
          verify(plans.getFirst(), times(1)).close();
        }
      }
    }
  }

  /** A retired reader can close on a different thread without activating its captured session. */
  @Test
  public void lateCloseAfterTransactionEndDoesNotResolveAnotherGraphTransaction() throws Exception {
    seed();
    var reader = openReader();
    var db = graphSession();
    var active = db.getActiveQueries();
    var held = List.copyOf(active.values());
    assertThat(held).hasSize(1);
    graph.tx().rollback();
    assertThat(active).isEmpty();
    var executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(() -> {
        reader.close();
        held.forEach(RegisteredQuery::close);
        assertThat(reader.hasNext()).isFalse();
        assertThat(graph.tx().isOpen()).isFalse();
      }).get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(graph.tx().isOpen()).isFalse();
  }

  private void seed() {
    for (var i = 0; i < 4; i++) {
      graph.addVertex(T.label, "GqlOwned", "x", i);
    }
    graph.tx().commit();
  }

  private DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private GqlResultIterator openReader() {
    var traversal = graph.traversal().gql(QUERY).asAdmin();
    var ctx = new Service.ServiceCallContext(traversal, traversal.getStartStep());
    return (GqlResultIterator) new GqlService(QUERY, Map.of(), Service.Type.Start)
        .execute(ctx, Map.of());
  }

  private void endTransaction(boolean commit) {
    if (commit) {
      graph.tx().commit();
    } else {
      graph.tx().rollback();
    }
  }

  private static GqlExecutionPlan plan(GqlResultIterator reader) throws Exception {
    var field = GqlResultIterator.class.getDeclaredField("plan");
    field.setAccessible(true);
    return (GqlExecutionPlan) field.get(reader);
  }

  private static void assertPlanClosed(GqlExecutionPlan plan) throws Exception {
    var field = GqlExecutionPlan.class.getDeclaredField("sqlPlan");
    field.setAccessible(true);
    var sqlPlan = (InternalExecutionPlan) field.get(plan);
    assertThat(sqlPlan.getSteps()).isNotEmpty();
    var guard = AbstractExecutionStep.class.getDeclaredField("alreadyClosed");
    guard.setAccessible(true);
    for (var step : sqlPlan.getSteps()) {
      assertThat(guard.getBoolean(step)).as(step.getClass().getSimpleName()).isTrue();
    }
  }

  private static List<String> warnings(LogRecordCollector logs) {
    return logs.messages().stream().filter(message -> message.startsWith("WARNING ")).toList();
  }

  private static void assertWarning(LogRecordCollector logs) {
    assertThat(warnings(logs)).singleElement().asString()
        .contains("This database instance has 2 open command/query result sets");
  }

  private static final class StreamProbe implements GqlExecutionStream {

    private final GqlExecutionStream delegate;
    private int reads;
    private int closes;
    private int ordinal;

    private StreamProbe(GqlExecutionStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean hasNext() {
      reads++;
      return delegate.hasNext();
    }

    @Override
    public Object next() {
      reads++;
      delegate.next();
      // Row ordinals are scalar payloads, so buffer reads do not access vertices after tx end.
      return ordinal++;
    }

    @Override
    public void close() {
      closes++;
      delegate.close();
    }
  }
}
