// Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class GraphDatabaseTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#populate
  @Test
  @Order(1)
  void populate() {
    generateGraphData();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#testSQLAgainstGraph
  @Test
  @Order(2)
  void testSQLAgainstGraph() {
    session.createEdgeClass("drives");
    session.createEdgeClass("owns");

    session.begin();
    var tom = session.newVertex();
    tom.setProperty("name", "Tom");

    var ferrari = session.newVertex("GraphCar");
    ferrari.setProperty("brand", "Ferrari");

    var maserati = session.newVertex("GraphCar");
    maserati.setProperty("brand", "Maserati");

    var porsche = session.newVertex("GraphCar");
    porsche.setProperty("brand", "Porsche");

    session.newStatefulEdge(tom, ferrari, "drives");
    session.newStatefulEdge(tom, maserati, "drives");
    session.newStatefulEdge(tom, porsche, "owns");

    session.commit();

    var activeTx = session.begin();
    tom = activeTx.load(tom);
    assertEquals(2, CollectionUtils.size(tom.getEdges(Direction.OUT, "drives")));

    var result =
        session.query("select out_[in.@class = 'GraphCar'].in_ from V where name = 'Tom'");
    assertEquals(1, result.stream().count());

    result =
        session.query(
            "select out_[label='drives'][in.brand = 'Ferrari'].in_ from V where name = 'Tom'");
    assertEquals(1, result.stream().count());

    result = session.query("select out_[in.brand = 'Ferrari'].in_ from V where name = 'Tom'");
    assertEquals(1, result.stream().count());
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#testNotDuplicatedIndexTxChanges
  @Test
  @Order(3)
  void testNotDuplicatedIndexTxChanges() {
    var oc = (SchemaClassInternal) session.createVertexClass("vertexA");
    if (oc == null) {
      oc = (SchemaClassInternal) session.createVertexClass("vertexA");
    }

    if (!oc.existsProperty("name")) {
      oc.createProperty("name", PropertyType.STRING);
      oc.createIndex("vertexA_name_idx", SchemaClass.INDEX_TYPE.UNIQUE, "name");
    }

    session.begin();
    var vertexA = session.newVertex("vertexA");
    vertexA.setProperty("name", "myKey");

    var vertexB = session.newVertex("vertexA");
    vertexB.setProperty("name", "anotherKey");
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    activeTx1.<Vertex>load(vertexB).delete();
    var activeTx = session.getActiveTransaction();
    activeTx.<Vertex>load(vertexA).delete();

    var v = session.newVertex("vertexA");
    v.setProperty("name", "myKey");

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#testNewVertexAndEdgesWithFieldsInOneShoot
  @Test
  @Order(4)
  void testNewVertexAndEdgesWithFieldsInOneShoot() {
    session.begin();
    var vertexA = session.newVertex();
    vertexA.setProperty("field1", "value1");
    vertexA.setProperty("field2", "value2");

    var vertexB = session.newVertex();
    vertexB.setProperty("field1", "value1");
    vertexB.setProperty("field2", "value2");

    var edgeC = session.newStatefulEdge(vertexA, vertexB);
    edgeC.setProperty("edgeF1", "edgeV2");

    session.commit();

    session.begin();
    vertexA = session.getActiveTransaction().load(vertexA);
    vertexB = session.getActiveTransaction().load(vertexB);
    edgeC = session.getActiveTransaction().load(edgeC);

    assertEquals("value1", vertexA.getProperty("field1"));
    assertEquals("value2", vertexA.getProperty("field2"));

    assertEquals("value1", vertexB.getProperty("field1"));
    assertEquals("value2", vertexB.getProperty("field2"));

    assertEquals("edgeV2", edgeC.getProperty("edgeF1"));
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#sqlNestedQueries
  @Test
  @Order(5)
  void sqlNestedQueries() {
    session.begin();
    var vertex1 = session.newVertex();
    vertex1.setProperty("driver", "John");

    var vertex2 = session.newVertex();
    vertex2.setProperty("car", "ford");

    var targetVertex = session.newVertex();
    targetVertex.setProperty("car", "audi");

    var edge = session.newStatefulEdge(vertex1, vertex2);
    edge.setProperty("color", "red");
    edge.setProperty("action", "owns");

    edge = session.newStatefulEdge(vertex1, targetVertex);
    edge.setProperty("color", "red");
    edge.setProperty("action", "wants");

    session.commit();

    session.begin();
    var query1 = "select driver from V where out().car contains 'ford'";
    var result = session.query(query1);
    assertEquals(1, result.stream().count());

    var query2 = "select driver from V where outE()[color='red'].inV().car contains 'ford'";
    result = session.query(query2);
    assertEquals(1, result.stream().count());

    var query3 = "select driver from V where outE()[action='owns'].inV().car = 'ford'";
    result = session.query(query3);
    assertEquals(1, result.stream().count());

    var query4 =
        "select driver from V where outE()[color='red'][action='owns'].inV().car = 'ford'";
    result = session.query(query4);
    assertEquals(1, result.stream().count());
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#nestedQuery
  @SuppressWarnings("unchecked")
  @Test
  @Order(6)
  void nestedQuery() {
    if (session.getClass("owns") == null) {
      session.createEdgeClass("owns");
    }
    session.begin();

    var countryVertex1 = session.newVertex();
    countryVertex1.setProperty("name", "UK");
    countryVertex1.setProperty("area", "Europe");
    countryVertex1.setProperty("code", "2");

    var cityVertex1 = session.newVertex();
    cityVertex1.setProperty("name", "leicester");
    cityVertex1.setProperty("lat", "52.64640");
    cityVertex1.setProperty("long", "-1.13159");

    var cityVertex2 = session.newVertex();
    cityVertex2.setProperty("name", "manchester");
    cityVertex2.setProperty("lat", "53.47497");
    cityVertex2.setProperty("long", "-2.25769");

    session.newStatefulEdge(countryVertex1, cityVertex1, "owns");
    session.newStatefulEdge(countryVertex1, cityVertex2, "owns");

    session.commit();
    var subquery = "select out('owns') as out from V where name = 'UK'";
    session.begin();
    var result = session.query(subquery).stream().collect(Collectors.toList());

    assertEquals(1, result.size());
    assertEquals(2, ((Collection) result.get(0).getProperty("out")).size());

    subquery = "select expand(out('owns')) from V where name = 'UK'";
    result = session.query(subquery).stream().collect(Collectors.toList());

    assertEquals(2, result.size());
    for (var value : result) {
      assertTrue(value.hasProperty("lat"));
    }
    session.commit();

    session.begin();
    var query =
        "select name, lat, long, distance(lat,long,51.5,0.08) as distance from (select"
            + " expand(out('owns')) from V where name = 'UK') order by distance";
    result = session.query(query).stream().collect(Collectors.toList());

    assertEquals(2, result.size());
    for (var oResult : result) {
      assertTrue(oResult.hasProperty("lat"));
      assertTrue(oResult.hasProperty("distance"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#testDeleteOfVerticesWithDeleteCommandMustFail
  @Test
  @Order(7)
  void testDeleteOfVerticesWithDeleteCommandMustFail() {
    session.begin();
    try {
      session.execute("delete from GraphVehicle").close();
      assertTrue(false, "Should have thrown CommandExecutionException");
    } catch (CommandExecutionException e) {
      assertTrue(true);
      session.rollback();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#testInsertOfEdgeWithInsertCommand
  @Test
  @Order(8)
  void testInsertOfEdgeWithInsertCommand() {
    assertThrows(DatabaseException.class, () -> session.command("insert into E set a = 33"));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GraphDatabaseTest#testEmbeddedDoc
  @Test
  @Order(9)
  void testEmbeddedDoc() {
    session.createAbstractClass("Vertex", "V");
    session.createAbstractClass("NonVertex");

    session.begin();
    var vertex = session.newVertex();
    vertex.setProperty("name", "vertexWithEmbedded");

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("foo", "bar");

    vertex.setProperty("emb1", doc);

    var doc3 = ((EntityImpl) session.newEmbeddedEntity("NonVertex"));
    doc3.setProperty("foo", "bar2");
    vertex.setProperty("emb3", doc3, PropertyType.EMBEDDED);

    var res1 = vertex.getProperty("emb1");
    assertNotNull(res1);
    assertTrue(res1 instanceof EntityImpl);

    var res3 = vertex.getProperty("emb3");
    assertNotNull(res3);
    assertTrue(res3 instanceof EntityImpl);
    session.commit();
  }
}
