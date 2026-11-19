package com.jetbrains.youtrackdb.benchmarks.pagelock;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Trial-wide failure latch shared by all writers, including warmup and lifecycle operations. */
public final class WriterRunValidity {

  @FunctionalInterface
  public interface Commit {
    void run() throws Exception;
  }

  private final AtomicReference<Throwable> failure = new AtomicReference<>();

  /** Success performs no shared write. The first failure remains even after later success. */
  public void execute(Commit commit) throws Exception {
    try {
      commit.run();
    } catch (Exception | Error error) {
      record(error);
      throw error;
    }
  }

  public void record(Throwable error) {
    failure.compareAndSet(null, Objects.requireNonNull(error));
  }

  /** Call at trial teardown, with resource cleanup in finally. Automation uses JMH -foe true. */
  public String finish(IterationCounters counters) {
    var error = failure.get();
    if (error != null) {
      throw new IllegalStateException("Invalid writer run: an operation failed", error);
    }
    return counters.share(true);
  }
}
