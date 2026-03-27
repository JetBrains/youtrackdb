package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded LDBC SNB Interactive Short (IS) query benchmark.
 * Runs IS1-IS7 with a single thread.
 *
 * <p>Usage:
 * <pre>
 * # Via Maven — single command (recommended)
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcSingleThreadIS.*"
 *
 * # Via Maven — two-step
 * ./mvnw -pl jmh-ldbc -am compile exec:exec -Djmh.args="LdbcSingleThreadIS.*"
 *
 * # Via uber-jar
 * java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar "LdbcSingleThreadIS.*"
 *
 * # A specific query
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args=".*SingleThreadIS.*is1_personProfile"
 * </pre>
 */
@Threads(1)
public class LdbcSingleThreadISBenchmark extends LdbcISBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcSingleThreadISBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
