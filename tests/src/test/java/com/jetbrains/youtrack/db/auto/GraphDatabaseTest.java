/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import java.io.IOException;
import java.util.Collection;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class GraphDatabaseTest extends BaseDBTest {

  @Parameters(value = "remote")
  public GraphDatabaseTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void populate() {
    generateGraphData();
  }

  @Test(dependsOnMethods = "populate")
  public void testSQLAgainstGraph() {
    session.createEdgeClass("drives");

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

    tom = session.bindToSession(tom);
    Assert.assertEquals(CollectionUtils.size(tom.getEdges(Direction.OUT, "drives")), 2);

    var result =
        session.query("select out_[in.@class = 'GraphCar'].in_ from V where name = 'Tom'");
    Assert.assertEquals(result.stream().count(), 1);

    result =
        session.query(
            "select out_[label='drives'][in.brand = 'Ferrari'].in_ from V where name = 'Tom'");
    Assert.assertEquals(result.stream().count(), 1);

    result = session.query("select out_[in.brand = 'Ferrari'].in_ from V where name = 'Tom'");
    Assert.assertEquals(result.stream().count(), 1);
  }

  public void testNotDuplicatedIndexTxChanges() throws IOException {
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
    session.bindToSession(vertexB).delete();
    session.bindToSession(vertexA).delete();

    var v = session.newVertex("vertexA");
    v.setProperty("name", "myKey");

    session.commit();
  }

  public void testNewVertexAndEdgesWithFieldsInOneShoot() {
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

    Assert.assertEquals(vertexA.getProperty("field1"), "value1");
    Assert.assertEquals(vertexA.getProperty("field2"), "value2");

    Assert.assertEquals(vertexB.getProperty("field1"), "value1");
    Assert.assertEquals(vertexB.getProperty("field2"), "value2");

    Assert.assertEquals(edgeC.getProperty("edgeF1"), "edgeV2");
  }

  @Test
  public void sqlNestedQueries() {
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

    var query1 = "select driver from V where out().car contains 'ford'";
    var result = session.query(query1);
    Assert.assertEquals(result.stream().count(), 1);

    var query2 = "select driver from V where outE()[color='red'].inV().car contains 'ford'";
    result = session.query(query2);
    Assert.assertEquals(result.stream().count(), 1);

    var query3 = "select driver from V where outE()[action='owns'].inV().car = 'ford'";
    result = session.query(query3);
    Assert.assertEquals(result.stream().count(), 1);

    var query4 =
        "select driver from V where outE()[color='red'][action='owns'].inV().car = 'ford'";
    result = session.query(query4);
    Assert.assertEquals(result.stream().count(), 1);
  }

  @SuppressWarnings("unchecked")
  public void nestedQuery() {
    session.createEdgeClass("owns");
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
    var result = session.query(subquery).stream().collect(Collectors.toList());

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(((Collection) result.get(0).getProperty("out")).size(), 2);

    subquery = "select expand(out('owns')) from V where name = 'UK'";
    result = session.query(subquery).stream().collect(Collectors.toList());

    Assert.assertEquals(result.size(), 2);
    for (var value : result) {
      Assert.assertTrue(value.hasProperty("lat"));
    }

    var query =
        "select name, lat, long, distance(lat,long,51.5,0.08) as distance from (select"
            + " expand(out('owns')) from V where name = 'UK') order by distance";
    result = session.query(query).stream().collect(Collectors.toList());

    Assert.assertEquals(result.size(), 2);
    for (var oResult : result) {
      Assert.assertTrue(oResult.hasProperty("lat"));
      Assert.assertTrue(oResult.hasProperty("distance"));
    }
  }

  public void testDeleteOfVerticesWithDeleteCommandMustFail() {
    try {
      session.execute("delete from GraphVehicle").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }
  }

  public void testInsertOfEdgeWithInsertCommand() {
    try {
      session.command(new CommandSQL("insert into E set a = 33")).execute(session);
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }
  }

  public void testInsertOfEdgeWithInsertCommandUnsafe() {
    session.begin();
    var insertedEdge =
        session
            .execute("insert into E set in = #9:0, out = #9:1, a = 33 unsafe")
            .next()
            .asEntity();
    session.commit();

    Assert.assertNotNull(insertedEdge);

    session.begin();
    var confirmDeleted =
        session
            .execute("delete from " + insertedEdge.getIdentity() + " unsafe")
            .next()
            .<Long>getProperty("count");
    session.commit();

    Assert.assertEquals(confirmDeleted.intValue(), 1);
  }

  public void testEmbeddedDoc() {
    session.createClass("NonVertex");

    session.begin();
    var vertex = session.newVertex();
    vertex.setProperty("name", "vertexWithEmbedded");

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("foo", "bar");

    vertex.setProperty("emb1", doc);

    var doc2 = ((EntityImpl) session.newEntity("V"));
    doc2.setProperty("foo", "bar1");
    vertex.setProperty("emb2", doc2, PropertyType.EMBEDDED);

    var doc3 = ((EntityImpl) session.newEntity("NonVertex"));
    doc3.setProperty("foo", "bar2");
    vertex.setProperty("emb3", doc3, PropertyType.EMBEDDED);

    var res1 = vertex.getProperty("emb1");
    Assert.assertNotNull(res1);
    Assert.assertTrue(res1 instanceof EntityImpl);

    var res2 = vertex.getProperty("emb2");
    Assert.assertNotNull(res2);
    Assert.assertFalse(res2 instanceof EntityImpl);

    var res3 = vertex.getProperty("emb3");
    Assert.assertNotNull(res3);
    Assert.assertTrue(res3 instanceof EntityImpl);
    session.commit();
  }
}
