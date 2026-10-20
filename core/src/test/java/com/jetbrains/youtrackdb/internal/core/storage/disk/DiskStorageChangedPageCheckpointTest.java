package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.INVALIDATED;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.NO_SAVE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.PREFLIGHT_UNAVAILABLE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.SAVED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.engine.Engine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointResult;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerStartup;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.StartupPerformanceFixture;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.TrackerPerformanceProbe;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageStartupMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MetaDataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageBrokenException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.CutPreflight;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

/** Direct storage bridge contracts, independent of ordinary checkpoint caller routing. */
public class DiskStorageChangedPageCheckpointTest {

  private static final LogSequenceNumber COVERAGE = new LogSequenceNumber(2, 32);

  @Rule
  public final TemporaryFolder folder;

  public DiskStorageChangedPageCheckpointTest() {
    folder = new TemporaryFolder();
  }

  private DiskStorageChangedPageCheckpointTest(Path root) {
    folder = new TemporaryFolder(root.toFile());
  }

  @Rule
  public final TestName testName = new TestName();

  private YouTrackDBImpl manager;
  private DatabaseSessionEmbedded session;
  private DiskStorage storage;
  private ScalableRWLock stateLock;
  private WriteAheadLog realWal;
  private WriteAheadLog wal;
  private ChangedPageTracker tracker;
  private Path sideFile;

  @Before
  public void openStorage() throws Exception {
    // The parent launcher needs no database. The child calls this setup directly without a name.
    if ("trackerPerformanceStartupOpen".equals(testName.getMethodName())) {
      Assume.assumeTrue(Boolean.getBoolean("youtrackdb.test.trackerPerformance"));
      return;
    }
    var config = new BaseConfiguration();
    // Keep periodic work away from the scoped WAL replacement used by these direct bridge tests.
    config.setProperty(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT.getKey(), false);
    config.setProperty(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.getKey(), 3600);
    config.setProperty(GlobalConfiguration.STORAGE_COLLECTION_GC_PAUSE_INTERVAL.getKey(), 3600);
    manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString(), config);
    manager.create("bridge", DatabaseType.DISK, "admin", "admin", "admin");
    session = manager.open("bridge", "admin", "admin");
    storage = (DiskStorage) session.getStorage();
    sideFile = folder.getRoot().toPath().resolve("bridge")
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    // Creation can publish tracker coverage. Normalize only its file, not the shared tracker.
    Files.deleteIfExists(sideFile);
    stateLock = (ScalableRWLock) field(AbstractStorage.class, storage, "stateLock");
    tracker = (ChangedPageTracker) field(DiskStorage.class, storage, "changedPageTracker");
    realWal = storage.getWALInstance();
    wal = mock(WriteAheadLog.class);
    when(wal.preflightCut(3)).thenReturn(new CutPreflight(true, 2, COVERAGE));
    when(wal.cutAllSegmentsSmallerThan(2)).thenReturn(true);
    replaceWal(wal);
  }

  @After
  public void closeStorage() throws Exception {
    // Clear only test cancellation before normal lifecycle teardown forces real storage channels.
    Thread.interrupted();
    if (storage != null && realWal != null) {
      replaceWal(realWal);
    }
    try {
      if (session != null) {
        session.close();
      }
    } finally {
      if (manager != null) {
        manager.close();
      }
    }
  }

  /** Existing-disk admission must remove malformed authority without relying on a checkpoint. */
  @Test
  public void existingOpenEagerlyRemovesMalformedAuthority() throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var path = ((Path) field(DiskStorage.class, created, "storagePath"))
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    Files.writeString(path, "invalid saved authority");
    created.open(new ContextConfiguration());
    assertFalse("Admission must durably remove unprovable authority", Files.exists(path));
  }

  /** An initial fallback failure must stop open before configuration or maintenance admission. */
  @Test
  public void existingOpenRejectsFailedInitialFallback() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID());
    var path = ((Path) field(DiskStorage.class, created, "storagePath"))
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    Files.deleteIfExists(path);
    Files.createDirectory(path);
    var blocker = Files.writeString(path.resolve("blocker"), "retain");
    var recovery = new AtomicInteger();
    setField(created, "recoverListener",
        (com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageRecoverListener) recovery::incrementAndGet);
    clearInvocations(created);
    try {
      assertThrows(RuntimeException.class, () -> created.open(new ContextConfiguration()));
      assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
      verify(created, never()).beginStartupReplay();
      verify(created, never()).checkpointChangedPages(anyLong());
      verify(created, never()).finishStartupHistory();
      assertEquals(0, recovery.get());
    } finally {
      Files.delete(blocker);
      Files.delete(path);
    }
    assertNull(field(DiskStorage.class, created, "startupOwner"));
  }

  /** Real clean reopen proves retained coverage, merges sealed marks and keeps continuity. */
  @Test
  public void cleanOpenLoadsTrustedHistoryAndFinishesBeforeMigration() throws Exception {
    assertTrustedReopen(false);
  }

  /** Dirty reopen uses the pre-recovery shutdown snapshot and ends preservation before components. */
  @Test
  public void dirtyOpenLoadsTrustedHistoryAndEndsReplayPreservation() throws Exception {
    assertTrustedReopen(true);
  }

  /** Committed redo creates an absent file without clearing loaded active or sealed candidates. */
  @Test
  public void committedReplayPreservesLoadedCandidatesThroughFileCreation() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID(), true);
    var originalReadCache = created.getReadCache();
    var observed = mock(ReadCache.class, delegatesTo(originalReadCache));
    var replayed = new AtomicInteger();
    doAnswer(call -> {
      var cache = (WOWCache) call.getArgument(2);
      var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
      assertTrue(selected.isTrusted());
      assertFalse(cache.exists("committed.bin"));
      assertEquals(List.of(0L, 7L, 9L), candidates(selected, 123456));
      var result = originalReadCache.addFile(call.getArgument(0), call.getArgument(1), cache);
      assertTrue(cache.exists("committed.bin"));
      assertEquals("Real redo must preserve loaded candidates when it creates the absent file",
          List.of(0L, 7L, 9L), candidates(selected, 123456));
      replayed.incrementAndGet();
      return result;
    }).when(observed).addFile(eq("committed.bin"), anyLong(), any());
    setField(created, "readCache", observed);
    created.open(new ContextConfiguration());
    assertEquals("The completed unit must reach real file-creation redo", 1, replayed.get());
    var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
    assertTrue(selected.isTrusted());
    assertEquals(List.of(0L, 7L, 9L), candidates(selected, 123456));
    assertEquals("The incomplete unit remains proof evidence without being replayed",
        List.of(31L, 33L), candidates(selected, 123457));
    var cache = (WOWCache) created.getWriteCache();
    assertFalse(cache.exists("incomplete.bin"));
    assertEquals(1L, cache.getFilledUpTo(cache.externalFileId(123456)));
    assertFalse((boolean) field(WOWCache.class, cache, "startupReplayPreservation"));
    assertNull(field(DiskStorage.class, created, "startupOwner"));
  }

  /** Physical damage is admitted only for an unclean terminal page without a readable suffix. */
  @Test
  public void physicalWalDamageResolvesTrustBeforeStorageAdmission() throws Exception {
    for (var variant : List.of("unclean-tail", "clean-tail", "interior", "clean-interior",
        "later-segment", "clean-later-segment", "missing-coverage", "missing-interior",
        "vanished")) {
      boolean interior = variant.endsWith("interior") && !variant.equals("missing-interior");
      boolean later = variant.endsWith("later-segment");
      boolean missing = variant.startsWith("missing-");
      boolean vanished = variant.equals("vanished");
      boolean clean = variant.startsWith("clean-") || missing || vanished;
      var created = createObservedStorage("damage-" + variant);
      try (var baseline = manager.open(created.getName(), "admin", "admin")) {
        baseline.getMetadata().getSchema().createClass("DamageBaseline");
        baseline
            .executeInTx(tx -> baseline.newEntity("DamageBaseline").setProperty("value", "safe"));
        baseline.freeze();
        baseline.release();
      }
      var completed = UUID.randomUUID();
      var metadataLsn = new LogSequenceNumber[1];
      var coverageLsn = new LogSequenceNumber[1];
      var lostSegment = new long[1];
      seedTrustedHistory(created, true, completed, false, (target, coverage) -> {
        coverageLsn[0] = coverage;
        var input = target.getWALInstance();
        var payload = new byte[interior ? 10000 : 4500];
        new Random(18).nextBytes(payload);
        metadataLsn[0] = input.log(new MetaDataRecord(payload));
        if (interior || later) {
          if (later) {
            assertTrue(input.appendNewSegment());
          }
          input.log(new UpdatePageRecord(599, target.getWriteCache().externalFileId(123457),
              123, new WALPageChangesPortion(), coverage));
        }
        if (variant.equals("missing-interior") || vanished) {
          assertTrue(input.appendNewSegment());
          lostSegment[0] = input.log(new MetaDataRecord(new byte[] {1})).getSegment();
          assertTrue(input.appendNewSegment());
          input.log(new MetaDataRecord(new byte[] {2}));
        }
        if (clean) {
          ((StorageStartupMetadata) field(DiskStorage.class, target, "startupMetadata"))
              .clearDirty();
        }
      });
      var directory = (Path) field(DiskStorage.class, created, "storagePath");
      var path = directory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + "."
          + metadataLsn[0].getSegment() + ".wal");
      long size = Files.size(path);
      assertEquals(0, size % CASWALPage.DEFAULT_PAGE_SIZE);
      long brokenPage = interior
          ? metadataLsn[0].getPosition() / CASWALPage.DEFAULT_PAGE_SIZE + 1L
          : size / CASWALPage.DEFAULT_PAGE_SIZE - 1;
      assertTrue(brokenPage > metadataLsn[0].getPosition() / CASWALPage.DEFAULT_PAGE_SIZE);
      if (variant.equals("missing-coverage")) {
        Files.delete(directory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + "."
            + coverageLsn[0].getSegment() + ".wal"));
      } else if (variant.equals("missing-interior")) {
        Files.delete(directory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + "."
            + lostSegment[0] + ".wal"));
      } else if (!vanished) {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
          var damage = ByteBuffer.wrap(new byte[Long.BYTES]);
          long offset = brokenPage * CASWALPage.DEFAULT_PAGE_SIZE;
          while (damage.hasRemaining()) {
            assertTrue(channel.write(damage, offset + damage.position()) > 0);
          }
        }
      }
      boolean trusted = variant.equals("unclean-tail");
      var probes = new AtomicInteger();
      doAnswer(call -> {
        var input = created.getWALInstance();
        if (variant.equals("missing-interior") || vanished) {
          var extent = input.preOpenExtent();
          var segments = extent.segments().stream().map(segment -> segment.segment()).toList();
          assertTrue(segments.contains(coverageLsn[0].getSegment()));
          assertTrue(segments.contains(lostSegment[0] + 1));
          assertEquals(vanished, segments.contains(lostSegment[0]));
          if (vanished) {
            // Construction captured this unread extent. Both readers see the later unlink.
            Files.delete(directory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + "."
                + lostSegment[0] + ".wal"));
            assertSame(extent, input.preOpenExtent());
          }
        }
        var reader = input.openProofReader(coverageLsn[0], 16 * 1024 * 1024);
        var prefix = new ArrayList<Long>();
        WriteAheadLog.ProofBatch batch;
        do {
          batch = reader.next(16);
          for (var record : batch.records()) {
            if (record instanceof UpdatePageRecord page && page.getFileId()
                == created.getWriteCache().externalFileId(123457)) {
              prefix.add(page.getPageIndex());
            }
          }
        } while (batch.status() == WriteAheadLog.ProofStatus.MORE);
        if (variant.equals("missing-coverage")) {
          assertEquals(WriteAheadLog.ProofStatus.COVERAGE_NOT_RETAINED, batch.status());
          assertEquals(coverageLsn[0], batch.position());
          assertFalse(batch.readablePageFollows());
          assertTrue(prefix.isEmpty());
        } else if (variant.equals("missing-interior") || vanished) {
          assertEquals(vanished ? WriteAheadLog.ProofStatus.SEGMENT_VANISHED
              : WriteAheadLog.ProofStatus.MISSING_SEGMENT, batch.status());
          assertEquals(
              new LogSequenceNumber(lostSegment[0], vanished ? 0 : CASWALPage.RECORDS_OFFSET),
              batch.position());
          assertFalse(batch.readablePageFollows());
          assertEquals(vanished ? List.of(31L, 33L) : List.of(), prefix);
        } else {
          assertEquals(WriteAheadLog.ProofStatus.BROKEN_PAGE, batch.status());
          assertEquals(new LogSequenceNumber(metadataLsn[0].getSegment(),
              Math.toIntExact(brokenPage * CASWALPage.DEFAULT_PAGE_SIZE)), batch.position());
          assertEquals(interior || later,
              batch.readablePageFollows());
          assertEquals(List.of(31L, 33L), prefix);
        }
        assertEquals(clean ? ChangedPageTrackerStartup.ShutdownState.CLEAN
            : ChangedPageTrackerStartup.ShutdownState.UNCLEAN,
            field(DiskStorage.class, created, "startupShutdown"));
        call.callRealMethod();
        var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
        assertEquals(trusted, selected.isTrusted());
        assertEquals(completed, field(ChangedPageTracker.class, selected, "lastCompleted"));
        probes.incrementAndGet();
        return null;
      }).when(created).verifyStartupHistory();
      var barriers = new AtomicInteger();
      doAnswer(call -> {
        call.callRealMethod();
        if (!trusted && barriers.get() == 0) {
          assertInvalidatedAuthority(directory.resolve(ChangedPageTrackerFile.FILE_NAME));
        }
        barriers.incrementAndGet();
        return null;
      }).when(created).startupHistoryBarrier();
      try {
        created.open(new ContextConfiguration());
        assertEquals(1, probes.get());
        assertTrue(barriers.get() > 0);
        var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
        assertEquals(trusted, selected.isTrusted());
        if (trusted) {
          assertEquals(completed, field(ChangedPageTracker.class, selected, "lastCompleted"));
        }
        // An untrusted recovery checkpoint may reset continuity but must retain candidates.
        assertEquals(List.of(7L, 9L), candidates(selected, 123456));
        // Direct storage admission does not reopen the manager's cached security context.
        try (var baseline =
            ((com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded) manager.internal)
                .openNoAuthorization(created.getName())) {
          baseline.executeInTx(tx -> {
            try (var rows = baseline.query("select value from DamageBaseline")) {
              assertEquals("safe", rows.next().getProperty("value"));
              assertFalse(rows.hasNext());
            }
          });
        }
        assertEquals(missing ? List.of() : List.of(31L, 33L),
            candidates(selected, 123457));
        assertNotNull(field(AbstractStorage.class, created, "configuration"));
        assertNull(field(DiskStorage.class, created, "startupOwner"));
      } finally {
        created.close(null, true);
        created.preCloseSteps();
      }
    }
  }

  /** Invalid primary bytes cannot gain authority from a valid temporary file on either open. */
  @Test
  public void structuredCorruptionInvalidatesBeforeAdmissionAndCannotResurrect() throws Exception {
    for (var fault : List.of("truncated", "checksum", "version")) {
      var created = createObservedStorage("corruption-" + fault);
      var completed = UUID.randomUUID();
      seedTrustedHistory(created, false, completed);
      var path = ((Path) field(DiskStorage.class, created, "storagePath"))
          .resolve(ChangedPageTrackerFile.FILE_NAME);
      var temporary = path.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME);
      var valid = Files.readAllBytes(path);
      Files.write(temporary, valid);
      var invalid = valid.clone();
      if (fault.equals("truncated")) {
        invalid = Arrays.copyOf(valid, valid.length - 1);
      } else if (fault.equals("checksum")) {
        invalid[invalid.length - 1] ^= 1;
      } else {
        ByteBuffer.wrap(invalid).putInt(4, Integer.MAX_VALUE);
      }
      Files.write(path, invalid);
      var barriers = new AtomicInteger();
      doAnswer(call -> {
        call.callRealMethod();
        assertInvalidatedAuthority(path);
        barriers.incrementAndGet();
        return null;
      }).when(created).startupHistoryBarrier();
      try {
        created.open(new ContextConfiguration());
        var selected = assertSharedUntrustedTracker(created);
        assertTrue(candidates(selected, 123456).isEmpty());
        assertNull(field(DiskStorage.class, created, "startupOwner"));
        assertTrue(barriers.get() > 0);
      } finally {
        created.close(null, true);
        created.preCloseSteps();
      }
      replaceWal(realWal);
      if (session != null) {
        session.close();
        session = null;
      }
      var previousManager = manager;
      manager.close();
      manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString());
      assertNotSame(previousManager, manager);
      session = manager.open(created.getName(), "admin", "admin");
      storage = (DiskStorage) session.getStorage();
      realWal = storage.getWALInstance();
      assertNotSame(created, storage);
      var reopened = assertSharedUntrustedTracker(storage);
      assertFalse(completed.equals(field(ChangedPageTracker.class, reopened, "lastCompleted")));
      assertTrue(candidates(reopened, 123456).isEmpty());
      assertNull(field(DiskStorage.class, storage, "startupOwner"));
      session.close();
      session = null;
    }
  }

  /** Pre-DDL trusted coverage survives a dirty class-creation image and actual schema redo. */
  @Test
  public void trustedHistorySurvivesClassCreationAndCrash() throws Exception {
    var created = createObservedStorage();
    try (var source = manager.open("startup", "admin", "admin")) {
      source.getMetadata().getSchema().createClass("Baseline");
      source.executeInTx(tx -> source.newEntity("Baseline").setProperty("value", "before"));
      source.freeze();
      source.release();
    }
    var completed = UUID.randomUUID();
    var proofPages = new HashMap<Integer, TreeSet<Long>>();
    var replayedPages = new AtomicInteger();
    var recoveringStorage = new DiskStorage[1];
    var replayReadCache = new ReadCache[1];
    seedTrustedHistory(created, true, completed, false, (target, coverage) -> {
      var path = ((Path) field(DiskStorage.class, target, "storagePath"))
          .resolve(ChangedPageTrackerFile.FILE_NAME);
      assertEquals(coverage, ChangedPageTrackerStartup.load(path).coverageLsn());
      try (var source = manager.open("startup", "admin", "admin")) {
        var targetClass = source.getMetadata().getSchema().createClass("CrashTarget");
        targetClass.createProperty("name", PropertyType.STRING);
        targetClass.createIndex("CrashTarget.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
        source.executeInTx(tx -> source.newEntity("CrashTarget").setProperty("name", "first"));
        source.executeInTx(tx -> source.newEntity("CrashTarget").setProperty("name", "later"));
      }
      var input = target.getWALInstance();
      input.flush();
      var batch = input.read(coverage, 1000);
      while (!batch.isEmpty()) {
        for (var record : batch) {
          if (record instanceof PageOperation page) {
            proofPages.computeIfAbsent(target.getWriteCache().internalFileId(page.getFileId()),
                ignored -> new TreeSet<>()).add(page.getPageIndex());
          } else if (record instanceof UpdatePageRecord page) {
            proofPages.computeIfAbsent(target.getWriteCache().internalFileId(page.getFileId()),
                ignored -> new TreeSet<>()).add(page.getPageIndex());
          }
        }
        batch = input.next(batch.getLast().getLsn(), 1000);
      }
      // This case checks actual schema and data files, not the synthetic incomplete unit.
      proofPages.remove(123457);
      assertFalse("Real schema and data records must contribute proof candidates",
          proofPages.isEmpty());
    });
    replaceWal(realWal);
    session.close();
    session = null;
    manager.close();
    manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString());
    var engineField = manager.internal.getClass().getDeclaredField("disk");
    engineField.setAccessible(true);
    var originalEngine = (Engine) engineField.get(manager.internal);
    var observedEngine = spy(originalEngine);
    doAnswer(call -> {
      recoveringStorage[0] = (DiskStorage) call.callRealMethod();
      return recoveringStorage[0];
    }).when(observedEngine).createStorage(anyString(), anyLong(), anyLong(), anyInt(), any());
    engineField.set(manager.internal, observedEngine);
    try (var startup = mockStatic(ChangedPageTrackerStartup.class, call -> {
      if (call.getMethod().getName().equals("verify")) {
        var pending = (ChangedPageTrackerStartup.PendingHistory) call.getArgument(0);
        boolean needsProof = false;
        for (var entry : proofPages.entrySet()) {
          needsProof |=
              !candidates(pending.tracker(), entry.getKey()).containsAll(entry.getValue());
        }
        assertTrue("Saved baseline history must need real post-DDL proof marks", needsProof);
      }
      var result = call.callRealMethod();
      if (call.getMethod().getName().equals("verify")) {
        var pending = (ChangedPageTrackerStartup.PendingHistory) call.getArgument(0);
        for (var entry : proofPages.entrySet()) {
          assertTrue(
              "Proof must add schema and data pages before replay for file " + entry.getKey(),
              candidates(pending.tracker(), entry.getKey()).containsAll(entry.getValue()));
        }
        assertEquals(ChangedPageTrackerStartup.ShutdownState.UNCLEAN, call.getArgument(2));
        assertTrue(pending.tracker().isTrusted());
        // Observe actual page-redo delegation on the new storage, not a begin/end hook alone.
        var recovering = recoveringStorage[0];
        assertNotSame(created, recovering);
        var original = recovering.getReadCache();
        replayReadCache[0] = original;
        var observed = mock(ReadCache.class, invocation -> {
          if (!invocation.getMethod().getName().equals("loadOrAddForWrite")) {
            return delegatesTo(original).answer(invocation);
          }
          var cache = (WOWCache) invocation.getArgument(2);
          assertTrue("Actual page redo must run inside replay preservation",
              (boolean) field(WOWCache.class, cache, "startupReplayPreservation"));
          for (var entry : proofPages.entrySet()) {
            assertTrue(candidates(pending.tracker(), entry.getKey()).containsAll(entry.getValue()));
          }
          var loaded = delegatesTo(original).answer(invocation);
          for (var entry : proofPages.entrySet()) {
            assertTrue("Page redo must keep proof marks",
                candidates(pending.tracker(), entry.getKey()).containsAll(entry.getValue()));
          }
          replayedPages.incrementAndGet();
          return loaded;
        });
        setField(recovering, "readCache", observed);
      }
      return result;
    })) {
      session = manager.open("startup", "admin", "admin");
    } finally {
      engineField.set(manager.internal, originalEngine);
      if (replayReadCache[0] != null) {
        setField(recoveringStorage[0], "readCache", replayReadCache[0]);
      }
    }
    storage = (DiskStorage) session.getStorage();
    realWal = storage.getWALInstance();
    stateLock = (ScalableRWLock) field(AbstractStorage.class, storage, "stateLock");
    var selected = (ChangedPageTracker) field(DiskStorage.class, storage, "changedPageTracker");
    assertTrue(selected.isTrusted());
    assertEquals(completed, field(ChangedPageTracker.class, selected, "lastCompleted"));
    assertEquals(List.of(7L, 9L), candidates(selected, 123456));
    for (var entry : proofPages.entrySet()) {
      assertTrue("Replay must preserve proof pages for file " + entry.getKey(),
          candidates(selected, entry.getKey()).containsAll(entry.getValue()));
    }
    session.executeInTx(tx -> {
      try (var rows = session.query("select name from CrashTarget order by name")) {
        assertEquals(List.of("first", "later"),
            rows.stream().map(row -> row.<String>getProperty("name")).toList());
      }
      try (var rows = session.query("select value from Baseline")) {
        assertEquals("before", rows.next().getProperty("value"));
        assertFalse(rows.hasNext());
      }
    });
    assertNull(field(DiskStorage.class, storage, "startupOwner"));
    assertTrue("Real schema/data page redo must reach the observer", replayedPages.get() > 0);
    var recoveredCache = (WOWCache) storage.getWriteCache();
    assertFalse((boolean) field(WOWCache.class, recoveredCache, "startupReplayPreservation"));
    // Clear another sentinel on this recovered cache before a clean reopen can mask a leak.
    assertEquals(List.of(31L, 33L), candidates(selected, 123457));
    recoveredCache.addFile("ordinary-on-recovered.bin", recoveredCache.externalFileId(123457));
    assertTrue(candidates(selected, 123457).isEmpty());
    session.close();
    manager.close();
    manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString());
    session = manager.open("startup", "admin", "admin");
    storage = (DiskStorage) session.getStorage();
    realWal = storage.getWALInstance();
    selected = (ChangedPageTracker) field(DiskStorage.class, storage, "changedPageTracker");
    assertTrue("A clean physical reopen must retain the proved continuity", selected.isTrusted());
    assertEquals(completed, field(ChangedPageTracker.class, selected, "lastCompleted"));
    assertEquals(List.of(7L, 9L), candidates(selected, 123456));
    assertNull(field(DiskStorage.class, storage, "startupOwner"));
    stateLock = (ScalableRWLock) field(AbstractStorage.class, storage, "stateLock");
    var cache = (WOWCache) storage.getWriteCache();
    assertFalse((boolean) field(WOWCache.class, cache, "startupReplayPreservation"));
    cache.addFile("ordinary-after-crash.bin", cache.externalFileId(123456));
    assertTrue(candidates(selected, 123456).isEmpty());
  }

  private static void assertInvalidatedAuthority(Path path) throws Exception {
    // Windows may keep an empty primary name. Corrupt old bytes cannot remain authoritative.
    assertTrue("Durable invalidation must remove or empty " + path,
        !Files.exists(path) || Files.size(path) == 0);
    assertNull(ChangedPageTrackerStartup.load(path).coverageLsn());
    // Linux may leave temporary bytes. The temporary name itself must never grant authority.
    assertNull(ChangedPageTrackerStartup.load(
        path.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME)).coverageLsn());
  }

  private void assertTrustedReopen(boolean dirty) throws Exception {
    var created = createObservedStorage();
    var completed = UUID.randomUUID();
    seedTrustedHistory(created, dirty, completed);
    var selected = new ChangedPageTracker[1];
    try (var startup = mockStatic(ChangedPageTrackerStartup.class, call -> {
      var result = call.callRealMethod();
      if (call.getMethod().getName().equals("verify")) {
        var pending = (ChangedPageTrackerStartup.PendingHistory) call.getArgument(0);
        selected[0] = pending.tracker();
        assertEquals(dirty ? ChangedPageTrackerStartup.ShutdownState.UNCLEAN
            : ChangedPageTrackerStartup.ShutdownState.CLEAN, call.getArgument(2));
        var ids = (ChangedPageTrackerStartup.FileIds) call.getArgument(3);
        var cache = (WOWCache) created.getWriteCache();
        long external = cache.externalFileId(123456);
        assertEquals(123456, ids.internalFileId(external));
        assertNull(ids.currentName(123456));
        assertTrue(selected[0].isTrusted());
      }
      return result;
    })) {
      doAnswer(call -> {
        assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
        assertNotNull(field(DiskStorage.class, created, "startupOwner"));
        assertFalse((boolean) field(WOWCache.class, created.getWriteCache(),
            "startupReplayPreservation"));
        call.callRealMethod();
        assertNull(field(DiskStorage.class, created, "startupOwner"));
        return null;
      }).when(created).finishStartupHistory();
      created.open(new ContextConfiguration());
    }
    assertSame(selected[0], field(DiskStorage.class, created, "changedPageTracker"));
    assertSame(selected[0], field(WOWCache.class, created.getWriteCache(), "changedPageTracker"));
    assertTrue(selected[0].isTrusted());
    assertNull(field(ChangedPageTracker.class, selected[0], "sealed"));
    assertEquals(completed, field(ChangedPageTracker.class, selected[0], "lastCompleted"));
    assertEquals(List.of(7L, 9L), candidates(selected[0], 123456));
    assertEquals("Proof retains pages in an incomplete unit, including a page before create",
        List.of(31L, 33L), candidates(selected[0], 123457));
    // No startup interval leaks into ordinary file events or reused sessions.
    clearInvocations(created);
    created.open(new ContextConfiguration());
    verify(created, never()).initWalAndDiskCacheOnOpen(any());
    assertNull(field(DiskStorage.class, created, "startupOwner"));
  }

  /** Trusted physical attempts replace shared state, while OPEN session reuse retains continuity. */
  @Test
  public void repeatedTrustedOpensRetainContinuityWithoutReloadingOnReuse() throws Exception {
    var created = createObservedStorage();
    var completed = UUID.randomUUID();
    seedTrustedHistory(created, false, completed);
    Object previous = null;
    var owners = new ArrayList<Object>();
    doAnswer(call -> {
      call.callRealMethod();
      var owner = field(DiskStorage.class, created, "startupOwner");
      assertNotNull(owner);
      owners.add(owner);
      return null;
    }).when(created).initWalAndDiskCacheOnOpen(any());
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        clearInvocations(created);
        created.open(new ContextConfiguration());
        assertEquals(attempt + 1, owners.size());
        if (attempt > 0) {
          assertNotSame(owners.get(0), owners.get(attempt));
        }
        var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
        assertNotSame(previous, selected);
        assertSame(selected, field(WOWCache.class, created.getWriteCache(), "changedPageTracker"));
        assertTrue(selected.isTrusted());
        assertEquals(completed, field(ChangedPageTracker.class, selected, "lastCompleted"));
        assertEquals(List.of(7L, 9L), candidates(selected, 123456));
        assertNull(field(ChangedPageTracker.class, selected, "sealed"));
        assertNull(field(DiskStorage.class, created, "startupOwner"));
        assertFalse((boolean) field(WOWCache.class, created.getWriteCache(),
            "startupReplayPreservation"));
        created.open(new ContextConfiguration());
        verify(created).initWalAndDiskCacheOnOpen(any());
        assertSame(selected, field(DiskStorage.class, created, "changedPageTracker"));
        previous = selected;
        created.close(null, true);
      }
    } finally {
      created.preCloseSteps();
    }
  }

  /** Linux owner fallback performs real unlink and folder force and restores inherited cancellation. */
  @Test
  public void linuxOwnerInvalidatesAndRestoresInterruptBeforeAdmission() throws Exception {
    org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
    var created = createObservedStorage();
    seedTrustedHistory(created, false, UUID.randomUUID());
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    var path = directory.resolve(ChangedPageTrackerFile.FILE_NAME);
    saveHistory(path, new ChangedPageTracker(), ChangedPageTrackerStartup.load(path).coverageLsn());
    var events = new ArrayList<String>();
    var barriers = new AtomicInteger();
    try (var filesystem =
        com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerCheckpointTest
            .observeFilesystem(call -> {
              var result = call.callRealMethod();
              if (call.getMethod().getName().equals("delete") && path.equals(call.getArgument(0))) {
                events.add("unlink");
              }
              if (call.getMethod().getName().equals("forceFolder")
                  && directory.equals(call.getArgument(0))) {
                events.add("force");
              }
              return result;
            });
        var startup = mockStatic(ChangedPageTrackerStartup.class, call -> {
          if (!call.getMethod().getName().equals("durableDistrust")
              || call.getArguments().length != 2 || barriers.get() != 0) {
            return call.callRealMethod();
          }
          assertNotNull(field(DiskStorage.class, created, "startupOwner"));
          Thread.currentThread().interrupt();
          try {
            var result = call.callRealMethod();
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
            assertInvalidatedAuthority(path);
            assertEquals(List.of("unlink", "force"), events);
            barriers.incrementAndGet();
            return result;
          } finally {
            Thread.interrupted();
          }
        })) {
      created.open(new ContextConfiguration());
    } finally {
      Thread.interrupted();
      created.preCloseSteps();
    }
    assertEquals(1, barriers.get());
    created.close(null, true);
    try (var channel = FileChannel.open(directory.resolve("dirty.fl"), StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      assertNotNull(lock);
    }
    replaceWal(realWal);
    manager.close();
    manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString());
    session = manager.open("startup", "admin", "admin");
    storage = (DiskStorage) session.getStorage();
    realWal = storage.getWALInstance();
    var selected = assertSharedUntrustedTracker(storage);
    assertNull(field(ChangedPageTracker.class, selected, "lastCompleted"));
  }

  /** Real committed deletion reaches redo with preserved marks and removes the registered file. */
  @Test
  public void committedDeleteRedoPreservesTrustedCandidates() throws Exception {
    var created = createObservedStorage();
    var initial = (WOWCache) created.getWriteCache();
    initial.addFile("sentinel.bin", initial.externalFileId(123456));
    seedTrustedHistory(created, true, UUID.randomUUID(), false, (target, coverage) -> {
      var actual = target.getWALInstance();
      actual.log(new AtomicUnitStartRecord(true, 124));
      actual.log(
          new com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord(
              124, target.getWriteCache().externalFileId(123456)));
      actual.log(new AtomicUnitEndRecord(124, false, null));
    });
    var original = created.getReadCache();
    var observed = mock(ReadCache.class, delegatesTo(original));
    var deletes = new AtomicInteger();
    doAnswer(call -> {
      var cache = (WOWCache) call.getArgument(1);
      assertEquals(123456, cache.internalFileId(call.getArgument(0)));
      assertTrue(cache.exists("sentinel.bin"));
      assertTrue((boolean) field(WOWCache.class, cache, "startupReplayPreservation"));
      original.deleteFile(call.getArgument(0), cache);
      assertFalse(cache.exists("sentinel.bin"));
      var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
      assertTrue(selected.isTrusted());
      assertEquals(List.of(7L, 9L), candidates(selected, 123456));
      deletes.incrementAndGet();
      return null;
    }).when(observed).deleteFile(anyLong(), any());
    setField(created, "readCache", observed);
    try {
      created.open(new ContextConfiguration());
      assertEquals(1, deletes.get());
      var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
      assertTrue(selected.isTrusted());
      assertEquals(List.of(7L, 9L), candidates(selected, 123456));
      assertFalse(created.getWriteCache().exists("sentinel.bin"));
      assertNull(field(DiskStorage.class, created, "startupOwner"));
      assertFalse((boolean) field(WOWCache.class, created.getWriteCache(),
          "startupReplayPreservation"));
    } finally {
      setField(created, "readCache", original);
      created.preCloseSteps();
    }
  }

  /** Failed fallback excludes maintenance through abort and permits only a fresh-object retry. */
  @Test
  public void failedFallbackExcludesMaintenanceAndFreshRetryCannotRestoreTrust() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID());
    injectReplayFailure(created, new IllegalStateException("revoke proved history"));
    var failure = new IOException("failed coupled fallback");
    var workers = Executors.newFixedThreadPool(3);
    var tasks = new ArrayList<Future<?>>();
    var vacuumEntry = new CountDownLatch(1);
    var vacuumWorker = new AtomicReference<Thread>();
    var fuzzyEntry = new CountDownLatch(1);
    var actualLock = (ScalableRWLock) field(AbstractStorage.class, created, "stateLock");
    var reader = mock(java.util.concurrent.locks.Lock.class, call -> {
      // Open also takes this lock. Only the submitted vacuum proves concurrent entry.
      if (call.getMethod().getName().equals("lock")
          && Thread.currentThread() == vacuumWorker.get()) {
        vacuumEntry.countDown();
      } else if (call.getMethod().getName().equals("tryLock")) {
        fuzzyEntry.countDown();
      }
      return delegatesTo(actualLock.readLock()).answer(call);
    });
    var observedLock = mock(ScalableRWLock.class, delegatesTo(actualLock));
    doReturn(reader).when(observedLock).readLock();
    setField(created, "stateLock", observedLock);
    var aborts = new AtomicInteger();
    var barriers = new AtomicInteger();
    var payload = new AtomicInteger();
    var recovery = new AtomicInteger();
    setField(created, "recoverListener",
        (com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageRecoverListener) recovery::incrementAndGet);
    doAnswer(call -> {
      assertTrue(actualLock.isWriteLockedByCurrentThread());
      assertNotNull(field(DiskStorage.class, created, "startupOwner"));
      tasks.add(workers.submit(created::makeFuzzyCheckpoint));
      tasks.add(workers.submit(() -> {
        assertThrows(com.jetbrains.youtrackdb.internal.core.exception.StorageException.class,
            () -> created.backup(() -> {
              payload.incrementAndGet();
              return java.util.Collections.emptyIterator();
            }, ignored -> null, ignored -> new java.io.ByteArrayOutputStream(), ignored -> {
            }));
      }));
      assertTrue(fuzzyEntry.await(5, TimeUnit.SECONDS));
      tasks.get(0).get(10, TimeUnit.SECONDS);
      tasks.get(1).get(10, TimeUnit.SECONDS);
      tasks.add(workers.submit(() -> {
        vacuumWorker.set(Thread.currentThread());
        var vacuum = AbstractStorage.class.getDeclaredMethod("runWALVacuum");
        vacuum.setAccessible(true);
        vacuum.invoke(created);
        return null;
      }));
      assertTrue(vacuumEntry.await(5, TimeUnit.SECONDS));
      call.callRealMethod();
      aborts.incrementAndGet();
      return null;
    }).when(created).abortStartupHistory(any());
    doAnswer(call -> {
      assertNull(field(DiskStorage.class, created, "startupOwner"));
      assertFalse((boolean) field(WOWCache.class, created.getWriteCache(),
          "startupReplayPreservation"));
      assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
      return call.callRealMethod();
    }).when(created).postCloseSteps(eq(false), eq(true), anyLong());
    var originalReadCache = created.getReadCache();
    var observedReadCache = mock(ReadCache.class, delegatesTo(originalReadCache));
    var cacheCloses = new AtomicInteger();
    var cleanCacheCloses = new AtomicInteger();
    doAnswer(call -> {
      // Count entry even if open suppresses an observer assertion during cleanup.
      cacheCloses.incrementAndGet();
      // Observe cleanup at cache-close entry, before delegation can hide an ordering error.
      assertNull(field(DiskStorage.class, created, "startupOwner"));
      var cache = (WOWCache) call.getArgument(0);
      assertFalse((boolean) field(WOWCache.class, cache, "startupReplayPreservation"));
      assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
      cleanCacheCloses.incrementAndGet();
      originalReadCache.closeStorage(cache);
      return null;
    }).when(observedReadCache).closeStorage(any());
    setField(created, "readCache", observedReadCache);
    var flushes = new AtomicInteger();
    var flush = AbstractStorage.class.getDeclaredMethod("flushAllData");
    flush.setAccessible(true);
    flush.invoke(doAnswer(call -> {
      flushes.incrementAndGet();
      return call.callRealMethod();
    }).when(created));
    clearInvocations(created);
    try {
      try (var startup = mockStatic(ChangedPageTrackerStartup.class, call -> {
        if (call.getMethod().getName().equals("durableDistrust")
            && call.getArguments().length == 2) {
          assertFalse(((ChangedPageTracker) field(DiskStorage.class, created,
              "changedPageTracker")).isTrusted());
          barriers.incrementAndGet();
          throw failure;
        }
        return call.callRealMethod();
      })) {
        var thrown = assertThrows(RuntimeException.class,
            () -> created.open(new ContextConfiguration()));
        assertSame(failure, thrown.getCause());
      }
      for (var task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
      assertEquals(1, barriers.get());
      assertEquals(1, aborts.get());
      assertEquals(1, cacheCloses.get());
      assertEquals(1, cleanCacheCloses.get());
      assertEquals(0, recovery.get());
      assertEquals(0, flushes.get());
      assertEquals(0, payload.get());
      verify(created, never()).checkpointChangedPages(anyLong());
      assertInvalidatedAuthority(((Path) field(DiskStorage.class, created, "storagePath"))
          .resolve(ChangedPageTrackerFile.FILE_NAME));
    } finally {
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
      setField(created, "stateLock", actualLock);
      setField(created, "readCache", originalReadCache);
      created.preCloseSteps();
    }
    replaceWal(realWal);
    manager.close();
    manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString());
    session = manager.open("startup", "admin", "admin");
    storage = (DiskStorage) session.getStorage();
    realWal = storage.getWALInstance();
    assertNotSame(created, storage);
    assertEquals(AbstractStorage.STATUS.OPEN, storage.getStatus());
    var selected = assertSharedUntrustedTracker(storage);
    assertNull(field(ChangedPageTracker.class, selected, "lastCompleted"));
    assertNull(field(DiskStorage.class, storage, "startupOwner"));
    assertFalse((boolean) field(WOWCache.class, storage.getWriteCache(),
        "startupReplayPreservation"));
  }

  /** Broken-page replay revokes loaded trust before its first error diagnostic. */
  @Test
  public void brokenPageReplayDistrustsBeforeDiagnostics() throws Exception {
    assertAbsorbedReplayFailure(new WALPageBrokenException("broken startup page"));
  }

  /** Runtime replay failure revokes loaded trust before its first error diagnostic. */
  @Test
  public void runtimeReplayDistrustsBeforeDiagnostics() throws Exception {
    assertAbsorbedReplayFailure(new IllegalStateException("runtime startup replay"));
  }

  private void assertAbsorbedReplayFailure(RuntimeException failure) throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID());
    injectReplayFailure(created, failure);
    var observed = new boolean[1];
    var logger = LogManager.instance();
    try (var logging = mockStatic(LogManager.class)) {
      var diagnostic = mock(LogManager.class, call -> {
        if (call.getMethod().getName().equals("error") && call.getArguments().length > 2
            && call.getArgument(2) == failure) {
          observed[0] = true;
          assertFalse(((ChangedPageTracker) field(DiskStorage.class, created,
              "changedPageTracker")).isTrusted());
        }
        return delegatesTo(logger).answer(call);
      });
      logging.when(LogManager::instance).thenReturn(diagnostic);
      created.open(new ContextConfiguration());
    }
    assertTrue(observed[0]);
    assertNull(field(DiskStorage.class, created, "startupOwner"));
    created.close(null, true);
  }

  /** A post-replay barrier failure prevents recovery notification and leaves CLOSED. */
  @Test
  public void postReplayFallbackFailurePreventsRecoveryListener() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID());
    injectReplayFailure(created, new IllegalStateException("replay distrust"));
    var histogram =
        mock(com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager.class);
    var engine =
        mock(com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine.class);
    when(engine.getHistogramManager()).thenReturn(histogram);
    doAnswer(call -> {
      call.callRealMethod();
      @SuppressWarnings("unchecked")
      var engines =
          (List<com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine>) field(
              AbstractStorage.class, created, "indexEngines");
      engines.add(engine);
      return null;
    }).when(created).verifyStartupHistory();
    var failure = new IOException("post-replay invalidation");
    var entered = new boolean[1];
    var fullFlush = new AtomicInteger();
    var flush = AbstractStorage.class.getDeclaredMethod("flushAllData");
    flush.setAccessible(true);
    flush.invoke(doAnswer(call -> {
      fullFlush.incrementAndGet();
      return call.callRealMethod();
    }).when(created));
    clearInvocations(created);
    setField(created, "recoverListener",
        (com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageRecoverListener) () -> entered[0] =
            true);
    try (var startup = mockStatic(ChangedPageTrackerStartup.class, call -> {
      if (call.getMethod().getName().equals("durableDistrust")) {
        throw failure;
      }
      return call.callRealMethod();
    })) {
      var thrown = assertThrows(RuntimeException.class,
          () -> created.open(new ContextConfiguration()));
      assertSame(failure, thrown.getCause());
      assertFalse(entered[0]);
      assertEquals(0, fullFlush.get());
      verify(histogram, never()).flushIfDirty();
      verify(created, never()).checkpointChangedPages(anyLong());
      assertTrue(created.isClosed(null));
      assertNull(field(DiskStorage.class, created, "startupOwner"));
    }
  }

  /** Abort attempts end, distrust and durable removal independently while preserving the Error. */
  @Test
  public void abortKeepsPrimaryAndAttemptsAllOwnerStepsUnderWriteExclusion() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID());
    var primary = new Error("proof failed");
    var secondary = new Error("end failed");
    var steps = new ArrayList<String>();
    doAnswer(call -> {
      var lock = (ScalableRWLock) field(AbstractStorage.class, created, "stateLock");
      assertTrue(lock.isWriteLockedByCurrentThread());
      steps.add("end");
      throw secondary;
    }).when(created).endStartupReplay();
    doAnswer(call -> {
      steps.add("resource-close");
      assertTrue(created.isClosed(null));
      assertNull(field(DiskStorage.class, created, "startupOwner"));
      return call.callRealMethod();
    }).when(created).postCloseSteps(eq(false), eq(true), anyLong());
    try (var startup = mockStatic(ChangedPageTrackerStartup.class, call -> {
      if (call.getMethod().getName().equals("verify")) {
        throw primary;
      }
      if (call.getMethod().getName().equals("replayFailed")) {
        steps.add("distrust");
      }
      return call.callRealMethod();
    })) {
      assertSame(primary,
          assertThrows(Error.class, () -> created.open(new ContextConfiguration())));
    }
    assertTrue(Arrays.asList(primary.getSuppressed()).contains(secondary));
    assertEquals(List.of("end", "distrust", "resource-close"), steps);
    var path = ((Path) field(DiskStorage.class, created, "storagePath"))
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    assertFalse(Files.exists(path));
  }

  /** Replay deletion keeps unrelated saved candidates and normal clearing resumes afterward. */
  @Test
  public void preservationBeginsBeforeNonDurableDeletionAndEndsBeforeComponents() throws Exception {
    assertDeletionPreservation(false);
  }

  /** An Error before deletion returns still ends the interval and clears the replay skip set. */
  @Test
  public void deletionFailureEndsPreservationAndKeepsPrimary() throws Exception {
    assertDeletionPreservation(true);
  }

  private void assertDeletionPreservation(boolean failDeletion) throws Exception {
    var created = createObservedStorage();
    var cache = (WOWCache) created.getWriteCache();
    cache.addFile("sentinel.bin", cache.externalFileId(123456));
    seedTrustedHistory(created, true, UUID.randomUUID());
    var originalReadCache = created.getReadCache();
    var primary = new Error("delete before skip set returns");
    var previousSkipSet = field(AbstractStorage.class, created, "deletedNonDurableFileIds");
    var replaySkipSet = new Object[1];
    var replayWal = new WriteAheadLog[1];
    doAnswer(call -> {
      call.callRealMethod();
      var actualWal = created.getWALInstance();
      replayWal[0] = actualWal;
      var observedWal = mock(WriteAheadLog.class, delegatesTo(actualWal));
      doAnswer(read -> {
        replaySkipSet[0] = field(AbstractStorage.class, created, "deletedNonDurableFileIds");
        assertTrue(((it.unimi.dsi.fastutil.ints.IntOpenHashSet) replaySkipSet[0]).contains(123456));
        return delegatesTo(actualWal).answer(read);
      }).when(observedWal).read(any(), anyInt());
      setField(created, "writeAheadLog", observedWal);
      return null;
    }).when(created).verifyStartupHistory();
    var seen = new boolean[1];
    var observed = mock(ReadCache.class, delegatesTo(originalReadCache));
    doAnswer(call -> {
      var actual = (WOWCache) call.getArgument(1);
      seen[0] = true;
      assertTrue((boolean) field(WOWCache.class, actual, "startupReplayPreservation"));
      if (failDeletion) {
        throw primary;
      }
      originalReadCache.deleteFile(call.getArgument(0), actual);
      assertEquals(List.of(7L, 9L), candidates(
          (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker"), 123456));
      return null;
    }).when(observed).deleteFile(anyLong(), any());
    setField(created, "readCache", observed);
    doAnswer(call -> {
      call.callRealMethod();
      var ids = (it.unimi.dsi.fastutil.ints.IntOpenHashSet) field(WOWCache.class,
          created.getWriteCache(), "nonDurableFileIds");
      ids.add(123456);
      return null;
    }).when(created).initWalAndDiskCacheOnOpen(any());
    try {
      if (failDeletion) {
        assertSame(primary, assertThrows(Error.class,
            () -> created.open(new ContextConfiguration())));
      } else {
        created.open(new ContextConfiguration());
        assertNotNull(replaySkipSet[0]);
        assertNotSame(replaySkipSet[0],
            field(AbstractStorage.class, created, "deletedNonDurableFileIds"));
        replaySkipSet[0] = null;
        var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
        assertEquals(List.of(7L, 9L), candidates(selected, 123456));
        var actual = (WOWCache) created.getWriteCache();
        actual.addFile("sentinel.bin", actual.externalFileId(123456));
        assertTrue(candidates(selected, 123456).isEmpty());
      }
      assertTrue(seen[0]);
      assertFalse((boolean) field(WOWCache.class, created.getWriteCache(),
          "startupReplayPreservation"));
      assertNotSame("Replay cleanup releases the skip set even when deletion fails",
          previousSkipSet, field(AbstractStorage.class, created, "deletedNonDurableFileIds"));
      assertTrue(((it.unimi.dsi.fastutil.ints.IntOpenHashSet) field(AbstractStorage.class, created,
          "deletedNonDurableFileIds")).isEmpty());
    } finally {
      if (replayWal[0] != null) {
        setField(created, "writeAheadLog", replayWal[0]);
      }
      setField(created, "readCache", originalReadCache);
      replaySkipSet[0] = null;
      created.preCloseSteps();
    }
  }

  /** An escaping replay Error stays identical even when interval end fails once and abort retries. */
  @Test
  public void escapingReplayErrorSurvivesFailedEndAndAbortRetries() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID());
    var primary = new Error("escaping replay");
    var secondary = new Error("first interval end");
    injectReplayFailure(created, primary);
    var attempts = new int[1];
    doAnswer(call -> {
      if (++attempts[0] == 1) {
        throw secondary;
      }
      return call.callRealMethod();
    }).when(created).endStartupReplay();
    assertSame(primary, assertThrows(Error.class, () -> created.open(new ContextConfiguration())));
    assertEquals(2, attempts[0]);
    assertTrue(Arrays.asList(primary.getSuppressed()).contains(secondary));
    assertFalse((boolean) field(WOWCache.class, created.getWriteCache(),
        "startupReplayPreservation"));
    assertNull(field(DiskStorage.class, created, "startupOwner"));
  }

  /** Late live distrust is resolved by the final barrier rather than the earlier proof result. */
  @Test
  public void finalBarrierUsesLiveTrustAfterComponentOpen() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, false, UUID.randomUUID());
    var path = ((Path) field(DiskStorage.class, created, "storagePath"))
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    var resolved = new AtomicInteger();
    doAnswer(call -> {
      resolved.incrementAndGet();
      assertTrue(Files.exists(path));
      var owner = field(DiskStorage.class, created, "startupOwner");
      var pending =
          (ChangedPageTrackerStartup.PendingHistory) field(owner.getClass(), owner, "history");
      ChangedPageTrackerStartup.replayFailed(pending);
      call.callRealMethod();
      assertFalse(Files.exists(path));
      assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
      return null;
    }).when(created).finishStartupHistory();
    created.open(new ContextConfiguration());
    assertEquals(1, resolved.get());
    assertNull(field(DiskStorage.class, created, "startupOwner"));
  }

  /** Checked replay failure retains its exact cause when interval end also fails. */
  @Test
  public void checkedReplayFailureRetainsCauseThroughIntervalEndFailure() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID());
    var primary = new IOException("checked replay failure");
    var secondary = new Error("end failure");
    injectReplayFailure(created, primary);
    doThrow(secondary).when(created).endStartupReplay();
    var thrown = assertThrows(RuntimeException.class,
        () -> created.open(new ContextConfiguration()));
    assertSame(primary, thrown.getCause());
    assertTrue(Arrays.asList(primary.getSuppressed()).contains(secondary));
    assertNull(field(DiskStorage.class, created, "startupOwner"));
  }

  /** Failed pending resolution cannot skip the independent tracker invalidation during abort. */
  @Test
  public void abortInvalidatesAuthorityEvenWhenPendingDistrustFails() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, false, UUID.randomUUID());
    var primary = new Error("proof error");
    var secondary = new Error("pending resolution error");
    var path = ((Path) field(DiskStorage.class, created, "storagePath"))
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    try (var startup = mockStatic(ChangedPageTrackerStartup.class, call -> {
      if (call.getMethod().getName().equals("verify")) {
        throw primary;
      }
      if (call.getMethod().getName().equals("replayFailed")) {
        throw secondary;
      }
      return call.callRealMethod();
    })) {
      assertSame(primary,
          assertThrows(Error.class, () -> created.open(new ContextConfiguration())));
    }
    assertTrue(Arrays.asList(primary.getSuppressed()).contains(secondary));
    assertFalse(Files.exists(path));
    assertNull(field(DiskStorage.class, created, "startupOwner"));
  }

  /** Broken-page identity survives failure of the diagnostic that normally absorbs replay. */
  @Test
  public void brokenPageReplayKeepsPrimaryWhenDiagnosticsFail() throws Exception {
    assertReplayDiagnosticFailure(new WALPageBrokenException("broken page primary"));
  }

  /** Runtime replay identity survives failure of the diagnostic that normally absorbs replay. */
  @Test
  public void runtimeReplayKeepsPrimaryWhenDiagnosticsFail() throws Exception {
    assertReplayDiagnosticFailure(new IllegalStateException("runtime replay primary"));
  }

  private void assertReplayDiagnosticFailure(RuntimeException primary) throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, true, UUID.randomUUID());
    injectReplayFailure(created, primary);
    var logger = LogManager.instance();
    var secondary = new Error("diagnostic failure");
    try (var logging = mockStatic(LogManager.class)) {
      var diagnostic = mock(LogManager.class, call -> {
        if (call.getMethod().getName().equals("error") && call.getArguments().length > 2
            && call.getArgument(2) == primary) {
          throw secondary;
        }
        return delegatesTo(logger).answer(call);
      });
      logging.when(LogManager::instance).thenReturn(diagnostic);
      assertSame(primary, assertThrows(RuntimeException.class,
          () -> created.open(new ContextConfiguration())));
    }
    assertTrue(Arrays.asList(primary.getSuppressed()).contains(secondary));
    assertNull(field(DiskStorage.class, created, "startupOwner"));
    assertTrue(created.isClosed(null));
  }

  /** The shared replay consumer sends no startup notification outside a physical-open owner. */
  @Test
  public void ordinaryRestoreConsumerDoesNotNotifyStartupHistory() throws Exception {
    var consumer = AbstractStorage.class.getDeclaredMethod("restoreFrom", WriteAheadLog.class,
        LogSequenceNumber.class);
    consumer.setAccessible(true);
    var input = mock(WriteAheadLog.class);
    when(input.read(any(), anyInt())).thenThrow(new WALPageBrokenException("backup replay"));
    try (var startup = mockStatic(ChangedPageTrackerStartup.class, CALLS_REAL_METHODS)) {
      consumer.invoke(storage, input, COVERAGE);
      startup.verifyNoInteractions();
    }
  }

  /** Every fallback input acquires a real owner and resolves authority before admission. */
  @Test
  public void realOwnerFallbackMatrixCompletesBeforeAdmission() throws Exception {
    for (var input : List.of("missing", "temporary", "untrusted", "unprovable")) {
      var created = createObservedStorage("fallback-" + input);
      seedTrustedHistory(created, false, UUID.randomUUID());
      var path = ((Path) field(DiskStorage.class, created, "storagePath"))
          .resolve(ChangedPageTrackerFile.FILE_NAME);
      if (input.equals("missing") || input.equals("temporary")) {
        Files.delete(path);
        if (input.equals("temporary")) {
          Files.writeString(path.resolveSibling(path.getFileName() + ".tmp"), "temporary only");
        }
      } else if (input.equals("untrusted")) {
        saveHistory(path, new ChangedPageTracker(), COVERAGE);
      } else {
        var pending = ChangedPageTrackerStartup.load(path);
        saveHistory(path, pending.tracker(), new LogSequenceNumber(Long.MAX_VALUE, 32));
      }
      var barriers = new AtomicInteger();
      doAnswer(call -> {
        if (field(DiskStorage.class, created, "startupOwner") == null) {
          return call.callRealMethod();
        }
        call.callRealMethod();
        assertFalse(Files.exists(path));
        barriers.incrementAndGet();
        return null;
      }).when(created).startupHistoryBarrier();
      created.open(new ContextConfiguration());
      assertFalse(((ChangedPageTracker) field(DiskStorage.class, created,
          "changedPageTracker")).isTrusted());
      assertTrue(barriers.get() >= 2);
      created.close(null, true);
    }
  }

  /** Real unknown-content adoption runs after components and invalidates loaded authority first. */
  @Test
  public void lateAdoptionResolvesLoadedHistoryWhileKnownFileKeepsTrust() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, false, UUID.randomUUID());
    var path = ((Path) field(DiskStorage.class, created, "storagePath"))
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    var adopted = new AtomicInteger();
    doAnswer(call -> {
      adopted.incrementAndGet();
      var cache = (WOWCache) created.getWriteCache();
      var selected = (ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker");
      var known = cache.files().keySet().iterator().next();
      cache.loadFile(known);
      assertTrue(selected.isTrusted());
      assertTrue(Files.exists(path));
      long id = cache.bookFileId("late.bin");
      Files.write(cache.getRootDirectory().resolve("late_" + cache.internalFileId(id) + ".bin"),
          new byte[com.jetbrains.youtrackdb.internal.core.storage.fs.File.HEADER_SIZE]);
      assertEquals(id, cache.loadFile("late.bin"));
      assertFalse(Files.exists(path));
      assertFalse(selected.isTrusted());
      assertEquals(List.of(7L, 9L), candidates(selected, 123456));
      assertNotNull(field(DiskStorage.class, created, "startupOwner"));
      call.callRealMethod();
      assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
      return null;
    }).when(created).finishStartupHistory();
    created.open(new ContextConfiguration());
    assertEquals(1, adopted.get());
  }

  /** A new storage object over the same files inherits no owner from a failed attempt. */
  @Test
  public void failedAttemptDoesNotLeakOwnerToNewManager() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, false, UUID.randomUUID());
    var primary = new Error("recover using a new manager");
    doThrow(primary).when(created).verifyStartupHistory();
    assertSame(primary, assertThrows(Error.class, () -> created.open(new ContextConfiguration())));
    assertNull(field(DiskStorage.class, created, "startupOwner"));
    replaceWal(realWal);
    manager.close();
    manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString());
    session = manager.open("startup", "admin", "admin");
    var reopened = (DiskStorage) session.getStorage();
    assertNotSame(created, reopened);
    assertEquals(AbstractStorage.STATUS.OPEN, reopened.getStatus());
    assertNull(field(DiskStorage.class, reopened, "startupOwner"));
    // The ordinary bridge teardown must not reinstall the WAL from the closed manager.
    storage = reopened;
    realWal = reopened.getWALInstance();
  }

  /** A checksum-valid stale side file is removed by create without loading or proving it. */
  @Test
  public void creationNeverLoadsValidStaleAuthority() throws Exception {
    var created = createObservedStorage();
    seedTrustedHistory(created, false, UUID.randomUUID());
    var path = ((Path) field(DiskStorage.class, created, "storagePath"))
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    var bytes = Files.readAllBytes(path);
    var stale = path.getParent().resolveSibling("stale").resolve(path.getFileName());
    Files.createDirectories(stale.getParent());
    Files.write(stale, bytes);
    try (var startup = mockStatic(ChangedPageTrackerStartup.class, CALLS_REAL_METHODS)) {
      manager.create("stale", DatabaseType.DISK, "admin", "admin", "admin");
      startup.verifyNoInteractions();
    }
  }

  /** Proof, post-replay, final resolution and abort exclude all real maintenance admissions. */
  @Test
  public void unresolvedOwnerExcludesCheckpointVacuumAndBackup() throws Exception {
    for (var boundary : List.of("proof", "post-replay", "final", "abort")) {
      var created = createObservedStorage("admission-" + boundary);
      seedTrustedHistory(created, boundary.equals("post-replay"), UUID.randomUUID());
      var executor = Executors.newFixedThreadPool(3);
      var tasks = new ArrayList<Future<?>>();
      var protectedEntry = new CountDownLatch(1);
      var held = new AtomicInteger();
      var opener = Thread.currentThread();
      doAnswer(call -> {
        if (Thread.currentThread() != opener) {
          protectedEntry.countDown();
          assertNull(field(DiskStorage.class, created, "startupOwner"));
        }
        return call.callRealMethod();
      }).when(created).checkpointChangedPages(anyLong());
      org.mockito.stubbing.Answer<Object> hold = call -> {
        if (field(DiskStorage.class, created, "startupOwner") == null) {
          return call.callRealMethod();
        }
        assertTrue(((ScalableRWLock) field(AbstractStorage.class, created, "stateLock"))
            .isWriteLockedByCurrentThread());
        assertNotNull(field(DiskStorage.class, created, "startupOwner"));
        assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
        held.incrementAndGet();
        var actual = created.getWALInstance();
        setField(created, "writeAheadLog", mock(WriteAheadLog.class, walCall -> {
          if (Thread.currentThread() != opener
              && (walCall.getMethod().getName().equals("nonActiveSegments")
                  || walCall.getMethod().getName().equals("begin"))) {
            protectedEntry.countDown();
            assertNull(field(DiskStorage.class, created, "startupOwner"));
          }
          return delegatesTo(actual).answer(walCall);
        }));
        var attempted = new CountDownLatch(3);
        var start = new CountDownLatch(1);
        tasks.add(executor.submit(() -> {
          attempted.countDown();
          assertTrue(start.await(5, TimeUnit.SECONDS));
          created.makeFuzzyCheckpoint();
          return null;
        }));
        tasks.add(executor.submit(() -> {
          attempted.countDown();
          assertTrue(start.await(5, TimeUnit.SECONDS));
          var vacuum = AbstractStorage.class.getDeclaredMethod("runWALVacuum");
          vacuum.setAccessible(true);
          vacuum.invoke(created);
          return null;
        }));
        tasks.add(executor.submit(() -> {
          attempted.countDown();
          assertTrue(start.await(5, TimeUnit.SECONDS));
          try {
            created.backup(() -> {
              protectedEntry.countDown();
              return java.util.Collections.emptyIterator();
            }, ignored -> null, ignored -> new java.io.ByteArrayOutputStream(), ignored -> {
            });
          } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException expected) {
            assertTrue(created.getStatus() != AbstractStorage.STATUS.OPEN);
          }
          return null;
        }));
        assertTrue(attempted.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertFalse("No protected entry while the owner is unresolved",
            protectedEntry.await(200, TimeUnit.MILLISECONDS));
        return call.callRealMethod();
      };
      if (boundary.equals("proof")) {
        doAnswer(hold).when(created).verifyStartupHistory();
      } else if (boundary.equals("post-replay")) {
        var barriers = new AtomicInteger();
        doAnswer(call -> barriers.incrementAndGet() == 2 ? hold.answer(call)
            : call.callRealMethod()).when(created).startupHistoryBarrier();
      } else if (boundary.equals("final")) {
        doAnswer(hold).when(created).finishStartupHistory();
      } else {
        doThrow(new Error("abort admission fixture")).when(created).verifyStartupHistory();
        doAnswer(hold).when(created).abortStartupHistory(any());
      }
      try {
        if (boundary.equals("abort")) {
          assertThrows(Error.class, () -> created.open(new ContextConfiguration()));
          assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
        } else {
          created.open(new ContextConfiguration());
        }
        for (var task : tasks) {
          task.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, held.get());
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        created.close(null, true);
        created.preCloseSteps();
      }
    }
  }

  /** Builder, floor and component failures abort every acquired owner before resource close. */
  @Test
  public void ownerFailureMatrixClearsReferencesBeforeResourceCleanup() throws Exception {
    for (var boundary : List.of("builder", "floor", "component")) {
      var created = createObservedStorage("failure-" + boundary);
      seedTrustedHistory(created, false, UUID.randomUUID());
      var primary = new Error(boundary + " failure");
      var aborted = new AtomicInteger();
      doAnswer(call -> {
        if (field(DiskStorage.class, created, "startupOwner") == null) {
          return call.callRealMethod();
        }
        assertTrue(((ScalableRWLock) field(AbstractStorage.class, created, "stateLock"))
            .isWriteLockedByCurrentThread());
        call.callRealMethod();
        assertNull(field(DiskStorage.class, created, "startupOwner"));
        aborted.incrementAndGet();
        return null;
      }).when(created).abortStartupHistory(any());
      doAnswer(call -> {
        assertNull(field(DiskStorage.class, created, "startupOwner"));
        assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
        return call.callRealMethod();
      }).when(created).postCloseSteps(eq(false), eq(true), anyLong());
      if (boundary.equals("builder")) {
        doAnswer(call -> {
          call.callRealMethod();
          throw primary;
        }).when(created).initWalAndDiskCacheOnOpen(any());
      } else if (boundary.equals("floor")) {
        doThrow(primary).when(created).operationIdFloorOnOpen(anyLong());
      } else {
        doThrow(primary).when(created).initConfiguration(any(), any());
      }
      assertSame(primary,
          assertThrows(boundary, Error.class, () -> created.open(new ContextConfiguration())));
      assertEquals(1, aborted.get());
      created.preCloseSteps();
    }
  }

  /** Physical opens reset frozen disk bytes before every warmup and measured sample. */
  @Test
  public void trackerPerformanceStartupOpen() throws Exception {
    TrackerPerformanceProbe.launchStartup(StartupOpenProbe.class,
        List.of("open1CLEAN", "open1UNCLEAN", "open128CLEAN", "open128UNCLEAN", "untrusted1CLEAN"),
        "loadMergeNs", "verifyNs", "fullOpenNs", "openingThreadAllocationBytes",
        "replayPreservationNs", "barrierCalls", "barrierNs", "firstPhaseWriteLockNs",
        "invalidationCalls", "invalidationNs", "deleteCalls", "deleteNs", "folderForceCalls",
        "folderForceNs", "laterBarrierDiskCalls", "msPerScannedMiB");
  }

  public static final class StartupOpenProbe {
    public static void main(String[] args) throws Exception {
      var test = new DiskStorageChangedPageCheckpointTest(StartupPerformanceFixture.root());
      test.folder.create();
      try {
        test.openStorage();
        for (int mib : new int[] {1, 128}) {
          for (boolean dirty : new boolean[] {false, true}) {
            test.measureStartupOpen(mib, dirty, true);
          }
        }
        test.measureStartupOpen(1, false, false);
      } finally {
        test.closeStorage();
        test.folder.delete();
      }
      System.out.println(TrackerPerformanceProbe.COMPLETE);
    }
  }

  private void measureStartupOpen(int mib, boolean dirty, boolean trusted) throws Exception {
    String row = (trusted ? "open" : "untrusted") + mib + (dirty ? "UNCLEAN" : "CLEAN");
    var created = createObservedStorage(row);
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    var image = folder.newFolder().toPath();
    // Close the baseline before writing synthetic incomplete units. Replay must not apply them.
    created.close(null, true);
    StartupPerformanceFixture fixture;
    try (var writer =
        new com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog(
            row, directory, directory, ContextConfiguration.WAL_DEFAULT_NAME, 100, 64, null, null,
            Integer.MAX_VALUE, Integer.MAX_VALUE, 20, true, java.util.Locale.US, -1, 1000,
            false, true, false, 10)) {
      writer.log(new AtomicUnitStartRecord(true, 991));
      fixture = new StartupPerformanceFixture(writer, directory, mib, false);
    }
    var path = directory.resolve(ChangedPageTrackerFile.FILE_NAME);
    fixture.save(path, trusted, 1);
    if (dirty) {
      var metadata = new StorageStartupMetadata(directory.resolve("dirty.fl"),
          directory.resolve("dirty.flb"));
      metadata.open(com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants.getRawVersion());
      metadata
          .makeDirty(com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants.getRawVersion());
      metadata.close();
    }
    copyStartupImage(directory, image);
    String label = fixture.label(row, 1, dirty ? "UNCLEAN" : "CLEAN",
        fixture.positions.size() + fixture.inventory.stream()
            .filter(s -> s.segment() > fixture.coverage.getSegment()).count());
    if (!trusted) {
      label = label.replace("PROVED_HISTORY_TRUSTED", "PROVED_HISTORY_UNTRUSTED");
    }
    label += " observers=Mockito-including-nested-static-calls+decoded-record-counter"
        + " timingScope=open-only";
    var series = new TrackerPerformanceProbe.Series(label, "loadMergeNs", "verifyNs", "fullOpenNs",
        "openingThreadAllocationBytes", "replayPreservationNs", "barrierCalls", "barrierNs",
        "firstPhaseWriteLockNs", "invalidationCalls", "invalidationNs", "deleteCalls", "deleteNs",
        "folderForceCalls", "folderForceNs", "laterBarrierDiskCalls", "msPerScannedMiB");
    try {
      for (int sample = -3; sample < TrackerPerformanceProbe.samples(); sample++) {
        copyStartupImage(image, directory);
        assertEquals(fixture.inventory, StartupPerformanceFixture.inventory(directory));
        long[] m = new long[16];
        long[] decoded = {0};
        boolean[] collecting = {false};
        int[] loadCalls = {0};
        doAnswer(call -> {
          call.callRealMethod();
          setField(created, "writeAheadLog",
              StartupPerformanceFixture.countDecoded(created.getWALInstance(), decoded));
          return null;
        }).when(created).initWalAndDiskCacheOnOpen(any());
        long[] replay = {0};
        long[] held = {0};
        int[] writeDepth = {0};
        int[] barrier = {0};
        boolean[] invalidating = {false};
        var pendingHistory = new ChangedPageTrackerStartup.PendingHistory[1];
        var proofResult = new ChangedPageTrackerStartup.StartupResult[1];
        var shutdown = new ChangedPageTrackerStartup.ShutdownState[1];
        var extent = new WriteAheadLog.PreOpenExtent[1];
        var actualLock = (ScalableRWLock) field(AbstractStorage.class, created, "stateLock");
        var writeLock = mock(java.util.concurrent.locks.Lock.class, call -> {
          if (call.getMethod().getName().equals("lock")) {
            actualLock.writeLock().lock();
            if (writeDepth[0]++ == 0) {
              held[0] = System.nanoTime();
            }
            return null;
          }
          if (call.getMethod().getName().equals("unlock")) {
            if (--writeDepth[0] == 0 && m[7] == 0) {
              m[7] = System.nanoTime() - held[0];
            }
            actualLock.writeLock().unlock();
            return null;
          }
          return delegatesTo(actualLock.writeLock()).answer(call);
        });
        var observedLock = mock(ScalableRWLock.class, delegatesTo(actualLock));
        doReturn(writeLock).when(observedLock).writeLock();
        setField(created, "stateLock", observedLock);
        doAnswer(call -> {
          long start = System.nanoTime();
          barrier[0]++;
          m[5]++;
          try {
            return call.callRealMethod();
          } finally {
            m[6] += System.nanoTime() - start;
          }
        }).when(created).startupHistoryBarrier();
        doAnswer(call -> {
          call.callRealMethod();
          replay[0] = System.nanoTime();
          return null;
        }).when(created).beginStartupReplay();
        doAnswer(call -> {
          call.callRealMethod();
          if (replay[0] != 0) {
            m[4] += System.nanoTime() - replay[0];
            replay[0] = 0;
          }
          return null;
        }).when(created).endStartupReplay();
        try (var filesystem =
            com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerCheckpointTest
                .observeFilesystem(call -> {
                  String name = call.getMethod().getName();
                  int index = name.equals("delete") && path.equals(call.getArgument(0)) ? 10
                      : name.equals("forceFolder") && directory.equals(call.getArgument(0)) ? 12
                          : -1;
                  long start = System.nanoTime();
                  var result = call.callRealMethod();
                  if (index >= 0 && invalidating[0]) {
                    m[index]++;
                    m[index + 1] += System.nanoTime() - start;
                    if (barrier[0] > 1) {
                      m[14]++;
                    }
                  }
                  return result;
                });
            var startup = mockStatic(ChangedPageTrackerStartup.class, call -> {
              if (!collecting[0]) {
                return call.callRealMethod();
              }
              String name = call.getMethod().getName();
              int index = name.equals("load") ? 0 : name.equals("verify") ? 1
                  : name.equals("durableDistrust") && call.getArguments().length == 2 ? 9 : -1;
              if (index == 0) {
                loadCalls[0]++;
              }
              long start = System.nanoTime();
              if (index == 9) {
                invalidating[0] = true;
              }
              Object result;
              try {
                result = call.callRealMethod();
              } finally {
                if (index == 9) {
                  invalidating[0] = false;
                }
              }
              if (index >= 0) {
                m[index] += System.nanoTime() - start;
              }
              if (index == 9) {
                m[8]++;
              }
              if (index == 1) {
                pendingHistory[0] = call.getArgument(0);
                proofResult[0] = (ChangedPageTrackerStartup.StartupResult) result;
                extent[0] = ((WriteAheadLog) call.getArgument(1)).preOpenExtent();
                shutdown[0] = call.getArgument(2);
              }
              return result;
            })) {
          assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
          long before = TrackerPerformanceProbe.allocated();
          long start = System.nanoTime();
          collecting[0] = true;
          try {
            created.open(new ContextConfiguration());
          } finally {
            collecting[0] = false;
          }
          m[2] = System.nanoTime() - start;
          m[3] = TrackerPerformanceProbe.allocated() - before;
          assertTrue(m[0] > 0 && m[1] > 0 && m[7] > 0);
          assertEquals(fixture.inventory, extent[0].segments());
          assertEquals(dirty ? ChangedPageTrackerStartup.ShutdownState.UNCLEAN
              : ChangedPageTrackerStartup.ShutdownState.CLEAN, shutdown[0]);
          assertEquals(trusted, proofResult[0].historyTrusted());
          assertTrue(proofResult[0].coverageProved());
          assertEquals(WriteAheadLog.ProofStatus.REACHED_END, proofResult[0].terminalStatus());
          fixture.assertMarks(pendingHistory[0].tracker(), 1);
          assertEquals(fixture.coverage, pendingHistory[0].coverageLsn());
          if (!trusted) {
            assertInvalidatedAuthority(path);
          }
          assertEquals("Only the open-interval load is measured", 1, loadCalls[0]);
          assertEquals(fixture.positions.size() + fixture.inventory.stream()
              .filter(s -> s.segment() > fixture.coverage.getSegment()).count(), decoded[0]);
          assertTrue(m[5] >= 2);
          assertEquals(dirty, m[4] > 0);
          assertEquals(trusted ? 0 : 1, m[10]);
          assertEquals(trusted ? 0 : 1, m[12]);
          assertEquals(0, m[14]);
          assertEquals(trusted, ((ChangedPageTracker) field(DiskStorage.class, created,
              "changedPageTracker")).isTrusted());
          if (sample >= 0) {
            double[] values = new double[16];
            for (int i = 0; i < 15; i++) {
              values[i] = m[i];
            }
            values[15] = m[1] / 1e6 / (fixture.suffixBytes / 1048576.0);
            series.add(values);
          }
        } finally {
          setField(created, "stateLock", actualLock);
          created.close(null, true);
        }
      }
      series.report();
    } finally {
      created.preCloseSteps();
    }
  }

  // Files.copy streams through the filesystem. No whole-file Java byte arrays are retained.
  private static void copyStartupImage(Path source, Path target) throws Exception {
    try (var files = Files.list(target)) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        Files.delete(file);
      }
    }
    try (var files = Files.list(source)) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        Files.copy(file, target.resolve(file.getFileName()));
      }
    }
  }

  private static void saveHistory(Path path, ChangedPageTracker saved, LogSequenceNumber coverage)
      throws Exception {
    var save = ChangedPageTrackerFile.class.getDeclaredMethod("save", Path.class,
        ChangedPageTracker.class, LogSequenceNumber.class);
    save.setAccessible(true);
    save.invoke(null, path, saved, coverage);
  }

  private void injectReplayFailure(DiskStorage created, Throwable failure) throws Exception {
    doAnswer(call -> {
      call.callRealMethod();
      var actual = created.getWALInstance();
      var observed = mock(WriteAheadLog.class, delegatesTo(actual));
      doAnswer(read -> {
        assertTrue("Replay starts only after loaded history is proved",
            ((ChangedPageTracker) field(DiskStorage.class, created, "changedPageTracker"))
                .isTrusted());
        throw failure;
      }).when(observed).read(any(), anyInt());
      setField(created, "writeAheadLog", observed);
      return null;
    }).when(created).initWalAndDiskCacheOnOpen(any());
  }

  private void seedTrustedHistory(DiskStorage created, boolean dirty, UUID completed)
      throws Exception {
    seedTrustedHistory(created, dirty, completed, false);
  }

  private void seedTrustedHistory(DiskStorage created, boolean dirty, UUID completed,
      boolean committedReplay) throws Exception {
    seedTrustedHistory(created, dirty, completed, committedReplay, null);
  }

  @FunctionalInterface
  private interface StartupImageAction {
    void run(DiskStorage created, LogSequenceNumber coverage) throws Exception;
  }

  private void seedTrustedHistory(DiskStorage created, boolean dirty, UUID completed,
      boolean committedReplay, StartupImageAction beforeImage) throws Exception {
    var actual = created.getWALInstance();
    var coverage = actual.log(new AtomicUnitStartRecord(true, 123));
    var external = created.getWriteCache().externalFileId(123457);
    actual.log(new UpdatePageRecord(31, external, 123, new WALPageChangesPortion(), coverage));
    actual.log(new FileCreatedWALRecord(123, "incomplete.bin", external));
    actual.log(new UpdatePageRecord(33, external, 123, new WALPageChangesPortion(), coverage));
    // Deliberately omit the unit end. Proof must retain pages that redo will not apply.
    if (committedReplay) {
      var start = actual.log(new AtomicUnitStartRecord(true, 124));
      var committedFile = created.getWriteCache().externalFileId(123456);
      actual.log(new FileCreatedWALRecord(124, "committed.bin", committedFile));
      actual.log(new UpdatePageRecord(0, committedFile, 124, new WALPageChangesPortion(), start));
      actual.log(new AtomicUnitEndRecord(124, false, null));
    }
    actual.flush();
    actual.addCutTillLimit(coverage);
    var metadata = (StorageStartupMetadata) field(DiskStorage.class, created, "startupMetadata");
    metadata.makeDirty(com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants.getRawVersion());
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    var saved = trustedSavedHistory(created, completed, beforeImage != null);
    saveHistory(directory.resolve(ChangedPageTrackerFile.FILE_NAME), saved, coverage);
    try {
      if (beforeImage != null) {
        beforeImage.run(created, coverage);
      }
    } catch (Exception | Error failure) {
      actual.removeCutTillLimit(coverage);
      throw failure;
    }
    actual.flush();
    // Drain asynchronous file initialization as well as periodic page writes before copying.
    created.getWriteCache().pauseBackgroundFlush();
    var image = new java.util.HashMap<Path, byte[]>();
    var lock = (ScalableRWLock) field(AbstractStorage.class, created, "stateLock");
    lock.writeLock().lock();
    try {
      if (dirty) {
        // Copy this small fixture while all storage writes are excluded. Restore the entire image,
        // not just its dirty flag, so recovery consumes the exact pre-close WAL and registry.
        try (var paths = Files.list(directory)) {
          for (var path : paths.filter(Files::isRegularFile).toList()) {
            image.put(path, Files.readAllBytes(path));
          }
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
    try {
      created.close(null, true);
    } finally {
      actual.removeCutTillLimit(coverage);
    }
    if (dirty) {
      try (var paths = Files.list(directory)) {
        for (var path : paths.filter(Files::isRegularFile).toList()) {
          Files.delete(path);
        }
      }
      for (var entry : image.entrySet()) {
        Files.write(entry.getKey(), entry.getValue());
      }
    }
    saveHistory(directory.resolve(ChangedPageTrackerFile.FILE_NAME), saved, coverage);
  }

  private static ChangedPageTracker trustedSavedHistory(DiskStorage created, UUID completed,
      boolean includeBaseline) throws Exception {
    var restore = ChangedPageTracker.class.getDeclaredMethod("restoration", UUID.class,
        UUID.class, boolean.class, UUID.class);
    restore.setAccessible(true);
    var builder = restore.invoke(null, UUID.randomUUID(), completed, true, UUID.randomUUID());
    for (var name : List.of("activeWord", "sealedWord")) {
      var word = builder.getClass().getDeclaredMethod(name, int.class, long.class, long.class);
      word.setAccessible(true);
      word.invoke(builder, 123456, 0L, name.equals("activeWord") ? 1L << 7 : 1L << 9);
    }
    if (includeBaseline) {
      // Conservatively cover every baseline page. No pre-coverage change is omitted from history.
      var cache = created.getWriteCache();
      var word = builder.getClass().getDeclaredMethod("activeWord", int.class, long.class,
          long.class);
      word.setAccessible(true);
      for (var id : cache.files().values()) {
        long pages = cache.getFilledUpTo(id);
        for (long first = 0; first < pages; first += 64) {
          int count = (int) Math.min(64, pages - first);
          word.invoke(builder, cache.internalFileId(id), first / 64,
              count == 64 ? -1L : (1L << count) - 1);
        }
      }
    }
    var build = builder.getClass().getDeclaredMethod("build");
    build.setAccessible(true);
    return (ChangedPageTracker) build.invoke(builder);
  }

  private static List<Long> candidates(ChangedPageTracker selected, int id) throws Exception {
    var active = field(ChangedPageTracker.class, selected, "active");
    var collect = active.getClass().getDeclaredMethod("forEachCandidate", int.class,
        java.util.function.LongConsumer.class);
    collect.setAccessible(true);
    var result = new ArrayList<Long>();
    collect.invoke(active, id, (java.util.function.LongConsumer) result::add);
    return result;
  }

  /** Real disk creation uses only the create initializer and shares one untrusted tracker. */
  @Test
  public void creationSelectsCreateInitializerAndSharesTracker() throws Exception {
    var created = createObservedStorage();
    verify(created).initWalAndDiskCache(any(ContextConfiguration.class));
    verify(created, never()).initWalAndDiskCacheOnOpen(any(ContextConfiguration.class));
    assertSharedUntrustedTracker(created);
  }

  /** An existing image without saved authority shares its loaded untrusted tracker. */
  @Test
  public void existingImageSelectsOpenInitializerAndSharesTracker() throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    clearInvocations(created);
    created.open(new ContextConfiguration());
    verify(created).initWalAndDiskCacheOnOpen(any(ContextConfiguration.class));
    verify(created, never()).initWalAndDiskCache(any(ContextConfiguration.class));
    assertSharedUntrustedTracker(created);
  }

  /** Each physical open without trusted authority replaces both selected references together. */
  @Test
  public void repeatedPhysicalOpenReplacesSharedTracker() throws Exception {
    var created = createObservedStorage();
    var previous = assertSharedUntrustedTracker(created);
    for (var attempt = 0; attempt < 2; attempt++) {
      created.close(null, true);
      clearInvocations(created);
      created.open(new ContextConfiguration());
      verify(created).initWalAndDiskCacheOnOpen(any(ContextConfiguration.class));
      verify(created, never()).initWalAndDiskCache(any(ContextConfiguration.class));
      var current = assertSharedUntrustedTracker(created);
      assertNotSame(previous, current);
      previous = current;
    }
  }

  /** A real create failure at the cleanup barrier cannot write dirty.fl/configuration or OPEN. */
  @Test
  public void creationFailurePreventsDirtyFlagConfigurationAndOpenPublication() throws Exception {
    var directory = folder.getRoot().toPath().resolve("failedCreation");
    var created = mock(DiskStorage.class, withSettings().useConstructor("failedCreation",
        directory.toString(), 4321, storage.getReadCache(),
        field(DiskStorage.class, storage, "files"),
        field(DiskStorage.class, storage, "walMaxSegSize"),
        field(DiskStorage.class, storage, "doubleWriteLogMaxSegSize"), manager.internal)
        .defaultAnswer(CALLS_REAL_METHODS));
    var metadata = mock(StorageStartupMetadata.class);
    var metadataField = DiskStorage.class.getDeclaredField("startupMetadata");
    metadataField.setAccessible(true);
    metadataField.set(created, metadata);
    var failure = new IOException("injected cleanup folder force failure");
    try (var cleanup = mockStatic(ChangedPageTrackerFile.class,
        call -> call.getMethod().getName().equals("cleanupForCreation")
            && call.getArguments().length == 1
                ? null : call.callRealMethod())) {
      cleanup.when(() -> ChangedPageTrackerFile.cleanupForCreation(directory)).thenAnswer(call -> {
        // Observe before any factory deletion can hide ordering. Birth, IV, WAL and cache already
        // exist, but startup metadata and storage configuration must still be untouched.
        assertNotNull(created.getWALInstance());
        assertNotNull(created.getWriteCache());
        assertTrue(Files.exists(directory.resolve("data.iv")));
        assertTrue(DiskStorage.exists(directory));
        verifyNoInteractions(metadata);
        assertNull(field(AbstractStorage.class, created, "configuration"));
        throw failure;
      });
      assertThrows(RuntimeException.class, () -> created.create(new ContextConfiguration()));
      cleanup.verify(() -> ChangedPageTrackerFile.cleanupForCreation(directory));
      verifyNoInteractions(metadata);
      verify(created, never()).makeStorageDirty();
      assertTrue(created.isClosed(null));
      assertNull(field(AbstractStorage.class, created, "configuration"));
      assertFalse(Files.exists(directory.resolve("dirty.fl")));
      assertFalse(Files.exists(directory.resolve("database.ocf")));
      try (var paths = Files.list(directory)) {
        assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("config")));
      }
    } finally {
      // CLOSED lifecycle shutdown returns early. Release the initialized resources explicitly.
      created.preCloseSteps();
      try {
        if (created.getWriteCache() != null) {
          created.getReadCache().closeStorage(created.getWriteCache());
        }
      } finally {
        if (created.getWALInstance() != null) {
          created.getWALInstance().close();
        }
      }
    }
  }

  /** A failed cache close keeps the metadata lock and primary Error, but still closes the WAL. */
  @Test
  public void openErrorSurvivesReadCacheCloseErrorAndRunsLaterCleanup() throws Exception {
    assertOpenFailure(new Error("open failure"), 0, new Error("read-cache close failure"), null);
  }

  /** A WAL close Error stays suppressed and cannot prevent the later metadata close. */
  @Test
  public void openErrorSurvivesWalCloseErrorAndRunsMetadataCleanup() throws Exception {
    assertOpenFailure(new Error("open failure"), 1, new Error("WAL close failure"), null);
  }

  /** A post-close Error stays suppressed and storage always reaches CLOSED. */
  @Test
  public void openErrorSurvivesPostCloseErrorAndReachesClosed() throws Exception {
    assertOpenFailure(new Error("open failure"), 2, new Error("post-close failure"), null);
  }

  /** Diagnostics cannot replace an Error after the real disk open cleanup completes. */
  @Test
  public void openErrorSurvivesDiagnosticError() throws Exception {
    assertOpenFailure(new Error("open failure"), -1, null, new Error("diagnostic failure"));
  }

  /** An Error after MIGRATION must reset storage to CLOSED and retain the original failure. */
  @Test
  public void openErrorDuringMigrationResetsStatusToClosed() throws Exception {
    assertOpenFailure(new Error("migration failure"), -1, null, null, true);
  }

  /** Runtime open failures retain their identity when diagnostic reporting throws an Error. */
  @Test
  public void runtimeOpenFailureSurvivesDiagnosticError() throws Exception {
    assertOpenFailure(new IllegalStateException("open failure"), -1, null,
        new Error("diagnostic failure"));
  }

  /** Checked open failures remain the exact cause of a runtime wrapper if diagnostics fail. */
  @Test
  public void checkedOpenFailureSurvivesDiagnosticErrorAsExactCause() throws Exception {
    assertOpenFailure(new IOException("open failure"), -1, null, new Error("diagnostic failure"));
  }

  /** Reusing the primary in cleanup and diagnostics must not attempt self-suppression. */
  @Test
  public void openFailureSurvivesSameFailureFromCleanupAndDiagnostics() throws Exception {
    var failure = new Error("shared failure");
    assertOpenFailure(failure, 0, failure, failure);
  }

  /** Rejected metadata admission must not create an IV or touch earlier cache and WAL fields. */
  @Test
  public void rejectedMetadataLeavesFilesAndEarlierResourcesUntouched() throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var oldCache = mock(com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class);
    var oldWal = mock(WriteAheadLog.class);
    setField(created, "writeCache", oldCache);
    setField(created, "writeAheadLog", oldWal);
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    Files.delete(directory.resolve("data.iv"));
    var before = new java.util.HashMap<Path, byte[]>();
    doAnswer(call -> {
      call.callRealMethod();
      // Identity admission can publish bootstrap authority before metadata admission.
      // Snapshot after that unchanged step to isolate IV, WAL, cache and tracker effects.
      try (var paths = Files.list(directory)) {
        for (var path : paths.toList()) {
          if (!path.getFileName().toString().startsWith("dirty.fl")) {
            before.put(path, Files.readAllBytes(path));
          }
        }
      }
      return null;
    }).when(created).readStorageIdentity();
    try (var channel = FileChannel.open(directory.resolve("dirty.fl"), StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      assertThrows(RuntimeException.class, () -> created.open(new ContextConfiguration()));
      assertFalse(Files.exists(directory.resolve("data.iv")));
      verifyNoInteractions(oldCache, oldWal);
      verify(created, never()).postCloseSteps(eq(false), eq(true), anyLong());
      var after = new java.util.HashSet<Path>();
      try (var paths = Files.list(directory)) {
        paths.filter(path -> !path.getFileName().toString().startsWith("dirty.fl"))
            .forEach(after::add);
      }
      assertEquals(before.keySet(), after);
      for (var entry : before.entrySet()) {
        org.junit.Assert.assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
      }
      assertTrue(created.isClosed(null));
    }
  }

  /** An Error after metadata locking releases this attempt's lock and keeps its identity. */
  @Test
  public void metadataReadErrorReleasesAcquiredLock() throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    var failure = new OutOfMemoryError("metadata read failure");
    var previous = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.FILE_LOCK.setValue(true);
    try {
      try (var reads = mockStatic(IOUtils.class, CALLS_REAL_METHODS)) {
        reads.when(() -> IOUtils.readByteBuffer(
            any(ByteBuffer.class), any(FileChannel.class), eq(0L), eq(true)))
            .thenThrow(failure);
        assertSame(failure, assertThrows(OutOfMemoryError.class,
            () -> created.open(new ContextConfiguration())));
      }
      assertTrue(created.isClosed(null));
      try (var channel = FileChannel.open(directory.resolve("dirty.fl"),
          StandardOpenOption.WRITE); var lock = channel.tryLock()) {
        assertNotNull(lock);
      }
      verify(created, never()).postCloseSteps(eq(false), eq(true), anyLong());
    } finally {
      ((StorageStartupMetadata) field(DiskStorage.class, created, "startupMetadata")).close();
      GlobalConfiguration.FILE_LOCK.setValue(previous);
    }
  }

  /** Failed production double-write log open releases metadata without deleting recovery bytes. */
  @Test
  public void doubleWriteOpenFailureBeforeChannelReleasesMetadataAndPreservesSegments()
      throws Exception {
    assertDoubleWriteOpenFailure(false);
  }

  /** A failure after log channel assignment preserves recovery bytes and releases metadata. */
  @Test
  public void doubleWriteOpenFailureAfterChannelReleasesMetadataAndPreservesSegments()
      throws Exception {
    assertDoubleWriteOpenFailure(true);
  }

  private void assertDoubleWriteOpenFailure(boolean afterChannel) throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    var segment = directory.resolve(ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME
        + (afterChannel ? "_0.dwl" : "_bad.dwl"));
    byte[] bytes = {1, 4, 9, 16};
    Files.write(segment, bytes);
    var previous = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.FILE_LOCK.setValue(true);
    var readCache = created.getReadCache();
    try {
      if (afterChannel) {
        try (var blocks = mockStatic(IOUtils.class, CALLS_REAL_METHODS)) {
          blocks.when(() -> IOUtils.calculateBlockSize(any(Path.class)))
              .thenThrow(new Error("after double-write channel assignment"));
          assertThrows(Error.class, () -> created.open(new ContextConfiguration()));
        }
      } else {
        assertThrows(RuntimeException.class, () -> created.open(new ContextConfiguration()));
      }
      org.junit.Assert.assertArrayEquals(bytes, Files.readAllBytes(segment));
      try (var channel = FileChannel.open(directory.resolve("dirty.fl"),
          StandardOpenOption.WRITE); var lock = channel.tryLock()) {
        assertNotNull(lock);
      }
      verify(created).postCloseSteps(eq(false), eq(true), anyLong());
    } finally {
      created.preCloseSteps();
      if (created.getWriteCache() != null) {
        readCache.closeStorage(created.getWriteCache());
      }
      ((StorageStartupMetadata) field(DiskStorage.class, created, "startupMetadata")).close();
      Files.deleteIfExists(segment);
      GlobalConfiguration.FILE_LOCK.setValue(previous);
    }
  }

  /** A registry-load failure must close the constructed cache before releasing metadata. */
  @Test
  public void registryLoadFailureClosesUnpublishedCacheBeforeMetadata() throws Exception {
    assertRegistryLoadFailure(false);
  }

  /** Failed partial-cache close must retain metadata while the WAL still closes. */
  @Test
  public void registryLoadFailureRetainsMetadataWhenPartialCacheCloseFails() throws Exception {
    assertRegistryLoadFailure(true);
  }

  private void assertRegistryLoadFailure(boolean failClose) throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    var registry = directory.resolve("name_id_map_v3.cm");
    Files.delete(registry);
    Files.createDirectory(registry);
    var steps = new ArrayList<String>();
    var earlierCache = created.getWriteCache();
    var actualReadCache = created.getReadCache();
    var partialCache = new WOWCache[1];
    var closeFailure = new Error("partial cache close failure");
    var observed = mock(ReadCache.class, delegatesTo(actualReadCache));
    doAnswer(call -> {
      steps.add("cache");
      assertNull(field(DiskStorage.class, created, "startupOwner"));
      assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
      var cache = (WOWCache) call.getArgument(0);
      assertNotSame(earlierCache, cache);
      partialCache[0] = cache;
      if (failClose) {
        throw closeFailure;
      }
      actualReadCache.closeStorage(cache);
      assertTrue((boolean) field(WOWCache.class, cache, "stopFlush"));
      return null;
    }).when(observed).closeStorage(any());
    setField(created, "readCache", observed);
    doAnswer(call -> {
      steps.add("metadata");
      return call.callRealMethod();
    }).when(created).postCloseSteps(eq(false), eq(true), anyLong());
    try {
      var failure = assertThrows(RuntimeException.class,
          () -> created.open(new ContextConfiguration()));
      assertEquals(failClose ? List.of("cache") : List.of("cache", "metadata"), steps);
      if (failClose) {
        assertTrue(Arrays.asList(failure.getCause().getSuppressed()).contains(closeFailure));
        try (var channel = FileChannel.open(directory.resolve("dirty.fl"),
            StandardOpenOption.WRITE)) {
          assertThrows(OverlappingFileLockException.class, channel::tryLock);
        }
      }
      assertTrue(created.isClosed(null));
    } finally {
      created.preCloseSteps();
      if (partialCache[0] != null) {
        actualReadCache.closeStorage(partialCache[0]);
      }
      ((StorageStartupMetadata) field(DiskStorage.class, created, "startupMetadata")).close();
      Files.delete(registry);
    }
  }

  private void assertOpenFailure(Throwable primary, int failedStep, Error cleanupFailure,
      Error diagnosticFailure) throws Exception {
    assertOpenFailure(primary, failedStep, cleanupFailure, diagnosticFailure, false);
  }

  private void assertOpenFailure(Throwable primary, int failedStep, Error cleanupFailure,
      Error diagnosticFailure, boolean failDuringMigration) throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var steps = new ArrayList<String>();
    var closed = new boolean[3];
    var actualReadCache = created.getReadCache();
    var actualWal = new WriteAheadLog[1];
    var reachedMigration = new boolean[1];
    // Wrap only resources acquired by this attempt, after real WAL and cache initialization.
    doAnswer(call -> {
      call.callRealMethod();
      actualWal[0] = created.getWALInstance();
      var observedReadCache = mock(ReadCache.class, delegatesTo(actualReadCache));
      doAnswer(close -> {
        steps.add("read-cache");
        if (failedStep == 0) {
          // Leave the real writers alive to prove that releasing the metadata lock is unsafe.
          throw cleanupFailure;
        }
        actualReadCache.closeStorage(created.getWriteCache());
        closed[0] = true;
        return null;
      }).when(observedReadCache).closeStorage(any());
      var observedWal = mock(WriteAheadLog.class, delegatesTo(actualWal[0]));
      doAnswer(close -> {
        steps.add("WAL");
        actualWal[0].close();
        closed[1] = true;
        if (failedStep == 1) {
          throw cleanupFailure;
        }
        return null;
      }).when(observedWal).close();
      setField(created, "readCache", observedReadCache);
      setField(created, "writeAheadLog", observedWal);
      if (!failDuringMigration) {
        throw primary;
      }
      return null;
    }).when(created).initWalAndDiskCacheOnOpen(any(ContextConfiguration.class));
    doAnswer(call -> {
      steps.add("metadata");
      call.callRealMethod();
      closed[2] = true;
      if (failedStep == 2) {
        throw cleanupFailure;
      }
      return null;
    }).when(created).postCloseSteps(eq(false), eq(true), anyLong());
    var actualLogger = LogManager.instance();
    var observedLogger = mock(LogManager.class, call -> {
      if (call.getMethod().getName().equals("error") && call.getArguments().length > 2
          && call.getArgument(2) == primary) {
        steps.add("diagnostics");
        if (diagnosticFailure != null) {
          throw diagnosticFailure;
        }
      }
      return delegatesTo(actualLogger).answer(call);
    });
    try (var components = mockStatic(LinkCollectionsBTreeManagerShared.class, call -> {
      if (failDuringMigration && call.getMethod().getName().equals("isComponentPresent")
          && created.getStatus() == AbstractStorage.STATUS.MIGRATION) {
        reachedMigration[0] = true;
        throw primary;
      }
      return call.callRealMethod();
    }); var logging = mockStatic(LogManager.class)) {
      logging.when(LogManager::instance).thenReturn(observedLogger);
      var thrown = assertThrows(Throwable.class,
          () -> created.open(new ContextConfiguration()));
      if (primary instanceof IOException) {
        assertEquals(RuntimeException.class, thrown.getClass());
      }
      assertSame(primary, primary instanceof IOException ? thrown.getCause() : thrown);
      var suppressed = new ArrayList<Throwable>();
      if (cleanupFailure != null && cleanupFailure != primary) {
        suppressed.add(cleanupFailure);
      }
      if (diagnosticFailure != null && diagnosticFailure != primary) {
        suppressed.add(diagnosticFailure);
      }
      assertEquals(suppressed, Arrays.asList(primary.getSuppressed()));
      assertEquals(failDuringMigration, reachedMigration[0]);
      assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
      if (failedStep == 0) {
        var directory = (Path) field(DiskStorage.class, created, "storagePath");
        try (var channel = FileChannel.open(directory.resolve("dirty.fl"),
            StandardOpenOption.WRITE)) {
          assertThrows(OverlappingFileLockException.class, channel::tryLock);
        }
        assertEquals(List.of("read-cache", "WAL", "diagnostics"), steps);
      } else {
        assertEquals(List.of("read-cache", "WAL", "metadata", "diagnostics"), steps);
      }
    } finally {
      // Prevent manager teardown from repeating this fixture's explicit resource cleanup.
      setField(created, "status", AbstractStorage.STATUS.CLOSED);
      // CLOSED shutdown skips periodic-task cancellation. Do not retain this failed storage.
      for (var name : List.of("fuzzyCheckpointTask", "recordsGcTask")) {
        var task = (Future<?>) field(DiskStorage.class, created, name);
        if (task != null) {
          task.cancel(false);
          assertTrue(task.isCancelled());
        }
      }
      // Bypass fault-injecting wrappers and always release real resources, including in red runs.
      try {
        if (!closed[0]) {
          actualReadCache.closeStorage(created.getWriteCache());
        }
      } finally {
        try {
          if (!closed[1] && actualWal[0] != null) {
            actualWal[0].close();
          }
        } finally {
          if (!closed[2]) {
            ((StorageStartupMetadata) field(DiskStorage.class, created, "startupMetadata")).close();
          }
        }
      }
    }
  }

  private static void setField(AbstractStorage target, String name, Object value) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private DiskStorage createObservedStorage() throws Exception {
    return createObservedStorage("startup");
  }

  private DiskStorage createObservedStorage(String name) throws Exception {
    // Replace only this manager's factory. The shared engine and other managers stay untouched.
    var engineField = manager.internal.getClass().getDeclaredField("disk");
    engineField.setAccessible(true);
    var realEngine = (Engine) engineField.get(manager.internal);
    var engine = spy(realEngine);
    doAnswer(call -> {
      var real = (DiskStorage) call.callRealMethod();
      // Construct the mock itself so storage components capture it, not a copied spy source.
      return mock(DiskStorage.class, withSettings().useConstructor(real.getName(),
          field(DiskStorage.class, real, "storagePath").toString(), call.getArgument(3),
          real.getReadCache(), field(DiskStorage.class, real, "files"), call.getArgument(1),
          call.getArgument(2), call.getArgument(4)).defaultAnswer(CALLS_REAL_METHODS));
    }).when(engine).createStorage(anyString(), anyLong(), anyLong(), anyInt(), any());
    engineField.set(manager.internal, engine);
    try {
      manager.create(name, DatabaseType.DISK, "admin", "admin", "admin");
      try (var opened = manager.open(name, "admin", "admin")) {
        return (DiskStorage) opened.getStorage();
      }
    } finally {
      engineField.set(manager.internal, realEngine);
    }
  }

  private static ChangedPageTracker assertSharedUntrustedTracker(DiskStorage target)
      throws Exception {
    var selected = (ChangedPageTracker) field(DiskStorage.class, target, "changedPageTracker");
    assertNotNull(selected);
    assertSame(selected, field(WOWCache.class, target.getWriteCache(), "changedPageTracker"));
    var trusted = ChangedPageTracker.class.getDeclaredMethod("isTrusted");
    trusted.setAccessible(true);
    assertFalse((boolean) trusted.invoke(selected));
    return selected;
  }

  /** The real publisher completes before cutting, and cache file events invalidate the same state. */
  @Test
  public void publicationPrecedesBoundedCutAndUsesWriteCacheTracker() throws Exception {
    assertSame(tracker, field(WOWCache.class, storage.getWriteCache(), "changedPageTracker"));
    doAnswer(call -> {
      assertTrue(stateLock.isReadLockedByCurrentThread());
      assertPublishedCoverage();
      return true;
    }).when(wal).cutAllSegmentsSmallerThan(2);

    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    var order = inOrder(wal);
    order.verify(wal).preflightCut(3);
    order.verify(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(NO_SAVE, true), checkpoint());

    // WOWCache owns this file-identity change. Reusing another tracker would miss it.
    storage.getWriteCache().addFile("bridge-event.bin");
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
  }

  /** Both inventory availability failures report a retryable result without writing or cutting. */
  @Test
  public void unavailablePreflightLeavesNoSideFileOrCutAndLaterCallRetries() throws Exception {
    for (var failure : List.of(new IllegalStateException("closed inventory"),
        new NoSuchElementException("changed inventory"))) {
      Files.deleteIfExists(sideFile);
      doThrow(failure).when(wal).preflightCut(3);
      assertEquals(new CheckpointResult(PREFLIGHT_UNAVAILABLE, false), checkpoint());
      assertFalse(Files.exists(sideFile));
      assertFalse(
          Files.exists(sideFile.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME)));
      verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
    }
    doReturn(new CutPreflight(true, 2, COVERAGE)).when(wal).preflightCut(3);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** A non-empty authority directory fails publication and invalidation, so no cut is permitted. */
  @Test
  public void failedDurableInvalidationReportsIOExceptionAndRetryCanPublish() throws Exception {
    Files.createDirectory(sideFile);
    var blocker = sideFile.resolve("blocker");
    Files.writeString(blocker, "do not remove");

    var failure = assertThrows(IOException.class, this::checkpoint);
    assertTrue(failure.getMessage().contains("side-file invalidation failed"));
    assertTrue(Files.exists(blocker));
    verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());

    Files.delete(blocker);
    Files.delete(sideFile);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** Cancellation can invalidate a failed save, but the bounded cutter never inherits its flag. */
  @Test
  public void interruptedPublicationPreservesFlagAndLaterCheckpointRetries() throws Exception {
    doAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      assertFalse(Files.exists(sideFile));
      return true;
    }).when(wal).cutAllSegmentsSmallerThan(2);
    Thread.currentThread().interrupt();
    try {
      assertEquals(new CheckpointResult(INVALIDATED, true), checkpoint());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    doReturn(true).when(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** A cut error follows publication without rollback, preserves interruption, and allows retry. */
  @Test
  public void cutterFailureKeepsPublicationAndInterruptStatusForCaller() throws Exception {
    assertEquals(SAVED, checkpoint().outcome());
    var failure = new IOException("cut failed");
    doAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      assertPublishedCoverage();
      throw failure;
    }).when(wal).cutAllSegmentsSmallerThan(2);
    Thread.currentThread().interrupt();
    try {
      assertSame(failure, assertThrows(IOException.class, this::checkpoint));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertPublishedCoverage();
    doReturn(true).when(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(NO_SAVE, true), checkpoint());
  }

  /** Null prediction is a contract error, and an I/O preflight failure retains its identity. */
  @Test
  public void invalidOrFailedPreflightDoesNotSaveOrCut() throws Exception {
    when(wal.preflightCut(3)).thenReturn(null);
    assertThrows(IllegalStateException.class, this::checkpoint);
    var failure = new IOException("preflight failed");
    doThrow(failure).when(wal).preflightCut(3);
    assertSame(failure, assertThrows(IOException.class, this::checkpoint));
    assertFalse(Files.exists(sideFile));
    verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
  }

  private CheckpointResult checkpoint() throws IOException {
    stateLock.readLock().lock();
    try {
      return storage.checkpointChangedPages(3);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void assertPublishedCoverage() throws Exception {
    assertTrue(Files.isRegularFile(sideFile));
    assertFalse(Files.exists(sideFile.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME)));
    try (var in = new DataInputStream(Files.newInputStream(sideFile))) {
      assertEquals(0x59544350, in.readInt());
      assertEquals(1, in.readInt());
      in.readUnsignedByte();
      assertEquals(COVERAGE, new LogSequenceNumber(in));
      assertEquals(field(ChangedPageTracker.class, tracker, "trackerId"),
          new UUID(in.readLong(), in.readLong()));
    }
  }

  private void replaceWal(WriteAheadLog replacement) throws Exception {
    stateLock.writeLock().lock();
    try {
      var field = AbstractStorage.class.getDeclaredField("writeAheadLog");
      field.setAccessible(true);
      field.set(storage, replacement);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private static Object field(Class<?> owner, Object target, String name) throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
