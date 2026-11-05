package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.PageSetTrackingAssertions;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

@Category(SequentialTest.class)
public class CommitPageSetApplyTest {
  @Rule
  public TemporaryFolder directory = new TemporaryFolder();

  // Real disk caches exercise dirty tracking, native frame locks, pins and backup marks.
  // The WAL is deterministic so legacy and page-set output can be compared byte for byte.
  private final class Fixture implements AutoCloseable {
    final ByteBufferPool pool = new ByteBufferPool(DurablePage.MAX_PAGE_SIZE_BYTES);
    final LockFreeReadCache read = spy(new LockFreeReadCache(pool,
        128L * DurablePage.MAX_PAGE_SIZE_BYTES, DurablePage.MAX_PAGE_SIZE_BYTES, 4));
    final ChangedPageTracker tracker = new ChangedPageTracker();
    final WriteAheadLog wal = mock(WriteAheadLog.class);
    final java.util.concurrent.ExecutorService io = Executors.newCachedThreadPool();
    final ApplyPhaseEpoch epoch = new ApplyPhaseEpoch();
    final WOWCache write;
    final AtomicInteger lsn = new AtomicInteger(1);
    final List<String> events = new ArrayList<>();
    final List<CacheEntry> pinned = new ArrayList<>();

    Fixture() throws Exception {
      YouTrackDBEnginesManager.startUp(false);
      when(wal.end()).thenReturn(new LogSequenceNumber(7, 100));
      when(wal.logAtomicOperationStartRecord(anyBoolean(), anyLong()))
          .thenAnswer(c -> new LogSequenceNumber(7, lsn.getAndIncrement()));
      when(wal.log(any(WriteableWALRecord.class))).thenAnswer(c -> {
        var record = c.getArgument(0, WriteableWALRecord.class);
        var result = new LogSequenceNumber(7, lsn.getAndIncrement());
        record.setLsn(result);
        return result;
      });
      write = spy(new WOWCache(DurablePage.MAX_PAGE_SIZE_BYTES, false, pool, wal,
          new DoubleWriteLogNoOP(), 0, 10000, 128L * DurablePage.MAX_PAGE_SIZE_BYTES,
          directory.newFolder().toPath(), "page-set", new ClosableLinkedContainer<Long, File>(128),
          1, ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, ChecksumMode.StoreAndVerify,
          null, null, false, io, tracker));
      write.loadRegisteredFiles();
      write.pauseBackgroundFlush();
    }

    AtomicOperationBinaryTracking operation(boolean enabled) {
      var op = new AtomicOperationBinaryTracking(read, write, wal, 1,
          new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100),
          new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(), new AtomicLong(),
          new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(), new AtomicLong(), epoch);
      op.startToApplyOperations(42);
      op.setPageSetApplyForTesting(enabled);
      return op;
    }

    void change(AtomicOperationBinaryTracking op, long file, long page, boolean nonDurable)
        throws Exception {
      var changes = page < write.getFilledUpTo(file)
          ? op.loadPageForWrite(file, page, 1, false) : op.allocatePageForWrite(file, page);
      changes.getChanges().setByteValue(null, (byte) (page + 21), 100);
      if (!nonDurable) {
        op.registerPageOperation(file, page,
            new TestPageOperation(page, file, 0, new LogSequenceNumber(0, 0), 0));
      }
      op.releasePageFromWrite(changes);
    }

    String key(CacheEntry entry) {
      return entry.getFileId() + ":" + entry.getPageIndex();
    }

    void observe() {
      doAnswer(call -> {
        var real = (ReadCache.WriteReservation) call.callRealMethod();
        assertNotNull(real);
        var reservation = spy(real);
        doAnswer(c -> {
          assertTrue(events.stream().noneMatch(e -> e.startsWith("lock")));
          var entry = (CacheEntry) c.callRealMethod();
          pinned.add(entry);
          events.add("pin " + key(entry));
          return entry;
        }).when(reservation).pin(anyLong(), anyLong(), any(), anyBoolean());
        doAnswer(c -> {
          assertEquals(4, pinned.size());
          assertTrue(events.stream().noneMatch(e -> e.startsWith("prepare")));
          var entry = c.getArgument(0, CacheEntry.class);
          assertEquals(0, entry.getCachePointer().getBuffer().get(100));
          events.add("lock " + key(entry));
          return c.callRealMethod();
        }).when(reservation).lock(any());
        doAnswer(c -> {
          assertEquals(4, events.stream().filter(e -> e.startsWith("lock")).count());
          var entry = c.getArgument(0, CacheEntry.class);
          assertEquals(0, entry.getCachePointer().getPageFrame().tryOptimisticRead());
          assertEquals(1, epoch.enterSeq());
          assertEquals(0, epoch.exitSeq());
          assertEquals(write.isNonDurable(entry.getFileId()) ? null
              : new LogSequenceNumber(7, 1), c.getArgument(1));
          events.add("prepare " + key(entry));
          return c.callRealMethod();
        }).when(reservation).prepare(any(), any(), any());
        doAnswer(c -> {
          var entry = c.getArgument(0, CacheEntry.class);
          assertEquals("prepare " + key(entry), events.getLast());
          assertEquals(0, entry.getCachePointer().getBuffer().get(100));
          events.add("mark " + key(entry));
          return c.callRealMethod();
        }).when(reservation).markChanged(any());
        doAnswer(c -> {
          var entry = c.getArgument(0, CacheEntry.class);
          assertEquals("mark " + key(entry), events.getLast());
          assertEquals(entry.getPageIndex() + 21, entry.getCachePointer().getBuffer().get(100));
          events.add("publish " + key(entry));
          var result = c.callRealMethod();
          assertNotEquals(0, entry.getCachePointer().getPageFrame().tryOptimisticRead());
          assertEquals(4, read.reservedWritePins());
          events.add("unlock " + key(entry));
          return result;
        }).when(reservation).publishAndUnlock(any());
        doAnswer(c -> {
          assertEquals(4, events.stream().filter(e -> e.startsWith("unlock")).count());
          events.add("close");
          return c.callRealMethod();
        }).when(reservation).close();
        return reservation;
      }).when(read).tryReserveWritePins(anyInt());
      doAnswer(c -> {
        var pointer = c.getArgument(2,
            com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer.class);
        assertEquals(0, pointer.getPageFrame().tryOptimisticRead());
        return c.callRealMethod();
      }).when(write).store(anyLong(), anyLong(), any());
    }

    @Override
    public void close() throws Exception {
      read.clear();
      write.delete();
      io.shutdownNow();
      pool.clear();
    }
  }

  // An unsorted, multi-file set pins all pages, locks all pages in global order, then
  // publishes under each lock. Both protocols must produce identical bytes, LSNs,
  // dirty start LSN and backup candidates, including allocation gaps and non-durable pages.
  @Test
  public void sortedWholeSetApplyMatchesLegacy() throws Exception {
    List<byte[]> legacy = null;
    for (boolean enabled : new boolean[] {false, true}) {
      try (var f = new Fixture()) {
        long first = f.write.addFile("first");
        long second = f.write.addFile("second");
        long ephemeral = f.write.addFile("ephemeral", f.write.bookFileId("ephemeral"), true);
        var existing = f.read.loadOrAddForWrite(first, 0, f.write, false, null);
        f.read.releaseFromWrite(existing, f.write, false);
        var op = f.operation(enabled);
        op.loadFile("second");
        op.loadFile("ephemeral");
        op.loadFile("first");
        f.change(op, second, 2, false);
        f.change(op, first, 0, false);
        f.change(op, first, 3, false);
        f.change(op, ephemeral, 1, true);
        if (enabled) {
          f.observe();
        }
        var end = op.commitChanges(42, f.wal);
        assertNotNull(end);
        assertEquals(Long.valueOf(7), f.write.getMinimalNotFlushedSegment());
        PageSetTrackingAssertions.assertPages(f.tracker, f.write.internalFileId(first), 0, 3);
        PageSetTrackingAssertions.assertPages(f.tracker, f.write.internalFileId(second), 2);
        PageSetTrackingAssertions.assertPages(f.tracker, f.write.internalFileId(ephemeral));
        assertEquals(0, f.read.reservedWritePins());
        assertEquals(1, f.epoch.exitSeq());
        var bytes = new ArrayList<byte[]>();
        for (long[] identity : new long[][] {{first, 0}, {first, 3}, {second, 2}, {ephemeral, 1}}) {
          var entry = f.read.loadForRead(identity[0], identity[1], f.write, false);
          try {
            var data = new byte[DurablePage.MAX_PAGE_SIZE_BYTES];
            entry.getCachePointer().getBuffer().duplicate().get(data);
            bytes.add(data);
            if (identity[0] != ephemeral) {
              assertEquals(end, entry.getEndLSN());
              assertEquals(7, new DurablePage(entry).getLsn().getSegment());
            }
          } finally {
            f.read.releaseFromRead(entry);
          }
        }
        if (!enabled) {
          legacy = bytes;
          verify(f.read, never()).tryReserveWritePins(anyInt());
        } else {
          for (int i = 0; i < bytes.size(); i++) {
            assertArrayEquals(legacy.get(i), bytes.get(i));
          }
          var locks = f.events.stream().filter(e -> e.startsWith("lock")).toList();
          var expected = new ArrayList<>(List.of(first + ":0", first + ":3",
              second + ":2", ephemeral + ":1"));
          expected.sort((a, b) -> {
            var x = Arrays.stream(a.split(":")).mapToLong(Long::parseLong).toArray();
            var y = Arrays.stream(b.split(":")).mapToLong(Long::parseLong).toArray();
            int cmp = Long.compare(x[0], y[0]);
            return cmp == 0 ? Long.compare(x[1], y[1]) : cmp;
          });
          assertEquals(expected.stream().map(k -> "lock " + k).toList(), locks);
          assertEquals("close", f.events.getLast());
        }
      }
    }
  }

  // Capacity rejection and CAS denial select legacy before the first load. The
  // operation still commits normally, with no attempt to pin a partial page set.
  @Test
  public void deniedAndOversizedSetsFallBackBeforeLoading() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      try (var f = new Fixture()) {
        long file = f.write.addFile("fallback");
        var op = f.operation(true);
        op.loadFile("fallback");
        int count = mode == 0 ? 5 : mode == 1 ? 2 : 1;
        for (int i = 0; i < count; i++) {
          f.change(op, file, i, false);
        }
        if (mode == 1) {
          doReturn(1).when(f.read).writePinBudget();
        }
        var held = mode == 2 ? f.read.tryReserveWritePins(4) : null;
        clearInvocations(f.read, f.write);
        doAnswer(c -> {
          verify(f.write, never()).loadOrAdd(anyLong(), anyLong(), anyBoolean());
          return c.callRealMethod();
        }).when(f.read).tryReserveWritePins(anyInt());
        try {
          assertNotNull(op.commitChanges(42, f.wal));
          if (mode != 2) {
            verify(f.read, never()).tryReserveWritePins(anyInt());
          }
          verify(f.read, times(count)).loadOrAddForWrite(anyLong(), anyLong(), any(),
              anyBoolean(), any());
        } finally {
          if (held != null) {
            held.close();
          }
        }
        assertEquals(0, f.read.reservedWritePins());
      }
    }
  }

  // Empty sets need neither a reservation nor loads. Non-durable-only commits still
  // use the path and publish content without WAL records or backup marks.
  @Test
  public void emptyAndNonDurableSets() throws Exception {
    try (var f = new Fixture()) {
      long file = f.write.addFile("nondurable", f.write.bookFileId("nondurable"), true);
      var empty = f.operation(true);
      empty.loadFile("nondurable");
      assertNull(empty.commitChanges(42, f.wal));
      verify(f.read, never()).tryReserveWritePins(anyInt());
      var changed = f.operation(true);
      changed.loadFile("nondurable");
      f.change(changed, file, 0, true);
      assertNull(changed.commitChanges(43, f.wal));
      var entry = f.read.loadForRead(file, 0, f.write, false);
      try {
        assertEquals(21, entry.getCachePointer().getBuffer().get(100));
        assertNull(entry.getEndLSN());
      } finally {
        f.read.releaseFromRead(entry);
      }
      verify(f.read).tryReserveWritePins(1);
      assertNull(f.write.getMinimalNotFlushedSegment());
      PageSetTrackingAssertions.assertPages(f.tracker, f.write.internalFileId(file));
      assertEquals(0, f.read.reservedWritePins());
    }
  }

  // Same-name recreation must match legacy for full content, page/end LSNs, the
  // actual dirty-table record and a fresh backup mark. Reclassifying the operation as
  // non-durable must retain an already dirty physical page's WAL flush dependency.
  @Test
  public void recreatedPagesMatchLegacyAndPreserveExistingWalDependency() throws Exception {
    for (boolean nonDurable : new boolean[] {false, true}) {
      byte[] legacyBytes = null;
      LogSequenceNumber legacyPageLsn = null;
      LogSequenceNumber legacyEndLsn = null;
      Map<Long, LogSequenceNumber> legacyDirty = null;
      for (boolean enabled : new boolean[] {false, true}) {
        try (var f = new Fixture()) {
          long file = f.write.addFile("recreated");
          var oldStart = new LogSequenceNumber(5, 1);
          var oldPage = new LogSequenceNumber(5, 10);
          var oldEnd = new LogSequenceNumber(6, 20);
          var existing = f.read.loadOrAddForWrite(file, 0, f.write, false, oldStart);
          existing.getCachePointer().getBuffer().put(100, (byte) 9);
          new DurablePage(existing).setLsn(oldPage);
          existing.setEndLSN(oldEnd);
          f.read.releaseFromWrite(existing, f.write, true);
          PageSetTrackingAssertions.beginBackup(f.tracker);
          PageSetTrackingAssertions.assertPages(f.tracker, f.write.internalFileId(file));

          var op = f.operation(enabled);
          op.loadFile("recreated");
          op.deleteFile(file);
          assertEquals(file, op.addFile("recreated", nonDurable));
          // Recreation starts a fresh logical allocation horizon over the same frame.
          var changes = op.allocatePageForWrite(file, 0);
          changes.getChanges().setByteValue(null, (byte) 21, 100);
          if (!nonDurable) {
            op.registerPageOperation(file, 0,
                new TestPageOperation(0, file, 0, new LogSequenceNumber(0, 0), 0));
          }
          op.releasePageFromWrite(changes);
          var end = op.commitChanges(42, f.wal);
          assertEquals(nonDurable, end == null);
          var entry = f.read.loadForRead(file, 0, f.write, false);
          try {
            var bytes = new byte[DurablePage.MAX_PAGE_SIZE_BYTES];
            entry.getCachePointer().getBuffer().duplicate().get(bytes);
            var pageLsn = new DurablePage(entry).getLsn();
            var dirty = PageSetTrackingAssertions.dirtyPages(f.write,
                f.write.internalFileId(file));
            assertEquals(21, bytes[100]);
            assertEquals(nonDurable ? oldEnd : end, entry.getEndLSN());
            assertEquals(nonDurable ? oldPage.getSegment() : 7, pageLsn.getSegment());
            assertEquals(Map.of(0L, oldStart), dirty);
            PageSetTrackingAssertions.assertPages(f.tracker, f.write.internalFileId(file), 0);
            if (!enabled) {
              legacyBytes = bytes;
              legacyPageLsn = pageLsn;
              legacyEndLsn = entry.getEndLSN();
              legacyDirty = dirty;
            } else {
              assertArrayEquals(legacyBytes, bytes);
              assertEquals(legacyPageLsn, pageLsn);
              assertEquals(legacyEndLsn, entry.getEndLSN());
              assertEquals(legacyDirty, dirty);
            }
          } finally {
            f.read.releaseFromRead(entry);
          }
          verify(f.read, times(enabled ? 1 : 0)).tryReserveWritePins(1);
          assertEquals(0, f.read.reservedWritePins());
        }
      }
    }
  }

  // Delete then re-add of the same name reuses the physical file id. It is not a
  // lifecycle fallback. True create and delete operations retain the legacy apply step.
  @Test
  public void recreatedIdentityAndLifecycleFallback() throws Exception {
    for (int mode = 0; mode < 4; mode++) {
      try (var f = new Fixture()) {
        long file = f.write.addFile("identity");
        var op = f.operation(true);
        op.loadFile("identity");
        if (mode != 3) {
          op.deleteFile(file);
        }
        if (mode == 0) {
          assertEquals(file, op.addFile("identity"));
          f.change(op, file, 0, false);
        } else if (mode == 1 || mode == 3) {
          long fresh = op.addFile("fresh");
          f.change(op, fresh, 0, false);
        }
        assertNotNull(op.commitChanges(42, f.wal));
        verify(f.read, times(mode == 0 ? 1 : 0)).tryReserveWritePins(anyInt());
        assertEquals(0, f.read.reservedWritePins());
      }
    }
  }
}
