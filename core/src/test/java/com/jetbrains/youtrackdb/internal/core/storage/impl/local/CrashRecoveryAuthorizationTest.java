package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageAllocatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Opens raw disk snapshots with deliberately selected recovery evidence. */
public class CrashRecoveryAuthorizationTest {
  private static final PageFileIdentity IDENTITY =
      new PageFileIdentity(PageFileRole.RECORD_DATA, 100000);
  private static final long FILE = IDENTITY.internalId();
  private static final String NAME = PageFileNames.layout28(IDENTITY);
  private static final String DISK_NAME = PageFileNames.layout28DiskName(IDENTITY);
  private static final long UNIT = 1000000;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @FunctionalInterface
  private interface Records {
    void append(WriteAheadLog wal) throws Exception;
  }

  private Path snapshot(Records records) throws Exception {
    Path root = folder.newFolder().toPath();
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL,
            100000000)
        .addGlobalConfigurationParameter(GlobalConfiguration.DISK_WRITE_CACHE_PAGE_FLUSH_INTERVAL,
            0)
        .build();
    try (var manager = (YouTrackDBImpl) YourTracks.instance(root.toString())) {
      try (var selection = StorageBirthTestSupport.selectBirthFormat(
          manager, "source", StorageFormat.LAYOUT_28)) {
        manager.create("source", DatabaseType.DISK, config, "admin", "admin", "admin");
      }
      try (var session = manager.open("source", "admin", "admin")) {
        session.freeze();
        session.release();
        var storage = (DiskStorage) session.getStorage();
        var wal = storage.getWALInstance();
        var start = wal.begin();
        wal.addCutTillLimit(start);
        try {
          records.append(wal);
          wal.flush();
          storage.getWriteCache().pauseBackgroundFlush();
          Path copy = Files.createDirectory(root.resolve("copy"));
          try (var paths = Files.list(root.resolve("source"))) {
            for (var path : paths.toList()) {
              String name = path.getFileName().toString();
              if (!name.endsWith(".dwl") && !name.startsWith("dirty.fl")) {
                Files.copy(path, copy.resolve(name));
              }
            }
          }
        } finally {
          wal.removeCutTillLimit(start);
        }
      }
    }
    StorageBirthTestSupport.removeNameIdMaps(root.resolve("copy"));
    return root;
  }

  private void begin(WriteAheadLog wal, long id) throws Exception {
    wal.log(new AtomicUnitStartRecord(false, id));
  }

  private void end(WriteAheadLog wal, long id) throws Exception {
    wal.log(new AtomicUnitEndRecord(id, false, null));
  }

  private void update(WriteAheadLog wal, long id, long file) throws Exception {
    update(wal, id, file, 0);
  }

  private void update(WriteAheadLog wal, long id, long file, int page) throws Exception {
    var changes = new WALPageChangesPortion();
    changes.setIntValue(null, 0x12345678, DurablePage.NEXT_FREE_POSITION);
    wal.log(new UpdatePageRecord(page, file, id, changes, new LogSequenceNumber(0, 0)));
  }

  private void open(Path root) {
    try (var manager = (YouTrackDBImpl) YourTracks.instance(root.toString());
        var session = manager.open("copy", "admin", "admin")) {
      assertEquals(28, session.getStorage().getStorageFormat().layoutVersion());
    }
  }

  private Map<Path, byte[]> walBytes(Path root) throws Exception {
    var result = new HashMap<Path, byte[]>();
    try (var paths = Files.list(root.resolve("copy"))) {
      for (var path : paths.filter(p -> p.toString().endsWith(".wal")).toList()) {
        result.put(path, Files.readAllBytes(path));
      }
    }
    assertFalse(result.isEmpty());
    return result;
  }

  private Throwable failsWithHistory(Path root, String text) throws Exception {
    var before = walBytes(root);
    var failure = assertThrows(RuntimeException.class, () -> open(root));
    var messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append('\n');
    }
    assertTrue(messages.toString(), messages.toString().contains(text));
    assertTrue("Failed open must keep the startup dirty flag",
        Files.readAllBytes(root.resolve("copy").resolve("dirty.fl"))[12] != 0);
    for (var entry : before.entrySet()) {
      org.junit.Assert.assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }
    // A second attempt must take recovery again rather than clear the dirty state.
    assertThrows(RuntimeException.class, () -> open(root));
    return failure;
  }

  private void assertPayload(Path root, int page) {
    try (var manager = (YouTrackDBImpl) YourTracks.instance(root.toString());
        var session = manager.open("copy", "admin", "admin")) {
      var storage = session.getStorage();
      var cache = storage.getWriteCache();
      assertTrue(cache.admitExpectedFile(IDENTITY));
      long file = cache.externalFileId(IDENTITY.internalId());
      var entry = storage.getReadCache().loadForRead(file, page, cache, false);
      try {
        assertEquals(0x12345678,
            entry.getCachePointer().getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        storage.getReadCache().releaseFromRead(entry);
      }
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  /** A later complete unit supplies the only page zero for the first unit's missing file. */
  @Test
  public void laterPageZeroAuthorizesEarlierUnitCreation() throws Exception {
    Path root = snapshot(wal -> {
      begin(wal, UNIT);
      wal.log(new PageAllocatedWALRecord(1, FILE, UNIT));
      update(wal, UNIT, FILE, 1);
      end(wal, UNIT);
      begin(wal, UNIT + 1);
      wal.log(new PageAllocatedWALRecord(0, FILE, UNIT + 1));
      end(wal, UNIT + 1);
    });
    open(root);
    assertPayload(root, 1);
  }

  /** A unit scans allocations before redo, even when its declaration follows the update. */
  @Test
  public void allocationAfterUpdateAuthorizesCreation() throws Exception {
    Path root = snapshot(wal -> {
      begin(wal, UNIT);
      update(wal, UNIT, FILE);
      wal.log(new PageAllocatedWALRecord(0, FILE, UNIT));
      end(wal, UNIT);
    });
    open(root);
    assertPayload(root, 0);
  }

  /** Same-unit and later-unit drops skip even invalid page work, for present and absent files. */
  @Test
  public void committedDeletionPrecedesAllFileAndPageAccess() throws Exception {
    for (boolean sameUnit : new boolean[] {true, false}) {
      for (boolean present : new boolean[] {true, false}) {
        Path root = snapshot(wal -> {
          begin(wal, UNIT);
          // Neither allocation preparation nor redo may touch this missing or junk file.
          // A nonzero allocation cannot itself authorize creation of the absent file.
          wal.log(new PageAllocatedWALRecord(1, FILE, UNIT));
          update(wal, UNIT, FILE);
          if (!sameUnit) {
            end(wal, UNIT);
            begin(wal, UNIT + 1);
          }
          wal.log(new FileDeletedWALRecord(sameUnit ? UNIT : UNIT + 1, FILE));
          end(wal, sameUnit ? UNIT : UNIT + 1);
        });
        if (present) {
          Files.write(root.resolve("copy").resolve(DISK_NAME), new byte[65536]);
        }
        open(root);
        assertFalse(Files.exists(root.resolve("copy").resolve(DISK_NAME)));
      }
    }
  }

  /** An incomplete drop supplies no skip, and a creation record supplies no permission. */
  @Test
  public void incompleteDropAndTransitionalCreateDoNotAuthorize() throws Exception {
    Path root = snapshot(wal -> {
      begin(wal, UNIT);
      wal.log(new FileCreatedWALRecord(UNIT, NAME, FILE));
      update(wal, UNIT, FILE);
      end(wal, UNIT);
      begin(wal, UNIT + 1);
      wal.log(new FileDeletedWALRecord(UNIT + 1, FILE));
    });
    var failure = failsWithHistory(root, "page-0 allocation evidence is absent or unusable");
    Throwable diagnostic = failure;
    while (diagnostic.getCause() != null) {
      diagnostic = diagnostic.getCause();
    }
    assertTrue(diagnostic.getMessage(), diagnostic.getMessage().contains(DISK_NAME));
    assertTrue(diagnostic.getMessage().contains(IDENTITY.toString()));
    assertTrue(diagnostic.getMessage().contains("kind COLLECTION"));
    assertTrue(diagnostic.getMessage().contains("replay bounds [LogSequenceNumber"));
    assertFalse(Files.exists(root.resolve("copy").resolve(DISK_NAME)));
  }

  /** An ended leading unit without its start never grants page-zero creation permission. */
  @Test
  public void partialLeadingUnitCannotCreate() throws Exception {
    Path root = snapshot(wal -> {
      wal.log(new PageAllocatedWALRecord(0, FILE, UNIT));
      end(wal, UNIT);
    });
    failsWithHistory(root, "Restoration from backup is required");
    assertFalse(Files.exists(root.resolve("copy").resolve(DISK_NAME)));
  }

  /** A replay resolver failure retains its cause, exact record positions and recovery history. */
  @Test
  public void runtimeReplayFailureKeepsHistory() throws Exception {
    LogSequenceNumber[] positions = new LogSequenceNumber[3];
    Path root = snapshot(wal -> {
      begin(wal, UNIT);
      positions[2] = wal.end();
      update(wal, UNIT, FILE);
      positions[0] = wal.end();
      end(wal, UNIT);
      positions[1] = wal.end();
    });
    var injected = new IllegalStateException("injected recovery file resolution failure");
    try (var identities = mockStatic(PageFileIdentity.class, CALLS_REAL_METHODS)) {
      identities.when(() -> PageFileIdentity.decode((int) FILE)).thenThrow(injected);
      var failure = failsWithHistory(root, "WAL replay failed");
      while (failure.getCause() != injected) {
        failure = failure.getCause();
        org.junit.Assert.assertNotNull(failure);
      }
      assertSame(injected, failure.getCause());
      assertEquals(IllegalStateException.class, failure.getCause().getClass());
      assertEquals("injected recovery file resolution failure", failure.getCause().getMessage());
      assertTrue(failure.getMessage().contains("failed at record " + positions[0]));
      assertTrue(
          failure.getMessage().contains("last successfully applied record " + positions[2]));
      assertTrue(failure.getMessage().contains("evidence readable end " + positions[1]));
    }
  }

  /** A damaged existing page has no rebuild permission and retains its failure and history. */
  @Test
  public void damagedExistingPageKeepsHistory() throws Exception {
    Path root = snapshot(wal -> {
      begin(wal, UNIT);
      update(wal, UNIT, FILE);
      end(wal, UNIT);
    });
    // Keep a complete physical page so open-time partial-tail truncation is not involved.
    byte[] damaged = new byte[com.jetbrains.youtrackdb.internal.core.storage.fs.File.HEADER_SIZE
        + GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024];
    java.util.Arrays.fill(damaged, (byte) 42);
    Path pageFile = root.resolve("copy").resolve(DISK_NAME);
    Files.write(pageFile, damaged);
    var failure = failsWithHistory(root, "WAL replay failed");
    while (failure.getCause() != null
        && !(failure instanceof java.io.IOException)) {
      failure = failure.getCause();
    }
    org.junit.Assert.assertNotNull(failure.getCause());
    assertEquals(com.jetbrains.youtrackdb.internal.core.exception.StorageException.class,
        failure.getCause().getClass());
    assertTrue(failure.getCause().getMessage(), failure.getCause().getMessage()
        .contains("Magic number or check sum verification failed for page `0`"));
    org.junit.Assert.assertArrayEquals(damaged, Files.readAllBytes(pageFile));
  }

  /** A malformed final page must not discard committed records from a replay read batch. */
  @Test
  public void malformedTailAfterCommittedPrefixKeepsPayload() throws Exception {
    LogSequenceNumber[] tail = new LogSequenceNumber[1];
    Path root = snapshot(wal -> {
      begin(wal, UNIT);
      wal.log(new PageAllocatedWALRecord(0, FILE, UNIT));
      update(wal, UNIT, FILE);
      end(wal, UNIT);
      wal.appendNewSegment();
      tail[0] = wal.log(new AtomicUnitStartRecord(false, UNIT + 1));
    });
    Path segment = root.resolve("copy").resolve(
        ContextConfiguration.WAL_DEFAULT_NAME + "." + tail[0].getSegment() + ".wal");
    byte[] bytes = Files.readAllBytes(segment);
    ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        .putShort(CASWALPage.PAGE_SIZE_OFFSET, (short) 1);
    Files.write(segment, bytes);
    open(root);
    assertPayload(root, 0);
  }

  /** A torn first retained record has no readable prefix and still permits normal storage open. */
  @Test
  public void tornFirstRecordHasNoReplayablePrefix() throws Exception {
    Path root = snapshot(wal -> {
      begin(wal, UNIT);
      end(wal, UNIT);
    });
    var segments = walBytes(root).keySet().stream().sorted().toList();
    Path first = segments.getFirst();
    byte[] bytes = java.util.Arrays.copyOf(Files.readAllBytes(first),
        CASWALPage.DEFAULT_PAGE_SIZE);
    ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        .putShort(CASWALPage.PAGE_SIZE_OFFSET, (short) 1);
    Files.write(first, bytes);
    // Keep only this torn segment, so no later content invalidates torn-tail permission.
    for (Path segment : segments.subList(1, segments.size())) {
      Files.delete(segment);
    }
    RecoveryWALEvidence[] observed = new RecoveryWALEvidence[1];
    try (var evidence = mockStatic(RecoveryWALEvidence.class, CALLS_REAL_METHODS)) {
      evidence.when(() -> RecoveryWALEvidence.collect(any(), any(), anyBoolean(), anyInt()))
          .thenAnswer(invocation -> {
            observed[0] = (RecoveryWALEvidence) invocation.callRealMethod();
            return observed[0];
          });
      open(root);
      org.junit.Assert.assertNotNull(observed[0]);
      org.junit.Assert.assertNull(observed[0].readableEnd());
    }
  }

  /** A damaged final segment is a torn tail, but later content turns it into a hard failure. */
  @Test
  public void tornTailAndInteriorEvidenceFailureAreDifferent() throws Exception {
    for (boolean laterContent : new boolean[] {false, true}) {
      LogSequenceNumber[] broken = new LogSequenceNumber[1];
      Path root = snapshot(wal -> {
        wal.appendNewSegment();
        broken[0] = wal.log(new AtomicUnitStartRecord(false, UNIT));
        if (laterContent) {
          wal.appendNewSegment();
          begin(wal, UNIT + 1);
          end(wal, UNIT + 1);
        }
      });
      Path segment = root.resolve("copy").resolve(
          ContextConfiguration.WAL_DEFAULT_NAME + "." + broken[0].getSegment() + ".wal");
      byte[] bytes = Files.readAllBytes(segment);
      bytes[CASWALPage.RECORDS_OFFSET] ^= 1;
      Files.write(segment, bytes);
      if (laterContent) {
        failsWithHistory(root, "Cannot read WAL evidence");
      } else {
        open(root);
      }
    }
  }
}
