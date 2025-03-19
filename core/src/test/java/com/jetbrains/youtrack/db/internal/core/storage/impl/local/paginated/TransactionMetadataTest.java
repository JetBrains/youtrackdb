package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransacationMetadataHolder;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionId;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionSequenceStatus;
import java.io.File;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransactionMetadataTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionInternal db;
  private static final String DB_NAME = TransactionMetadataTest.class.getSimpleName();

  @Before
  public void before() {

    youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database `" + DB_NAME + "` disk users(admin identified by 'admin' role admin)");
    db = (DatabaseSessionInternal) youTrackDB.open(DB_NAME, "admin", "admin");
  }

  @Test
  public void testBackupRestore() {
    db.begin();
    var metadata = new byte[]{1, 2, 4};
    db.getTransactionInternal()
        .setMetadataHolder(new TestTransacationMetadataHolder(metadata));
    var v = db.newVertex("V");
    v.setProperty("name", "Foo");
    db.commit();
    db.incrementalBackup(Path.of("target/backup_metadata"));
    db.close();
    YouTrackDBInternal.extract(youTrackDB)
        .restore(
            DB_NAME + "_re",
            null,
            null,
            DatabaseType.DISK,
            "target/backup_metadata",
            YouTrackDBConfig.defaultConfig());
    var db1 = youTrackDB.open(DB_NAME + "_re", "admin", "admin");
    var fromStorage =
        ((AbstractPaginatedStorage) ((DatabaseSessionInternal) db1).getStorage())
            .getLastMetadata();
    assertTrue(fromStorage.isPresent());
    assertArrayEquals(fromStorage.get(), metadata);
  }

  @After
  public void after() {
    FileUtils.deleteRecursively(new File("target/backup_metadata"));
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
