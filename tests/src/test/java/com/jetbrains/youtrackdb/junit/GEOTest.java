// Migrated from: com.jetbrains.youtrackdb.auto.GEOTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class GEOTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.GEOTest#geoSchema
  @Test
  @Order(1)
  void geoSchema() {
    final var mapPointClass = session.getMetadata().getSchema().createClass("MapPoint");
    mapPointClass.createProperty("x", PropertyType.DOUBLE)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    mapPointClass.createProperty("y", PropertyType.DOUBLE)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    final var xIndexes =
        session.getMetadata().getSchema().getClassInternal("MapPoint")
            .getInvolvedIndexesInternal(session, "x");
    assertEquals(1, xIndexes.size());

    final var yIndexes =
        session.getMetadata().getSchema().getClassInternal("MapPoint")
            .getInvolvedIndexesInternal(session, "y");
    assertEquals(1, yIndexes.size());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GEOTest#checkGeoIndexes
  @Test
  @Order(2)
  void checkGeoIndexes() {
    final var xIndexes =
        session.getMetadata().getSchema().getClassInternal("MapPoint")
            .getInvolvedIndexesInternal(session, "x");
    assertEquals(1, xIndexes.size());

    final var yIndexDefinitions =
        session.getMetadata().getSchema().getClassInternal("MapPoint")
            .getInvolvedIndexesInternal(session, "y");
    assertEquals(1, yIndexDefinitions.size());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GEOTest#queryCreatePoints
  @Test
  @Order(3)
  void queryCreatePoints() {
    EntityImpl point;

    for (var i = 0; i < 10000; ++i) {
      session.begin();
      point = ((EntityImpl) session.newEntity("MapPoint"));

      point.setProperty("x", 52.20472d + i / 100d);
      point.setProperty("y", 0.14056d + i / 100d);

      session.commit();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GEOTest#queryDistance
  @Test
  @Order(4)
  void queryDistance() {
    session.begin();
    assertEquals(10000, session.countClass("MapPoint"));

    var result =
        session
            .query(
                "select from MapPoint where distance(x, y,52.20472, 0.14056 ) <= 30")
            .toList();
    assertFalse(result.isEmpty());

    for (var d : result) {
      assertEquals("MapPoint", d.asEntity().getSchemaClassName());
      assertEquals(EntityImpl.RECORD_TYPE, ((EntityImpl) d.asEntity()).getRecordType());
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.GEOTest#queryDistanceOrdered
  @Test
  @Order(5)
  void queryDistanceOrdered() {
    session.begin();
    assertEquals(10000, session.countClass("MapPoint"));

    // MAKE THE FIRST RECORD DIRTY TO TEST IF DISTANCE JUMP IT
    var resultSet =
        session.execute("select from MapPoint limit 1").toList();
    try {
      resultSet.getFirst().asEntityOrNull().setProperty("x", "--wrong--");
      assertTrue(false, "Should have thrown NumberFormatException");
    } catch (NumberFormatException e) {
      assertTrue(true);
    }

    resultSet =
        executeQuery(
            "select distance(x, y,52.20472, 0.14056 ) as distance from MapPoint order by"
                + " distance desc");

    assertFalse(resultSet.isEmpty());

    Double lastDistance = null;
    for (var result : resultSet) {
      if (lastDistance != null && result.getProperty("distance") != null) {
        assertTrue(((Double) result.getProperty("distance")).compareTo(lastDistance) <= 0);
      }
      lastDistance = result.getProperty("distance");
    }
  }
}
