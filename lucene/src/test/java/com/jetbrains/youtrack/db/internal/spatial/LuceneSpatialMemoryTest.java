/*
 *
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 * <p>
 * *
 */
package com.jetbrains.youtrack.db.internal.spatial;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.lucene.tests.LuceneBaseTest;
import com.jetbrains.youtrack.db.internal.spatial.collections.SpatialCompositeKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class LuceneSpatialMemoryTest extends LuceneBaseTest {
  @Test
  @Ignore
  public void boundingBoxTest() {
    var point = session.getMetadata().getSchema().createClass("Point");
    point.createProperty("latitude", PropertyType.DOUBLE);
    point.createProperty("longitude", PropertyType.DOUBLE);

    session.execute("CREATE INDEX Point.ll ON Point(latitude,longitude) SPATIAL ENGINE LUCENE")
        .close();

    session.begin();
    var document = ((EntityImpl) session.newEntity("Point"));

    document.setProperty("latitude", 42.2814837);
    document.setProperty("longitude", -83.7605452);

    session.commit();

    session.begin();
    var query = session.query(
        "SELECT FROM Point WHERE [latitude, longitude] WITHIN"
            + " [[42.26531323615103,-83.71986351411135],[42.29239784478525,-83.7662120858887]]"
    ).toList();

    Assert.assertEquals(1, query.size());
    session.commit();
  }

  @Test
  @Ignore
  public void boundingBoxTestTxRollBack() {
    var point = session.getMetadata().getSchema().createClass("Point");
    point.createProperty("latitude", PropertyType.DOUBLE);
    point.createProperty("longitude", PropertyType.DOUBLE);

    session.execute("CREATE INDEX Point.ll ON Point(latitude,longitude) SPATIAL ENGINE LUCENE")
        .close();

    session.begin();

    session.begin();
    var document = ((EntityImpl) session.newEntity("Point"));

    document.setProperty("latitude", 42.2814837);
    document.setProperty("longitude", -83.7605452);
    session.commit();

    var query = session.query(
        "SELECT FROM Point WHERE [latitude, longitude] WITHIN"
            + " [[42.26531323615103,-83.71986351411135],[42.29239784478525,-83.7662120858887]]"
    ).toList();

    Assert.assertEquals(1, query.size());

    var oSpatialCompositeKey =
        new SpatialCompositeKey(
            new ArrayList<List<Number>>() {
              {
                add(
                    new ArrayList<>() {
                      {
                        add(42.26531323615103);
                        add(-83.71986351411135);
                      }
                    });
                add(
                    new ArrayList<>() {
                      {
                        add(42.29239784478525);
                        add(-83.7662120858887);
                      }
                    });
              }
            })
            .setOperation(SpatialOperation.IsWithin);
    var index = session.getSharedContext().getIndexManager().getIndex("Point.ll");

    var baseContext = new BasicCommandContext();
    baseContext.setDatabaseSession(session);
    oSpatialCompositeKey.setContext(baseContext);

    Collection<?> coll;
    try (var stream = index.getRids(session, oSpatialCompositeKey)) {
      coll = stream.toList();
    }
    Assert.assertEquals(1, coll.size());
    session.rollback();

    query = session.query(
        "SELECT FROM Point WHERE [latitude, longitude] WITHIN"
            + " [[42.26531323615103,-83.71986351411135],[42.29239784478525,-83.7662120858887]]"
    ).toList();

    Assert.assertEquals(0, query.size());
  }

  @Test
  @Ignore
  public void boundingBoxTestTxCommit() {
    var point = session.getMetadata().getSchema().createClass("Point");
    point.createProperty("latitude", PropertyType.DOUBLE);
    point.createProperty("longitude", PropertyType.DOUBLE);

    session.execute("CREATE INDEX Point.ll ON Point(latitude,longitude) SPATIAL ENGINE LUCENE")
        .close();

    session.begin();

    var document = ((EntityImpl) session.newEntity("Point"));

    document.setProperty("latitude", 42.2814837);
    document.setProperty("longitude", -83.7605452);

    session.commit();

    var query = session.query(
        "SELECT FROM Point WHERE [latitude, longitude] WITHIN"
            + " [[42.26531323615103,-83.71986351411135],[42.29239784478525,-83.7662120858887]]"
    ).toList();

    Assert.assertEquals(1, query.size());

    var oSpatialCompositeKey =
        new SpatialCompositeKey(
            new ArrayList<List<Number>>() {
              {
                add(
                    new ArrayList<>() {
                      {
                        add(42.26531323615103);
                        add(-83.71986351411135);
                      }
                    });
                add(
                    new ArrayList<>() {
                      {
                        add(42.29239784478525);
                        add(-83.7662120858887);
                      }
                    });
              }
            })
            .setOperation(SpatialOperation.IsWithin);
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    oSpatialCompositeKey.setContext(context);

    var index = session.getSharedContext().getIndexManager().getIndex("Point.ll");

    Collection<?> coll;
    try (var stream = index.getRids(session, oSpatialCompositeKey)) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(1, coll.size());

    session.begin();

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    session.delete(document);

    query = session.query(
        "SELECT FROM Point WHERE [latitude, longitude] WITHIN"
            + " [[42.26531323615103,-83.71986351411135],[42.29239784478525,-83.7662120858887]]"
    ).toList();

    Assert.assertEquals(0, query.size());

    try (var stream = index.getRids(session, oSpatialCompositeKey)) {
      coll = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(0, coll.size());

    session.rollback();

    query = session.query(
        "SELECT FROM Point WHERE [latitude, longitude] WITHIN"
            + " [[42.26531323615103,-83.71986351411135],[42.29239784478525,-83.7662120858887]]"
    ).toList();

    Assert.assertEquals(1, query.size());

  }
}
