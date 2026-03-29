package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded LDBC SNB Interactive Complex (IC) query benchmark.
 * Runs IC1-IC13 with a single thread.
 *
 * <p>Usage:
 * <pre>
 * # Via Maven — single command (recommended)
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcSingleThreadIC.*"
 *
 * # Via Maven — two-step
 * ./mvnw -pl jmh-ldbc -am compile exec:exec -Djmh.args="LdbcSingleThreadIC.*"
 *
 * # Via uber-jar
 * java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar "LdbcSingleThreadIC.*"
 *
 * # A specific query
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args=".*SingleThreadIC.*ic1_transitiveFriends"
 * </pre>
 */
@Threads(1)
public class LdbcSingleThreadICBenchmark extends LdbcICBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcSingleThreadICBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
