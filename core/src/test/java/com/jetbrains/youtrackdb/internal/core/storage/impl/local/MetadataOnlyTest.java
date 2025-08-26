package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MetadataOnlyTest {

  private YouTrackDBImpl youTrackDb;

  @Before
  public void before() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);
    youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(getClass()),
            config);
    youTrackDb.execute(
        "create database testMetadataOnly disk users (admin identified by 'admin' role admin)");
  }

  @Test
  public void test() {
    var db = youTrackDb.open("testMetadataOnly", "admin", "admin");
    var blob =
        new byte[]{
            1, 2, 3, 4, 5, 6,
        };
    ((AbstractStorage) ((DatabaseSessionInternal) db).getStorage()).metadataOnly(blob);
    db.close();
    YouTrackDBInternal.extract((YouTrackDBImpl) youTrackDb).forceDatabaseClose(
        "testMetadataOnly");
    db = youTrackDb.open("testMetadataOnly", "admin", "admin");
    var loaded =
        ((AbstractStorage) ((DatabaseSessionInternal) db).getStorage())
            .getLastMetadata();
    assertTrue(loaded.isPresent());
    assertArrayEquals(loaded.get(), blob);
    db.close();
  }

  @After
  public void after() {

    youTrackDb.drop("testMetadataOnly");
    youTrackDb.close();
  }
}
