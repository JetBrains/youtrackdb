package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters.Cause;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationTestBridge;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CommitProtocolCounters.Outcome;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

@Category(SequentialTest.class)
public class PageSetPausedReaderTest {
  @Rule
  public TemporaryFolder directory = new TemporaryFolder();
  private YouTrackDBImpl db;
  private AbstractStorage storage;
  private AtomicOperationsManager manager;
  private ReadCache original;
  private ReadCache cache;
  private SharedLinkBagBTree left;
  private SharedLinkBagBTree right;
  private Probe probe;
  private final EdgeKey key = new EdgeKey(7, 3, 0, 0);

  @Before
  public void setUp() throws Exception {
    db = (YouTrackDBImpl) YourTracks.instance(directory.newFolder().getPath());
    db.create("paused", DatabaseType.DISK, "admin", "admin", "admin");
    try (var session = db.open("paused", "admin", "admin")) {
      storage = session.getStorage();
    }
    manager = storage.getAtomicOperationsManager();
    original = storage.getReadCache();
    assertTrue(original instanceof LockFreeReadCache);
    assertTrue(storage.getWriteCache() instanceof WOWCache);
    // A bounded real disk read cache makes eviction deterministic without global configuration.
    cache = spy(new LockFreeReadCache(ByteBufferPool.instance(null),
        1024L * DurablePage.MAX_PAGE_SIZE_BYTES, DurablePage.MAX_PAGE_SIZE_BYTES));
    // Components and manager-created operations share this real cache spy. Restore before close.
    var field = AbstractStorage.class.getDeclaredField("readCache");
    field.setAccessible(true);
    field.set(storage, cache);
    var managerCache = AtomicOperationsManager.class.getDeclaredField("readCache");
    managerCache.setAccessible(true);
    managerCache.set(manager, cache);
    left = new SharedLinkBagBTree(storage, "pausedLeft", ".sbc");
    right = new SharedLinkBagBTree(storage, "pausedRight", ".sbc");
    probe = new Probe();
    manager.executeInsideAtomicOperation(op -> {
      left.create(op);
      right.create(op);
      put(left, op, 1);
      put(right, op, 1);
    });
    if (left.getFileId() > right.getFileId()) {
      var swap = left;
      left = right;
      right = swap;
    }
  }

  @After
  public void tearDown() throws Exception {
    if (storage != null && original != null) {
      if (cache != null) {
        cache.clear();
      }
      var field = AbstractStorage.class.getDeclaredField("readCache");
      field.setAccessible(true);
      field.set(storage, original);
      var managerCache = AtomicOperationsManager.class.getDeclaredField("readCache");
      managerCache.setAccessible(true);
      managerCache.set(manager, original);
    }
    if (db != null) {
      db.close();
    }
  }

  private final class Probe extends StorageComponent {
    Probe() {
      super(PageSetPausedReaderTest.this.storage, "probe", "", "probe", true);
    }

    PageView page(AtomicOperation op, long file, long page) {
      return loadPageOptimistic(op, file, page);
    }
  }

  private void put(SharedLinkBagBTree tree, AtomicOperation op, int value)
      throws java.io.IOException {
    tree.put(op, key, new LinkBagValue(value, 0, 0, false));
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue("barrier timed out", latch.await(20, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static boolean contains(CacheEntry[] pages, long file, long index) {
    return Arrays.stream(pages).anyMatch(p -> p.getFileId() == file && p.getPageIndex() == index);
  }

  @FunctionalInterface
  private interface ReadCheck {
    void run(AtomicOperation reader, CacheEntry[] pages) throws Exception;
  }

  // Each test owns one writer. Exact storage and process-wide counter deltas exclude setup/close.
  private void paused(boolean twoTrees, boolean published, boolean split, int stampFailures,
      ReadCheck check) throws Exception {
    var ready = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    var observed = new CacheEntry[1][];
    var counters = manager.getCommitProtocolCounters();
    var before = Arrays.stream(Outcome.values()).mapToLong(counters::count).toArray();
    var failures = Arrays.stream(Cause.values()).mapToLong(OptimisticReadFailureCounters::count)
        .toArray();
    // Capture a stamp before the writer starts. This operation has no private changes.
    var reader = manager.startAtomicOperation();
    try (var readerCleanup = (AutoCloseable) () -> manager.endAtomicOperation(reader, null);
        var executorCleanup = (AutoCloseable) () -> {
          executor.shutdownNow();
          assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }) {
      manager.startToApplyOperations(reader);
      var old = probe.page(reader, twoTrees ? right.getFileId() : left.getFileId(), 1);
      assertEquals(1, new Bucket(old).getValue(0,
          storage.getComponentsFactory().binarySerializerFactory).counter());
      var writer = executor.submit(() -> {
        manager.executeInsideAtomicOperation(op -> {
          put(left, op, 2);
          if (twoTrees) {
            put(right, op, 2);
          }
          if (split) {
            for (int i = 1; i < 1000; i++) {
              left.put(op, new EdgeKey(7, 3, i, 0), new LinkBagValue(2, 0, 0, false));
            }
          }
          AtomicOperationTestBridge.installPageSetPauseHook(op, (pages, firstPublished) -> {
            if (firstPublished == published) {
              observed[0] = pages.clone();
              ready.countDown();
              await(resume);
            }
          });
        });
        return null;
      });
      // Resource cleanup suppresses writer failures onto the original reader failure.
      try (var writerCleanup = (AutoCloseable) () -> {
        resume.countDown();
        writer.get(30, TimeUnit.SECONDS);
      }) {
        await(ready);
        assertTrue(contains(observed[0], left.getFileId(), 1));
        assertEquals(twoTrees, contains(observed[0], right.getFileId(), 1));
        check.run(reader, observed[0]);
      }
      var after = Arrays.stream(Outcome.values()).mapToLong(counters::count).toArray();
      for (var outcome : Outcome.values()) {
        assertEquals(outcome.name(), outcome == Outcome.PAGE_SET ? 1 : 0,
            after[outcome.ordinal()] - before[outcome.ordinal()]);
      }
      for (var cause : Cause.values()) {
        assertEquals(cause.name(), cause == Cause.STAMP ? stampFailures : 0,
            OptimisticReadFailureCounters.count(cause) - failures[cause.ordinal()]);
      }
    }
  }

  // A different component remains readable while every frame in the writer's set is locked.
  @Test
  public void outsideSetReadSucceeds() throws Exception {
    paused(false, false, false, 0, (reader, pages) -> {
      assertEquals(1, pages.length);
      reader.getOptimisticReadScope().reset();
      var view = probe.page(reader, right.getFileId(), 1);
      assertEquals(1, new Bucket(view).getValue(0,
          storage.getComponentsFactory().binarySerializerFactory).counter());
      reader.getOptimisticReadScope().validateOrThrow();
      assertEquals(0, pages[0].getCachePointer().getPageFrame().tryOptimisticRead());
    });
  }

  // Both an earlier recorded stamp and a new lookup fail inside the all-locked window.
  @Test
  public void insideSetAndEarlierStampFail() throws Exception {
    paused(false, false, false, 2, (reader, pages) -> {
      assertThrows(OptimisticReadFailedException.class,
          reader.getOptimisticReadScope()::validateOrThrow);
      reader.getOptimisticReadScope().reset();
      assertThrows(OptimisticReadFailedException.class,
          () -> probe.page(reader, left.getFileId(), 1));
    });
  }

  // One raw scope spans both logical components: old right plus new left must not validate.
  @Test
  public void partialTwoComponentCommitFails() throws Exception {
    paused(true, true, false, 1, (reader, pages) -> {
      assertEquals(2, pages.length);
      assertEquals(left.getFileId(), pages[0].getFileId());
      // Retain the old right stamp, then record the new left page in that same scope.
      assertNotEquals(0, pages[0].getCachePointer().getPageFrame().tryOptimisticRead());
      assertEquals(0, pages[1].getCachePointer().getPageFrame().tryOptimisticRead());
      assertEquals(2, new Bucket(probe.page(reader, left.getFileId(), 1)).getValue(0,
          storage.getComponentsFactory().binarySerializerFactory).counter());
      assertEquals(2, reader.getOptimisticReadScope().count());
      assertThrows(OptimisticReadFailedException.class,
          reader.getOptimisticReadScope()::validateOrThrow);
    });
  }

  // Eviction can leave a retained frame's stamp valid. A paused commit locks the replacement,
  // so only final cached-frame identity validation can reject the reader's old observation.
  @Test
  public void evictedCommitSetFrameFailsIdentityDespiteValidStamp() throws Exception {
    long[] file = new long[1];
    manager.executeInsideAtomicOperation(op -> {
      file[0] = op.addFile("identity-pages.dat");
      for (int i = 0; i < 1100; i++) {
        var entry = op.allocatePageForWrite(file[0], i);
        new Bucket(entry).init(true);
        op.releasePageFromWrite(entry);
      }
    });
    // Remove dirty write-cache references so a later miss loads a distinct frame from disk.
    storage.getWriteCache().flush(file[0]);
    record Retained(PageView view,
        CachePointer pointer, OptimisticReadScope scope) {
    }
    var captured = new ArrayList<Retained>();
    try (var references = (AutoCloseable) () -> captured.forEach(
        item -> item.pointer().decrementReadersReferrer())) {
      for (int i = 0; i < 1100; i++) {
        var entry = cache.loadForRead(file[0], i, storage.getWriteCache(), true);
        try {
          var pointer = entry.getCachePointer();
          pointer.incrementReadersReferrer();
          var frame = pointer.getPageFrame();
          long stamp = frame.tryOptimisticRead();
          var scope = new OptimisticReadScope();
          scope.record(frame, stamp, entry);
          captured.add(new Retained(new PageView(pointer.getBuffer(), frame, stamp, entry),
              pointer, scope));
        } finally {
          cache.releaseFromRead(entry);
        }
      }
      var assertSize = LockFreeReadCache.class.getDeclaredMethod("assertSize");
      assertSize.setAccessible(true);
      assertSize.invoke(cache);
      // More observations than cache slots guarantee a victim, independent of admission hashing.
      var old = captured.stream().filter(item -> !item.view().cacheEntry().isAlive())
          .findFirst().orElseThrow();
      long index = old.view().cacheEntry().getPageIndex();
      assertNull(cache.getCacheEntryOptimistic(file[0], index));
      assertTrue(old.view().validateStamp());
      var counters = manager.getCommitProtocolCounters();
      var before = Arrays.stream(Outcome.values()).mapToLong(counters::count).toArray();
      var failures = Arrays.stream(Cause.values()).mapToLong(OptimisticReadFailureCounters::count)
          .toArray();
      var ready = new CountDownLatch(1);
      var resume = new CountDownLatch(1);
      var executor = Executors.newSingleThreadExecutor();
      try (var executorCleanup = (AutoCloseable) () -> {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
      }) {
        var writer = executor.submit(() -> {
          manager.executeInsideAtomicOperation(op -> {
            var entry = op.loadPageForWrite(file[0], index, 1, true);
            new Bucket(entry).setRightSibling(42);
            op.releasePageFromWrite(entry);
            AtomicOperationTestBridge.installPageSetPauseHook(op, (pages, published) -> {
              if (!published) {
                assertEquals(1, pages.length);
                assertTrue(contains(pages, file[0], index));
                assertNotSame(old.view().pageFrame(),
                    pages[0].getCachePointer().getPageFrame());
                assertEquals(0, pages[0].getCachePointer().getPageFrame().tryOptimisticRead());
                ready.countDown();
                await(resume);
              }
            });
          });
          return null;
        });
        try (var writerCleanup = (AutoCloseable) () -> {
          resume.countDown();
          writer.get(30, TimeUnit.SECONDS);
        }) {
          await(ready);
          assertTrue(old.view().validateStamp());
          assertThrows(OptimisticReadFailedException.class, old.scope()::validateOrThrow);
        }
      }
      for (var outcome : Outcome.values()) {
        assertEquals(outcome.name(), outcome == Outcome.PAGE_SET ? 1 : 0,
            counters.count(outcome) - before[outcome.ordinal()]);
      }
      for (var cause : Cause.values()) {
        assertEquals(cause.name(), cause == Cause.FRAME_IDENTITY ? 1 : 0,
            OptimisticReadFailureCounters.count(cause) - failures[cause.ordinal()]);
      }
    }
  }

  // Pinning materializes empty pages before any frame lock. The old root cannot reach them.
  // Under the all-locked seam, the new pages and their parent are members of the same set.
  @Test
  public void splitPagesGrowBeforeLocksAndStayBehindParent() throws Exception {
    long floor = storage.getWriteCache().getFilledUpTo(left.getFileId());
    var beforeLocks = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(call -> {
      assertEquals(floor, storage.getWriteCache().getFilledUpTo(left.getFileId()));
      assertNull(cache.getCacheEntryOptimistic(left.getFileId(), floor));
      var reservation = spy((ReadCache.WriteReservation) call.callRealMethod());
      doAnswer(lock -> {
        if (beforeLocks.compareAndSet(false, true)) {
          assertTrue(storage.getWriteCache().getFilledUpTo(left.getFileId()) > floor);
          var empty = cache.getCacheEntryOptimistic(left.getFileId(), floor);
          assertNotNull(empty);
          assertNotEquals(0, empty.getCachePointer().getPageFrame().tryOptimisticRead());
          assertEquals(0, new Bucket(empty).size());
          var root = cache.getCacheEntryOptimistic(left.getFileId(), 1);
          assertTrue(new Bucket(root).isLeaf());
          assertEquals(-1, new Bucket(root).getRightSibling());
        }
        return lock.callRealMethod();
      }).when(reservation).lock(anyInt());
      return reservation;
    }).when(cache).tryReserveWritePins(anyInt());
    paused(false, false, true, 1, (reader, pages) -> {
      assertTrue(beforeLocks.get());
      assertTrue(contains(pages, left.getFileId(), floor));
      assertTrue(contains(pages, left.getFileId(), 0));
      for (var page : pages) {
        assertEquals(0, page.getCachePointer().getPageFrame().tryOptimisticRead());
      }
      reader.getOptimisticReadScope().reset();
      assertThrows(OptimisticReadFailedException.class,
          () -> probe.page(reader, left.getFileId(), 1));
    });
    var root = cache.getCacheEntryOptimistic(left.getFileId(), 1);
    assertFalse(new Bucket(root).isLeaf());
    assertTrue(new Bucket(root).getLeft(0) >= floor);
    assertTrue(new Bucket(root).getRight(new Bucket(root).size() - 1) >= floor);
    var read = manager.startAtomicOperation();
    manager.startToApplyOperations(read);
    try {
      assertEquals(2, left.get(new EdgeKey(7, 3, 999, 0), read).counter());
    } finally {
      manager.endAtomicOperation(read, null);
    }
  }
}
