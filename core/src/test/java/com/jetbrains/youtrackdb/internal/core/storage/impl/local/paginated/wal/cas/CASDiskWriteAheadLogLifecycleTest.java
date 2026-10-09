package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.EncryptionKeyAbsentException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CheckpointRequestListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AbstractWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartMetadataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.CoverageTestWALRecordIds;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.HighLevelTransactionChangeRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MemoryWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MetaDataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofStatus;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Direct-construction lifecycle tests for {@link CASDiskWriteAheadLog} covering the writer /
 * segment / reader paths not already exercised by {@code CASDiskWriteAheadLogCloseTest}.
 *
 * <p>The class under test is instantiated directly in a temporary directory (pattern
 * established by {@code CASDiskWriteAheadLogCloseTest}).  No shared test infrastructure is
 * modified; every test opens its own WAL, operates on it, and closes it in a {@code
 * try/finally}.
 *
 * <p><b>Pattern boundary:</b> no page-level direct-memory pattern is used here —
 * {@code CASDiskWriteAheadLog} operates on a writable {@link Path} via {@link WALChannelFile},
 * not on cache-managed pages.
 */
public class CASDiskWriteAheadLogLifecycleTest {

  /**
   * Test-only WAL record ID. Centralised in {@link CoverageTestWALRecordIds} so the
   * compile-time anti-collision invariant covers this site.
   */
  private static final int LIFECYCLE_TEST_RECORD_ID =
      CoverageTestWALRecordIds.CAS_LIFECYCLE_TEST_RECORD_ID;

  private static Path testDirectory;

  @BeforeClass
  public static void beforeClass() {
    // Append a UUID to the directory name so concurrent test forks (or stale on-disk state
    // from a crashed prior run) cannot collide — mandated by the user-global rule
    // "every temporary file or directory must include a unique suffix".
    testDirectory =
        Paths.get(
            System.getProperty("buildDirectory", "." + File.separator + "target"),
            "casWALLifecycleTest-" + UUID.randomUUID());

    // Register test-only WAL record type for round-trip deserialization.
    WALRecordsFactory.INSTANCE.registerNewRecord(
        LIFECYCLE_TEST_RECORD_ID, LifecycleTestRecord.class);
  }

  @Before
  public void before() {
    FileUtils.deleteRecursively(testDirectory.toFile());
  }

  @After
  public void after() {
    FileUtils.deleteRecursively(testDirectory.toFile());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Creates a {@link CASDiskWriteAheadLog} with the given {@code storageName} in the shared
   * test directory. Uses deterministic defaults: no encryption, no fsync, no statistics.
   * Caller is responsible for closing the WAL.
   */
  private CASDiskWriteAheadLog createWAL(String storageName) throws IOException {
    return createWAL(storageName, null);
  }

  private CASDiskWriteAheadLog createWAL(String storageName, byte[] key) throws IOException {
    return createWAL(storageName, key, true);
  }

  private CASDiskWriteAheadLog createWAL(String storageName, byte[] key, boolean filterWALFiles)
      throws IOException {
    return new CASDiskWriteAheadLog(
        storageName,
        testDirectory,
        testDirectory,
        ContextConfiguration.WAL_DEFAULT_NAME,
        100, // maxPagesCacheSize
        64, // bufferSize (MB)
        key,
        key == null ? null : new byte[16],
        Integer.MAX_VALUE, // segmentsInterval
        Integer.MAX_VALUE, // maxSegmentSize
        20, // commitDelay (ms)
        filterWALFiles,
        Locale.US,
        -1, // walSizeHardLimit (disabled)
        1000, // fsyncInterval (ms)
        false, // keepSingleWALSegment
        false, // callFsync
        false, // printPerformanceStatistic
        10); // statisticPrintInterval
  }

  /**
   * Creates a {@link LifecycleTestRecord} with a random byte payload of the given length.
   */
  private static LifecycleTestRecord record(int len, long seed) {
    final var random = new Random(seed);
    final var data = new byte[len];
    random.nextBytes(data);
    return new LifecycleTestRecord(data);
  }

  // ---------------------------------------------------------------------------
  // Tests: begin / end / activeSegment after construction
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a freshly opened WAL has its {@code begin()} and {@code end()} LSNs
   * both anchored to segment 1 at the records offset. This is the canonical post-construction
   * state: the constructor writes a {@code StartWALRecord} followed by an {@code EmptyWALRecord}
   * and flushes, so both LSNs point into segment 1.
   */
  @Test
  public void freshWALHasBeginAndEndInFirstSegment() throws IOException {
    final var wal = createWAL("freshWALTest");
    try {
      final var begin = wal.begin();
      final var end = wal.end();

      // Segment must be 1 (the first segment created on construction).
      assertEquals(1L, begin.getSegment());
      // Position must be at RECORDS_OFFSET — where record content starts.
      assertEquals(CASWALPage.RECORDS_OFFSET, begin.getPosition());

      assertNotNull(end);
      assertEquals(1L, end.getSegment());
      // Pin end.getPosition() to the records offset — the constructor places the
      // StartWALRecord at RECORDS_OFFSET (CASDiskWriteAheadLog.java:293) and the post-init
      // end LSN points to that same offset. Without pinning the position, a regression that
      // left end at 0 (or some sentinel) would still pass the segment check.
      assertEquals(
          "end.getPosition() must equal RECORDS_OFFSET — the constructor logs StartWALRecord"
              + " at that offset and end is published past it; got " + end,
          CASWALPage.RECORDS_OFFSET,
          end.getPosition());
    } finally {
      wal.close();
    }
  }

  /**
   * Verifies that {@link CASDiskWriteAheadLog#activeSegment()} returns 1 on a fresh WAL
   * and that {@link CASDiskWriteAheadLog#begin(long)} returns a non-null LSN for segment 1
   * and {@code null} for a segment that does not yet exist.
   */
  @Test
  public void activeSegmentAndBeginBySegmentId() throws IOException {
    final var wal = createWAL("activeSegTest");
    try {
      // Active segment is 1 immediately after construction.
      assertEquals(1L, wal.activeSegment());

      // begin(1) must return the start LSN of segment 1.
      final var seg1Begin = wal.begin(1L);
      assertNotNull(seg1Begin);
      assertEquals(1L, seg1Begin.getSegment());
      assertEquals(CASWALPage.RECORDS_OFFSET, seg1Begin.getPosition());

      // begin(99) must return null — segment 99 does not exist.
      assertNull(wal.begin(99L));
    } finally {
      wal.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: log / flush / read round-trip
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a record logged via {@link CASDiskWriteAheadLog#log(WriteableWALRecord)} is
   * durably persisted across a close-and-reopen cycle: after {@code flush()}, the WAL is
   * closed, then re-opened against the same on-disk directory and storage name; the record
   * is read back and its payload pinned. Without the close-and-reopen step a serializer
   * regression that kept the record only in the in-memory queue (or skipped fsync) would
   * still pass — the assertions would all hit the same in-memory state the writer just
   * produced. Pins the deserialized record's {@code data} field and LSN — equality alone
   * would not catch a corrupt round-trip.
   */
  @Test
  public void logFlushAndReadRoundTrip() throws IOException {
    final var storageName = "logReadTest";
    final var expected = record(32, 1001L);

    // --- Write phase: log + flush, then close.
    final LogSequenceNumber lsn;
    final var writeWal = createWAL(storageName);
    try {
      lsn = writeWal.log(expected);
      writeWal.flush();
    } finally {
      writeWal.close();
    }

    // --- Read phase: re-open the WAL from disk and read the record back.
    final var readWal = createWAL(storageName);
    try {
      final var results = readWal.read(lsn, 10);
      assertFalse("read() returned empty for a freshly flushed LSN", results.isEmpty());

      // Locate our payload record (read may return EmptyWALRecord as first/last entry).
      LifecycleTestRecord actual = null;
      for (final WriteableWALRecord r : results) {
        if (r instanceof LifecycleTestRecord lr) {
          actual = lr;
          break;
        }
      }
      assertNotNull("LifecycleTestRecord not found in read() result after reopen", actual);

      // Pin the specific data field to falsify the round-trip assertion.
      assertArrayEquals(expected.data, actual.data);
      assertEquals(lsn, actual.getLsn());
    } finally {
      readWal.close();
    }
  }

  /**
   * Verifies that logging a record moves {@link CASDiskWriteAheadLog#end()} to the logged
   * LSN. After flush the flushed LSN is also updated.
   */
  @Test
  public void logUpdatesEndLsn() throws IOException {
    final var wal = createWAL("logEndTest");
    try {
      final var rec = record(16, 2002L);
      final var lsn = wal.log(rec);

      // end() must reflect the last logged LSN.
      assertEquals(lsn, wal.end());

      // After flush the getFlushedLsn() must be non-null and >= lsn.
      wal.flush();
      final var flushedLsn = wal.getFlushedLsn();
      assertNotNull(flushedLsn);
      assertTrue(flushedLsn.compareTo(lsn) >= 0);
    } finally {
      wal.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: next()
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#next(LogSequenceNumber, int)} returns the
   * immediately following record after the given LSN. Logs two records and confirms that
   * {@code next()} from the first LSN returns the second record with the correct data.
   */
  @Test
  public void nextReturnsFollowingRecord() throws IOException {
    final var wal = createWAL("nextTest");
    try {
      final var first = record(16, 3001L);
      final var second = record(16, 3002L);

      final var lsn1 = wal.log(first);
      final var lsn2 = wal.log(second);

      wal.flush();

      // next(lsn1) must contain the record at lsn2.
      final var results = wal.next(lsn1, 10);
      assertFalse("next() returned empty after first record", results.isEmpty());

      // Find the LifecycleTestRecord in the result list.
      LifecycleTestRecord nextRec = null;
      for (final WriteableWALRecord r : results) {
        if (r instanceof LifecycleTestRecord lr) {
          nextRec = lr;
          break;
        }
      }
      assertNotNull("LifecycleTestRecord not found in next() result", nextRec);

      // Pin the LSN and the payload content.
      assertEquals(lsn2, nextRec.getLsn());
      assertArrayEquals(second.data, nextRec.data);
    } finally {
      wal.close();
    }
  }

  /**
   * Verifies that {@link CASDiskWriteAheadLog#next(LogSequenceNumber, int)} with limit 0
   * returns all records following the given LSN (treats 0 as "no limit").
   */
  @Test
  public void nextWithZeroLimitReturnsAllFollowingRecords() throws IOException {
    final var wal = createWAL("nextNoLimitTest");
    try {
      final var lsn1 = wal.log(record(8, 4001L));
      wal.log(record(8, 4002L));
      wal.log(record(8, 4003L));

      wal.flush();

      // next with limit=0 means "read all remaining".
      final var results = wal.next(lsn1, 0);

      // Must contain at least the two records after lsn1.
      assertTrue(
          "Expected at least 2 records after first LSN, got " + results.size(),
          results.size() >= 2);
    } finally {
      wal.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: addCutTillLimit / removeCutTillLimit
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#addCutTillLimit(LogSequenceNumber)} and
   * {@link CASDiskWriteAheadLog#removeCutTillLimit(LogSequenceNumber)} are symmetric — adding
   * then removing a limit leaves the map empty so that a subsequent
   * {@code cutAllSegmentsSmallerThan()} is not blocked by the limit.
   */
  @Test
  public void addAndRemoveCutTillLimitIsSymmetric() throws IOException {
    final var wal = createWAL("cutLimitTest");
    try {
      final var lsn = wal.log(record(8, 5001L));
      wal.flush();

      // Add the limit then remove it — must not throw.
      wal.addCutTillLimit(lsn);
      wal.removeCutTillLimit(lsn);
    } finally {
      wal.close();
    }
  }

  /**
   * Verifies that {@link CASDiskWriteAheadLog#addCutTillLimit(LogSequenceNumber)} throws
   * {@link NullPointerException} when {@code null} is passed, and likewise for
   * {@link CASDiskWriteAheadLog#removeCutTillLimit(LogSequenceNumber)}.
   */
  @Test(expected = NullPointerException.class)
  public void addCutTillLimitThrowsOnNull() throws IOException {
    final var wal = createWAL("cutLimitNullTest");
    try {
      wal.addCutTillLimit(null);
    } finally {
      wal.close();
    }
  }

  /**
   * Verifies that {@link CASDiskWriteAheadLog#removeCutTillLimit(LogSequenceNumber)} throws
   * {@link NullPointerException} when passed {@code null}.
   */
  @Test(expected = NullPointerException.class)
  public void removeCutTillLimitThrowsOnNull() throws IOException {
    final var wal = createWAL("removeLimitNullTest");
    try {
      wal.removeCutTillLimit(null);
    } finally {
      wal.close();
    }
  }

  /**
   * Verifies the load-bearing contract of {@code addCutTillLimit}: while a limit is active at
   * a position inside segment 1, {@code cutAllSegmentsSmallerThan(2L)} must NOT delete the
   * segment 1 file; once the limit is removed, the same call must delete it. The earlier
   * {@code addAndRemoveCutTillLimitIsSymmetric} only checks "doesn't throw", which would still
   * pass if both methods became no-ops while the active-limit gate stopped working.
   */
  @Test
  public void cutAllSegmentsSmallerThanIsBlockedByActiveCutTillLimit() throws IOException {
    final var wal = createWAL("cutLimitEnforceTest");
    try {
      final var lsnInSeg1 = wal.log(record(8, 5201L));
      wal.flush();
      wal.appendNewSegment();
      wal.flush();

      final var seg1File =
          testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + ".1.wal");
      assertTrue("Segment 1 WAL file must exist before the limit is exercised",
          Files.exists(seg1File));

      // Active limit at an LSN inside segment 1 must prevent segment 1 from being cut.
      wal.addCutTillLimit(lsnInSeg1);
      try {
        wal.cutAllSegmentsSmallerThan(2L);
        assertTrue(
            "Segment 1 file must NOT be deleted while a cutTillLimit at segment 1 is active",
            Files.exists(seg1File));
      } finally {
        wal.removeCutTillLimit(lsnInSeg1);
      }

      // After the limit is removed, the same call must delete segment 1.
      wal.cutAllSegmentsSmallerThan(2L);
      assertFalse(
          "Segment 1 file must be deleted after the cutTillLimit is removed",
          Files.exists(seg1File));
    } finally {
      wal.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: appendNewSegment / activeSegment / nonActiveSegments
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#appendNewSegment()} increments the active
   * segment counter by 1 and that the old segment becomes non-active after the new one
   * is appended and flushed.
   */
  @Test
  public void appendNewSegmentIncrementsActiveSegment() throws IOException {
    final var wal = createWAL("appendSegTest");
    try {
      wal.log(record(8, 6001L));
      wal.flush();

      final long segmentBefore = wal.activeSegment();

      wal.appendNewSegment();
      wal.flush();

      // Active segment must have incremented by exactly 1.
      assertEquals(segmentBefore + 1, wal.activeSegment());
    } finally {
      wal.close();
    }
  }

  /**
   * Verifies that {@link CASDiskWriteAheadLog#nonActiveSegments()} returns only segments
   * that are fully written (below the current active segment). After appending a new segment
   * the prior segment must appear in the non-active list.
   */
  @Test
  public void nonActiveSegmentsReturnsCompletedSegmentsAfterAppend() throws IOException {
    final var wal = createWAL("nonActiveTest");
    try {
      wal.log(record(8, 7001L));
      wal.flush();

      wal.appendNewSegment();
      wal.flush();

      // Segment 1 is now non-active; segment 2 is the current active segment.
      final var nonActive = wal.nonActiveSegments();
      assertTrue(
          "Expected at least one non-active segment after appendNewSegment()",
          nonActive.length >= 1);

      // Pin the specific segment value — at least segment 1 must be in the list.
      boolean foundSegment1 = false;
      for (final long seg : nonActive) {
        if (seg == 1L) {
          foundSegment1 = true;
          break;
        }
      }
      assertTrue("Segment 1 must appear in nonActiveSegments() after segment 2 is appended",
          foundSegment1);
    } finally {
      wal.close();
    }
  }

  /**
   * Verifies that {@link CASDiskWriteAheadLog#nonActiveSegments(long)} returns only non-active
   * segments at or after the specified starting segment. Segments below the start are excluded,
   * and the active segment itself is also excluded (the implementation breaks out of the loop on
   * the first segment id {@code >= currentSegment}).
   *
   * <p>After {@code appendNewSegment()} the active segment is 2, so {@code nonActiveSegments(2L)}
   * filters out segment 1 (below the start) and segment 2 (the active one) — the result must be
   * an empty array. We pin the exact length so a regression that returns segment 1 (because the
   * lower bound stops being honoured) or returns segment 2 (because the active-segment cap stops
   * being honoured) fails the test, instead of being silently swallowed by an empty-loop
   * iteration.
   */
  @Test
  public void nonActiveSegmentsByFromSegment() throws IOException {
    final var wal = createWAL("nonActiveFromTest");
    try {
      wal.log(record(8, 8001L));
      wal.flush();
      wal.appendNewSegment();
      wal.flush();

      // fromSegment=2 with active segment 2: the only candidate is segment 2 itself (excluded
      // because it equals currentSegment). Segment 1 is below the start, so also excluded.
      final var files = wal.nonActiveSegments(2L);
      assertEquals(
          "nonActiveSegments(2) with active segment 2 must return an empty array — "
              + "segment 1 is below the start, segment 2 is the active segment",
          0,
          files.length);
      // Defence in depth: if a regression returns segment 1, surface the file name explicitly.
      for (final File f : files) {
        assertFalse(
            "nonActiveSegments(2) must not include segment 1 files, but found: " + f.getName(),
            f.getName().contains(".1.wal"));
      }
    } finally {
      wal.close();
    }
  }

  // Preflight applies current-segment and earliest-limit clamps without changing files, segment
  // membership or limit reference counts. Coverage is the retained segment's actual record start.
  @Test
  public void preflightIsNonMutatingAndAppliesCurrentSegmentAndLimitClamps() throws Exception {
    try (var wal = createWAL("preflight")) {
      wal.appendNewSegment();
      wal.appendNewSegment();
      wal.flush();
      var segments = wal.nonActiveSegments();
      var beginning = wal.begin();
      var end = wal.end();
      var size = wal.size();
      var files = wal.nonActiveSegments(0);
      var plan = wal.preflightCut(Long.MAX_VALUE);
      assertTrue(plan.removesSegments());
      assertEquals(3, plan.effectiveBoundary());
      assertEquals(wal.begin(3), plan.coverageLsn());
      assertEquals(CASWALPage.RECORDS_OFFSET, plan.coverageLsn().getPosition());
      assertEquals(plan.coverageLsn(), wal.read(plan.coverageLsn(), 1).getFirst().getLsn());
      var early = new LogSequenceNumber(1, 100);
      var later = new LogSequenceNumber(2, 100);
      wal.addCutTillLimit(later);
      wal.addCutTillLimit(early);
      wal.addCutTillLimit(early);
      try {
        var limitField = CASDiskWriteAheadLog.class.getDeclaredField("cutTillLimits");
        limitField.setAccessible(true);
        var limits = (Map<?, ?>) limitField.get(wal);
        var before = Map.copyOf(limits);
        plan = wal.preflightCut(3);
        assertFalse(plan.removesSegments());
        assertEquals(1, plan.effectiveBoundary());
        assertEquals(beginning, plan.coverageLsn());
        assertEquals(before, limits);
        wal.removeCutTillLimit(early);
        assertEquals(1, wal.preflightCut(3).effectiveBoundary());
        wal.removeCutTillLimit(early);
        plan = wal.preflightCut(3);
        assertTrue(plan.removesSegments());
        assertEquals(2, plan.effectiveBoundary());
        assertEquals(wal.begin(2), plan.coverageLsn());
      } finally {
        wal.removeCutTillLimit(later);
      }
      assertArrayEquals(segments, wal.nonActiveSegments());
      assertArrayEquals(files, wal.nonActiveSegments(0));
      for (var file : files) {
        assertTrue(file.exists());
      }
      assertEquals(beginning, wal.begin());
      assertEquals(end, wal.end());
      assertEquals(size, wal.size());
      assertFalse(wal.preflightCut(1).removesSegments());
      assertFalse(wal.preflightCut(0).removesSegments());
      assertEquals(beginning, wal.preflightCut(0).coverageLsn());
    }
  }

  // Holding the writer lock lets the test install deterministic written-up-to positions on an
  // open WAL. Each clamp is isolated, and the real position is restored before releasing the lock.
  @Test
  public void preflightAppliesWrittenUpToClamp() throws Exception {
    try (var wal = createWAL("writtenClamp")) {
      wal.appendNewSegment();
      wal.appendNewSegment();
      wal.flush();
      var lock = (ReentrantLock) walField(wal, "recordsWriterLock");
      var written = writtenPosition(wal);
      lock.lock();
      var original = written.get();
      try {
        written.set(new WrittenUpTo(new LogSequenceNumber(10, 100), 200));
        assertEquals(3, wal.preflightCut(100).effectiveBoundary());
        written.set(new WrittenUpTo(new LogSequenceNumber(2, 100), 200));
        var plan = wal.preflightCut(3);
        assertTrue(plan.removesSegments());
        assertEquals(2, plan.effectiveBoundary());
        assertEquals(wal.begin(2), plan.coverageLsn());
        written.set(new WrittenUpTo(new LogSequenceNumber(1, 100), 200));
        assertFalse(wal.preflightCut(3).removesSegments());
      } finally {
        written.set(original);
        lock.unlock();
      }
    }
  }

  // Construction logs an empty record before returning. Reconstruct the earlier reopen state
  // with inventory {k}, currentSegment k+1 and writtenUpTo (k+1, 0), before that first log.
  // The prediction must still require a save and match the later fixed-boundary cut.
  @Test
  public void preflightCoversReopenStateBeforeFirstLogPublishesCurrentSegment() throws Exception {
    try (var wal = createWAL("reopenPreflight")) {
      assertEquals(1, wal.activeSegment());
    }
    try (var wal = createWAL("reopenPreflight")) {
      assertEquals(2, wal.activeSegment());
      var lock = (ReentrantLock) walField(wal, "recordsWriterLock");
      var segments = segmentInventory(wal);
      var written = writtenPosition(wal);
      lock.lock();
      var original = written.get();
      try {
        assertTrue(segments.remove(2L));
        assertEquals(java.util.Set.of(1L), segments);
        written.set(new WrittenUpTo(new LogSequenceNumber(2, 0), 0));
        var plan = wal.preflightCut(Long.MAX_VALUE);
        assertTrue(plan.removesSegments());
        assertEquals(2, plan.effectiveBoundary());
        assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), plan.coverageLsn());
        // Complete the first log's inventory publication before the caller performs its cut.
        segments.add(2L);
        assertTrue(wal.cutAllSegmentsSmallerThan(plan.effectiveBoundary()));
        assertEquals(plan.coverageLsn(), wal.begin());
        assertFalse(Files.exists(testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME
            + ".1.wal")));
      } finally {
        segments.add(2L);
        written.set(original);
        lock.unlock();
      }
    }
  }

  // Deterministically withhold the rotated segment's inventory entry after its first record is
  // written. Preflight must predict deletion of segment 1, with readable coverage in segment 2.
  @Test
  public void preflightCoversWrittenRotationBeforeInventoryPublication() throws Exception {
    try (var wal = createWAL("rotationPreflight")) {
      wal.appendNewSegment();
      wal.flush();
      var lock = (ReentrantLock) walField(wal, "recordsWriterLock");
      var segments = segmentInventory(wal);
      lock.lock();
      try {
        assertEquals(2, writtenPosition(wal).get().lsn().getSegment());
        assertTrue(segments.remove(2L));
        assertNull(wal.begin(2));
        var plan = wal.preflightCut(Long.MAX_VALUE);
        assertTrue(plan.removesSegments());
        assertEquals(2, plan.effectiveBoundary());
        assertEquals(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), plan.coverageLsn());
        segments.add(2L);
        assertTrue(wal.cutAllSegmentsSmallerThan(plan.effectiveBoundary()));
        assertEquals(plan.coverageLsn(), wal.begin());
        assertEquals(plan.coverageLsn(), wal.read(plan.coverageLsn(), 1).getFirst().getLsn());
      } finally {
        segments.add(2L);
        lock.unlock();
      }
    }
  }

  // An empty disk inventory cannot support a cut prediction. Closing is rejected even when a
  // synthetic inventory entry is present. Neither unavailable state is a healthy memory-WAL no-op.
  @Test
  public void preflightRejectsEmptyDiskInventoryAndClosedWal() throws Exception {
    var wal = createWAL("unavailablePreflight");
    var segments = segmentInventory(wal);
    try {
      segments.clear();
      try {
        var failure = assertThrows(IllegalStateException.class, () -> wal.preflightCut(2));
        assertTrue(failure.getMessage().contains("empty segment inventory"));
      } finally {
        segments.add(1L);
      }
    } finally {
      wal.close();
    }
    assertTrue(segments.isEmpty());
    var failure = assertThrows(IllegalStateException.class, () -> wal.preflightCut(2));
    assertTrue(failure.getMessage().contains("closed disk WAL"));
    segments.add(1L);
    try {
      assertThrows(IllegalStateException.class, () -> wal.preflightCut(2));
    } finally {
      segments.clear();
    }
  }

  private static Object walField(CASDiskWriteAheadLog wal, String name) throws Exception {
    var field = CASDiskWriteAheadLog.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(wal);
  }

  @SuppressWarnings("unchecked")
  private static java.util.Set<Long> segmentInventory(CASDiskWriteAheadLog wal) throws Exception {
    return (java.util.Set<Long>) walField(wal, "segments");
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<WrittenUpTo> writtenPosition(CASDiskWriteAheadLog wal)
      throws Exception {
    return (AtomicReference<WrittenUpTo>) walField(wal, "writtenUpTo");
  }

  // A missing requested segment is legal after an LSN jump. Even while the written segment's
  // inventory entry is pending, coverage must name its real first record, not a start in the gap.
  @Test
  public void preflightUsesFirstActualRetainedRecordAcrossSegmentGaps() throws Exception {
    try (var wal = createWAL("preflightGap")) {
      wal.moveLsnAfter(new LogSequenceNumber(4, 100));
      wal.log(record(8, 42));
      wal.flush();
      assertNull(wal.begin(3));
      var lock = (ReentrantLock) walField(wal, "recordsWriterLock");
      var segments = segmentInventory(wal);
      lock.lock();
      try {
        assertEquals(5, writtenPosition(wal).get().lsn().getSegment());
        assertTrue(segments.remove(5L));
        var pending = wal.preflightCut(3);
        assertTrue(pending.removesSegments());
        assertEquals(3, pending.effectiveBoundary());
        assertEquals(new LogSequenceNumber(5, CASWALPage.RECORDS_OFFSET), pending.coverageLsn());
      } finally {
        segments.add(5L);
        lock.unlock();
      }
      var plan = wal.preflightCut(3);
      assertTrue(plan.removesSegments());
      assertEquals(3, plan.effectiveBoundary());
      assertEquals(wal.begin(5), plan.coverageLsn());
      assertTrue(wal.cutAllSegmentsSmallerThan(plan.effectiveBoundary()));
      assertEquals(plan.coverageLsn(), wal.begin());
      var stale = wal.preflightCut(3);
      assertFalse(stale.removesSegments());
      assertEquals(wal.begin(), stale.coverageLsn());
    }
  }

  // A late backup limit can only lower the real cut. Removing a planned limit cannot raise a
  // cut whose caller uses the preflight's fixed effective boundary instead of its initial request.
  @Test
  public void retentionChangesAfterPreflightNeverRaiseFixedCutBoundary() throws Exception {
    try (var wal = createWAL("lateLimit")) {
      wal.appendNewSegment();
      wal.appendNewSegment();
      wal.flush();
      var plan = wal.preflightCut(3);
      assertEquals(3, plan.effectiveBoundary());
      var limit = wal.begin(2);
      wal.addCutTillLimit(limit);
      assertTrue(wal.cutAllSegmentsSmallerThan(plan.effectiveBoundary()));
      assertEquals(limit, wal.begin());
      assertNotNull(wal.begin(3));
      plan = wal.preflightCut(3);
      assertFalse(plan.removesSegments());
      assertEquals(2, plan.effectiveBoundary());
      wal.removeCutTillLimit(limit);
      assertFalse(wal.cutAllSegmentsSmallerThan(plan.effectiveBoundary()));
      assertEquals(limit, wal.begin());
      assertTrue(wal.preflightCut(3).removesSegments());
    }
  }

  // Memory-only storage retains no physical WAL segments and therefore has no coverage LSN.
  @Test
  public void memoryWalPreflightNeverClaimsSegmentRemovalOrCoverage() {
    var wal = new MemoryWriteAheadLog();
    var plan = wal.preflightCut(100);
    assertFalse(plan.removesSegments());
    assertEquals(0, plan.effectiveBoundary());
    assertNull(plan.coverageLsn());
  }

  // ---------------------------------------------------------------------------
  // Tests: cutAllSegmentsSmallerThan / cutTill
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#cutAllSegmentsSmallerThan(long)} deletes the
   * WAL file for the given segment after it becomes non-active. The WAL file for segment 1
   * must be absent from disk after cutting with {@code segmentId=2}.
   */
  @Test
  public void cutAllSegmentsSmallerThanDeletesOldSegmentFile() throws IOException {
    final var wal = createWAL("cutSegTest");
    try {
      wal.log(record(8, 9001L));
      wal.flush();

      // Advance to segment 2 so segment 1 becomes non-active.
      wal.appendNewSegment();
      wal.log(record(8, 9002L));
      wal.flush();

      // Segment 1 WAL file must exist before cutting.
      final var seg1File = testDirectory.resolve(
          ContextConfiguration.WAL_DEFAULT_NAME + ".1.wal");
      assertTrue("Segment 1 WAL file must exist before cut", Files.exists(seg1File));

      // Cut segments below 2 — this removes segment 1.
      final var removed = wal.cutAllSegmentsSmallerThan(2L);

      assertTrue("cutAllSegmentsSmallerThan(2) must return true when segment 1 existed", removed);
      assertFalse("Segment 1 WAL file must be deleted after cut", Files.exists(seg1File));
    } finally {
      wal.close();
    }
  }

  /**
   * Verifies that {@link CASDiskWriteAheadLog#cutTill(LogSequenceNumber)} is equivalent to
   * cutting all segments smaller than the LSN's segment. After cutting to a LSN in segment 2
   * the WAL file for segment 1 must no longer exist.
   */
  @Test
  public void cutTillDelegatesToCutAllSegmentsSmallerThan() throws IOException {
    final var wal = createWAL("cutTillTest");
    try {
      wal.log(record(8, 10001L));
      wal.flush();

      wal.appendNewSegment();
      final var lsnInSeg2 = wal.log(record(8, 10002L));
      wal.flush();

      final var seg1File = testDirectory.resolve(
          ContextConfiguration.WAL_DEFAULT_NAME + ".1.wal");
      assertTrue("Segment 1 WAL file must exist before cut", Files.exists(seg1File));

      // cutTill delegates to cutAllSegmentsSmallerThan(lsnInSeg2.getSegment()).
      wal.cutTill(lsnInSeg2);

      assertFalse("Segment 1 WAL file must be deleted after cutTill", Files.exists(seg1File));
    } finally {
      wal.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: addEventAt / event firing
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#addEventAt(LogSequenceNumber, Runnable)} fires
   * the event exactly once after the WAL is flushed past the given LSN. The latch times out
   * with a 5-second budget to avoid hanging the build; an expired latch means the event was
   * never fired, which is a failure. The {@code callCount} pin guards against a regression
   * that fires the event more than once: {@code CountDownLatch.countDown()} is a no-op past
   * zero, so a duplicate fire would otherwise pass silently.
   */
  @Test
  public void addEventAtFiresAfterFlush() throws IOException, InterruptedException {
    final var wal = createWAL("eventTest");
    var closed = false;
    try {
      final var latch = new CountDownLatch(1);
      final var callCount = new AtomicInteger(0);
      final var lsn = wal.log(record(8, 11001L));

      // Register the event before flushing — the WAL may not have written lsn yet.
      wal.addEventAt(
          lsn,
          () -> {
            callCount.incrementAndGet();
            latch.countDown();
          });

      // Flush forces the WAL to write past lsn, which must fire the event.
      wal.flush();

      // Allow up to 5 s for the executor to deliver the event asynchronously.
      final var fired = latch.await(5, TimeUnit.SECONDS);
      assertTrue("Event registered via addEventAt() was not fired within 5 s after flush", fired);

      // Quiesce the commit executor by closing the WAL before pinning the exactly-once
      // contract — close() waits for the records-writer future to complete, so any duplicate
      // delivery scheduled on the same executor must have run by the time close() returns.
      // Without this the duplicate could land between latch.await() and the assertEquals
      // below, silently passing the test (countDown past zero is a no-op).
      wal.close();
      closed = true;
      assertEquals("addEventAt must fire the runnable exactly once", 1, callCount.get());
    } finally {
      if (!closed) {
        wal.close();
      }
    }
  }

  /**
   * Verifies that {@link CASDiskWriteAheadLog#addEventAt(LogSequenceNumber, Runnable)} fires
   * immediately (synchronously or via the commit executor) when the LSN is already flushed
   * before the event is registered. Pins the counter at 1 to falsify a "fires multiple times"
   * regression and uses a {@link CountDownLatch} to wait for the asynchronous delivery instead
   * of a {@code Thread.sleep} polling loop.
   */
  @Test
  public void addEventAtFiresImmediatelyWhenLsnAlreadyFlushed()
      throws IOException, InterruptedException {
    final var wal = createWAL("eventFlushedTest");
    var closed = false;
    try {
      final var lsn = wal.log(record(8, 12001L));
      // Flush before registering the event.
      wal.flush();

      final var latch = new CountDownLatch(1);
      final var callCount = new AtomicInteger(0);
      // The LSN is already past the flushed point — event must fire immediately.
      wal.addEventAt(
          lsn,
          () -> {
            callCount.incrementAndGet();
            latch.countDown();
          });

      // Wait up to 2 s for the executor to deliver the event — replaces a Thread.sleep poll.
      assertTrue(
          "Event must fire within 2 s after addEventAt() for an already-flushed LSN",
          latch.await(2, TimeUnit.SECONDS));

      // Quiesce the commit executor before checking exactly-once — see addEventAtFiresAfterFlush
      // for the rationale (close() waits for the records-writer future, so any duplicate fire
      // queued on the same executor must have run by the time close() returns).
      wal.close();
      closed = true;
      assertEquals(1, callCount.get());
    } finally {
      if (!closed) {
        wal.close();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: addCheckpointListener / removeCheckpointListener
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#addCheckpointListener(CheckpointRequestListener)}
   * registers the listener and
   * {@link CASDiskWriteAheadLog#removeCheckpointListener(CheckpointRequestListener)} unregisters
   * it.  Reflectively reads the underlying {@code CopyOnWriteArrayList} so the round-trip
   * cannot be silently passed by both methods being no-op stubs — without this check, a
   * regression that turned both into no-ops would still see {@code requestCount == 0} and
   * pass.
   *
   * <p>The configured WAL has {@code keepSingleWALSegment=false} and {@code walSizeLimit=-1},
   * so the production paths that drive {@code requestCheckpoint()} (in {@code log()} on lines
   * 1016-1024) are inert; we cannot trigger a real checkpoint without a heavyweight invasive
   * setup, hence the structural check on the listener list itself.
   */
  @Test
  public void addAndRemoveCheckpointListener() throws IOException, ReflectiveOperationException {
    final var wal = createWAL("checkpointListenerTest");
    try {
      final var requestCount = new AtomicInteger(0);
      final CheckpointRequestListener listener = requestCount::incrementAndGet;

      // Read the private list field reflectively so we can pin presence/absence directly.
      final var listField =
          CASDiskWriteAheadLog.class.getDeclaredField("checkpointRequestListeners");
      listField.setAccessible(true);
      @SuppressWarnings("unchecked")
      final var listeners = (java.util.List<CheckpointRequestListener>) listField.get(wal);

      assertFalse(
          "Listener must not be registered before addCheckpointListener() is called",
          listeners.contains(listener));

      wal.addCheckpointListener(listener);
      assertTrue(
          "Listener must be present in checkpointRequestListeners after addCheckpointListener()",
          listeners.contains(listener));

      wal.removeCheckpointListener(listener);
      assertFalse(
          "Listener must be absent from checkpointRequestListeners after removeCheckpointListener()",
          listeners.contains(listener));

      // Belt and braces: requestCount must still be 0 because keepSingleWALSegment is false
      // and walSizeLimit is -1 — no checkpoint request is triggered by this test.
      assertEquals(0, requestCount.get());
    } finally {
      wal.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: moveLsnAfter / appendSegment
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#moveLsnAfter(LogSequenceNumber)} advances the
   * active segment to at least segment + 1 relative to the given LSN's segment.
   */
  @Test
  public void moveLsnAfterAdvancesActiveSegment() throws IOException {
    final var wal = createWAL("moveLsnTest");
    try {
      final var lsn = wal.log(record(8, 13001L));
      wal.flush();

      // moveLsnAfter(lsn) must advance to lsn.getSegment() + 1.
      wal.moveLsnAfter(lsn);

      // Pin: activeSegment must be at least lsn.getSegment() + 1.
      assertTrue(
          "activeSegment must be > lsn.getSegment() after moveLsnAfter()",
          wal.activeSegment() > lsn.getSegment());
    } finally {
      wal.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: delete()
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#delete()} closes the WAL and removes all WAL
   * segment files from disk. After {@code delete()} the test directory must contain no
   * {@code .wal} files.
   */
  @Test
  public void deleteRemovesAllWALFiles() throws IOException {
    final var wal = createWAL("deleteTest");

    wal.log(record(8, 14001L));
    wal.flush();

    // Confirm at least one WAL file exists before deletion.
    try (final var stream = Files.list(testDirectory)) {
      final var walFiles = stream
          .filter(p -> p.getFileName().toString().endsWith(".wal"))
          .count();
      assertTrue("At least one WAL file must exist before delete()", walFiles > 0);
    }

    // delete() closes and removes all segment files — no close() call needed afterwards.
    wal.delete();

    // After delete, no .wal files must remain.
    try (final var stream = Files.list(testDirectory)) {
      final var remaining = stream
          .filter(p -> p.getFileName().toString().endsWith(".wal"))
          .count();
      assertEquals("All WAL files must be deleted after delete()", 0, remaining);
    }
  }

  // ---------------------------------------------------------------------------
  // Tests: size() and segSize()
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link CASDiskWriteAheadLog#size()} grows after logging a record.
   * The initial size is non-zero (the constructor logs a {@code StartWALRecord} and an
   * {@code EmptyWALRecord}); after logging one additional record the size must increase.
   */
  @Test
  public void sizeGrowsAfterLoggingRecord() throws IOException {
    final var wal = createWAL("sizeTest");
    try {
      final var sizeBefore = wal.size();

      wal.log(record(64, 15001L));

      // Logging a 64-byte record must increase the reported size.
      assertTrue(
          "WAL size must increase after logging a record; was " + sizeBefore + ", now "
              + wal.size(),
          wal.size() > sizeBefore);
    } finally {
      wal.close();
    }
  }

  // Real close/reopen fixtures include the constructor's EmptyWALRecord at each segment start.
  // Exact sequence checks include every record, not only payload records or their counts.
  private List<WriteableWALRecord> proofFixture(int segmentCount, int secondRecordSize)
      throws IOException {
    var expected = new ArrayList<WriteableWALRecord>();
    try (var wal = createWAL("proof")) {
      for (int segment = 1; segment <= segmentCount; segment++) {
        if (segment > 1) {
          wal.appendNewSegment();
        }
        expected.add(wal.read(wal.begin(segment), 1).getFirst());
        var first = record(8, segment);
        wal.log(first);
        expected.add(first);
        if (segment == 1 && secondRecordSize > 0) {
          var second = record(secondRecordSize, 42);
          wal.log(second);
          expected.add(second);
        }
      }
    }
    return expected;
  }

  private Path segmentPath(long segment) {
    return testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + "." + segment + ".wal");
  }

  private static void assertSequence(List<WriteableWALRecord> expected,
      List<WriteableWALRecord> actual) {
    assertEquals(expected.stream().map(WriteableWALRecord::getLsn).toList(),
        actual.stream().map(WriteableWALRecord::getLsn).toList());
    for (int i = 0; i < expected.size(); i++) {
      var message = "Record at " + expected.get(i).getLsn();
      assertEquals(message, expected.get(i).getId(), actual.get(i).getId());
      assertEquals(message, WALRecordsFactory.toStream(expected.get(i)).rewind(),
          WALRecordsFactory.toStream(actual.get(i)).rewind());
    }
  }

  private void breakPage(long segment, int page) throws IOException {
    try (var file = FileChannel.open(segmentPath(segment), StandardOpenOption.WRITE)) {
      file.write(ByteBuffer.wrap(new byte[Long.BYTES]), (long) page * CASWALPage.DEFAULT_PAGE_SIZE);
    }
  }

  // A clean multi-segment extent reaches its physical end in bounded batches. New records,
  // segments and rotation after reopen cannot enter the immutable original inventory.
  @Test
  public void proofReachesCleanPreOpenEndWithBoundedBatchesAndExcludesNewRecords()
      throws Exception {
    var expected = proofFixture(3, 10000);
    try (var wal = createWAL("proof")) {
      var extent = wal.preOpenExtent();
      assertEquals(List.of(1L, 2L, 3L),
          extent.segments().stream().map(s -> s.segment()).toList());
      for (var segment : extent.segments()) {
        assertEquals(Files.size(segmentPath(segment.segment())), segment.bytes());
      }
      assertThrows(UnsupportedOperationException.class, () -> extent.segments().clear());
      wal.log(record(8, 99));
      wal.appendNewSegment();
      wal.flush();
      assertEquals(extent, wal.preOpenExtent());
      var reader = wal.openProofReader(wal.begin(), 20000);
      assertThrows(IllegalArgumentException.class, () -> reader.next(0));
      assertThrows(IllegalArgumentException.class, () -> wal.openProofReader(wal.begin(), 1));
      var actual = new ArrayList<WriteableWALRecord>();
      for (int batchIndex = 0; batchIndex < 10; batchIndex++) {
        var batch = reader.next(2);
        assertTrue(batch.records().size() <= 2);
        actual.addAll(batch.records());
        assertNull(batch.error());
        if (batch.status() == ProofStatus.REACHED_END) {
          var last = extent.segments().getLast();
          assertEquals(new LogSequenceNumber(last.segment(), (int) last.bytes()), batch.position());
          assertEquals(ProofStatus.REACHED_END, reader.next(2).status());
          assertTrue(reader.next(2).records().isEmpty());
          break;
        }
        assertEquals(ProofStatus.MORE, batch.status());
      }
      assertSequence(expected, actual);
      // Begin below coverage is accepted at the preflight's RECORDS_OFFSET boundary.
      var batch = wal.openProofReader(wal.begin(2), 20000).next(100);
      assertEquals(ProofStatus.REACHED_END, batch.status());
      assertSequence(expected.subList(3, expected.size()), batch.records());
    }
  }

  // No original extent and memory-only storage cannot use an empty read as coverage proof.
  @Test
  public void proofRejectsEmptyExtentAndMemoryProofIsUnavailable() throws Exception {
    try (var wal = createWAL("emptyProof")) {
      assertTrue(wal.preOpenExtent().segments().isEmpty());
      var batch = wal.openProofReader(wal.begin(), 100).next(10);
      assertEquals(ProofStatus.COVERAGE_NOT_RETAINED, batch.status());
      assertEquals(wal.begin(), batch.position());
      assertSequence(List.of(), batch.records());
    }
    var memory = new MemoryWriteAheadLog();
    assertTrue(memory.preOpenExtent().segments().isEmpty());
    var batch = memory.openProofReader(new LogSequenceNumber(1, 22), 100).next(10);
    assertEquals(ProofStatus.UNAVAILABLE, batch.status());
    assertSequence(List.of(), batch.records());
  }

  // Missing coverage and an interior numeric hole have different terminal reasons. An intentional
  // LSN jump has no durable certificate, so a jump inside coverage also fails conservatively.
  @Test
  public void proofRejectsMissingCoverageInteriorSegmentsAndUncertifiedLsnJumps() throws Exception {
    proofFixture(3, 0);
    Files.delete(segmentPath(1));
    try (var wal = createWAL("proof")) {
      var batch = wal.openProofReader(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), 100)
          .next(10);
      assertEquals(ProofStatus.COVERAGE_NOT_RETAINED, batch.status());
      assertSequence(List.of(), batch.records());
    }
    Files.delete(segmentPath(3));
    try (var wal = createWAL("proof")) {
      var batch = wal.openProofReader(wal.begin(), 100).next(10);
      assertEquals(ProofStatus.MISSING_SEGMENT, batch.status());
      assertEquals(new LogSequenceNumber(3, CASWALPage.RECORDS_OFFSET), batch.position());
      assertSequence(List.of(), batch.records());
    }
    FileUtils.deleteRecursively(testDirectory.toFile());
    try (var wal = createWAL("proof")) {
      wal.moveLsnAfter(new LogSequenceNumber(4, 100));
      wal.log(record(8, 7));
    }
    try (var wal = createWAL("proof")) {
      var batch = wal.openProofReader(wal.begin(), 100).next(10);
      assertEquals(ProofStatus.MISSING_SEGMENT, batch.status());
      assertSequence(List.of(), batch.records());
      batch = wal.openProofReader(wal.begin(5), 100).next(10);
      assertEquals(ProofStatus.REACHED_END, batch.status());
      assertSequence(wal.read(wal.begin(5), 1), batch.records());
    }
  }

  // Unlinking an unread segment after the first batch is explicit failure, not a partial success.
  @Test
  public void proofReportsSegmentVanishedBetweenBatches() throws Exception {
    var expected = proofFixture(3, 0);
    try (var wal = createWAL("proof")) {
      var reader = wal.openProofReader(wal.begin(), 100);
      var batch = reader.next(1);
      assertEquals(ProofStatus.MORE, batch.status());
      assertSequence(expected.subList(0, 1), batch.records());
      Files.delete(segmentPath(2));
      batch = reader.next(10);
      assertEquals(ProofStatus.SEGMENT_VANISHED, batch.status());
      assertEquals(new LogSequenceNumber(2, 0), batch.position());
      assertSequence(expected.subList(1, 2), batch.records());
      assertNotNull(batch.error());
    }
  }

  // A consumed segment also remains required until completion. Shrink is distinct from unlink.
  @Test
  public void proofChecksConsumedFilesAtEndAndReportsIoErrors() throws Exception {
    var expected = proofFixture(2, 0);
    try (var wal = createWAL("proof")) {
      var reader = wal.openProofReader(wal.begin(), 100);
      assertSequence(expected.subList(0, 2), reader.next(2).records());
      Files.delete(segmentPath(1));
      var batch = reader.next(10);
      assertEquals(ProofStatus.SEGMENT_VANISHED, batch.status());
      assertSequence(expected.subList(2, 4), batch.records());
      reader = wal.openProofReader(wal.begin(2), 100);
      try (var file = FileChannel.open(segmentPath(2), StandardOpenOption.WRITE)) {
        file.truncate(0);
      }
      batch = reader.next(10);
      assertEquals(ProofStatus.IO_ERROR, batch.status());
      assertNotNull(batch.error());
      assertSequence(List.of(), batch.records());
    }
  }

  // The same real broken fixtures keep legacy partial-list behavior. The proof reader distinguishes
  // an unreadable final page, later readable same-segment pages and readable later segments.
  @Test
  public void proofReportsBrokenPageAndLaterReadablePagesWithoutChangingLegacyReaders()
      throws Exception {
    for (int scenario = 0; scenario < 3; scenario++) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      var expected = proofFixture(scenario == 2 ? 2 : 1, scenario == 1 ? 10000 : 4500);
      // Constructor flush consumes page 0. The payloads start on page 1 and span page 2.
      assertEquals((scenario == 1 ? 4 : 3) * (long) CASWALPage.DEFAULT_PAGE_SIZE,
          Files.size(segmentPath(1)));
      breakPage(1, 2);
      try (var wal = createWAL("proof")) {
        var batch = wal.openProofReader(wal.begin(), 20000).next(100);
        assertEquals(ProofStatus.BROKEN_PAGE, batch.status());
        assertEquals(new LogSequenceNumber(1, 2 * CASWALPage.DEFAULT_PAGE_SIZE), batch.position());
        assertEquals(scenario > 0, batch.readablePageFollows());
        assertSequence(expected.subList(0, 2), batch.records());
        assertNull(batch.error());
        assertSequence(expected.subList(0, 2), wal.read(wal.begin(), 100));
        assertSequence(expected.subList(1, 2), wal.next(wal.begin(), 100));
      }
    }
  }

  // A large valid record cannot defeat the reader's memory bound. Out-of-range coverage is not end.
  @Test
  public void proofBoundsRecordAllocationAndRejectsCoverageOutsidePhysicalExtent()
      throws Exception {
    var expected = proofFixture(1, 4500);
    try (var wal = createWAL("proof")) {
      var batch = wal.openProofReader(wal.begin(), 100).next(10);
      assertEquals(ProofStatus.RECORD_TOO_LARGE, batch.status());
      assertSequence(expected.subList(0, 2), batch.records());
      for (int position : new int[] {0, 1, CASWALPage.DEFAULT_PAGE_SIZE, 1000000}) {
        batch = wal.openProofReader(new LogSequenceNumber(1, position), 100).next(10);
        assertEquals(ProofStatus.COVERAGE_NOT_RETAINED, batch.status());
        assertSequence(List.of(), batch.records());
      }
    }
  }

  // Checksummed malformed records must fail explicitly. This fixture edits real page bytes and
  // recalculates the page checksum, so failures exercise record validation rather than page damage.
  @Test
  public void proofReportsInvalidAndIncompleteRecordsAndPadding() throws Exception {
    var expected = proofFixture(1, 0);
    byte[] original = Files.readAllBytes(segmentPath(1));
    int start = expected.get(1).getLsn().getPosition();
    for (int scenario = 0; scenario < 4; scenario++) {
      var content = original.clone();
      var buffer = ByteBuffer.wrap(content).order(ByteOrder.nativeOrder());
      if (scenario == 0) {
        buffer.putInt(start, -1);
      } else if (scenario == 1) {
        buffer.putShort(start + Integer.BYTES, (short) 511);
      } else if (scenario == 2) {
        buffer.putInt(start, 100);
      } else {
        buffer.putInt(start, 0);
      }
      int pageStart = start / CASWALPage.DEFAULT_PAGE_SIZE * CASWALPage.DEFAULT_PAGE_SIZE;
      buffer.limit(pageStart + buffer.getShort(pageStart + CASWALPage.PAGE_SIZE_OFFSET));
      buffer.position(pageStart + CASWALPage.RECORDS_OFFSET);
      buffer.putLong(pageStart + CASWALPage.XX_OFFSET,
          XXHashFactory.fastestJavaInstance().hash64().hash(buffer, 0x9747b28cL));
      Files.write(segmentPath(1), content);
      try (var wal = createWAL("proof")) {
        var batch = wal.openProofReader(wal.begin(), 1000).next(10);
        assertEquals(scenario == 2 ? ProofStatus.INCOMPLETE_RECORD
            : scenario == 3 ? ProofStatus.REACHED_END : ProofStatus.INVALID_RECORD, batch.status());
        assertSequence(expected.subList(0, 1), batch.records());
        if (scenario == 0) {
          batch = wal.openProofReader(new LogSequenceNumber(1, start + 100), 1000).next(10);
          assertEquals(ProofStatus.INVALID_RECORD, batch.status());
          assertSequence(List.of(), batch.records());
        }
      }
      // Reopen adds a segment. It must not affect the next malformed fixture's original extent.
      Files.delete(segmentPath(2));
    }
  }

  // A physically torn final page is broken, not end. No readable page follows in the original
  // extent. Removing a later segment after capture cannot be hidden by the broken-page lookahead.
  @Test
  public void proofHandlesShortFinalPagesAndMissingFilesDuringBrokenPageLookahead()
      throws Exception {
    var expected = proofFixture(1, 4500);
    try (var file = FileChannel.open(segmentPath(1), StandardOpenOption.WRITE)) {
      file.truncate(2L * CASWALPage.DEFAULT_PAGE_SIZE + 8);
    }
    try (var wal = createWAL("proof")) {
      var batch = wal.openProofReader(wal.begin(), 20000).next(10);
      assertEquals(ProofStatus.BROKEN_PAGE, batch.status());
      assertFalse(batch.readablePageFollows());
      assertSequence(expected.subList(0, 2), batch.records());
    }
    FileUtils.deleteRecursively(testDirectory.toFile());
    expected = proofFixture(2, 4500);
    breakPage(1, 2);
    try (var wal = createWAL("proof")) {
      var reader = wal.openProofReader(wal.begin(), 20000);
      Files.delete(segmentPath(2));
      var batch = reader.next(10);
      assertEquals(ProofStatus.SEGMENT_VANISHED, batch.status());
      assertSequence(expected.subList(0, 2), batch.records());
    }
  }

  // Encrypted multi-page records use the same decrypt/checksum path as legacy reads. Compressed
  // records are bounded by their expanded size as well as their encoded size.
  @Test
  public void proofReadsEncryptedRecordsAndBoundsCompressedExpansion() throws Exception {
    var key = new byte[16];
    var expected = new ArrayList<WriteableWALRecord>();
    try (var wal = createWAL("encryptedProof", key)) {
      expected.add(wal.read(wal.begin(), 1).getFirst());
      var payload = record(10000, 57);
      wal.log(payload);
      expected.add(payload);
    }
    try (var wal = createWAL("encryptedProof", key)) {
      var batch = wal.openProofReader(wal.begin(), 20000).next(10);
      assertEquals(ProofStatus.REACHED_END, batch.status());
      assertSequence(expected, batch.records());
    }
    FileUtils.deleteRecursively(testDirectory.toFile());
    expected.clear();
    try (var wal = createWAL("compressedProof")) {
      expected.add(wal.read(wal.begin(), 1).getFirst());
      var payload = new LifecycleTestRecord(new byte[10000]);
      assertTrue(WALRecordsFactory.toStream(payload).getShort(0) < 0);
      wal.log(payload);
      expected.add(payload);
    }
    try (var wal = createWAL("compressedProof")) {
      var batch = wal.openProofReader(wal.begin(), 20000).next(10);
      assertEquals(ProofStatus.REACHED_END, batch.status());
      assertSequence(expected, batch.records());
      batch = wal.openProofReader(wal.begin(), 1000).next(10);
      assertEquals(ProofStatus.RECORD_TOO_LARGE, batch.status());
      assertSequence(expected.subList(0, 1), batch.records());
    }
  }

  // A checksum-valid record with a corrupt nested length must fail before allocation. The
  // same reader repeats INVALID_RECORD, rather than advancing past the failed decode to end.
  @Test
  public void proofRejectsNestedMetadataAndTransactionLengthsAndLatchesFailure() throws Exception {
    for (boolean transaction : new boolean[] {false, true}) {
      for (int nestedLength : new int[] {-1, Integer.MAX_VALUE, 16 * 1024 * 1024}) {
        FileUtils.deleteRecursively(testDirectory.toFile());
        WriteableWALRecord payload = transaction
            ? new HighLevelTransactionChangeRecord(7, new byte[8])
            : new MetaDataRecord(new byte[8]);
        WriteableWALRecord first;
        try (var wal = createWAL("nestedProof")) {
          first = wal.read(wal.begin(), 1).getFirst();
          wal.log(payload);
        }
        patchRecordInt(payload.getLsn(), Integer.BYTES + 6 + (transaction ? Long.BYTES : 0),
            nestedLength);
        try (var wal = createWAL("nestedProof")) {
          var reader = wal.openProofReader(wal.begin(), 100);
          var batch = reader.next(10);
          assertEquals(ProofStatus.INVALID_RECORD, batch.status());
          assertEquals(payload.getLsn(), batch.position());
          assertSequence(List.of(first), batch.records());
          assertEquals(ProofStatus.INVALID_RECORD, reader.next(10).status());
          assertEquals(payload.getLsn(), reader.next(10).position());
          assertTrue(reader.next(10).records().isEmpty());
        }
      }
    }
  }

  private void patchRecordInt(LogSequenceNumber lsn, int relativeOffset, int value)
      throws IOException {
    byte[] content = Files.readAllBytes(segmentPath(lsn.getSegment()));
    var buffer = ByteBuffer.wrap(content).order(ByteOrder.nativeOrder());
    buffer.putInt(lsn.getPosition() + relativeOffset, value);
    int pageStart = lsn.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE * CASWALPage.DEFAULT_PAGE_SIZE;
    buffer.limit(pageStart + buffer.getShort(pageStart + CASWALPage.PAGE_SIZE_OFFSET));
    buffer.position(pageStart + CASWALPage.RECORDS_OFFSET);
    buffer.putLong(pageStart + CASWALPage.XX_OFFSET,
        XXHashFactory.fastestJavaInstance().hash64().hash(buffer, 0x9747b28cL));
    Files.write(segmentPath(lsn.getSegment()), content);
  }

  // Each remaining in-package allocation guard rejects negative or out-of-content lengths.
  // Valid empty and non-empty records still round-trip through the same decoder.
  @Test
  public void walPackageDecodersValidateNestedLengthsBeforeAllocation() {
    var metadata = new AtomicUnitStartMetadataRecord(true, 7, new byte[] {1, 2});
    var file = new FileCreatedWALRecord(7, "file", 9);
    var end = new AtomicUnitEndRecord(7, false, Map.of());
    for (int length : new int[] {-1, Integer.MAX_VALUE, 16 * 1024 * 1024}) {
      var metadataBytes = serializedBytes(metadata);
      ByteBuffer.wrap(metadataBytes).order(ByteOrder.nativeOrder()).putInt(15, length);
      assertThrows(IllegalArgumentException.class,
          () -> new AtomicUnitStartMetadataRecord().fromStream(metadataBytes, 6));
      var fileBytes = serializedBytes(file);
      ByteBuffer.wrap(fileBytes).order(ByteOrder.nativeOrder()).putInt(14, length);
      assertThrows(IllegalArgumentException.class,
          () -> WALRecordsFactory.INSTANCE.fromStream(fileBytes));
      var endBytes = new byte[20];
      System.arraycopy(serializedBytes(end), 0, endBytes, 0, 16);
      endBytes[15] = 1;
      ByteBuffer.wrap(endBytes).order(ByteOrder.nativeOrder()).putInt(16, length);
      assertThrows(IllegalArgumentException.class,
          () -> WALRecordsFactory.INSTANCE.fromStream(endBytes));
    }
    for (short count : new short[] {-1, 1, Short.MAX_VALUE}) {
      var buffer = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).putShort(count).rewind();
      assertThrows(IllegalArgumentException.class,
          () -> new WALPageChangesPortion().fromStream(buffer));
    }
    assertSequence(List.of(metadata, file, end), List.of(
        deserializeAtomicMetadata(serializedBytes(metadata)),
        WALRecordsFactory.INSTANCE.fromStream(serializedBytes(file)),
        WALRecordsFactory.INSTANCE.fromStream(serializedBytes(end))));
    new WALPageChangesPortion().fromStream(ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()));
  }

  private static AtomicUnitStartMetadataRecord deserializeAtomicMetadata(byte[] content) {
    var record = new AtomicUnitStartMetadataRecord();
    record.fromStream(content, 6);
    return record;
  }

  private static byte[] serializedBytes(WriteableWALRecord record) {
    var buffer = WALRecordsFactory.toStream(record).rewind();
    var bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  // A decoder Error propagates after terminal failure is latched. Use a deterministic injected
  // Error in the registered fixture decoder, rather than exhausting the test JVM's heap.
  @Test
  public void proofLatchesDecoderErrorBeforePropagatingAndNeverCertifiesEnd() throws Exception {
    var expected = proofFixture(1, 0);
    try (var wal = createWAL("proof")) {
      var reader = wal.openProofReader(wal.begin(), 100);
      LifecycleTestRecord.decodeError.set(true);
      try {
        assertThrows(AssertionError.class, () -> reader.next(10));
      } finally {
        LifecycleTestRecord.decodeError.remove();
      }
      var batch = reader.next(10);
      assertEquals(ProofStatus.INVALID_RECORD, batch.status());
      assertEquals(expected.get(1).getLsn(), batch.position());
      assertTrue(batch.records().isEmpty());
      assertEquals(ProofStatus.INVALID_RECORD, reader.next(10).status());
    }
  }

  // Every encrypted short physical page is broken before decrypt. Lookahead certifies whether
  // another readable segment follows, and terminal calls repeat the exact result.
  @Test
  public void proofClassifiesEncryptedTornPagesWithAndWithoutReadableSuffix() throws Exception {
    var key = new byte[16];
    for (int physicalBytes : new int[] {22, 100, 4095}) {
      for (boolean laterSegment : new boolean[] {false, true}) {
        FileUtils.deleteRecursively(testDirectory.toFile());
        WriteableWALRecord first;
        try (var wal = createWAL("encryptedTornProof", key)) {
          first = wal.read(wal.begin(), 1).getFirst();
          wal.log(record(4500, 57));
          if (laterSegment) {
            wal.appendNewSegment();
          }
        }
        long brokenStart = Files.size(segmentPath(1)) - CASWALPage.DEFAULT_PAGE_SIZE;
        try (var file = FileChannel.open(segmentPath(1), StandardOpenOption.WRITE)) {
          file.truncate(brokenStart + physicalBytes);
        }
        try (var wal = createWAL("encryptedTornProof", key)) {
          var reader = wal.openProofReader(wal.begin(), 20000);
          var batch = reader.next(10);
          assertEquals(ProofStatus.BROKEN_PAGE, batch.status());
          assertEquals(new LogSequenceNumber(1, (int) brokenStart), batch.position());
          assertEquals(laterSegment, batch.readablePageFollows());
          assertNull(batch.error());
          assertSequence(List.of(first), batch.records());
          batch = reader.next(10);
          assertEquals(ProofStatus.BROKEN_PAGE, batch.status());
          assertEquals(new LogSequenceNumber(1, (int) brokenStart), batch.position());
          assertEquals(laterSegment, batch.readablePageFollows());
          assertNull(batch.error());
          assertTrue(batch.records().isEmpty());
        }
      }
    }
  }

  // Lookahead cannot publish BROKEN_PAGE with a false suffix flag if reading that suffix fails.
  // A missing file and a shrunk file both latch terminal failure on repeated calls.
  @Test
  public void proofLatchesLookaheadIoFailures() throws Exception {
    for (boolean remove : new boolean[] {false, true}) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      proofFixture(2, 4500);
      breakPage(1, 2);
      try (var wal = createWAL("proof")) {
        var reader = wal.openProofReader(wal.begin(), 20000);
        if (remove) {
          Files.delete(segmentPath(2));
        } else {
          try (var file = FileChannel.open(segmentPath(2), StandardOpenOption.WRITE)) {
            file.truncate(0);
          }
        }
        var expected = remove ? ProofStatus.SEGMENT_VANISHED : ProofStatus.IO_ERROR;
        var batch = reader.next(10);
        assertEquals(expected, batch.status());
        assertEquals(new LogSequenceNumber(2, 0), batch.position());
        assertNotNull(batch.error());
        assertEquals(expected, reader.next(10).status());
        assertEquals(batch.error(), reader.next(10).error());
        assertTrue(reader.next(10).records().isEmpty());
      }
    }
  }

  // Admission failures are not damaged-page evidence. A missing key in the initial read or in
  // lookahead propagates once and leaves IO_ERROR latched with the original cause.
  @Test
  public void proofLatchesMissingEncryptionKeyDuringReadAndLookahead() throws Exception {
    var key = new byte[16];
    for (boolean lookahead : new boolean[] {false, true}) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      try (var wal = createWAL("admissionProof", lookahead ? null : key)) {
        wal.log(record(4500, 7));
        if (lookahead) {
          wal.flush();
          setEncryptionField(wal, "aesKey", key);
          setEncryptionField(wal, "iv", new byte[16]);
          wal.appendNewSegment();
        }
      }
      if (lookahead) {
        breakPage(1, 2);
      }
      try (var wal = createWAL("admissionProof")) {
        var reader = wal.openProofReader(wal.begin(), 20000);
        var failure = assertThrows(EncryptionKeyAbsentException.class, () -> reader.next(10));
        var batch = reader.next(10);
        assertEquals(ProofStatus.IO_ERROR, batch.status());
        assertEquals(failure, batch.error().getCause());
        assertTrue(batch.records().isEmpty());
        assertEquals(ProofStatus.IO_ERROR, reader.next(10).status());
      }
    }
  }

  // An invalid IV must propagate rather than masquerade as a broken page. Inject it only after
  // constructor flush, so the proof reader, not the writer, reaches the admission failure.
  @Test
  public void proofPropagatesInvalidIvAndLatchesFailure() throws Exception {
    var key = new byte[16];
    try (var wal = createWAL("invalidIvProof", key)) {
      wal.log(record(8, 7));
    }
    try (var wal = createWAL("invalidIvProof", key)) {
      setEncryptionField(wal, "iv", new byte[17]);
      var reader = wal.openProofReader(wal.begin(), 100);
      var failure = assertThrows(IllegalArgumentException.class, () -> reader.next(10));
      assertEquals("Invalid IV.", failure.getMessage());
      var batch = reader.next(10);
      assertEquals(ProofStatus.IO_ERROR, batch.status());
      assertEquals(failure, batch.error().getCause());
      assertTrue(batch.records().isEmpty());
      assertEquals(ProofStatus.IO_ERROR, reader.next(10).status());
      // Restore the writer's valid configuration before close.
      setEncryptionField(wal, "iv", new byte[16]);
    }
  }

  private static void setEncryptionField(CASDiskWriteAheadLog wal, String name, byte[] value)
      throws ReflectiveOperationException {
    var field = CASDiskWriteAheadLog.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(wal, value);
  }

  // A decoded logical page length below the header is malformed page data, unlike an IV error.
  @Test
  public void proofTreatsLogicalPageSizeBelowHeaderAsBroken() throws Exception {
    proofFixture(1, 0);
    try (var file = FileChannel.open(segmentPath(1), StandardOpenOption.WRITE)) {
      var size =
          ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).putShort((short) 21).rewind();
      file.write(size, CASWALPage.PAGE_SIZE_OFFSET);
    }
    try (var wal = createWAL("proof")) {
      var batch = wal.openProofReader(wal.begin(), 100).next(10);
      assertEquals(ProofStatus.BROKEN_PAGE, batch.status());
      assertEquals(new LogSequenceNumber(1, 0), batch.position());
      assertTrue(batch.readablePageFollows());
    }
  }

  // Canonical and padded names with one numeric ID make the original inventory ambiguous,
  // even if their bytes match. Neither enumeration order nor coverage can yield a proof batch.
  // Legacy reads still use the canonical file, whether it is broken or the alias is broken.
  @Test
  public void proofRejectsDuplicateNumericAliasesBeforeReadingAndLatchesIoError()
      throws Exception {
    for (int scenario = 0; scenario < 3; scenario++) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      var expected = proofFixture(2, 0);
      var alias = testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + ".01.wal");
      Files.copy(segmentPath(1), alias);
      if (scenario == 0) {
        breakPage(1, 0);
      } else if (scenario == 1) {
        try (var file = FileChannel.open(alias, StandardOpenOption.WRITE)) {
          file.write(ByteBuffer.wrap(new byte[Long.BYTES]), 0);
        }
      } else {
        Files.copy(segmentPath(1),
            testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + ".001.wal"));
      }
      try (var wal = createWAL("proof")) {
        assertEquals(3, wal.activeSegment());
        assertEquals(List.of(1L, 2L),
            wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
        assertSequence(scenario == 0 ? List.of() : expected.subList(0, 2),
            wal.read(wal.begin(), 2));
        for (long coverageSegment : new long[] {1, 2}) {
          var reader = wal.openProofReader(wal.begin(coverageSegment), 100);
          var batch = reader.next(1);
          assertEquals(ProofStatus.IO_ERROR, batch.status());
          assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), batch.position());
          assertTrue(batch.records().isEmpty());
          assertFalse(batch.readablePageFollows());
          assertNotNull(batch.error());
          assertEquals("Multiple WAL paths map to segment 1", batch.error().getMessage());
          var repeated = reader.next(10);
          assertEquals(batch, repeated);
        }
      }
    }
  }

  // With one canonical file, proof and legacy readers decode the same serialized records.
  // Breaking that file before reopen stops both readers at page zero, not at a clean alias.
  @Test
  public void proofReadsTheSingleCanonicalFileUsedByLegacyReads() throws Exception {
    for (boolean broken : new boolean[] {false, true}) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      var expected = proofFixture(1, 0);
      if (broken) {
        breakPage(1, 0);
      }
      try (var wal = createWAL("proof")) {
        var batch = wal.openProofReader(wal.begin(), 100).next(10);
        assertEquals(broken ? ProofStatus.BROKEN_PAGE : ProofStatus.REACHED_END, batch.status());
        assertSequence(broken ? List.of() : expected, batch.records());
        assertSequence(batch.records(), wal.read(wal.begin(), expected.size()));
      }
    }
  }

  // Padded names remain admitted, but missing canonical recovery bytes invalidate the whole
  // proof before any prefix records are emitted. A failed capture keeps the admitted ID.
  @Test
  public void proofRejectsMissingCanonicalSegmentWithoutBreakingNonCanonicalAdmission()
      throws Exception {
    var expected = proofFixture(2, 0);
    Files.move(segmentPath(2),
        testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + ".02.wal"));
    try (var wal = createWAL("proof")) {
      assertEquals(List.of(1L, 2L),
          wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
      assertEquals(0, wal.preOpenExtent().segments().get(1).bytes());
      assertEquals(3, wal.activeSegment());
      assertSequence(expected.subList(0, 2), wal.read(wal.begin(), 100));
      assertMissingCanonical(wal, 1, 2);
    }
  }

  private static void assertMissingCanonical(CASDiskWriteAheadLog wal, long coverage,
      long missing) {
    var reader = wal.openProofReader(new LogSequenceNumber(coverage, CASWALPage.RECORDS_OFFSET),
        20000);
    var batch = reader.next(1);
    assertEquals(ProofStatus.MISSING_SEGMENT, batch.status());
    assertEquals(new LogSequenceNumber(missing, CASWALPage.RECORDS_OFFSET), batch.position());
    assertTrue(batch.records().isEmpty());
    assertFalse(batch.readablePageFollows());
    assertTrue(batch.error() instanceof NoSuchFileException);
    assertEquals(batch, reader.next(10));
  }

  // Healthy and broken single aliases must never supply proof bytes. Unfiltered enumeration
  // also admits foreign and empty basenames, without changing canonical recovery lookup.
  @Test
  public void proofRejectsSingleAliasesWithFilteredAndUnfilteredEnumeration() throws Exception {
    var base = ContextConfiguration.WAL_DEFAULT_NAME;
    for (var name : List.of(base + ".01.wal", base + ".1.extra.1.wal",
        base + ".+1.extra.1.wal", "other.1.wal", ".1.wal", "other.1.extra.1.wal")) {
      for (boolean broken : new boolean[] {false, true}) {
        FileUtils.deleteRecursively(testDirectory.toFile());
        proofFixture(1, 0);
        if (broken) {
          breakPage(1, 0);
          breakPage(1, 1);
        }
        Files.move(segmentPath(1), testDirectory.resolve(name));
        try (var wal = createWAL("proof", null, name.startsWith(base))) {
          assertEquals(2, wal.activeSegment());
          assertEquals(List.of(1L),
              wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
          assertTrue(wal.read(wal.begin(), 10).isEmpty());
          assertMissingCanonical(wal, 1, 1);
        }
      }
    }
  }

  // Detect actual canonical lookup, not the host OS. Case variants are proof-readable only
  // when that lookup reaches the same file. Recovery and proof then return identical bytes.
  @Test
  public void proofUsesCanonicalLookupForCaseVariantOnTheActualFilesystem() throws Exception {
    var expected = proofFixture(1, 0);
    var alias = testDirectory.resolve(
        ContextConfiguration.WAL_DEFAULT_NAME.toUpperCase(Locale.US) + ".1.wal");
    Files.move(segmentPath(1), alias);
    boolean resolves = Files.exists(segmentPath(1));
    if (resolves) {
      assertTrue(Files.isSameFile(segmentPath(1), alias));
    }
    try (var wal = createWAL("proof")) {
      assertEquals(2, wal.activeSegment());
      if (resolves) {
        var batch = wal.openProofReader(wal.begin(), 100).next(10);
        assertEquals(ProofStatus.REACHED_END, batch.status());
        assertSequence(expected, batch.records());
        assertSequence(batch.records(), wal.read(wal.begin(), expected.size()));
      } else {
        assertTrue(wal.read(wal.begin(), 10).isEmpty());
        assertMissingCanonical(wal, 1, 1);
      }
    }
  }

  // A zero-byte or readable alias after a broken canonical segment is not broken-tail evidence.
  // Global capture validation must report the absent canonical suffix before lookahead.
  @Test
  public void proofRejectsAliasSuffixEvenWhenBrokenPageLookaheadWouldSkipIt() throws Exception {
    for (boolean empty : new boolean[] {false, true}) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      proofFixture(2, 0);
      breakPage(1, 0);
      if (empty) {
        Files.write(segmentPath(2), new byte[0]);
      }
      Files.move(segmentPath(2),
          testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + ".02.wal"));
      try (var wal = createWAL("proof")) {
        assertTrue(wal.read(wal.begin(), 10).isEmpty());
        assertMissingCanonical(wal, 1, 2);
      }
    }
  }

  // Recovery from begin is blocked by an alias below coverage. A later canonical file does
  // not repair the captured failure, for an existing reader or a newly created reader.
  @Test
  public void proofKeepsMissingCanonicalFailureBelowCoverageWhenCanonicalFileAppearsLater()
      throws Exception {
    var expected = proofFixture(2, 0);
    var alias = testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + ".01.wal");
    Files.move(segmentPath(1), alias);
    try (var wal = createWAL("proof")) {
      assertTrue(wal.read(wal.begin(), 10).isEmpty());
      assertSequence(expected.subList(2, 4), wal.read(wal.begin(2), 2));
      assertMissingCanonical(wal, 2, 1);
      var reader = wal.openProofReader(wal.begin(2), 100);
      var before = reader.next(10);
      Files.copy(alias, segmentPath(1));
      assertSequence(expected.subList(0, 2), wal.read(wal.begin(), 2));
      assertEquals(before, reader.next(10));
      assertMissingCanonical(wal, 1, 1);
      assertMissingCanonical(wal, 2, 1);
    }
  }

  // Cut and delete target canonical names only. Aliases survive both operations and cannot
  // become proof-readable after the live ID is cut or after the WAL is deleted and reopened.
  @Test
  public void proofRejectsAliasesSurvivingCanonicalCutAndDelete() throws Exception {
    for (boolean cut : new boolean[] {false, true}) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      proofFixture(2, 0);
      var alias = testDirectory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + ".01.wal");
      Files.move(segmentPath(1), alias);
      var wal = createWAL("proof");
      boolean deleted = false;
      try {
        if (cut) {
          assertTrue(wal.preflightCut(2).removesSegments());
          assertFalse(wal.cutTill(wal.begin(2)));
          assertTrue(Files.exists(alias));
          assertTrue(wal.read(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), 10).isEmpty());
        }
        assertMissingCanonical(wal, 2, 1);
        wal.delete();
        deleted = true;
      } finally {
        if (!deleted) {
          wal.close();
        }
      }
      assertTrue(Files.exists(alias));
      try (var reopened = createWAL("proof")) {
        assertMissingCanonical(reopened, 1, 1);
      }
    }
  }

  // A long-ID canonical filename is excluded by legacy Integer validation, while an extra-
  // component alias admits its ID. Proof content, bounds and broken-page lookahead must all
  // come from the canonical file, whether the alias is shorter, longer or healthy instead.
  @Test
  public void proofUsesCanonicalContentsAndBoundsWhenExcludedCanonicalAndAliasLengthsDiffer()
      throws Exception {
    long segment = (long) Integer.MAX_VALUE + 1;
    var shortExpected = proofFixture(1, 0);
    var shortBytes = Files.readAllBytes(segmentPath(1));
    FileUtils.deleteRecursively(testDirectory.toFile());
    var longExpected = proofFixture(1, 4500);
    var longBytes = Files.readAllBytes(segmentPath(1));
    assertTrue(longBytes.length > shortBytes.length);
    for (var record : shortExpected) {
      record.setLsn(new LogSequenceNumber(segment, record.getLsn().getPosition()));
    }
    for (var record : longExpected) {
      record.setLsn(new LogSequenceNumber(segment, record.getLsn().getPosition()));
    }
    for (int scenario = 0; scenario < 3; scenario++) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      Files.createDirectories(testDirectory);
      var canonical = scenario == 0 ? longBytes : shortBytes;
      Files.write(segmentPath(segment), canonical);
      var alias = testDirectory.resolve(
          ContextConfiguration.WAL_DEFAULT_NAME + ".1.extra." + segment + ".wal");
      Files.write(alias, scenario == 0 ? shortBytes : longBytes);
      if (scenario == 2) {
        for (int page = 0; page * CASWALPage.DEFAULT_PAGE_SIZE < canonical.length; page++) {
          breakPage(segment, page);
        }
      }
      try (var wal = createWAL("proof")) {
        assertEquals(segment + 1, wal.activeSegment());
        assertEquals(List.of(segment),
            wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
        assertEquals(canonical.length, wal.preOpenExtent().segments().getFirst().bytes());
        var batch = wal.openProofReader(wal.begin(), 20000).next(10);
        assertEquals(scenario == 2 ? ProofStatus.BROKEN_PAGE : ProofStatus.REACHED_END,
            batch.status());
        assertFalse(batch.readablePageFollows());
        var expected = scenario == 2 ? List.<WriteableWALRecord>of()
            : scenario == 0 ? longExpected : shortExpected;
        assertSequence(expected, batch.records());
        assertSequence(batch.records(), wal.read(wal.begin(), Math.max(1, expected.size())));
      }
    }
  }

  // A canonical FIFO stays admitted, but neither construction nor proof reading may wait
  // for a FIFO partner. Replacing it with a regular file cannot repair the capture failure.
  @Test(timeout = 10000)
  public void proofRejectsCanonicalFifoWithoutBlockingConstructionOrReading() throws Exception {
    Files.createDirectories(testDirectory);
    Process mkfifo;
    try {
      mkfifo = new ProcessBuilder("mkfifo", segmentPath(1).toString()).start();
    } catch (IOException | java.lang.SecurityException unsupported) {
      org.junit.Assume.assumeNoException("Platform must support mkfifo", unsupported);
      return;
    }
    try {
      org.junit.Assume.assumeTrue("mkfifo must finish successfully",
          mkfifo.waitFor(5, TimeUnit.SECONDS) && mkfifo.exitValue() == 0);
    } finally {
      mkfifo.destroyForcibly();
    }
    assertNonRegularCaptureFailure();
  }

  // Filename-only admission includes canonical directories. Proof capture must reject the
  // directory with the same sticky IO_ERROR as a FIFO, without changing the next segment ID.
  @Test
  public void proofRejectsCanonicalDirectoryWithoutChangingConstructorAdmission() throws Exception {
    Files.createDirectories(segmentPath(1));
    assertNonRegularCaptureFailure();
  }

  private void assertNonRegularCaptureFailure() throws Exception {
    try (var wal = createWAL("proof")) {
      assertEquals(2, wal.activeSegment());
      assertEquals(List.of(1L),
          wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
      assertEquals(0, wal.preOpenExtent().segments().getFirst().bytes());
      var reader = wal.openProofReader(wal.begin(), 100);
      var batch = reader.next(10);
      assertEquals(ProofStatus.IO_ERROR, batch.status());
      assertEquals(new LogSequenceNumber(1, CASWALPage.RECORDS_OFFSET), batch.position());
      assertTrue(batch.records().isEmpty());
      assertFalse(batch.readablePageFollows());
      assertNotNull(batch.error());
      assertEquals("WAL proof segment 1 is not a regular file: " + segmentPath(1),
          batch.error().getMessage());
      assertEquals(batch, reader.next(10));
      // Global validation also rejects the non-regular segment below requested coverage.
      assertEquals(batch, wal.openProofReader(wal.begin(2), 100).next(10));
      Files.delete(segmentPath(1));
      Files.write(segmentPath(1), new byte[0]);
      assertEquals(batch, reader.next(10));
      assertEquals(batch, wal.openProofReader(wal.begin(), 100).next(10));
    }
  }

  // A canonical symlink loop fails capture with IO_ERROR, without a constructor exception.
  // Removing the loop cannot repair that immutable failure. No proof record is emitted.
  @Test
  public void proofLatchesCanonicalCaptureIoFailureWithoutRejectingConstructorAdmission()
      throws Exception {
    proofFixture(1, 0);
    long segment = (long) Integer.MAX_VALUE + 1;
    var canonical = segmentPath(segment);
    var alias = testDirectory.resolve(
        ContextConfiguration.WAL_DEFAULT_NAME + ".1.extra." + segment + ".wal");
    Files.move(segmentPath(1), alias);
    try {
      Files.createSymbolicLink(canonical, canonical.getFileName());
    } catch (UnsupportedOperationException | IOException unsupported) {
      org.junit.Assume.assumeNoException("Filesystem must support symbolic links", unsupported);
    }
    try (var wal = createWAL("proof")) {
      assertEquals(segment + 1, wal.activeSegment());
      var reader = wal.openProofReader(wal.begin(), 100);
      var batch = reader.next(10);
      assertEquals(ProofStatus.IO_ERROR, batch.status());
      assertEquals(new LogSequenceNumber(segment, CASWALPage.RECORDS_OFFSET), batch.position());
      assertTrue(batch.records().isEmpty());
      assertFalse(batch.readablePageFollows());
      assertNotNull(batch.error());
      assertFalse(batch.error() instanceof NoSuchFileException);
      Files.delete(canonical);
      Files.copy(alias, canonical);
      assertEquals(batch, reader.next(10));
      assertEquals(batch, wal.openProofReader(wal.begin(), 100).next(10));
    }
  }

  // An empty captured segment is a broken page at its own start, not an incomplete record at
  // the previous segment's end. A later readable segment is included in its suffix decision.
  @Test
  public void proofReportsZeroByteSegmentsAtTheirStartAndChecksLaterSegments() throws Exception {
    for (boolean laterSegment : new boolean[] {false, true}) {
      FileUtils.deleteRecursively(testDirectory.toFile());
      var expected = proofFixture(laterSegment ? 3 : 2, 0);
      try (var file = FileChannel.open(segmentPath(2), StandardOpenOption.WRITE)) {
        file.truncate(0);
      }
      try (var wal = createWAL("proof")) {
        var reader = wal.openProofReader(wal.begin(), 100);
        var batch = reader.next(10);
        assertEquals(ProofStatus.BROKEN_PAGE, batch.status());
        assertEquals(new LogSequenceNumber(2, 0), batch.position());
        assertEquals(laterSegment, batch.readablePageFollows());
        assertSequence(expected.subList(0, 2), batch.records());
        assertEquals(ProofStatus.BROKEN_PAGE, reader.next(10).status());
        assertEquals(batch.position(), reader.next(10).position());
        assertEquals(laterSegment, reader.next(10).readablePageFollows());
        batch =
            wal.openProofReader(new LogSequenceNumber(2, CASWALPage.RECORDS_OFFSET), 100).next(10);
        assertEquals(ProofStatus.BROKEN_PAGE, batch.status());
        assertEquals(new LogSequenceNumber(2, 0), batch.position());
        assertEquals(laterSegment, batch.readablePageFollows());
        assertTrue(batch.records().isEmpty());
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Inner test record type
  // ---------------------------------------------------------------------------

  /**
   * A minimal WAL record for lifecycle tests. Carries a raw byte payload that is serialized
   * and deserialized in a straightforward length-prefix encoding, matching the pattern used
   * by {@code CASDiskWriteAheadLogCloseTest.SmallTestRecord}.
   */
  public static final class LifecycleTestRecord extends AbstractWALRecord {

    byte[] data;
    private static final ThreadLocal<Boolean> decodeError = new ThreadLocal<>();

    @SuppressWarnings("unused") // required by WALRecordsFactory zero-arg instantiation
    public LifecycleTestRecord() {
    }

    LifecycleTestRecord(byte[] data) {
      this.data = data;
    }

    @Override
    public int toStream(byte[] content, int offset) {
      IntegerSerializer.serializeNative(data.length, content, offset);
      offset += IntegerSerializer.INT_SIZE;
      System.arraycopy(data, 0, content, offset, data.length);
      return offset + data.length;
    }

    @Override
    public void toStream(ByteBuffer buffer) {
      buffer.putInt(data.length);
      buffer.put(data);
    }

    @Override
    public int fromStream(byte[] content, int offset) {
      if (Boolean.TRUE.equals(decodeError.get())) {
        throw new AssertionError("Injected decoder failure");
      }
      final var len = IntegerSerializer.deserializeNative(content, offset);
      offset += IntegerSerializer.INT_SIZE;
      data = new byte[len];
      System.arraycopy(content, offset, data, 0, len);
      return offset + len;
    }

    @Override
    public int serializedSize() {
      return data.length + IntegerSerializer.INT_SIZE;
    }

    @Override
    public int getId() {
      return LIFECYCLE_TEST_RECORD_ID;
    }
  }
}
