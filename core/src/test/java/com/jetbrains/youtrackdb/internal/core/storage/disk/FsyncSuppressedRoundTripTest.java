package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogGL;
import com.jetbrains.youtrackdb.internal.core.storage.fs.AsyncFile;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.MockedStatic;

/** Clean-close disk round trips for durable and fsync-suppressed storage modes. */
@Category(SequentialTest.class)
public class FsyncSuppressedRoundTripTest extends DbTestBase {

  private Object previousCallFsync;

  @Override
  protected DatabaseType calculateDbType() {
    return DatabaseType.DISK;
  }

  @Before
  @Override
  public void beforeTest() throws Exception {
    previousCallFsync = GlobalConfiguration.STORAGE_CALL_FSYNC.getValue();
    GlobalConfiguration.STORAGE_CALL_FSYNC.setValue(
        !name.getMethodName().contains("WithoutFsync"));
    super.beforeTest();
  }

  @After
  @Override
  public void afterTest() {
    try {
      super.afterTest();
    } finally {
      GlobalConfiguration.STORAGE_CALL_FSYNC.setValue(previousCallFsync);
    }
  }

  /**
   * Schema, indexes, and file registry mappings survive a clean close without fsync.
   */
  @Test
  public void testCleanCloseReopenWithoutFsyncPreservesSchemaAndRegistry() {
    assertFalse(readCallFsync(session));
    assertProductionWiringFsyncMode(session, false, "productionWiringProbe.tst");
    createScenario(session);

    reopenStorage();

    assertProductionWiringFsyncMode(session, false, "productionWiringProbe.tst");
    assertScenario(session);
    final var storage = (AbstractStorage) session.getStorage();
    assertFalse("clean reopen must not run WAL recovery", storage.wereDataRestoredAfterOpen());
    assertRegistryMappings(storage);
  }

  /**
   * The same round trip keeps durable mode enabled while preserving schema and registry data.
   */
  @Test
  public void testCleanCloseReopenWithFsyncKeepsDurableMode() {
    assertTrue(readCallFsync(session));
    assertProductionWiringFsyncMode(session, true, "durableProductionWiringProbe.tst");
    createScenarioAndAssertRegistryForce(session);

    reopenStorage();

    assertProductionWiringFsyncMode(session, true, "durableProductionWiringProbe.tst");
    assertScenario(session);
    final var storage = (AbstractStorage) session.getStorage();
    assertFalse("durable clean reopen must not run WAL recovery",
        storage.wereDataRestoredAfterOpen());
    assertRegistryMappings(storage);
  }

  private void reopenStorage() {
    session.close();
    pool.close();
    youTrackDB.close();

    youTrackDB = createContext();
    pool = youTrackDB.cachedPool(databaseName, adminUser, adminPassword);
    session = openDatabase();
  }

  private static void assertProductionWiringFsyncMode(
      DatabaseSessionEmbedded database, boolean expectedFsync, String fileName) {
    try {
      final var writeCache = (WOWCache) database.getStorage().getWriteCache();
      var fileId = writeCache.fileIdByName(fileName);
      if (fileId < 0) {
        fileId = writeCache.addFile(fileName);
      }

      final Field filesField = WOWCache.class.getDeclaredField("files");
      filesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      final var liveFiles =
          (ClosableLinkedContainer<Long, File>) filesField.get(writeCache);
      final var fileEntry = liveFiles.acquire(fileId);
      assertNotNull("production-created file must be registered", fileEntry);
      try {
        assertTrue("production wiring must create AsyncFile", fileEntry.get() instanceof AsyncFile);
        final Field fileFsyncField = AsyncFile.class.getDeclaredField("callFsync");
        fileFsyncField.setAccessible(true);
        assertEquals(
            "live AsyncFile must inherit the configured fsync mode",
            expectedFsync,
            fileFsyncField.getBoolean(fileEntry.get()));
      } finally {
        liveFiles.release(fileEntry);
      }

      final Field doubleWriteLogField = WOWCache.class.getDeclaredField("doubleWriteLog");
      doubleWriteLogField.setAccessible(true);
      final var doubleWriteLog = doubleWriteLogField.get(writeCache);
      assertTrue("production wiring must create DoubleWriteLogGL",
          doubleWriteLog instanceof DoubleWriteLogGL);
      final Field dwlFsyncField = DoubleWriteLogGL.class.getDeclaredField("callFsync");
      dwlFsyncField.setAccessible(true);
      assertEquals(
          "live double-write log must inherit the configured fsync mode",
          expectedFsync,
          dwlFsyncField.getBoolean(doubleWriteLog));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while inspecting production fsync wiring", e);
    } catch (ReflectiveOperationException | java.io.IOException e) {
      throw new AssertionError("Cannot inspect production fsync wiring", e);
    }
  }

  private static void createScenarioAndAssertRegistryForce(DatabaseSessionEmbedded database) {
    try {
      final var writeCache = (WOWCache) database.getStorage().getWriteCache();
      final Field pathField = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
      pathField.setAccessible(true);
      final var registryPath = (Path) pathField.get(writeCache);
      final var channel =
          spy(
              FileChannel.open(
                  registryPath, StandardOpenOption.READ, StandardOpenOption.WRITE));

      try (MockedStatic<FileChannel> mockedChannels =
          mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
        mockedChannels
            .when(
                () -> FileChannel.open(
                    registryPath, StandardOpenOption.READ, StandardOpenOption.WRITE))
            .thenReturn(channel);
        writeCache.addFile("durableForceProbe.tst");
      }
      verify(channel).force(true);
      createScenario(database);
    } catch (ReflectiveOperationException | java.io.IOException e) {
      throw new AssertionError("Cannot observe durable registry forces", e);
    }
  }

  private static void createScenario(DatabaseSessionEmbedded database) {
    for (var i = 0; i < 3; i++) {
      final var className = "FsyncRoundTrip" + i;
      final var propertyName = "value" + i;
      final var indexName = className + "." + propertyName;
      final var schemaClass = database.getMetadata().getSchema().createClass(className);
      schemaClass.createProperty(propertyName, PropertyType.STRING);
      schemaClass.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, propertyName);
    }
  }

  private static void assertScenario(DatabaseSessionEmbedded database) {
    final var schema = database.getMetadata().getSchema();
    for (var i = 0; i < 3; i++) {
      final var className = "FsyncRoundTrip" + i;
      final var propertyName = "value" + i;
      final var indexName = className + "." + propertyName;
      final var schemaClass = schema.getClass(className);
      assertNotNull("class must survive reopen", schemaClass);
      assertNotNull("property must survive reopen", schemaClass.getProperty(propertyName));
      assertTrue("index must survive reopen", schema.getIndexes().contains(indexName));
    }
  }

  private static void assertRegistryMappings(AbstractStorage storage) {
    final var writeCache = storage.getWriteCache();
    final var registeredFiles = writeCache.files();
    assertFalse("disk storage must register files", registeredFiles.isEmpty());
    for (final var fileName : registeredFiles.keySet()) {
      assertTrue("every registry name must map to a file id",
          writeCache.fileIdByName(fileName) >= 0);
    }
  }

  private static boolean readCallFsync(DatabaseSessionEmbedded database) {
    try {
      final var writeCache = database.getStorage().getWriteCache();
      assertTrue("disk storage must use WOWCache", writeCache instanceof WOWCache);
      final Field field = WOWCache.class.getDeclaredField("callFsync");
      field.setAccessible(true);
      return field.getBoolean(writeCache);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Cannot inspect WOWCache fsync mode", e);
    }
  }
}
