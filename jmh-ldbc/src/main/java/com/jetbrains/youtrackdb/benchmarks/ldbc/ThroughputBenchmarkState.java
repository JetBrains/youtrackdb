package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprLowerer;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.UnsupportedAnalyzedNodeException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.HashMap;
import java.util.Map;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Shared JMH state for the Track-06 throughput COVERAGE benches (Bench 2 filter / Bench 3 expand).
 *
 * <p>Backs the same synthetic {@code Bench} dataset as {@link AnalyzedExprBenchmarkState} (via the
 * shared {@link BenchDataset} builder) but WITHOUT the Bench-1 {@code predicateCase} {@code @Param}
 * axis — so the throughput benches are not multiplied across nine cases. The dataset is unindexed,
 * so every query is a full sequential scan and {@code FilterStep} runs on every row.
 *
 * <p>Exposes two predicate queries that hit the two {@code FilterStep.filterMap} branches:
 * <ul>
 *   <li>{@link #irSql} — {@code age > :p}, a LOWERABLE predicate → the IR evaluator branch; and</li>
 *   <li>{@link #astSql} — {@code age IN [...]}, an UNLOWERABLE predicate → the AST-fallback branch
 *       ({@code matchesFilters}).</li>
 * </ul>
 * Both are tuned to ~50% selectivity (age is uniform 0..99) so the two methods do comparable
 * per-row work — though, per the plan, their throughputs are NEVER compared to each other.
 *
 * <p>{@link #setup()} asserts the IR predicate lowers and the AST-fallback predicate does NOT lower,
 * guaranteeing each {@code @Benchmark} method actually exercises its intended branch.
 */
@State(Scope.Benchmark)
public class ThroughputBenchmarkState {

  /** Schema class name for the synthetic dataset (shared via {@link BenchDataset}). */
  public static final String CLASS_NAME = BenchDataset.CLASS_NAME;

  /** Bind value for the IR predicate ({@code age > :p}); ~50% selectivity over age 0..99. */
  private static final int PARAM_THRESHOLD = 49;

  private final int rowCount = BenchDataset.defaultRowCount();

  // ---- Database lifecycle (kept OPEN for the whole trial). ----
  private BenchDataset.Handle handle;

  // THREADING NOTE: this @State(Scope.Benchmark) holds ONE shared, non-thread-safe
  // DatabaseSessionEmbedded. It is safe ONLY because every concrete runner consuming this state
  // is @Threads(1) (see FilterStepThroughputSingleThreadBenchmark). A future @Threads(>1) /
  // multi-thread variant MUST NOT be added without giving each thread its own session (or
  // synchronizing) — sharing a single session across threads is a data race.
  /** Open session that runs the benchmark queries through the real execution pipeline. */
  public DatabaseSessionEmbedded session;

  /** Lowerable predicate query → IR {@code FilterStep} branch. */
  public String irSql;

  /** Named parameters for {@link #irSql} ({@code {p: 49}}). */
  public Map<String, Object> irParams;

  /** Unlowerable predicate query → AST-fallback {@code FilterStep} branch. */
  public String astSql;

  @Setup(Level.Trial)
  public void setup() {
    // In-memory DB + unindexed Bench schema + N inserted rows (shared builder).
    handle = BenchDataset.create("filter_throughput_", rowCount);
    // Release the DB if any guard below throws (JMH does not call @TearDown when @Setup throws).
    try {
      configure();
    } catch (RuntimeException e) {
      BenchDataset.close(handle);
      throw e;
    }
  }

  private void configure() {
    session = handle.session;

    // IR path: trivial projection + a lowerable range predicate on the unindexed column.
    irSql = "SELECT age FROM " + CLASS_NAME + " WHERE age > :p";
    irParams = new HashMap<>();
    irParams.put("p", PARAM_THRESHOLD);

    // AST-fallback path: IN is outside the IR subset. Build IN [50..99] → ~50% selectivity too, so
    // the two methods do comparable per-row work (they are never compared to each other).
    StringBuilder in = new StringBuilder();
    for (int v = 50; v <= 99; v++) {
      if (in.length() > 0) {
        in.append(", ");
      }
      in.append(v);
    }
    astSql = "SELECT age FROM " + CLASS_NAME + " WHERE age IN [" + in + "]";

    // ---- Branch-selection sanity: IR predicate lowers, AST-fallback predicate does NOT. ----
    // lowerBoolean returns a non-null IR or throws UnsupportedAnalyzedNodeException (never null),
    // so the IR check is a try/catch, symmetric with the AST check below.
    SQLWhereClause irWhere = BenchDataset.parseWhere(irSql);
    try {
      AnalyzedExprLowerer.lowerBoolean(irWhere.getBaseExpression());
    } catch (UnsupportedAnalyzedNodeException e) {
      throw new IllegalStateException(
          "IR predicate expected to lower but did not (would not exercise the IR branch): " + irSql,
          e);
    }
    SQLWhereClause astWhere = BenchDataset.parseWhere(astSql);
    boolean astLowered;
    try {
      AnalyzedExprLowerer.lowerBoolean(astWhere.getBaseExpression());
      astLowered = true;
    } catch (UnsupportedAnalyzedNodeException e) {
      astLowered = false; // expected: IN is outside the lowering subset → AST fallback
    }
    if (astLowered) {
      throw new IllegalStateException(
          "AST-fallback predicate unexpectedly lowered (would not exercise the AST branch): "
              + astSql);
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    BenchDataset.close(handle);
  }
}
