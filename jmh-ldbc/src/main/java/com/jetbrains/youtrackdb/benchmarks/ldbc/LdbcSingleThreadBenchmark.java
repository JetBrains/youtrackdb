package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded LDBC SNB read query benchmark (~22 min).
 * Runs all 20 interactive read queries (IS1-IS7, IC1-IC13) with a single thread.
 *
 * <p>Usage:
 * <pre>
 * # Via Maven (recommended)
 * ./mvnw -pl jmh-ldbc -am compile exec:exec -Djmh.args="LdbcSingleThread.*"
 *
 * # Via uber-jar
 * java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar "LdbcSingleThread.*"
 *
 * # A specific query
 * ./mvnw -pl jmh-ldbc -am compile exec:exec -Djmh.args=".*is1_personProfile"
 * </pre>
 */
@Threads(1)
public class LdbcSingleThreadBenchmark extends LdbcReadBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcSingleThreadBenchmark.class.getSimpleName())
            .build()
    ).run();
  }
}
