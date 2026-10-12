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
 * # Two steps from the repository root
 * ./mvnw -pl jmh-ldbc -am package -DskipTests
 * # Define LDBC_JAVA_OPTS and LDBC_JAR from README.md in this Bash shell.
 * java "${LDBC_JAVA_OPTS[@]}" -jar "$LDBC_JAR" "LdbcSingleThreadIC.*"
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
