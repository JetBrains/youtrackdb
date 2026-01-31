/*
 * JUnit 4 version of GraphDatabaseTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of GraphDatabaseTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class GraphDatabaseTest extends BaseDBTest {

  private static GraphDatabaseTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new GraphDatabaseTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: populate (line 36) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
   */
  @Test
  public void test00_Populate() {
    generateGraphData();
  }

  /**
   * Original: testSQLAgainstGraph (line 41) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
   */
  @Test
  public void test01_SQLAgainstGraph() {
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
    session.commit();
  }

  /**
   * Original: testNotDuplicatedIndexTxChanges (line 81) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
   */
  @Test
  public void test02_NotDuplicatedIndexTxChanges() {
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

  /**
   * Original: testNewVertexAndEdgesWithFieldsInOneShoot (line 112) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
   */
  @Test
  public void test03_NewVertexAndEdgesWithFieldsInOneShoot() {
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

    Assert.assertEquals(vertexA.getProperty("field1"), "value1");
    Assert.assertEquals(vertexA.getProperty("field2"), "value2");

    Assert.assertEquals(vertexB.getProperty("field1"), "value1");
    Assert.assertEquals(vertexB.getProperty("field2"), "value2");

    Assert.assertEquals(edgeC.getProperty("edgeF1"), "edgeV2");
    session.commit();
  }

  /**
   * Original: testDeleteOfVerticesWithDeleteCommandMustFail (line 238) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
   */
  @Test
  public void test04_DeleteOfVerticesWithDeleteCommandMustFail() {
    session.begin();
    try {
      session.execute("delete from GraphVehicle").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
      session.rollback();
    }
  }

  /**
   * Original: testInsertOfEdgeWithInsertCommand (line 249) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
   */
  @Test
  public void test05_InsertOfEdgeWithInsertCommand() {
    try {
      session.command("insert into E set a = 33");
      Assert.fail();
    } catch (DatabaseException e) {
      Assert.assertTrue(true);
    }
  }

  /**
   * Original: testEmbeddedDoc (line 258) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GraphDatabaseTest.java
   */
  @Test
  public void test06_EmbeddedDoc() {
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
    Assert.assertNotNull(res1);
    Assert.assertTrue(res1 instanceof EntityImpl);

    var res3 = vertex.getProperty("emb3");
    Assert.assertNotNull(res3);
    Assert.assertTrue(res3 instanceof EntityImpl);
    session.commit();
  }

}
