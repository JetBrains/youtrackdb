package com.jetbrains.youtrack.db.internal.spatial;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.lucene.tests.LuceneBaseTest;
import java.io.File;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
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
    youTrackDB.createIfNotExists(dbName, DatabaseType.DISK,
        "admin", "adminpwd", "admin");

    try (var db = youTrackDB.open(dbName, "admin", "adminpwd")) {
      var test = db.getSchema().createClass("test");
      test.createProperty("name", PropertyType.STRING);
      test.createProperty("latitude", PropertyType.DOUBLE).setMandatory(false);
      test.createProperty("longitude", PropertyType.DOUBLE).setMandatory(false);
      db.computeScript("sql", "create index test.name on test (name) FULLTEXT ENGINE LUCENE").close();
      db.computeScript("sql", "create index test.ll on test (latitude,longitude) SPATIAL ENGINE LUCENE")
          .close();
    }
  }

  @Test
  @Ignore
  public void testDeleteLuceneIndex1() {
    try (var dpPool = youTrackDB.cachedPool(dbName, "admin", "adminpwd")) {
      var db = (DatabaseSessionInternal) dpPool.acquire();
      fillDb(db, insertcount);
      db.close();

      db = (DatabaseSessionInternal) dpPool.acquire();
      var query = "select from test where [latitude,longitude] WITHIN [[50.0,8.0],[51.0,9.0]]";
      final var result = db.query(query).toList();
      Assert.assertEquals(insertcount, result.size());
      db.close();
      dpPool.close();

      var dbFolder = new File(dbName);
      Assert.assertFalse(dbFolder.exists());
    }

  }

  private static void fillDb(DatabaseSession db, int count) {
    db.executeInTx(transaction -> {
      for (var i = 0; i < count; i++) {
        var doc = ((EntityImpl) transaction.newEntity("test"));
        doc.setProperty("name", "TestInsert" + i);
        doc.setProperty("latitude", 50.0 + (i * 0.000001));
        doc.setProperty("longitude", 8.0 + (i * 0.000001));
      }
    });

    db.executeInTx(transaction -> {
      var result = transaction.query("select * from test");
      Assert.assertEquals(count, result.stream().count());
    });
  }
}
