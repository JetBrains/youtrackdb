package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransactionMetadataTest {

  private static final String ADMIN_PASSWORD = "adminpwd";
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionInternal db;
  private static final String DB_NAME = TransactionMetadataTest.class.getSimpleName();

  @Before
  public void before() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create(DB_NAME, DatabaseType.DISK, "admin", ADMIN_PASSWORD, "admin");

    db = youTrackDB.open(DB_NAME, "admin", ADMIN_PASSWORD);
  }

  @Test
  public void test() {
    db.begin();
    var metadata = new byte[]{1, 2, 4};
    db.getTransactionInternal()
        .setMetadataHolder(new TestTransacationMetadataHolder(metadata));
    var v = db.newVertex("V");
    v.setProperty("name", "Foo");
    db.commit();
    db.close();
    youTrackDB.close();

    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()), config);
    db = youTrackDB.open(DB_NAME, "admin", ADMIN_PASSWORD);

    var fromStorage = ((AbstractStorage) db.getStorage()).getLastMetadata();
    assertTrue(fromStorage.isPresent());
    assertArrayEquals(fromStorage.get(), metadata);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.drop(DB_NAME);
    if (youTrackDB.exists(DB_NAME + "_re")) {
      youTrackDB.drop(DB_NAME + "_re");
    }
    youTrackDB.close();
  }

  private static class TestTransacationMetadataHolder implements
      FrontendTransacationMetadataHolder {

    private final byte[] metadata;

    public TestTransacationMetadataHolder(byte[] metadata) {
      this.metadata = metadata;
    }

    @Override
    public byte[] metadata() {
      return metadata;
    }

    @Override
    public void notifyMetadataRead() {
    }

    @Override
    public FrontendTransactionId getId() {
      return null;
    }

    @Override
    public FrontendTransactionSequenceStatus getStatus() {
      return null;
    }
  }
}
