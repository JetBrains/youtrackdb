package com.jetbrains.youtrackdb.benchmarks.pagelock;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.ThreadParams;

/** Successful durable commits only. Automation uses -foe true to reject benchmark errors. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
public class WriterBenchmark {

  @State(Scope.Thread)
  public static class Writer {

    WriterRunValidity.Commit commit;

    @Setup(Level.Trial)
    public void setup(WriterBenchmarkState state, ThreadParams params) {
      try {
        commit = state.commits[params.getThreadIndex()];
      } catch (RuntimeException | Error failure) {
        state.validity.record(failure);
        throw failure;
      }
    }
  }

  @Benchmark
  @Threads(1)
  public void singleRecords1(WriterBenchmarkState state, Writer writer) throws Exception {
    state.validity.execute(writer.commit);
  }

  @Benchmark
  @Threads(4)
  public void singleRecords4(WriterBenchmarkState state, Writer writer) throws Exception {
    state.validity.execute(writer.commit);
  }

  @Benchmark
  @Threads(1)
  public void nearLimit1(WriterBenchmarkState state, Writer writer) throws Exception {
    state.validity.execute(writer.commit);
  }

  @Benchmark
  @Threads(4)
  public void nearLimit4(WriterBenchmarkState state, Writer writer) throws Exception {
    state.validity.execute(writer.commit);
  }

  @Benchmark
  @Threads(1)
  public void smallCache1(WriterBenchmarkState state, Writer writer) throws Exception {
    state.validity.execute(writer.commit);
  }

  @Benchmark
  @Threads(4)
  public void smallCache4(WriterBenchmarkState state, Writer writer) throws Exception {
    state.validity.execute(writer.commit);
  }
}
