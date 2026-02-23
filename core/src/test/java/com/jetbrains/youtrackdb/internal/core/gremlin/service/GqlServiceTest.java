package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests for GqlService: Factory createService with null params, non-String QUERY,
 * ARGUMENTS as List; execute with empty query returns empty iterator; execute with valid
 * MATCH; streaming execute; gql() DSL methods.
 */
@SuppressWarnings("resource")
public class GqlServiceTest extends GraphBaseTest {

  @Test
  public void execute_withValidMatch_returnsResults() {
    graph.traversal().addV("V").property("name", "A").iterate();
    var g = graph.traversal();
    var list = g.gql("MATCH (a:V)").toList();
    Assert.assertNotNull(list);
    Assert.assertEquals(1, list.size());
    Assert.assertTrue(list.getFirst() instanceof Vertex);
  }

  @Test
  public void execute_gqlWithArguments_callsServiceWithMap() {
    graph.traversal().addV("V").property("name", "B").iterate();
    var list = graph.traversal().gql("MATCH (a:V)", Map.of()).toList();
    Assert.assertNotNull(list);
    Assert.assertEquals(1, list.size());
  }

  @Test
  public void execute_streamingMode_delegatesToExecute() {
    graph.traversal().addV("V").property("name", "C").iterate();
    var list = graph.traversal().V().gql("MATCH (a:V)").toList();
    Assert.assertEquals(1, list.size());
  }

  @Test(expected = Exception.class)
  public void execute_whenClassDoesNotExist_throws() {
    graph.traversal().gql("MATCH (a:NonExistentClassXYZ)").toList();
  }

  @Test
  public void factory_createService_withNullParams_usesEmptyQuery() {
    var factory = new GqlService.Factory();
    var service = factory.createService(true, null);
    Assert.assertNotNull(service);
    Assert.assertSame(Service.Type.Start, service.getType());
  }

  @Test
  public void factory_createService_whenQueryNotString_usesEmptyQuery() {
    var factory = new GqlService.Factory();
    var service = factory.createService(true, Map.of(GqlService.QUERY, 123));
    Assert.assertNotNull(service);
  }

  @Test
  public void factory_createService_whenArgumentsIsMap_usesMap() {
    var factory = new GqlService.Factory();
    var service = factory.createService(true,
        Map.of(GqlService.QUERY, "MATCH (n:V)", GqlService.ARGUMENTS, Map.of("k", "v")));
    Assert.assertNotNull(service);
  }

  @Test
  public void factory_createService_whenArgumentsIsListWithFirstString_usesQueryFromList() {
    var factory = new GqlService.Factory();
    var service = factory.createService(true,
        Map.of(GqlService.ARGUMENTS, List.of("MATCH (n:OUser)")));
    Assert.assertNotNull(service);
  }

  @Test
  public void factory_createService_whenArgumentsIsListWithKeyValuePairs_buildsMap() {
    var factory = new GqlService.Factory();
    var service = factory.createService(true,
        Map.of(GqlService.ARGUMENTS, List.of("MATCH (n:OUser)", "key", "value")));
    Assert.assertNotNull(service);
  }

  @Test(expected = IllegalArgumentException.class)
  public void factory_createService_whenArgumentsListHasOddRest_throws() {
    var factory = new GqlService.Factory();
    factory.createService(true,
        Map.of(GqlService.ARGUMENTS, List.of("MATCH (n:V)", "key")));
  }

  @Test
  public void execute_withEmptyQuery_returnsEmptyIterator() {
    var g = graph.traversal();
    var list = g.call(GqlService.NAME, Map.of(GqlService.QUERY, "")).toList();
    Assert.assertTrue(list.isEmpty());
  }
}
