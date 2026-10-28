package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;

/**
 * Opt-in child workloads without inherited agents. The first-mark burst uses Mockito-inline
 * instrumentation. Timings are evidence, not regression thresholds.
 */
public final class TrackerPerformanceProbe {

  static final String ENABLED = "youtrackdb.test.trackerPerformance";
  static final String SAMPLES = ENABLED + ".samples";
  public static final String COMPLETE = "TRACKER_PERFORMANCE_COMPLETE";
  private static final ThreadMXBean ALLOCATIONS =
      (ThreadMXBean) ManagementFactory.getThreadMXBean();

  private TrackerPerformanceProbe() {
  }

  static void launch(Class<?> main, List<String> workloads, List<Integer> generations,
      String... metrics) throws Exception {
    launch(main, workloads, generations, 180, metrics);
  }

  public static void launchStartup(Class<?> main, List<String> workloads, String... metrics)
      throws Exception {
    launch(main, workloads, List.of(0), 900, metrics);
  }

  private static void launch(Class<?> main, List<String> workloads, List<Integer> generations,
      int timeoutSeconds, String... metrics) throws Exception {
    Assume.assumeTrue(Boolean.getBoolean(ENABLED));
    var command = new ArrayList<String>();
    command.add(System.getProperty("java.home") + "/bin/java");
    command.addAll(java.util.List.of("-Xms256m",
        timeoutSeconds == 900 ? "-Xmx1g" : "-Xmx256m", "-XX:-DoEscapeAnalysis", "-ea"));
    // Inherit WAL module access, not parent agents. The first-mark child attaches Mockito-inline.
    ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(arg -> arg.startsWith("--add-opens=") || arg.startsWith("--add-exports="))
        .forEach(command::add);
    command.add("-D" + ENABLED + "=true");
    command.add("-D" + SAMPLES + "=" + samples());
    command.add("-cp");
    command
        .add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    command.add(main.getName());
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try (var reader = Executors.newSingleThreadExecutor()) {
      // Drain while the child runs so logging cannot fill its pipe and block termination.
      var output = reader.submit(() -> {
        var text = new StringBuilder();
        try (var lines = process.inputReader(StandardCharsets.UTF_8)) {
          String line;
          while ((line = lines.readLine()) != null) {
            text.append(line).append('\n');
            if (timeoutSeconds == 900) {
              System.out.println(line);
            }
          }
        }
        return text.toString();
      });
      try {
        assertTrue("Probe timed out", process.waitFor(timeoutSeconds, TimeUnit.SECONDS));
        var text = output.get(10, TimeUnit.SECONDS);
        if (timeoutSeconds != 900) {
          System.out.print(text);
        }
        assertEquals(text, 0, process.exitValue());
        assertRows(text, workloads, generations, metrics);
      } finally {
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      }
    }
  }

  // Exact keys prevent partial workload names, missing generations, and duplicate metric rows
  // from satisfying the expected workload x generation x metric matrix.
  private static void assertRows(String text, List<String> workloads, List<Integer> generations,
      String[] metrics) {
    var remaining = new HashSet<MetricRow>();
    for (String workload : workloads) {
      for (int generation : generations) {
        for (String metric : metrics) {
          assertTrue(remaining.add(new MetricRow(workload, generation, metric)));
        }
      }
    }
    int completions = 0;
    for (String line : text.lines().toList()) {
      if (line.equals(COMPLETE)) {
        completions++;
      } else if (line.startsWith("TRACKER_PERFORMANCE ")) {
        var tokens = line.split("\\s+");
        var fields = new HashMap<String, String>();
        for (int token = 2; token < tokens.length; token++) {
          var entry = tokens[token].split("=", 2);
          assertEquals(line, 2, entry.length);
          assertNull("Duplicate field: " + line, fields.put(entry[0], entry[1]));
        }
        assertTrue(line, fields.keySet().containsAll(
            List.of("samples", "metric", "median", "p90", "min", "max")));
        assertEquals(line, samples(), Integer.parseInt(fields.get("samples")));
        int generation = fields.containsKey("generations")
            ? Integer.parseInt(fields.get("generations")) : 0;
        var row = new MetricRow(tokens[1], generation, fields.get("metric"));
        assertTrue("Unexpected or duplicate row: " + line, remaining.remove(row));
        double min = statistic(fields.get("min"));
        double median = statistic(fields.get("median"));
        double p90 = statistic(fields.get("p90"));
        double max = statistic(fields.get("max"));
        assertTrue(line, min <= median && median <= p90 && p90 <= max);
      }
    }
    assertEquals(text, 1, completions);
    assertTrue("Missing metric rows: " + remaining + "\\n" + text, remaining.isEmpty());
  }

  private static double statistic(String value) {
    double number = Double.parseDouble(value);
    assertTrue("Invalid statistic: " + value, Double.isFinite(number) && number >= 0);
    return number;
  }

  private record MetricRow(String workload, int generations, String metric) {
  }

  public static int samples() {
    int count = Integer.getInteger(SAMPLES, 15);
    assertTrue("Use 1 to 100 samples", count > 0 && count <= 100);
    return count;
  }

  public static long allocated() {
    assertTrue("Thread allocation accounting is required",
        ALLOCATIONS.isThreadAllocatedMemorySupported());
    ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
    return ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId());
  }

  /**
   * Reports a synchronized commit burst after the real backup unfreeze. Mockito interception is
   * intentionally disclosed and calibrated. No generation is primed and no warm control runs.
   */
  public static void main(String[] args) throws Exception {
    System.out.println("BURST_ENV os=" + System.getProperty("os.name") + " arch="
        + System.getProperty("os.arch") + " processors="
        + Runtime.getRuntime().availableProcessors()
        + " jdk=" + System.getProperty("java.runtime.version") + " flags="
        + ManagementFactory.getRuntimeMXBean().getInputArguments()
        + " maxHeap=" + Runtime.getRuntime().maxMemory()
        + " instrumentation=Mockito-inline writers=16 classes=16");
    System.out.println("BURST_OVERHEAD firstMarkNs includes allocation-observer callbacks,"
        + " ThreadLocal lookups, mask updates and observer timer calls."
        + " classificationNs times only mask updates, not full observer overhead."
        + " commitNs includes all intercepted mark calls and recorder work."
        + " interceptionExtraNs is a separate post-burst default-spy estimate per call,"
        + " not a correction for the timed answer or share.");
    var series = new Series("firstMarkBurst", "firstMarkNs", "firstMarkBytes", "commitNs",
        "share", "classificationNs", "interceptionExtraNs", "interceptionExtraBytes");
    for (int run = 0; run < samples(); run++) {
      burst(run, series);
    }
    series.report();
    System.out.println(COMPLETE);
  }

  private static Object get(Object target, Class<?> owner, String name) throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, Class<?> owner, String name, Object value)
      throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class BurstMarks {
    // Each commit has a bounded, preallocated buffer. Classification uses only primitive stores.
    final long[][] rows = new long[4096][5];
    int count;
    long markCalls;
    int kinds;
    long classificationNs;
    long commitNs;
  }

  private static void burst(int run, Series series) throws Exception {
    var root = java.nio.file.Files.createTempDirectory("tracker-burst-");
    var local = new ThreadLocal<BurstMarks>();
    try (var tracks =
        (com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
            .instance(root.resolve("db").toString())) {
      tracks.create("burst", com.jetbrains.youtrackdb.api.DatabaseType.DISK,
          "admin", "adminpwd", "admin");
      try (var session = tracks.open("burst", "admin", "adminpwd")) {
        for (int writer = 0; writer < 16; writer++) {
          session.getMetadata().getSchema().createClass("Burst" + writer);
          session.begin();
          session.newEntity("Burst" + writer).setProperty("value", "before");
          session.commit();
        }
      }
      var storage =
          ((com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded) tracks.internal)
              .getStorage("burst");
      var tracker = (ChangedPageTracker) get(storage,
          com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage.class,
          "changedPageTracker");
      var observed = org.mockito.Mockito.spy(tracker);
      set(observed, ChangedPageTracker.class, "allocations",
          (ChangedPageTracker.AllocationObserver) kind -> {
            var marks = local.get();
            if (marks != null) {
              long start = System.nanoTime();
              marks.kinds |= 1 << kind.ordinal();
              marks.classificationNs += System.nanoTime() - start;
            }
          });
      org.mockito.Mockito.doAnswer(call -> {
        var marks = local.get();
        if (marks == null) {
          return call.callRealMethod();
        }
        // Count all intercepted calls in this commit, including non-allocating marks.
        marks.markCalls++;
        marks.kinds = 0;
        long bytes = allocated();
        long start = System.nanoTime();
        var result = call.callRealMethod();
        long elapsed = System.nanoTime() - start;
        long allocation = allocated() - bytes;
        if ((marks.kinds & ((1 << ChangedPageTracker.Allocation.FILE.ordinal())
            | (1 << ChangedPageTracker.Allocation.NODE.ordinal())
            | (1 << ChangedPageTracker.Allocation.SEGMENT.ordinal()))) != 0) {
          assertTrue("mark buffer exhausted", marks.count < marks.rows.length);
          var row = marks.rows[marks.count++];
          row[0] = call.<Integer>getArgument(0);
          row[1] = call.<Long>getArgument(1);
          row[2] = elapsed;
          row[3] = allocation;
          row[4] = marks.kinds;
        }
        return result;
      }).when(observed).mark(org.mockito.ArgumentMatchers.anyInt(),
          org.mockito.ArgumentMatchers.anyLong());
      var cache = storage.getWriteCache();
      set(storage, com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage.class,
          "changedPageTracker", observed);
      set(cache, WOWCache.class, "changedPageTracker", observed);
      var backup = java.nio.file.Files.createDirectories(root.resolve("backup"));
      storage.fullBackup(backup);
      var ready = new java.util.concurrent.CountDownLatch(16);
      var release = new java.util.concurrent.CountDownLatch(1);
      var records = new ArrayList<BurstMarks>();
      var tasks = new ArrayList<java.util.concurrent.Future<?>>();
      try (var writers = Executors.newFixedThreadPool(16)) {
        for (int writer = 0; writer < 16; writer++) {
          int id = writer;
          var marks = new BurstMarks();
          records.add(marks);
          tasks.add(writers.submit(() -> {
            try (var session = tracks.open("burst", "admin", "adminpwd")) {
              session.begin();
              session.command("update Burst" + id + " set value = 'after'");
              ready.countDown();
              assertTrue(release.await(60, TimeUnit.SECONDS));
              local.set(marks);
              try {
                long start = System.nanoTime();
                session.commit();
                marks.commitNs = System.nanoTime() - start;
              } finally {
                local.remove();
              }
            }
            return null;
          }));
        }
        assertTrue(ready.await(60, TimeUnit.SECONDS));
        var inventory = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class,
            org.mockito.AdditionalAnswers.delegatesTo(cache));
        var reached = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
          // files() runs after the production beginBoundary and freezer release.
          if (reached.compareAndSet(false, true)) {
            release.countDown();
            for (var task : tasks) {
              task.get(60, TimeUnit.SECONDS);
            }
          }
          return cache.files();
        }).when(inventory).files();
        set(storage,
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage.class,
            "writeCache", inventory);
        try {
          storage.backup(backup);
          assertTrue(reached.get());
        } finally {
          release.countDown();
          set(storage,
              com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage.class,
              "writeCache", cache);
        }
      }
      long ns = 0;
      long bytes = 0;
      long commits = 0;
      long classification = 0;
      long markCalls = 0;
      var files = new HashSet<Long>();
      for (int writer = 0; writer < records.size(); writer++) {
        var marks = records.get(writer);
        commits += marks.commitNs;
        classification += marks.classificationNs;
        markCalls += marks.markCalls;
        System.out.printf(Locale.ROOT,
            "BURST_COMMIT run=%d writer=%d markCalls=%d firstMarks=%d commitNs=%d%n",
            run, writer, marks.markCalls, marks.count, marks.commitNs);
        for (int mark = 0; mark < marks.count; mark++) {
          var row = marks.rows[mark];
          ns += row[2];
          bytes += row[3];
          files.add(row[0]);
          System.out.printf(Locale.ROOT,
              "BURST_MARK run=%d writer=%d file=%d page=%d ns=%d bytes=%d kinds=%d commitNs=%d%n",
              run, writer, row[0], row[1], row[2], row[3], row[4], marks.commitNs);
        }
      }
      assertTrue("burst must allocate marks in many files", files.size() >= 16);
      // Post-burst calibration does not prime any measured generation. Both trackers already
      // contain this bit. The positive delta estimates interception overhead, not a warm control.
      var direct = new ChangedPageTracker();
      direct.mark(1, 0);
      var intercepted = org.mockito.Mockito.spy(direct);
      int calls = 10000;
      long directBytes = allocated();
      long directStart = System.nanoTime();
      for (int call = 0; call < calls; call++) {
        direct.mark(1, 0);
      }
      long directNs = System.nanoTime() - directStart;
      directBytes = allocated() - directBytes;
      long interceptedBytes = allocated();
      long interceptedStart = System.nanoTime();
      for (int call = 0; call < calls; call++) {
        intercepted.mark(1, 0);
      }
      long interceptedNs = System.nanoTime() - interceptedStart;
      interceptedBytes = allocated() - interceptedBytes;
      double extraNs = Math.max(0, interceptedNs - directNs) / (double) calls;
      double extraBytes = Math.max(0, interceptedBytes - directBytes) / (double) calls;
      double share = ns / (double) commits;
      System.out.printf(Locale.ROOT,
          "BURST_RUN run=%d files=%d markCalls=%d firstMarkNs=%d firstMarkBytes=%d commitNs=%d share=%.9f classificationNs=%d interceptionExtraNs=%.3f interceptionExtraBytes=%.3f%n",
          run, files.size(), markCalls, ns, bytes, commits, share, classification, extraNs,
          extraBytes);
      series.add(ns, bytes, commits, share, classification, extraNs, extraBytes);
    } finally {
      com.jetbrains.youtrackdb.internal.common.io.FileUtils.deleteRecursively(root.toFile());
    }
  }

  public static final class Series {
    private final String label;
    private final String[] fields;
    private final double[][] values;
    private int count;

    public Series(String label, String... fields) {
      this.label = label;
      this.fields = fields;
      values = new double[fields.length][samples()];
    }

    public void add(double... sample) {
      assertEquals(fields.length, sample.length);
      assertTrue(count < samples());
      for (int metric = 0; metric < fields.length; metric++) {
        assertTrue(Double.isFinite(sample[metric]) && sample[metric] >= 0);
        values[metric][count] = sample[metric];
      }
      count++;
    }

    public void report() {
      assertEquals(samples(), count);
      long gcCount = 0;
      long gcMillis = 0;
      for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
        gcCount += Math.max(0, gc.getCollectionCount());
        gcMillis += Math.max(0, gc.getCollectionTime());
      }
      System.out.println("TRACKER_CHILD_STATE row=" + label.split(" ")[0]
          + " cumulativeGcCount=" + gcCount + " cumulativeGcMillis=" + gcMillis
          + " heapUsedBytes=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
          + " allocationScope=opening-thread-Java-bytes nestedTimes=not-additive");
      for (int metric = 0; metric < fields.length; metric++) {
        var sorted = values[metric].clone();
        Arrays.sort(sorted);
        double median = (sorted[(count - 1) / 2] + sorted[count / 2]) / 2;
        System.out.printf(Locale.ROOT,
            "TRACKER_PERFORMANCE %s samples=%d metric=%s median=%.3f p90=%.3f min=%.3f max=%.3f%n",
            label, count, fields[metric], median, sorted[(int) Math.ceil(count * 0.9) - 1],
            sorted[0], sorted[count - 1]);
      }
    }
  }
}
