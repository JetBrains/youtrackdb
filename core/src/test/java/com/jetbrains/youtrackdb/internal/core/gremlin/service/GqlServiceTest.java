package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlStatement;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.query.RegisteredQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.junit.Assert;
import org.junit.Test;

/**
 * End-to-end tests for GqlService verifying:
 * - single binding returns Map with Vertex
 * - multiple vertices all returned with correct properties
 * - multiple patterns produce Map projection with cartesian product
 * - anonymous alias returns Map with Vertex
 * - streaming mode
 * - error paths (non-existent class, empty query)
 * - Factory: all parameter parsing branches, type, name, requirements
 */
@SuppressWarnings({"resource", "unchecked"})
public class GqlServiceTest extends GraphBaseTest {

  // ── Single binding: returns Map with vertex ──

  @Test
  public void execute_singleVertex_returnsVertexWithCorrectProperties() {
    graph.addVertex(T.label, "GqlSvcPerson", "name", "Alice", "age", 30);
    graph.tx().commit();

    var results = graph.traversal().gql("MATCH (a:GqlSvcPerson)").toList();

    Assert.assertEquals(1, results.size());
    var map = (Map<String, Object>) results.getFirst();
    var v = (Vertex) map.get("a");
    Assert.assertEquals("GqlSvcPerson", v.label());
    Assert.assertEquals("Alice", v.property("name").value());
    Assert.assertEquals(30, v.property("age").value());
  }

  // ── Multiple vertices: verify all returned ──

  @Test
  public void execute_multipleVertices_returnsAllWithCorrectProperties() {
    graph.addVertex(T.label, "GqlSvcAnimal", "species", "Cat");
    graph.addVertex(T.label, "GqlSvcAnimal", "species", "Dog");
    graph.addVertex(T.label, "GqlSvcAnimal", "species", "Parrot");
    graph.tx().commit();

    var results = graph.traversal().gql("MATCH (a:GqlSvcAnimal)").toList();

    Assert.assertEquals(3, results.size());
    var species = results.stream()
        .map(r -> ((Vertex) ((Map<String, Object>) r).get("a"))
            .property("species").value().toString())
        .sorted()
        .toList();
    Assert.assertEquals(List.of("Cat", "Dog", "Parrot"), species);
  }

  // ── Multiple patterns: cartesian product returns Map ──

  @SuppressWarnings("unchecked")
  @Test
  public void execute_multiplePatterns_returnsMapWithAllBindings() {
    graph.addVertex(T.label, "GqlSvcCity", "name", "Warsaw");
    graph.addVertex(T.label, "GqlSvcCountry", "name", "Poland");
    graph.tx().commit();

    var results = graph.traversal().gql("MATCH (c:GqlSvcCity), (co:GqlSvcCountry)").toList();

    Assert.assertEquals(1, results.size());
    Assert.assertTrue(results.getFirst() instanceof Map);
    var map = (Map<String, Object>) results.getFirst();
    Assert.assertTrue(map.containsKey("c"));
    Assert.assertTrue(map.containsKey("co"));
    Assert.assertEquals("Warsaw", ((Vertex) map.get("c")).property("name").value());
    Assert.assertEquals("Poland", ((Vertex) map.get("co")).property("name").value());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void execute_multiplePatterns_cartesianProductSize() {
    graph.addVertex(T.label, "GqlSvcColor", "name", "Red");
    graph.addVertex(T.label, "GqlSvcColor", "name", "Blue");
    graph.addVertex(T.label, "GqlSvcShape", "name", "Circle");
    graph.tx().commit();

    var results = graph.traversal().gql("MATCH (c:GqlSvcColor), (s:GqlSvcShape)").toList();

    Assert.assertEquals(2, results.size());
    for (var result : results) {
      var row = (Map<String, Object>) result;
      Assert.assertTrue(row.containsKey("c"));
      Assert.assertTrue(row.containsKey("s"));
      Assert.assertEquals("Circle", ((Vertex) row.get("s")).property("name").value());
    }
  }

  // ── Anonymous alias: returns Map with generated alias ──

  @Test
  public void execute_withoutAlias_returnsVertexWithProperties() {
    graph.addVertex(T.label, "GqlSvcItem", "name", "Widget");
    graph.tx().commit();

    var results = graph.traversal().gql("MATCH (:GqlSvcItem)").toList();

    Assert.assertEquals(1, results.size());
    Assert.assertTrue(results.getFirst() instanceof Map);
    var map = (Map<String, Object>) results.getFirst();
    var v = (Vertex) map.values().iterator().next();
    Assert.assertEquals("Widget", v.property("name").value());
  }

  // ── gql() with arguments ──

  @Test
  public void execute_gqlWithArguments_returnsResults() {
    graph.addVertex(T.label, "GqlSvcArg", "name", "Arg");
    graph.tx().commit();

    var list = graph.traversal().gql("MATCH (a:GqlSvcArg)", Map.of()).toList();
    Assert.assertEquals(1, list.size());
    Assert.assertTrue(list.getFirst() instanceof Map);
  }

  // ── Streaming mode ──

  @Test
  public void execute_streamingMode_returnsResults() {
    graph.addVertex(T.label, "GqlSvcStream", "name", "S");
    graph.tx().commit();

    var list = graph.traversal().V()
        .call(GqlService.NAME, Map.of(GqlService.QUERY, "MATCH (a:GqlSvcStream)"))
        .toList();
    Assert.assertEquals(1, list.size());
  }

  // ── Error paths ──

  @Test(expected = Exception.class)
  public void execute_nonExistentClass_throws() {
    graph.traversal().gql("MATCH (a:NonExistentClassXYZ123)").toList();
  }

  @Test
  public void execute_emptyQuery_returnsEmptyList() {
    var list = graph.traversal()
        .call(GqlService.NAME, Map.of(GqlService.QUERY, "")).toList();
    Assert.assertTrue(list.isEmpty());
  }

  // ── Factory: parameter parsing ──

  @Test
  public void factory_nullParams_returnsStartService() {
    var factory = new GqlService.Factory();
    var service = factory.createService(true, null);
    Assert.assertNotNull(service);
    Assert.assertSame(Service.Type.Start, service.getType());
  }

  @Test
  public void factory_isStartFalse_returnsStreamingService() {
    var factory = new GqlService.Factory();
    var service = factory.createService(false, null);
    Assert.assertSame(Service.Type.Streaming, service.getType());
  }

  @Test
  public void factory_queryNotString_usesEmptyQuery() {
    var service = new GqlService.Factory().createService(true,
        Map.of(GqlService.QUERY, 123));
    Assert.assertNotNull(service);
  }

  @Test
  public void factory_argumentsIsMap_accepted() {
    var service = new GqlService.Factory().createService(true,
        Map.of(GqlService.QUERY, "MATCH (n:V)", GqlService.ARGUMENTS, Map.of("k", "v")));
    Assert.assertNotNull(service);
  }

  @Test
  public void factory_argumentsListSingleString_usesQueryFromList() {
    var service = new GqlService.Factory().createService(true,
        Map.of(GqlService.ARGUMENTS, List.of("MATCH (n:OUser)")));
    Assert.assertNotNull(service);
  }

  @Test
  public void factory_argumentsListWithKeyValuePairs_buildsMap() {
    var service = new GqlService.Factory().createService(true,
        Map.of(GqlService.ARGUMENTS, List.of("MATCH (n:OUser)", "key", "value")));
    Assert.assertNotNull(service);
  }

  @Test(expected = IllegalArgumentException.class)
  public void factory_argumentsListOddRest_throws() {
    new GqlService.Factory().createService(true,
        Map.of(GqlService.ARGUMENTS, List.of("MATCH (n:V)", "key")));
  }

  // ── Factory metadata ──

  @Test
  public void factory_getName_returnsGql() {
    Assert.assertEquals("gql", new GqlService.Factory().getName());
  }

  @Test
  public void factory_getSupportedTypes_containsStartAndStreaming() {
    var types = new GqlService.Factory().getSupportedTypes();
    Assert.assertTrue(types.contains(Service.Type.Start));
    Assert.assertTrue(types.contains(Service.Type.Streaming));
  }

  @Test
  public void service_getRequirements_isEmpty() {
    var service = new GqlService.Factory().createService(true, null);
    Assert.assertTrue(service.getRequirements().isEmpty());
  }

  // ── GqlResultIterator lifecycle ──

  @Test
  public void execute_multipleResults_iteratedCorrectly() {
    graph.addVertex(T.label, "GqlSvcIter", "name", "A");
    graph.addVertex(T.label, "GqlSvcIter", "name", "B");
    graph.addVertex(T.label, "GqlSvcIter", "name", "C");
    graph.tx().commit();

    var traversal = graph.traversal()
        .call(GqlService.NAME, Map.of(GqlService.QUERY, "MATCH (a:GqlSvcIter)"));
    var count = 0;
    while (traversal.hasNext()) {
      Assert.assertNotNull(traversal.next());
      count++;
    }
    Assert.assertEquals(3, count);
    Assert.assertFalse("hasNext after exhaustion should return false",
        traversal.hasNext());
  }

  @Test
  public void execute_resultMapContainsGremlinTypes() {
    graph.addVertex(T.label, "GqlSvcType", "name", "TypeTest");
    graph.tx().commit();

    var results = graph.traversal().gql("MATCH (a:GqlSvcType)").toList();
    Assert.assertEquals(1, results.size());
    var map = (Map<String, Object>) results.getFirst();
    Assert.assertTrue("Value should be a Vertex", map.get("a") instanceof Vertex);
  }

  /** Start and streaming invocations each publish a distinct, fully closable execution. */
  @Test
  public void serviceInvocationsOwnDistinctHandlesAndCaptureTheOpeningSession() {
    var fixture = new ServiceFixture();
    try (var planner = mockStatic(GqlPlanner.class)) {
      planner.when(() -> GqlPlanner.getStatement("query", fixture.session))
          .thenReturn(fixture.statement);
      var start = new GqlService("query", Map.of(), Service.Type.Start);
      var streaming = new GqlService("query", Map.of(), Service.Type.Streaming);
      var first = start.execute(fixture.ctx, Map.of());
      var second = streaming.execute(fixture.ctx, mock(Traverser.Admin.class), Map.of());
      Assert.assertNotSame(first, second);
      Assert.assertEquals(2, fixture.queries.size());
      var handles = List.copyOf(fixture.queries.values());
      Assert.assertNotSame(handles.get(0), handles.get(1));
      var newSession = mock(DatabaseSessionEmbedded.class);
      when(fixture.tx.getDatabaseSession()).thenReturn(newSession);
      first.close();
      Assert.assertEquals(1, fixture.queries.size());
      second.close();
      Assert.assertTrue(fixture.queries.isEmpty());
      verifyNoInteractions(newSession);
      verify(fixture.session, times(2)).queryStarted(anyString(), any(RegisteredQuery.class));
      verify(fixture.session, times(2)).queryClosed(anyString());
      verify(fixture.plan, times(2)).start(fixture.session);
      verify(fixture.stream, times(2)).close();
      verify(fixture.plan, times(2)).close();
    }
  }

  /** A rejection before or after insertion closes the owner and removes any installed handle. */
  @Test
  public void registrationFailureReleasesEveryResourceAndKeepsTheOriginalFailure() {
    for (var inserted : new boolean[] {false, true}) {
      for (var fatal : new boolean[] {false, true}) {
        var fixture = new ServiceFixture();
        Throwable failure = fatal ? new AssertionError("register")
            : new IllegalStateException("register");
        var streamFailure = new AssertionError("stream close");
        var planFailure = new IllegalStateException("plan close");
        doAnswer(call -> {
          if (inserted) {
            fixture.queries.put(call.getArgument(0), call.getArgument(1));
          }
          throw failure;
        }).when(fixture.session).queryStarted(anyString(), any(RegisteredQuery.class));
        doThrow(streamFailure).when(fixture.stream).close();
        doThrow(planFailure).when(fixture.plan).close();
        try (var planner = mockStatic(GqlPlanner.class)) {
          planner.when(() -> GqlPlanner.getStatement("query", fixture.session))
              .thenReturn(fixture.statement);
          var service = new GqlService("query", Map.of(), Service.Type.Start);
          Assert.assertSame(failure, Assert.assertThrows(Throwable.class,
              () -> service.execute(fixture.ctx, Map.of())));
          Assert.assertArrayEquals(new Throwable[] {streamFailure}, failure.getSuppressed());
          Assert.assertArrayEquals(new Throwable[] {planFailure}, streamFailure.getSuppressed());
          Assert.assertTrue(fixture.queries.isEmpty());
          verify(fixture.stream).close();
          verify(fixture.plan).close();
          verify(fixture.session).queryClosed(anyString());
        }
      }
    }
  }

  /** Synchronous registry teardown during publication cannot revive or restart the iterator. */
  @Test
  public void reentrantTransactionCloseDuringRegistrationReturnsATerminalReader() {
    var fixture = new ServiceFixture();
    doAnswer(call -> {
      RegisteredQuery handle = call.getArgument(1);
      fixture.queries.put(call.getArgument(0), handle);
      handle.close();
      return null;
    }).when(fixture.session).queryStarted(anyString(), any(RegisteredQuery.class));
    try (var planner = mockStatic(GqlPlanner.class)) {
      planner.when(() -> GqlPlanner.getStatement("query", fixture.session))
          .thenReturn(fixture.statement);
      var iterator = new GqlService("query", Map.of(), Service.Type.Start)
          .execute(fixture.ctx, Map.of());
      Assert.assertFalse(iterator.hasNext());
      Assert.assertThrows(java.util.NoSuchElementException.class, iterator::next);
      iterator.close();
      Assert.assertTrue(fixture.queries.isEmpty());
      verify(fixture.stream, times(1)).close();
      verify(fixture.plan, times(1)).close();
      verify(fixture.plan, times(1)).start(fixture.session);
    }
  }

  /** Startup cleanup covers no plan, a failed plan start, and a stream without an owner. */
  @Test
  public void startupFailureClosesPartialResourcesWithoutMaskingUncheckedFailures() {
    for (var stage = 0; stage < 3; stage++) {
      for (var fatal : new boolean[] {false, true}) {
        for (var shared : new boolean[] {false, true}) {
          var fixture = new ServiceFixture();
          Throwable failure = fatal ? new AssertionError("startup")
              : new IllegalStateException("startup");
          Throwable cleanup = shared ? failure : new AssertionError("cleanup");
          if (stage == 0) {
            when(fixture.statement.createExecutionPlan(any())).thenThrow(failure);
          } else if (stage == 1) {
            when(fixture.plan.start(fixture.session)).thenThrow(failure);
          } else {
            when(fixture.session.getMetadata().getImmutableSchemaSnapshot()).thenThrow(failure);
            doThrow(cleanup).when(fixture.stream).close();
          }
          doThrow(cleanup).when(fixture.plan).close();
          try (var planner = mockStatic(GqlPlanner.class)) {
            planner.when(() -> GqlPlanner.getStatement("query", fixture.session))
                .thenReturn(fixture.statement);
            var service = new GqlService("query", Map.of(), Service.Type.Start);
            Assert.assertSame(failure, Assert.assertThrows(Throwable.class,
                () -> service.execute(fixture.ctx, Map.of())));
            Assert.assertEquals(shared || stage == 0 ? 0 : stage, failure.getSuppressed().length);
            verify(fixture.stream, times(stage == 2 ? 1 : 0)).close();
            verify(fixture.plan, times(stage > 0 ? 1 : 0)).close();
            verify(fixture.session, times(0)).queryStarted(anyString(), any(RegisteredQuery.class));
          }
        }
      }
    }
  }

  private static final class ServiceFixture {

    final DatabaseSessionEmbedded session = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    final YTDBGraphInternal graph = mock(YTDBGraphInternal.class);
    final YTDBTransaction tx = mock(YTDBTransaction.class);
    final Service.ServiceCallContext ctx = mock(Service.ServiceCallContext.class);
    final GqlStatement statement = mock(GqlStatement.class);
    final GqlExecutionPlan plan = mock(GqlExecutionPlan.class);
    final GqlExecutionStream stream = mock(GqlExecutionStream.class);
    final Map<String, RegisteredQuery> queries = new HashMap<>();

    ServiceFixture() {
      var traversal = mock(Traversal.Admin.class);
      when(ctx.getTraversal()).thenReturn(traversal);
      when(traversal.getGraph()).thenReturn(Optional.of(graph));
      when(graph.tx()).thenReturn(tx);
      when(tx.getDatabaseSession()).thenReturn(session);
      when(statement.createExecutionPlan(any())).thenReturn(plan);
      when(plan.start(session)).thenReturn(stream);
      doAnswer(call -> {
        queries.put(call.getArgument(0), call.getArgument(1));
        return null;
      }).when(session).queryStarted(anyString(), any(RegisteredQuery.class));
      doAnswer(call -> {
        queries.remove(call.getArgument(0));
        return null;
      }).when(session).queryClosed(anyString());
    }
  }

  // ── Factory: arguments edge cases ──

  @Test
  public void factory_argumentsListNonStringFirst_usesEmptyQuery() {
    var service = new GqlService.Factory().createService(true,
        Map.of(GqlService.ARGUMENTS, List.of(42, "key", "value")));
    Assert.assertNotNull(service);
  }

  @Test
  public void factory_argumentsEmptyList_usesEmptyQuery() {
    var service = new GqlService.Factory().createService(true,
        Map.of(GqlService.ARGUMENTS, List.of()));
    Assert.assertNotNull(service);
  }

  @Test
  public void factory_argumentsNotListOrMap_usesEmptyQuery() {
    var service = new GqlService.Factory().createService(true,
        Map.of(GqlService.ARGUMENTS, "not-a-list"));
    Assert.assertNotNull(service);
  }
}
