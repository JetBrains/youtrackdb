package com.jetbrains.youtrackdb.benchmarks.pagelock;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/** One disposable copy, independent writer partitions and boundary counters per trial. */
@State(Scope.Benchmark)
public class WriterBenchmarkState {

  @Param({"target/pagelock-data"})
  public String seedRoot;
  @Param({"target/pagelock-runs"})
  public String runRoot;
  // Normal cases fit their record data in cache. Small-cache cases use 80 MiB total below.
  @Param({"1024"})
  public int bufferMiB;
  @Param({"20"})
  public int writeCachePercent;
  @Param({"8"})
  public int pageKiB;
  @Param({"1024"})
  public int commitLimit;
  @Param({"4"})
  public double pinPercent;
  // Smaller data sets are explicit overrides for wiring tests, not measurement defaults.
  @Param({"262144"})
  public int recordCount;

  final WriterRunValidity validity = new WriterRunValidity();
  WriterRunValidity.Commit[] commits;
  IterationCounters counters;
  private final List<AutoCloseable> resources = new ArrayList<>();
  private Path copy;
  private String workload;
  private int writers;

  @Setup(Level.Trial)
  public void setup(BenchmarkParams params) throws Exception {
    try {
      writers = params.getThreads();
      WriterSettings.validateRun(writers, params.getForks());
      String method = params.getBenchmark().substring(params.getBenchmark().lastIndexOf('.') + 1);
      workload = method.replaceAll("[14]$", "");
      if (!List.of("singleRecords", "nearLimit", "smallCache").contains(workload)) {
        throw new IllegalArgumentException("Unknown writer workload: " + method);
      }
      var settings = new WriterSettings(workload.equals("smallCache") ? 80 : bufferMiB,
          writeCachePercent, pageKiB,
          commitLimit, pinPercent);
      int pages = workload.equals("nearLimit") ? commitLimit - 1 : WriterSettings.RECORD_PAGE_BOUND;
      settings.checkCapacity(writers, pages);
      if (workload.equals("smallCache")
          && settings.readCachePages() * pageKiB != 64 * 1024L) {
        throw new IllegalArgumentException("Small cache requires exactly 64 MiB read cache");
      }
      // Includes read-back by key and the running-engine guard. No seed is opened before this.
      settings.apply(writers, pages);
      open(settings);
    } catch (Exception | Error failure) {
      validity.record(failure);
      try {
        cleanup();
      } catch (Exception | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private void open(WriterSettings settings) throws Exception {
    Path root = Path.of(seedRoot);
    boolean near = workload.equals("nearLimit");
    Path seed = near ? NearLimitWorkload.seedPath(root, writers, settings)
        : RecordWorkload.seedRoot(root, pageKiB)
            .resolve("records-v1-" + writers + "-" + recordCount);
    // prepare must never generate data here, even if the seed disappears after this check.
    if (!Files.isDirectory(seed)) {
      throw new IllegalStateException("Prepare the full writer seed first: " + seed);
    }
    var prepared = SeedDatabase.prepare(seed, path -> {
      throw new IllegalStateException("Prepared writer seed disappeared: " + seed);
    });
    copy = prepared.freshCopy(Path.of(runRoot));
    var database = (YouTrackDBImpl) YourTracks.instance(copy.toString());
    resources.add(database);
    commits = new WriterRunValidity.Commit[writers];
    if (near) {
      try (var session = database.open(NearLimitWorkload.DATABASE, "admin", "admin")) {
        var storage = session.getStorage();
        var workloads = new NearLimitWorkload[writers];
        for (int i = 0; i < writers; i++) {
          workloads[i] = new NearLimitWorkload(storage, i, settings);
          commits[i] = workloads[i]::commitMeasured;
        }
        NearLimitWorkload.checkOwnership(workloads);
        counters = new IterationCounters(new CounterProbes(storage), unavailable());
      }
    } else {
      var workloads = new RecordWorkload[writers];
      for (int i = 0; i < writers; i++) {
        workloads[i] = new RecordWorkload(database, i, recordCount / writers);
        resources.add(workloads[i]);
        commits[i] = workloads[i]::commit;
      }
      RecordWorkload.checkOwnership(workloads);
      var storage = workloads[0].session.getStorage();
      settings.verifyCache(storage.getReadCache(), unavailable());
      counters = new IterationCounters(new CounterProbes(storage), unavailable());
    }
  }

  private static boolean unavailable() {
    return Boolean.getBoolean("pagelock.expectUnavailable");
  }

  @Setup(Level.Iteration)
  public void startIteration() {
    try {
      counters.start();
    } catch (RuntimeException | Error failure) {
      validity.record(failure);
      throw failure;
    }
  }

  @TearDown(Level.Iteration)
  public void finishIteration(IterationParams params) {
    try {
      var delta = counters.finish(params.getType() == IterationType.MEASUREMENT);
      System.out.println("PAGELOCK " + IterationCounters.json(
          Map.of("benchmark", "goal9", "workload", workload, "writers", Integer.toString(writers),
              "phase", params.getType().name(),
              "pageSetShare", IterationCounters.iterationShare(delta)),
          delta));
    } catch (RuntimeException | Error failure) {
      validity.record(failure);
      throw failure;
    }
  }

  @TearDown(Level.Trial)
  public void close() throws Exception {
    try {
      validity.finish(counters);
    } catch (RuntimeException | Error failure) {
      validity.record(failure);
      throw failure;
    } finally {
      cleanup();
    }
  }

  private void cleanup() throws Exception {
    Throwable first = null;
    // Sessions close before their database. Every close is attempted, including after an Error.
    for (int i = resources.size() - 1; i >= 0; i--) {
      try {
        resources.get(i).close();
      } catch (Exception | Error failure) {
        validity.record(failure);
        if (first == null) {
          first = failure;
        } else {
          first.addSuppressed(failure);
        }
      }
    }
    resources.clear();
    try {
      if (copy != null) {
        SeedDatabase.deleteTree(copy);
        copy = null;
      }
    } catch (Exception | Error failure) {
      validity.record(failure);
      if (first == null) {
        first = failure;
      } else {
        first.addSuppressed(failure);
      }
    }
    if (first instanceof Exception exception) {
      throw exception;
    }
    if (first instanceof Error error) {
      throw error;
    }
  }
}
