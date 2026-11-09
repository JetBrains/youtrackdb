package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.JUnitTestListener;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.PageSetEntryAssertions;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.WTinyLFUPolicy;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationTestBridge;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CommitProtocolCounters.Outcome;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

@Category(SequentialTest.class)
public class PageSetPinBudgetStressTest {
  private final TemporaryFolder directory = new TemporaryFolder();
  private long deadline;
  private volatile String phase = "starting lifecycle";
  private final Object settingsGuard = new Object();
  private final AtomicReference<Thread> cleanupOwner = new AtomicReference<>();
  private volatile Thread pressureLoader;
  private final GlobalConfiguration[] settings = {
      GlobalConfiguration.DISK_CACHE_SIZE, GlobalConfiguration.DISK_WRITE_CACHE_PART,
      GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT,
      GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT};
  private Object[] saved;
  private boolean[] changed;

  // The owner bounds even non-interruptible storage waits. Only the daemon worker runs
  // storage lifecycle code. Cleanup ownership prevents late teardown after an owner timeout.
  private final TestRule lifecycleDeadline = (base, description) -> new Statement() {
    @Override
    public void evaluate() throws Throwable {
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      saved = Arrays.stream(settings).map(GlobalConfiguration::getValue).toArray();
      changed = new boolean[settings.length];
      for (int i = 0; i < settings.length; i++) {
        changed[i] = settings[i].isChanged();
      }
      var failure = new AtomicReference<Throwable>();
      var lifecycle = new FutureTask<Void>(() -> {
        try {
          base.evaluate();
        } catch (Throwable t) {
          failure.set(t);
        }
        return null;
      });
      var worker = new Thread(lifecycle, "pin-budget-lifecycle");
      worker.setDaemon(true);
      Throwable primary = null;
      try {
        worker.start();
        await(lifecycle, "lifecycle: " + phase);
        if (failure.get() != null) {
          throw failure.get();
        }
      } catch (Throwable t) {
        primary = t;
      } finally {
        // Claim before interrupting. If teardown already owns cleanup, only that worker
        // restores settings, so the owner cannot race a later worker restoration.
        boolean ownsCleanup = cleanupOwner.compareAndSet(null, Thread.currentThread());
        worker.interrupt();
        if (primary != null) {
          try {
            var loader = pressureLoader;
            if (loader != null) {
              loader.interrupt();
            }
            TimeUnit.SECONDS.timedJoin(worker, 1);
            loader = pressureLoader;
            if (loader != null) {
              loader.interrupt();
              TimeUnit.SECONDS.timedJoin(loader, 1);
            }
          } catch (InterruptedException e) {
            primary.addSuppressed(e);
            Thread.currentThread().interrupt();
          }
          primary = new AssertionError(primary.getMessage()
              + " (threads still alive: lifecycle=" + worker.isAlive()
              + ", pressure=" + (pressureLoader != null && pressureLoader.isAlive()) + ")",
              primary);
        }
        try {
          if (ownsCleanup) {
            restoreSettings();
          }
        } catch (Throwable t) {
          if (primary == null) {
            throw t;
          }
          primary.addSuppressed(t);
        }
      }
      if (primary != null) {
        throw primary;
      }
    }
  };

  @Rule
  public final RuleChain rules = RuleChain.outerRule(lifecycleDeadline).around(directory);

  private AssertionError timeout(String label) {
    JUnitTestListener.appendDiagnostics("pin budget timeout: " + label);
    return new AssertionError("Timed out: " + label);
  }

  private <T> T await(FutureTask<T> task, String label) throws Exception {
    try {
      return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      throw timeout(label + " (current phase: " + phase + ")");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      if (e.getCause() instanceof Exception exception) {
        throw exception;
      }
      throw new AssertionError(e.getCause());
    }
  }

  private void restoreSettings() {
    synchronized (settingsGuard) {
      for (int i = 0; i < settings.length; i++) {
        if (changed[i]) {
          settings[i].setValue(saved[i]);
        } else {
          settings[i].resetToDefault();
        }
        assertEquals(settings[i].name(), saved[i], settings[i].getValue());
        assertEquals(settings[i].name(), changed[i], settings[i].isChanged());
      }
    }
  }

  private void checkDeadline() {
    if (System.nanoTime() >= deadline) {
      throw timeout("lifecycle");
    }
  }

  // A full-budget commit pins cold frames into a full cache. Resident filler must be evicted
  // before publish, and every captured pin and reservation must be released before later work.
  @Test
  public void maximumBudgetEvictsFillerAndReleasesEveryPin() throws Exception {
    run(WTinyLFUPolicy.MAX_COMMIT_PIN_BUDGET_PERCENT);
  }

  // Existing-file mutations use only the no-budget fallback after setup. Cold-page reads and
  // later commits must still complete and preserve all values with page-set apply disabled.
  @Test
  public void zeroBudgetKeepsColdPageCommitsProgressing() throws Exception {
    run(0);
  }

  private void run(int percent) throws Exception {
    YouTrackDBImpl db = null;
    Throwable failure = null;
    try {
      phase = "engine restart";
      YouTrackDBEnginesManager.instance().shutdown();
      synchronized (settingsGuard) {
        // A timed-out worker must not overwrite the owner's restored settings.
        checkDeadline();
        // One MB is the smallest positive integral MB setting. Keep one quarter for writes.
        settings[0].setValue(1);
        settings[1].setValue(25);
        settings[2].setValue(1024);
        settings[3].setValue(percent);
      }
      YouTrackDBEnginesManager.instance().startup();
      phase = "storage setup";
      db = (YouTrackDBImpl) YourTracks.instance(directory.newFolder().getPath());
      db.create("budget", DatabaseType.DISK, "admin", "admin", "admin");
      AbstractStorage storage;
      try (var session = db.open("budget", "admin", "admin")) {
        storage = session.getStorage();
      }
      assertTrue(storage.getReadCache() instanceof LockFreeReadCache);
      assertTrue(storage.getWriteCache() instanceof WOWCache);
      var read = (LockFreeReadCache) storage.getReadCache();
      var write = (WOWCache) storage.getWriteCache();
      int capacity = 768 * 1024 / DurablePage.MAX_PAGE_SIZE_BYTES;
      // Check the actual engine capacity, not only the requested global setting.
      var max = LockFreeReadCache.class.getDeclaredField("maxCacheSize");
      max.setAccessible(true);
      assertEquals(capacity, max.getInt(read));
      int maximumBudget = capacity * WTinyLFUPolicy.MAX_COMMIT_PIN_BUDGET_PERCENT / 100;
      assertTrue("many target pages", maximumBudget >= 10);
      assertEquals(percent == 0 ? 0 : maximumBudget, read.writePinBudget());
      assertTrue(read.commitPageLimit() >= maximumBudget);
      var manager = storage.getAtomicOperationsManager();
      int targets = maximumBudget;
      int fillers = capacity * 6;
      long[] files = new long[2];
      manager.executeInsideAtomicOperation(op -> {
        files[0] = op.addFile("budget-target.dat");
        files[1] = op.addFile("budget-filler.dat");
      });
      // Small setup commits keep the small real write cache below sustained back-pressure.
      for (int f = 0; f < files.length; f++) {
        long file = files[f];
        int pages = f == 0 ? targets : fillers;
        for (int start = 0; start < pages; start += targets) {
          checkDeadline();
          int first = start;
          int end = Math.min(start + targets, pages);
          manager.executeInsideAtomicOperation(op -> {
            for (int p = first; p < end; p++) {
              var entry = op.allocatePageForWrite(file, p);
              new Bucket(entry).init(true);
              new Bucket(entry).setRightSibling(1);
              op.releasePageFromWrite(entry);
            }
          });
        }
        write.flush(file);
      }
      phase = "cold-page commit";
      var counters = manager.getCommitProtocolCounters();
      var before = Arrays.stream(Outcome.values()).mapToLong(counters::count).toArray();
      long stops = read.counters().evictionStops();
      long excess = read.counters().largestExcess();
      long skips = write.pageCacheCounters().flushSkips();
      var captured = new CacheEntry[1][];
      var resident = new ArrayList<Integer>();
      manager.executeInsideAtomicOperation(op -> {
        for (int p = 0; p < targets; p++) {
          var entry = op.loadPageForWrite(files[0], p, 1, true);
          new Bucket(entry).setRightSibling(2);
          op.releasePageFromWrite(entry);
        }
        // Private deltas are staged now. Evict their source frames before commit pinning.
        // Repeated filler scans promote filler instead of the recently staged target frames.
        for (int pass = 0; pass < 8; pass++) {
          for (int p = 0; p < fillers / 2; p++) {
            checkDeadline();
            var entry = read.loadForRead(files[1], p, write, true);
            read.releaseFromRead(entry);
          }
          drain(read);
          // Promote admitted filler out of probation so protected target frames age out too.
          for (int p = 0; p < fillers / 2; p++) {
            if (read.getCacheEntryOptimistic(files[1], p) != null) {
              var entry = read.loadForRead(files[1], p, write, true);
              read.releaseFromRead(entry);
            }
          }
          drain(read);
        }
        drain(read);
        for (int p = 0; p < targets; p++) {
          assertNull("commit target must be cold: " + p,
              read.getCacheEntryOptimistic(files[0], p));
        }
        for (int p = 0; p < fillers; p++) {
          if (read.getCacheEntryOptimistic(files[1], p) != null) {
            resident.add(p);
          }
        }
        assertTrue("filler fills the real read cache", resident.size() >= capacity - targets);
        if (percent > 0) {
          AtomicOperationTestBridge.installPageSetPauseHook(op, (pages, published) -> {
            if (!published) {
              captured[0] = pages.clone();
              assertEquals(targets, pages.length);
              assertEquals("entire budget held", read.writePinBudget(), read.reservedWritePins());
              for (var entry : pages) {
                assertEquals(files[0], entry.getFileId());
                assertTrue(entry.getPageIndex() < targets);
                assertPins(entry, 1);
              }
              phase = "eviction under held pins";
              var releaseCommit = new CountDownLatch(1);
              var pressure = new FutureTask<Void>(() -> {
                try {
                  // onAdd appends to Eden. Scan three capacities and require more than one
                  // capacity of additions, exceeding Eden (20%) plus the second level (80%).
                  // Without target accesses, every held target reaches an Eden candidate
                  // or probation victim position before this scan finishes.
                  int additions = 0;
                  for (int p = fillers / 2; p < fillers; p++) {
                    if (read.getCacheEntryOptimistic(files[1], p) == null) {
                      additions++;
                    }
                    checkDeadline();
                    var entry = read.loadForRead(files[1], p, write, true);
                    read.releaseFromRead(entry);
                    drain(read);
                  }
                  assertTrue("new additions exceed Eden plus the entire second level",
                      additions > capacity);
                  for (var entry : pages) {
                    assertSame("pinned frame stays cached", entry,
                        read.getCacheEntryOptimistic(entry.getFileId(), entry.getPageIndex()));
                    assertPins(entry, 1);
                  }
                  return null;
                } finally {
                  releaseCommit.countDown();
                }
              });
              var loader = new Thread(pressure, "pin-budget-pressure");
              loader.setDaemon(true);
              pressureLoader = loader;
              try {
                loader.start();
                if (!releaseCommit.await(Math.max(0, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS)) {
                  throw timeout("filler loads under held pins");
                }
                await(pressure, "filler loads under held pins");
              } catch (Exception e) {
                throw new AssertionError("Filler pressure failed", e);
              } finally {
                releaseCommit.countDown();
                loader.interrupt();
              }
              assertTrue("filler evicted while commit pins are held",
                  resident.stream()
                      .anyMatch(p -> read.getCacheEntryOptimistic(files[1], p) == null));
            }
          });
        }
      });
      assertOutcomes(storage, before, percent == 0 ? Outcome.NO_PIN_BUDGET : Outcome.PAGE_SET, 1);
      assertEquals("write reservation released", 0, read.reservedWritePins());
      if (percent > 0) {
        assertNotNull("page-set hook reached", captured[0]);
        for (var entry : captured[0]) {
          assertPins(entry, 0);
        }
      }
      System.out.printf("Pin budget percent=%d capacity=%d targets=%d evictionStops=%d->%d "
          + "largestExcessLifetime=%d->%d flushSkips=%d->%d%n",
          percent, capacity, targets, stops, read.counters().evictionStops(),
          excess, read.counters().largestExcess(), skips, write.pageCacheCounters().flushSkips());

      phase = "later reads and commits";
      // Check values without read-only atomic operations, which would add ZERO_CHANGE outcomes.
      for (int generation = 2; generation <= 4; generation++) {
        for (int p = 0; p < targets; p++) {
          checkDeadline();
          var entry = read.loadForRead(files[0], p, write, true);
          try {
            assertEquals(generation, new Bucket(entry).getRightSibling());
          } finally {
            read.releaseFromRead(entry);
          }
        }
        if (generation < 4) {
          int value = generation + 1;
          manager.executeInsideAtomicOperation(op -> {
            for (int p = 0; p < targets; p++) {
              var entry = op.loadPageForWrite(files[0], p, 1, true);
              new Bucket(entry).setRightSibling(value);
              op.releasePageFromWrite(entry);
            }
          });
          assertEquals(0, read.reservedWritePins());
        }
      }
      assertOutcomes(storage, before, percent == 0 ? Outcome.NO_PIN_BUDGET : Outcome.PAGE_SET, 3);
    } catch (Exception | Error e) {
      failure = e;
      throw e;
    } finally {
      if (cleanupOwner.compareAndSet(null, Thread.currentThread())) {
        phase = "storage teardown";
        try {
          try {
            if (db != null) {
              db.close();
            }
          } finally {
            try {
              YouTrackDBEnginesManager.instance().shutdown();
            } finally {
              restoreSettings();
              phase = "engine teardown restart";
              checkDeadline();
              YouTrackDBEnginesManager.instance().startup();
            }
          }
        } catch (Exception | Error e) {
          if (failure == null) {
            throw e;
          }
          failure.addSuppressed(e);
        }
      }
    }
  }

  private static void assertOutcomes(AbstractStorage storage, long[] before,
      Outcome expected, int count) {
    var counters = storage.getAtomicOperationsManager().getCommitProtocolCounters();
    for (var outcome : Outcome.values()) {
      assertEquals(outcome.name(), outcome == expected ? count : 0,
          counters.count(outcome) - before[outcome.ordinal()]);
    }
  }

  private static void assertPins(CacheEntry entry, int expected) {
    try {
      PageSetEntryAssertions.assertPins(entry, expected);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void drain(LockFreeReadCache read) {
    // Drain queued admission work before taking membership snapshots, as in the identity test.
    try {
      var drain = LockFreeReadCache.class.getDeclaredMethod("assertSize");
      drain.setAccessible(true);
      drain.invoke(read);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
