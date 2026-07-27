package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprEvaluator;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Bench 1 — the YTDB-916 predicate-evaluation <b>sensitivity instrument</b> (in-branch A/B).
 *
 * <p>Each measurement evaluates the SAME predicate (selected by {@link
 * AnalyzedExprBenchmarkState#predicateCase}) through both arms:
 * <ul>
 *   <li>{@link #evalIr} → {@code AnalyzedExprEvaluator.evaluate(analyzed, row, ctx)} (the new IR
 *       runtime), and</li>
 *   <li>{@link #evalAst} → {@code SQLWhereClause.matchesFilters(row, ctx)} (the AST path).</li>
 * </ul>
 * Because both arms evaluate the identical predicate over the identical rows, the comparison is
 * apples-to-apples and cannot be masked by pipeline cost — this is the primary regression detector
 * for the evaluator itself.
 *
 * <p><b>Hardening.</b>
 * <ul>
 *   <li>AD1 — every {@code @Benchmark} RETURNS its result, so JMH consumes it and cannot
 *       dead-code-eliminate the evaluation.</li>
 *   <li>AD3 — each invocation reads the NEXT row from the state's rotation array (advancing a
 *       per-thread index with a cheap bit-mask), so the JIT cannot over-specialize on one row.</li>
 *   <li>AD6 — the {@link AnalyzedExprBenchmarkState#ctx} is per-trial and read-only along the eval
 *       path; no {@code Level.Invocation} setup is used.</li>
 *   <li>AD7 — the IR-path-taken guarantee is established in {@code @Setup} (lowering via
 *       {@code getBaseExpression()} asserted non-null), not at runtime.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
// @Fork(10) matches the repo's noisy-benchmark convention (e.g. LdbcISBenchmarkBase): this is the
// primary sub-microsecond regression detector, so more forks tighten the confidence intervals.
@Fork(value = 10, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
})
// The benchmark instance carries the per-thread rotation cursor, so JMH requires @State here.
@State(Scope.Thread)
public abstract class AnalyzedExprEvaluatorBenchmark {

  /**
   * Per-thread rotation cursor (AD3). The enclosing benchmark class is JMH's implicit
   * {@code Scope.Thread} state, so mutating this field per invocation is safe and allocation-free.
   */
  private int idx;

  // PF4 — return-type asymmetry: evalIr returns Object (the IR evaluator's native return type;
  // JMH auto-consumes the boxed reference) while evalAst returns primitive boolean (matchesFilters'
  // native return type; JMH consumes the primitive). This is a small, constant, faithful-to-API
  // offset — each arm returns its production API's real type. It does not bias over-time regression
  // detection because the A/B comparison is directional (IR vs AST evaluated against their own
  // historical baselines) and the constant boxing overhead is fixed across all runs.

  /** IR arm: evaluate the analyzed-expression tree over the next rotation row. */
  @Benchmark
  public Object evalIr(AnalyzedExprBenchmarkState state) {
    idx = (idx + 1) & state.rowMask;
    Result row = state.rows[idx];
    return AnalyzedExprEvaluator.evaluate(state.analyzed, row, state.ctx);
  }

  /** AST arm: evaluate the same predicate via {@code matchesFilters} over the next rotation row. */
  @Benchmark
  public boolean evalAst(AnalyzedExprBenchmarkState state) {
    idx = (idx + 1) & state.rowMask;
    Result row = state.rows[idx];
    return state.where.matchesFilters(row, state.ctx);
  }
}
