package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageAllocatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.WALFile;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.WALRecordDecodeException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises recovery against invalid records on checksum-valid WAL pages. */
public class WALDecodeRecoveryTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private CASDiskWriteAheadLog open() throws Exception {
    Path path = folder.getRoot().toPath();
    return new CASDiskWriteAheadLog("decodeRecovery", path, path,
        ContextConfiguration.WAL_DEFAULT_NAME, 100, 64, null, null,
        Integer.MAX_VALUE, Integer.MAX_VALUE, 20, true, Locale.US, -1, 1000,
        false, false, false, 10);
  }

  /**
   * Changes framing or the record type, then refreshes the page checksum. This forces a record
   * decoding failure rather than the existing broken-page path.
   */
  private void corrupt(LogSequenceNumber bad, boolean framing) throws Exception {
    corrupt(bad, framing, -2);
  }

  private void corrupt(LogSequenceNumber bad, boolean framing, int length) throws Exception {
    Path path = folder.getRoot().toPath().resolve(
        ContextConfiguration.WAL_DEFAULT_NAME + "." + bad.getSegment() + ".wal");
    byte[] bytes = Files.readAllBytes(path);
    int pageStart = (bad.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE)
        * CASWALPage.DEFAULT_PAGE_SIZE;
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
    if (framing) {
      buffer.putInt(bad.getPosition(), length);
    } else {
      buffer.putShort(bad.getPosition() + Integer.BYTES, Short.MAX_VALUE);
    }
    int pageLength = buffer.getShort(pageStart + CASWALPage.PAGE_SIZE_OFFSET);
    buffer.position(pageStart + CASWALPage.RECORDS_OFFSET);
    buffer.limit(pageStart + pageLength);
    long hash = XXHashFactory.fastestJavaInstance().hash64().hash(buffer, 0x9747b28cL);
    buffer.putLong(pageStart + CASWALPage.XX_OFFSET, hash);
    Files.write(path, bytes);
  }

  /** Both framing and payload errors retain exactly the prefix and preserve ordinary reads. */
  @Test
  public void recoveryReaderRetainsPrefixAndNextExcludesAnchor() throws Exception {
    for (boolean framing : List.of(false, true)) {
      LogSequenceNumber start;
      LogSequenceNumber end;
      LogSequenceNumber bad;
      try (var wal = open()) {
        start = wal.log(new AtomicUnitStartRecord(false, 1));
        end = wal.log(new AtomicUnitEndRecord(1, false, null));
        bad = wal.log(new AtomicUnitStartRecord(false, 2));
      }
      corrupt(bad, framing);
      try (var wal = open()) {
        var failure = assertThrows(WALRecordDecodeException.class,
            () -> wal.readForRecovery(start, 1000));
        assertEquals(List.of(start, end),
            failure.getRecords().stream().map(r -> r.getLsn()).toList());
        assertTrue(framing ? failure.getCause() instanceof NegativeArraySizeException
            : failure.getCause() instanceof IllegalStateException);
        var next = assertThrows(WALRecordDecodeException.class,
            () -> wal.nextForRecovery(start, 1000));
        assertEquals(List.of(end), next.getRecords().stream().map(r -> r.getLsn()).toList());
        assertTrue(assertThrows(WALRecordDecodeException.class,
            () -> wal.nextForRecovery(end, 0)).getRecords().isEmpty());
        assertTrue(assertThrows(WALRecordDecodeException.class,
            () -> wal.readForRecovery(bad, 0)).getRecords().isEmpty());
        assertEquals(start, wal.readForRecovery(start, 1).getFirst().getLsn());
        assertEquals(end, wal.nextForRecovery(start, 1).getFirst().getLsn());
        var ordinary = assertThrows(RuntimeException.class, () -> wal.read(start, 1000));
        assertFalse(ordinary instanceof WALRecordDecodeException);
        assertEquals(failure.getCause().getClass(), ordinary.getClass());
        assertEquals(ordinary.getClass(),
            assertThrows(RuntimeException.class, () -> wal.next(start, 1000)).getClass());
      }
      // Each iteration needs a separate WAL, so no earlier invalid record can mask this case.
      for (var file : folder.getRoot().listFiles()) {
        assertTrue(file.delete());
      }
    }
  }

  /** Unwrapping preserves the primary object and every close failure without self-suppression. */
  @Test
  public void unwrappingKeepsSuppressedFailuresOnOriginalCause() {
    var cause = new IllegalArgumentException("bad record");
    var failure = new WALRecordDecodeException(List.of(), cause);
    var firstClose = new IOException("first close");
    var secondClose = new IOException("second close");
    failure.addSuppressed(firstClose);
    failure.addSuppressed(cause);
    failure.addSuppressed(secondClose);

    assertSame(cause, failure.getCauseWithSuppressed());
    assertEquals(List.of(firstClose, secondClose), List.of(cause.getSuppressed()));
  }

  /** Decode plus close failures survive ordinary reads, next-prefix trimming and replay. */
  @Test
  public void decodeAndCloseFailuresRetainBothCauses() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber end;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      end = wal.log(new AtomicUnitEndRecord(1, false, null));
      bad = wal.log(new AtomicUnitStartRecord(false, 2));
    }
    corrupt(bad, true);
    var closeFailure = new IOException("injected close failure");
    var storage = storage(mock(WriteCache.class), mock(ReadCache.class));
    try (var wal = open(); var capture = new Capture(storage)) {
      var read = withCloseFailure(bad, closeFailure,
          () -> assertThrows(WALRecordDecodeException.class,
              () -> wal.readForRecovery(start, 1000)));
      assertEquals(2, read.getRecords().size());
      assertSame(closeFailure, read.getSuppressed()[0]);

      var next = withCloseFailure(bad, closeFailure,
          () -> assertThrows(WALRecordDecodeException.class,
              () -> wal.nextForRecovery(start, 1000)));
      assertEquals(List.of(end), next.getRecords().stream().map(r -> r.getLsn()).toList());
      assertSame(closeFailure, next.getSuppressed()[0]);

      var ordinaryRead = withCloseFailure(bad, closeFailure,
          () -> assertThrows(NegativeArraySizeException.class, () -> wal.read(start, 1000)));
      assertEquals(List.of(closeFailure), List.of(ordinaryRead.getSuppressed()));
      var ordinaryNext = withCloseFailure(bad, closeFailure,
          () -> assertThrows(NegativeArraySizeException.class, () -> wal.next(start, 1000)));
      assertEquals(List.of(closeFailure), List.of(ordinaryNext.getSuppressed()));

      assertEquals(end, withCloseFailure(bad, closeFailure, () -> storage.restoreFrom(wal, start)));
      assertEquals(1, capture.records.size());
      var replayFailure = capture.records.getFirst().getThrown();
      assertTrue(replayFailure instanceof NegativeArraySizeException);
      assertEquals(List.of(closeFailure), List.of(replayFailure.getSuppressed()));
    }
  }

  private <T> T withCloseFailure(LogSequenceNumber lsn, IOException closeFailure,
      Callable<T> action) throws Exception {
    var path = folder.getRoot().toPath().resolve(
        ContextConfiguration.WAL_DEFAULT_NAME + "." + lsn.getSegment() + ".wal");
    try (var file = WALFile.createReadWALFile(path, lsn.getSegment())) {
      var failingFile = mock(WALFile.class, org.mockito.AdditionalAnswers.delegatesTo(file));
      doAnswer(invocation -> {
        file.close();
        throw closeFailure;
      }).when(failingFile).close();
      // Only the calling thread uses this factory override. WAL writers keep their real files.
      try (var factory = mockStatic(WALFile.class, CALLS_REAL_METHODS)) {
        factory.when(() -> WALFile.createReadWALFile(any(Path.class), anyLong()))
            .thenReturn(failingFile);
        return action.call();
      }
    }
  }

  private AbstractStorage storage(WriteCache cache, ReadCache readCache) throws Exception {
    var storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    for (var entry : java.util.Map.<String, Object>of(
        "name", "decodeRecovery", "writeCache", cache, "readCache", readCache,
        "idGen", new AtomicOperationIdGen(),
        "deletedNonDurableFileIds", new IntOpenHashSet()).entrySet()) {
      var field = AbstractStorage.class.getDeclaredField(entry.getKey());
      field.setAccessible(true);
      field.set(storage, entry.getValue());
    }
    return storage;
  }

  private static final class Capture extends Handler implements AutoCloseable {

    private final Logger logger;
    private final long ownerThreadId = Thread.currentThread().threadId();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private Capture(AbstractStorage storage) {
      logger = Logger.getLogger(storage.getClass().getName());
      logger.addHandler(this);
    }

    @Override
    public void publish(LogRecord record) {
      if (record.getLongThreadID() == ownerThreadId
          && (record.getMessage().contains("Data restore was paused")
              || record.getMessage().contains("Deletion lookahead stopped"))) {
        records.add(record);
      }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
      logger.removeHandler(this);
    }
  }

  /** Every reported deletion failure becomes a checked crash-recovery failure with its cause. */
  @Test
  public void identityCrashReplayDoesNotSwallowDeletionFailures() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber deletion;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      deletion = wal.log(new FileDeletedWALRecord(1, 7));
      wal.log(new AtomicUnitEndRecord(1, false, null));
    }
    var cache = mock(WriteCache.class);
    when(cache.supportsIdentityRecovery()).thenReturn(true);
    var readCache = mock(ReadCache.class);
    var storage = storage(cache, readCache);
    var method = AbstractStorage.class.getDeclaredMethod("restoreFrom",
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
        LogSequenceNumber.class,
        com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext.class);
    method.setAccessible(true);
    for (Throwable cause : List.of(
        new com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException("unlink failed"),
        new com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageBrokenException(
            "broken replay"),
        new AssertionError("replay error"), new IOException("deletion IO failure"))) {
      doThrow(cause).when(readCache).deleteFile(7, cache);
      try (var wal = open()) {
        var wrapper = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> method.invoke(storage, wal, start,
                new com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext()));
        assertTrue(wrapper.getCause() instanceof IOException);
        assertSame(cause, wrapper.getCause().getCause());
        assertTrue(wrapper.getCause().getMessage().contains("failed at record " + deletion));
        assertTrue(wrapper.getCause().getMessage().contains(
            "last successfully applied record " + start));
        var evidence = RecoveryWALEvidence.collect(wal, start, true, Integer.MAX_VALUE);
        assertTrue(wrapper.getCause().getMessage().contains(
            "evidence readable end " + evidence.readableEnd()));
      }
    }
  }

  /** An initial read failure names its known position, no applied record and the evidence end. */
  @Test
  public void identityReadFailureReportsKnownPositionAndCause() throws Exception {
    var start = new LogSequenceNumber(1, 100);
    var end = new LogSequenceNumber(1, 200);
    var wal = mock(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class);
    var cause = new IOException("injected WAL read failure");
    when(wal.read(start, 1000)).thenThrow(cause);
    when(wal.read(start, 1)).thenThrow(cause);
    var cache = mock(WriteCache.class);
    when(cache.supportsIdentityRecovery()).thenReturn(true);
    var storage = storage(cache, mock(ReadCache.class));
    var method = AbstractStorage.class.getDeclaredMethod("restoreFrom",
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
        LogSequenceNumber.class,
        com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext.class);
    method.setAccessible(true);
    var collected = new RecoveryWALEvidence(start, end, java.util.Set.of(),
        java.util.Map.of(), java.util.Set.of());
    try (var evidence = mockStatic(RecoveryWALEvidence.class)) {
      evidence.when(() -> RecoveryWALEvidence.collect(wal, start, true, Integer.MAX_VALUE))
          .thenReturn(collected);
      var wrapper = assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> method.invoke(storage, wal, start,
              new com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext()));
      var diagnostic = wrapper.getCause();
      assertSame(cause, diagnostic.getCause());
      assertTrue(diagnostic.getMessage(),
          diagnostic.getMessage().contains("failed at record " + start));
      assertTrue(diagnostic.getMessage().contains("last successfully applied record null"));
      assertTrue(diagnostic.getMessage().contains("evidence readable end " + end));
      verify(wal).read(start, 1000);
      verify(wal).read(start, 1);
    }
  }

  /**
   * Healthy replay batches reads. Failed batches recover every bounded record, but an
   * in-range read failure retains its cause and reports the last applied unit, not buffered data.
   */
  @Test
  public void identityBatchesRetryOnlyFailedReadsAndRejectInRangeFailures() throws Exception {
    for (int scenario = 0; scenario < 7; scenario++) {
      var start = new AtomicUnitStartRecord(false, 1);
      start.setLsn(new LogSequenceNumber(1, 100));
      var end = new AtomicUnitEndRecord(1, false, null);
      end.setLsn(new LogSequenceNumber(1, 200));
      var tail = new AtomicUnitStartRecord(false, 2);
      tail.setLsn(new LogSequenceNumber(1, 300));
      var wal = mock(
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class);
      when(wal.end()).thenReturn(tail.getLsn());
      var discardedBatch = new IOException("discarded batch");
      var cause = scenario == 3 ? new IllegalStateException("in-range runtime failure")
          : new IOException("in-range read failure");
      if (scenario == 0) {
        when(wal.read(start.getLsn(), 1000)).thenReturn(List.of(start, end));
        when(wal.next(end.getLsn(), 1000)).thenReturn(List.of(tail));
      } else {
        when(wal.read(start.getLsn(), 1000)).thenThrow(discardedBatch);
        when(wal.read(start.getLsn(), 1)).thenReturn(List.of(start));
        when(wal.next(start.getLsn(), 1)).thenReturn(List.of(end));
        if (scenario == 1) {
          when(wal.next(end.getLsn(), 1)).thenReturn(List.of(tail));
        } else if (scenario == 4) {
          cause = new IllegalArgumentException("in-range decoding failure");
          when(wal.next(end.getLsn(), 1))
              .thenThrow(new WALRecordDecodeException(List.of(), (RuntimeException) cause));
        } else if (scenario == 5) {
          when(wal.next(end.getLsn(), 1)).thenReturn(List.of());
        } else if (scenario == 6) {
          var beyond = new AtomicUnitStartRecord(false, 3);
          beyond.setLsn(new LogSequenceNumber(1, 400));
          when(wal.next(end.getLsn(), 1)).thenReturn(List.of(beyond));
        } else {
          when(wal.next(end.getLsn(), 1)).thenThrow(cause);
        }
      }
      var cache = mock(WriteCache.class);
      when(cache.supportsIdentityRecovery()).thenReturn(true);
      var storage = storage(cache, mock(ReadCache.class));
      var method = AbstractStorage.class.getDeclaredMethod("restoreFrom",
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
          LogSequenceNumber.class,
          com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext.class);
      method.setAccessible(true);
      var collected = new RecoveryWALEvidence(start.getLsn(), tail.getLsn(), java.util.Set.of(),
          java.util.Map.of(), java.util.Set.of());
      try (var evidence = mockStatic(RecoveryWALEvidence.class)) {
        evidence
            .when(() -> RecoveryWALEvidence.collect(wal, start.getLsn(), true, Integer.MAX_VALUE))
            .thenReturn(collected);
        if (scenario <= 1) {
          assertEquals(end.getLsn(), method.invoke(storage, wal, start.getLsn(),
              new com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext()));
          if (scenario == 0) {
            verify(wal, never()).read(start.getLsn(), 1);
            verify(wal).next(end.getLsn(), 1000);
          }
          verify(wal, never()).next(tail.getLsn(), 1);
        } else {
          var wrapper = assertThrows(java.lang.reflect.InvocationTargetException.class,
              () -> method.invoke(storage, wal, start.getLsn(),
                  new com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext()));
          var diagnostic = wrapper.getCause();
          assertTrue(diagnostic.getMessage(),
              diagnostic.getMessage().contains("failed at record unknown"));
          assertTrue(
              diagnostic.getMessage().contains("last successfully applied record " + end.getLsn()));
          assertTrue(diagnostic.getMessage().contains("evidence readable end " + tail.getLsn()));
          assertSame(scenario < 5 ? cause : discardedBatch, diagnostic.getCause());
        }
      }
    }
  }

  /**
   * A failed next batch excludes its deletion anchor. The unit starts in the healthy batch,
   * commits in the retry, and deletes its file exactly once.
   */
  @Test
  public void identityNextBatchRetryExcludesAnchorAndCompletesSpanningUnit() throws Exception {
    var records = new ArrayList<WriteableWALRecord>();
    for (int unit = 1; unit <= 499; unit++) {
      records.add(new AtomicUnitStartRecord(false, unit));
      records.add(new AtomicUnitEndRecord(unit, false, null));
    }
    records.add(new AtomicUnitStartRecord(false, 500));
    var anchor = new FileDeletedWALRecord(500, 7);
    records.add(anchor);
    var end = new AtomicUnitEndRecord(500, false, null);
    records.add(end);
    for (int i = 0; i < records.size(); i++) {
      records.get(i).setLsn(new LogSequenceNumber(1, 100 + i));
    }
    var start = records.getFirst().getLsn();
    var wal = mock(WriteAheadLog.class);
    when(wal.end()).thenReturn(end.getLsn());
    when(wal.read(start, 1000)).thenReturn(records.subList(0, 1000));
    when(wal.next(anchor.getLsn(), 1000)).thenThrow(new IOException("failed later batch"));
    // Model both APIs faithfully so an inclusive retry would replay the deletion twice.
    when(wal.read(anchor.getLsn(), 1)).thenReturn(List.of(anchor));
    when(wal.next(anchor.getLsn(), 1)).thenReturn(List.of(end));
    var cache = mock(WriteCache.class);
    when(cache.supportsIdentityRecovery()).thenReturn(true);
    var readCache = mock(ReadCache.class);

    assertEquals(end.getLsn(),
        replayWithIdentityEvidence(wal, start, end.getLsn(), cache, readCache));

    verify(readCache).deleteFile(7, cache);
    verifyNoMoreInteractions(readCache);
    var reads = inOrder(wal);
    reads.verify(wal).read(start, 1000);
    reads.verify(wal).next(anchor.getLsn(), 1000);
    reads.verify(wal).next(anchor.getLsn(), 1);
    verify(wal, never()).read(anchor.getLsn(), 1);
    verify(wal, never()).next(end.getLsn(), 1);
    verify(wal, never()).next(end.getLsn(), 1000);
  }

  /**
   * A later batch failure retries exactly 1000 records, then resumes normal batching.
   * Replay must reach the final committed unit beyond the retry cap.
   */
  @Test
  public void identityNextBatchRetryStopsAtThousandRecordsAndResumesBatching() throws Exception {
    var records = new ArrayList<WriteableWALRecord>();
    for (int unit = 1; unit <= 1001; unit++) {
      records.add(new AtomicUnitStartRecord(false, unit));
      records.add(new AtomicUnitEndRecord(unit, false, null));
    }
    for (int i = 0; i < records.size(); i++) {
      records.get(i).setLsn(new LogSequenceNumber(1, 100 + i));
    }
    var start = records.getFirst().getLsn();
    var anchor = records.get(999).getLsn();
    var retryEnd = records.get(1999).getLsn();
    var end = records.getLast().getLsn();
    var wal = mock(WriteAheadLog.class);
    when(wal.end()).thenReturn(end);
    when(wal.read(start, 1000)).thenReturn(records.subList(0, 1000));
    when(wal.next(anchor, 1000)).thenThrow(new IOException("failed later batch"));
    when(wal.next(any(), eq(1))).thenAnswer(invocation -> {
      var position = (LogSequenceNumber) invocation.getArgument(0);
      int index = position.getPosition() - 100;
      return List.of(records.get(index + 1));
    });
    when(wal.next(retryEnd, 1000)).thenReturn(records.subList(2000, 2002));
    var cache = mock(WriteCache.class);
    when(cache.supportsIdentityRecovery()).thenReturn(true);

    assertEquals(end,
        replayWithIdentityEvidence(wal, start, end, cache, mock(ReadCache.class)));

    var reads = inOrder(wal);
    reads.verify(wal).read(start, 1000);
    reads.verify(wal).next(anchor, 1000);
    reads.verify(wal).next(retryEnd, 1000);
    var retryAnchors = mockingDetails(wal).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("next")
            && invocation.getArgument(1).equals(1))
        .map(invocation -> (LogSequenceNumber) invocation.getArgument(0)).toList();
    assertEquals(records.subList(999, 1999).stream().map(r -> r.getLsn()).toList(),
        retryAnchors);
    verify(wal, never()).next(retryEnd, 1);
    verify(wal, never()).next(end, 1000);
  }

  private LogSequenceNumber replayWithIdentityEvidence(WriteAheadLog wal, LogSequenceNumber start,
      LogSequenceNumber end, WriteCache cache, ReadCache readCache) throws Exception {
    var storage = storage(cache, readCache);
    var method = AbstractStorage.class.getDeclaredMethod("restoreFrom",
        WriteAheadLog.class, LogSequenceNumber.class, RecoveryPageContext.class);
    method.setAccessible(true);
    var collected = new RecoveryWALEvidence(start, end, java.util.Set.of(),
        java.util.Map.of(), java.util.Set.of());
    try (var evidence = mockStatic(RecoveryWALEvidence.class)) {
      evidence.when(() -> RecoveryWALEvidence.collect(wal, start, true, Integer.MAX_VALUE))
          .thenReturn(collected);
      return (LogSequenceNumber) method.invoke(storage, wal, start, new RecoveryPageContext());
    }
  }

  /** Evidence deletion skips allocation resolution and preload for either committed drop shape. */
  @Test
  public void identityDeletionSkipsAllocationResolutionAndPreload() throws Exception {
    // Pair the real present/absent disk matrix with strict cache-boundary observations.
    for (int scenario = 0; scenario < 4; scenario++) {
      boolean sameUnit = (scenario & 1) != 0;
      boolean present = (scenario & 2) != 0;
      LogSequenceNumber start;
      try (var wal = open()) {
        start = wal.log(new AtomicUnitStartRecord(false, 1));
        wal.log(new PageAllocatedWALRecord(1, 7, 1));
        if (!sameUnit) {
          wal.log(new AtomicUnitEndRecord(1, false, null));
          wal.log(new AtomicUnitStartRecord(false, 2));
        }
        wal.log(new FileDeletedWALRecord(sameUnit ? 1 : 2, 7));
        wal.log(new AtomicUnitEndRecord(sameUnit ? 1 : 2, false, null));
      }
      var cache = mock(WriteCache.class);
      when(cache.supportsIdentityRecovery()).thenReturn(true);
      when(cache.internalFileId(7)).thenReturn(7);
      when(cache.exists(7)).thenReturn(present);
      var read = mock(ReadCache.class);
      var storage = storage(cache, read);
      var method = AbstractStorage.class.getDeclaredMethod("restoreFrom",
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
          LogSequenceNumber.class,
          com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext.class);
      method.setAccessible(true);
      try (var wal = open()) {
        method.invoke(storage, wal, start,
            new com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext());
      }
      verify(cache, never()).resolveRecoveryFile(any(), anyLong(), any());
      verify(read, never()).loadOrAddForWrite(anyLong(), anyLong(), any(), anyBoolean(), any());
      verify(read).deleteFile(7, cache);
      verifyNoMoreInteractions(read);
    }
  }

  /** Complete units before the bad record apply, but an incomplete unit and later units do not. */
  @Test
  public void backupReplayAppliesCompletePrefixAndLogsCause() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber end;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new FileCreatedWALRecord(1, "good.dat", 7));
      end = wal.log(new AtomicUnitEndRecord(1, false, null));
      wal.log(new AtomicUnitStartRecord(false, 2));
      wal.log(new FileCreatedWALRecord(2, "incomplete.dat", 8));
      bad = wal.log(new AtomicUnitEndRecord(2, false, null));
      wal.log(new FileCreatedWALRecord(3, "later.dat", 9));
      wal.log(new AtomicUnitEndRecord(3, false, null));
    }
    corrupt(bad, false);
    var cache = mock(WriteCache.class);
    var readCache = mock(ReadCache.class);
    var storage = storage(cache, readCache);
    try (var wal = open(); var capture = new Capture(storage)) {
      assertEquals(end, storage.restoreFrom(wal, start));
      verify(readCache).addFile("good.dat", 7, cache);
      verify(readCache, never()).addFile(eq("incomplete.dat"), anyLong(), any());
      verify(readCache, never()).addFile(eq("later.dat"), anyLong(), any());
      assertEquals(1, capture.records.size());
      assertTrue(capture.records.getFirst().getThrown() instanceof IllegalStateException);
    }
  }

  /** A checked replay failure escapes with the pending decode cause attached. */
  @Test
  public void checkedPrefixReplayFailureRetainsDecodeCause() throws Exception {
    var replayFailure = new IOException("disk full");
    prefixReplayFailureRetainsDecodeCause(replayFailure);
  }

  /** A runtime replay failure is logged with the pending decode cause attached. */
  @Test
  public void runtimePrefixReplayFailureRetainsDecodeCause() throws Exception {
    var replayFailure = new IllegalArgumentException("file creation failed");
    prefixReplayFailureRetainsDecodeCause(replayFailure);
  }

  private void prefixReplayFailureRetainsDecodeCause(Exception replayFailure) throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new FileCreatedWALRecord(1, "good.dat", 7));
      wal.log(new AtomicUnitEndRecord(1, false, null));
      bad = wal.log(new AtomicUnitStartRecord(false, 2));
    }
    corrupt(bad, false);
    var cache = mock(WriteCache.class);
    var readCache = mock(ReadCache.class);
    doThrow(replayFailure).when(readCache).addFile("good.dat", 7, cache);
    var storage = storage(cache, readCache);
    try (var wal = open(); var capture = new Capture(storage)) {
      if (replayFailure instanceof IOException) {
        assertSame(replayFailure, assertThrows(IOException.class,
            () -> storage.restoreFrom(wal, start)));
      } else {
        assertNull(storage.restoreFrom(wal, start));
        assertEquals(1, capture.records.size());
        assertSame(replayFailure, capture.records.getFirst().getThrown());
      }
      assertEquals(1, replayFailure.getSuppressed().length);
      var decodeCause = replayFailure.getSuppressed()[0];
      assertTrue(decodeCause instanceof IllegalStateException);
      assertTrue(decodeCause.getMessage().contains("record"));
    }
  }

  /** A forward-prefix observation failure keeps the decode cause in its warning. */
  @Test
  public void forwardPrefixFailureRetainsDecodeCause() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 7, 1));
      wal.log(new AtomicUnitEndRecord(1, false, null));
      wal.log(new AtomicUnitStartRecord(false, 2));
      wal.log(new FileDeletedWALRecord(2, 8));
      wal.log(new AtomicUnitEndRecord(2, false, null));
      bad = wal.log(new AtomicUnitStartRecord(false, 3));
    }
    corrupt(bad, false);
    var replayFailure = new IllegalArgumentException("file id lookup failed");
    var cache = mock(WriteCache.class);
    when(cache.internalFileId(7)).thenReturn(7);
    when(cache.internalFileId(8)).thenThrow(replayFailure);
    var storage = storage(cache, mock(ReadCache.class));
    try (var wal = open(); var capture = new Capture(storage)) {
      storage.restoreFrom(wal, start);
      var warnings = capture.records.stream()
          .filter(r -> r.getMessage().contains("Deletion lookahead stopped")).toList();
      assertEquals(1, warnings.size());
      assertSame(replayFailure, warnings.getFirst().getThrown());
      assertEquals(1, replayFailure.getSuppressed().length);
      assertTrue(replayFailure.getSuppressed()[0] instanceof IllegalStateException);
    }
  }

  /** An impossible array length fails recovery with the original Error, not a decode wrapper. */
  @Test
  public void decodeErrorEscapesRecovery() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new AtomicUnitEndRecord(1, false, null));
      bad = wal.log(new AtomicUnitStartRecord(false, 2));
    }
    // The VM rejects this length without trying to allocate a multi-gigabyte array.
    corrupt(bad, true, Integer.MAX_VALUE);
    var storage = storage(mock(WriteCache.class), mock(ReadCache.class));
    try (var wal = open(); var capture = new Capture(storage)) {
      assertThrows(OutOfMemoryError.class, () -> storage.restoreFrom(wal, start));
      assertTrue(capture.records.isEmpty());
    }
  }

  /** A bad first record must report a failure even though no records can be returned. */
  @Test
  public void emptyInitialPrefixLogsError() throws Exception {
    LogSequenceNumber bad;
    try (var wal = open()) {
      bad = wal.log(new AtomicUnitStartRecord(false, 1));
    }
    corrupt(bad, true);
    var storage = storage(mock(WriteCache.class), mock(ReadCache.class));
    try (var wal = open(); var capture = new Capture(storage)) {
      assertNull(storage.restoreFrom(wal, bad));
      assertEquals(1, capture.records.size());
      assertTrue(capture.records.getFirst().getThrown() instanceof NegativeArraySizeException);
    }
  }

  /** The first batch ends at 1000 records. An empty next prefix must still log its error. */
  @Test
  public void emptyNextPrefixLogsError() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber end = null;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      for (int i = 0; i < 499; i++) {
        end = wal.log(new AtomicUnitEndRecord(i + 1, false, null));
        wal.log(new AtomicUnitStartRecord(false, i + 2));
      }
      end = wal.log(new AtomicUnitEndRecord(500, false, null));
      bad = wal.log(new AtomicUnitStartRecord(false, 501));
    }
    corrupt(bad, false);
    var storage = storage(mock(WriteCache.class), mock(ReadCache.class));
    try (var wal = open(); var capture = new Capture(storage)) {
      assertEquals(end, storage.restoreFrom(wal, start));
      assertEquals(1, capture.records.size());
      assertTrue(capture.records.getFirst().getThrown() instanceof IllegalStateException);
    }
  }

  /** The next batch keeps its prefix without replaying the start record at its anchor twice. */
  @Test
  public void nextBatchPrefixDoesNotDuplicateAtomicStart() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber end;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitEndRecord(0, false, null));
      for (int i = 1; i <= 499; i++) {
        wal.log(new AtomicUnitStartRecord(false, i));
        wal.log(new AtomicUnitEndRecord(i, false, null));
      }
      wal.log(new AtomicUnitStartRecord(false, 500));
      wal.log(new FileCreatedWALRecord(500, "next.dat", 7));
      end = wal.log(new AtomicUnitEndRecord(500, false, null));
      bad = wal.log(new AtomicUnitStartRecord(false, 501));
    }
    corrupt(bad, false);
    var cache = mock(WriteCache.class);
    var readCache = mock(ReadCache.class);
    var storage = storage(cache, readCache);
    try (var wal = open(); var capture = new Capture(storage)) {
      assertEquals(end, storage.restoreFrom(wal, start));
      verify(readCache).addFile("next.dat", 7, cache);
      assertEquals(1, capture.records.size());
    }
  }

  /**
   * The forward read starts inside the normal batch. Both reads keep the complete deletion
   * before the bad record. The page allocation is skipped and the file is not resurrected.
   */
  @Test
  public void forwardAndNormalReadKeepSameDeletionPrefix() throws Exception {
    LogSequenceNumber start;
    LogSequenceNumber end;
    LogSequenceNumber bad;
    try (var wal = open()) {
      start = wal.log(new AtomicUnitStartRecord(false, 1));
      wal.log(new PageAllocatedWALRecord(0, 7, 1));
      wal.log(new AtomicUnitEndRecord(1, false, null));
      wal.log(new AtomicUnitStartRecord(false, 2));
      wal.log(new FileDeletedWALRecord(2, 7));
      end = wal.log(new AtomicUnitEndRecord(2, false, null));
      bad = wal.log(new AtomicUnitStartRecord(false, 3));
    }
    corrupt(bad, false);
    var cache = mock(WriteCache.class);
    when(cache.internalFileId(7)).thenReturn(7);
    var readCache = mock(ReadCache.class);
    var storage = storage(cache, readCache);
    try (var wal = open(); var capture = new Capture(storage)) {
      assertEquals(end, storage.restoreFrom(wal, start));
      verify(cache, never()).restoreFileById(anyLong());
      verify(readCache, never()).loadOrAddForWrite(anyLong(), anyLong(), any(), anyBoolean(),
          any());
      assertEquals(2, capture.records.size());
      assertTrue(capture.records.stream()
          .allMatch(r -> r.getThrown() instanceof IllegalStateException));
    }
  }
}
