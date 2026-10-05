package com.jetbrains.youtrackdb.internal.core.gremlin.translator;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinPlanCache;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Real graph execution ownership. Global warning configuration requires sequential execution. */
@Category(SequentialTest.class)
public class TranslatedMatchSessionTest extends GraphBaseTest {

  private Object previousThreshold;

  @Override
  public void beforeTest() throws Exception {
    previousThreshold = GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.getValue();
    // Set this before even the database context exists, not only before the first query.
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

  /** Three strongly held single, union, or mixed YQL/MATCH executions emit the existing warning. */
  @Test
  public void warningsCountExecutionsButNotUnopenedTraversalsOrCachedTemplates() throws Exception {
    seed();
    for (var kind = 0; kind < 3; kind++) {
      var db = graphSession();
      var cache = GremlinPlanCache.instance(db);
      cache.invalidate();
      try (var logs = LogRecordCollector.attachTo(db.getClass());
          var first = scalarTraversal();
          var unopened = scalarTraversal()) {
        var firstStep = boundary(first);
        var unopenedStep = boundary(unopened);
        assertThat(firstStep).isInstanceOf(YTDBMatchPlanStep.class);
        assertThat(unopenedStep).isInstanceOf(YTDBMatchPlanStep.class);
        var template = ((YTDBMatchPlanStep<?, ?>) firstStep).getPlan();
        assertThat(((YTDBMatchPlanStep<?, ?>) unopenedStep).getPlan()).isSameAs(template);
        assertThat(cache.getTranslationHits()).isPositive();
        assertThat(db.getActiveQueries()).isEmpty();
        assertThat(warnings(logs)).isEmpty();
        try (var second = kind == 1 ? unionTraversal() : scalarTraversal();
            var third = kind == 1 ? unionTraversal() : scalarTraversal()) {
          var secondStep = boundary(second);
          var thirdStep = boundary(third);
          if (kind == 1) {
            assertThat(secondStep).isInstanceOf(MultiPlanMatchStep.class);
            assertThat(thirdStep).isInstanceOf(MultiPlanMatchStep.class);
          }
          first.hasNext();
          assertThat(((YTDBMatchPlanStep<?, ?>) firstStep).getPlan()).isNotSameAs(template);
          if (kind == 2) {
            try (var yql = db.query("select 42 as answer")) {
              assertThat(db.getActiveQueries()).hasSize(2);
              assertThat(warnings(logs)).isEmpty();
              assertThat(third.hasNext()).isTrue();
              assertWarning(logs);
              assertThat(db.getActiveQueries()).hasSize(3);
              assertThat(yql.hasNext()).isTrue();
            }
          } else {
            assertThat(second.hasNext()).isTrue();
            assertThat(db.getActiveQueries()).hasSize(2);
            assertThat(warnings(logs)).isEmpty();
            assertThat(third.hasNext()).isTrue();
            assertWarning(logs);
            assertThat(db.getActiveQueries()).hasSize(3);
          }
        }
      } finally {
        graph.tx().rollback();
      }
    }
  }

  /** Commit and rollback close the actual stream and every union child, including lazy children. */
  @Test
  public void transactionEndClosesSingleAndUnionResourcesAndLeavesShortScalarResults()
      throws Exception {
    seed();
    for (var commit : new boolean[] {true, false}) {
      for (var union : new boolean[] {false, true}) {
        try (var traversal = union ? unionTraversal() : scalarTraversal()) {
          var step = boundary(traversal);
          assertThat(step).isInstanceOf(union ? MultiPlanMatchStep.class : YTDBMatchPlanStep.class);
          var probe = armWithProbe(step);
          assertThat(traversal.next()).isIn(0, 1, 2, 3);
          var plans = plans(step);
          assertPlansClosed(plans, false);
          var active = graphSession().getActiveQueries();
          var handles = List.copyOf(active.values()); // Retain weak registry values strongly.
          assertThat(handles).hasSize(1);
          var reads = probe.reads;
          endTransaction(commit);
          assertThat(active).isEmpty();
          assertThat(probe.closes).isEqualTo(1);
          assertPlansClosed(plans, true);
          assertThat(traversal.toList()).hasSizeLessThan(4);
          assertThat(traversal.hasNext()).isFalse();
          assertThat(probe.reads).isEqualTo(reads);
          assertThat(probe.closes).isEqualTo(1);
        }
      }
    }
  }

  /** The direct reader exhausts, while hasNext read-ahead and a downstream barrier retain scalars. */
  @Test
  public void endedExecutionDoesNotReadAgainButTraversalBuffersCanDrain() throws Exception {
    seed();
    for (var mode = 0; mode < 3; mode++) {
      try (var compiled = scalarTraversal()) {
        var step = boundary(compiled);
        Traversal.Admin<?, ?> reader = compiled;
        if (mode == 2) {
          // Keep the barrier downstream of MATCH, rather than letting the translator absorb it.
          var pipeline = new DefaultTraversal<>(graph);
          pipeline.setStrategies(new DefaultTraversalStrategies());
          pipeline.addStep(step);
          pipeline.addStep(new NoOpBarrierStep<>(pipeline, 100));
          reader = pipeline;
          assertThat(boundary(reader)).isSameAs(step);
        }
        var probe = armWithProbe(step);
        if (mode == 0) {
          Object scalar = step.next().get();
          assertThat(scalar).isIn(0, 1, 2, 3);
        } else {
          assertThat(reader.hasNext()).isTrue();
        }
        var reads = probe.reads;
        graph.tx().commit();
        assertThat(probe.closes).isEqualTo(1);
        assertPlansClosed(plans(step), true);
        if (mode == 0) {
          assertThat(step.hasNext()).isFalse();
        } else {
          assertThat(reader.next()).isIn(0, 1, 2, 3);
          var buffered = reader.toList();
          assertThat(buffered).hasSize(mode == 2 ? 3 : 0);
          assertThat(reader.hasNext()).isFalse();
        }
        assertThat(probe.reads).isEqualTo(reads);
        reader.close();
      }
    }
  }

  /** An indexed MATCH execution owns a real index cursor and closes its plan at either tx end. */
  @Test
  public void indexedMatchPlanClosesAtCommitAndRollback() throws Exception {
    var db = graphSession();
    db.createVertexClass("Indexed").createProperty("x", PropertyType.INTEGER)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    for (var i = 0; i < 200; i++) {
      graph.addVertex(T.label, "Indexed", "x", 7);
    }
    graph.tx().commit();
    for (var commit : new boolean[] {true, false}) {
      try (var traversal = graph.traversal().V().hasLabel("Indexed").has("x", 7)
          .values("x").asAdmin()) {
        var step = (YTDBMatchPlanStep<?, ?>) boundary(traversal);
        var probe = armWithProbe(step);
        assertThat(step.getPlan().getSteps().stream().flatMap(s -> s.getSubSteps().stream()))
            .anyMatch(FetchFromIndexStep.class::isInstance);
        assertThat(traversal.next()).isEqualTo(7);
        var active = graphSession().getActiveQueries();
        assertThat(active).hasSize(1);
        endTransaction(commit);
        assertThat(active).isEmpty();
        assertPlansClosed(plans(step), true);
        assertThat(probe.closes).isEqualTo(1);
        var reads = probe.reads;
        traversal.toList();
        assertThat(probe.reads).isEqualTo(reads);
        assertThat(traversal.hasNext()).isFalse();
      }
    }
  }

  /** Compilation does not own an execution. Iteration binds to the other thread's real session. */
  @Test
  public void compileAndIterateOnDifferentThreadsThenCloseRetiredExecutionOnCompileThread()
      throws Exception {
    seed();
    var traversal = scalarTraversal();
    var step = (YTDBMatchPlanStep<?, ?>) boundary(traversal);
    var compileSession = graphSession();
    assertThat(compileSession.getActiveQueries()).isEmpty();
    graph.tx().commit();
    var executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(() -> {
        try {
          var iterationSession = graphSession();
          assertThat(iterationSession).isNotSameAs(compileSession);
          var probe = armWithProbe(step);
          assertThat(traversal.next()).isIn(0, 1, 2, 3);
          assertThat(step.getPlan().getContext().getDatabaseSession()).isSameAs(iterationSession);
          var active = iterationSession.getActiveQueries();
          assertThat(active).hasSize(1);
          graph.tx().rollback();
          assertThat(active).isEmpty();
          assertThat(probe.closes).isEqualTo(1);
          assertPlansClosed(plans(step), true);
        } catch (Exception failure) {
          throw new AssertionError(failure);
        } finally {
          if (graph.tx().isOpen()) {
            graph.tx().rollback();
          }
        }
      }).get(30, TimeUnit.SECONDS);
      // The captured session is not activated here. A registry access would fail its assertion.
      traversal.close();
      assertThat(traversal.hasNext()).isFalse();
      assertThat(graph.tx().isOpen()).isFalse();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      traversal.close();
    }
  }

  /** A reset before commit preserves a requested real execution and replaces the closed plan. */
  @Test
  public void nextResetCommitReadUsesFreshPlanAndRegistration() throws Exception {
    seed();
    try (var traversal = scalarTraversal()) {
      var step = (YTDBMatchPlanStep<?, ?>) boundary(traversal);
      traversal.next();
      var oldPlan = step.getPlan();
      var oldQueries = graphSession().getActiveQueries();
      var oldHandle = oldQueries.values().iterator().next();
      traversal.reset();
      graph.tx().commit();
      assertThat(oldQueries).isEmpty();
      assertPlansClosed(List.of(oldPlan), true);
      assertThat(traversal.next()).isIn(0, 1, 2, 3);
      assertThat(step.getPlan()).isNotSameAs(oldPlan);
      assertThat(graphSession().getActiveQueries().values()).hasSize(1).doesNotContain(oldHandle);
      assertThat(traversal.toList()).hasSize(3);
      graph.tx().commit();
    }
  }

  private void seed() {
    for (var i = 0; i < 4; i++) {
      graph.addVertex(T.label, "Person", "x", i);
    }
    graph.tx().commit();
  }

  private DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private Traversal.Admin<?, ?> scalarTraversal() {
    return graph.traversal().V().values("x").asAdmin();
  }

  private Traversal.Admin<?, ?> unionTraversal() {
    return graph.traversal().V().union(__.values("x"), __.values("x")).asAdmin();
  }

  private static AbstractMatchPlanStep<?, ?> boundary(Traversal.Admin<?, ?> traversal) {
    traversal.applyStrategies();
    var boundaries = traversal.getSteps().stream()
        .filter(AbstractMatchPlanStep.class::isInstance).toList();
    assertThat(boundaries).as("fixture must really translate to one MATCH boundary").hasSize(1);
    return (AbstractMatchPlanStep<?, ?>) boundaries.getFirst();
  }

  private void endTransaction(boolean commit) {
    if (commit) {
      graph.tx().commit();
    } else {
      graph.tx().rollback();
    }
  }

  private static List<InternalExecutionPlan> plans(AbstractMatchPlanStep<?, ?> step) {
    return step instanceof YTDBMatchPlanStep<?, ?> single ? List.of(single.getPlan())
        : ((MultiPlanMatchStep<?, ?>) step).getPlans();
  }

  private static void assertPlansClosed(List<InternalExecutionPlan> plans, boolean closed)
      throws Exception {
    var guard = AbstractExecutionStep.class.getDeclaredField("alreadyClosed");
    guard.setAccessible(true);
    for (var plan : plans) {
      assertThat(plan.getSteps()).isNotEmpty();
      for (var step : plan.getSteps()) {
        assertThat(guard.getBoolean(step)).as(step.getClass().getSimpleName()).isEqualTo(closed);
      }
    }
  }

  private static List<String> warnings(LogRecordCollector logs) {
    return logs.messages().stream().filter(message -> message.startsWith("WARNING ")).toList();
  }

  private static void assertWarning(LogRecordCollector logs) {
    assertThat(warnings(logs)).singleElement().asString()
        .contains("This database instance has 2 open command/query result sets",
            "please make sure you close them with ResultSet.close()");
  }

  private static StreamProbe armWithProbe(AbstractMatchPlanStep<?, ?> step) throws Exception {
    // Open the real plan before projection captures the stream. No plan or session is mocked.
    var open = AbstractMatchPlanStep.class.getDeclaredMethod("openArming");
    open.setAccessible(true);
    open.invoke(step);
    var field = AbstractMatchPlanStep.class.getDeclaredField("openStream");
    field.setAccessible(true);
    var probe = new StreamProbe((ExecutionStream) field.get(step));
    field.set(step, probe);
    return probe;
  }

  private static final class StreamProbe implements ExecutionStream {

    private final ExecutionStream delegate;
    private int reads;
    private int closes;

    private StreamProbe(ExecutionStream delegate) {
      this.delegate = delegate;
      assertThat(delegate).isNotNull();
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      reads++;
      return delegate.hasNext(ctx);
    }

    @Override
    public Result next(CommandContext ctx) {
      reads++;
      return delegate.next(ctx);
    }

    @Override
    public void close(CommandContext ctx) {
      closes++;
      delegate.close(ctx);
    }
  }
}
