package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BrowseCollectionTest {

  private DatabaseSessionEmbedded db;
  private YouTrackDBImpl youTrackDb;

  @Before
  public void before() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()), config);
    youTrackDb.execute(
        "create database "
            + "test"
            + " "
            + "memory"
            + " users ( admin identified by '"
            + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
            + "' role admin)");
    db = (DatabaseSessionEmbedded) youTrackDb.open("test", "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    try (var graph = youTrackDb.openGraph("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
      graph.autoExecuteInTx(g -> g.addSchemaClass("One"));
    }
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
    var collection = db.getMetadata().getFastImmutableSchema().getClass("One")
        .getCollectionIds()[0];
    var browser =
        db.getStorage().browseCollection(collection);
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
