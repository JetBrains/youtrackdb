package com.jetbrains.youtrack.db.internal.spatial;

import org.junit.Assert;
import org.junit.Test;

public class GeometryCollectionTest extends BaseSpatialLuceneTest {

  @Test
  public void testDeleteVerticesWithGeometryCollection() {
    session.execute("CREATE CLASS TestInsert extends V").close();
    session.execute("CREATE PROPERTY TestInsert.name STRING").close();
    session.execute("CREATE PROPERTY TestInsert.geometry EMBEDDED OGeometryCollection").close();

    session.execute(
            "CREATE INDEX TestInsert.geometry ON TestInsert(geometry) SPATIAL ENGINE LUCENE")
        .close();

    session.begin();
    session.execute(
            "insert into TestInsert content {'name': 'loc1', 'geometry':"
                + " {'@type':'d','@class':'OGeometryCollection','geometries':[{'@type':'d','@class':'OPolygon','coordinates':[[[0,0],[0,10],[10,10],[10,0],[0,0]]]}]}}")
        .close();
    session.execute(
            "insert into TestInsert content {'name': 'loc2', 'geometry':"
                + " {'@type':'d','@class':'OGeometryCollection','geometries':[{'@type':'d','@class':'OPolygon','coordinates':[[[0,0],[0,20],[20,20],[20,0],[0,0]]]}]}}")
        .close();
    session.commit();

    session.begin();
    var qResult =
        session.execute(
            "select * from TestInsert where ST_WITHIN(geometry,'POLYGON ((0 0, 15 0, 15 15, 0 15, 0"
                + " 0))') = true");
    Assert.assertEquals(1, qResult.stream().count());

    session.execute("DELETE VERTEX TestInsert").close();
    session.commit();

    session.begin();
    var qResult2 = session.execute("select * from TestInsert");
    Assert.assertEquals(0, qResult2.stream().count());
    session.commit();
  }
}
