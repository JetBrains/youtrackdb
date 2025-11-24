package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransacationMetadataHolder;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionId;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionSequenceStatus;
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
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
    db = youTrackDB.open(DB_NAME, "admin", DbTestBase.ADMIN_PASSWORD);
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
    YouTrackDBInternal.extract((YouTrackDBImpl) youTrackDB)
        .restore(
            DB_NAME + "_re",
            DatabaseType.DISK,
            "target/backup_metadata",
            YouTrackDBConfig.defaultConfig());
    var db1 = youTrackDB.open(DB_NAME + "_re", "admin", DbTestBase.ADMIN_PASSWORD);
    var fromStorage =
        ((AbstractStorage) ((DatabaseSessionInternal) db1).getStorage())
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
