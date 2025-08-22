package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BrowseCollectionTest {

  private DatabaseSession db;
  private YouTrackDB youTrackDb;

  @Before
  public void before() {
    youTrackDb =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CLASS_COLLECTIONS_COUNT, 1)
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    youTrackDb.execute(
        "create database "
            + "test"
            + " "
            + "memory"
            + " users ( admin identified by '"
            + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
            + "' role admin)");
    db = youTrackDb.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    db.getSchema().createVertexClass("One");
  }

  @Test
  public void testBrowse() {
    var numberOfEntries = 4962;
    for (var i = 0; i < numberOfEntries; i++) {
      var tx = db.begin();
      var v = tx.newVertex("One");
      v.setProperty("a", i);
      tx.commit();
    }
    var collection = db.getSchema().getClass("One").getCollectionIds()[0];
    var browser =
        ((AbstractStorage) ((DatabaseSessionInternal) db).getStorage())
            .browseCollection(collection);
    var count = 0;

    while (browser.hasNext()) {
      var page = browser.next();
      for (var entry : page) {
        count++;
        assertNotNull(entry.buffer());
        assertNotNull(entry.collectionPosition());
      }
    }
    assertEquals(numberOfEntries, count);
  }

  @After
  public void after() {
    db.close();
    youTrackDb.close();
  }
}
