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
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercises recovery from a durable WAL with the file-creation apply phase absent. */
public class LostFileCreationWALRecoveryTest {

  private static final String INDEX = "WalTarget.name";
  private static final String NAME_ID_MAP = "name_id_map_v3.cm";
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

  private void runRecoveryScenario(boolean replaceIndex) throws Exception {
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL,
            100000000)
        .addGlobalConfigurationParameter(GlobalConfiguration.DISK_WRITE_CACHE_PAGE_FLUSH_INTERVAL,
            0)
        .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_CALL_FSYNC, true)
        .build();
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString())) {
      manager.create("source", DatabaseType.DISK, config, "admin", "admin", "admin");
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
          var baselineMap = Files.readAllBytes(source.resolve(NAME_ID_MAP));
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
          Files.write(copy.resolve(NAME_ID_MAP), baselineMap);
          for (var nativeName : newFiles.values()) {
            Files.deleteIfExists(copy.resolve(nativeName));
            assertFalse(Files.exists(copy.resolve(nativeName)));
          }
          for (var entry : baselineFiles.entrySet()) {
            Files.write(copy.resolve(entry.getKey()), entry.getValue());
          }
          assertArrayEquals(baselineMap, Files.readAllBytes(copy.resolve(NAME_ID_MAP)));
        });
      }
    }
    // New managers force storage close, so the second open also checks persisted recovery effects.
    for (var open = 0; open < 2; open++) {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString());
          var session = manager.open("recovered", "admin", "admin")) {
        assertRecoveredData(session, replaceIndex);
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
