package com.jetbrains.youtrackdb.benchmarks.manual;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Control;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.infra.ThreadParams;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 10)
@Measurement(iterations = 3, time = 10)
@Fork(1)
public class WriteWorkload {

  @Param("2")
  public int databases;
  @Param("1")
  public int busyDatabases;
  @Param("1")
  public int busyThreads;
  @Param("1")
  public int quietThreads;
  @Param("false")
  public boolean encryption;
  @Param("256")
  public int recordBytes;
  @Param("10000")
  public long quietPaceMicros;
  @Param("6")
  public int fixedEngineThreads;
  @Param("")
  public String extra;

  private Path directory;
  private YouTrackDB instance;
  private final List<String> created = new ArrayList<>();
  private final List<Worker> workers = new ArrayList<>();
  private Object[] records;
  private byte[][] busyPayloads;
  private byte[][] quietPayloads;
  private long fsyncMillis;

  @Setup(Level.Trial)
  public void open(BenchmarkParams params) throws IOException {
    require(databases > 0, "databases must be positive");
    require(busyDatabases >= 0 && busyDatabases <= databases,
        "busyDatabases must be in 0..databases");
    require(busyThreads >= 0, "busyThreads must be nonnegative");
    require(quietThreads >= 0, "quietThreads must be nonnegative");
    require(busyThreads >= busyDatabases, "Each busy database needs at least one busy writer");
    require(busyDatabases > 0 || busyThreads == 0, "Busy writers need a busy database");
    require(quietThreads == 0 || databases > busyDatabases,
        "Quiet writers need a separate database");
    int writers = Math.addExact(busyThreads, quietThreads);
    require(params.getThreads() == Math.max(1, writers),
        "Set JMH -t to busyThreads + quietThreads, or 1 for idle-only");
    require(fixedEngineThreads >= 0, "fixedEngineThreads must be nonnegative");
    require(recordBytes > 0 && recordBytes <= 8 * 1024 * 1024,
        "recordBytes must be in 1..8388608");
    require(quietPaceMicros > 0 && quietPaceMicros <= TimeUnit.SECONDS.toMicros(60),
        "quietPaceMicros must be in 1..60000000");
    require(params.getMode() == Mode.Throughput, "Only JMH Throughput mode is supported");
    long requiredHeap = databases * (256L << 20) + (512L << 20) + 4L * recordBytes;
    require(Runtime.getRuntime().maxMemory() >= requiredHeap,
        "Heap needs databases * 256 MiB + 512 MiB + four payloads");
    try {
      directory = Files.createTempDirectory("ytdb-write-jmh-");
      configure();
      long engineBound = engineThreadBound();
      int cores = Runtime.getRuntime().availableProcessors();
      require(writers + engineBound <= cores,
          "Writer threads=" + writers + ", engine thread bound=" + engineBound
              + ", available processors=" + cores + ": need at least "
              + (writers + engineBound) + " dedicated cores");
      System.out.println("DIRECTORY path=" + directory);
      validateIteration(params.getWarmup());
      validateIteration(params.getMeasurement());
      busyPayloads = payloads(recordBytes, 42);
      quietPayloads = payloads(64, 43);
      records = new Object[writers];
      int before = ManagementFactory.getThreadMXBean().getThreadCount();
      instance = YourTracks.instance(directory);
      for (int db = 0; db < databases; db++) {
        String name = "bench" + db;
        instance.create(name, DatabaseType.DISK, "bench", "benchmark-password", "admin");
        created.add(name);
        try (var g = instance.openTraversal(name, "bench", "benchmark-password")) {
          for (int writer = 0; writer < writers; writer++) {
            if (databaseFor(writer) == db) {
              final int index = writer;
              g.executeInTx(tx -> records[index] = tx.addV("Bench")
                  .property("payload", writerPayload(index)[0]).next().id());
            }
          }
        }
      }
      int observed = Math.max(0, ManagementFactory.getThreadMXBean().getThreadCount() - before);
      System.out.println("JVM " + System.getProperty("java.runtime.version")
          + " args=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
          + " maxHeapBytes=" + Runtime.getRuntime().maxMemory()
          + " gc=" + ManagementFactory.getGarbageCollectorMXBeans().stream()
              .map(bean -> bean.getName()).toList());
      System.out.println("WORKLOAD databases=" + databases + " busyDatabases=" + busyDatabases
          + " busyThreads=" + busyThreads + " quietThreads=" + quietThreads
          + " recordBytes=" + recordBytes + " quietPaceMicros=" + quietPaceMicros
          + " encryption=" + encryption + " fixedEngineThreads=" + fixedEngineThreads
          + " engineThreadBound=" + engineBound + " availableProcessors=" + cores
          + " minimumCores=" + (writers + engineBound) + " setupThreadGrowth=" + observed);
      for (var setting : GlobalConfiguration.values()) {
        System.out.println("CONFIG " + setting.getKey() + "="
            + (setting.isHidden() ? "<hidden>" : String.valueOf((Object) setting.getValue())));
      }
    } catch (IOException | RuntimeException | Error failure) {
      try {
        close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private void configure() {
    Map<String, String> settings = new LinkedHashMap<>();
    // Both keys pin the segment size to 120 MiB.
    settings.put("storage.wal.maxSegmentSize", "120");
    settings.put("storage.wal.minSegSize", "120");
    settings.put("storage.diskCache.diskFreeSpaceLimit", "960");
    settings.put("storage.callFsync", "true");
    settings.put("executor.pool.maxSize", "4");
    settings.put("executor.pool.io.maxSize", "4");
    settings.put("executor.pool.io.enabled", "true");
    settings.put("storage.diskCache.bufferSize", "256");
    settings.put("storage.wal.commitTimeout", "1000");
    settings.put("storage.wal.segmentsInterval", "10");
    settings.put("storage.wal.fuzzyCheckpointInterval", "300");
    settings.put("storage.wal.keepSingleSegment", "true");
    settings.put("storage.wal.maxSize", "-1");
    settings.put("storage.printWALPerformanceStatistics", "false");
    settings.put("memory.directMemory.trackMode", "false");
    settings.put("memory.profiling", "false");
    settings.put("storage.diskCache.writeCachePart", "5");
    settings.put("storage.diskCache.writeCachePageFlushInterval", "25");
    // This fixed key belongs only to throwaway benchmark databases.
    settings.put("storage.encryptionKey",
        encryption ? "MDEyMzQ1Njc4OWFiY2RlZg==" : null);
    if (!extra.isBlank()) {
      for (String pair : extra.split(";", -1)) {
        String[] parts = pair.split("=", 2);
        require(parts.length == 2 && parts[0].startsWith("youtrackdb."),
            "extra must contain semicolon-separated youtrackdb.key=value pairs");
        String key = parts[0].substring("youtrackdb.".length());
        require(!key.equals("storage.wal.path"),
            "WAL location must stay in the temporary database");
        settings.put(key, parts[1]);
      }
    }
    settings.forEach((key, value) -> {
      if (value == null) {
        System.clearProperty("youtrackdb." + key);
      } else {
        System.setProperty("youtrackdb." + key, value);
      }
    });
    System.clearProperty("youtrackdb.storage.wal.path");
    for (var entry : settings.entrySet()) {
      var setting = GlobalConfiguration.findByKey("youtrackdb." + entry.getKey());
      if (setting == null) {
        System.out.println("CONFIG " + entry.getKey() + " effective=not available");
      } else {
        require(
            String.valueOf((Object) setting.getValue())
                .equalsIgnoreCase(String.valueOf(entry.getValue())),
            "Effective configuration differs from requested value for " + entry.getKey());
      }
    }
    var location = GlobalConfiguration.findByKey("youtrackdb.storage.wal.path");
    require(location == null || location.getValue() == null,
        "Effective WAL location must be the database directory");
    fsyncMillis = GlobalConfiguration.findByKey("youtrackdb.storage.wal.commitTimeout")
        .getValueAsLong();
    require(fsyncMillis > 0, "WAL commit timeout must be positive");
  }

  private long engineThreadBound() {
    long bound = fixedEngineThreads;
    for (String key : List.of("executor.pool.maxSize", "executor.pool.io.maxSize")) {
      int maximum = GlobalConfiguration.findByKey("youtrackdb." + key).getValueAsInteger();
      require(maximum > 0, key + " must be positive, not CPU-derived");
      // The product maps a positive pin to a pool maximum no greater than the pin.
      // Count the pin itself to keep admission conservative across both versions.
      bound += maximum;
    }
    require(GlobalConfiguration.findByKey("youtrackdb.executor.pool.io.enabled")
        .getValueAsBoolean(), "executor.pool.io.enabled must be true to bound I/O threads");
    return bound;
  }

  private void validateIteration(IterationParams iteration) {
    long nanos = iteration.getTime().convertTo(TimeUnit.NANOSECONDS);
    require(nanos > 0 && nanos % TimeUnit.MILLISECONDS.toNanos(fsyncMillis) == 0,
        "Iteration length must be a positive multiple of the WAL commit timeout");
  }

  private int databaseFor(int writer) {
    return writer < busyThreads ? writer % busyDatabases : busyDatabases;
  }

  private byte[][] writerPayload(int writer) {
    return writer < busyThreads ? busyPayloads : quietPayloads;
  }

  private static byte[][] payloads(int bytes, long seed) {
    byte[][] values = {new byte[bytes], new byte[bytes]};
    var random = new Random(seed);
    for (byte[] value : values) {
      random.nextBytes(value);
    }
    return values;
  }

  @Benchmark
  public void write(Worker worker, Control control) {
    if (worker.graph == null) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      return;
    }
    long now = System.nanoTime();
    long intended = worker.next == 0 ? now : worker.next;
    byte[] payload = worker.payloads[worker.sequence++ & 1];
    long start;
    // Use one observation for normalization, waiting and transaction membership.
    // A newly observed window can move an overdue slot into the future.
    while (true) {
      start = System.nanoTime();
      worker.observeMeasurement(control, start);
      if (worker.quiet) {
        intended = worker.normalizeIntended(intended,
            TimeUnit.MICROSECONDS.toNanos(quietPaceMicros));
        if (worker.windowStart != 0 && worker.firstIntended == 0) {
          worker.firstIntended = intended;
          worker.measuredNext = intended;
        }
        if (control.stopMeasurement
            || (worker.windowStart != 0 && start >= worker.windowEnd)) {
          return;
        }
        long remaining = intended - start;
        if (remaining > 0) {
          LockSupport.parkNanos(Math.min(remaining, 100_000));
          continue;
        }
      }
      break;
    }
    // The nominal end excludes overrun calls. Completion can cross that end.
    boolean measured = worker.windowStart != 0 && start < worker.windowEnd
        && !control.stopMeasurement;
    worker.graph.executeInTx(g -> g.V(worker.record).property("payload", payload).iterate());
    long end = System.nanoTime();
    if (worker.quiet) {
      worker.next = intended + TimeUnit.MICROSECONDS.toNanos(quietPaceMicros);
    }
    if (measured) {
      worker.service.add(end - start);
      if (worker.quiet) {
        worker.quietCommits++;
        worker.response.add(end - intended);
        if (worker.previousMeasuredEnd > intended) {
          worker.lateExecuted++;
        }
        worker.previousMeasuredEnd = end;
        worker.measuredNext = worker.next;
      } else {
        worker.busyCommits++;
      }
    }
  }

  @TearDown(Level.Iteration)
  public void report(IterationParams iteration) {
    for (boolean quiet : new boolean[] {false, true}) {
      var service = new Histogram();
      var response = new Histogram();
      long late = 0;
      long outstanding = 0;
      long scheduled = 0;
      for (Worker worker : workers) {
        if (worker.quiet == quiet) {
          service.merge(worker.service);
          response.merge(worker.response);
          late += worker.lateExecuted;
          long pace = TimeUnit.MICROSECONDS.toNanos(quietPaceMicros);
          long first = worker.firstIntended;
          if (quiet && first != 0) {
            scheduled += slotsBefore(first, worker.windowEnd, pace);
            outstanding += slotsBefore(worker.measuredNext, worker.windowEnd, pace);
          }
        }
      }
      String role = quiet ? "quiet" : "busy";
      System.out.println("LATENCY " + iteration.getType() + " " + role + " service " + service);
      if (quiet) {
        System.out.println("LATENCY " + iteration.getType() + " quiet response " + response
            + " missedStarts=" + (late + outstanding) + " lateExecutedStarts=" + late
            + " unstartedStarts=" + outstanding + " scheduledStarts=" + scheduled);
      }
    }
  }

  private static long slotsBefore(long next, long end, long pace) {
    return next >= end ? 0 : 1 + (end - next - 1) / pace;
  }

  @TearDown(Level.Trial)
  public void close() throws IOException {
    try {
      if (instance != null) {
        try {
          for (String name : created) {
            instance.drop(name);
          }
        } finally {
          instance.close();
        }
      }
    } finally {
      if (directory != null) {
        try (var paths = Files.walk(directory)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(path);
          }
        }
      }
    }
  }

  private static void require(boolean valid, String message) {
    if (!valid) {
      throw new IllegalArgumentException(message);
    }
  }

  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Worker {

    public long busyCommits;
    public long quietCommits;
    private YTDBGraphTraversalSource graph;
    private Object record;
    private byte[][] payloads;
    private boolean quiet;
    private int sequence;
    private long next;
    private long windowStart;
    private long windowEnd;
    private long iterationNanos;
    private long firstIntended;
    private long measuredNext;
    private long previousMeasuredEnd;
    private long lateExecuted;
    private final Histogram service = new Histogram();
    private final Histogram response = new Histogram();

    @Setup(Level.Trial)
    public void open(WriteWorkload state, ThreadParams thread) {
      int index = thread.getThreadIndex();
      if (state.busyThreads + state.quietThreads > 0) {
        quiet = index >= state.busyThreads;
        record = state.records[index];
        payloads = state.writerPayload(index);
        graph = state.instance.openTraversal("bench" + state.databaseFor(index),
            "bench", "benchmark-password");
      }
      synchronized (state.workers) {
        state.workers.add(this);
      }
    }

    @Setup(Level.Iteration)
    public void reset(IterationParams iteration) {
      service.reset();
      response.reset();
      iterationNanos = iteration.getTime().convertTo(TimeUnit.NANOSECONDS);
      windowStart = 0;
      windowEnd = 0;
      firstIntended = 0;
      measuredNext = 0;
      previousMeasuredEnd = 0;
      lateExecuted = 0;
      next = 0;
    }

    private long normalizeIntended(long intended, long pace) {
      if (windowStart != 0 && intended < windowStart) {
        return intended + ((windowStart - intended + pace - 1) / pace) * pace;
      }
      return intended;
    }

    private void observeMeasurement(Control control, long now) {
      if (windowStart == 0 && control.startMeasurement && !control.stopMeasurement) {
        // Control has no edge timestamps. Anchor the nominal window at first observation.
        windowStart = now;
        windowEnd = now + iterationNanos;
      }
    }

    @TearDown(Level.Trial)
    public void close() {
      if (graph != null) {
        graph.close();
      }
    }
  }

  private static class Histogram {

    private final long[] bins = new long[4096];
    private long count;
    private long maximum;

    void add(long nanos) {
      long value = Math.max(1, nanos);
      // Retain six significant fraction bits without allocating per transaction.
      int exponent = 63 - Long.numberOfLeadingZeros(value);
      int fraction = (int) ((value - (1L << exponent)) >>> Math.max(0, exponent - 6));
      bins[exponent * 64 + fraction]++;
      count++;
      maximum = Math.max(maximum, value);
    }

    void reset() {
      Arrays.fill(bins, 0);
      count = 0;
      maximum = 0;
    }

    void merge(Histogram other) {
      for (int i = 0; i < bins.length; i++) {
        bins[i] += other.bins[i];
      }
      count += other.count;
      maximum = Math.max(maximum, other.maximum);
    }

    private double percentile(double fraction) {
      long target = (long) Math.ceil(count * fraction);
      long seen = 0;
      for (int i = 0; i < bins.length; i++) {
        seen += bins[i];
        if (seen >= target) {
          int exponent = i / 64;
          double upper = exponent < 6
              ? (1L << exponent) + i % 64 : Math.scalb(1 + (i % 64 + 1) / 64.0, exponent);
          return Math.min(maximum, upper) / 1000;
        }
      }
      return Double.NaN;
    }

    @Override
    public String toString() {
      if (count == 0) {
        return "n=0 p50/p95/p99/max=not available";
      }
      return "n=" + count + " p50_us=" + percentile(0.50) + " p95_us=" + percentile(0.95)
          + " p99_us=" + percentile(0.99) + " max_us=" + maximum / 1000.0;
    }
  }
}
