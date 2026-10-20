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

/** Opt-in, uninstrumented child workloads. Timings are evidence, not regression thresholds. */
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
    // The real WAL needs the same module access as the parent, but no instrumentation agent.
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
