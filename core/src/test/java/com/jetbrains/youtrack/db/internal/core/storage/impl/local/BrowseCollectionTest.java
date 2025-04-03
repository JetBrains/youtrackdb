package com.jetbrains.youtrack.db.internal.core.storage.impl.local;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BrowseCollectionTest {

  private DatabaseSession db;
  private YouTrackDB youTrackDb;

  @Before
  public void before() {
    youTrackDb =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
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
        ((AbstractPaginatedStorage) ((DatabaseSessionInternal) db).getStorage())
            .browseCollection(collection);
    var count = 0;

    while (browser.hasNext()) {
      var page = browser.next();
      for (var entry : page) {
        count++;
        assertNotNull(entry.getBuffer());
        assertNotNull(entry.getCollectionPosition());
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
