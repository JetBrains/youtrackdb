package com.jetbrains.youtrackdb.benchmarks.pagelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openjdk.jmh.infra.ThreadParams;

public class BenchmarkWorkloadTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private PageLockBenchmarkState state(String pattern, int reads) {
    var state = new PageLockBenchmarkState();
    state.pattern = pattern;
    state.pagesPerRead = reads;
    state.pagesPerCommit = 34;
    state.sharedPages = -1;
    return state;
  }

  // Every configured case has exact page counts and overlap, with no duplicate write coordinates.
  @Test
  public void sixPatternsAndPartialSharingHaveExactShape() {
    for (String pattern : new String[] {"SHARED", "DISJOINT", "DIFFERENT"}) {
      for (int reads : new int[] {1, 32}) {
        var state = state(pattern, reads);
        int expected = pattern.equals("SHARED") ? reads : 0;
        assertEquals(expected, state.configurePattern());
        assertEquals(reads, state.readPages.length);
        assertEquals(34, Arrays.stream(state.writePages).distinct().count());
        assertEquals(expected, Arrays.stream(state.readPages)
            .filter(r -> Arrays.stream(state.writePages).anyMatch(w -> w == r)).count());
      }
    }
    var state = state("SHARED", 32);
    state.sharedPages = 2;
    assertEquals(2, state.configurePattern());
    state.sharedPages = 33;
    assertThrows(IllegalArgumentException.class, state::configurePattern);
    state.pattern = "unknown";
    assertThrows(IllegalArgumentException.class, state::configurePattern);
    state.pattern = "DISJOINT";
    state.pagesPerCommit = 0;
    assertThrows(IllegalArgumentException.class, state::configurePattern);
  }

  // Open every goal 8 case and verify the benchmark boundary reads the intended file and
  // every page. Warm reads must stay optimistic and must not register any write outcome.
  // Six disk lifecycles need extra time on Windows, but a hang must still fail.
  @Test(timeout = 300000)
  public void benchmarkReadsAllSixCasesWithoutRegisteringWrites() throws Exception {
    var benchmark = new ReaderWriterBenchmark();
    for (String pattern : new String[] {"SHARED", "DISJOINT", "DIFFERENT"}) {
      for (int count : new int[] {1, 32}) {
        var state = state(pattern, count);
        try {
          state.setup(folder.getRoot().toPath(), 1, 3);
          long readerFile = state.reader.read(new int[] {0}).pages()[0].fileId();
          long writerFile = state.writers[0].read(new int[] {0}).pages()[0].fileId();
          assertEquals(pattern, !pattern.equals("DIFFERENT"), readerFile == writerFile);
          benchmark.read(state);
          var probes = new CounterProbes(state.storage);
          var before = probes.snapshot();
          var read = benchmark.read(state);
          assertTrue("warm benchmark reads must use the optimistic path", read.optimistic());
          assertEquals(count, read.pages().length);
          for (int page = 0; page < count; page++) {
            assertEquals(readerFile, read.pages()[page].fileId());
            assertEquals(page, read.pages()[page].pageIndex());
          }
          CounterProbes.iteration(before, probes.snapshot()).forEach((name, value) -> {
            if (name.startsWith("commit.")) {
              assertEquals(name,
                  Boolean.getBoolean("pagelock.expectUnavailable") ? "not available" : "0",
                  value);
            }
          });
        } finally {
          state.close();
        }
      }
    }
  }

  // Each boundary reports only its own mutating share, without rejecting fallback-only runs.
  @Test
  public void iterationShareExcludesZeroChangeAndHandlesUnavailableOrIdle() {
    assertEquals("not available", IterationCounters.iterationShare(
        Map.of("commit.PAGE_SET", "not available")));
    assertEquals("not available", IterationCounters.iterationShare(
        Map.of("commit.PAGE_SET", "0", "commit.ZERO_CHANGE", "999")));
    assertEquals("0.0", IterationCounters.iterationShare(
        Map.of("commit.PAGE_SET", "0", "commit.FORCED", "7")));
    assertEquals("0.75", IterationCounters.iterationShare(
        Map.of("commit.PAGE_SET", "3", "commit.FORCED", "1", "commit.ZERO_CHANGE", "999")));
  }

  // Warmup and ZERO_CHANGE cannot dilute the measurement-only aggregate or hide a bad run.
  @Test
  public void protocolAccountingRejectsBadSharesAndPartialSides() {
    var absent = new HashMap<String, OptionalLong>();
    for (int i = 0; i < 17; i++) {
      absent.put("probe" + i, OptionalLong.empty());
    }
    IterationCounters.checkSide(absent, true);
    assertThrows(IllegalStateException.class, () -> IterationCounters.checkSide(absent, false));
    absent.put("probe0", OptionalLong.of(0));
    assertThrows(IllegalStateException.class, () -> IterationCounters.checkSide(absent, true));
    absent.clear();
    assertThrows(IllegalStateException.class, () -> IterationCounters.checkSide(absent, true));
    String json = IterationCounters.json(Map.of("phase", "MEASUREMENT"),
        Map.of("missing", "not available", "escaped", "a\n\t\"\\"));
    assertTrue(json.contains("\"missing\":\"not available\""));
    assertTrue(json.contains("a\\n\\t\\\"\\\\"));
    PageLockBenchmarkState.validateThreads(4, new int[] {1, 3}, 1);
    assertThrows(IllegalArgumentException.class,
        () -> PageLockBenchmarkState.validateThreads(8, new int[] {1, 3}, 1));
    assertThrows(IllegalArgumentException.class,
        () -> PageLockBenchmarkState.validateThreads(4, new int[] {1, 3}, 2));
    assertThrows(IllegalArgumentException.class,
        () -> PageLockBenchmarkState.validateThreads(4, new int[] {4}, 1));
    assertThrows(IllegalArgumentException.class,
        () -> PageLockBenchmarkState.validateThreads(4, new int[] {0, 4}, 1));
  }

  // Two real components must have different file identities. A commit error must escape unchanged.
  // On the branch, pause both commits after locking and observe both live cache reservations.
  // Include slow Windows database setup and teardown in the bounded test budget.
  @Test(timeout = 180000)
  public void independentWritersOverlapReservationsAndPropagateFailures() throws Exception {
    var state = state("DIFFERENT", 1);
    try {
      state.setup(folder.getRoot().toPath(), 2, 3);
      var left = state.writers[0];
      var right = state.writers[1];
      long leftId = left.read(new int[] {1}).pages()[0].fileId();
      assertNotEquals(leftId, right.read(new int[] {1}).pages()[0].fileId());
      assertNotEquals(leftId, state.reader.read(new int[] {1}).pages()[0].fileId());
      assertEquals(34, state.writePages.length);
      state.startIteration();
      var benchmark = new ReaderWriterBenchmark();
      assertEquals(1, benchmark.read(state).pages().length);
      // The writer subgroup index, not the global thread index, selects its own component.
      var threads = new ThreadParams(4, 5, 0, 1, 0, 2, 4, 5, 1, 2);
      assertEquals(right.read(new int[] {1}).pages()[0].fileId(),
          benchmark.commit(state, new ReaderWriterBenchmark.Writer(), threads)[0].fileId());
      // Inject invalid coordinates at the throughput boundary. A normal return counts in JMH.
      int[] validPages = state.writePages;
      try {
        state.writePages = new int[] {9999};
        assertThrows(IllegalArgumentException.class,
            () -> benchmark.commit(state, new ReaderWriterBenchmark.Writer(), threads));
      } finally {
        state.writePages = validPages;
      }
      var delta = state.counters.finish(false);
      assertEquals(Boolean.getBoolean("pagelock.expectUnavailable") ? "not available" : "1",
          delta.get("commit.PAGE_SET"));
      assertEquals(Boolean.getBoolean("pagelock.expectUnavailable") ? "not available" : "0.0",
          state.counters.share(false));
      if (Boolean.getBoolean("pagelock.expectUnavailable")) {
        System.out.println("PF-2: not available");
        assertCommitFailureEscapes(state);
        return;
      }
      assertThrows(IllegalStateException.class, () -> state.counters.share(true));
      state.counters.add(Map.of("commit.PAGE_SET", "99", "commit.FORCED", "1",
          "commit.ZERO_CHANGE", "999999"));
      assertEquals("0.99", state.counters.share(true));
      state.counters.add(Map.of("commit.FORCED", "1"));
      assertThrows(IllegalStateException.class, () -> state.counters.share(true));
      assertThrows(IllegalStateException.class,
          () -> state.counters.add(Map.of("commit.PAGE_SET", "-1")));
      state.startIteration();
      overlap(state);
      state.counters.finish(true);
      var measured = new IterationCounters(new CounterProbes(state.storage), false);
      measured.start();
      left.commit(state.writePages, 7);
      measured.finish(true);
      assertEquals("1.0", measured.share(true));
      assertCommitFailureEscapes(state);
    } finally {
      state.close();
    }
  }

  // Check generated JMH metadata, rather than assuming annotation order controls -tg.
  @Test
  public void generatedSubgroupOrderIsCommitThenRead() {
    var output = org.openjdk.jmh.runner.format.OutputFormatFactory.createFormatInstance(
        System.out, org.openjdk.jmh.runner.options.VerboseMode.SILENT);
    var entries = org.openjdk.jmh.runner.BenchmarkList.defaultList().find(output,
        java.util.List.of(ReaderWriterBenchmark.class.getName() + ".*"), java.util.List.of());
    assertEquals(1, entries.size());
    var entry = entries.first();
    assertEquals(java.util.List.of("commit", "read"),
        java.util.List.copyOf(entry.getThreadGroupLabels().get()));
    org.junit.Assert.assertArrayEquals(new int[] {1, 3}, entry.getThreadGroups());
    assertEquals(Integer.valueOf(1), entry.getForks().get());
  }

  // Shared reads observe exactly the coordinates the writer changes. Iteration hooks sample
  // outside operations and the same closed seed can serve another independent copy.
  // This includes two disk lifecycles, including copy cleanup on slow Windows file systems.
  @Test(timeout = 180000)
  public void sharedPagesAndIterationLifecycleUseActualCoordinates() throws Exception {
    var state = state("SHARED", 32);
    try {
      state.setup(folder.getRoot().toPath(), 1, 3);
      assertEquals(state.reader, state.writers[0]);
      var read = state.reader.read(state.readPages).pages();
      var written = state.writers[0].commit(state.writePages, 9);
      assertEquals(34, written.length);
      for (int i = 0; i < read.length; i++) {
        assertEquals(read[i].fileId(), written[i].fileId());
        assertEquals(read[i].pageIndex(), written[i].pageIndex());
      }
      state.startIteration();
      state.writers[0].commit(state.writePages, 10);
      state.finishIteration(new org.openjdk.jmh.infra.IterationParams(
          org.openjdk.jmh.runner.IterationType.WARMUP, 1,
          org.openjdk.jmh.runner.options.TimeValue.milliseconds(1), 1));
      assertEquals(Boolean.getBoolean("pagelock.expectUnavailable") ? "not available" : "0.0",
          state.counters.share(false));
      state.startIteration();
      state.writers[0].commit(state.writePages, 11);
      state.finishIteration(new org.openjdk.jmh.infra.IterationParams(
          org.openjdk.jmh.runner.IterationType.MEASUREMENT, 1,
          org.openjdk.jmh.runner.options.TimeValue.milliseconds(1), 1));
      assertEquals(Boolean.getBoolean("pagelock.expectUnavailable") ? "not available" : "1.0",
          state.counters.share(true));
    } finally {
      state.close();
    }
    // Reusing the seed must not regenerate it or expose values from the first copy.
    var fresh = state("SHARED", 32);
    try {
      fresh.setup(folder.getRoot().toPath(), 1, 3);
      assertEquals(0, fresh.reader.read(fresh.readPages).pages()[0].value());
    } finally {
      fresh.close();
    }
  }

  private static void assertCommitFailureEscapes(PageLockBenchmarkState state) {
    // An unexpected atomic-operation failure puts this storage in its error state.
    // Run this assertion last. The only subsequent commit must fail at the benchmark boundary.
    var failure = new IllegalStateException("observer failed");
    assertEquals(failure, assertThrows(
        com.jetbrains.youtrackdb.internal.core.exception.StorageException.class,
        () -> state.writers[0].commit(new int[] {1}, 2, op -> {
          throw failure;
        })).getCause());
    // The injected storage failure must also escape the measured entry point, not count as
    // successful throughput. This protects both storage and invalid-input failures.
    var benchmark = new ReaderWriterBenchmark();
    var threads = new ThreadParams(0, 4, 0, 1, 0, 2, 0, 1, 0, 3);
    assertThrows(com.jetbrains.youtrackdb.internal.core.exception.StorageException.class,
        () -> benchmark.commit(state, new ReaderWriterBenchmark.Writer(), threads));
  }

  private static void overlap(PageLockBenchmarkState state) throws Exception {
    var paused = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var jobs = Arrays.stream(state.writers)
          .map(component -> executor.submit(
              () -> component.commit(state.writePages, 3, op -> installPause(op, paused, release))))
          .toList();
      boolean bothPaused = paused.await(10, TimeUnit.SECONDS);
      if (!bothPaused) {
        release.countDown();
        for (var job : jobs) {
          job.get(10, TimeUnit.SECONDS);
        }
      }
      assertTrue("both writers must reach the page-set lock window", bothPaused);
      var cache = state.storage.getReadCache();
      var reserved = cache.getClass().getMethod("reservedWritePins");
      assertEquals(2 * state.pagesPerCommit, ((Number) reserved.invoke(cache)).intValue());
      release.countDown();
      for (var job : jobs) {
        assertEquals(state.pagesPerCommit, job.get(10, TimeUnit.SECONDS).length);
      }
      assertEquals(0, ((Number) reserved.invoke(cache)).intValue());
      var delta = state.counters.finish(false);
      assertEquals("2", delta.get("commit.PAGE_SET"));
      assertEquals("0", delta.get("commit.RESERVATION_DENIED"));
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
    }
  }

  private static void installPause(Object operation, CountDownLatch paused,
      CountDownLatch release) {
    try {
      Class<?> hook = Class.forName(operation.getClass().getName() + "$PageSetPauseHook");
      var setter = operation.getClass().getDeclaredMethod("setPageSetPauseHook", hook);
      setter.setAccessible(true);
      setter.invoke(operation, Proxy.newProxyInstance(hook.getClassLoader(), new Class<?>[] {hook},
          (proxy, method, args) -> {
            if (!(boolean) args[1]) {
              paused.countDown();
              if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for overlapping writers");
              }
            }
            return null;
          }));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
