package com.jetbrains.youtrackdb.internal.common.comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.util.GremlinValueComparator;
import org.junit.Test;

public class GremlinOrderComparatorTest {

  @Test
  public void ordersEveryAdjacentTinkerPopTypePriority() {
    Object[] values = {
        null,
        false,
        1,
        new Date(1),
        "a",
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        element(Vertex.class),
        element(Edge.class),
        element(VertexProperty.class),
        property(),
        path(),
        Set.of(1),
        List.of(1),
        Map.of("a", 1),
        Map.entry("a", 1),
        new Object()
    };

    for (int i = 0; i < values.length - 1; i++) {
      assertTrue("priority at " + i,
          GremlinOrderComparator.INSTANCE.compare(values[i], values[i + 1], null) < 0);
    }
  }

  /**
   * Translator provenance enables TinkerPop type priority only for translated items. Unmarked SQL
   * ordering retains its legacy incompatible-type tie.
   */
  @Test
  public void translatorMarkerEnablesTypePriorityForOrderItem() {
    var text = new ResultInternal(null);
    text.setProperty("value", "10");
    var bool = new ResultInternal(null);
    bool.setProperty("value", true);

    var marked = new SQLOrderByItem();
    marked.setAlias("value");
    marked.setGremlinToMatchTranslatorProduced(true);
    var unmarked = new SQLOrderByItem();
    unmarked.setAlias("value");

    assertTrue(marked.compare(text, bool, null, ResolvedOrderByNullsPlacement.SHIPPED) > 0);
    assertEquals(
        0, unmarked.compare(text, bool, null, ResolvedOrderByNullsPlacement.SHIPPED));
  }

  @Test
  public void comparesNestedListsLexicographically() {
    assertTrue(
        GremlinOrderComparator.INSTANCE.compare(List.of(List.of(1, 2)), List.of(List.of(1, 3)),
            null)
            < 0);
  }

  @Test
  public void comparesSetsAfterSortingTheirContents() {
    Set<Integer> first = new LinkedHashSet<>(List.of(3, 1));
    Set<Integer> second = new LinkedHashSet<>(List.of(3, 2));

    assertTrue(GremlinOrderComparator.INSTANCE.compare(first, second, null) < 0);
  }

  @Test
  public void comparesMapsBySortedEntries() {
    Map<String, Integer> first = new LinkedHashMap<>();
    first.put("b", 1);
    first.put("a", 1);
    Map<String, Integer> second = new LinkedHashMap<>();
    second.put("b", 1);
    second.put("a", 2);

    assertTrue(GremlinOrderComparator.INSTANCE.compare(first, second, null) < 0);
  }

  /** Raw loaded and unloaded links use native type priorities against every other value type. */
  @Test
  public void graphValuesMatchNativeAcrossTypesAndRepresentations() {
    var session = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    when(schema.getClassByCollectionId(10).isVertexType()).thenReturn(true);
    when(schema.getClassByCollectionId(20).isEdgeType()).thenReturn(true);
    var vertexId = new RecordId(10, 1);
    var edgeId = new RecordId(20, 1);
    var vertex = entity(vertexId, true, false);
    var edge = entity(edgeId, false, true);
    Object[] raw = {vertex, vertexId, edge, edgeId};
    Object[] nativeValues = {
        element(Vertex.class, vertexId), element(Vertex.class, vertexId),
        element(Edge.class, edgeId), element(Edge.class, edgeId)
    };
    Object[] others = {null, false, 1, new Date(1), "a", UUID.randomUUID(),
        element(Vertex.class, new RecordId(10, 2)),
        element(Edge.class, new RecordId(20, 2)), element(VertexProperty.class),
        property(), path(), Set.of(1), List.of(1), Map.of("a", 1), Map.entry("a", 1),
        new Object(), vertex, vertexId, edge, edgeId};
    for (int i = 0; i < raw.length; i++) {
      for (int j = 0; j < others.length; j++) {
        var expectedOther = j < others.length - raw.length
            ? others[j] : nativeValues[j - (others.length - raw.length)];
        var expected = GremlinValueComparator.ORDERABILITY.compare(nativeValues[i], expectedOther);
        assertEquals("forward " + i + "/" + j, Integer.signum(expected),
            Integer.signum(GremlinOrderComparator.INSTANCE.compare(raw[i], others[j], session)));
        assertEquals("reverse " + i + "/" + j, -Integer.signum(expected),
            Integer.signum(GremlinOrderComparator.INSTANCE.compare(others[j], raw[i], session)));
      }
    }
  }

  /** Pending and committed identities keep TinkerPop's class-sensitive ordering, not RID order. */
  @Test
  public void graphIdentitiesPreservePendingAndCommittedClasses() {
    var session = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    when(schema.getClassByCollectionId(10).isVertexType()).thenReturn(true);
    when(schema.getClassByCollectionId(11).isVertexType()).thenReturn(true);
    var pending = new ChangeableRecordId(11, -2);
    var committed = new RecordId(10, 1);
    assertTrue("the fixture distinguishes numeric and TinkerPop RID order",
        GremlinValueComparator.ORDERABILITY.compare(pending, committed) < 0);
    assertTrue(pending.compareTo(committed) > 0);
    for (var first : List.of(pending, entity(pending, true, false))) {
      for (var second : List.of(committed, entity(committed, true, false))) {
        assertTrue(GremlinOrderComparator.INSTANCE.compare(first, second, session) < 0);
        assertTrue(GremlinOrderComparator.INSTANCE.compare(second, first, session) > 0);
      }
    }
  }

  /** Containers and ordinary documents retain raw orderability and never consult the session. */
  @Test
  public void containersAndNonGraphEntitiesKeepTheirComparison() {
    var session = mock(DatabaseSessionEmbedded.class);
    var vertex = entity(new RecordId(10, 1), true, false);
    var edge = entity(new RecordId(20, 1), false, true);
    var document = entity(new RecordId(30, 1), false, false);
    Object[][] pairs = {
        {List.of(vertex), List.of(edge)}, {Set.of(vertex), Set.of(edge)},
        {Map.of("a", vertex), Map.of("a", edge)}, {document, "a"}, {1, "a"}
    };
    for (var pair : pairs) {
      assertEquals(GremlinValueComparator.ORDERABILITY.compare(pair[0], pair[1]),
          GremlinOrderComparator.INSTANCE.compare(pair[0], pair[1], session));
    }
    verifyNoInteractions(session);
  }

  /** Identifiers outside graph classes keep the dependency comparison unchanged. */
  @Test
  public void nonGraphAndUnknownCollectionIdentifiersKeepTheirComparison() {
    var session = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    when(schema.getClassByCollectionId(99)).thenReturn(null);
    for (var rid : List.of(new RecordId(30, 1), new RecordId(99, 1))) {
      assertEquals(GremlinValueComparator.ORDERABILITY.compare(rid, "a"),
          GremlinOrderComparator.INSTANCE.compare(rid, "a", session));
    }
  }

  /** Only marked items use graph priorities, including copied items and descending direction. */
  @Test
  public void markedOrderItemPassesSessionAndKeepsCopyDirection() {
    var session = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    when(session.getMetadata().getImmutableSchemaSnapshot()
        .getClassByCollectionId(10).isVertexType()).thenReturn(true);
    var ctx = new BasicCommandContext(session);
    var vertex = new ResultInternal(null);
    vertex.setProperty("value", new RecordId(10, 1));
    var list = new ResultInternal(null);
    list.setProperty("value", List.of(1));
    var marked = new SQLOrderByItem();
    marked.setAlias("value");
    marked.setGremlinToMatchTranslatorProduced(true);
    assertTrue(marked.copy().compare(vertex, list, ctx,
        ResolvedOrderByNullsPlacement.SHIPPED) < 0);
    marked.setType(SQLOrderByItem.DESC);
    assertTrue(marked.copy().compare(vertex, list, ctx,
        ResolvedOrderByNullsPlacement.SHIPPED) > 0);
    var unmarked = new SQLOrderByItem();
    unmarked.setAlias("value");
    assertEquals(0, unmarked.compare(vertex, list, ctx, ResolvedOrderByNullsPlacement.SHIPPED));
  }

  private static Entity entity(RID id, boolean vertex, boolean edge) {
    var entity = mock(Entity.class);
    when(entity.getIdentity()).thenReturn(id);
    when(entity.isVertex()).thenReturn(vertex);
    when(entity.isEdge()).thenReturn(edge);
    return entity;
  }

  private static Object element(Class<?> type) {
    return element(type, 1);
  }

  private static Object element(Class<?> type, Object id) {
    return Proxy.newProxyInstance(
        type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
          if (method.getName().equals("id")) {
            return id;
          }
          return null;
        });
  }

  private static Property<Object> property() {
    return (Property<Object>) Proxy.newProxyInstance(
        Property.class.getClassLoader(), new Class<?>[] {Property.class}, (proxy, method, args) -> {
          if (method.getName().equals("key")) {
            return "a";
          }
          if (method.getName().equals("value")) {
            return 1;
          }
          return null;
        });
  }

  private static Path path() {
    return (Path) Proxy.newProxyInstance(
        Path.class.getClassLoader(), new Class<?>[] {Path.class}, (proxy, method, args) -> {
          if (method.getName().equals("iterator")) {
            return List.of(1).iterator();
          }
          return null;
        });
  }
}
