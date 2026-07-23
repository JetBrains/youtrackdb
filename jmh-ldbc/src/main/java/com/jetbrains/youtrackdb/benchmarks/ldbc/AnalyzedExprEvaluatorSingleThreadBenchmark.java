package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded runner for the Bench-1 predicate-evaluation sensitivity instrument
 * ({@link AnalyzedExprEvaluatorBenchmark}).
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
 *   -Djmh.args="AnalyzedExprEvaluatorSingleThreadBenchmark.*"
 * </pre>
 */
@Threads(1)
public class AnalyzedExprEvaluatorSingleThreadBenchmark extends AnalyzedExprEvaluatorBenchmark {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(AnalyzedExprEvaluatorSingleThreadBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
