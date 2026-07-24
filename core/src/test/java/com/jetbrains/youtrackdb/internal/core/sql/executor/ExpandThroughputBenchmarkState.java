package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprLowerer;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.UnsupportedAnalyzedNodeException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Shared JMH state for the Track-06 {@code ExpandStep} throughput COVERAGE bench (Bench 3).
 *
 * <p>Builds a synthetic STAR GRAPH via {@link BenchDataset#createStarGraph} (one {@code Root} → many
 * {@code Leaf} via {@code HasLeaf}, leaves carry an unindexed {@code age INT}). The two exposed
 * queries use the push-down shape {@code SELECT FROM (SELECT expand(out('HasLeaf')) FROM Root)
 * WHERE <predicate>}, which the planner rewrites so {@code <predicate>} becomes the
 * {@code ExpandStep.pushDownFilter} (verified by {@link ExpandStepIrPathGuardTest}). The two
 * predicates hit the two {@code ExpandStep.filterMap} branches:
 * <ul>
 *   <li>{@link #irSql} — {@code age > 49}, LOWERABLE → the IR evaluator branch; and</li>
 *   <li>{@link #astSql} — {@code age IN [...]}, UNLOWERABLE → the AST-fallback branch.</li>
 * </ul>
 * Both are ~50% selectivity so the two methods do comparable per-leaf work — but, per the plan,
 * their throughputs are NEVER compared to each other.
 *
 * <p>{@link #setup()} asserts the IR predicate lowers and the AST-fallback predicate does NOT lower.
 */
@State(Scope.Benchmark)
public class ExpandThroughputBenchmarkState {

  private final int leafCount = BenchDataset.defaultLeafCount();

  private BenchDataset.Handle handle;

  // THREADING NOTE: this @State(Scope.Benchmark) holds ONE shared, non-thread-safe
  // DatabaseSessionEmbedded. It is safe ONLY because every concrete runner consuming this state
  // is @Threads(1) (see ExpandStepThroughputSingleThreadBenchmark). A future @Threads(>1) /
  // multi-thread variant MUST NOT be added without giving each thread its own session (or
  // synchronizing) — sharing a single session across threads is a data race.
  /** Open session that runs the benchmark queries through the real execution pipeline. */
  public DatabaseSessionEmbedded session;

  /** Lowerable push-down predicate query → IR {@code ExpandStep} branch. */
  public String irSql;

  /** Unlowerable push-down predicate query → AST-fallback {@code ExpandStep} branch. */
  public String astSql;

  @Setup(Level.Trial)
  public void setup() {
    handle = BenchDataset.createStarGraph("expand_throughput_", leafCount);
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

    String from = "(SELECT expand(out('" + BenchDataset.EDGE_CLASS + "')) FROM "
        + BenchDataset.ROOT_CLASS + ")";

    // IR path: lowerable comparison on the expanded leaf age.
    irSql = "SELECT FROM " + from + " WHERE age > 49";

    // AST-fallback path: IN is outside the IR subset. IN [50..99] → ~50% selectivity too.
    StringBuilder in = new StringBuilder();
    for (int v = 50; v <= 99; v++) {
      if (in.length() > 0) {
        in.append(", ");
      }
      in.append(v);
    }
    astSql = "SELECT FROM " + from + " WHERE age IN [" + in + "]";

    // ---- Branch-selection sanity: IR predicate lowers, AST-fallback predicate does NOT. ----
    // lowerBoolean returns a non-null IR or throws UnsupportedAnalyzedNodeException (never null),
    // so the IR check is a try/catch, symmetric with the AST check below.
    SQLWhereClause irWhere = BenchDataset.parseWhere(irSql);
    try {
      AnalyzedExprLowerer.lowerBoolean(irWhere.getBaseExpression());
    } catch (UnsupportedAnalyzedNodeException e) {
      throw new IllegalStateException(
          "IR push-down predicate expected to lower but did not: " + irSql, e);
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
          "AST-fallback push-down predicate unexpectedly lowered: " + astSql);
    }

    // ---- PF1: self-verify push-down actually happened (it is planner-conditional). ----
    // If the predicate regressed to a standalone FilterStep, expandStep_ir would silently measure
    // the wrong step. Mirror ExpandStepIrPathGuardTest's plan inspection and fail loudly otherwise.
    try (ResultSet rs = session.query(irSql)) {
      while (rs.hasNext()) {
        rs.next();
      }
      ExecutionPlan plan = rs.getExecutionPlan();
      if (plan == null) {
        throw new IllegalStateException(
            "no execution plan available to verify ExpandStep push-down for: " + irSql);
      }
      String pretty = plan.prettyPrint(0, 3);
      if (!pretty.contains("EXPAND") || !pretty.contains("push-down filter")
          || pretty.contains("FILTER ITEMS WHERE")) {
        throw new IllegalStateException(
            "expected the IR predicate to be pushed into ExpandStep, but the plan was:\n" + pretty);
      }
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    BenchDataset.close(handle);
  }
}
