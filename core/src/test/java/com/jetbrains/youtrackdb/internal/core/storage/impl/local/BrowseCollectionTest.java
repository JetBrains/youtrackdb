package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BrowseCollectionTest {

  private static final String PASSWORD = "adminpwd";
  private DatabaseSessionEmbedded db;
  private YouTrackDBImpl youTrackDb;

  @Before
  public void before() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()),
            config);
    youTrackDb.create("test", DatabaseType.MEMORY,
        new LocalUserCredential("admin", PASSWORD, PredefinedLocalRole.ADMIN));
    db = youTrackDb.open("test", "admin", PASSWORD);
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

    db.begin();
    var activeTx = (FrontendTransactionImpl) db.getActiveTransaction();
    var atomicOperation = activeTx.getAtomicOperation();
    var forwardBrowser =
        ((AbstractStorage) db.getStorage())
            .browseCollection(collection, true, atomicOperation);

    final var forwardPositions = new ArrayList<Long>();
    while (forwardBrowser.hasNext()) {
      var page = forwardBrowser.next();
      for (var entry : page) {
        assertNotNull(entry.buffer());
        forwardPositions.add(entry.collectionPosition());
      }
    }
    assertEquals(numberOfEntries, forwardPositions.size());
    assertTrue(ArrayUtils.isSorted(forwardPositions.stream().mapToLong(Long::longValue).toArray()));

    var backwardBrowser =
        ((AbstractStorage) db.getStorage())
            .browseCollection(collection, false, atomicOperation);
    final var backwardPositions = new ArrayList<Long>();
    while (backwardBrowser.hasNext()) {
      var page = backwardBrowser.next();
      for (var entry : page) {
        assertNotNull(entry.buffer());
        backwardPositions.add(entry.collectionPosition());
      }
    }

    assertEquals(
        forwardPositions.reversed(),
        backwardPositions
    );
    activeTx.rollback();
  }

  @After
  public void after() {
    db.close();
    youTrackDb.close();
  }
}
