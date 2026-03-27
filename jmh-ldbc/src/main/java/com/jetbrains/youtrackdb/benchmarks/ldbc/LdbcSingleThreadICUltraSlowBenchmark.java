package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded LDBC SNB Interactive Complex ultra-slow query benchmark.
 * Runs IC3, IC5, and IC10 with a single thread and very long measurement windows.
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcSingleThreadICUltraSlow.*"
 * </pre>
 */
@Threads(1)
public class LdbcSingleThreadICUltraSlowBenchmark extends LdbcICUltraSlowBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcSingleThreadICUltraSlowBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
