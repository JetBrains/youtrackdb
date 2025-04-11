package com.jetbrains.youtrack.db.internal.core.tx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransactionMetadataTest {

  private YouTrackDB youTrackDB;
  private DatabaseSessionInternal db;
  private static final String DB_NAME = TransactionMetadataTest.class.getSimpleName();

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase(
            DB_NAME, DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_DISK);
    db =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
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

    youTrackDB =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    db =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

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
