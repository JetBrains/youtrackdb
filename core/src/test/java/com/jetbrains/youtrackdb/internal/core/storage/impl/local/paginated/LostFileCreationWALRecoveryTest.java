package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionStateV2;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercises recovery from a durable WAL with the file-creation apply phase absent. */
public class LostFileCreationWALRecoveryTest {

  private static final String INDEX = "WalTarget.name";
  private static final String NAME_ID_MAP = "name_id_map_v3.cm";
  private static final int STARTUP_DIRTY_OFFSET = Long.BYTES + Integer.BYTES;
  private static final int STARTUP_OPEN_VERSION_LENGTH_OFFSET = 25;
  private Path directory;

  @Before
  public void createDirectory() throws Exception {
    directory = Files.createTempDirectory("lost-file-creation-wal-");
  }

  @After
  public void deleteDirectory() {
    if (directory != null) {
      FileUtils.deleteRecursively(directory.toFile());
    }
  }

  /** A new class and its later transaction survive absent files and page writes on both opens. */
  @Test
  public void committedClassCreationAndLaterTransactionSurviveRecovery() throws Exception {
    runRecoveryScenario(false);
  }

  /** A same-transaction NOTUNIQUE-to-UNIQUE replacement recovers its new files and later entries. */
  @Test
  public void sameNameIndexReplacementAndLaterTransactionSurviveRecovery() throws Exception {
    runRecoveryScenario(true);
  }

  /** Layout 28 replays creating units without any map variant, including same-name replacement. */
  @Test
  public void layout28MaplessCreationAndLaterTransactionSurviveRecovery() throws Exception {
    runRecoveryScenario(true, true);
  }

  /** Recovery recreates a registered file that has not received its first page write. */
  @Test(timeout = 120_000)
  public void replaysNewFileWithNoPath() throws Exception {
    assertPendingFileRecovery(-1);
  }

  /** Recovery recognizes the new file even when only its header reached disk. */
  @Test(timeout = 120_000)
  public void replaysNewFileWithOnlyItsHeader() throws Exception {
    assertPendingFileRecovery(0);
  }

  /** Recovery retains the first written page and reconstructs the missing second page. */
  @Test(timeout = 120_000)
  public void replaysNewFileWithOneOfTwoPagesWritten() throws Exception {
    assertPendingFileRecovery(1);
  }

  private void assertPendingFileRecovery(int writtenPages) throws Exception {
    var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 12020);
    var name = PageFileNames.layout28(identity);
    var diskName = PageFileNames.layout28DiskName(identity);
    var source = directory.resolve("source");
    var image = directory.resolve("recovered");
    var sourceFile = source.resolve(diskName);
    var imageFile = image.resolve(diskName);
    byte[] committedBytes;
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString())) {
      createPendingRecoverySource(manager);
      try (var session = manager.open("source", "admin", "admin")) {
        var storage = (DiskStorage) session.getStorage();
        var cache = storage.getWriteCache();
        var wal = storage.getWALInstance();
        checkpointRecoverySource(storage);
        WalTestUtils.withWalProtection(session, () -> {
          assertPeriodicFlushDisabled(storage);
          var before = wal.end();
          long id = WalTestUtils.commitFixtureChange(manager, storage, op -> {
            long newId = op.addFile(name, false);
            writeRecoveryPage(op, newId, 0, 42);
            writeRecoveryPage(op, newId, 1, 84);
            return newId;
          });
          // A pending file is registered in the cache but has no path on disk.
          assertTrue("The new file must be registered", cache.exists(id));
          assertTrue("The new file must be pending before any explicit flush",
              Files.notExists(sourceFile, LinkOption.NOFOLLOW_LINKS));
          wal.flush();
          var records = wal.next(before, 1_000);

          if (writtenPages >= 0) {
            cache.flush(id);
          }
          copyRecoveryImage(storage, source, image);
          assertCopiedCreatingUnit(storage, source, image, records, name, 2);

          if (writtenPages < 0) {
            assertTrue("The image must have no page-file path",
                Files.notExists(imageFile, LinkOption.NOFOLLOW_LINKS));
            cache.flush(id);
          } else {
            // Keep the header and any complete leading pages that could reach disk before a stop.
            long extent = File.HEADER_SIZE + (long) writtenPages * cache.pageSize();
            try (var channel = FileChannel.open(imageFile, StandardOpenOption.WRITE)) {
              channel.truncate(extent);
            }
            assertEquals("The image must have the requested written extent",
                extent, Files.size(imageFile));
          }
        });
        committedBytes = Files.readAllBytes(sourceFile);
        assertEquals("The flushed source must contain both committed pages",
            File.HEADER_SIZE + 2L * cache.pageSize(), committedBytes.length);
      }
    }
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString());
        var session = manager.open("recovered", "admin", "admin")) {
      assertEquals(StorageFormat.LAYOUT_28, session.getStorage().getStorageFormat());
      assertTrue("The image must go through WAL recovery",
          session.getStorage().wereDataRestoredAfterOpen());
      assertArrayEquals("Recovered file bytes", committedBytes, Files.readAllBytes(imageFile));
    }
  }

  /** Dirty open clears the saved registration of a non-durable file with no disk path. */
  @Test(timeout = 120_000)
  public void discardsPendingNonDurableFileOnReopen() throws Exception {
    var transientFile = new PageFileIdentity(PageFileRole.RECORD_DATA, 12021);
    var durableFile = new PageFileIdentity(PageFileRole.RECORD_DATA, 12022);
    var transientName = PageFileNames.layout28(transientFile);
    var durableName = PageFileNames.layout28(durableFile);
    var transientDiskName = PageFileNames.layout28DiskName(transientFile);
    var source = directory.resolve("source");
    var image = directory.resolve("recovered");
    var transientPath = image.resolve(transientDiskName);
    var durablePath = image.resolve(PageFileNames.layout28DiskName(durableFile));
    var primary = image.resolve("non_durable_files.cm");
    var shadow = image.resolve("non_durable_files_shadow.cm");
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString())) {
      createPendingRecoverySource(manager);
      try (var session = manager.open("source", "admin", "admin")) {
        var storage = (DiskStorage) session.getStorage();
        var cache = storage.getWriteCache();
        var wal = storage.getWALInstance();
        checkpointRecoverySource(storage);
        WalTestUtils.withWalProtection(session, () -> {
          assertPeriodicFlushDisabled(storage);
          var before = wal.end();
          long id = WalTestUtils.commitFixtureChange(manager, storage, op -> {
            long transientId = op.addFile(transientName, true);
            writeRecoveryPage(op, transientId, 0, 42);
            // A durable file in the same commit gives recovery a logged change to replay.
            long durableId = op.addFile(durableName, false);
            writeRecoveryPage(op, durableId, 0, 84);
            return transientId;
          });
          assertTrue("The non-durable file must be registered", cache.exists(id));
          assertTrue("The registration must be non-durable", cache.isNonDurable(id));
          assertTrue("The non-durable file must be pending on the source",
              Files.notExists(source.resolve(transientDiskName), LinkOption.NOFOLLOW_LINKS));
          wal.flush();
          var records = wal.next(before, 1_000);
          assertFalse("A non-durable file must not have a WAL create record",
              records.stream().anyMatch(record -> record instanceof FileCreatedWALRecord created
                  && transientName.equals(created.getFileName())));

          copyRecoveryImage(storage, source, image);
          assertCopiedCreatingUnit(storage, source, image, records, durableName, 1);
          assertTrue("The image must have no non-durable page-file path",
              Files.notExists(transientPath, LinkOption.NOFOLLOW_LINKS));
          assertTrue("The image must have no durable page-file path",
              Files.notExists(durablePath, LinkOption.NOFOLLOW_LINKS));
          assertTrue("The primary registry must retain the pending ID",
              registryContains(primary, transientFile));
          assertTrue("The shadow registry must retain the pending ID",
              registryContains(shadow, transientFile));
        });
      }
    }
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString());
        var session = manager.open("recovered", "admin", "admin")) {
      var storage = (DiskStorage) session.getStorage();
      var cache = storage.getWriteCache();
      assertTrue("The image must go through WAL recovery", storage.wereDataRestoredAfterOpen());
      assertTrue("The durable file from the same unit must be recovered",
          cache.exists(durableName));
      assertEquals("Recovery must write the durable page from the same unit",
          File.HEADER_SIZE + (long) cache.pageSize(), Files.size(durablePath));
      assertFalse("Recovery must not publish the discarded file", cache.exists(transientName));
      assertTrue("Recovery must not materialize the discarded file",
          Files.notExists(transientPath, LinkOption.NOFOLLOW_LINKS));
      assertFalse("Recovery must clear the pending ID from the primary registry",
          registryContains(primary, transientFile));
      assertFalse("Recovery must clear the pending ID from the shadow registry",
          registryContains(shadow, transientFile));
      int durableValue = WalTestUtils.commitFixtureChange(manager, storage, op -> {
        try (var page = op.loadPageForRead(op.loadFile(durableName), 0)) {
          return new PaginatedCollectionStateV2(page).getFileSize();
        }
      });
      assertEquals("Recovery must replay the durable page value from the same unit", 84,
          durableValue);
    }
  }

  private static void checkpointRecoverySource(DiskStorage storage) throws IOException {
    storage.synch();
    assertEquals("A checkpoint must leave clean startup metadata", 0,
        readValidStartupMetadata(storage)[STARTUP_DIRTY_OFFSET]);
  }

  private static void copyRecoveryImage(DiskStorage storage, Path source, Path image)
      throws IOException {
    var main = readValidStartupMetadata(storage);
    assertTrue("The committed source must persist a dirty flag at capture",
        main[STARTUP_DIRTY_OFFSET] > 0);
    WalTestUtils.copyDirtyStorage(source, image);
    // Keep the main bytes read through the owning channel and any protective copy.
    Files.write(image.resolve("dirty.fl"), main);
    var backup = source.resolve("dirty.flb");
    if (Files.exists(backup)) {
      Files.copy(backup, image.resolve("dirty.flb"));
    }
  }

  private static byte[] readValidStartupMetadata(DiskStorage storage) throws IOException {
    var main = storage.readStartupMetadataForTesting();
    int fixedSize = STARTUP_OPEN_VERSION_LENGTH_OFFSET + Integer.BYTES;
    assertTrue("Startup metadata must contain the current format header",
        main.length >= fixedSize);
    var data = ByteBuffer.wrap(main);
    assertEquals("Startup metadata checksum", data.getLong(0),
        XXHashFactory.fastestInstance().hash64()
            .hash(main, Long.BYTES, main.length - Long.BYTES, 0xADF678FE45L));
    assertEquals("Startup metadata format", StorageStartupMetadata.VERSION,
        data.getInt(Long.BYTES));
    int versionLength = data.getInt(STARTUP_OPEN_VERSION_LENGTH_OFFSET);
    assertTrue("Startup metadata version string length must be valid", versionLength >= -1);
    assertEquals("Startup metadata must contain the whole version string",
        fixedSize + Math.max(0L, versionLength), main.length);
    return main;
  }

  private static boolean registryContains(Path registry, PageFileIdentity file) throws IOException {
    if (Files.notExists(registry, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    var bytes = ByteBuffer.wrap(Files.readAllBytes(registry));
    assertTrue("The registry must contain a header and count", bytes.remaining() >= 16);
    assertEquals("Registry format version", 1, bytes.getInt());
    long checksum = bytes.getLong();
    // The checksum covers the count and internal file IDs, not the header.
    assertEquals("Registry payload checksum", checksum,
        XXHashFactory.fastestInstance().hash64()
            .hash(bytes, bytes.position(), bytes.remaining(), 0xADF678FE45L));
    int count = bytes.getInt();
    assertEquals("Registry count must match its payload", (long) count * Integer.BYTES,
        bytes.remaining());
    for (int i = 0; i < count; i++) {
      if (bytes.getInt() == file.internalId()) {
        return true;
      }
    }
    return false;
  }

  private static void createPendingRecoverySource(YouTrackDBImpl manager) throws Exception {
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT, false)
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL, 3600)
        .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_COLLECTION_GC_PAUSE_INTERVAL,
            3600)
        .addGlobalConfigurationParameter(GlobalConfiguration.DISK_WRITE_CACHE_PAGE_FLUSH_INTERVAL,
            0)
        .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_CALL_FSYNC, true)
        // The double-write log stores page copies. Leave only the WAL as a recovery source.
        .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG, false)
        .build();
    try (var selection = StorageBirthTestSupport.selectBirthFormat(
        manager, "source", StorageFormat.LAYOUT_28)) {
      manager.create("source", DatabaseType.DISK, config, "admin", "admin", "admin");
    }
  }

  private static void assertPeriodicFlushDisabled(DiskStorage storage) {
    assertEquals("Periodic page flushing must be disabled", 0,
        storage.getContextConfiguration().getValueAsInteger(
            GlobalConfiguration.DISK_WRITE_CACHE_PAGE_FLUSH_INTERVAL));
  }

  private static void writeRecoveryPage(AtomicOperation operation, long id, long index, int value)
      throws IOException {
    var page = operation.allocatePageForWrite(id, index);
    try {
      new PaginatedCollectionStateV2(page).setFileSize(value);
    } finally {
      operation.releasePageFromWrite(page);
    }
  }

  private static void assertCopiedCreatingUnit(DiskStorage storage, Path source, Path image,
      List<? extends WALRecord> records, String name, int pageCount) throws IOException {
    var creation = records.stream().filter(FileCreatedWALRecord.class::isInstance)
        .map(FileCreatedWALRecord.class::cast)
        .filter(record -> name.equals(record.getFileName()))
        .findFirst().orElseThrow(() -> new AssertionError("Missing file creation in WAL"));
    long unitId = creation.getOperationUnitId();
    var start = records.stream().filter(AtomicUnitStartRecord.class::isInstance)
        .map(AtomicUnitStartRecord.class::cast)
        .filter(record -> record.getOperationUnitId() == unitId)
        .findFirst().orElseThrow(() -> new AssertionError("Missing creating unit start in WAL"));
    var end = records.stream().filter(AtomicUnitEndRecord.class::isInstance)
        .map(AtomicUnitEndRecord.class::cast)
        .filter(record -> record.getOperationUnitId() == unitId && !record.isRollback())
        .findFirst().orElseThrow(() -> new AssertionError("Missing creating unit commit in WAL"));
    assertTrue("The WAL must have forced the complete creating unit",
        storage.getWALInstance().getFlushedLsn().compareTo(end.getLsn()) >= 0);
    var loggedPages = new HashSet<Long>();
    var unitSegments = new HashSet<Long>();
    // WAL records in the same unit can compare equal. Use positions to delimit the unit.
    for (var record : records) {
      var lsn = record.getLsn();
      if (lsn.compareTo(start.getLsn()) < 0 || lsn.compareTo(end.getLsn()) > 0) {
        continue;
      }
      unitSegments.add(lsn.getSegment());
      if (record instanceof PageOperation page && page.getOperationUnitId() == unitId
          && storage.getWriteCache().fileIdsAreEqual(page.getFileId(), creation.getFileId())) {
        loggedPages.add(page.getPageIndex());
      }
    }
    for (long index = 0; index < pageCount; index++) {
      assertTrue("The creating unit must log the changes for page " + index,
          loggedPages.contains(index));
    }
    var walName = storage.getContextConfiguration().getValueAsString(
        ContextConfiguration.WAL_BASE_NAME, ContextConfiguration.WAL_DEFAULT_NAME);
    for (long segment : unitSegments) {
      var segmentName = walName + "." + segment + WriteAheadLog.WAL_SEGMENT_EXTENSION;
      var copiedSegment = image.resolve(segmentName);
      assertTrue("The image must contain the creating WAL segment " + segmentName,
          Files.isRegularFile(copiedSegment));
      assertEquals("The image must retain every byte of " + segmentName,
          -1, Files.mismatch(source.resolve(segmentName), copiedSegment));
    }
  }

  private void runRecoveryScenario(boolean replaceIndex) throws Exception {
    runRecoveryScenario(replaceIndex, false);
  }

  private void runRecoveryScenario(boolean replaceIndex, boolean layout28) throws Exception {
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL,
            100000000)
        .addGlobalConfigurationParameter(GlobalConfiguration.DISK_WRITE_CACHE_PAGE_FLUSH_INTERVAL,
            0)
        .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_CALL_FSYNC, true)
        .build();
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString())) {
      if (layout28) {
        try (var selection = StorageBirthTestSupport.selectBirthFormat(
            manager, "source", StorageFormat.LAYOUT_28)) {
          manager.create("source", DatabaseType.DISK, config, "admin", "admin", "admin");
        }
      } else {
        manager.create("source", DatabaseType.DISK, config, "admin", "admin", "admin");
      }
      try (var session = manager.open("source", "admin", "admin")) {
        session.getMetadata().getSchema().createClass("Baseline");
        session.executeInTx(tx -> session.newEntity("Baseline").setProperty("value", "before"));
        if (replaceIndex) {
          var target = session.getMetadata().getSchema().createClass("WalTarget");
          target.createProperty("name", PropertyType.STRING);
          target.createIndex(INDEX, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
        }
        session.freeze();
        session.release();

        WalTestUtils.withWalProtection(session, () -> {
          var storage = (DiskStorage) session.getStorage();
          var cache = storage.getWriteCache();
          var wal = storage.getWALInstance();
          var source = directory.resolve("source");
          var copy = Files.createDirectory(directory.resolve("recovered"));
          var baselineMap = layout28 ? null : Files.readAllBytes(source.resolve(NAME_ID_MAP));
          var baselineFiles = new HashMap<String, byte[]>();
          var baselineNames = new HashSet<>(cache.files().keySet());
          for (var id : cache.files().values()) {
            var nativeName = cache.nativeFileNameById(id);
            baselineFiles.put(nativeName, Files.readAllBytes(source.resolve(nativeName)));
          }
          var beforeA = wal.end();

          // One commit replaces the engine under the same logical name with a fresh file family.
          // The baseline class stays empty because in-transaction index builds require that.
          session.begin();
          if (replaceIndex) {
            session.getSharedContext().getIndexManager().dropIndex(session, INDEX);
            session.getMetadata().getSchema().getClass("WalTarget")
                .createIndex(INDEX, SchemaClass.INDEX_TYPE.UNIQUE, "name");
          } else {
            session.getMetadata().getSchema().createClass("WalTarget");
          }
          session.newEntity("WalTarget").setProperty("name", "first");
          session.commit();
          var afterA = wal.end();
          session.begin();
          session.newEntity("WalTarget").setProperty("name", "later");
          session.command("update Baseline set value = 'after'");
          session.commit();
          wal.flush();
          assertTrue(wal.getFlushedLsn().compareTo(wal.end()) >= 0);

          var newFiles = new HashMap<String, String>();
          for (var entry : cache.files().entrySet()) {
            if (!baselineNames.contains(entry.getKey())) {
              newFiles.put(entry.getKey(), cache.nativeFileNameById(entry.getValue()));
            }
          }
          assertFalse("The first commit must create a new file family", newFiles.isEmpty());
          assertReplayOrder(wal, beforeA, afterA, newFiles.keySet());

          // Drain blank-page initialization tasks as well as periodic writes before the raw copy.
          cache.pauseBackgroundFlush();
          try (var paths = Files.list(source)) {
            for (var path : paths.toList()) {
              var name = path.getFileName().toString();
              if (!name.endsWith(".dwl") && !name.equals("dirty.fl") && !name.equals("dirty.flb")) {
                Files.copy(path, copy.resolve(name), StandardCopyOption.REPLACE_EXISTING);
              }
            }
          }
          // Restore the registry too. A negative booked-id entry would enable restoreFileById
          // and hide a missing pending-create consult. Restore every baseline page to exclude
          // already-flushed effects of the later transaction. No double-write log is retained.
          if (!layout28) {
            Files.write(copy.resolve(NAME_ID_MAP), baselineMap);
          }
          for (var nativeName : newFiles.values()) {
            Files.deleteIfExists(copy.resolve(nativeName));
            assertFalse(Files.exists(copy.resolve(nativeName)));
          }
          for (var entry : baselineFiles.entrySet()) {
            Files.write(copy.resolve(entry.getKey()), entry.getValue());
          }
          if (!layout28) {
            assertArrayEquals(baselineMap, Files.readAllBytes(copy.resolve(NAME_ID_MAP)));
          }
        });
      }
    }
    // New managers force storage close, so the second open also checks persisted recovery effects.
    for (var open = 0; open < 2; open++) {
      if (layout28) {
        StorageBirthTestSupport.removeNameIdMaps(directory.resolve("recovered"));
      }
      try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString());
          var session = manager.open("recovered", "admin", "admin")) {
        assertEquals(layout28 ? 28 : 27, session.getStorage().getStorageFormat().layoutVersion());
        assertRecoveredData(session, replaceIndex);
        if (layout28 && open == 0) {
          assertFalse("Recovery admission must not recreate the name-id map",
              Files.exists(directory.resolve("recovered").resolve(NAME_ID_MAP)));
        }
        var cache = ((DiskStorage) session.getStorage()).getWriteCache();
        for (var id : cache.files().values()) {
          assertTrue(Files.exists(directory.resolve("recovered")
              .resolve(cache.nativeFileNameById(id))));
        }
      }
    }
  }

  private static void assertRecoveredData(DatabaseSessionEmbedded session, boolean replaceIndex) {
    session.executeInTx(tx -> {
      try (var rows = session.query("select name from WalTarget order by name")) {
        assertEquals(List.of("first", "later"),
            rows.stream().map(row -> row.<String>getProperty("name")).toList());
      }
      try (var rows = session.query("select value from Baseline")) {
        assertEquals("after", rows.next().getProperty("value"));
        assertFalse(rows.hasNext());
      }
      if (replaceIndex) {
        var index = session.getSharedContext().getIndexManager().getIndex(INDEX);
        assertNotNull("The replacement index must be published", index);
        assertTrue("Recovery must retain the new index type", index.isUnique());
        for (var name : List.of("first", "later")) {
          try (var rids = index.getRids(session, name)) {
            assertEquals("The replacement index must contain " + name, 1, rids.count());
          }
        }
      }
    });
  }

  private static void assertReplayOrder(WriteAheadLog wal, LogSequenceNumber beforeA,
      LogSequenceNumber afterA, Set<String> newFiles) throws Exception {
    var records = new ArrayList<WALRecord>();
    var batch = wal.next(beforeA, 1_000);
    while (!batch.isEmpty()) {
      records.addAll(batch);
      batch = wal.next(batch.getLast().getLsn(), 1_000);
    }
    var firstPage = new HashMap<Long, Map<Long, LogSequenceNumber>>();
    var creatingUnits = new HashSet<Long>();
    var completedUnits = new HashSet<Long>();
    var pageBeforeCreate = false;
    var laterCommit = false;
    for (var record : records) {
      if (record instanceof PageOperation page) {
        firstPage.computeIfAbsent(page.getOperationUnitId(), id -> new HashMap<>())
            .putIfAbsent(page.getFileId(), page.getLsn());
      } else if (record instanceof UpdatePageRecord page) {
        firstPage.computeIfAbsent(page.getOperationUnitId(), id -> new HashMap<>())
            .putIfAbsent(page.getFileId(), page.getLsn());
      } else if (record instanceof FileCreatedWALRecord create
          && newFiles.contains(create.getFileName())) {
        creatingUnits.add(create.getOperationUnitId());
        var page = firstPage.getOrDefault(create.getOperationUnitId(), Map.of())
            .get(create.getFileId());
        pageBeforeCreate |= page != null && page.compareTo(create.getLsn()) < 0;
      } else if (record instanceof AtomicUnitEndRecord end) {
        completedUnits.add(end.getOperationUnitId());
        laterCommit |= end.getLsn().compareTo(afterA) > 0;
      }
    }
    assertFalse("The new file family must have WAL create records", creatingUnits.isEmpty());
    assertTrue("A page redo must precede its own file create in a committed unit",
        pageBeforeCreate);
    assertTrue("Every file-creating unit must have a durable end", completedUnits.containsAll(
        creatingUnits));
    assertTrue("A later committed unit must follow transaction A", laterCommit);
  }
}
