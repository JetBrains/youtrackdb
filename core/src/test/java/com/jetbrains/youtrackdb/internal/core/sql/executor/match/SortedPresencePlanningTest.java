package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Real translator inputs connect sorted-slice promotion to the existing G5 and G7 policies. */
public class SortedPresencePlanningTest extends GraphBaseTest {

  /** Dense explicit sorts use G7 while sparse ordered indexes and absent data keep G5. */
  @Test
  public void sortedSlices_applyDensityOrTheFilterAbsentReference() throws Exception {
    for (var mode : List.of("Dense", "Sparse", "Fallback")) {
      seed(mode);
      session.begin();
      try {
        for (var direction : List.of(Order.asc, Order.desc)) {
          var query = graph.traversal().V().hasLabel("Source" + mode).has("selected", true)
              .out("Link" + mode).hasLabel("Target" + mode).values("k")
              .order().by(direction).range(1, 10);
          try (var planner = PresencePlanTestSupport.plan(query.asAdmin(), session)) {
            var target = planner.inputs.promotedPresenceKeys().keySet().iterator().next();
            assertThat(planner.inputs.promotedPresenceKeys().get(target)).containsExactly("k");
            var density = planner.presenceDensities().get(target);
            assertThat(density.candidateClass()).isEqualTo("Target" + mode);
            var fallback = !mode.equals("Dense");
            assertThat(density.applied()).as(mode + " " + density + " filters="
                + planner.inputs.aliasFilters() + " plan=" + planner.plan.prettyPrint(0, 2))
                .isEqualTo(!fallback);
            assertThat(planner.edges).hasSize(1);
            assertThat(planner.edges.getFirst().out).isTrue();
            var text = planner.plan.prettyPrint(0, 2);
            assertThat(text).contains(mode.equals("Sparse") ? "INDEX ORDERED MATCH" : "ORDER BY")
                .doesNotContain("FETCH FROM INDEX Target");
            System.out.println("SORTED PLAN " + mode + " " + direction + " " + density
                + " estimates=" + planner.estimates + "\n" + text);
            if (fallback) {
              var filters = new HashMap<>(planner.inputs.aliasFilters());
              filters.remove(target);
              var source = planner.inputs.aliasClasses().keySet().stream()
                  .filter(alias -> !alias.equals(target)).findFirst().orElseThrow();
              var statement =
                  (com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement) com.jetbrains.youtrackdb.internal.core.sql.SQLEngine
                      .parse(
                          "MATCH {as:s,where:(selected = true)} RETURN s", session);
              filters.put(source,
                  statement.getMatchExpressions().getFirst().getOrigin().getFilter());
              var reference = MatchExecutionPlanner.estimateRootEntries(
                  planner.inputs.aliasClasses(), Map.of(), filters,
                  new BasicCommandContext(session));
              assertThat(planner.estimates).isEqualTo(reference);
              assertThat(reference.get(source)).isGreaterThan(250L).isLessThan(501L);
            }
          }
        }
      } finally {
        session.rollback();
      }
    }
  }

  /** Bare LINK inference must retain the pre-rewrite V density record on end-to-end sorted inputs. */
  @Test
  public void sortedIndexedHop_keepsOriginalDensityClassAndSource() throws Exception {
    seed("Sparse");
    session.begin();
    var query = graph.traversal().V().hasLabel("SourceSparse").has("selected", true)
        .out("LinkSparse").values("k").order().limit(10);
    try (var reads = org.mockito.Mockito.mockStatic(PresenceDensity.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);
        var planner = PresencePlanTestSupport.plan(query.asAdmin(), session)) {
      var target = planner.inputs.promotedPresenceKeys().keySet().iterator().next();
      assertThat(planner.inputs.aliasClasses()).containsEntry(target, "V");
      var density = planner.presenceDensities().get(target);
      assertThat(density.candidateClass()).isEqualTo("V");
      assertThat(density.source()).isEqualTo(PresenceDensity.Source.NO_DECLARATION);
      assertThat(density.applied()).isFalse();
      var classesField = MatchExecutionPlanner.class.getDeclaredField("aliasClasses");
      classesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var classes = (Map<String, String>) classesField.get(planner);
      assertThat(classes).containsEntry(target, "TargetSparse");
      reads.verify(() -> PresenceDensity.read(target, "V", Set.of("k"), session));
      reads.verify(() -> PresenceDensity.read(target, "TargetSparse", Set.of("k"), session),
          org.mockito.Mockito.never());
      assertThat(planner.plan.prettyPrint(0, 2)).contains("INDEX ORDERED MATCH");
    } finally {
      session.rollback();
    }
  }

  /** Bound pre-sort MATCH reads with identical roots and compare the sorted survivor slices. */
  @Test
  public void sortedExecution_boundsReadsForG5AndBothG7Roots() throws Exception {
    for (var mode : List.of("Fallback", "Dense", "Sparse")) {
      seed(mode);
      session.begin();
      try {
        for (var selected : List.of(true, false)) {
          var query = graph.traversal().V().hasLabel("Source" + mode).has("selected", selected)
              .out("Link" + mode).hasLabel("Target" + mode).values("k").order().range(1, 10);
          try (var translated = PresencePlanTestSupport.plan(query.asAdmin(), session)) {
            var target = translated.inputs.promotedPresenceKeys().keySet().iterator().next();
            var inputs = translated.inputs;
            if (mode.equals("Sparse")) {
              // The emitted ORDER BY selects index order (G5). Explicit ci collation disables
              // that path to reach G7's reverse full-sort root. It does not change integer order.
              var statement =
                  (com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement) com.jetbrains.youtrackdb.internal.core.sql.SQLEngine
                      .parse("MATCH {as:s} RETURN s ORDER BY `" + target + "`.k COLLATE ci",
                          session);
              inputs = inputs.toBuilder().orderBy(statement.getOrderBy()).build();
            }
            var parameters =
                ((com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan) translated.plan)
                    .getContext().getInputParameters();
            var actual = executionReads(inputs, target, null, parameters);
            assertThat(actual.applied()).isEqualTo(!mode.equals("Fallback"));
            assertThat(actual.forward()).isEqualTo(!mode.equals("Sparse"));
            var filters = new HashMap<>(inputs.aliasFilters());
            filters.remove(target);
            var reference = inputs.toBuilder().aliasFilters(filters)
                .promotedPresenceKeys(Map.of()).skip(null).limit(null).build();
            var baseline = executionReads(reference, target, actual.forward(), parameters);
            assertThat(actual.values()).isEqualTo(baseline.values());
            assertThat(actual.reads()).isPositive().isLessThanOrEqualTo(baseline.reads());
            if (mode.equals("Dense")) {
              // Every target has k. Promotion must add no execution reads to the same scan.
              assertThat(actual.reads()).isEqualTo(baseline.reads());
            } else if (mode.equals("Sparse")) {
              // Only 10/500 targets have k. A filter after the reverse hop would do the full
              // reference scan and must fail even if it returns the same survivor slice.
              assertThat(actual.reads()).isLessThan(baseline.reads() / 2);
            }
            System.out.println("SORTED READ BOUND " + mode + " selected=" + selected
                + " actual=" + actual + " reference=" + baseline);
          }
        }
      } finally {
        session.rollback();
      }
    }
  }

  private record Work(long planningReads, long reads, boolean applied, boolean forward,
      List<Integer> values) {
  }

  private Work executionReads(MatchPlanInputs inputs, String target, Boolean forcedForward,
      Map<Object, Object> parameters) throws Exception {
    var observed = countingSession();
    observed.begin();
    var context = new BasicCommandContext(observed);
    context.setInputParameters(parameters);
    var direction = new boolean[1];
    var planner = new MatchExecutionPlanner(inputs) {
      @Override
      void observeRootPlanning(Map<String, Long> estimates, Set<String> prefetch,
          List<EdgeTraversal> edges) {
        if (forcedForward != null) {
          var edge = edges.getFirst().copy();
          edge.out = forcedForward;
          edges.set(0, edge);
        }
        direction[0] = edges.getFirst().out;
      }
    };
    var plan = planner.createExecutionPlan(context, false, false);
    long planning = observed.reads;
    observed.reads = 0;
    var values = new java.util.ArrayList<Integer>();
    try {
      // Plan with the sorted inputs so G5/G7 see the real sort requirement, but drain only
      // the prefix before ORDER BY on both sides. Sort reloads and consumer projection reads
      // are not filter-placement work and must not inflate the survivor-reference bound.
      var steps = plan.getSteps();
      var sort = steps.stream()
          .filter(com.jetbrains.youtrackdb.internal.core.sql.executor.OrderByStep.class::isInstance)
          .findFirst().orElseThrow();
      var prefix = (com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal) steps
          .get(steps.indexOf(sort) - 1);
      var rows = new java.util.ArrayList<com.jetbrains.youtrackdb.internal.core.query.Result>();
      var stream = prefix.start(context);
      try {
        while (stream.hasNext(context)) {
          rows.add(stream.next(context));
        }
      } finally {
        stream.close(context);
      }
      long execution = observed.reads;
      for (var row : rows) {
        var id = (com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable) row
            .getProperty(target);
        var entity = observed.loadEntity(id.getIdentity());
        if (entity.hasProperty("k")) {
          values.add(entity.getProperty("k"));
        }
      }
      // Apply the same survivor projection, integer sort and range(1,10) outside both counters.
      values.sort(Integer::compareTo);
      values = new java.util.ArrayList<>(values.subList(Math.min(1, values.size()),
          Math.min(10, values.size())));
      return new Work(planning, execution,
          planner.presenceDensities().containsKey(target)
              && planner.presenceDensities().get(target).applied(),
          direction[0], values);
    } finally {
      plan.close();
      observed.close();
      session.activateOnCurrentThread();
    }
  }

  /** Copy the session lifecycle while observing the same logical-read seam as projection tests. */
  private CountingSession countingSession() throws Exception {
    var storage = session.getStorage();
    var shared = session.getSharedContext();
    var user = session.getCurrentUser();
    var username = user == null ? null : user.getName(session);
    var configField =
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.class
            .getDeclaredField("config");
    configField.setAccessible(true);
    var config =
        (com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl) configField.get(session);
    storage.open(session, null, null, session.getConfiguration());
    var observed = new CountingSession(storage);
    observed.init(config, shared);
    observed.internalOpen(username, null, false);
    return observed;
  }

  private static final class CountingSession
      extends com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded {
    long reads;

    CountingSession(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage storage) {
      super(storage, false);
    }

    @Override
    public com.jetbrains.youtrackdb.internal.core.record.RecordAbstract executeReadRecord(
        com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal rid,
        com.jetbrains.youtrackdb.internal.core.storage.RawBuffer prefetchedBuffer,
        boolean throwExceptionIfRecordNotFound) {
      reads++;
      return super.executeReadRecord(rid, prefetchedBuffer, throwExceptionIfRecordNotFound);
    }
  }

  private void seed(String mode) {
    var source = session.createVertexClass("Source" + mode);
    source.createProperty("selected", PropertyType.BOOLEAN)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var target = session.createVertexClass("Target" + mode);
    var property = target.createProperty("k", PropertyType.INTEGER);
    if (mode.equals("Dense")) {
      property.setMandatory(true);
    } else if (mode.equals("Sparse")) {
      property.setNotNull(true);
      property.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    }
    var link = session.createEdgeClass("Link" + mode);
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(target);
    for (int i = 0; i < 500; i++) {
      var vertex = graph.addVertex(T.label, "Target" + mode);
      if (mode.equals("Dense") || (mode.equals("Fallback") ? i % 4 == 0 : i < 10)) {
        vertex.property("k", i);
      }
      for (int j = 0; j < 2; j++) {
        graph.addVertex(T.label, "Source" + mode, "selected", i < 225)
            .addEdge("Link" + mode, vertex);
      }
    }
    graph.tx().commit();
    session.getSharedContext().getIndexManager().getIndex("Source" + mode + ".selected")
        .rebuild(session);
  }
}
