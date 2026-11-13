package com.jetbrains.youtrackdb.benchmarks.pagelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.infra.ThreadParams;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.WorkloadParams;
import org.openjdk.jmh.runner.options.TimeValue;

@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class WriterStateTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  protected String method() {
    return "smallCache4";
  }

  protected void checkMeasuredPath() throws Exception {
  }

  private WriterBenchmarkState state(Path root) {
    var state = new WriterBenchmarkState();
    state.seedRoot = root.resolve("seeds").toString();
    state.runRoot = root.resolve("runs").toString();
    state.bufferMiB = 80;
    state.writeCachePercent = 20;
    state.pageKiB = 8;
    state.commitLimit = 17;
    state.pinPercent = 1;
    state.recordCount = RecordWorkload.FULL_RECORD_COUNT;
    return state;
  }

  private static IterationParams iteration(IterationType type) {
    return new IterationParams(type, 1, TimeValue.milliseconds(100), 1);
  }

  private BenchmarkParams params(String method, int threads, int forks) {
    return new BenchmarkParams(WriterBenchmark.class.getName() + "." + method, "", true,
        threads, new int[] {threads}, List.of(), forks, 0,
        iteration(IterationType.WARMUP), iteration(IterationType.MEASUREMENT),
        Mode.Throughput, new WorkloadParams(), TimeUnit.SECONDS, 1,
        "", List.of(), "", "", "", "", TimeValue.minutes(5));
  }

  // Every invalid start must fail before either seed or run directories can be created.
  @Test(timeout = 300000)
  public void aStartChecksPrecedeAllDataAndMissingFullSeedsAreRefused() throws Exception {
    Path root = folder.getRoot().toPath();
    for (int[] run : new int[][] {{4, 0}, {4, 2}, {2, 1}}) {
      var state = state(root);
      assertThrows(IllegalArgumentException.class,
          () -> state.setup(params(method(), run[0], run[1])));
      assertThrows(IllegalStateException.class, state::close);
    }
    var capacity = state(root);
    capacity.pinPercent = 0.1;
    assertThrows(IllegalArgumentException.class, () -> capacity.setup(params(method(), 4, 1)));
    var cache = state(root);
    cache.writeCachePercent = 21;
    assertThrows(IllegalArgumentException.class,
        () -> cache.setup(params("smallCache4", 4, 1)));
    var unknown = state(root);
    assertThrows(IllegalArgumentException.class,
        () -> unknown.setup(params("unknown", 4, 1)));
    var missing = state(root);
    assertTrue(assertThrows(IllegalStateException.class,
        () -> missing.setup(params(method(), 4, 1))).getMessage().contains("Prepare the full"));
    assertFalse(Files.exists(Path.of(missing.seedRoot)));
    assertFalse(Files.exists(Path.of(missing.runRoot)));
  }

  // Full record data or exact near-limit pages are prepared in a separate fresh JVM.
  // Four owners are bound by global thread index. Warmup cannot contribute to trial share.
  // Include slow Windows seed creation, copying and cleanup in the fixed five-minute limit.
  @Test(timeout = 300000)
  public void bLifecycleCountsOnlyMeasuredCommitsAndKeepsFailuresSticky() throws Exception {
    var state = state(folder.getRoot().toPath());
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(arg -> arg.startsWith("--add-")).forEach(command::add);
    command.add("-Dpagelock.expectUnavailable="
        + Boolean.getBoolean("pagelock.expectUnavailable"));
    command.add("-cp");
    command
        .add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    boolean near = method().startsWith("nearLimit");
    command.add((near ? NearLimitWorkload.class : RecordWorkload.class).getName());
    command.add(state.seedRoot);
    command.add("4");
    if (near) {
      command.addAll(List.of("80", "20", "8", "17", "1"));
    } else {
      command.add("17");
    }
    var prepare = new ProcessBuilder(command).inheritIO().start();
    try {
      assertTrue("seed preparation timed out", prepare.waitFor(240, TimeUnit.SECONDS));
      assertEquals(0, prepare.exitValue());
    } finally {
      prepare.destroyForcibly();
    }
    state.setup(params(method(), 4, 1));
    try {
      var writers = new WriterBenchmark.Writer[4];
      for (int i = 0; i < 4; i++) {
        writers[i] = new WriterBenchmark.Writer();
        writers[i].setup(state, new ThreadParams(i, 4, i, 4, 0, 1, 0, 1, 0, 1));
        assertSame(state.commits[i], writers[i].commit);
      }
      state.startIteration();
      writers[0].commit.run();
      state.finishIteration(iteration(IterationType.WARMUP));
      assertEquals(Boolean.getBoolean("pagelock.expectUnavailable") ? "not available" : "0.0",
          state.counters.share(false));
      state.startIteration();
      for (var writer : writers) {
        new WriterBenchmark().smallCache4(state, writer);
      }
      state.finishIteration(iteration(IterationType.MEASUREMENT));
      assertEquals(Boolean.getBoolean("pagelock.expectUnavailable") ? "not available" : "1.0",
          state.validity.finish(state.counters));
    } finally {
      state.close();
    }
    try (var runs = Files.list(Path.of(state.runRoot))) {
      assertEquals(0, runs.count());
    }
    checkMeasuredPath();
    // Inject only after the healthy trial is closed. Later success cannot clear either failure.
    var attempts = new AtomicInteger();
    var writer = new WriterBenchmark.Writer();
    var error = new AssertionError("commit failed");
    writer.commit = () -> {
      attempts.incrementAndGet();
      throw error;
    };
    assertSame(error, assertThrows(AssertionError.class,
        () -> new WriterBenchmark().smallCache4(state, writer)));
    writer.commit = attempts::incrementAndGet;
    new WriterBenchmark().smallCache4(state, writer);
    assertEquals(2, attempts.get());
    assertSame(error, assertThrows(IllegalStateException.class, state::close).getCause());
  }

  // Each boundary starts with an independent healthy latch. A later successful commit must
  // not erase the exact failure, even when trial teardown has no counters to consult.
  @Test
  public void lifecycleFailuresStayStickyAfterLaterSuccess() throws Exception {
    for (int boundary = 0; boundary < 3; boundary++) {
      var lifecycle = state(folder.getRoot().toPath());
      lifecycle.commits = new WriterRunValidity.Commit[] {() -> {
      }};
      Throwable failure;
      if (boundary == 0) {
        failure = assertThrows(NullPointerException.class, lifecycle::startIteration);
      } else if (boundary == 1) {
        failure = assertThrows(NullPointerException.class,
            () -> lifecycle.finishIteration(iteration(IterationType.MEASUREMENT)));
      } else {
        failure = assertThrows(ArrayIndexOutOfBoundsException.class,
            () -> new WriterBenchmark.Writer().setup(lifecycle,
                new ThreadParams(9, 10, 0, 1, 0, 1, 0, 1, 0, 1)));
      }
      var writer = new WriterBenchmark.Writer();
      writer.setup(lifecycle, new ThreadParams(0, 1, 0, 1, 0, 1, 0, 1, 0, 1));
      new WriterBenchmark().smallCache1(lifecycle, writer);
      assertSame(failure,
          assertThrows(IllegalStateException.class, lifecycle::close).getCause());
    }
  }

  // Param defaults must track the engine limit and fit all six cells without overrides.
  @Test
  public void defaultsMatchEngineLimitAndFitEveryCell() throws Exception {
    int limit = Integer.parseInt(defaultParam("commitLimit"));
    if (!Boolean.getBoolean("pagelock.expectUnavailable")) {
      var engine = com.jetbrains.youtrackdb.api.config.GlobalConfiguration
          .findByKey("youtrackdb.storage.diskCache.commitPageLimit");
      assertEquals(engine.getDefValue(), limit);
    }
    for (int writers : new int[] {1, 4}) {
      for (String workload : List.of("singleRecords", "nearLimit", "smallCache")) {
        var settings = new WriterSettings(
            workload.equals("smallCache") ? 80 : Integer.parseInt(defaultParam("bufferMiB")),
            Integer.parseInt(defaultParam("writeCachePercent")),
            Integer.parseInt(defaultParam("pageKiB")), limit,
            Double.parseDouble(defaultParam("pinPercent")));
        settings.checkCapacity(writers,
            workload.equals("nearLimit") ? limit - 1 : WriterSettings.RECORD_PAGE_BOUND);
      }
    }
    assertEquals(RecordWorkload.FULL_RECORD_COUNT, Integer.parseInt(defaultParam("recordCount")));
  }

  private static String defaultParam(String field) throws Exception {
    return WriterBenchmarkState.class.getField(field)
        .getAnnotation(org.openjdk.jmh.annotations.Param.class).value()[0];
  }

  // Generated entries must retain exactly six cells with their own thread count and one fork.
  @Test
  public void generatedEntriesHaveOneOrFourWriters() throws Exception {
    var output = org.openjdk.jmh.runner.format.OutputFormatFactory.createFormatInstance(
        System.out, org.openjdk.jmh.runner.options.VerboseMode.SILENT);
    var entries = org.openjdk.jmh.runner.BenchmarkList.defaultList().find(output,
        List.of(WriterBenchmark.class.getName() + ".*"), List.of());
    assertEquals(6, entries.size());
    for (var entry : entries) {
      assertEquals(Integer.valueOf(entry.getUsername().endsWith("4") ? 4 : 1),
          entry.getThreads().get());
      assertEquals(Integer.valueOf(1), entry.getForks().get());
      var state = state(folder.getRoot().toPath());
      var writer = new WriterBenchmark.Writer();
      var calls = new AtomicInteger();
      writer.commit = calls::incrementAndGet;
      var method = WriterBenchmark.class.getMethod(
          entry.getUsername().substring(entry.getUsername().lastIndexOf('.') + 1),
          WriterBenchmarkState.class, WriterBenchmark.Writer.class);
      method.invoke(new WriterBenchmark(), state, writer);
      assertEquals(1, calls.get());
      var failure = new IllegalStateException("commit exception");
      writer.commit = () -> {
        throw failure;
      };
      assertSame(failure, assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> method.invoke(new WriterBenchmark(), state, writer)).getCause());
      assertSame(failure, assertThrows(IllegalStateException.class, state::close).getCause());
    }
  }
}
