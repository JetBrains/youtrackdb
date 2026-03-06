package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Multi-threaded LDBC SNB read query benchmark (~22 min).
 * Runs all 20 interactive read queries (IS1-IS7, IC1-IC13) with 8 concurrent threads.
 *
 * <p>The default thread count (8) can be overridden at runtime via the JMH {@code -t} flag.
 *
 * <p>Usage:
 * <pre>
 * # Via Maven (recommended)
 * ./mvnw -pl jmh-ldbc -am compile exec:exec -Djmh.args="LdbcMultiThread.*"
 *
 * # Override thread count to 16
 * ./mvnw -pl jmh-ldbc -am compile exec:exec -Djmh.args="LdbcMultiThread.* -t 16"
 *
 * # Via uber-jar
 * java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar -t 16 "LdbcMultiThread.*"
 * </pre>
 */
@Threads(8)
public class LdbcMultiThreadBenchmark extends LdbcReadBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcMultiThreadBenchmark.class.getSimpleName())
            .build()
    ).run();
  }
}
