package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Verifies that disabled storage durability logs only one warning per JVM. */
@Category(SequentialTest.class)
public class DiskStorageFsyncWarningTest extends DbTestBase {

  private static final String WARNING_MESSAGE =
      "Storage durability barriers are disabled by youtrackdb.storage.callFsync. "
          + "A power loss can lose data. Use this mode only for tests.";

  private Object previousCallFsync;
  private AtomicBoolean warningState;
  private boolean previousWarningState;
  private Logger rootLogger;
  private CapturingHandler capturingHandler;
  private Level previousLevel;

  @Override
  protected DatabaseType calculateDbType() {
    return DatabaseType.DISK;
  }

  @Before
  @Override
  public void beforeTest() throws Exception {
    previousCallFsync = GlobalConfiguration.STORAGE_CALL_FSYNC.getValue();
    GlobalConfiguration.STORAGE_CALL_FSYNC.setValue(false);
    warningState = warningState();
    previousWarningState = warningState.get();
    warningState.set(false);
    rootLogger = Logger.getLogger("");
    capturingHandler = new CapturingHandler();
    previousLevel = rootLogger.getLevel();
    rootLogger.addHandler(capturingHandler);
    rootLogger.setLevel(Level.ALL);
    super.beforeTest();
  }

  @After
  @Override
  public void afterTest() {
    try {
      super.afterTest();
    } finally {
      rootLogger.removeHandler(capturingHandler);
      rootLogger.setLevel(previousLevel);
      warningState.set(previousWarningState);
      GlobalConfiguration.STORAGE_CALL_FSYNC.setValue(previousCallFsync);
    }
  }

  /** A disk storage open emits the warning, and a second disabled open emits no duplicate. */
  @Test
  public void disabledFsyncWarningIsEmittedOnceThroughStorageOpen() {
    assertTrue(warningState.get());
    reopenStorage();

    assertEquals(1, warningRecords().size());
    assertEquals(WARNING_MESSAGE, warningRecords().get(0).getMessage());
    assertFalse(DiskStorage.warnIfFsyncDisabled(false));
  }

  private void reopenStorage() {
    session.close();
    pool.close();
    youTrackDB.close();

    youTrackDB = createContext();
    pool = youTrackDB.cachedPool(databaseName, adminUser, adminPassword);
    session = openDatabase();
  }

  private List<LogRecord> warningRecords() {
    return capturingHandler.records.stream()
        .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
        .toList();
  }

  private static AtomicBoolean warningState() throws Exception {
    final Field warningField = DiskStorage.class.getDeclaredField("fsyncWarningLogged");
    warningField.setAccessible(true);
    return (AtomicBoolean) warningField.get(null);
  }

  private static final class CapturingHandler extends Handler {
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void publish(final LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  }
}
