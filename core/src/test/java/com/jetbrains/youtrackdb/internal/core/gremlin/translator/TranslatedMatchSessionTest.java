package com.jetbrains.youtrackdb.internal.core.gremlin.translator;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHook;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinPlanCache;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Edge;
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

  /** A real hash anti-join rewinds in place after a partial read without stale hash state. */
  @Test
  public void hashAntiJoinPartialReadResetRead() throws Exception {
    var savedMinimum = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    var savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10000L);
      graphSession().createVertexClass("RestartPerson");
      var a = graph.addVertex(T.label, "RestartPerson", "name", "A");
      var b = graph.addVertex(T.label, "RestartPerson", "name", "B");
      graph.addVertex(T.label, "RestartPerson", "name", "C");
      a.addEdge("knows", b);
      graph.tx().commit();
      try (var traversal = graph.traversal().V().hasLabel("RestartPerson")
          .not(__.out("knows")).asAdmin()) {
        var step = (YTDBMatchPlanStep<?, ?>) boundary(traversal);
        assertThat(step.getPlan().prettyPrint(0, 2)).contains("HASH ANTI_JOIN");
        assertThat(traversal.next().<String>value("name")).isIn("B", "C");
        var openPlan = step.getPlan();
        traversal.reset();
        assertThat(traversal.toList().stream().map(v -> v.value("name")).toList())
            .containsExactlyInAnyOrder("B", "C");
        assertThat(step.getPlan()).isSameAs(openPlan);
      }
    } finally {
      graph.tx().rollback();
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(savedMinimum);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
    }
  }

  /** Flat translated NOT keeps hash selection for IN predicates outside the tree whitelist. */
  @Test
  public void flatNotWithWithinPredicateKeepsHashSelection() throws Exception {
    var savedMinimum = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    var savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10000L);
      graphSession().createVertexClass("FlatPerson");
      var a = graph.addVertex(T.label, "FlatPerson", "name", "A", "age", 30);
      var b = graph.addVertex(T.label, "FlatPerson", "name", "B", "age", 40);
      a.addEdge("knows", b);
      graph.tx().commit();
      try (var traversal = graph.traversal().V().hasLabel("FlatPerson")
          .not(__.out("knows").has("age", org.apache.tinkerpop.gremlin.process.traversal.P
              .within(30, 40)))
          .asAdmin()) {
        var step = (YTDBMatchPlanStep<?, ?>) boundary(traversal);
        assertThat(step.getPlan().prettyPrint(0, 2)).contains("HASH ANTI_JOIN");
        assertThat(traversal.toList().stream().map(v -> v.value("name")).toList())
            .containsExactly("B");
      }
    } finally {
      graph.tx().rollback();
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(savedMinimum);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
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

  /** Vertex projection consumes the checked record in a MATCH row without another READ hook. */
  @Test
  public void vertexBoundaryReusesCheckedRowAndMatchesNativeResults() throws Exception {
    seed();
    var db = graphSession();
    var reads = new ArrayList<DBRecord>();
    RecordHook hook = new RecordHook() {
      @Override
      public void onUnregister() {
      }

      @Override
      public void onTrigger(TYPE type, DBRecord record) {
        if (type == TYPE.READ) {
          reads.add(record);
        }
      }
    };
    db.registerHook(hook);
    try (var traversal = graph.traversal().V().hasLabel("Person").asAdmin()) {
      var step = boundary(traversal);
      var probe = armWithProbe(step);
      var first = traversal.next();
      var matched = probe.lastRow;
      var alias = matched.getPropertyNames().getFirst();
      int before = reads.size();
      assertThat(matched.getVertex(alias)).isSameAs(((YTDBElementImpl) first).getRawEntity());
      assertThat(reads).hasSize(before).contains(((YTDBElementImpl) first).getRawEntity());
      assertThat((Object) matched.getProperty(alias)).isInstanceOf(
          com.jetbrains.youtrackdb.internal.core.db.record.record.RID.class);
      var translated = new ArrayList<>(traversal.toList());
      translated.add(first);
      assertThat(translated).hasSize(4);
      // MATCH prefetch may read all candidates before the first row. Each is read only once.
      assertThat(reads).hasSize(4);
      for (var element : translated) {
        assertThat(reads).anySatisfy(
            record -> assertThat(((YTDBElementImpl) element).getRawEntity()).isSameAs(record));
      }
      db.getConfiguration().setValue(
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
      try (var nativeTraversal = graph.traversal().V().hasLabel("Person").asAdmin()) {
        nativeTraversal.applyStrategies();
        assertThat(nativeTraversal.getSteps()).noneMatch(AbstractMatchPlanStep.class::isInstance);
        assertThat(nativeTraversal.toList()).hasSize(4).containsAll(translated);
      } finally {
        db.getConfiguration().setValue(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
      }
    } finally {
      db.unregisterHook(hook);
      graph.tx().rollback();
    }
  }

  /** A filtered hop retains the checked start alias when the filter rewrites the same RID. */
  @Test
  public void filteredHopBoundaryReusesStartAlias() throws Exception {
    var from = graph.addVertex(T.label, "Person", "x", 1);
    var to = graph.addVertex(T.label, "Person", "x", 2);
    from.addEdge("Knows", to);
    graph.tx().commit();
    var db = graphSession();
    var reads = new ArrayList<DBRecord>();
    var hook = observeReads(db, reads);
    try (var traversal = graph.traversal().V().hasLabel("Person").as("a")
        .out("Knows").hasLabel("Person").select("a").asAdmin()) {
      var step = boundary(traversal);
      var probe = armWithProbe(step);
      probe.recordReads = reads;
      var first = (YTDBElementImpl) traversal.next();
      var row = (ResultInternal) probe.lastRow;
      var alias = row.getPropertyNames().getFirst();
      assertThat(row.getMatchedRecord(alias)).isNotNull();
      var retained = row.getMatchedRecord(alias).reusableIn(db);
      int before = reads.size();
      assertThat(first.getRawEntity()).isSameAs(retained);
      assertThat(row.getVertex(alias)).isSameAs(retained);
      assertThat(reads).hasSize(before);
      // Graph-hop execution can read the start record again. Projection must add no read.
      assertThat(reads).hasSize(probe.readsBeforeProjection).contains(retained);
      assertThat(traversal.hasNext()).isFalse();
    } finally {
      db.unregisterHook(hook);
      graph.tx().rollback();
    }
  }

  /** A real RID-valued edge row crosses the boundary without losing its checked record. */
  @Test
  public void edgeBoundaryReusesCheckedRow() throws Exception {
    var from = graph.addVertex(T.label, "Person");
    var to = graph.addVertex(T.label, "Person");
    from.addEdge("Knows", to, "x", 7);
    graph.tx().commit();
    var db = graphSession();
    var reads = new ArrayList<DBRecord>();
    var hook = observeReads(db, reads);
    // A root edge scan provides checked evidence. Identifier-only hop edges stay lazy.
    try (var traversal = new DefaultTraversal<Object, Edge>(graph)) {
      traversal.setStrategies(new DefaultTraversalStrategies());
      var ctx = new BasicCommandContext();
      ctx.setDatabaseSession(db);
      var sql = "MATCH {class:Knows, as:e} RETURN e";
      var statement = (SQLMatchStatement) new YouTrackDBSql(
          new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8))).parse();
      var step = new YTDBMatchPlanStep<>(traversal, Edge.class,
          statement.createExecutionPlan(ctx), "e", BoundaryOutputType.ELEMENT);
      traversal.addStep(step);
      assertThat(boundary(traversal)).isSameAs(step);
      var probe = armWithProbe(step);
      probe.recordReads = reads;
      var first = (YTDBElementImpl) traversal.next();
      var row = (ResultInternal) probe.lastRow;
      var alias = row.getPropertyNames().getFirst();
      assertThat((Object) row.getProperty(alias)).isInstanceOf(
          com.jetbrains.youtrackdb.internal.core.db.record.record.RID.class);
      assertThat(row.getMatchedRecord(alias)).isNotNull();
      var retained = row.getMatchedRecord(alias).reusableIn(db);
      int before = reads.size();
      assertThat(first.getRawEntity()).isSameAs(retained);
      assertThat(row.getEdge(alias)).isSameAs(retained);
      assertThat(reads).hasSize(before);
      assertThat(reads).hasSize(probe.readsBeforeProjection).contains(retained);
      assertThat(traversal.hasNext()).isFalse();
    } finally {
      db.unregisterHook(hook);
      graph.tx().rollback();
    }
  }

  /** Concurrent open rows prove reuse and ownership in each session from one shared template. */
  @Test
  public void concurrentCachedMatchExecutionsDoNotShareRecords() throws Exception {
    seed();
    try (var warm = graph.traversal().V().hasLabel("Person").asAdmin()) {
      boundary(warm);
    }
    graph.tx().commit();
    var executor = Executors.newFixedThreadPool(2);
    var barrier = new CyclicBarrier(2);
    try {
      var task = (java.util.concurrent.Callable<Object[]>) () -> {
        var db = graphSession();
        var reads = new ArrayList<DBRecord>();
        var hook = observeReads(db, reads);
        try (var traversal = graph.traversal().V().hasLabel("Person").asAdmin()) {
          var step = (YTDBMatchPlanStep<?, ?>) boundary(traversal);
          var template = step.getPlan();
          var probe = armWithProbe(step);
          var first = (YTDBElementImpl) traversal.next();
          var row = (ResultInternal) probe.lastRow;
          var alias = row.getPropertyNames().getFirst();
          var evidence = row.getMatchedRecord(alias);
          assertThat(evidence).isNotNull();
          assertThat(evidence.session()).isSameAs(db);
          assertThat(evidence.transaction()).isSameAs(db.getActiveTransaction());
          // Both rows and their executions remain live before either thread consumes more rows.
          barrier.await(30, TimeUnit.SECONDS);
          int before = reads.size();
          var record = evidence.reusableIn(db);
          assertThat(record).isNotNull();
          assertThat(record.isNotBound(db)).isFalse();
          assertThat(first.getRawEntity()).isSameAs(record);
          assertThat(row.getVertex(alias)).isSameAs(record);
          assertThat(reads).hasSize(before);
          assertThat(reads.stream().filter(r -> r == record).count()).isEqualTo(1);
          assertThat(traversal.toList()).hasSize(3);
          assertThat(reads).hasSize(4);
          return new Object[] {template, record, db, row};
        } finally {
          db.unregisterHook(hook);
          graph.tx().rollback();
        }
      };
      var first = executor.submit(task);
      var second = executor.submit(task);
      var a = first.get(30, TimeUnit.SECONDS);
      var b = second.get(30, TimeUnit.SECONDS);
      assertThat(a[0]).isSameAs(b[0]);
      assertThat(a[1]).isNotSameAs(b[1]);
      assertThat(a[2]).isNotSameAs(b[2]);
      assertThat(a[3]).isNotSameAs(b[3]);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static RecordHook observeReads(DatabaseSessionEmbedded db, List<DBRecord> reads) {
    RecordHook hook = new RecordHook() {
      @Override
      public void onUnregister() {
      }

      @Override
      public void onTrigger(TYPE type, DBRecord record) {
        if (type == TYPE.READ) {
          reads.add(record);
        }
      }
    };
    db.registerHook(hook);
    return hook;
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
    private Result lastRow;
    private List<DBRecord> recordReads;
    private int readsBeforeProjection;

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
      lastRow = delegate.next(ctx);
      if (recordReads != null) {
        readsBeforeProjection = recordReads.size();
      }
      return lastRow;
    }

    @Override
    public void close(CommandContext ctx) {
      closes++;
      delegate.close(ctx);
    }
  }
}
