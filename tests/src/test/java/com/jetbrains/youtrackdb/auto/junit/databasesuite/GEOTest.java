/*
 * JUnit 4 version of GEOTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/GEOTest.java
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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of GEOTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/GEOTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class GEOTest extends BaseDBTest {

  private static GEOTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new GEOTest();
    instance.beforeClass();
  }

  /**
   * Original: geoSchema (line 28) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GEOTest.java
   */
  @Test
  public void test01_GeoSchema() {
    final var mapPointClass = session.getMetadata().getSchema().createClass("MapPoint");
    mapPointClass.createProperty("x", PropertyType.DOUBLE)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    mapPointClass.createProperty("y", PropertyType.DOUBLE)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    final var xIndexes =
        session.getMetadata().getSchema().getClassInternal("MapPoint")
            .getInvolvedIndexesInternal(session, "x");
    Assert.assertEquals(xIndexes.size(), 1);

    final var yIndexes =
        session.getMetadata().getSchema().getClassInternal("MapPoint")
            .getInvolvedIndexesInternal(session, "y");
    Assert.assertEquals(yIndexes.size(), 1);
  }

  /**
   * Original: checkGeoIndexes (line 46) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GEOTest.java Note: depends on geoSchema
   */
  @Test
  public void test02_CheckGeoIndexes() {
    final var xIndexes =
        session.getMetadata().getSchema().getClassInternal("MapPoint").
            getInvolvedIndexesInternal(session, "x");
    Assert.assertEquals(xIndexes.size(), 1);

    final var yIndexDefinitions =
        session.getMetadata().getSchema().getClassInternal("MapPoint")
            .getInvolvedIndexesInternal(session, "y");
    Assert.assertEquals(yIndexDefinitions.size(), 1);
  }

  /**
   * Original: queryCreatePoints (line 59) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GEOTest.java Note: depends on
   * checkGeoIndexes
   */
  @Test
  public void test03_QueryCreatePoints() {
    EntityImpl point;

    for (var i = 0; i < 10000; ++i) {
      session.begin();
      point = ((EntityImpl) session.newEntity("MapPoint"));

      point.setProperty("x", 52.20472d + i / 100d);
      point.setProperty("y", 0.14056d + i / 100d);

      session.commit();
    }
  }

  /**
   * Original: queryDistance (line 74) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GEOTest.java Note: depends on
   * queryCreatePoints
   */
  @Test
  public void test04_QueryDistance() {
    Assert.assertEquals(session.countClass("MapPoint"), 10000);

    var result =
        session
            .query(
                "select from MapPoint where distance(x, y,52.20472, 0.14056 ) <= 30").toList();
    Assert.assertFalse(result.isEmpty());

    for (var d : result) {
      Assert.assertEquals(d.asEntity().getSchemaClassName(), "MapPoint");
      Assert.assertEquals(((EntityImpl) d.asEntity()).getRecordType(), EntityImpl.RECORD_TYPE);
    }
  }

  /**
   * Original: queryDistanceOrdered (line 90) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/GEOTest.java Note: depends on
   * queryCreatePoints
   */
  @Test
  public void test05_QueryDistanceOrdered() {
    session.begin();
    Assert.assertEquals(session.countClass("MapPoint"), 10000);

    // MAKE THE FIRST RECORD DIRTY TO TEST IF DISTANCE JUMP IT
    var resultSet =
        session.execute("select from MapPoint limit 1").toList();
    try {
      resultSet.getFirst().asEntityOrNull().setProperty("x", "--wrong--");
      Assert.fail();
    } catch (NumberFormatException e) {
      Assert.assertTrue(true);
    }

    resultSet =
        executeQuery(
            "select distance(x, y,52.20472, 0.14056 ) as distance from MapPoint order by"
                + " distance desc");

    Assert.assertFalse(resultSet.isEmpty());

    Double lastDistance = null;
    for (var result : resultSet) {
      if (lastDistance != null && result.getProperty("distance") != null) {
        Assert.assertTrue(((Double) result.getProperty("distance")).compareTo(lastDistance) <= 0);
      }
      lastDistance = result.getProperty("distance");
    }
  }
}
