package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CheckpointRequestListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AbstractWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
   * Test-only WAL record ID from the coverage-suite reserved range {@code [460, 510]}
   * (see {@code CoverageTestWALRecordIds}).  Kept clear of ID 500 ({@code SmallTestRecord}
   * in {@code CASDiskWriteAheadLogCloseTest}) and ID 511 ({@code CASDiskWriteAheadLogIT}).
   */
  private static final int LIFECYCLE_TEST_RECORD_ID = 460;

  private static Path testDirectory;

  @BeforeClass
  public static void beforeClass() {
    testDirectory =
        Paths.get(
            System.getProperty("buildDirectory", "." + File.separator + "target"),
            "casWALLifecycleTest");

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
    return new CASDiskWriteAheadLog(
        storageName,
        testDirectory,
        testDirectory,
        ContextConfiguration.WAL_DEFAULT_NAME,
        100, // maxPagesCacheSize
        64, // bufferSize (MB)
        null, // no AES key
        null, // no IV
        Integer.MAX_VALUE, // segmentsInterval
        Integer.MAX_VALUE, // maxSegmentSize
        20, // commitDelay (ms)
        true, // filterWALFiles
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
      // Pin the exactly-once contract — countDown past zero is a no-op so a duplicate fire
      // would otherwise be invisible to the latch.
      assertEquals("addEventAt must fire the runnable exactly once", 1, callCount.get());
    } finally {
      wal.close();
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
      // The event must have been fired exactly once.
      assertEquals(1, callCount.get());
    } finally {
      wal.close();
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
