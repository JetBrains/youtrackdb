/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBatch;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Dead-code pin tests for the {@code core/sql/executor} package.
 *
 * <p>Track 8's Phase A reviews (technical T-table, adversarial Certificate S2) identified four
 * classes with <strong>zero</strong> production callers in the {@code core} module. Verified at
 * Phase A by:
 *
 * <pre>
 *   grep -rn "new InfoExecutionPlan\\(" core/src/main   --&gt; 0 hits
 *   grep -rn "new InfoExecutionStep\\(" core/src/main   --&gt; 0 hits
 *   grep -rn "new TraverseResult\\(" core/src/main      --&gt; 0 hits
 *   grep -rn "new BatchStep\\(" core/src/main           --&gt; only BatchStep.copy()
 *                                                          self-instantiation (private ctor)
 * </pre>
 *
 * <p>None of the four are referenced from {@code META-INF/services}, none are loaded by
 * reflection (no string literals matching their simple names appear in {@code core/src/main}).
 *
 * <p>This suite exists to:
 *
 * <ul>
 *   <li>Exercise each class's reachable surface so JaCoCo reports coverage and the executor
 *       package aggregate is not artificially depressed by never-loaded classes.
 *   <li>Flag the exact deletion targets Track 22 should pursue via {@code // WHEN-FIXED:}
 *       markers under each class's tests.
 * </ul>
 *
 * <p>If any production caller appears later, these tests still pass — but the package's overall
 * coverage will naturally rise via the new caller, removing the rationale for the pin. Search
 * for the {@code // WHEN-FIXED: Track 22 — delete} markers in this file before deleting any
 * class so you don't accidentally remove a reachable one.
 *
 * <p>All tests are standalone (no {@code DbTestBase}); the four classes either ignore their
 * session field, accept {@code @Nullable DatabaseSessionEmbedded} via their parent, or don't
 * touch one at construction time.
 */
public class SqlExecutorDeadCodeTest {

  // ---------------------------------------------------------------------------
  // InfoExecutionPlan — POJO with serialization-shape getters/setters and a
  // toResult() that returns null. WHEN-FIXED: Track 22 — delete InfoExecutionPlan.
  // ---------------------------------------------------------------------------

  @Test
  public void infoExecutionPlanPropertiesRoundTripAndPrettyPrintFallsBackToToString() {
    // Pin every public setter/getter pair plus the prettyPrint/toString aliasing.
    var plan = new InfoExecutionPlan();
    plan.setPrettyPrint("[pp]");
    plan.setType("InfoPlan");
    plan.setJavaType("com.jetbrains.test.InfoPlan");
    plan.setCost(42);
    plan.setStmText("SELECT 1");
    assertEquals("[pp]", plan.getPrettyPrint());
    assertEquals("InfoPlan", plan.getType());
    assertEquals("com.jetbrains.test.InfoPlan", plan.getJavaType());
    assertEquals(Integer.valueOf(42), plan.getCost());
    assertEquals("SELECT 1", plan.getStmText());
    // toString delegates to the prettyPrint field — pinning the alias keeps the contract
    // explicit if a future refactor ever decouples them.
    assertEquals("[pp]", plan.toString());
    // prettyPrint(depth, indent) ignores its arguments and returns the field — not the
    // depth/indent rendering most ExecutionPlans implement. Pin the divergence.
    assertEquals(
        "InfoExecutionPlan.prettyPrint ignores depth/indent and returns the stored field",
        "[pp]",
        plan.prettyPrint(0, 0));
    assertEquals("ditto for non-zero depth/indent", "[pp]", plan.prettyPrint(5, 4));
  }

  @Test
  public void infoExecutionPlanGetStepsReturnsTheAssignedListByReference() {
    // WHEN-FIXED: Track 22 — InfoExecutionPlan.setSteps takes ownership of the caller's list
    // (no defensive copy). Pin reference identity so a future refactor that adds defensive
    // copying surfaces explicitly.
    var plan = new InfoExecutionPlan();
    // Default getSteps must be a non-null empty list — initialized in the field declarator.
    assertNotNull(plan.getSteps());
    assertEquals(0, plan.getSteps().size());
    List<ExecutionStep> assigned = new ArrayList<>();
    plan.setSteps(assigned);
    assertSame(
        "setSteps shares the caller's list reference (no defensive copy)",
        assigned,
        plan.getSteps());
  }

  @Test
  public void infoExecutionPlanToResultIsHardcodedNull() {
    // WHEN-FIXED: Track 22 — InfoExecutionPlan.toResult unconditionally returns null. Any
    // production caller that piped this through ExecutionPlan.toResult() would NPE on the
    // next call. The hardcoded null is strong evidence this method is unused and the class
    // can go.
    var plan = new InfoExecutionPlan();
    assertNull(
        "toResult on a fresh plan returns null — the hardcoded null is the dead-code tell",
        plan.toResult(null));
    plan.setPrettyPrint("populated");
    assertNull("populating fields does not change the hardcoded null", plan.toResult(null));
  }

  // ---------------------------------------------------------------------------
  // InfoExecutionStep — POJO with name/type/description/cost/javaType getters and
  // toResult() that returns a fresh ResultInternal. WHEN-FIXED: Track 22 — delete.
  // ---------------------------------------------------------------------------

  @Test
  public void infoExecutionStepPropertiesRoundTrip() {
    var step = new InfoExecutionStep();
    step.setName("FetchFromClass");
    step.setType("FETCH");
    step.setJavaType("com.jetbrains.test.FetchStep");
    step.setDescription("scan SchemaClass");
    step.setCost(123L);
    assertEquals("FetchFromClass", step.getName());
    assertEquals("FETCH", step.getType());
    assertEquals("com.jetbrains.test.FetchStep", step.getJavaType());
    assertEquals("scan SchemaClass", step.getDescription());
    assertEquals(123L, step.getCost());
  }

  @Test
  public void infoExecutionStepSubStepsAreAnInitiallyEmptyMutableList() {
    // WHEN-FIXED: Track 22 — getSubSteps returns the internal final list directly. Pin
    // both the empty-by-default invariant AND the "reference is mutable, no defensive copy"
    // tell.
    var step = new InfoExecutionStep();
    var subSteps = step.getSubSteps();
    assertNotNull("subSteps must be non-null even when never populated", subSteps);
    assertEquals(0, subSteps.size());
    // Mutating the returned list must affect the step's view — proves no defensive copy.
    var sentinel = new InfoExecutionStep();
    sentinel.setName("sentinel");
    subSteps.add(sentinel);
    assertEquals(1, step.getSubSteps().size());
    assertSame(
        "the returned list is the live internal reference",
        sentinel,
        step.getSubSteps().get(0));
  }

  @Test
  public void infoExecutionStepToResultBuildsAFreshResultInternalEachCall() {
    // WHEN-FIXED: Track 22 — toResult returns `new ResultInternal(session)` and never
    // populates any of the fields the producer set on the InfoExecutionStep. Pin the
    // identity-vs-equality contract: each call returns a distinct empty ResultInternal,
    // confirming the method is structurally a stub.
    var step = new InfoExecutionStep();
    step.setName("populated");
    step.setCost(7L);
    var first = step.toResult(null);
    var second = step.toResult(null);
    assertNotNull(first);
    assertNotNull(second);
    assertNotSame("each call constructs a fresh ResultInternal", first, second);
    // The result is empty — none of the populated fields were copied across.
    assertNull("toResult does not propagate the step's name", first.getProperty("name"));
    assertNull("toResult does not propagate the step's cost", first.getProperty("cost"));
  }

  // ---------------------------------------------------------------------------
  // TraverseResult — extends ResultInternal, special-cases the $depth property.
  // WHEN-FIXED: Track 22 — delete TraverseResult.
  // ---------------------------------------------------------------------------

  @Test
  public void traverseResultDepthIsReadAsNullUntilSet() {
    // WHEN-FIXED: Track 22 — TraverseResult.depth defaults to null (boxed Integer field).
    // Pin: getProperty("$depth") returns null on a fresh instance, even though the
    // production code path that uses TraverseResult would always set it before reading.
    var tr = new TraverseResult(null);
    assertNull("depth is null until setProperty($depth, n) is called", tr.getProperty("$depth"));
  }

  @Test
  public void traverseResultDepthSetterAcceptsNumberAndIsCaseInsensitive() {
    // WHEN-FIXED: Track 22 — the $depth setter narrows Number → int via Number.intValue(),
    // and the property name match is case-insensitive (equalsIgnoreCase). Pin both
    // directions: mixed-case setter hits the same field as a lowercase getter AND vice
    // versa.
    var tr = new TraverseResult(null);
    tr.setProperty("$DEPTH", 3);
    assertEquals(
        "case-insensitive setter: $DEPTH is normalized to $depth",
        Integer.valueOf(3),
        tr.getProperty("$depth"));
    // Long input: also accepted because it's a Number — narrowed via intValue().
    tr.setProperty("$depth", 5L);
    assertEquals(Integer.valueOf(5), tr.getProperty("$depth"));
    // Case-insensitive getter: $Depth / $DEPTH must return the same field as lowercase.
    assertEquals(
        "case-insensitive getter: mixed-case $Depth hits the same field",
        Integer.valueOf(5),
        tr.getProperty("$Depth"));
    assertEquals(
        "case-insensitive getter: uppercase $DEPTH hits the same field",
        Integer.valueOf(5),
        tr.getProperty("$DEPTH"));
  }

  @Test
  public void traverseResultDepthSetterNarrowsNonIntegerNumbersViaIntValue() {
    // WHEN-FIXED: Track 22 — pin Number.intValue() narrowing for non-Integer/Long inputs
    // a future caller might pass (double from SQL arithmetic, BigDecimal from aggregation,
    // Long.MAX_VALUE that truncates to -1). This is a silent-data-loss surface a restored
    // caller would inherit unless Track 22 either deletes the class or validates the input.
    var tr = new TraverseResult(null);
    // Double: fractional part is dropped.
    tr.setProperty("$depth", 3.9);
    assertEquals(
        "Double.intValue truncates the fractional part",
        Integer.valueOf(3),
        tr.getProperty("$depth"));
    // BigDecimal: delegated to Number.intValue() → integral part.
    tr.setProperty("$depth", new BigDecimal("7"));
    assertEquals(
        "BigDecimal.intValue returns the integral part",
        Integer.valueOf(7),
        tr.getProperty("$depth"));
    // Long.MAX_VALUE overflow: intValue returns the low-order int bits (-1 in two's
    // complement). Pin this so the overflow-silent behavior is explicit — a restored caller
    // that assumed clamping rather than truncation would surface a logic bug Track 22
    // should fix (or the class simply deleted).
    tr.setProperty("$depth", Long.MAX_VALUE);
    assertEquals(
        "Long.MAX_VALUE.intValue overflows to -1 (low-order bits)",
        Integer.valueOf(-1),
        tr.getProperty("$depth"));
  }

  @Test
  public void traverseResultDepthSetterIgnoresNonNumberValues() {
    // WHEN-FIXED: Track 22 — the setter silently ignores non-Number values for $depth.
    // No exception, no warning. Pin so a future "throw on bad type" change is explicit.
    var tr = new TraverseResult(null);
    tr.setProperty("$depth", 4);
    assertEquals(Integer.valueOf(4), tr.getProperty("$depth"));
    tr.setProperty("$depth", "not a number");
    assertEquals(
        "non-Number values are silently ignored — depth keeps its prior value",
        Integer.valueOf(4),
        tr.getProperty("$depth"));
    tr.setProperty("$depth", null);
    assertEquals(
        "null values fail the instanceof Number check and are also ignored",
        Integer.valueOf(4),
        tr.getProperty("$depth"));
    // Also pin that the non-Number branch does NOT fall through to the super.setProperty
    // storage — the getter's $depth short-circuit would mask any such fall-through by
    // reading the `depth` field, not the super map. A mutation removing the
    // `instanceof Number num` guard (so every non-$depth-match still delegates) would
    // leave "$depth" as a stored key in the ResultInternal property names.
    assertFalse(
        "non-Number $depth values must not leak into the super.ResultInternal property"
            + " map — $depth stays exclusively in the TraverseResult.depth field",
        tr.getPropertyNames().contains("$depth"));
  }

  @Test
  public void traverseResultNonDepthPropertiesDelegateToResultInternal() {
    // WHEN-FIXED: Track 22 — when the property name is not $depth (case-insensitive), the
    // TraverseResult get/set falls through to super (ResultInternal). Pin delegation: a
    // value set under a non-$depth name must round-trip via the ResultInternal storage,
    // not via the depth field.
    var tr = new TraverseResult(null);
    tr.setProperty("name", "alpha");
    assertEquals("alpha", tr.getProperty("name"));
    // Sanity: the depth field stays null because "name" is not $depth.
    assertNull("setProperty(name, ...) must not collide with the $depth field",
        tr.getProperty("$depth"));
  }

  // ---------------------------------------------------------------------------
  // BatchStep — public ctor BatchStep(SQLBatch, ctx, profilingEnabled) has no
  // production callers; the private ctor is reachable only via copy(). WHEN-FIXED:
  // Track 22 — delete BatchStep entirely. Real BATCH semantics are exercised in
  // SQL script tests, not via this dead step class.
  // ---------------------------------------------------------------------------

  @Test
  public void batchStepPublicConstructorEvaluatesEmptySqlBatchToMinusOne() {
    // SQLBatch with neither `num` nor `inputParam` set falls through SQLBatch.evaluate to
    // `return -1`. The BatchStep public ctor stores that -1 as its batchSize. Pin the path
    // that proves the public ctor is reachable and produces a deterministic state.
    var ctx = new BasicCommandContext();
    var batch = new SQLBatch(-1);
    var step = new BatchStep(batch, ctx, false);
    assertNotNull(step);
    // batchSize is private; pin via prettyPrint which embeds the literal value.
    assertEquals(
        "default-empty SQLBatch yields batchSize=-1, embedded in prettyPrint",
        "+ BATCH COMMIT EVERY -1",
        step.prettyPrint(0, 0));
  }

  @Test
  public void batchStepPrettyPrintRespectsDepthAndIndent() {
    // WHEN-FIXED: Track 22 — pin the prettyPrint indent rendering (uses
    // ExecutionStepInternal.getIndent under the hood) so a whitespace regression in the
    // shared helper is caught here too. The indent is depth * indent spaces followed by the
    // base segment — exact-equals pins both the whitespace count and the segment content so
    // a regression that added/dropped leading characters is caught.
    var ctx = new BasicCommandContext();
    var batch = new SQLBatch(-1);
    var step = new BatchStep(batch, ctx, false);
    var indented = step.prettyPrint(2, 3);
    // depth=2 * indent=3 = 6 leading spaces, followed by "+ BATCH COMMIT EVERY -1".
    assertEquals(
        "depth=2 indent=3 prettyPrint must render as 6 leading spaces + base segment",
        "      + BATCH COMMIT EVERY -1",
        indented);
  }

  @Test
  public void batchStepCopyReturnsAFreshInstanceCarryingTheSameBatchSize() {
    // WHEN-FIXED: Track 22 — copy() invokes the private ctor, copying batchSize verbatim.
    // Pin (a) freshness (not the same instance) and (b) batchSize parity via prettyPrint.
    var ctx = new BasicCommandContext();
    var batch = new SQLBatch(-1);
    var step = new BatchStep(batch, ctx, false);
    var copy = step.copy(ctx);
    assertNotNull(copy);
    assertNotSame("copy must return a fresh BatchStep instance", step, copy);
    assertTrue("copy must itself be a BatchStep", copy instanceof BatchStep);
    assertEquals(
        "copied BatchStep carries the same batchSize, observable via prettyPrint",
        "+ BATCH COMMIT EVERY -1",
        ((BatchStep) copy).prettyPrint(0, 0));
  }
}
