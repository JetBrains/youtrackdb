package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Multi-threaded LDBC SNB Interactive Short (IS) query benchmark.
 * Runs IS1-IS7 with one thread per available processor ({@link Threads#MAX}).
 *
 * <p>The thread count can be overridden at runtime via the JMH {@code -t} flag.
 *
 * <p>Usage:
 * <pre>
 * # Via Maven — single command (recommended)
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcMultiThreadIS.*"
 *
 * # Override thread count to 16
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcMultiThreadIS.* -t 16"
 *
 * # Via Maven — two-step
 * ./mvnw -pl jmh-ldbc -am compile exec:exec -Djmh.args="LdbcMultiThreadIS.*"
 *
 * # Via uber-jar
 * java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar -t 16 "LdbcMultiThreadIS.*"
 * </pre>
 */
@Threads(Threads.MAX)
public class LdbcMultiThreadISBenchmark extends LdbcISBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcMultiThreadISBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
