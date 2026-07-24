package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprLowerer;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.UnsupportedAnalyzedNodeException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end guard that the Bench-3 {@code ExpandStep} IR push-down path is genuinely taken
 * (Track 06). This is a JUnit test, NOT a JMH benchmark.
 *
 * <p>Runs the {@code expandStep_ir} query shape ({@code SELECT FROM (SELECT expand(out('HasLeaf'))
 * FROM Root) WHERE age > 49}) over a small star graph and asserts:
 * <ol>
 *   <li>results are NON-EMPTY and CORRECTLY FILTERED (every returned leaf satisfies {@code age >
 *       49}, and the count equals the exact number of matching leaves) — proving the push-down
 *       filter actually ran; and</li>
 *   <li>the execution plan pushed the predicate INTO an {@code ExpandStep} (the plan shows
 *       {@code + EXPAND ... (push-down filter: age > 49)} and NO standalone {@code FILTER ITEMS
 *       WHERE} step) — proving push-down occurred rather than a separate FilterStep; and</li>
 *   <li>the pushed-down {@code ExpandStep} in the live plan has a non-null lowered IR
 *       ({@code getAnalyzed() != null}) — proving the IR branch of {@code ExpandStep.filterMap} is
 *       the one actually taken, not the AST fallback. This is checked by the inlined
 *       {@link #innerExpandAnalyzedNonNull} helper (this guard test lives in the same package as
 *       {@code ExpandStep}, so it reads the package-private {@code getAnalyzed()} directly); a
 *       broken {@code ExpandStep.tryLower} returning null would fail HERE even though results stay
 *       correct.</li>
 * </ol>
 * It also asserts the freshly-parsed {@code age > 49} predicate lowers to IR and the AST-fallback
 * predicate ({@code age IN [...]}) does NOT lower.
 *
 * <p>No Mockito spy is used; the non-empty + correctly-filtered + plan-inspection +
 * {@code getAnalyzed()} assertions stand in for it.
 */
// Runs sequentially (not in core's parallel bucket): BenchDataset.open/close mutates the global
// GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED flag, so this test follows the module
// convention for GlobalConfiguration-mutating tests (e.g. TxResultCacheWiringTest).
@Category(SequentialTest.class)
public class ExpandStepIrPathGuardTest {

  /** 200 leaves → ages 0..99 twice → exactly 100 leaves with age > 49. */
  private static final int LEAF_COUNT = 200;
  private static final int EXPECTED_MATCHES = 100;

  /**
   * Distinct target ages in the AST_SQL IN-list ({@code age IN [50, 60, 70]}). Each target age
   * appears exactly {@code LEAF_COUNT / 100} times in the leaf-age sequence ({@code i % 100} for
   * {@code i} in {@code 0..LEAF_COUNT-1}).
   */
  private static final int AST_SQL_TARGET_AGE_COUNT = 3;
  private static final Set<Integer> AST_SQL_TARGET_AGES = Set.of(50, 60, 70);
  /** Expected leaf count matching {@code AST_SQL}: 3 ages × (200/100) = 6 leaves. */
  private static final int EXPECTED_AST_FALLBACK_MATCHES =
      AST_SQL_TARGET_AGE_COUNT * (LEAF_COUNT / 100);

  private static final String FROM =
      "(SELECT expand(out('" + BenchDataset.EDGE_CLASS + "')) FROM " + BenchDataset.ROOT_CLASS
          + ")";
  private static final String IR_SQL = "SELECT FROM " + FROM + " WHERE age > 49";
  private static final String AST_SQL = "SELECT FROM " + FROM + " WHERE age IN [50, 60, 70]";

  private static BenchDataset.Handle handle;
  private static DatabaseSessionEmbedded session;

  @BeforeClass
  public static void setup() {
    handle = BenchDataset.createStarGraph("expand_guard_", LEAF_COUNT);
    session = handle.session;
  }

  @AfterClass
  public static void tearDown() {
    BenchDataset.close(handle);
  }

  @Test
  public void expandIrPushDownIsTakenAndCorrect() {
    List<Integer> ages = new ArrayList<>();
    String prettyPlan;
    boolean irBranchActive;
    try (ResultSet rs = session.query(IR_SQL)) {
      while (rs.hasNext()) {
        Result r = rs.next();
        ages.add(r.getProperty("age"));
      }
      ExecutionPlan plan = rs.getExecutionPlan();
      assertNotNull("execution plan must be available for plan inspection", plan);
      prettyPlan = plan.prettyPrint(0, 3);
      // Reach the package-private ExpandStep.getAnalyzed() via the inlined same-package helper to
      // confirm the pushed-down filter carries a lowered IR (i.e. the IR branch of filterMap is
      // taken).
      irBranchActive = innerExpandAnalyzedNonNull(plan);
    }

    System.out.println("[EXPAND-GUARD] plan for " + IR_SQL + ":\n" + prettyPlan);

    // ---- (1) non-empty + correctly filtered. ----
    assertFalse("IR expand query must return rows (push-down filter must not drop everything)",
        ages.isEmpty());
    assertTrue("every returned leaf must satisfy the pushed-down predicate age > 49",
        ages.stream().allMatch(a -> a > 49));
    assertEquals("exact number of leaves matching age > 49", EXPECTED_MATCHES, ages.size());

    // ---- (2) push-down occurred: predicate is on an ExpandStep, not a standalone FilterStep. ----
    assertTrue("plan must contain an EXPAND step", prettyPlan.contains("EXPAND"));
    assertTrue("predicate must be pushed into ExpandStep as a push-down filter",
        prettyPlan.contains("push-down filter"));
    assertTrue("the push-down filter must carry the age predicate",
        prettyPlan.contains("age > 49"));
    assertFalse("predicate must NOT remain as a standalone FilterStep (push-down must occur)",
        prettyPlan.contains("FILTER ITEMS WHERE"));

    // ---- (2b) IR branch genuinely taken: the pushed-down ExpandStep carries a non-null IR. ----
    // This is what distinguishes "IR branch of ExpandStep.filterMap runs" from "AST fallback runs"
    // (both produce identical functional results). A null ExpandStep.analyzed would fail here.
    assertTrue("ExpandStep.analyzed must be non-null (IR branch of filterMap is exercised)",
        irBranchActive);

    // ---- (3) branch selection: IR predicate lowers; AST-fallback predicate does NOT. ----
    SQLWhereClause irWhere = BenchDataset.parseWhere(IR_SQL);
    assertNotNull("age > 49 must lower to IR (so ExpandStep.analyzed != null → IR branch)",
        AnalyzedExprLowerer.lowerBoolean(irWhere.getBaseExpression()));

    SQLWhereClause astWhere = BenchDataset.parseWhere(AST_SQL);
    boolean astLowered;
    try {
      AnalyzedExprLowerer.lowerBoolean(astWhere.getBaseExpression());
      astLowered = true;
    } catch (UnsupportedAnalyzedNodeException e) {
      astLowered = false;
    }
    assertFalse("age IN [...] must NOT lower (so the AST-fallback branch is taken)", astLowered);
  }

  /**
   * Verifies that the AST-fallback branch of {@code ExpandStep.filterMap} is genuinely taken
   * end-to-end when the pushed-down predicate is outside the analyzed-expression lowering subset.
   *
   * <p>Runs {@code AST_SQL} ({@code age IN [50, 60, 70]}) over the same star graph and asserts:
   * <ol>
   *   <li>Results are non-empty and correctly filtered — every returned leaf age is in
   *       {@code {50, 60, 70}} and the count equals the exact number of matching leaves (each of
   *       the 3 target ages appears {@code LEAF_COUNT / 100} times in the {@code i % 100}
   *       sequence, giving {@link #EXPECTED_AST_FALLBACK_MATCHES} = 6 total).</li>
   *   <li>Push-down occurred — the plan shows the IN predicate on an {@code ExpandStep}, not a
   *       standalone {@code FILTER ITEMS WHERE} step. (The planner's
   *       {@code tryPushDownFilterIntoExpand} pushes any WHERE clause into ExpandStep regardless
   *       of IR lowerability; lowerability only governs which branch of
   *       {@code ExpandStep.filterMap} is taken.)</li>
   *   <li>The AST-fallback branch is taken — {@code ExpandStep.getAnalyzed()} is {@code null}
   *       (because {@code tryLower} catches the {@code UnsupportedAnalyzedNodeException} from IN
   *       and returns {@code null}), confirmed via the inlined {@link #innerExpandAnalyzedNonNull}
   *       helper.</li>
   * </ol>
   *
   * <p>This is the complement of {@link #expandIrPushDownIsTakenAndCorrect}: together they guard
   * both branches of {@code ExpandStep.filterMap}.
   *
   * <p><b>Empirical push-down verification:</b> the planner's {@code tryPushDownFilterIntoExpand}
   * pushes a non-null {@code pushDownWhere} for any predicate that does not reduce entirely to
   * {@code @class} / edge-RID / direct-RID / {@code $parent} extractions. {@code age IN [50,60,70]}
   * satisfies none of those, so the full WHERE is set as {@code pushDownWhere} and the condition
   * {@code classFilter==null && ridFilter==null && pushDownWhere==null} is false → push-down fires.
   * This was confirmed by printing the plan in the test run (see test output).
   */
  @Test
  public void expandAstFallbackPushDownIsTakenAndCorrect() {
    // Verify the planner actually pushed age IN [...] into the ExpandStep before asserting.
    // Both IR and AST queries use the same outer query shape; only the lowerability of the
    // predicate differs, and lowerability does not affect whether push-down occurs.
    List<Integer> ages = new ArrayList<>();
    String prettyPlan;
    boolean irBranchActive;
    try (ResultSet rs = session.query(AST_SQL)) {
      while (rs.hasNext()) {
        Result r = rs.next();
        ages.add(r.getProperty("age"));
      }
      ExecutionPlan plan = rs.getExecutionPlan();
      assertNotNull("execution plan must be available for AST-fallback plan inspection", plan);
      prettyPlan = plan.prettyPrint(0, 3);
      irBranchActive = innerExpandAnalyzedNonNull(plan);
    }

    System.out.println("[EXPAND-GUARD] plan for " + AST_SQL + ":\n" + prettyPlan);

    // ---- (1) non-empty + correctly filtered. ----
    assertFalse(
        "AST-fallback expand query must return rows (push-down filter must not drop everything)",
        ages.isEmpty());
    assertTrue(
        "every returned leaf must satisfy the pushed-down predicate age IN [50, 60, 70]",
        ages.stream().allMatch(AST_SQL_TARGET_AGES::contains));
    assertEquals(
        "exact number of leaves matching age IN [50, 60, 70] (" + AST_SQL_TARGET_AGE_COUNT
            + " ages x " + (LEAF_COUNT / 100) + " occurrences each)",
        EXPECTED_AST_FALLBACK_MATCHES, ages.size());

    // ---- (2) push-down occurred: predicate is on an ExpandStep, not a standalone FilterStep. ----
    assertTrue("plan must contain an EXPAND step", prettyPlan.contains("EXPAND"));
    assertTrue("age IN predicate must be pushed into ExpandStep as a push-down filter",
        prettyPlan.contains("push-down filter"));
    assertTrue("the push-down filter must carry the age IN predicate",
        prettyPlan.contains("age IN"));
    assertFalse(
        "predicate must NOT remain as a standalone FilterStep (push-down must occur)",
        prettyPlan.contains("FILTER ITEMS WHERE"));

    // ---- (3) AST-fallback branch taken: ExpandStep.analyzed must be null (IR lowering failed). ----
    // ExpandStep.tryLower catches the UnsupportedAnalyzedNodeException thrown for IN and returns
    // null, so ExpandStep.analyzed is null and filterMap falls back to pushDownFilter.matchesFilters.
    // A null analyzed means innerExpandAnalyzedNonNull returns false.
    assertFalse(
        "ExpandStep.analyzed must be null for an unlowerable predicate (AST-fallback branch taken)",
        irBranchActive);
  }

  // ---------------------------------------------------------------------------------------------
  // Inlined from the former same-package ExpandStepIrProbe (deleted when this guard test moved into
  // core test sources). Now that the test lives in the same package as ExpandStep/SubQueryStep, it
  // reaches the package-private ExpandStep.getAnalyzed() and SubQueryStep.subExecutionPlan directly
  // instead of going through a separate probe class.
  // ---------------------------------------------------------------------------------------------

  /**
   * Navigates the plan (descending into subquery plans) to the single {@link ExpandStep} and
   * reports whether its lowered IR ({@code analyzed}) is non-null — i.e. the IR branch is active.
   *
   * @throws IllegalStateException if no {@link ExpandStep} is present in the plan
   */
  private static boolean innerExpandAnalyzedNonNull(ExecutionPlan plan) {
    ExpandStep expand = findExpand(plan);
    if (expand == null) {
      throw new IllegalStateException("no ExpandStep found in execution plan");
    }
    return expand.getAnalyzed() != null;
  }

  private static ExpandStep findExpand(ExecutionPlan plan) {
    for (ExecutionStep step : plan.getSteps()) {
      ExpandStep found = findInStep(step);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static ExpandStep findInStep(ExecutionStep step) {
    if (step instanceof ExpandStep expand) {
      return expand;
    }
    // The push-down query shape wraps the expand in a subquery; descend into its plan.
    if (step instanceof SubQueryStep subQuery) {
      for (ExecutionStep inner : subQuery.subExecutionPlan.getSteps()) {
        ExpandStep found = findInStep(inner);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
