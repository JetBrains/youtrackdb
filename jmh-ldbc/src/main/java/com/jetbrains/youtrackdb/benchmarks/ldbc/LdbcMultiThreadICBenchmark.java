package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Multi-threaded LDBC SNB Interactive Complex (IC) query benchmark.
 * Runs IC1-IC13 with one thread per available processor ({@link Threads#MAX}).
 *
 * <p>The thread count can be overridden at runtime via the JMH {@code -t} flag.
 *
 * <p>Usage:
 * <pre>
 * # Via Maven — single command (recommended)
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcMultiThreadIC.*"
 *
 * # Override thread count to 16
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcMultiThreadIC.* -t 16"
 *
 * # Two steps from the repository root
 * ./mvnw -pl jmh-ldbc -am package -DskipTests
 * # Define LDBC_JAVA_OPTS and LDBC_JAR from README.md in this Bash shell.
 * java "${LDBC_JAVA_OPTS[@]}" -jar "$LDBC_JAR" "LdbcMultiThreadIC.*"
 *
 * # Override thread count when using the uber-jar
 * java "${LDBC_JAVA_OPTS[@]}" -jar "$LDBC_JAR" -t 16 "LdbcMultiThreadIC.*"
 * </pre>
 */
@Threads(Threads.MAX)
public class LdbcMultiThreadICBenchmark extends LdbcICBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcMultiThreadICBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
