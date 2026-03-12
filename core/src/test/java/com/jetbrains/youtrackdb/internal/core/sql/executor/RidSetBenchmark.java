package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH benchmarks for {@link RidSet} measuring add, contains, remove, and iteration performance
 * across different data distributions: dense sequential positions, sparse positions across many
 * collections, and large position values.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@State(Scope.Benchmark)
public class RidSetBenchmark {

  /**
   * Scenario controls the data distribution:
   *
   * <ul>
   *   <li>DENSE_SINGLE - many sequential positions in one collection (typical query dedup)
   *   <li>SPARSE_MULTI - one position per collection, many collections (cross-class query)
   *   <li>LARGE_POSITIONS - positions exceeding Integer.MAX_VALUE (long addressing)
   *   <li>MIXED - realistic mix: 50 collections, 200 positions each, non-sequential
   * </ul>
   */
  @Param({"DENSE_SINGLE", "SPARSE_MULTI", "LARGE_POSITIONS", "MIXED"})
  public Scenario scenario;

  public enum Scenario {
    DENSE_SINGLE, SPARSE_MULTI, LARGE_POSITIONS, MIXED
  }

  private RID[] ridsToAdd;
  private RID[] ridsToLookup;
  private RID[] ridsToRemove;
  private RidSet prePopulatedSet;

  @Setup(Level.Trial)
  public void setup() {
    switch (scenario) {
      case DENSE_SINGLE -> setupDenseSingle();
      case SPARSE_MULTI -> setupSparseMulti();
      case LARGE_POSITIONS -> setupLargePositions();
      case MIXED -> setupMixed();
    }

    // Pre-populate a set for contains/remove/iterate benchmarks
    prePopulatedSet = new RidSet();
    for (var rid : ridsToAdd) {
      prePopulatedSet.add(rid);
    }
  }

  private void setupDenseSingle() {
    // 10,000 sequential positions in collection 5
    int count = 10_000;
    ridsToAdd = new RID[count];
    ridsToLookup = new RID[count];
    ridsToRemove = new RID[count];
    for (int i = 0; i < count; i++) {
      ridsToAdd[i] = new RecordId(5, i);
      ridsToLookup[i] = new RecordId(5, i);
      ridsToRemove[i] = new RecordId(5, i);
    }
  }

  private void setupSparseMulti() {
    // 10,000 collections, one position each
    int count = 10_000;
    ridsToAdd = new RID[count];
    ridsToLookup = new RID[count];
    ridsToRemove = new RID[count];
    for (int i = 0; i < count; i++) {
      ridsToAdd[i] = new RecordId(i, 42);
      ridsToLookup[i] = new RecordId(i, 42);
      ridsToRemove[i] = new RecordId(i, 42);
    }
  }

  private void setupLargePositions() {
    // 10,000 positions above Integer.MAX_VALUE in collection 1
    int count = 10_000;
    long base = (long) Integer.MAX_VALUE + 1000L;
    ridsToAdd = new RID[count];
    ridsToLookup = new RID[count];
    ridsToRemove = new RID[count];
    for (int i = 0; i < count; i++) {
      ridsToAdd[i] = new RecordId(1, base + i);
      ridsToLookup[i] = new RecordId(1, base + i);
      ridsToRemove[i] = new RecordId(1, base + i);
    }
  }

  private void setupMixed() {
    // 50 collections x 200 positions, with gaps (stride of 7)
    int collections = 50;
    int perCollection = 200;
    int count = collections * perCollection;
    ridsToAdd = new RID[count];
    ridsToLookup = new RID[count];
    ridsToRemove = new RID[count];
    int idx = 0;
    for (int c = 0; c < collections; c++) {
      for (int p = 0; p < perCollection; p++) {
        long pos = (long) p * 7;
        ridsToAdd[idx] = new RecordId(c * 3, pos);
        ridsToLookup[idx] = new RecordId(c * 3, pos);
        ridsToRemove[idx] = new RecordId(c * 3, pos);
        idx++;
      }
    }
  }

  /** Measures the cost of adding all RIDs into a fresh RidSet. */
  @Benchmark
  public RidSet addAll() {
    var set = new RidSet();
    for (var rid : ridsToAdd) {
      set.add(rid);
    }
    return set;
  }

  /** Measures the cost of looking up all RIDs in a pre-populated set. */
  @Benchmark
  public void containsAll(Blackhole bh) {
    for (var rid : ridsToLookup) {
      bh.consume(prePopulatedSet.contains(rid));
    }
  }

  /** Measures the cost of iterating over all entries in a pre-populated set. */
  @Benchmark
  public void iterateAll(Blackhole bh) {
    for (var rid : prePopulatedSet) {
      bh.consume(rid);
    }
  }

  /** Measures the cost of removing all RIDs from a pre-populated copy. */
  @Benchmark
  public RidSet removeAll() {
    // Create a copy to remove from (so we don't mutate the shared prePopulatedSet)
    var copy = new RidSet();
    for (var rid : ridsToAdd) {
      copy.add(rid);
    }
    for (var rid : ridsToRemove) {
      copy.remove(rid);
    }
    return copy;
  }

  public static void main(String[] args) throws RunnerException {
    var opt = new OptionsBuilder().include(RidSetBenchmark.class.getSimpleName()).build();
    new Runner(opt).run();
  }
}
