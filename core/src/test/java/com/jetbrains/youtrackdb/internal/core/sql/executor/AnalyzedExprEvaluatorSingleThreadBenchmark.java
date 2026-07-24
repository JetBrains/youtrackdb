package com.jetbrains.youtrackdb.internal.core.sql.executor;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded runner for the Bench-1 predicate-evaluation sensitivity instrument
 * ({@link AnalyzedExprEvaluatorBenchmark}).
 *
 * <p>Now lives in {@code core} test sources; run it via this class's {@link #main(String[])} (JMH
 * {@code Runner}/{@code OptionsBuilder}) — e.g. from an IDE or a test-classpath launcher — rather
 * than through the old {@code jmh-ldbc} {@code -P bench} uber-jar.
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
