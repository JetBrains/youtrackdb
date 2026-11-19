package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.JUnitTestListener;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters.Cause;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationTestBridge;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CommitProtocolCounters.Outcome;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

@Category(SequentialTest.class)
public class PageSetReaderConsistencyStressTest {
  private static final int LIMIT = 8;
  private static final int[] POSITIONS = {0, 600, 1199};
  private final TemporaryFolder directory = new TemporaryFolder();
  private long deadline;

  // This outer rule covers engine restart, database creation and close, not only worker waits.
  // The listener remains the last-resort JVM watchdog if storage code ignores interruption.
  private final TestRule lifecycleDeadline = (base, description) -> new Statement() {
    @Override
    public void evaluate() throws Throwable {
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      var owner = Thread.currentThread();
      var watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "reader-consistency-deadline");
        thread.setDaemon(true);
        return thread;
      });
      var alarm = watchdog.schedule(() -> {
        JUnitTestListener.appendDiagnostics("reader consistency lifecycle deadline");
        owner.interrupt();
      }, 120, TimeUnit.SECONDS);
      try {
        base.evaluate();
        assertTrue("lifecycle deadline exceeded", System.nanoTime() < deadline);
      } finally {
        alarm.cancel(false);
        watchdog.shutdownNow();
      }
    }
  };

  @Rule
  public final RuleChain rules = RuleChain.outerRule(lifecycleDeadline).around(directory);

  private long remaining() {
    long nanos = Math.min(TimeUnit.SECONDS.toNanos(20), deadline - System.nanoTime());
    if (nanos <= 0) {
      throw timeout("lifecycle");
    }
    return nanos;
  }

  private AssertionError timeout(String label) {
    JUnitTestListener.appendDiagnostics("reader consistency timeout: " + label);
    return new AssertionError("Timed out: " + label);
  }

  private void await(CountDownLatch latch, String label) throws InterruptedException {
    if (!latch.await(remaining(), TimeUnit.NANOSECONDS)) {
      throw timeout(label);
    }
  }

  private void join(Future<?> future) throws Exception {
    try {
      future.get(remaining(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      throw timeout("worker completion");
    }
  }

  private void stop(ExecutorService executor) throws InterruptedException {
    executor.shutdownNow();
    if (!executor.awaitTermination(remaining(), TimeUnit.NANOSECONDS)) {
      throw timeout("executor termination");
    }
  }

  /**
   * Two writers cycle through page-set, lifecycle, over-limit, forced and reservation-denied
   * commits while four readers validate six values across two real trees per lane.
   * Only a result accepted by the
   * outer production scope is checked. A locked fallback must satisfy the same invariant.
   */
  @Test
  public void mixedProtocolsNeverAcceptPartialGenerations() throws Exception {
    var settings = new GlobalConfiguration[] {
        GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT,
        GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT};
    var saved = Arrays.stream(settings).map(GlobalConfiguration::getValue).toArray();
    YouTrackDBImpl db = null;
    try {
      YouTrackDBEnginesManager.instance().shutdown();
      settings[0].setValue(LIMIT);
      settings[1].setValue(1.0);
      YouTrackDBEnginesManager.instance().startup();
      db = (YouTrackDBImpl) YourTracks.instance(directory.newFolder().getPath());
      db.create("consistency", DatabaseType.DISK, "admin", "admin", "admin");
      AbstractStorage storage;
      try (var session = db.open("consistency", "admin", "admin")) {
        storage = session.getStorage();
      }
      assertTrue(storage.getReadCache() instanceof LockFreeReadCache);
      assertTrue(storage.getWriteCache() instanceof WOWCache);
      assertEquals(LIMIT, storage.getReadCache().commitPageLimit());
      assertTrue(storage.getReadCache().writePinBudget() > LIMIT);
      var manager = storage.getAtomicOperationsManager();
      var lanes = new Lane[] {new Lane(storage, "lane0"), new Lane(storage, "lane1")};
      for (var lane : lanes) {
        lane.create();
      }
      var counters = manager.getCommitProtocolCounters();
      var before = Arrays.stream(Outcome.values()).mapToLong(counters::count).toArray();
      var failures = Arrays.stream(Cause.values()).mapToLong(OptimisticReadFailureCounters::count)
          .toArray();

      forceOverlap(lanes);
      staleStamp(lanes[0]);
      stress(lanes, storage);
      assertEquals("all commit reservations released", 0,
          ((LockFreeReadCache) storage.getReadCache()).reservedWritePins());
      for (var outcome : new Outcome[] {Outcome.PAGE_SET, Outcome.FILE_LIFECYCLE,
          Outcome.OVER_LIMIT, Outcome.FORCED, Outcome.RESERVATION_DENIED}) {
        assertTrue(outcome.name(), counters.count(outcome) > before[outcome.ordinal()]);
      }
      assertTrue("forced window must fail a frame stamp",
          OptimisticReadFailureCounters.count(Cause.STAMP) > failures[Cause.STAMP.ordinal()]);
      long failed = 0;
      for (var cause : Cause.values()) {
        long delta = OptimisticReadFailureCounters.count(cause) - failures[cause.ordinal()];
        assertTrue(cause.name(), delta >= 0);
        failed += delta;
      }
      assertTrue("optimistic failures", failed > 0);
      for (var lane : lanes) {
        assertTrue("optimistic successes", lane.optimistic.sum() > 0);
      }
      assertTrue("locked fallback observations", lanes[0].locked.sum() > 0);
      System.out.printf("Reader consistency: optimistic=%d locked=%d failures=%d%n",
          lanes[0].optimistic.sum() + lanes[1].optimistic.sum(),
          lanes[0].locked.sum() + lanes[1].locked.sum(), failed);
    } finally {
      try {
        if (db != null) {
          db.close();
        }
      } finally {
        YouTrackDBEnginesManager.instance().shutdown();
        for (int i = 0; i < settings.length; i++) {
          settings[i].setValue(saved[i]);
        }
        YouTrackDBEnginesManager.instance().startup();
      }
    }
  }

  // At first publish, one changed frame is readable but all remaining changed frames stay
  // locked. The inside reader must retry under the lane lock. The independent lane must finish.
  private void forceOverlap(Lane[] lanes) throws Exception {
    var published = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var writer = executor.submit(() -> {
        lanes[0].manager.executeInsideAtomicOperation(op -> {
          lanes[0].put(op, 2);
          AtomicOperationTestBridge.installPageSetPauseHook(op, (pages, firstPublished) -> {
            if (firstPublished) {
              assertTrue("several pages across two components", pages.length >= 2);
              assertTrue("small commit fits", pages.length <= LIMIT);
              published.countDown();
              try {
                await(resume, "resume paused commit");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              }
            }
          });
        });
        return null;
      });
      try {
        await(published, "first publish");
        long outside = lanes[1].optimistic.sum();
        lanes[1].read(null);
        assertEquals(outside + 1, lanes[1].optimistic.sum());
        var reader = executor.submit(() -> {
          lanes[0].read(attempted);
          return null;
        });
        // The lambda unwinds before signalling, but its production final check or failed load
        // still needs to reject the observation. The fallback cannot finish before resume.
        await(attempted, "inside optimistic attempt");
        resume.countDown();
        join(reader);
        assertEquals(1, lanes[0].locked.sum());
      } finally {
        resume.countDown();
        join(writer);
      }
    } finally {
      resume.countDown();
      stop(executor);
    }
  }

  // The first leaf is read before a whole page-set commit and the remaining leaves after it.
  // No writer holds a frame lock at the second load, so only final validation rejects the mix.
  private void staleStamp(Lane lane) throws Exception {
    var firstRead = new CountDownLatch(1);
    var committed = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    long stamps = OptimisticReadFailureCounters.count(Cause.STAMP);
    long locked = lane.locked.sum();
    long pageSets = lane.manager.getCommitProtocolCounters().count(Outcome.PAGE_SET);
    try {
      var reader = executor.submit(() -> {
        lane.read(null, firstRead, committed);
        return null;
      });
      try {
        await(firstRead, "stale stamp first leaf");
        lane.commit(3, Outcome.PAGE_SET);
        assertEquals("stale stamp commit uses page-set", pageSets + 1,
            lane.manager.getCommitProtocolCounters().count(Outcome.PAGE_SET));
      } finally {
        committed.countDown();
      }
      join(reader);
      assertEquals("stale stamp requires locked fallback", locked + 1, lane.locked.sum());
      assertEquals("final validation rejects stale stamp", stamps + 1,
          OptimisticReadFailureCounters.count(Cause.STAMP));
    } finally {
      committed.countDown();
      stop(executor);
    }
  }

  private void stress(Lane[] lanes, AbstractStorage storage) throws Exception {
    for (var lane : lanes) {
      lane.pageSetOverlaps.reset();
      lane.pageSetOverlapFailures.reset();
      lane.denialOverlaps.reset();
      lane.denialOverlapFailures.reset();
    }
    var counters = lanes[0].manager.getCommitProtocolCounters();
    var before = Arrays.stream(Outcome.values()).mapToLong(counters::count).toArray();
    // Ordinary writers may run together. A whole-budget reservation excludes every other
    // writer, including either lane's guaranteed generation-4 page-set commit.
    var admission = new ReentrantReadWriteLock();
    var executor = Executors.newFixedThreadPool(6);
    var ready = new CountDownLatch(6);
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    var futures = new ArrayList<Future<?>>();
    try {
      for (var lane : lanes) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          await(start, "stress start");
          try {
            var kinds = new Outcome[] {Outcome.PAGE_SET, Outcome.FILE_LIFECYCLE,
                Outcome.OVER_LIMIT, Outcome.FORCED, Outcome.RESERVATION_DENIED};
            for (int generation = 4; generation < 304; generation++) {
              if (Thread.currentThread().isInterrupted()) {
                return null;
              }
              var kind = kinds[(generation - 4) % kinds.length];
              var gate = kind == Outcome.RESERVATION_DENIED
                  ? admission.writeLock() : admission.readLock();
              gate.lockInterruptibly();
              try {
                if (kind == Outcome.RESERVATION_DENIED) {
                  // Capacity reservation alone neither loads nor pins a frame. Readers remain
                  // free to run while this writer applies a distinct generation under the epoch.
                  try (var reservation = storage.getReadCache()
                      .tryReserveWritePins(storage.getReadCache().writePinBudget())) {
                    assertNotNull("whole write-pin budget available", reservation);
                    lane.commit(generation, kind);
                  }
                } else {
                  lane.commit(generation, kind);
                }
              } finally {
                gate.unlock();
              }
            }
          } finally {
            done.countDown();
          }
          return null;
        }));
        for (int r = 0; r < 2; r++) {
          futures.add(executor.submit(() -> {
            ready.countDown();
            await(start, "stress start");
            // Start the first attempt only after the writer pauses. Otherwise both readers
            // could reach blocked fallback before the pause hook can observe their signal.
            await(lane.firstPageSetPaused, "first stress page-set apply");
            do {
              if (Thread.currentThread().isInterrupted()) {
                return null;
              }
              lane.read(null);
            } while (done.getCount() != 0);
            return null;
          }));
        }
      }
      await(ready, "workers ready");
      start.countDown();
      // Futures carry every worker exception back to the test thread.
      for (var future : futures) {
        join(future);
      }
      for (var outcome : new Outcome[] {Outcome.PAGE_SET, Outcome.FILE_LIFECYCLE,
          Outcome.OVER_LIMIT, Outcome.FORCED, Outcome.RESERVATION_DENIED}) {
        long delta = counters.count(outcome) - before[outcome.ordinal()];
        assertEquals("stress commits for " + outcome, 120, delta);
        System.out.printf("Stress protocol %s: commits=%d%n", outcome, delta);
      }
      for (var lane : lanes) {
        assertTrue("page-set overlapping attempts in " + lane.getName(),
            lane.pageSetOverlaps.sum() > 0);
        assertTrue("page-set overlapping failures in " + lane.getName(),
            lane.pageSetOverlapFailures.sum() > 0);
        System.out.printf("Page-set overlap %s: attempts=%d failures=%d%n", lane.getName(),
            lane.pageSetOverlaps.sum(), lane.pageSetOverlapFailures.sum());
        // Denial overlap is diagnostic only. No handshake guarantees a reader runs during
        // a reservation-denied commit.
        System.out.printf("Reservation-denied overlap %s: attempts=%d failures=%d%n",
            lane.getName(), lane.denialOverlaps.sum(), lane.denialOverlapFailures.sum());
      }
    } finally {
      start.countDown();
      stop(executor);
    }
  }

  private final class Lane extends StorageComponent {
    private final AtomicOperationsManager manager;
    private final SharedLinkBagBTree left;
    private final SharedLinkBagBTree right;
    private final LongAdder optimistic = new LongAdder();
    private final LongAdder locked = new LongAdder();
    private final LongAdder pageSetOverlaps = new LongAdder();
    private final LongAdder pageSetOverlapFailures = new LongAdder();
    private final LongAdder denialOverlaps = new LongAdder();
    private final LongAdder denialOverlapFailures = new LongAdder();
    private final CountDownLatch overlapAttempted = new CountDownLatch(1);
    private final CountDownLatch firstPageSetPaused = new CountDownLatch(1);
    // One writer per lane. Odd values mark a page-set commit in progress. A changed value
    // also detects an entire page-set commit between the attempt's two sequence samples.
    private volatile long pageSetSequence;
    // The same sampling protocol attributes stress attempts to reservation-denied commits.
    private volatile long denialSequence;
    private volatile boolean pageSetPaused;
    private long padding;
    private final long[] leafPages = new long[POSITIONS.length * 2];
    private final int[] leafSlots = new int[POSITIONS.length * 2];

    Lane(AbstractStorage storage, String name) {
      super(storage, name, "", name, true);
      manager = storage.getAtomicOperationsManager();
      left = new SharedLinkBagBTree(storage, name + "Left", ".sbc");
      right = new SharedLinkBagBTree(storage, name + "Right", ".sbc");
    }

    void create() throws IOException {
      manager.executeInsideAtomicOperation(op -> {
        left.create(op);
        right.create(op);
        // Pre-split each real B-tree so the selected keys span several leaves.
        for (int i = 0; i < 1200; i++) {
          var key = key(i);
          var value = new LinkBagValue(1, 0, 0, false);
          left.put(op, key, value);
          right.put(op, key, value);
        }
        padding = op.addFile(getName() + "-padding.dat");
        for (int i = 0; i < LIMIT + 1; i++) {
          var page = op.allocatePageForWrite(padding, i);
          new Bucket(page).init(true);
          op.releasePageFromWrite(page);
        }
      });
      // Fixed keys never insert or remove entries after setup. Discover their leaf coordinates
      // while quiescent, then let one production scope cover both components without nesting
      // wrappers from different epoch domains (which production deliberately rejects).
      manager.executeInsideAtomicOperation(op -> {
        var trees = new SharedLinkBagBTree[] {left, right};
        var serializers = storage.getComponentsFactory().binarySerializerFactory;
        for (int t = 0; t < trees.length; t++) {
          long file = trees[t].getFileId();
          for (int k = 0; k < POSITIONS.length; k++) {
            boolean found = false;
            for (long p = 1; p < storage.getWriteCache().getFilledUpTo(file); p++) {
              try (var entry = loadPageForRead(op, file, p)) {
                var bucket = new Bucket(entry);
                if (bucket.isLeaf()) {
                  int slot = bucket.find(key(POSITIONS[k]), serializers);
                  if (slot >= 0) {
                    leafPages[k * 2 + t] = p;
                    leafSlots[k * 2 + t] = slot;
                    found = true;
                    break;
                  }
                }
              }
            }
            assertTrue("seed key must have a leaf", found);
          }
        }
        assertTrue("seed spans several leaves", Arrays.stream(leafPages).distinct().count() > 1);
      });
    }

    void put(AtomicOperation op, int generation) throws IOException {
      // The lane lock gives the outer fallback a consistent view across both tree locks.
      // Register it with the manager so epoch fallback commits also protect the outer scope.
      manager.acquireExclusiveLockTillOperationComplete(op, this);
      for (int position : POSITIONS) {
        left.put(op, key(position), new LinkBagValue(generation, 0, 0, false));
        right.put(op, key(position), new LinkBagValue(generation, 0, 0, false));
      }
    }

    void commit(int generation, Outcome kind) throws IOException {
      var epoch = AtomicOperationTestBridge.applyPhaseEpoch(manager, getLockName());
      long beforeEnter = epoch.enterSeq();
      long beforeExit = epoch.exitSeq();
      if (kind == Outcome.PAGE_SET) {
        pageSetSequence++;
      } else if (kind == Outcome.RESERVATION_DENIED) {
        denialSequence++;
      }
      try {
        manager.executeInsideAtomicOperation(op -> {
          put(op, generation);
          if (generation == 4 && kind == Outcome.PAGE_SET) {
            // Every stress lane guarantees a real attempt during page-set apply, without
            // relying on scheduler timing. The reader signals before blocking in fallback.
            AtomicOperationTestBridge.installPageSetPauseHook(op, (pages, firstPublished) -> {
              if (firstPublished) {
                pageSetPaused = true;
                firstPageSetPaused.countDown();
                try {
                  await(overlapAttempted, "stress page-set overlapping attempt");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(e);
                } finally {
                  pageSetPaused = false;
                }
              }
            });
          }
          if (kind == Outcome.FILE_LIFECYCLE) {
            // Unique empty files exercise lifecycle fallback without changing the read topology.
            op.addFile(getName() + "-lifecycle-" + generation + ".dat");
          } else if (kind == Outcome.OVER_LIMIT) {
            for (int i = 0; i < LIMIT + 1; i++) {
              var page = op.loadPageForWrite(padding, i, 1, true);
              new Bucket(page).setRightSibling(generation);
              op.releasePageFromWrite(page);
            }
          } else if (kind == Outcome.FORCED) {
            AtomicOperationTestBridge.forceEpochApply(op);
          }
        });
        if (kind == Outcome.RESERVATION_DENIED) {
          // Frame stamps can reject mixed observations even if an epoch bracket is missing.
          // Assert the bracket directly as well. The fallback pause seam forces FORCED, so
          // it cannot deterministically attribute a mid-apply reader failure to denial.
          // One writer owns this lane, so no unrelated commit can satisfy this assertion.
          assertEquals("reservation-denied commit enters lane epoch in " + getName(),
              beforeEnter + 1, epoch.enterSeq());
          assertEquals("reservation-denied commit exits lane epoch in " + getName(),
              beforeExit + 1, epoch.exitSeq());
        }
      } finally {
        if (kind == Outcome.PAGE_SET) {
          pageSetSequence++;
        } else if (kind == Outcome.RESERVATION_DENIED) {
          denialSequence++;
        }
      }
    }

    void read(CountDownLatch attempted) throws IOException {
      read(attempted, null, null);
    }

    void read(CountDownLatch attempted, CountDownLatch firstRead, CountDownLatch committed)
        throws IOException {
      var op = manager.startAtomicOperation();
      try {
        manager.startToApplyOperations(op);
        var fallback = new boolean[1];
        var overlaps = new boolean[1];
        var denialOverlap = new boolean[1];
        int[] values = executeOptimisticStorageRead(op, () -> {
          long sequence = pageSetSequence;
          long denial = denialSequence;
          try {
            return optimisticValues(op, firstRead, committed);
          } finally {
            overlaps[0] = (sequence & 1) != 0 || sequence != pageSetSequence;
            denialOverlap[0] = (denial & 1) != 0 || denial != denialSequence;
            if (overlaps[0] && pageSetPaused) {
              overlapAttempted.countDown();
            }
            if (attempted != null) {
              attempted.countDown();
            }
          }
        }, () -> {
          fallback[0] = true;
          return values(op);
        });
        // Assert outside the production wrapper. It deliberately catches speculative assertions
        // and retries, which would hide an invariant failure placed inside its lambda.
        for (int value : values) {
          assertEquals("accepted partial generation in " + getName(), values[0], value);
        }
        if (overlaps[0]) {
          pageSetOverlaps.increment();
          if (fallback[0]) {
            pageSetOverlapFailures.increment();
          }
        }
        if (denialOverlap[0]) {
          denialOverlaps.increment();
          if (fallback[0]) {
            denialOverlapFailures.increment();
          }
        }
        if (fallback[0]) {
          locked.increment();
        } else {
          optimistic.increment();
        }
      } finally {
        manager.endAtomicOperation(op, null);
      }
    }

    private int[] optimisticValues(AtomicOperation op, CountDownLatch firstRead,
        CountDownLatch committed) throws IOException {
      var values = new int[leafPages.length];
      var serializers = storage.getComponentsFactory().binarySerializerFactory;
      for (int i = 0; i < values.length; i++) {
        long file = i % 2 == 0 ? left.getFileId() : right.getFileId();
        var bucket = new Bucket(loadPageOptimistic(op, file, leafPages[i]));
        values[i] = bucket.getValue(leafSlots[i], serializers).counter();
        if (i == 0 && firstRead != null) {
          firstRead.countDown();
          try {
            await(committed, "stale stamp commit completion");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
        }
      }
      return values;
    }

    private int[] values(AtomicOperation op) {
      var values = new int[POSITIONS.length * 2];
      for (int i = 0; i < POSITIONS.length; i++) {
        values[i * 2] = left.get(key(POSITIONS[i]), op).counter();
        values[i * 2 + 1] = right.get(key(POSITIONS[i]), op).counter();
      }
      return values;
    }

    private EdgeKey key(int position) {
      return new EdgeKey(7, 3, position, 0);
    }
  }
}
