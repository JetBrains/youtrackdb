package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprLowerer;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.UnsupportedAnalyzedNodeException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Dataset-free JMH state for the YTDB-916 predicate-evaluation microbenchmarks (Track 06, Bench 1).
 *
 * <p>Unlike {@code LdbcBenchmarkState} (which requires the SF1 CSV dataset on disk), this state
 * spins up an <b>in-memory</b> embedded YouTrackDB via the public API and loads a synthetic
 * {@code Bench(age INT, name STRING, mid STRING?, nameCi STRING ci-collation)} class. It exposes,
 * for the single {@code @Param}-selected predicate case, the lowered analyzed-expression IR, the
 * AST {@link SQLWhereClause}, a rotating array of pre-built rows, and a per-trial
 * {@link CommandContext}, so a benchmark method can evaluate the same predicate through both the IR
 * evaluator and the AST {@code matchesFilters} arm with zero per-invocation setup cost.
 *
 * <p><b>Fast-path guarantee.</b> The entity-backed rows are captured from a live
 * {@code SELECT FROM Bench} and the session/tx is kept OPEN for the whole trial, so each row stays
 * EntityImpl-backed — i.e. {@code row instanceof ResultInternal ri && ri.asEntityOrNull()
 * instanceof EntityImpl} holds — which is exactly the guard the YTDB-628 in-place fast path in
 * {@code AnalyzedExprEvaluator.evaluateComparison} keys on. (Validated by the AD5 spike.)
 *
 * <p><b>IR-path guarantee (AD7/N1).</b> Each case's IR is lowered in {@link #setup()} via
 * {@code whereClause.getBaseExpression()} — the exact call {@code FilterStep.tryLower} makes — and
 * asserted non-null, so a successful setup proves the IR arm evaluates the intended tree rather
 * than silently timing an AST fallback.
 *
 * <p><b>Context hygiene (AD6).</b> The {@link CommandContext} is built once per trial and is
 * read-only along the evaluation path for every case in this bench (no case uses a method-call
 * coercion that would seed {@code $current}); it must never be rebuilt at {@code Level.Invocation}.
 */
@State(Scope.Benchmark)
public class AnalyzedExprBenchmarkState {

  /** Schema class name for the synthetic dataset (shared via {@link BenchDataset}). */
  public static final String CLASS_NAME = BenchDataset.CLASS_NAME;

  /**
   * Size of the row-rotation array driven by the benchmark hot loop (AD3). Power of two so the
   * per-invocation index advance can use a cheap bit-mask instead of a modulo.
   */
  public static final int ROTATION = 1024;

  /** Bit-mask for {@link #ROTATION}-sized rotation (index &amp; ROTATION_MASK). */
  public static final int ROTATION_MASK = ROTATION - 1;

  /** Bind-parameter value for the PARAM case ({@code age > :p}); ~50% selectivity over age 0..99. */
  private static final int PARAM_THRESHOLD = 49;

  /**
   * The predicate case under measurement. The nine values mirror the Bench-1 table in the Track-06
   * plan. The axis appears on the CLI / reports as {@code predicateCase=<VALUE>}.
   */
  @Param({
      "EQ_FAST", "CMP_FAST", "EQ_SLOW", "AND_OR", "IS_NULL",
      "IS_NOT_NULL", "PARAM", "CI_COLLATION", "ARITH"})
  public String predicateCase;

  // ---- Configurable dataset size (shared with the throughput benches). ----
  private final int rowCount = BenchDataset.defaultRowCount();

  // ---- Database lifecycle (kept OPEN for the whole trial). ----
  private BenchDataset.Handle handle;
  // THREADING NOTE: this @State(Scope.Benchmark) holds ONE shared, non-thread-safe
  // DatabaseSessionEmbedded (reached via the public ctx field). It is safe ONLY because every
  // concrete runner consuming this state is @Threads(1) (see AnalyzedExprEvaluatorSingleThreadBenchmark
  // and its siblings). A future @Threads(>1) / multi-thread variant MUST NOT be added without giving
  // each thread its own session (or synchronizing) — sharing a single session across threads is a
  // data race on the internal session state.
  private DatabaseSessionEmbedded session;

  // =========================================================================
  // Exposed per-case surface (public fields for zero-overhead hot-loop access).
  // Resolved once in setup() for the @Param-selected case.
  // =========================================================================

  /** The lowered analyzed-expression IR for the active case (drives the IR arm). */
  public AnalyzedExpr analyzed;

  /** The AST WHERE clause for the active case (drives the {@code matchesFilters} arm). */
  public SQLWhereClause where;

  /**
   * Rotation array of rows for the active case: EntityImpl-backed rows for the fast-path/slow-eval
   * cases, projection ({@link ResultInternal}) rows for {@code EQ_SLOW}. Length is {@link #ROTATION}.
   */
  public Result[] rows;

  /** Convenience mirror of {@link #ROTATION_MASK} for the hot loop. */
  public int rowMask = ROTATION_MASK;

  /** Per-trial command context (read-only along the eval path; carries the PARAM bind value). */
  public CommandContext ctx;

  // ---- Backing row pools (built once, reused to fill the per-case rotation array). ----
  private Result[] entityRows;
  private Result[] projectionRows;

  @Setup(Level.Trial)
  public void setup() {
    // ---- 1-3. In-memory DB + unindexed Bench schema + N inserted rows (shared builder). ----
    handle = BenchDataset.create("analyzed_bench_", rowCount);
    // Release the DB if any guard/build step below throws: JMH does NOT call @TearDown when @Setup
    // throws, so a failure here would otherwise leak the open session + in-memory DB.
    try {
      buildState();
    } catch (RuntimeException e) {
      BenchDataset.close(handle);
      throw e;
    }
  }

  private void buildState() {
    session = handle.session;

    // ---- 4. Capture EntityImpl-backed rows from a live scan; keep the session/tx OPEN. ----
    entityRows = new Result[ROTATION];
    List<Result> captured = new ArrayList<>(ROTATION);
    try (ResultSet rs = session.query("SELECT FROM " + CLASS_NAME)) {
      while (rs.hasNext() && captured.size() < ROTATION) {
        captured.add(rs.next());
      }
    }
    if (captured.isEmpty()) {
      throw new IllegalStateException("no rows captured from " + CLASS_NAME);
    }
    for (int i = 0; i < ROTATION; i++) {
      Result r = captured.get(i % captured.size());
      // Fail fast if the fast-path precondition is not met (would silently mis-measure Bench 1).
      if (!(r instanceof ResultInternal ri && ri.asEntityOrNull() instanceof EntityImpl)) {
        throw new IllegalStateException(
            "captured row is not EntityImpl-backed; fast path unreachable: " + r.getClass());
      }
      entityRows[i] = r;
    }

    // ---- 5. Build projection (non-entity) rows for the EQ_SLOW case → generic slow path. ----
    projectionRows = new Result[ROTATION];
    for (int i = 0; i < ROTATION; i++) {
      var pr = new ResultInternal(session);
      pr.setProperty("age", i % 100);
      pr.setProperty("name", "name" + i);
      projectionRows[i] = pr;
    }
    // CQ3: EQ_SLOW must genuinely measure the slow path — assert projection rows are NOT
    // entity-backed (symmetric to the fast-path entity assertion above), so it cannot silently
    // drift to the fast path if ResultInternal ever became entity-backed.
    if (projectionRows[0] instanceof ResultInternal pri
        && pri.asEntityOrNull() instanceof EntityImpl) {
      throw new IllegalStateException(
          "EQ_SLOW projection rows are unexpectedly EntityImpl-backed; slow path unreachable");
    }

    // ---- 6. Per-trial context; carries the PARAM bind value (harmless for the other cases). ----
    var basicCtx = new BasicCommandContext(session);
    Map<Object, Object> params = new HashMap<>();
    params.put("p", PARAM_THRESHOLD);
    basicCtx.setInputParameters(params);
    ctx = basicCtx;

    // ---- 7. Resolve the active case: predicate SQL, AST parse, IR lowering, row-pool selection. ----
    String predicateSql;
    boolean useProjection = false;
    switch (predicateCase) {
      case "EQ_FAST" -> predicateSql = "age = 30";
      case "CMP_FAST" -> predicateSql = "age < 30";
      case "EQ_SLOW" -> {
        predicateSql = "age = 30";
        useProjection = true;
      }
      case "AND_OR" -> predicateSql = "age > 20 AND age < 40";
      case "IS_NULL" -> predicateSql = "mid IS NULL";
      case "IS_NOT_NULL" -> predicateSql = "mid IS NOT NULL";
      case "PARAM" -> predicateSql = "age > :p";
      case "CI_COLLATION" -> predicateSql = "nameCi = 'xyz'";
      case "ARITH" -> predicateSql = "age + 1 > 30";
      default -> throw new IllegalArgumentException("unknown predicate case: " + predicateCase);
    }

    where = BenchDataset.parseWhere("SELECT FROM " + CLASS_NAME + " WHERE " + predicateSql);
    // Lower via getBaseExpression() — the SAME call FilterStep.tryLower makes (AD7). All nine
    // Bench-1 cases are inside the lowering subset, so lowering must succeed; lowerBoolean throws
    // UnsupportedAnalyzedNodeException (never returns null) for an out-of-subset predicate.
    try {
      analyzed = AnalyzedExprLowerer.lowerBoolean(where.getBaseExpression());
    } catch (UnsupportedAnalyzedNodeException e) {
      throw new IllegalStateException(
          "Bench-1 predicate expected to lower to IR but did not (N1 guard): " + predicateSql, e);
    }

    rows = useProjection ? projectionRows : entityRows;
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    BenchDataset.close(handle);
  }
}
