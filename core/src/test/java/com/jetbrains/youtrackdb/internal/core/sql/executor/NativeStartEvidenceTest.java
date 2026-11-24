package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.List;
import org.junit.Test;

/** Focused contract checks use package access without exposing planner internals in production. */
public class NativeStartEvidenceTest extends GraphBaseTest {

  /** Changing only index identity, direction or key arity invalidates otherwise equal evidence. */
  @Test
  public void equalityEvidenceRejectsAlternateIndexDirectionAndCompositeDefinition() {
    var type = session.createVertexClass("IndexedRouteVertex");
    type.createProperty("name",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING)
        .createIndex(
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    graph.addVertex(org.apache.tinkerpop.gremlin.structure.T.label,
        "IndexedRouteVertex", "name", "two");
    graph.tx().commit();
    var tx = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction) graph.tx();
    tx.readWrite();
    var db = tx.getDatabaseSession();
    var source =
        (InternalExecutionPlan) new com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphQuery(
            "SELECT FROM IndexedRouteVertex WHERE name = :value",
            java.util.Map.of("value", "two"), 1).explain(db);
    try {
      var evidence = NativeStartEvidence.from(source, "IndexedRouteVertex", "name", "two");
      assertThat(evidence).isNotNull();
      var fetch = (com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep) source
          .getSteps().getFirst();
      var desc = fetch.getDesc();
      assertThat(evidence.matches(source, source.getContext(), "IndexedRouteVertex")).isTrue();
      var alternate = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.index.Index.class);
      org.mockito.Mockito.when(alternate.getDefinition())
          .thenReturn(desc.getIndex().getDefinition());
      var alternateDesc =
          new com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor(
              alternate, desc.getKeyCondition(), desc.getAdditionalRangeCondition(),
              desc.getRemainingCondition());
      source.getSteps().set(0,
          new com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep(
              alternateDesc, fetch.isOrderAsc(), source.getContext(), false));
      assertThat(evidence.matches(source, source.getContext(), "IndexedRouteVertex")).isFalse();
      source.getSteps().set(0,
          new com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep(
              desc, !fetch.isOrderAsc(), source.getContext(), false));
      assertThat(evidence.matches(source, source.getContext(), "IndexedRouteVertex")).isFalse();

      // Keep index identity fixed while changing only its definition's key arity.
      var definition = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.index.IndexDefinition.class);
      org.mockito.Mockito.when(alternate.getDefinition()).thenReturn(definition);
      org.mockito.Mockito.when(definition.getProperties()).thenReturn(List.of("name"));
      source.getSteps().set(0,
          new com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep(
              alternateDesc, fetch.isOrderAsc(), source.getContext(), false));
      var single = com.jetbrains.youtrackdb.internal.core.sql.executor.NativeStartEvidence.from(
          source, "IndexedRouteVertex", "name", "two");
      assertThat(single).isNotNull();
      org.mockito.Mockito.when(definition.getProperties()).thenReturn(List.of("name", "other"));
      assertThat(single.matches(source, source.getContext(), "IndexedRouteVertex")).isFalse();
      assertThat(com.jetbrains.youtrackdb.internal.core.sql.executor.NativeStartEvidence.from(
          source, "IndexedRouteVertex", "name", "two")).isNull();
      source.getSteps().set(0, fetch);
    } finally {
      source.close();
    }
  }

}
