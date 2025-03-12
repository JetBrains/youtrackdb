package com.jetbrains.youtrack.db.internal.spatial;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import com.jetbrains.youtrack.db.internal.lucene.tests.LuceneBaseTest;
import java.io.File;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LuceneSpatialDropTest {

  private int insertcount;
  private String dbName;
  private YouTrackDB youTrackDB;

  @Before
  public void setUp() throws Exception {

    dbName = this.getClass().getSimpleName();

    // @maggiolo00 set cont to 0 and the test will not fail anymore
    insertcount = 100;

    final var dbPath = LuceneBaseTest.getBaseDirectoryPath(getClass());

    // clean up the data from the previous runs
    FileUtils.deleteRecursively(new File(dbPath));
    youTrackDB = YourTracks.embedded(dbPath);
    youTrackDB.createIfNotExists(dbName, DatabaseType.PLOCAL,
        "admin", "adminpwd", "admin");

    try (var db = youTrackDB.open(dbName, "admin", "adminpwd")) {
      var test = db.getSchema().createClass("test");
      test.createProperty("name", PropertyType.STRING);
      test.createProperty("latitude", PropertyType.DOUBLE).setMandatory(false);
      test.createProperty("longitude", PropertyType.DOUBLE).setMandatory(false);
      db.command("create index test.name on test (name) FULLTEXT ENGINE LUCENE").close();
      db.command("create index test.ll on test (latitude,longitude) SPATIAL ENGINE LUCENE").close();
    }
  }

  @Test
  public void testDeleteLuceneIndex1() {
    try (var dpPool = youTrackDB.cachedPool(dbName, "admin", "adminpwd")) {
      var db = (DatabaseSessionInternal) dpPool.acquire();
      fillDb(db, insertcount);
      db.close();

      db = (DatabaseSessionInternal) dpPool.acquire();
      var query =
          new SQLSynchQuery<EntityImpl>(
              "select from test where [latitude,longitude] WITHIN [[50.0,8.0],[51.0,9.0]]");
      List<EntityImpl> result = db.command(query).execute(db);
      Assert.assertEquals(insertcount, result.size());
      db.close();
      dpPool.close();

      var dbFolder = new File(dbName);
      Assert.assertFalse(dbFolder.exists());
    }

  }

  private static void fillDb(DatabaseSession db, int count) {
    for (var i = 0; i < count; i++) {
      var doc = ((EntityImpl) db.newEntity("test"));
      doc.field("name", "TestInsert" + i);
      doc.field("latitude", 50.0 + (i * 0.000001));
      doc.field("longitude", 8.0 + (i * 0.000001));

      db.begin();
      db.commit();
    }
    var result = db.query("select * from test");
    Assert.assertEquals(count, result.stream().count());
  }
}
