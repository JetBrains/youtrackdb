package com.jetbrains.youtrackdb.benchmarks.pagelock;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.ThreadParams;

/** One asymmetric group. JMH secondary results separate read and successful commit throughput. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
public class ReaderWriterBenchmark {

  @State(Scope.Thread)
  public static class Writer {
    long value;
  }

  // JMH sorts subgroup methods by name, so -tg is commit,read, not read,commit.
  @Benchmark
  @Group("pages")
  @GroupThreads(1)
  public PageWorkloadComponent.Sample[] commit(PageLockBenchmarkState state, Writer writer,
      ThreadParams threads) throws IOException {
    return state.writers[threads.getSubgroupThreadIndex()]
        .commit(state.writePages, ++writer.value);
  }

  @Benchmark
  @Group("pages")
  @GroupThreads(3)
  public PageWorkloadComponent.Read read(PageLockBenchmarkState state) throws IOException {
    return state.reader.read(state.readPages);
  }
}
