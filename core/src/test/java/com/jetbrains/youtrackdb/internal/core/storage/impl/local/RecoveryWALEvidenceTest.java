package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache.composeFileId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartMetadataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageAllocatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RecoveryWALEvidenceTest {
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private Path walPath;

  private CASDiskWriteAheadLog open() throws Exception {
    Path path = walPath == null ? folder.getRoot().toPath() : walPath;
    return new CASDiskWriteAheadLog("evidence", path, path,
        ContextConfiguration.WAL_DEFAULT_NAME, 100, 64, null, null,
        Integer.MAX_VALUE, Integer.MAX_VALUE, 20, true, Locale.US, -1, 1000,
        false, false, false, 10);
  }

  private RecoveryWALEvidence collect(WriteAheadLog wal, LogSequenceNumber start)
      throws Exception {
    return RecoveryWALEvidence.collect(wal, start, true, Integer.MAX_VALUE);
  }

  private void begin(WriteAheadLog wal, long id) throws Exception {
    wal.log(new AtomicUnitStartRecord(false, id));
  }

  private LogSequenceNumber end(WriteAheadLog wal, long id) throws Exception {
    return wal.log(new AtomicUnitEndRecord(id, false, null));
  }

  /** Earlier units and late allocation declarations contribute globally, with normalized ids. */
  @Test
  public void allocationsAreWholeRangeAndIndependentOfPageChangeOrder() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber finish;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, composeFileId(7, 11), 1));
      end(wal, 1);
      begin(wal, 2);
      wal.log(new UpdatePageRecord(0, composeFileId(8, 12), 2,
          new WALPageChangesPortion(), new LogSequenceNumber(0, 0)));
      wal.log(new PageAllocatedWALRecord(3, 13, 2));
      wal.log(new PageAllocatedWALRecord(0, composeFileId(9, 12), 2));
      finish = end(wal, 2);
    }
    try (var wal = open()) {
      var evidence = collect(wal, start);
      assertEquals(Set.of(11, 12), evidence.pageZeroFiles());
      assertEquals(start, evidence.start());
      assertEquals(finish, evidence.readableEnd());
      assertTrue(evidence.deletions().isEmpty());
      assertTrue(evidence.partialLeadingUnits().isEmpty());
      assertThrows(UnsupportedOperationException.class, () -> evidence.pageZeroFiles().add(99));
    }
  }

  /** Deletions publish at unit end, retain their own position, and latest normalized id wins. */
  @Test
  public void committedDeletionsWinButIncompleteDropsDoNot() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber latest;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 11, 1));
      wal.log(new FileDeletedWALRecord(1, composeFileId(7, 11)));
      end(wal, 1);
      begin(wal, 2);
      wal.log(new FileDeletedWALRecord(2, 11));
      latest = wal.log(new FileDeletedWALRecord(2, composeFileId(8, 11)));
      end(wal, 2);
      begin(wal, 3);
      wal.log(new FileDeletedWALRecord(3, 11));
      wal.log(new FileDeletedWALRecord(3, 12));
      wal.log(new PageAllocatedWALRecord(0, 12, 3));
    }
    try (var wal = open()) {
      var evidence = collect(wal, start);
      assertEquals(Set.of(11), evidence.pageZeroFiles());
      assertEquals(Map.of(11, latest), evidence.deletions());

    }
  }

  /** A missing leading start disqualifies allocation, not committed deletion. */
  @Test
  public void partialLeadingUnitsExcludeUnavailableAllocationEvidence() throws Exception {
    LogSequenceNumber allocation;
    LogSequenceNumber deletion;
    LogSequenceNumber finish;
    try (var wal = open()) {
      begin(wal, 1);
      wal.log(new PageAllocatedWALRecord(0, 10, 1));
      allocation = wal.log(new PageAllocatedWALRecord(0, 11, 1));
      deletion = wal.log(new FileDeletedWALRecord(1, 11));
      finish = end(wal, 1);
    }
    try (var wal = open()) {
      var evidence = RecoveryWALEvidence.collect(wal, allocation, true, Integer.MAX_VALUE);
      assertTrue(evidence.pageZeroFiles().isEmpty());
      assertEquals(Map.of(11, deletion), evidence.deletions());
      assertEquals(Set.of(1L), evidence.partialLeadingUnits());
      assertEquals(finish, evidence.readableEnd());
    }
  }

  private Path segment(LogSequenceNumber position) {
    return (walPath == null ? folder.getRoot().toPath() : walPath).resolve(
        ContextConfiguration.WAL_DEFAULT_NAME + "." + position.getSegment() + ".wal");
  }

  /** A broken final page is accepted only after unclean shutdown, never with a readable suffix. */
  @Test
  public void tornTailAndInteriorBrokenPageHaveDifferentOutcomes() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber finish;
    LogSequenceNumber broken;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 11, 1));
      finish = end(wal, 1);
      wal.appendNewSegment();
      broken = wal.log(new AtomicUnitStartRecord(false, 2));
    }
    byte[] bytes = Files.readAllBytes(segment(broken));
    bytes[0] ^= 1;
    Files.write(segment(broken), bytes);
    try (var wal = open()) {
      var evidence = collect(wal, start);
      assertEquals(Set.of(11), evidence.pageZeroFiles());
      assertEquals(finish, evidence.readableEnd());
      var cleanFailure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
          () -> RecoveryWALEvidence.collect(wal, start, false, Integer.MAX_VALUE));
      assertEquals(finish, cleanFailure.lastReadablePosition());
    }
    // A later readable segment turns the same broken page into an interior failure.
    try (var wal = open()) {
      begin(wal, 3);
      end(wal, 3);
    }
    try (var wal = open()) {
      var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
          () -> collect(wal, start));
      assertEquals(finish, failure.lastReadablePosition());
      assertNotNull(failure.failedPosition());
      assertNotNull(failure.getCause());
    }
  }

  /** Checksum-valid invalid payloads fail with the original decoder cause and exact valid prefix. */
  @Test
  public void decodeFailureKeepsCauseAndLastReadablePosition() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber finish;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 11, 1));
      finish = end(wal, 1);
      bad = wal.log(new AtomicUnitStartRecord(false, 2));
    }
    byte[] bytes = Files.readAllBytes(segment(bad));
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
    buffer.putShort(bad.getPosition() + Integer.BYTES, Short.MAX_VALUE);
    int pageStart = bad.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE * CASWALPage.DEFAULT_PAGE_SIZE;
    int length = buffer.getShort(pageStart + CASWALPage.PAGE_SIZE_OFFSET);
    buffer.position(pageStart + CASWALPage.RECORDS_OFFSET);
    buffer.limit(pageStart + length);
    buffer.putLong(pageStart + CASWALPage.XX_OFFSET,
        XXHashFactory.fastestJavaInstance().hash64().hash(buffer, 0x9747b28cL));
    Files.write(segment(bad), bytes);
    try (var wal = open()) {
      var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class, () -> collect(wal, start));
      assertEquals(finish, failure.lastReadablePosition());
      assertEquals(bad, failure.failedPosition());
      assertTrue(failure.getCause() instanceof IllegalStateException);
    }
  }

  /** The stand-in uses the proof API terminal I/O status and preserves the same cause object. */
  @Test
  public void ioFailureIsNeverTurnedIntoPartialSuccess() throws Exception {
    var wal = mock(WriteAheadLog.class);
    var reader = mock(WriteAheadLog.ProofReader.class);
    var start = new LogSequenceNumber(1, 32);
    var cause = new IOException("unreadable segment");
    when(wal.preOpenExtent()).thenReturn(new WriteAheadLog.PreOpenExtent(List.of()));
    when(wal.openSegmentProofReader(start, Integer.MAX_VALUE)).thenReturn(reader);
    when(reader.next(1)).thenReturn(new WriteAheadLog.ProofBatch(List.of(),
        WriteAheadLog.ProofStatus.IO_ERROR, start, false, cause));
    var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class, () -> collect(wal, start));
    assertSame(cause, failure.getCause());
    assertNull(failure.lastReadablePosition());
    assertEquals(start, failure.failedPosition());
  }

  /** Opening failures are checked failures too, with no fabricated readable position. */
  @Test
  public void readerOpeningFailureIsReported() {
    var wal = mock(WriteAheadLog.class);
    var cause = new IllegalStateException("reader unavailable");
    var start = new LogSequenceNumber(1, 32);
    when(wal.preOpenExtent()).thenReturn(new WriteAheadLog.PreOpenExtent(List.of()));
    when(wal.openSegmentProofReader(start, Integer.MAX_VALUE)).thenThrow(cause);
    var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class, () -> collect(wal, start));
    assertSame(cause, failure.getCause());
    assertNull(failure.lastReadablePosition());
  }

  /** Starting at an end record still identifies the ended partial unit, without file evidence. */
  @Test
  public void endOnlyRangeClassifiesPartialLeadingUnit() throws Exception {
    LogSequenceNumber finish;
    try (var wal = open()) {
      begin(wal, 1);
      wal.log(new PageAllocatedWALRecord(0, 11, 1));
      finish = end(wal, 1);
    }
    try (var wal = open()) {
      var evidence = collect(wal, finish);
      assertEquals(Set.of(1L), evidence.partialLeadingUnits());
      assertTrue(evidence.pageZeroFiles().isEmpty());
      assertTrue(evidence.deletions().isEmpty());
      assertEquals(finish, evidence.readableEnd());
    }
  }

  /** A batch's records cannot hide its terminal channel-close failure. */
  @Test
  public void recordsInFailedBatchNeverAuthorizeSuccess() throws Exception {
    var wal = mock(WriteAheadLog.class);
    var reader = mock(WriteAheadLog.ProofReader.class);
    var finish = new LogSequenceNumber(1, 32);
    var record = new AtomicUnitEndRecord(1, false, null);
    record.setLsn(finish);
    var cause = new IOException("channel close failed");
    when(wal.preOpenExtent()).thenReturn(new WriteAheadLog.PreOpenExtent(List.of()));
    when(wal.openSegmentProofReader(finish, Integer.MAX_VALUE)).thenReturn(reader);
    when(reader.next(1)).thenReturn(new WriteAheadLog.ProofBatch(List.of(record),
        WriteAheadLog.ProofStatus.IO_ERROR, finish, false, cause));
    var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class, () -> collect(wal, finish));
    assertSame(cause, failure.getCause());
    assertEquals(finish, failure.lastReadablePosition());
    assertEquals(finish, failure.failedPosition());
  }

  // The cut fixture retains a checksum-valid first page of a multi-page, incompressible record.
  // The other fixtures damage a page header or leave an existing segment at zero bytes.
  private LogSequenceNumber[] stopFixture(int kind) throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber finish;
    LogSequenceNumber broken;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 11, 1));
      finish = end(wal, 1);
      wal.appendNewSegment();
      if (kind == 1) {
        finish = wal.end();
      }
      byte[] metadata = new byte[CASWALPage.DEFAULT_PAGE_SIZE * 4];
      new Random(42).nextBytes(metadata);
      broken = wal.log(new AtomicUnitStartMetadataRecord(false, 2, metadata));
    }
    if (kind == 0) {
      byte[] bytes = Files.readAllBytes(segment(broken));
      bytes[0] ^= 1;
      Files.write(segment(broken), bytes);
    } else {
      try (var file = FileChannel.open(segment(broken), StandardOpenOption.WRITE)) {
        file.truncate(kind == 1 ? CASWALPage.DEFAULT_PAGE_SIZE : 0);
      }
    }
    return new LogSequenceNumber[] {start, finish, broken};
  }

  /** Repeated opens and recovery-flush markers do not turn a torn tail into interior damage. */
  @Test
  public void stopPointsAllowOnlyEmptyLaterSegmentsAfterUncleanShutdown() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      walPath = folder.newFolder().toPath();
      var positions = stopFixture(kind);
      // A damaged first page with later readable pages is deliberately not a torn tail.
      if (kind == 0) {
        try (var file = FileChannel.open(segment(positions[2]), StandardOpenOption.WRITE)) {
          file.truncate(CASWALPage.DEFAULT_PAGE_SIZE);
        }
      }
      for (int crash = 0; crash < 3; crash++) {
        try (var wal = open()) {
          var evidence = collect(wal, positions[0]);
          assertEquals(Set.of(11), evidence.pageZeroFiles());
          assertEquals(positions[1], evidence.readableEnd());
          // The final recovery flush writes two empty records in a new segment.
          wal.appendNewSegment();
          wal.log(new EmptyWALRecord());
          wal.log(new EmptyWALRecord());
        }
      }
      LogSequenceNumber empty;
      try (var wal = open()) {
        empty = wal.log(new EmptyWALRecord());
      }
      Files.write(segment(empty), new byte[0]);
      try (var wal = open()) {
        assertEquals(positions[1], collect(wal, positions[0]).readableEnd());
        // false covers both clean crash recovery and backup replay, neither trusts torn tails.
        var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
            () -> RecoveryWALEvidence.collect(wal, positions[0], false, Integer.MAX_VALUE));
        assertEquals(positions[1], failure.lastReadablePosition());
      }
    }
  }

  /** A cut record, broken page, or empty segment before content always fails, even after a crash. */
  @Test
  public void stopPointsBeforeLaterContentFail() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      walPath = folder.newFolder().toPath();
      var positions = stopFixture(kind);
      if (kind == 0) {
        try (var file = FileChannel.open(segment(positions[2]), StandardOpenOption.WRITE)) {
          file.truncate(CASWALPage.DEFAULT_PAGE_SIZE);
        }
      }
      try (var wal = open()) {
        begin(wal, 3);
        end(wal, 3);
      }
      try (var wal = open()) {
        var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
            () -> collect(wal, positions[0]));
        assertEquals(positions[1], failure.lastReadablePosition());
        assertNotNull(failure.getCause());
      }
    }
  }

  /** Readable metadata continuation pages after a broken first page reject torn-tail trust. */
  @Test
  public void readablePageInBrokenSegmentFails() throws Exception {
    var positions = stopFixture(0);
    try (var wal = open()) {
      var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
          () -> collect(wal, positions[0]));
      assertEquals(positions[1], failure.lastReadablePosition());
    }
  }

  /**
   * Physical tails of 1, 21, 22 and 23 bytes are stop points, both at segment start and after a
   * full page. Empty later records permit crash recovery only. Later content always rejects it.
   */
  @Test
  public void shortPhysicalPagesFollowTornTailPolicy() throws Exception {
    for (boolean laterSegment : new boolean[] {true, false}) {
      for (int length : new int[] {1, 21, 22, 23}) {
        walPath = folder.newFolder().toPath();
        LogSequenceNumber start;
        LogSequenceNumber finish;
        LogSequenceNumber broken;
        try (var wal = open()) {
          start = wal.log(new AtomicUnitStartRecord(false, 1));
          wal.log(new PageAllocatedWALRecord(0, 11, 1));
          finish = end(wal, 1);
          if (laterSegment) {
            wal.appendNewSegment();
          }
          byte[] metadata = new byte[CASWALPage.DEFAULT_PAGE_SIZE * 4];
          new Random(42).nextBytes(metadata);
          broken = wal.log(new AtomicUnitStartMetadataRecord(false, 2, metadata));
          wal.appendNewSegment();
          wal.log(new EmptyWALRecord());
          wal.appendNewSegment();
          wal.log(new EmptyWALRecord());
        }
        long pageStart = laterSegment ? 0
            : (finish.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE + 1L)
                * CASWALPage.DEFAULT_PAGE_SIZE;
        try (var file = FileChannel.open(segment(broken), StandardOpenOption.WRITE)) {
          file.truncate(pageStart + length);
        }
        var stop = new LogSequenceNumber(broken.getSegment(), (int) pageStart);
        try (var wal = open()) {
          var coverage = new LogSequenceNumber(broken.getSegment(), CASWALPage.RECORDS_OFFSET);
          if (laterSegment) {
            assertEquals(WriteAheadLog.ProofStatus.BROKEN_PAGE,
                wal.openSegmentProofReader(coverage, Integer.MAX_VALUE).next(1).status());
            // The general proof reader still rejects nonempty coverage at or beyond its bound.
            if (length <= CASWALPage.RECORDS_OFFSET) {
              assertEquals(WriteAheadLog.ProofStatus.COVERAGE_NOT_RETAINED,
                  wal.openProofReader(coverage, Integer.MAX_VALUE).next(1).status());
            }
          }
          var evidence = collect(wal, start);
          assertEquals(Set.of(11), evidence.pageZeroFiles());
          assertEquals(finish, evidence.readableEnd());
          var cleanFailure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
              () -> RecoveryWALEvidence.collect(wal, start, false, Integer.MAX_VALUE));
          assertEquals(stop, cleanFailure.failedPosition());
          assertEquals(finish, cleanFailure.lastReadablePosition());
          assertNotNull(cleanFailure.getCause());
          begin(wal, 3);
          end(wal, 3);
        }
        try (var wal = open()) {
          var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
              () -> collect(wal, start));
          assertEquals(finish, failure.lastReadablePosition());
          assertTrue(failure.failedPosition().getSegment() > broken.getSegment());
          assertTrue(failure.getCause().getMessage().contains("content after stop point"));
        }
      }
    }
  }

  /** A damaged middle page cannot be a torn tail when a later page in that segment is readable. */
  @Test
  public void readablePageAfterBrokenMiddlePageFails() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber finish;
    LogSequenceNumber broken;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 11, 1));
      finish = end(wal, 1);
      byte[] metadata = new byte[CASWALPage.DEFAULT_PAGE_SIZE * 4];
      new Random(42).nextBytes(metadata);
      broken = wal.log(new AtomicUnitStartMetadataRecord(false, 2, metadata));
    }
    byte[] bytes = Files.readAllBytes(segment(broken));
    int middlePage = (finish.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE + 1)
        * CASWALPage.DEFAULT_PAGE_SIZE;
    assertTrue(bytes.length > middlePage + CASWALPage.DEFAULT_PAGE_SIZE);
    bytes[middlePage] ^= 1;
    Files.write(segment(broken), bytes);
    try (var wal = open()) {
      var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
          () -> collect(wal, start));
      assertEquals(new LogSequenceNumber(broken.getSegment(), middlePage),
          failure.failedPosition());
      assertEquals(finish, failure.lastReadablePosition());
      assertNotNull(failure.getCause());
    }
  }

  /** A torn first record returns empty evidence with no readable end, not a fabricated LSN. */
  @Test
  public void tornFirstRecordReturnsEmptyEvidenceAndAbsentReadableEnd() throws Exception {
    LogSequenceNumber first;
    try (var wal = open()) {
      first = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 11, 1));
      end(wal, 1);
      wal.appendNewSegment();
      wal.log(new EmptyWALRecord());
    }
    try (var file = FileChannel.open(segment(first), StandardOpenOption.WRITE)) {
      file.truncate((first.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE)
          * (long) CASWALPage.DEFAULT_PAGE_SIZE + 23);
    }
    var start = first;
    try (var wal = open()) {
      var evidence = collect(wal, start);
      assertEquals(start, evidence.start());
      assertNull(evidence.readableEnd());
      assertTrue(evidence.pageZeroFiles().isEmpty());
      assertTrue(evidence.deletions().isEmpty());
      assertTrue(evidence.partialLeadingUnits().isEmpty());
    }
  }

  /** Numeric gaps are allowed between independent units, and both sides contribute evidence. */
  @Test
  public void segmentGapBetweenUnitsKeepsBothSidesEvidence() throws Exception {
    var positions = gapFixture(false);
    try (var wal = open()) {
      // The general proof reader keeps its strict gap policy.
      assertEquals(WriteAheadLog.ProofStatus.MISSING_SEGMENT,
          wal.openProofReader(positions[0], Integer.MAX_VALUE).next(1).status());
      var evidence = collect(wal, positions[0]);
      assertEquals(Set.of(11, 12), evidence.pageZeroFiles());
      assertEquals(Map.of(13, positions[2]), evidence.deletions());
    }
  }

  /** A unit cannot acquire a start or end across missing WAL history. */
  @Test
  public void segmentGapInsideUnitFails() throws Exception {
    var positions = gapFixture(true);
    try (var wal = open()) {
      var failure = assertThrows(RecoveryWALEvidence.ReadFailure.class,
          () -> collect(wal, positions[0]));
      assertTrue(failure.getCause().getMessage().contains("crosses a WAL segment gap"));
    }
  }

  private LogSequenceNumber[] gapFixture(boolean splitUnit) throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber missing;
    LogSequenceNumber deletion;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 11, 1));
      if (!splitUnit) {
        end(wal, 1);
      }
      wal.appendNewSegment();
      missing = wal.log(new EmptyWALRecord());
      wal.appendNewSegment();
      if (!splitUnit) {
        begin(wal, 2);
      }
      long id = splitUnit ? 1 : 2;
      wal.log(new PageAllocatedWALRecord(0, 12, id));
      deletion = wal.log(new FileDeletedWALRecord(id, 13));
      end(wal, id);
    }
    Files.delete(segment(missing));
    return new LogSequenceNumber[] {start, missing, deletion};
  }
}
