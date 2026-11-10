package com.jetbrains.youtrackdb.benchmarks.pagelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WriterBenchmarkTest {

  @BeforeClass
  public static void applySettingsBeforeEngineStartup() {
    new WriterSettings(80, 20, 8, 17, 1).apply(4, WriterSettings.RECORD_PAGE_BOUND);
  }

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  // Capacity is a precondition, not an instruction to silently raise the configured budget.
  @Test
  public void explicitSettingsRejectInsufficientCapacityAndUnsupportedRuns() throws Exception {
    var settings = new WriterSettings(80, 20, 8, 17, 1);
    assertEquals(8192, settings.readCachePages());
    assertEquals(81, settings.pinPages());
    settings.checkCapacity(4, 16);
    settings.checkCapacity(1, 6);
    // Decimal arithmetic must preserve this exact whole-page boundary.
    assertEquals(2553, new WriterSettings(271, 20, 8, 17, 9.2).pinPages());
    var boundary = new WriterSettings(80, 20, 8, 100, 1);
    boundary.checkCapacity(1, 81);
    assertThrows(IllegalArgumentException.class, () -> boundary.checkCapacity(1, 82));
    settings.checkCapacity(1, 17);
    assertThrows(IllegalArgumentException.class, () -> settings.checkCapacity(1, 18));
    // The configuration enum already holds 80 MiB. A property change cannot resize it.
    assertThrows(IllegalStateException.class,
        () -> new WriterSettings(81, 20, 8, 17, 1).apply(1, 6));
    assertThrows(IllegalArgumentException.class, () -> settings.checkCapacity(4, 21));
    assertThrows(IllegalArgumentException.class, () -> settings.checkCapacity(4, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new WriterSettings(80, 20, 8, 100, 0.1).apply(4, 16));
    for (int forks : new int[] {0, 2}) {
      assertThrows(IllegalArgumentException.class, () -> WriterSettings.validateRun(1, forks));
    }
    for (int threads : new int[] {0, 2, 3, 5}) {
      assertThrows(IllegalArgumentException.class, () -> WriterSettings.validateRun(threads, 1));
    }
    for (double percent : new double[] {0, -1, 16, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class,
          () -> new WriterSettings(80, 20, 8, 17, percent));
    }
    assertThrows(IllegalArgumentException.class, () -> new WriterSettings(0, 20, 8, 17, 1));
    assertThrows(IllegalArgumentException.class, () -> new WriterSettings(80, -1, 8, 17, 1));
    assertThrows(IllegalArgumentException.class, () -> new WriterSettings(80, 100, 8, 17, 1));
    assertThrows(IllegalArgumentException.class, () -> new WriterSettings(80, 20, 0, 17, 1));
    assertThrows(IllegalArgumentException.class, () -> new WriterSettings(80, 20, 8, 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> RecordWorkload.prepare(folder.getRoot().toPath(), 4, 3));
    assertThrows(IllegalArgumentException.class,
        () -> RecordWorkload.prepare(folder.getRoot().toPath(), 4, 5));
    assertThrows(IllegalArgumentException.class, () -> RecordWorkload.main(new String[0]));
  }

  // Four sessions use four real collections and disjoint RID partitions. The branch pause hook
  // observes the actual merged storage commit set. A 1 KiB update must change at most six pages.
  // Three disk lifecycles include Windows file I/O, bounded so a hang still fails.
  @Test(timeout = 300000)
  public void recordPartitionsBoundPagesAndUsePageSetOnFreshCopies() throws Exception {
    var settings = new WriterSettings(80, 20, 8, 17, 1);
    // Settings were applied once, before any engine or configuration initialization.
    var root = folder.getRoot().toPath();
    var seed = RecordWorkload.prepare(root, 4, 128);
    long physicalBytes = RecordWorkload.physicalBytes(root);
    assertTrue(physicalBytes > 128 * 1024);
    System.out.println("record seed physicalBytes=" + physicalBytes);
    boolean unavailable = Boolean.getBoolean("pagelock.expectUnavailable");
    for (int run = 0; run < 2; run++) {
      var copy = seed.freshCopy(root);
      var workloads = new ArrayList<RecordWorkload>();
      try (var db = (YouTrackDBImpl) YourTracks.instance(copy.toString())) {
        try {
          for (int writer = 0; writer < 4; writer++) {
            workloads.add(new RecordWorkload(db, writer, 32));
          }
          var array = workloads.toArray(RecordWorkload[]::new);
          RecordWorkload.checkOwnership(array);
          for (int writer = 0; writer < 4; writer++) {
            for (int other = 0; other < writer; other++) {
              assertNotEquals(array[writer].collectionId, array[other].collectionId);
              assertTrue(java.util.Collections.disjoint(
                  array[writer].records, array[other].records));
            }
          }
          assertThrows(IllegalStateException.class,
              () -> RecordWorkload.checkOwnership(new RecordWorkload[] {array[0], array[0]}));
          var storage = array[3].session.getStorage();
          settings.verifyCache(storage.getReadCache(), unavailable);
          assertThrows(IllegalStateException.class,
              () -> settings.apply(4, WriterSettings.RECORD_PAGE_BOUND));
          for (var wrong : List.of(
              new WriterSettings(81, 20, 8, 17, 1),
              new WriterSettings(80, 21, 8, 17, 1),
              new WriterSettings(80, 20, 16, 17, 1))) {
            assertThrows(IllegalStateException.class,
                () -> wrong.verifyCache(storage.getReadCache(), unavailable));
          }
          var probes = new CounterProbes(storage);
          var counters = new IterationCounters(probes, unavailable);
          counters.start();
          for (var workload : array) {
            var pages = new AtomicInteger();
            var rid = workload.commit(unavailable ? null : op -> observePages(op, pages));
            assertEquals(workload.collectionId, rid.getCollectionId());
            if (!unavailable) {
              assertTrue("observed page set must be nonempty", pages.get() > 0);
              assertTrue("1 KiB record update exceeds bound: " + pages,
                  pages.get() <= WriterSettings.RECORD_PAGE_BOUND);
            }
          }
          var delta = counters.finish(true);
          assertEquals(unavailable ? "not available" : "4", delta.get("commit.PAGE_SET"));
          assertEquals(unavailable ? "not available" : "1.0", counters.share(true));
          assertThrows(IllegalStateException.class,
              () -> settings.verifyCache(storage.getReadCache(), !unavailable));
          if (!unavailable) {
            assertThrows(IllegalStateException.class,
                () -> new WriterSettings(80, 20, 8, 18, 1)
                    .verifyCache(storage.getReadCache(), false));
            assertThrows(IllegalStateException.class,
                () -> new WriterSettings(80, 20, 8, 17, 2)
                    .verifyCache(storage.getReadCache(), false));
          }
        } finally {
          closeAll(workloads);
        }
      } finally {
        SeedDatabase.deleteTree(copy);
      }
    }
  }

  // A hook throws inside the real commit, not in the observer that installs it.
  // Run last because a failed atomic operation puts shared storage in an error state.
  @Test(timeout = 180000)
  public void zRecordCommitFailureEscapesWithoutRetry() throws Exception {
    assumeFalse("Develop has no page-set commit pause hook",
        Boolean.getBoolean("pagelock.expectUnavailable"));
    var root = folder.getRoot().toPath();
    var copy = RecordWorkload.prepare(root, 1, 32).freshCopy(root);
    try (var db = (YouTrackDBImpl) YourTracks.instance(copy.toString());
        var workload = new RecordWorkload(db, 0, 32)) {
      assertThrows(IllegalStateException.class, () -> new RecordWorkload(db, 0, 31));
      var failure = new IllegalStateException("commit apply failed");
      var attempts = new AtomicInteger();
      var commits = new AtomicInteger();
      var escaped = assertThrows(RuntimeException.class, () -> workload.commit(op -> {
        attempts.incrementAndGet();
        installPause(op, args -> {
          commits.incrementAndGet();
          throw failure;
        });
      }));
      assertEquals(1, attempts.get());
      assertEquals(1, commits.get());
      while (escaped.getCause() instanceof RuntimeException cause) {
        escaped = cause;
      }
      assertEquals(failure, escaped);
    } finally {
      SeedDatabase.deleteTree(copy);
    }
  }

  // All resources close even if one fails. Preserve the first failure and suppress the rest.
  @Test
  public void closeAllPreservesFailuresAndClosesEveryResource() {
    var closed = new AtomicInteger();
    var first = new IllegalStateException("first");
    var second = new IllegalStateException("second");
    assertEquals(first, assertThrows(IllegalStateException.class,
        () -> closeAll(List.of(
            () -> {
              closed.incrementAndGet();
              throw first;
            },
            () -> {
              closed.incrementAndGet();
              throw second;
            },
            () -> {
              closed.incrementAndGet();
            }))));
    assertEquals(3, closed.get());
    assertEquals(List.of(second), List.of(first.getSuppressed()));
  }

  private static void closeAll(List<? extends AutoCloseable> workloads) throws Exception {
    Exception first = null;
    for (var workload : workloads) {
      try {
        workload.close();
      } catch (Exception failure) {
        if (first == null) {
          first = failure;
        } else {
          first.addSuppressed(failure);
        }
      }
    }
    if (first != null) {
      throw first;
    }
  }

  private static void observePages(Object operation, AtomicInteger count) {
    installPause(operation, args -> {
      if (!(boolean) args[1]) {
        count.set(java.lang.reflect.Array.getLength(args[0]));
      }
    });
  }

  private static void installPause(Object operation, java.util.function.Consumer<Object[]> action) {
    try {
      Class<?> hook = Class.forName(operation.getClass().getName() + "$PageSetPauseHook");
      var setter = operation.getClass().getDeclaredMethod("setPageSetPauseHook", hook);
      setter.setAccessible(true);
      setter.invoke(operation, Proxy.newProxyInstance(hook.getClassLoader(), new Class<?>[] {hook},
          (proxy, method, args) -> {
            action.accept(args);
            return null;
          }));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
