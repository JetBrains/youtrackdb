package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Ignore;

/**
 * Long-running stress test for Records GC under sustained high load.
 *
 * <p>Verifies that the GC keeps disk space usage bounded and throughput stable over
 * time. Multiple writer threads continuously update and churn records (80% updates,
 * 20% delete+reinsert) while the storage's built-in periodic GC task reclaims dead
 * records. Collection size stays fixed — no pure inserts — so disk space stabilization
 * is a clean signal. The test observes only externally visible properties:
 * <ul>
 *   <li><b>No errors</b> during concurrent operation</li>
 *   <li><b>Data integrity</b> — live record count matches initial seed</li>
 *   <li><b>Disk space stabilization</b> — growth rate must not accelerate over time
 *       (would indicate dead records accumulating faster than GC reclaims them)</li>
 *   <li><b>No throughput hiccups</b> — no single measurement window drops below 30%
 *       of the median (would indicate GC pauses stalling the workload)</li>
 *   <li><b>No throughput collapse</b> — last third average stays above 40% of
 *       first third average</li>
 * </ul>
 *
 * <p>This test is excluded from the regular unit test suite ({@code @Ignore}) because
 * a meaningful run requires at least 15 minutes on dedicated hardware. Run manually on
 * a CCX33 Hetzner node (8 dedicated vCPUs, 32 GB RAM) via {@code exec:exec} — this
 * bypasses surefire entirely and uses a production-like JVM (heap + module opens, no
 * {@code -ea}, no test-specific disk cache or memory tracking flags):
 * <pre>{@code
 * ./mvnw -pl core -am verify -P stress-test -DskipTests
 * }</pre>
 * Override duration: {@code -DstressArgs="30 10"} (durationMinutes windowSeconds).
 *
 * <p>GC settings use production defaults (minThreshold=1000, scaleFactor=0.1,
 * pauseInterval=60s). Only the snapshot cleanup threshold is lowered for testability
 * — see comments in {@link #run()} for rationale.
 */
@Ignore("Manual-only: requires 15+ minutes on dedicated hardware (CCX33)")
public class RecordsGcStressTest {

  private static final String DB_NAME = "gcStressTest";
  private static final String CLASS_NAME = "StressRecord";
  private static final int INITIAL_RECORD_COUNT = 5_000;
  private static final int WRITER_THREADS = 6;
  private static final int READER_THREADS = 2;
  private static final int MAX_RETRIES = 5;

  // Payload size varies to exercise both single-chunk and multi-chunk records.
  private static final int SMALL_PAYLOAD_SIZE = 200;
  private static final int LARGE_PAYLOAD_SIZE = 20_000;

  // Pre-computed payloads to avoid re-creating strings on every operation.
  private static final String SMALL_UPDATE_PAYLOAD = "u".repeat(SMALL_PAYLOAD_SIZE);
  private static final String LARGE_UPDATE_PAYLOAD = "U".repeat(LARGE_PAYLOAD_SIZE);
  private static final String REINSERT_PAYLOAD = "r".repeat(SMALL_PAYLOAD_SIZE);
  private static final String SEED_PAYLOAD = "x".repeat(SMALL_PAYLOAD_SIZE);

  // Number of warmup windows to skip when computing baselines.
  // The first few windows include JIT compilation, cache population, and WAL init.
  private static final int WARMUP_WINDOWS = 2;

  private Duration testDuration;
  private Duration windowDuration;
  private final Path dbPath;

  public RecordsGcStressTest() {
    long durationMinutes = Long.parseLong(
        System.getProperty("stressDurationMinutes", "15"));
    long windowSeconds = Long.parseLong(
        System.getProperty("stressWindowSeconds", "10"));
    this.testDuration = Duration.ofMinutes(durationMinutes);
    this.windowDuration = Duration.ofSeconds(windowSeconds);
    this.dbPath = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath()
        .resolve("gc-stress-test-" + System.nanoTime());
  }

  /**
   * Run the stress test from the command line.
   * Default: 15-minute duration, 10-second measurement windows.
   * Usage: main [durationMinutes] [windowSeconds]
   *
   * <p>Run via the {@code stress-test} Maven profile which uses {@code exec:exec}
   * to fork a production-like JVM (heap + module opens, no {@code -ea}):
   * <pre>{@code
   * ./mvnw -pl core -am verify -P stress-test -DskipTests
   * }</pre>
   * Override duration (e.g., 30 min, 10 s windows):
   * <pre>{@code
   * ./mvnw -pl core -am verify -P stress-test -DskipTests -DstressArgs="30 10"
   * }</pre>
   */
  public static void main(String[] args) throws Exception {
    var test = new RecordsGcStressTest();
    test.testDuration = args.length >= 1
        ? Duration.ofMinutes(Long.parseLong(args[0])) : Duration.ofMinutes(15);
    test.windowDuration = args.length >= 2
        ? Duration.ofSeconds(Long.parseLong(args[1])) : Duration.ofSeconds(10);

    System.out.println("=== Records GC Stress Test ===");
    System.out.printf("Duration: %s, Window: %s%n",
        test.testDuration, test.windowDuration);
    System.out.printf("Writers: %d, Readers: %d%n",
        WRITER_THREADS, READER_THREADS);
    System.out.printf("Initial records: %d%n%n", INITIAL_RECORD_COUNT);

    var result = test.run();
    result.printReport();

    if (!result.passed) {
      System.exit(1);
    }
  }

  /**
   * JUnit entry point. Marked {@code @Ignore} — run manually via
   * {@code exec:exec -P stress-test}.
   */
  @org.junit.Test
  public void stressTestGcUnderSustainedLoad() throws Exception {
    var result = run();
    result.printReport();

    org.assertj.core.api.Assertions.assertThat(result.passed)
        .as("Stress test should pass all checks: " + result.failureReason)
        .isTrue();
  }

  public StressTestResult run() throws Exception {
    // Remove the engine shutdown hook up front. The hook races with
    // youTrackDB.close() in the finally block below and can crash in
    // ByteBufferPool.clear() → Unsafe.freeMemory() on already-freed
    // direct memory. We handle cleanup explicitly via close().
    com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager.instance()
        .removeShutdownHook();

    FileUtils.deleteRecursively(dbPath.toFile());

    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(dbPath);
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
            PredefinedLocalRole.ADMIN));

    try {
      // GC settings use production defaults:
      //   - GC min threshold: 1000 (default)
      //   - GC scale factor: 0.1 (default)
      //   - GC pause interval: 60s (default)
      //
      // Snapshot cleanup threshold is lowered from 10,000 to 100. The default
      // is designed for million-record workloads — our 5000-record test never
      // reaches it, so the dead record counter would stay at zero and GC
      // would never trigger. This is not a GC setting; it controls how
      // promptly stale snapshot entries are evicted (a prerequisite for the
      // dead record counter to increment).
      try (var session = openSession(youTrackDB)) {
        var storage = (AbstractStorage) session.getStorage();
        storage.getContextConfiguration()
            .setValue(
                GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD,
                100);

        session.command("CREATE CLASS " + CLASS_NAME);

        // Seed initial records in batches.
        int batchSize = 100;
        for (int i = 0; i < INITIAL_RECORD_COUNT; i += batchSize) {
          session.begin();
          int end = Math.min(i + batchSize, INITIAL_RECORD_COUNT);
          for (int j = i; j < end; j++) {
            session.command("INSERT INTO " + CLASS_NAME
                + " SET idx = " + j + ", ver = 0, payload = '"
                + SEED_PAYLOAD + "'");
          }
          session.commit();
        }
      }

      var result = runWorkload(youTrackDB);
      return result;
    } finally {
      youTrackDB.close();
      FileUtils.deleteRecursively(dbPath.toFile());
    }
  }

  private StressTestResult runWorkload(YouTrackDBImpl youTrackDB)
      throws Exception {
    var stop = new AtomicBoolean(false);
    var errors = new AtomicReference<Throwable>();
    var writeOps = new AtomicLong();
    var readOps = new AtomicLong();
    var retriesExhausted = new AtomicLong();

    int totalThreads = WRITER_THREADS + READER_THREADS;
    var barrier = new CyclicBarrier(totalThreads + 1); // +1 for monitor
    var executor = Executors.newFixedThreadPool(totalThreads);
    var futures = new ArrayList<Future<?>>();

    try {
      // Writer threads: mixed updates, deletes, and inserts.
      // Each writer reuses a single session across iterations to avoid per-op
      // session open/close overhead that would dominate throughput measurements.
      for (int w = 0; w < WRITER_THREADS; w++) {
        final int writerId = w;
        futures.add(executor.submit(() -> {
          try (var session = openSession(youTrackDB)) {
            barrier.await(30, TimeUnit.SECONDS);
            int localCounter = 0;
            while (!stop.get()) {
              // 80% updates (creates dead versions) + 20% delete-reinsert
              // (churn). Every operation produces dead records for GC.
              // No pure inserts — collection size stays fixed so disk space
              // stabilization is a clean signal for GC effectiveness.
              int op = localCounter % 5;
              boolean success = false;
              for (int retry = 0; retry < MAX_RETRIES && !success; retry++) {
                try {
                  session.begin();
                  int rangeSize = INITIAL_RECORD_COUNT / WRITER_THREADS;
                  int rangeStart = writerId * rangeSize;
                  int target = rangeStart + (localCounter % rangeSize);
                  if (op < 4) {
                    // 80%: update existing record.
                    String payload = (localCounter % 20 == 0)
                        ? LARGE_UPDATE_PAYLOAD : SMALL_UPDATE_PAYLOAD;
                    session.command("UPDATE " + CLASS_NAME
                        + " SET ver = ver + 1, payload = '" + payload + "'"
                        + " WHERE idx = " + target);
                  } else {
                    // 20%: delete + reinsert (churn).
                    session.command("DELETE FROM " + CLASS_NAME
                        + " WHERE idx = " + target + " LIMIT 1");
                    session.command("INSERT INTO " + CLASS_NAME
                        + " SET idx = " + target + ", ver = 0, payload = '"
                        + REINSERT_PAYLOAD + "'");
                  }
                  session.commit();
                  success = true;
                  writeOps.incrementAndGet();
                } catch (ConcurrentModificationException e) {
                  session.rollback();
                }
              }
              if (!success) {
                retriesExhausted.incrementAndGet();
              }
              localCounter++;
            }
          } catch (Throwable t) {
            if (!stop.get()) {
              errors.compareAndSet(null, t);
              stop.set(true);
            }
          }
        }));
      }

      // Reader threads: continuous reads to exercise snapshot isolation.
      for (int r = 0; r < READER_THREADS; r++) {
        futures.add(executor.submit(() -> {
          try (var session = openSession(youTrackDB)) {
            barrier.await(30, TimeUnit.SECONDS);
            while (!stop.get()) {
              session.begin();
              try (var result = session.query(
                  "SELECT FROM " + CLASS_NAME + " LIMIT 100")) {
                while (result.hasNext()) {
                  result.next();
                }
              }
              session.commit();
              readOps.incrementAndGet();
              Thread.sleep(1);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Throwable t) {
            if (!stop.get()) {
              errors.compareAndSet(null, t);
              stop.set(true);
            }
          }
        }));
      }

      // GC runs via the storage's built-in PeriodicRecordsGc scheduled task
      // (default 60-second interval). No manual GC threads needed.

      // Monitor: collects metrics per window on the current thread.
      var windows = new ArrayList<WindowMetrics>();
      barrier.await(30, TimeUnit.SECONDS);
      var startTime = Instant.now();

      while (!stop.get()) {
        long windowStartWrites = writeOps.get();
        long windowStartReads = readOps.get();
        long pclStart = measureByExt(".pcl");
        long cpmStart = measureByExt(".cpm");
        long fsmStart = measureByExt(".fsm");
        long dpbStart = measureByExt(".dpb");
        long windowStartMs = System.currentTimeMillis();

        Thread.sleep(windowDuration.toMillis());

        long windowEndWrites = writeOps.get();
        long windowEndReads = readOps.get();
        long pclEnd = measureByExt(".pcl");
        long cpmEnd = measureByExt(".cpm");
        long fsmEnd = measureByExt(".fsm");
        long dpbEnd = measureByExt(".dpb");
        long elapsedMs = System.currentTimeMillis() - windowStartMs;

        var wm = new WindowMetrics(
            Duration.between(startTime, Instant.now()),
            windowEndWrites - windowStartWrites,
            windowEndReads - windowStartReads,
            pclEnd, pclEnd - pclStart,
            cpmEnd, cpmEnd - cpmStart,
            fsmEnd, fsmEnd - fsmStart,
            dpbEnd, dpbEnd - dpbStart,
            elapsedMs);
        windows.add(wm);

        System.out.printf(
            "[%6.1fs] w/s: %4.0f | r/s: %4.0f"
                + " | pcl: %5.1fM(%+.0fK)"
                + " | cpm: %5.2fM(%+.0fK)"
                + " | fsm: %4.0fK(%+.0fK)"
                + " | dpb: %4.0fK(%+.0fK)%n",
            wm.elapsed.toMillis() / 1000.0,
            wm.writeThroughput(),
            wm.readThroughput(),
            wm.pclBytes / (1024.0 * 1024.0),
            wm.pclDelta / 1024.0,
            wm.cpmBytes / (1024.0 * 1024.0),
            wm.cpmDelta / 1024.0,
            wm.fsmBytes / 1024.0,
            wm.fsmDelta / 1024.0,
            wm.dpbBytes / 1024.0,
            wm.dpbDelta / 1024.0);

        if (Duration.between(startTime, Instant.now())
            .compareTo(testDuration) >= 0) {
          stop.set(true);
        }

        if (errors.get() != null) {
          break;
        }
      }

      stop.set(true);

      // Wait for all threads to finish.
      for (var f : futures) {
        try {
          f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
          // Errors are captured via the AtomicReference.
        }
      }

      // Final data integrity check — verify all live records are readable.
      int liveRecordCount;
      try (var session = openSession(youTrackDB)) {
        session.begin();
        liveRecordCount = 0;
        try (var result = session.query("SELECT FROM " + CLASS_NAME)) {
          while (result.hasNext()) {
            result.next();
            liveRecordCount++;
          }
        }
        session.commit();
      }

      return buildResult(windows, errors.get(), liveRecordCount,
          writeOps.get(), readOps.get(), retriesExhausted.get());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  /** Measures total size of files with the given extension in the DB directory. */
  private long measureByExt(String ext) {
    var dbDir = dbPath.resolve(DB_NAME);
    if (!Files.exists(dbDir)) {
      return 0;
    }
    try (var stream = Files.walk(dbDir)) {
      return stream.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(ext))
          .mapToLong(p -> {
            try {
              return Files.size(p);
            } catch (IOException e) {
              return 0;
            }
          }).sum();
    } catch (IOException e) {
      return 0;
    }
  }

  private StressTestResult buildResult(
      List<WindowMetrics> windows,
      Throwable error,
      int liveRecordCount,
      long totalWrites,
      long totalReads,
      long totalRetriesExhausted) {

    var result = new StressTestResult();
    result.windows = windows;
    result.error = error;
    result.liveRecordCount = liveRecordCount;
    result.totalWrites = totalWrites;
    result.totalReads = totalReads;
    result.totalRetriesExhausted = totalRetriesExhausted;

    // Check 1: No unexpected errors.
    if (error != null) {
      result.passed = false;
      result.failureReason = "Unexpected error: " + error;
      return result;
    }

    // Check 2: Data integrity — collection size is fixed (no pure inserts).
    // Churn ops (delete + reinsert) are transactional so net count stays the
    // same. Allow small slack for edge cases where a churn's delete succeeds
    // but the reinsert fails all retries (extremely unlikely with LIMIT 1).
    long minAcceptable = (long) (INITIAL_RECORD_COUNT * 0.95);
    if (liveRecordCount < minAcceptable) {
      result.passed = false;
      result.failureReason = String.format(
          "Too few live records: %d (expected %d, minimum acceptable: %d)",
          liveRecordCount, INITIAL_RECORD_COUNT, minAcceptable);
      return result;
    }

    List<WindowMetrics> steadyState = windows.size() > WARMUP_WINDOWS
        ? windows.subList(WARMUP_WINDOWS, windows.size()) : windows;

    // Check 3: No throughput hiccups — no single window should drop below 30%
    // of the median steady-state throughput. A GC-induced pause would show up
    // as one or more windows with anomalously low throughput.
    if (steadyState.size() >= 6) {
      var sortedThroughputs = steadyState.stream()
          .mapToDouble(WindowMetrics::writeThroughput)
          .sorted()
          .toArray();
      double medianThroughput =
          sortedThroughputs[sortedThroughputs.length / 2];
      double hiccupFloor = medianThroughput * 0.3;

      for (int i = 0; i < steadyState.size(); i++) {
        double tp = steadyState.get(i).writeThroughput();
        if (tp < hiccupFloor) {
          result.passed = false;
          result.failureReason = String.format(
              "Throughput hiccup at window %d: %.1f ops/s "
                  + "(median: %.1f, floor: %.1f)",
              i + WARMUP_WINDOWS, tp, medianThroughput, hiccupFloor);
          return result;
        }
      }
    }

    // Check 4: No throughput collapse — last third average must stay above
    // 40% of first third average. Collection size is fixed so throughput
    // should be roughly stable. The 40% floor catches catastrophic
    // GC-induced degradation.
    if (steadyState.size() >= 6) {
      int third = steadyState.size() / 3;
      double firstThirdAvg = steadyState.subList(0, third).stream()
          .mapToDouble(WindowMetrics::writeThroughput)
          .average().orElse(0);
      double lastThirdAvg = steadyState.subList(
          steadyState.size() - third, steadyState.size()).stream()
          .mapToDouble(WindowMetrics::writeThroughput)
          .average().orElse(0);

      if (firstThirdAvg > 0 && lastThirdAvg < firstThirdAvg * 0.4) {
        result.passed = false;
        result.failureReason = String.format(
            "Throughput collapsed: first third avg %.1f ops/s, last third "
                + "avg %.1f ops/s (%.1f%% of first)",
            firstThirdAvg, lastThirdAvg,
            (lastThirdAvg / firstThirdAvg) * 100);
        return result;
      }
    }

    // Check 5: Collection data (.pcl) growth rate stabilization. The .pcl
    // files contain actual record data and are the purest signal for GC
    // effectiveness. With a fixed collection size, .pcl growth comes only
    // from page allocation for new record versions. Once GC reaches steady
    // state, freed space is reused and .pcl growth rate drops to near zero.
    // If GC is broken, dead records accumulate and .pcl grows unboundedly.
    // The last third's growth rate must not exceed 3x the first third's.
    if (steadyState.size() >= 6) {
      int third = steadyState.size() / 3;
      double firstThirdGrowthRate = steadyState.subList(0, third).stream()
          .mapToLong(w -> Math.max(0, w.pclDelta))
          .average().orElse(0);
      double lastThirdGrowthRate = steadyState.subList(
          steadyState.size() - third, steadyState.size()).stream()
          .mapToLong(w -> Math.max(0, w.pclDelta))
          .average().orElse(0);

      if (firstThirdGrowthRate > 0
          && lastThirdGrowthRate > firstThirdGrowthRate * 3) {
        result.passed = false;
        result.failureReason = String.format(
            "PCL growth rate accelerating: first third avg %.1f KB/window,"
                + " last third avg %.1f KB/window (%.1fx)",
            firstThirdGrowthRate / 1024.0,
            lastThirdGrowthRate / 1024.0,
            lastThirdGrowthRate / firstThirdGrowthRate);
        return result;
      }
    }

    result.passed = true;
    return result;
  }

  private static DatabaseSessionEmbedded openSession(YouTrackDBImpl db) {
    return db.open(DB_NAME, "admin", DbTestBase.ADMIN_PASSWORD);
  }

  // -------------------------------------------------------------------------
  // Metrics and result types
  // -------------------------------------------------------------------------

  static class WindowMetrics {
    final Duration elapsed;
    final long writes;
    final long reads;
    // Per-extension file sizes and deltas.
    final long pclBytes, pclDelta; // .pcl — collection record data
    final long cpmBytes, cpmDelta; // .cpm — collection position map
    final long fsmBytes, fsmDelta; // .fsm — free space map
    final long dpbBytes, dpbDelta; // .dpb — dirty page bit set
    final long windowMs;

    WindowMetrics(Duration elapsed, long writes, long reads,
        long pclBytes, long pclDelta,
        long cpmBytes, long cpmDelta,
        long fsmBytes, long fsmDelta,
        long dpbBytes, long dpbDelta,
        long windowMs) {
      this.elapsed = elapsed;
      this.writes = writes;
      this.reads = reads;
      this.pclBytes = pclBytes;
      this.pclDelta = pclDelta;
      this.cpmBytes = cpmBytes;
      this.cpmDelta = cpmDelta;
      this.fsmBytes = fsmBytes;
      this.fsmDelta = fsmDelta;
      this.dpbBytes = dpbBytes;
      this.dpbDelta = dpbDelta;
      this.windowMs = windowMs;
    }

    double writeThroughput() {
      return windowMs > 0 ? (writes * 1000.0 / windowMs) : 0;
    }

    double readThroughput() {
      return windowMs > 0 ? (reads * 1000.0 / windowMs) : 0;
    }
  }

  static class StressTestResult {
    boolean passed;
    String failureReason;
    Throwable error;
    List<WindowMetrics> windows;
    int liveRecordCount;
    long totalWrites;
    long totalReads;
    long totalRetriesExhausted;

    void printReport() {
      System.out.println();
      System.out.println("=== Stress Test Report ===");
      System.out.printf("Result: %s%n", passed ? "PASSED" : "FAILED");
      if (!passed) {
        System.out.printf("Failure: %s%n", failureReason);
      }
      if (error != null) {
        System.out.printf("Error: %s%n", error);
        error.printStackTrace(System.out);
      }
      System.out.println();
      System.out.printf("Total writes:      %,d%n", totalWrites);
      System.out.printf("Total reads:       %,d%n", totalReads);
      System.out.printf("Retries exhausted: %,d%n", totalRetriesExhausted);
      System.out.printf("Live records:      %,d (expected: %,d)%n",
          liveRecordCount, INITIAL_RECORD_COUNT);

      if (windows != null && !windows.isEmpty()) {
        System.out.println();
        System.out.println("--- Per-window metrics ---");
        System.out.printf(
            "%-8s %6s %6s  %8s %8s  %8s %8s  %6s %6s  %6s %6s%n",
            "Time", "w/s", "r/s",
            "PCL MB", "PCL d", "CPM MB", "CPM d",
            "FSM K", "FSM d", "DPB K", "DPB d");
        for (var w : windows) {
          System.out.printf(
              "%-8.0fs %6.0f %6.0f  %8.1f %+7.0fK  %8.2f %+7.0fK"
                  + "  %6.0f %+5.0fK  %6.0f %+5.0fK%n",
              w.elapsed.toMillis() / 1000.0,
              w.writeThroughput(),
              w.readThroughput(),
              w.pclBytes / (1024.0 * 1024.0),
              w.pclDelta / 1024.0,
              w.cpmBytes / (1024.0 * 1024.0),
              w.cpmDelta / 1024.0,
              w.fsmBytes / 1024.0,
              w.fsmDelta / 1024.0,
              w.dpbBytes / 1024.0,
              w.dpbDelta / 1024.0);
        }

        var last = windows.get(windows.size() - 1);
        double avgWrites = windows.stream()
            .mapToDouble(WindowMetrics::writeThroughput)
            .average().orElse(0);
        double minWrites = windows.stream()
            .mapToDouble(WindowMetrics::writeThroughput)
            .min().orElse(0);
        double maxWrites = windows.stream()
            .mapToDouble(WindowMetrics::writeThroughput)
            .max().orElse(0);
        double avgReads = windows.stream()
            .mapToDouble(WindowMetrics::readThroughput)
            .average().orElse(0);
        double minReads = windows.stream()
            .mapToDouble(WindowMetrics::readThroughput)
            .min().orElse(0);
        double maxReads = windows.stream()
            .mapToDouble(WindowMetrics::readThroughput)
            .max().orElse(0);
        long peakPcl = windows.stream()
            .mapToLong(w -> w.pclBytes).max().orElse(0);

        System.out.println();
        System.out.printf(
            "Write throughput — avg: %.0f, min: %.0f, max: %.0f ops/s%n",
            avgWrites, minWrites, maxWrites);
        System.out.printf(
            "Read throughput  — avg: %.0f, min: %.0f, max: %.0f ops/s%n",
            avgReads, minReads, maxReads);
        System.out.printf(
            "PCL  — peak: %.1f MB, final: %.1f MB%n",
            peakPcl / (1024.0 * 1024.0),
            last.pclBytes / (1024.0 * 1024.0));
        System.out.printf(
            "CPM  — final: %.2f MB%n", last.cpmBytes / (1024.0 * 1024.0));
        System.out.printf(
            "FSM  — final: %.0f KB%n", last.fsmBytes / 1024.0);
        System.out.printf(
            "DPB  — final: %.0f KB%n", last.dpbBytes / 1024.0);
      }
    }
  }
}
