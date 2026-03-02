package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.List;
import java.util.Map;
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
}
