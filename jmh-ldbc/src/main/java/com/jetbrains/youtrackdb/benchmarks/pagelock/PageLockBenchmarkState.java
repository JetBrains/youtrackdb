package com.jetbrains.youtrackdb.benchmarks.pagelock;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/** One run copy and one snapshot pair for the entire asymmetric group. */
@State(Scope.Benchmark)
public class PageLockBenchmarkState {

  @Param({"DISJOINT", "SHARED", "DIFFERENT"})
  public String pattern;
  @Param({"1", "32"})
  public int pagesPerRead;
  @Param({"64"})
  public int pagesPerCommit;
  // -1 means all reader pages. A nonnegative value selects an exact overlap.
  @Param({"-1"})
  public int sharedPages;

  PageWorkloadComponent reader;
  PageWorkloadComponent[] writers;
  int[] readPages;
  int[] writePages;
  AbstractStorage storage;
  IterationCounters counters;
  private YouTrackDBImpl database;
  private Path copy;
  private int readerThreads;
  private int writerThreads;
  private int iteration;
  private Map<String, String> metadata;

  @Setup(Level.Trial)
  public void setup(BenchmarkParams params) throws Exception {
    int[] groups = params.getThreadGroups();
    validateThreads(params.getThreads(), groups, params.getForks());
    try {
      setup(Path.of(System.getProperty("pagelock.data", "target/pagelock-data")),
          groups[0], groups[1]);
    } catch (Exception failure) {
      try {
        close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  static void validateThreads(int threads, int[] groups, int forks) {
    if (forks != 1 || groups.length != 2 || groups[0] < 1 || groups[1] < 1
        || Arrays.stream(groups).sum() != threads) {
      throw new IllegalArgumentException("Use one fork and one group, -tg writers,readers");
    }
  }

  void setup(Path root, int writerCount, int readerCount) throws Exception {
    writerThreads = writerCount;
    readerThreads = readerCount;
    int overlap = configurePattern();
    int pageCount = pagesPerRead + pagesPerCommit;
    openCopy(root, writerCount, pageCount);
    counters = new IterationCounters(new CounterProbes(storage),
        Boolean.getBoolean("pagelock.expectUnavailable"));
    metadata = new LinkedHashMap<>(Map.of("benchmark", "goal8", "pattern", pattern,
        "pagesPerRead", Integer.toString(pagesPerRead),
        "pagesPerCommit", Integer.toString(pagesPerCommit),
        "sameComponent", Boolean.toString(reader == writers[0]),
        "sharedReaderPages", Integer.toString(overlap),
        "readers", Integer.toString(readerThreads), "writers", Integer.toString(writerThreads)));
    // Coordinates come from the workload itself, outside the measured path.
    metadata.put("readerPages", Arrays.toString(reader.read(readPages).pages()));
    for (int i = 0; i < writerCount; i++) {
      metadata.put("writer" + i + "Pages", Arrays.toString(writers[i].read(writePages).pages()));
    }
    try (var files = Files.walk(copy)) {
      long bytes = 0;
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        bytes += Files.size(file);
      }
      metadata.put("physicalBytes", Long.toString(bytes));
    }
  }

  int configurePattern() {
    readPages = IntStream.range(0, pagesPerRead).toArray();
    int overlap = pattern.equals("SHARED")
        ? (sharedPages == -1 ? pagesPerRead : sharedPages) : 0;
    if (!Arrays.asList("DISJOINT", "SHARED", "DIFFERENT").contains(pattern)
        || pagesPerRead < 1 || pagesPerCommit < 1 || overlap < 0
        || overlap > Math.min(pagesPerRead, pagesPerCommit)) {
      throw new IllegalArgumentException("Invalid controlled page pattern or overlap");
    }
    writePages = IntStream.range(0, pagesPerCommit)
        .map(i -> i < overlap ? i : pagesPerRead + i - overlap).toArray();
    return overlap;
  }

  private void openCopy(Path root, int writerCount, int pageCount) throws Exception {
    // The generator version, shape and writer count define a seed. Generation is never timed.
    var seed = SeedDatabase.prepare(root.resolve("pages-v1-" + pageCount + "-" + writerCount),
        path -> {
          try (var db = (YouTrackDBImpl) YourTracks.instance(path.toString())) {
            db.create("pages", DatabaseType.DISK, "admin", "admin", "admin");
            var seedStorage = storage(db);
            for (int i = 0; i < writerCount; i++) {
              component(seedStorage, "writer" + i, pageCount).create();
            }
            component(seedStorage, "reader", pageCount).create();
          }
        });
    copy = seed.freshCopy(root);
    database = (YouTrackDBImpl) YourTracks.instance(copy.toString());
    storage = storage(database);
    writers = new PageWorkloadComponent[writerCount];
    for (int i = 0; i < writerCount; i++) {
      writers[i] = component(storage, "writer" + i, pageCount);
      writers[i].open();
    }
    reader = pattern.equals("DIFFERENT") ? component(storage, "reader", pageCount) : writers[0];
    reader.open();
  }

  private static PageWorkloadComponent component(AbstractStorage storage, String name, int count) {
    return new PageWorkloadComponent(storage, name, count, true);
  }

  private static AbstractStorage storage(YouTrackDBImpl db) {
    try (var session = db.open("pages", "admin", "admin")) {
      return session.getStorage();
    }
  }

  @Setup(Level.Iteration)
  public void startIteration() {
    counters.start();
  }

  @TearDown(Level.Iteration)
  public void finishIteration(IterationParams params) {
    metadata.put("iteration", Integer.toString(++iteration));
    metadata.put("phase", params.getType().name());
    var delta = counters.finish(params.getType() == IterationType.MEASUREMENT);
    metadata.put("pageSetShare", IterationCounters.iterationShare(delta));
    System.out.println("PAGELOCK " + IterationCounters.json(metadata, delta));
  }

  @TearDown(Level.Trial)
  public void close() throws Exception {
    if (database != null) {
      database.close();
    }
    if (copy != null) {
      SeedDatabase.deleteTree(copy);
    }
  }
}
