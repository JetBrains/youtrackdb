package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
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
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryOnlyDiskCache;
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
    final LockFreeReadCache read;
    final ChangedPageTracker tracker = spy(new ChangedPageTracker());
    final WriteAheadLog wal = mock(WriteAheadLog.class);
    final java.util.concurrent.ExecutorService io = Executors.newCachedThreadPool();
    final ApplyPhaseEpoch epoch = new ApplyPhaseEpoch();
    final CommitProtocolCounters counters = new CommitProtocolCounters();
    final WOWCache write;
    final AtomicInteger lsn = new AtomicInteger(1);
    final List<String> events = new ArrayList<>();
    final List<CacheEntry> pinned = new ArrayList<>();

    Fixture() throws Exception {
      this(4);
    }

    Fixture(int pinBudget) throws Exception {
      // Keep the reservation budget below the cache's forced-drain safety margin.
      read = spy(new LockFreeReadCache(pool,
          Math.max(128L, 32L * pinBudget) * DurablePage.MAX_PAGE_SIZE_BYTES,
          DurablePage.MAX_PAGE_SIZE_BYTES, pinBudget));
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
          new DoubleWriteLogNoOP(), 0, 10000, 4096L * DurablePage.MAX_PAGE_SIZE_BYTES,
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
          new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(), new AtomicLong(), epoch,
          counters);
      op.startToApplyOperations(42);
      if (!enabled) {
        op.setForceEpochApplyForTesting(true);
      }
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

    void awaitAllocationTasks() {
      // Allocation tasks call this WOWCache spy even while periodic flushing is paused.
      // A fresh executor barrier drains them before Mockito installs per-mock pending answers.
      // Fixture writers stay on this thread, so no new task is submitted during stubbing.
      write.pauseBackgroundFlush();
    }

    void observe() {
      awaitAllocationTasks();
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
          assertEquals(0, epoch.enterSeq());
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

  // Either pause callback may fail. Cleanup releases every lock, pin and reservation,
  // and a failed apply must not increment any completed-protocol counter.
  @Test
  public void throwingPageSetPauseReleasesReservation() throws Exception {
    for (boolean published : new boolean[] {false, true}) {
      try (var f = new Fixture()) {
        long file = f.write.addFile("pause-failure");
        var op = f.operation(true);
        f.change(op, file, 0, false);
        f.change(op, file, 1, false);
        var failure = new IllegalStateException("pause failure");
        AtomicOperationTestBridge.installPageSetPauseHook(op, (pages, firstPublished) -> {
          if (firstPublished == published) {
            throw failure;
          }
        });
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> op.commitChanges(42, f.wal)));
        assertEquals(0, f.read.reservedWritePins());
        for (long page = 0; page < 2; page++) {
          var entry = f.read.getCacheEntryOptimistic(file, page);
          assertNotEquals(0, entry.getCachePointer().getPageFrame().tryOptimisticRead());
        }
        for (var outcome : CommitProtocolCounters.Outcome.values()) {
          assertEquals(0, f.counters.count(outcome));
        }
        AtomicOperationTestBridge.installPageSetPauseHook(op, null);
      }
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
        assertEquals(enabled ? 0 : 1, f.epoch.enterSeq());
        assertEquals(enabled ? 0 : 1, f.epoch.exitSeq());
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
    for (int mode = 0; mode < 4; mode++) {
      try (var f = new Fixture()) {
        long file = f.write.addFile("fallback");
        var op = f.operation(true);
        op.loadFile("fallback");
        int count = mode == 0 ? 5 : mode == 1 ? 2 : mode == 3 ? 1025 : 1;
        for (int i = 0; i < count; i++) {
          f.change(op, file, i, false);
        }
        if (mode == 1 || mode == 3) {
          doReturn(mode == 1 ? 1 : 2048).when(f.read).writePinBudget();
        }
        var held = mode == 2 ? f.read.tryReserveWritePins(4) : null;
        clearInvocations(f.read, f.write);
        doAnswer(c -> {
          verify(f.write, never()).loadOrAdd(anyLong(), anyLong(), anyBoolean());
          return c.callRealMethod();
        }).when(f.read).tryReserveWritePins(anyInt());
        var before = snapshot(f.counters);
        try {
          assertNotNull(op.commitChanges(42, f.wal));
          assertDelta(f.counters, before, mode == 2
              ? CommitProtocolCounters.Outcome.RESERVATION_DENIED
              : CommitProtocolCounters.Outcome.OVER_LIMIT);
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
        assertEquals(1, f.epoch.enterSeq());
        assertEquals(1, f.epoch.exitSeq());
      }
    }
  }

  // Exactly 1024 changed pages fit the internal limit with a real reservation budget.
  // The neighboring 1025-page rejection above must not turn this boundary into fallback.
  @Test
  public void exactPageSetLimitUsesPageSetApply() throws Exception {
    try (var f = new Fixture(2048)) {
      long file = f.write.addFile("boundary");
      var op = f.operation(true);
      op.loadFile("boundary");
      for (int i = 0; i < 1024; i++) {
        f.change(op, file, i, false);
      }
      clearInvocations(f.read);
      var before = snapshot(f.counters);
      assertNotNull(op.commitChanges(42, f.wal));
      assertDelta(f.counters, before, CommitProtocolCounters.Outcome.PAGE_SET);
      verify(f.read).tryReserveWritePins(1024);
      verify(f.read, never()).loadOrAddForWrite(anyLong(), anyLong(), any(),
          anyBoolean(), any());
      assertEquals(0, f.epoch.enterSeq());
      assertEquals(0, f.epoch.exitSeq());
      assertEquals(0, f.read.reservedWritePins());
      var entry = f.read.loadForRead(file, 1023, f.write, false);
      try {
        assertEquals((byte) (1023 + 21), entry.getCachePointer().getBuffer().get(100));
      } finally {
        f.read.releaseFromRead(entry);
      }
    }
  }

  // A real disk operation is page-set eligible until bridge hook installation forces
  // fallback. The hook must run inside the epoch without an explicit forceEpochApply call.
  @Test
  public void bridgeHookInstallationForcesEpochApply() throws Exception {
    try (var f = new Fixture()) {
      long file = f.write.addFile("hook");
      var op = f.operation(true);
      op.loadFile("hook");
      f.change(op, file, 0, false);
      var calls = new AtomicInteger();
      AtomicOperationTestBridge.installPageApplyHook(op,
          new AtomicOperationTestBridge.TestPageApplyHook() {
            @Override
            public void beforePageApply(long fileId, long pageIndex) {
              assertEquals(file, fileId);
              assertEquals(0, pageIndex);
              assertEquals(1, f.epoch.enterSeq());
              assertEquals(0, f.epoch.exitSeq());
              calls.incrementAndGet();
            }
          });
      var before = snapshot(f.counters);
      assertNotNull(op.commitChanges(42, f.wal));
      assertDelta(f.counters, before, CommitProtocolCounters.Outcome.FORCED);
      assertEquals(1, calls.get());
      assertEquals(1, f.epoch.enterSeq());
      assertEquals(1, f.epoch.exitSeq());
      verify(f.read, never()).tryReserveWritePins(anyInt());
      assertEquals(0, f.read.reservedWritePins());
    }
  }

  // Direct hook installation on a real cache with a positive pin budget must force
  // fallback, invoke the hook inside the epoch, and count only FORCED.
  @Test
  public void directHookInstallationForcesEpochApply() throws Exception {
    try (var f = new Fixture()) {
      long file = f.write.addFile("direct-hook");
      var op = f.operation(true);
      op.loadFile("direct-hook");
      f.change(op, file, 0, false);
      assertTrue(f.read.writePinBudget() > 0);
      var calls = new AtomicInteger();
      op.setPageApplyHook(new AtomicOperationBinaryTracking.PageApplyHook() {
        @Override
        public void beforePageApply(long fileId, long pageIndex) {
          assertEquals(file, fileId);
          assertEquals(0, pageIndex);
          assertEquals(1, f.epoch.enterSeq());
          assertEquals(0, f.epoch.exitSeq());
          calls.incrementAndGet();
        }
      });
      var before = snapshot(f.counters);
      assertNotNull(op.commitChanges(42, f.wal));
      assertDelta(f.counters, before, CommitProtocolCounters.Outcome.FORCED);
      assertEquals(1, calls.get());
      assertEquals(1, f.epoch.enterSeq());
      assertEquals(1, f.epoch.exitSeq());
      verify(f.read, never()).tryReserveWritePins(anyInt());
      assertEquals(0, f.read.reservedWritePins());
    }
  }

  // Real disk selection bypasses every registered component epoch. Forced fallback
  // brackets each registered domain exactly once.
  @Test
  public void protocolSelectionCoversRegisteredComponentEpochs() throws Exception {
    for (boolean pageSet : new boolean[] {true, false}) {
      try (var f = new Fixture()) {
        long file = f.write.addFile("components");
        var op = f.operation(pageSet);
        var epochs = new ApplyPhaseEpoch[] {new ApplyPhaseEpoch(), new ApplyPhaseEpoch()};
        for (var epoch : epochs) {
          var component = mock(StorageComponent.class);
          when(component.getApplyPhaseEpoch()).thenReturn(epoch);
          op.addLockedComponent(component);
        }
        op.loadFile("components");
        f.change(op, file, 0, false);
        clearInvocations(f.read, f.write);
        doAnswer(c -> {
          verify(f.read, never()).loadOrAddForWrite(anyLong(), anyLong(), any(),
              anyBoolean(), any());
          verify(f.read, never()).loadForRead(anyLong(), anyLong(), any(), anyBoolean());
          verify(f.write, never()).loadOrAdd(anyLong(), anyLong(), anyBoolean());
          return c.callRealMethod();
        }).when(f.read).tryReserveWritePins(anyInt());
        assertNotNull(op.commitChanges(42, f.wal));
        for (var epoch : epochs) {
          assertEquals(pageSet ? 0 : 1, epoch.enterSeq());
          assertEquals(pageSet ? 0 : 1, epoch.exitSeq());
        }
        assertEquals(0, f.read.reservedWritePins());
      }
    }
  }

  // The actual in-memory cache keeps its zero-budget default. A content-changing
  // operation therefore uses fallback and balances its epoch without a test override.
  @Test
  public void inMemoryCommitUsesFallback() throws Exception {
    var constructor = DirectMemoryOnlyDiskCache.class
        .getDeclaredConstructor(int.class, int.class, String.class);
    constructor.setAccessible(true);
    var cache = constructor.newInstance(DurablePage.MAX_PAGE_SIZE_BYTES, 1, "selection");
    try (var f = new Fixture()) {
      try {
        long file = cache.addFile("memory");
        var op = new AtomicOperationBinaryTracking(cache, cache, f.wal, 1,
            new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100),
            new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(), new AtomicLong(),
            new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(), new AtomicLong(),
            f.epoch, f.counters);
        op.startToApplyOperations(42);
        op.loadFile("memory");
        var page = op.allocatePageForWrite(file, 0);
        page.getChanges().setByteValue(null, (byte) 31, 100);
        op.registerPageOperation(file, 0,
            new TestPageOperation(0, file, 0, new LogSequenceNumber(0, 0), 0));
        op.releasePageFromWrite(page);
        var before = snapshot(f.counters);
        assertNotNull(op.commitChanges(42, f.wal));
        assertDelta(f.counters, before, CommitProtocolCounters.Outcome.NO_PIN_BUDGET);
        assertEquals(1, f.epoch.enterSeq());
        assertEquals(1, f.epoch.exitSeq());
        var entry = cache.loadForRead(file, 0, cache, false);
        try {
          assertEquals(31, entry.getCachePointer().getBuffer().get(100));
        } finally {
          cache.releaseFromRead(entry);
        }
      } finally {
        cache.delete();
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
      assertEquals(0, f.epoch.enterSeq());
      assertEquals(0, f.epoch.exitSeq());
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

  // A real miss acquires a pin before overflow checking. Interruption on the second miss
  // returns both that pin and the earlier reservation pin without entering the lock phase.
  @Test
  public void interruptedCacheMissRestoresInterruptAndReturnsAllPins() throws Exception {
    try (var f = new Fixture()) {
      long file = f.write.addFile("interrupted-miss");
      var op = f.operation(true);
      op.loadFile("interrupted-miss");
      f.change(op, file, 0, false);
      f.change(op, file, 1, false);
      f.read.clear();
      assertNull(f.read.getCacheEntryOptimistic(file, 0));
      assertNull(f.read.getCacheEntryOptimistic(file, 1));
      var interrupted = new InterruptedException("overflow");
      var checks = new AtomicInteger();
      f.awaitAllocationTasks();
      clearInvocations(f.read);
      doAnswer(call -> {
        if (checks.incrementAndGet() == 2) {
          throw interrupted;
        }
        return call.callRealMethod();
      }).when(f.write).checkCacheOverflow();
      try {
        var thrown = assertThrows(
            com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException.class,
            () -> op.commitChanges(42, f.wal));
        assertSame(interrupted, thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(2, checks.get());
        assertEquals(0, f.read.reservedWritePins());
        verify(f.read, never()).loadOrAddForWrite(anyLong(), anyLong(), any(),
            anyBoolean(), any());
        verify(f.write, never()).store(anyLong(), anyLong(), any());
        for (int page = 0; page < 2; page++) {
          var entry = f.read.getCacheEntryOptimistic(file, page);
          assertNotNull(entry);
          assertNotEquals(0, entry.getCachePointer().getPageFrame().tryOptimisticRead());
          com.jetbrains.youtrackdb.internal.core.storage.cache.chm.PageSetEntryAssertions
              .assertPins(entry, 0);
        }
      } finally {
        Thread.interrupted();
      }
    }
  }

  private static long[] snapshot(CommitProtocolCounters counters) {
    return Arrays.stream(CommitProtocolCounters.Outcome.values()).mapToLong(counters::count)
        .toArray();
  }

  private static void assertDelta(CommitProtocolCounters counters, long[] before,
      CommitProtocolCounters.Outcome expected) {
    for (var outcome : CommitProtocolCounters.Outcome.values()) {
      assertEquals(outcome.name(), outcome == expected ? 1 : 0,
          counters.count(outcome) - before[outcome.ordinal()]);
    }
    long expectedEpoch = Arrays.stream(CommitProtocolCounters.Outcome.values())
        .filter(o -> o != CommitProtocolCounters.Outcome.PAGE_SET
            && o != CommitProtocolCounters.Outcome.ZERO_CHANGE)
        .mapToLong(counters::count).sum();
    assertEquals(expectedEpoch, counters.epochCount());
  }

  // Each selection increments only its own outcome, even when the fixture already has counts.
  // Forced precedes lifecycle and capacity, lifecycle precedes zero budget, and zero-change
  // overrides forced selection. Both create and delete count as lifecycle.
  @Test
  public void completedCommitsCountExactlyOneOutcomeWithSelectionPrecedence() throws Exception {
    try (var f = new Fixture()) {
      for (int mode = 0; mode < 10; mode++) {
        String name = "counter-" + mode;
        long file = f.write.addFile(name);
        var op = f.operation(mode != 5 && mode != 7);
        op.loadFile(name);
        if (mode != 6 && mode != 7 && mode != 9) {
          f.change(op, file, 0, false);
        }
        doReturn(mode == 3 || mode == 8 ? 0 : mode == 2 ? 1 : 4)
            .when(f.read).writePinBudget();
        if (mode == 1 || mode == 5 || mode == 8) {
          op.addFile("new-" + mode);
        } else if (mode == 9) {
          op.deleteFile(file);
        }
        if (mode == 2) {
          f.change(op, file, 1, false);
        }
        var held = mode == 4 ? f.read.tryReserveWritePins(4) : null;
        var expected = switch (mode) {
          case 0 -> CommitProtocolCounters.Outcome.PAGE_SET;
          case 1, 8, 9 -> CommitProtocolCounters.Outcome.FILE_LIFECYCLE;
          case 2 -> CommitProtocolCounters.Outcome.OVER_LIMIT;
          case 3 -> CommitProtocolCounters.Outcome.NO_PIN_BUDGET;
          case 4 -> CommitProtocolCounters.Outcome.RESERVATION_DENIED;
          case 5 -> CommitProtocolCounters.Outcome.FORCED;
          default -> CommitProtocolCounters.Outcome.ZERO_CHANGE;
        };
        var before = snapshot(f.counters);
        try {
          op.commitChanges(42, f.wal);
          assertDelta(f.counters, before, expected);
        } finally {
          if (held != null) {
            held.close();
          }
        }
      }
    }
  }

  // A throwing epoch hook must leave every count unchanged. A successful retry operation
  // proves that the same counters still observe subsequent completed applies.
  @Test
  public void failedEpochApplyDoesNotCount() throws Exception {
    try (var f = new Fixture()) {
      long file = f.write.addFile("epoch-failure");
      var op = f.operation(true);
      op.loadFile("epoch-failure");
      f.change(op, file, 0, false);
      var failure = new IllegalStateException("apply failed");
      AtomicOperationTestBridge.installPageApplyHook(op,
          new AtomicOperationTestBridge.TestPageApplyHook() {
            @Override
            public void beforePageApply(long fileId, long pageIndex) {
              throw failure;
            }
          });
      var before = snapshot(f.counters);
      assertSame(failure, assertThrows(IllegalStateException.class,
          () -> op.commitChanges(42, f.wal)));
      assertDelta(f.counters, before, null);
      var next = f.operation(false);
      next.loadFile("epoch-failure");
      f.change(next, file, 0, false);
      next.commitChanges(42, f.wal);
      assertDelta(f.counters, before, CommitProtocolCounters.Outcome.FORCED);
    }
  }

  private enum FailureStep {
    PIN_FIRST, PIN_MIDDLE, PIN_LAST, DUPLICATE, LOCK, END_LSN, DIRTY, BACKUP, CONTENT, PUBLISH, CLOSE_PUBLISH, PUBLISH_UNLOCK, CLOSE_PUBLISH_UNLOCK, UNLOCK, CLOSE_UNLOCK, RELEASE
  }

  // Each injected exit reaches the commit caller with exact error identity. Real frames,
  // pins, write-cache records and backup candidates must agree after either throwable route.
  @Test
  public void everyFailureExitBalancesRealFramesPinsAndBudget() throws Exception {
    for (var step : FailureStep.values()) {
      for (boolean error : new boolean[] {false, true}) {
        checkFailureExit(step, error, false);
      }
    }
  }

  // Reusing the body throwable during cleanup requires a fresh suppressed aggregate.
  @Test
  public void bodyFailureOwnsCleanupAggregateIncludingReusedThrowable() throws Exception {
    for (var step : new FailureStep[] {FailureStep.UNLOCK, FailureStep.RELEASE}) {
      for (boolean error : new boolean[] {false, true}) {
        checkFailureExit(step, error, true);
      }
    }
  }

  private void checkFailureExit(FailureStep step, boolean error, boolean bodyFails)
      throws Exception {
    try {
      runFailureExit(step, error, bodyFails);
    } catch (Throwable failure) {
      throw new AssertionError("step=" + step + ", error=" + error + ", bodyFails=" + bodyFails,
          failure);
    }
  }

  private void runFailureExit(FailureStep step, boolean error, boolean bodyFails)
      throws Exception {
    boolean combined = step == FailureStep.PUBLISH_UNLOCK
        || step == FailureStep.CLOSE_PUBLISH_UNLOCK;
    try (var f = new Fixture()) {
      long a = f.write.addFile("failure-first");
      long b = f.write.addFile("failure-second");
      long first = Math.min(a, b);
      long second = Math.max(a, b);
      long[] files = {first, first, second, second};
      int[] pages = {0, 1, 0, 1};
      var originals = new CacheEntry[4];
      var entries = new CacheEntry[4];
      // Clean preloads leave operation content private and dirty-table assertions unambiguous.
      for (int i = 0; i < 4; i++) {
        originals[i] = f.read.loadForRead(files[i], pages[i], f.write, false);
        f.read.releaseFromRead(originals[i]);
      }
      f.awaitAllocationTasks();
      var op = f.operation(true);
      op.loadFile("failure-second");
      op.loadFile("failure-first");
      for (int i : new int[] {3, 0, 2, 1}) {
        f.change(op, files[i], pages[i], false);
      }
      var contentEntry = (CacheEntryChanges) op.loadPageForWrite(first, 1, 1, false);
      op.releasePageFromWrite(contentEntry);
      for (int i = 0; i < 4; i++) {
        entries[i] = spy(originals[i]);
        com.jetbrains.youtrackdb.internal.core.storage.cache.chm.PageSetEntryAssertions
            .replace(f.read, entries[i]);
      }
      Throwable failure = error ? new AssertionError(step.name())
          : new IllegalStateException(step.name());
      // Exercise distinct failures and the self-suppression guard on both publication routes.
      Throwable unlockFailure = error ? new IllegalStateException("unlock") : failure;
      var injected = new AtomicInteger();
      var stores = new ArrayList<CacheEntry>();
      var dirty = new ArrayList<CacheEntry>();
      var reservationRef = new ReadCache.WriteReservation[1];
      try {
        clearInvocations(f.read, f.write);
        doAnswer(call -> {
          var pointer = call.getArgument(0,
              com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer.class);
          var entry = Arrays.stream(entries).filter(e -> e.getCachePointer() == pointer)
              .findFirst().orElseThrow();
          if (entry == entries[1] && step == FailureStep.DIRTY) {
            injected.incrementAndGet();
            throw failure;
          }
          var result = call.callRealMethod();
          dirty.add(entry);
          return result;
        }).when(f.write).updateDirtyPagesTable(any(), any());
        doAnswer(call -> {
          var pointer = call.getArgument(2,
              com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer.class);
          var entry = Arrays.stream(entries).filter(e -> e.getCachePointer() == pointer)
              .findFirst().orElseThrow();
          assertEquals(0, pointer.getPageFrame().tryOptimisticRead());
          stores.add(entry);
          var result = call.callRealMethod();
          if ((step == FailureStep.PUBLISH || step == FailureStep.CLOSE_PUBLISH || combined)
              && entry == entries[1]) {
            // Store may install its reference before throwing. Keep the reservation pin.
            injected.incrementAndGet();
            throw failure;
          }
          return result;
        }).when(f.write).store(anyLong(), anyLong(), any());
        doAnswer(call -> {
          var reservation = spy((ReadCache.WriteReservation) call.callRealMethod());
          reservationRef[0] = reservation;
          if (step == FailureStep.CLOSE_UNLOCK || step == FailureStep.CLOSE_PUBLISH
              || step == FailureStep.CLOSE_PUBLISH_UNLOCK) {
            // Leave all prepared pages to the real close implementation.
            org.mockito.Mockito.doNothing().when(reservation).publishAndUnlock(any());
          }
          var pinIndex = new AtomicInteger();
          doAnswer(c -> {
            int index = pinIndex.getAndIncrement();
            f.events.add("pin " + index);
            int failIndex = step == FailureStep.PIN_FIRST ? 0
                : step == FailureStep.PIN_MIDDLE ? 2 : 3;
            if ((step == FailureStep.PIN_FIRST || step == FailureStep.PIN_MIDDLE
                || step == FailureStep.PIN_LAST) && index == failIndex) {
              doAnswer(acquisition -> {
                injected.incrementAndGet();
                throw failure;
              }).when(entries[index]).acquireEntry();
            }
            if (step == FailureStep.DUPLICATE && index == 1) {
              injected.incrementAndGet();
              return reservation.pin(first, 0, f.write, false);
            }
            return c.callRealMethod();
          }).when(reservation).pin(anyLong(), anyLong(), any(), anyBoolean());
          return reservation;
        }).when(f.read).tryReserveWritePins(4);
        if (step == FailureStep.LOCK) {
          doAnswer(c -> {
            injected.incrementAndGet();
            throw failure;
          }).when(entries[1]).acquireExclusiveLock();
        }
        if (step == FailureStep.END_LSN) {
          doAnswer(c -> {
            injected.incrementAndGet();
            throw failure;
          }).when(entries[1]).setEndLSN(any());
        }
        if (step == FailureStep.BACKUP) {
          PageSetTrackingAssertions.failBackupMark(f.tracker, f.write.internalFileId(first), 1,
              failure, f.events);
        }
        if (step == FailureStep.CONTENT || bodyFails) {
          var content = spy(contentEntry.changes);
          var field = CacheEntryChanges.class.getDeclaredField("changes");
          field.setAccessible(true);
          field.set(contentEntry, content);
          doAnswer(c -> {
            assertEquals(0, entries[1].getCachePointer().getPageFrame().tryOptimisticRead());
            assertTrue(dirty.contains(entries[1]));
            c.getArgument(0, java.nio.ByteBuffer.class).put(100, (byte) 99);
            injected.incrementAndGet();
            throw failure;
          }).when(content).applyChanges(any());
        }
        if (step == FailureStep.UNLOCK || step == FailureStep.CLOSE_UNLOCK || combined) {
          doAnswer(c -> {
            c.callRealMethod();
            injected.incrementAndGet();
            throw combined ? unlockFailure : failure;
          }).when(entries[bodyFails || combined ? 1 : 3]).releaseExclusiveLock();
        }
        if (step == FailureStep.RELEASE) {
          for (int releaseIndex : new int[] {2, 3}) {
            doAnswer(c -> {
              for (var entry : entries) {
                assertNotEquals(0, entry.getCachePointer().getPageFrame().tryOptimisticRead());
              }
              injected.incrementAndGet();
              throw failure;
            }).when(entries[releaseIndex]).releaseEntry();
          }
        }
        var before = snapshot(f.counters);
        var thrown = assertThrows(Throwable.class, () -> op.commitChanges(42, f.wal));
        assertDelta(f.counters, before, null);
        assertEquals(step.name(), step == FailureStep.BACKUP ? 1 : 0,
            f.events.stream().filter("backup failure"::equals).count());
        assertEquals(step.name(), step == FailureStep.BACKUP ? 0
            : (bodyFails ? 1 : 0) + (step == FailureStep.RELEASE || combined ? 2 : 1),
            injected.get());
        if (step == FailureStep.DUPLICATE) {
          assertEquals(IllegalArgumentException.class, thrown.getClass());
          assertEquals("Page is already pinned by this reservation", thrown.getMessage());
          assertEquals(0, thrown.getSuppressed().length);
        } else if ((step == FailureStep.RELEASE || step == FailureStep.CLOSE_UNLOCK
            || step == FailureStep.CLOSE_PUBLISH
            || step == FailureStep.CLOSE_PUBLISH_UNLOCK) && !bodyFails) {
          assertNotSame(failure, thrown);
          assertSame(failure, thrown.getCause());
          assertEquals(error, thrown instanceof Error);
          assertArrayEquals(step == FailureStep.RELEASE ? new Throwable[] {failure}
              : new Throwable[] {}, thrown.getSuppressed());
        } else {
          assertSame(failure, thrown);
          if (bodyFails) {
            assertEquals(1, thrown.getSuppressed().length);
            var aggregate = thrown.getSuppressed()[0];
            assertNotSame(failure, aggregate);
            assertSame(failure, aggregate.getCause());
            assertEquals(error, aggregate instanceof Error);
            assertArrayEquals(step == FailureStep.RELEASE ? new Throwable[] {failure}
                : new Throwable[] {}, aggregate.getSuppressed());
          } else {
            assertArrayEquals(combined && error ? new Throwable[] {unlockFailure}
                : new Throwable[] {}, thrown.getSuppressed());
          }
        }
        if (combined) {
          assertEquals(error, thrown instanceof Error);
          assertArrayEquals(error ? new Throwable[] {unlockFailure} : new Throwable[] {},
              failure.getSuppressed());
        }
        verify(f.read, never()).loadOrAddForWrite(anyLong(), anyLong(), any(),
            anyBoolean(), any());
        verify(reservationRef[0]).close();
        assertEquals(0, f.epoch.enterSeq());
        assertEquals(0, f.epoch.exitSeq());
        int prepared = switch (step) {
          case PIN_FIRST, PIN_MIDDLE, PIN_LAST, DUPLICATE, LOCK -> 0;
          case END_LSN, DIRTY, BACKUP -> 1;
          case CONTENT, PUBLISH, PUBLISH_UNLOCK -> 2;
          case UNLOCK, CLOSE_UNLOCK, CLOSE_PUBLISH, CLOSE_PUBLISH_UNLOCK, RELEASE ->
              bodyFails ? 2 : 4;
        };
        int changed = prepared;
        assertEquals(prepared, stores.size());
        assertEquals(prepared, dirty.size());
        assertEquals(step == FailureStep.RELEASE ? 2
            : step == FailureStep.PUBLISH || step == FailureStep.CLOSE_PUBLISH || combined ? 1 : 0,
            f.read.reservedWritePins());
        for (int i = 0; i < 4; i++) {
          var entry = entries[i];
          assertNotEquals("frame " + i, 0,
              entry.getCachePointer().getPageFrame().tryOptimisticRead());
          com.jetbrains.youtrackdb.internal.core.storage.cache.chm.PageSetEntryAssertions
              .assertPins(entry, ((step == FailureStep.PUBLISH
                  || step == FailureStep.CLOSE_PUBLISH || combined) && i == 1)
                  || (step == FailureStep.RELEASE && i >= 2) ? 1 : 0);
          assertEquals(i < prepared, stores.contains(entry));
          assertEquals(i < prepared, dirty.contains(entry));
          assertEquals(i == 1 && (step == FailureStep.CONTENT || bodyFails) ? 99
              : i < changed ? pages[i] + 21 : 0,
              entry.getCachePointer().getBuffer().get(100));
          assertEquals(i < changed && !(i == 1 && (step == FailureStep.CONTENT || bodyFails))
              ? 7 : -1, new DurablePage(entry).getLsn().getSegment());
          var records = PageSetTrackingAssertions.dirtyPages(f.write,
              f.write.internalFileId(files[i]));
          assertEquals(i < prepared, records.containsKey((long) pages[i]));
          if (i < prepared) {
            assertEquals(new LogSequenceNumber(7, 1), records.get((long) pages[i]));
          }
          // END_LSN fails before the setter. DIRTY and BACKUP fail after it, so the
          // unchanged page keeps the transaction boundary without a dirty-table record.
          if (i < prepared || (i == 1
              && (step == FailureStep.DIRTY || step == FailureStep.BACKUP))) {
            assertEquals(new LogSequenceNumber(7, f.lsn.get() - 1), entry.getEndLSN());
          } else {
            assertNull(entry.getEndLSN());
          }
        }
        PageSetTrackingAssertions.assertPages(f.tracker, f.write.internalFileId(first),
            prepared == 0 ? new long[] {} : prepared == 1 ? new long[] {0}
                : new long[] {0, 1});
        PageSetTrackingAssertions.assertPages(f.tracker, f.write.internalFileId(second),
            prepared <= 2 ? new long[] {} : new long[] {0, 1});
      } finally {
        // A killed mutation must not hang fixture cleanup on its deliberately leaked lock.
        for (int i = 0; i < 4; i++) {
          org.mockito.Mockito.reset(entries[i]);
          if (entries[i].getCachePointer().getPageFrame().tryOptimisticRead() == 0) {
            entries[i].releaseExclusiveLock();
          }
          while (!entries[i].isReleased()) {
            entries[i].releaseEntry();
          }
          com.jetbrains.youtrackdb.internal.core.storage.cache.chm.PageSetEntryAssertions
              .replace(f.read, originals[i]);
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
        assertEquals(mode == 0 ? 0 : 1, f.epoch.enterSeq());
        assertEquals(mode == 0 ? 0 : 1, f.epoch.exitSeq());
        assertEquals(0, f.read.reservedWritePins());
      }
    }
  }
}
