package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests for GqlService: Factory createService with null params, non-String QUERY,
 * ARGUMENTS as List; execute with empty query returns empty iterator.
 */
@SuppressWarnings("resource")
public class GqlServiceTest extends GraphBaseTest {

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
    Assert.assertNotNull(list);
    Assert.assertTrue(list.isEmpty());
  }
}
