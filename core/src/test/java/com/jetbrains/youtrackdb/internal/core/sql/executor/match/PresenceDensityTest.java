package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Population evidence and work-cost checks do not authorize an execution access path. */
public class PresenceDensityTest extends GraphBaseTest {

  /** Mandatory presence includes null and requires no property index or not-null constraint. */
  @Test
  public void mandatoryNullable_isFullDensityAndRetainsNulls() {
    var clazz = session.createVertexClass("Mandatory");
    clazz.createProperty("k", PropertyType.INTEGER).setMandatory(true);
    graph.addVertex(T.label, "Mandatory", "k", 1);
    graph.addVertex(T.label, "Mandatory", "k", null);
    graph.tx().commit();
    session.begin();
    var density = read("Mandatory");
    assertThat(density.source()).isEqualTo(PresenceDensity.Source.MANDATORY);
    assertThat(density.density()).isEqualTo(1);
    assertThat(density.applied()).isFalse();
    assertThat(graph.traversal().V().hasLabel("Mandatory").values("k").toList())
        .containsExactlyInAnyOrder(1, null);
    session.rollback();
  }

  /** A non-null scalar index counts records, including duplicate keys, not distinct keys. */
  @Test
  public void scalarStatistics_countDuplicateRecordsAndCommittedPopulation() {
    var clazz = session.createVertexClass("Indexed");
    clazz.createProperty("k", PropertyType.INTEGER).setNotNull(true);
    clazz.getProperty("k").createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    for (int i = 0; i < 1000; i++) {
      var target = graph.addVertex(T.label, "Indexed");
      if (i < 100) {
        target.property("k", 7);
      }
    }
    graph.tx().commit();
    session.begin();
    var committed = read("Indexed");
    assertThat(committed.source()).isEqualTo(PresenceDensity.Source.INDEX);
    assertThat(committed.candidates()).isEqualTo(1000);
    assertThat(committed.density()).isEqualTo(0.1);
    for (int i = 0; i < 100; i++) {
      graph.addVertex(T.label, "Indexed");
    }
    graph.traversal().V().hasLabel("Indexed").has("k").next().remove();
    assertThat(read("Indexed")).isEqualTo(committed);
    session.rollback();
  }

  /** Nullable non-mandatory data cannot use non-null index counters as presence counts. */
  @Test
  public void nullableAndMissingDeclarations_fallBack() {
    var clazz = session.createVertexClass("Nullable");
    clazz.createProperty("k", PropertyType.INTEGER).createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    graph.addVertex(T.label, "Nullable", "k", null);
    graph.tx().commit();
    session.begin();
    assertThat(read("Nullable").source()).isEqualTo(PresenceDensity.Source.NO_DECLARATION);
    assertThat(PresenceDensity.read("t", null, Set.of("k"), session).source())
        .isEqualTo(PresenceDensity.Source.NO_CLASS);
    assertThat(PresenceDensity.read("t", "Nullable", Set.of("k", "other"), session).useful())
        .isFalse();
    session.rollback();
  }

  /** Empty counters never establish an empty result or choose a sparse plan. */
  @Test
  public void emptyIndexAndBackfill_switchAtPlanBuildOnly() {
    var clazz = session.createVertexClass("Backfill");
    clazz.createProperty("k", PropertyType.INTEGER).setNotNull(true);
    clazz.getProperty("k").createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    for (int i = 0; i < 120; i++) {
      graph.addVertex(T.label, "Backfill");
    }
    graph.tx().commit();
    session.begin();
    var before = read("Backfill");
    assertThat(before.useful()).isFalse();
    graph.traversal().V().hasLabel("Backfill").next().property("k", 7);
    graph.tx().commit();
    session.begin();
    assertThat(read("Backfill").density()).isEqualTo(1.0 / 120);
    assertThat(before.useful()).isFalse();
    session.rollback();
  }

  /** Source selectivity and degree distinguish uniform high-degree work from target survivors. */
  @Test
  public void costs_chargeScansAndEdgesBeforeFiltering() {
    assertThat(PresenceRootCost.compare(1000000, 1000, 1500, 10000000, 1, true, -1)
        .reverseWins()).isFalse();
    assertThat(PresenceRootCost.compare(1000000, 1000, 1500, 10000000, 1, true, 10)
        .reverseWins()).isFalse();
    assertThat(PresenceRootCost.compare(100000, 45000, 50000, 100000, 1, true, -1)
        .reverseWins()).isFalse();
    for (var density : new double[] {0.1, 0.01, 0.001}) {
      assertThat(PresenceRootCost.compare(100000, 45000, 50000, 100000, density, true, -1)
          .reverseWins()).as("density %s", density).isTrue();
    }
    assertThat(PresenceRootCost.compare(100000, 45000, 50000, 100000, 0.5, false, 10)
        .reverseWins()).isFalse();
  }

  /** Stored population and density survive copies and later class changes, without a zero proof. */
  @Test
  public void estimateView_keepsPopulationAndNeverProvesEmpty() throws Exception {
    var clazz = session.createVertexClass("Estimate");
    session.begin();
    var density = new PresenceDensity("t", "Original", PresenceDensity.Source.INDEX,
        0.001, 1000, 1, 0, true);
    var view = new PresenceDensityFilter(density);
    var context = new BasicCommandContext(session);
    assertThat(view.estimate(
        (com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal) clazz, 100,
        context)).isEqualTo(2);
    var copied = (PresenceDensityFilter) view.copy();
    assertThat(copied.density).isSameAs(density);
    var statement =
        (com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement) com.jetbrains.youtrackdb.internal.core.sql.SQLEngine
            .parse(
                "MATCH {as:s}.out('E'){as:t} RETURN t", session);
    var pattern = new com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var edge = pattern.getAliasToNode().get("s").out.iterator().next();
    assertThat(MatchExecutionPlanner.targetSelectivityFactor("t", edge, true,
        Map.of("t", "Estimate"), Map.of("t", copied), Map.of("t", 2L), null, session))
        .isEqualTo(density.density());
    assertThat(MatchExecutionPlanner.estimateFilterSelectivity(copied, 0, null, session))
        .isEqualTo(density.density());
    assertThat(copied.estimate(
        (com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal) clazz, 100,
        context)).isEqualTo(2);
    assertThat(MatchExecutionPlanner.estimateRootEntries(Map.of("t", "Estimate"), Map.of(),
        Map.of("t", view), context)).containsEntry("t", 2L);
    var selectivity = MatchExecutionPlanner.class.getDeclaredMethod("estimateFilterSelectivity",
        com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause.class, String.class,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext.class);
    selectivity.setAccessible(true);
    assertThat(selectivity.invoke(null, copied, "Estimate", context)).isEqualTo(0.001);
    // Build the real runtime access object. Its copy must copy the view, not resolve new density.
    var constructor = KnownEndpointExistsAccess.class.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    var access = (KnownEndpointExistsAccess) constructor.newInstance("s", "Estimate", null,
        statement.getMatchExpressions().getFirst(),
        com.jetbrains.youtrackdb.internal.core.db.record.record.Direction.OUT,
        null, null, null, false);
    var estimateFilter = KnownEndpointExistsAccess.class.getDeclaredField("estimateFilter");
    estimateFilter.setAccessible(true);
    estimateFilter.set(access, copied);
    var accessCopy = access.copy();
    assertThat(((PresenceDensityFilter) accessCopy.estimateFilter()).density).isSameAs(density);
    var step = new KnownEndpointExistsStep(context, accessCopy,
        new com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan(context),
        new com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan(context),
        1, 1, null, null, false, false);
    assertThat(step.filterShare(context)).isEqualTo(0.001);
    var stepCopy = (KnownEndpointExistsStep) step.copy(context);
    assertThat(stepCopy.filterShare(context)).isEqualTo(0.001);
    stepCopy.close();
    step.close();
    session.rollback();
  }

  /** Cached counters are optional. Every unavailable or unusable source retains G5. */
  @Test
  public void optionalCounters_failClosedWithoutReadingIndexRecords() {
    var db = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    var clazz = mock(SchemaImmutableClass.class);
    when(db.getMetadata().getImmutableSchemaSnapshot().getClassInternal("Target"))
        .thenReturn(clazz);
    when(clazz.approximateCountImpl(true, db)).thenReturn(1000L);
    when(clazz.getPolymorphicCollectionIds()).thenReturn(new int[] {1});
    when(db.getCollectionNameById(1)).thenReturn("target");
    var property = mock(SchemaProperty.class);
    when(property.isNotNull()).thenReturn(true);
    when(clazz.getProperty("k")).thenReturn(property);
    var index = mock(Index.class);
    when(clazz.getIndexesInternal()).thenReturn(Set.of(index));
    when(index.getDefinition()).thenReturn(new PropertyIndexDefinition("Target", "k",
        PropertyTypeInternal.INTEGER));
    when(index.getCollections()).thenReturn(Set.of("target"));
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).source())
        .isEqualTo(PresenceDensity.Source.NO_STATISTICS);
    for (var counts : new IndexStatistics[] {new IndexStatistics(0, 0, 0),
        new IndexStatistics(100, 100, 100), new IndexStatistics(100, 100, -1)}) {
      when(index.getStatistics(db)).thenReturn(counts);
      assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).source())
          .isEqualTo(PresenceDensity.Source.EMPTY_STATISTICS);
    }
    when(index.getStatistics(db)).thenReturn(new IndexStatistics(2000, 1, 0));
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).density()).isEqualTo(1);
    when(index.getStatistics(db)).thenThrow(new IllegalStateException("unavailable statistics"));
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).source())
        .isEqualTo(PresenceDensity.Source.STATISTICS_FAILURE);
    when(clazz.approximateCountImpl(true, db)).thenReturn(0L);
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).source())
        .isEqualTo(PresenceDensity.Source.NONPOSITIVE_POPULATION);
    when(clazz.approximateCountImpl(true, db)).thenReturn(1000L);
    when(index.getCollections()).thenReturn(Set.of("another"));
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).source())
        .isEqualTo(PresenceDensity.Source.NO_COVERAGE);
    when(index.getCollections()).thenReturn(Set.of("target"));
    when(index.getDefinition()).thenReturn(null);
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).useful()).isFalse();
    when(index.getDefinition()).thenReturn(new PropertyIndexDefinition("Other", "k",
        PropertyTypeInternal.INTEGER));
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).useful()).isFalse();
    when(index.getDefinition()).thenReturn(new PropertyIndexDefinition("Target", "other",
        PropertyTypeInternal.INTEGER));
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).useful()).isFalse();
    when(index.getDefinition()).thenReturn(new PropertyIndexDefinition("Target", "k",
        PropertyTypeInternal.EMBEDDEDLIST));
    assertThat(PresenceDensity.read("t", "Target", Set.of("k"), db).useful()).isFalse();
    org.mockito.Mockito.verify(index, org.mockito.Mockito.never()).stream(db);
  }

  /** Inherited mandatory declarations are checked again for each subclass override. */
  @Test
  public void hierarchyOverride_doesNotPromisePresenceForUnconstrainedSubclass() {
    var parent = session.createVertexClass("Parent");
    parent.createProperty("k", PropertyType.INTEGER).setMandatory(true);
    var child = session.createClass("Child", "Parent");
    child.createProperty("k", PropertyType.INTEGER).setMandatory(false);
    graph.addVertex(T.label, "Child", "k", 1);
    graph.tx().commit();
    session.begin();
    assertThat(read("Parent").useful()).isFalse();
    session.rollback();
  }

  /** Null-ignoring counters still use the class population, including unindexed missing keys. */
  @Test
  public void nullIgnoringIndex_usesCommittedClassDenominator() {
    var clazz = session.createVertexClass("Ignoring");
    clazz.createProperty("k", PropertyType.INTEGER).setNotNull(true);
    session.command("CREATE INDEX Ignoring.k ON Ignoring (k) NOTUNIQUE "
        + "METADATA {ignoreNullValues: true}");
    for (int i = 0; i < 100; i++) {
      var target = graph.addVertex(T.label, "Ignoring");
      if (i < 10) {
        target.property("k", 7);
      }
    }
    graph.tx().commit();
    session.begin();
    try {
      var index = session.getSharedContext().getIndexManager().getIndex("Ignoring.k");
      assertThat(index.getDefinition().isNullValuesIgnored()).isTrue();
      var density = read("Ignoring");
      assertThat(density.source()).isEqualTo(PresenceDensity.Source.INDEX);
      assertThat(density.total()).isEqualTo(10);
      assertThat(density.nulls()).isZero();
      assertThat(density.density()).isEqualTo(0.1);
    } finally {
      session.rollback();
    }
  }

  /** Whole-index counters cannot estimate a population with an unreadable collection. */
  @Test
  public void restrictedReadableCollections_rejectWholeIndexCounters() {
    var db = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    var clazz = mock(SchemaImmutableClass.class);
    when(db.getMetadata().getImmutableSchemaSnapshot().getClassInternal("Restricted"))
        .thenReturn(clazz);
    when(clazz.approximateCountImpl(true, db)).thenReturn(100L);
    when(clazz.getPolymorphicCollectionIds()).thenReturn(new int[] {1, 2});
    when(db.getCollectionNameById(1)).thenReturn("allowed");
    when(db.getCollectionNameById(2)).thenReturn("denied");
    var property = mock(SchemaProperty.class);
    when(property.isNotNull()).thenReturn(true);
    when(clazz.getProperty("k")).thenReturn(property);
    org.mockito.Mockito
        .doThrow(new com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException(
            "test restriction"))
        .when(db).checkSecurity(
            com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric.COLLECTION,
            com.jetbrains.youtrackdb.internal.core.metadata.security.Role.PERMISSION_READ,
            "denied");
    assertThat(PresenceDensity.read("t", "Restricted", Set.of("k"), db).source())
        .isEqualTo(PresenceDensity.Source.NO_COVERAGE);
    org.mockito.Mockito.verify(clazz, org.mockito.Mockito.never()).getIndexesInternal();
  }

  private PresenceDensity read(String name) {
    return PresenceDensity.read("t", name, Set.of("k"), session);
  }
}
