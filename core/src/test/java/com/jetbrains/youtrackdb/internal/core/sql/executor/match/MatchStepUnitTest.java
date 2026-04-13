package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.QueryPlanningInfo;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFieldMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMultiMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Unit tests for MATCH execution step classes that exercise copy(), prettyPrint(),
 * and other methods not covered by integration tests.
 *
 * <p>Runs sequentially because several tests mutate
 * {@link GlobalConfiguration#QUERY_MATCH_HASH_JOIN_THRESHOLD}, a JVM-wide
 * singleton that would race with other MATCH tests reading the same entry in
 * the parallel-classes surefire pool.
 */
@Category(SequentialTest.class)
public class MatchStepUnitTest extends DbTestBase {

  // -- EdgeTraversal tests --

  /** Verifies EdgeTraversal constructor stores edge and direction correctly. */
  @Test
  public void testEdgeTraversalConstructor() {
    var edge = createTestPatternEdge();
    var traversal = new EdgeTraversal(edge, true);

    assertEquals(edge, traversal.edge);
    assertTrue(traversal.out);
  }

  /** Verifies EdgeTraversal.copy() with null filter and null RID preserves leftClass. */
  @Test
  public void testEdgeTraversalCopyNullConstraints() {
    var edge = createTestPatternEdge();
    var traversal = new EdgeTraversal(edge, true);
    traversal.setLeftClass("Person");

    var copy = traversal.copy();
    assertNotSame(traversal, copy);
    assertEquals("Person", copy.getLeftClass());
    assertNull(copy.getLeftFilter());
    assertNull(copy.getLeftRid());
  }

  /** Verifies EdgeTraversal.copy() preserves edge reference and direction. */
  @Test
  public void testEdgeTraversalCopyPreservesEdge() {
    var edge = createTestPatternEdge();
    var traversal = new EdgeTraversal(edge, false);

    var copy = traversal.copy();
    assertNotSame(traversal, copy);
    assertFalse(copy.out);
    assertEquals(edge, copy.edge);
  }

  /** Verifies EdgeTraversal.toString() delegates to the underlying PatternEdge. */
  @Test
  public void testEdgeTraversalToString() {
    var edge = createTestPatternEdgeWithOutPath();
    var traversal = new EdgeTraversal(edge, true);
    assertNotNull(traversal.toString());
    assertEquals(edge.toString(), traversal.toString());
  }

  /** Verifies copy() deep-copies non-null leftFilter, leftRid, and leftClass. */
  @Test
  public void testEdgeTraversalCopyWithNonNullConstraints() {
    var edge = createTestPatternEdge();
    var traversal = new EdgeTraversal(edge, true);
    traversal.setLeftClass("Person");
    var filter = new SQLWhereClause(-1);
    filter.setBaseExpression(SQLBooleanExpression.TRUE);
    traversal.setLeftFilter(filter);
    traversal.setLeftRid(new SQLRid(-1));

    var copy = traversal.copy();
    assertNotSame(traversal, copy);
    assertEquals("Person", copy.getLeftClass());
    assertNotNull(copy.getLeftFilter());
    assertNotSame(traversal.getLeftFilter(), copy.getLeftFilter());
    assertNotNull(copy.getLeftRid());
    assertNotSame(traversal.getLeftRid(), copy.getLeftRid());
  }

  /** Verifies getter/setter round-trips for leftClass, leftRid, leftFilter. */
  @Test
  public void testEdgeTraversalGettersSetters() {
    var edge = createTestPatternEdge();
    var traversal = new EdgeTraversal(edge, true);

    assertNull(traversal.getLeftClass());
    assertNull(traversal.getLeftRid());
    assertNull(traversal.getLeftFilter());

    traversal.setLeftClass("Employee");
    assertEquals("Employee", traversal.getLeftClass());

    var rid = new SQLRid(-1);
    traversal.setLeftRid(rid);
    assertEquals(rid, traversal.getLeftRid());

    var filter = new SQLWhereClause(-1);
    traversal.setLeftFilter(filter);
    assertEquals(filter, traversal.getLeftFilter());
  }

  // -- QueryPlanningInfo tests --

  /** Verifies QueryPlanningInfo.copy() copies boolean, reference, and null fields. */
  @Test
  public void testQueryPlanningInfoCopy() {
    var info = new QueryPlanningInfo();
    info.distinct = true;
    // Set a non-null reference field to verify it is copied (not just null→null)
    info.skip = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip(-1);

    var copy = info.copy();
    assertNotSame(info, copy);
    assertTrue(copy.distinct);
    assertNotNull(copy.skip);
    assertNull(copy.projection);
    assertNull(copy.groupBy);
  }

  // -- MatchStep tests --

  /** Verifies MatchStep.copy() creates a distinct instance with equivalent state. */
  @Test
  public void testMatchStepCopy() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    var step = new MatchStep(ctx, edge, true);

    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof MatchStep);
    var copy = (MatchStep) rawCopy;
    // Verify the copy preserves the profiling flag and produces equivalent output
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    assertEquals(step.prettyPrint(0, 2), copy.prettyPrint(0, 2));
  }

  /** Verifies MatchStep.prettyPrint() for forward direction contains "---->" arrow. */
  @Test
  public void testMatchStepPrettyPrintForward() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    var step = new MatchStep(ctx, edge, false);

    var result = step.prettyPrint(0, 2);
    assertNotNull(result);
    assertTrue(result.contains("MATCH"));
    assertTrue(result.contains("---->"));
  }

  /** Verifies MatchStep.prettyPrint() for reverse direction contains "<----" arrow. */
  @Test
  public void testMatchStepPrettyPrintReverse() {
    var ctx = createCommandContext();
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var item = new SQLMatchPathItem(-1);
    nodeA.addEdge(item, nodeB);
    var patternEdge = nodeA.out.iterator().next();
    var edge = new EdgeTraversal(patternEdge, false); // reverse

    var step = new MatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);
    assertNotNull(result);
    assertTrue(result.contains("<----"));
  }

  /**
   * Verifies MatchStep.prettyPrint() shows no intersection info when descriptor is null.
   */
  @Test
  public void testMatchStepPrettyPrintNoIntersection() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    assertNull("Precondition: no descriptor", edge.getIntersectionDescriptor());

    var step = new MatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);
    assertFalse("Should not contain intersection", result.contains("intersection"));
  }

  /**
   * Verifies MatchStep.prettyPrint() shows "(intersection: direct-rid)" when
   * the edge has a DirectRid descriptor.
   */
  @Test
  public void testMatchStepPrettyPrintDirectRidIntersection() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.DirectRid(new SQLExpression(-1)));

    var step = new MatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);
    assertTrue("Should contain direct-rid intersection",
        result.contains("(intersection: direct-rid)"));
  }

  /**
   * Verifies MatchStep.prettyPrint() shows "(intersection: out('Knows'))" when
   * the edge has an EdgeRidLookup descriptor.
   */
  @Test
  public void testMatchStepPrettyPrintEdgeRidLookupIntersection() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    var step = new MatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);
    assertTrue("Should contain EdgeRidLookup intersection",
        result.contains("(intersection: out('Knows'))"));
  }

  /**
   * Verifies MatchStep.prettyPrint() shows "(intersection: index ...)" when
   * the edge has an IndexLookup descriptor.
   */
  @Test
  public void testMatchStepPrettyPrintIndexLookupIntersection() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();

    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(index.getName()).thenReturn("Post.creationDate");
    var indexDesc = mock(
        com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor.class);
    when(indexDesc.getIndex()).thenReturn(index);
    when(indexDesc.estimateSelectivity(ctx)).thenReturn(0.42);

    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.IndexLookup(indexDesc));

    var step = new MatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);
    assertTrue("Should contain IndexLookup intersection with selectivity",
        result.contains("(intersection: index Post.creationDate selectivity=0.4200)"));
  }

  /**
   * Verifies MatchStep.prettyPrint() shows both descriptors for Composite.
   */
  @Test
  public void testMatchStepPrettyPrintCompositeIntersection() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();

    var edgeDesc = new RidFilterDescriptor.EdgeRidLookup(
        "Knows", "out", new SQLExpression(-1), false);
    var directDesc = new RidFilterDescriptor.DirectRid(new SQLExpression(-1));
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.Composite(java.util.List.of(edgeDesc, directDesc)));

    var step = new MatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);
    assertTrue("Should contain EdgeRidLookup in composite",
        result.contains("(intersection: out('Knows'))"));
    assertTrue("Should contain direct-rid in composite",
        result.contains("(intersection: direct-rid)"));
  }

  /**
   * Verifies OptionalMatchStep.prettyPrint() also shows intersection descriptor.
   */
  @Test
  public void testOptionalMatchStepPrettyPrintEdgeRidLookupIntersection() {
    var ctx = createCommandContext();
    var patternEdge = createTestPatternEdge();
    var edge = new EdgeTraversal(patternEdge, true);
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup("HasCreator", "in",
            new SQLExpression(-1), false));

    var step = new OptionalMatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);
    assertTrue("Should contain OPTIONAL MATCH", result.contains("OPTIONAL MATCH"));
    assertTrue("Should contain EdgeRidLookup intersection",
        result.contains("(intersection: in('HasCreator'))"));
  }

  // -- Pre-filter PROFILE/EXPLAIN output tests --

  /**
   * PROFILE output when pre-filter was applied: shows applied/skipped counts,
   * ridSetSize, buildTime, and filterRate. Only shown when profilingEnabled=true.
   */
  @Test
  public void testMatchStepPrettyPrintProfileApplied() throws Exception {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    // Set non-default counter values via reflection (recording happens at
    // runtime; here we test the formatting output)
    setCounterField(edge, "preFilterAppliedCount", 5);
    setCounterField(edge, "preFilterSkippedCount", 2);
    setCounterField(edge, "preFilterTotalProbed", 10000L);
    setCounterField(edge, "preFilterTotalFiltered", 9500L);
    setCounterField(edge, "preFilterBuildTimeNanos", 1_500_000L); // 1.5ms
    setCounterField(edge, "preFilterRidSetSize", 42);

    var step = new MatchStep(ctx, edge, true);
    var result = step.prettyPrint(0, 2);

    assertTrue("Should contain applied count",
        result.contains("pre-filter: applied=5"));
    assertTrue("Should contain skipped count",
        result.contains("skipped=2"));
    assertTrue("Should contain ridSetSize",
        result.contains("ridSetSize=42"));
    assertTrue("Should contain buildTime",
        result.contains("buildTime=1.500ms"));
    assertTrue("Should contain filterRate",
        result.contains("filterRate=95.0%"));
  }

  /**
   * PROFILE output when pre-filter never activated: shows "NEVER APPLIED"
   * with diagnostic info including the skip reason and relevant threshold.
   */
  @Test
  public void testMatchStepPrettyPrintProfileNeverApplied() throws Exception {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    setCounterField(edge, "lastSkipReason",
        PreFilterSkipReason.CAP_EXCEEDED);

    var step = new MatchStep(ctx, edge, true);
    var result = step.prettyPrint(0, 2);

    assertTrue("Should contain NEVER APPLIED",
        result.contains("pre-filter: NEVER APPLIED"));
    assertTrue("Should contain CAP_EXCEEDED reason",
        result.contains("reason: CAP_EXCEEDED"));
    assertTrue("Should contain cap value",
        result.contains("cap=" + TraversalPreFilterHelper.maxRidSetSize()));
  }

  /**
   * EXPLAIN (profilingEnabled=false) does NOT show pre-filter stats.
   * This prevents false "NEVER APPLIED" when no query has executed yet.
   */
  @Test
  public void testMatchStepPrettyPrintExplainNoPreFilterStats() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    var step = new MatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);

    assertFalse("EXPLAIN should not contain pre-filter stats",
        result.contains("pre-filter:"));
  }

  /**
   * OptionalMatchStep PROFILE output also shows pre-filter stats (T1).
   */
  @Test
  public void testOptionalMatchStepPrettyPrintProfile() throws Exception {
    var ctx = createCommandContext();
    var patternEdge = createTestPatternEdge();
    var edge = new EdgeTraversal(patternEdge, true);
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "HasCreator", "in", new SQLExpression(-1), false));

    setCounterField(edge, "preFilterAppliedCount", 3);
    setCounterField(edge, "preFilterTotalProbed", 500L);
    setCounterField(edge, "preFilterTotalFiltered", 400L);

    var step = new OptionalMatchStep(ctx, edge, true);
    var result = step.prettyPrint(0, 2);

    assertTrue("Should contain OPTIONAL MATCH",
        result.contains("OPTIONAL MATCH"));
    assertTrue("Should contain pre-filter stats",
        result.contains("pre-filter: applied=3"));
    assertTrue("Should contain filterRate",
        result.contains("filterRate=80.0%"));
  }

  /**
   * PROFILE output for SELECTIVITY_TOO_LOW shows the threshold value.
   */
  @Test
  public void testMatchStepPrettyPrintNeverAppliedSelectivityTooLow()
      throws Exception {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    setCounterField(edge, "lastSkipReason",
        PreFilterSkipReason.SELECTIVITY_TOO_LOW);

    var step = new MatchStep(ctx, edge, true);
    var result = step.prettyPrint(0, 2);

    assertTrue("Should contain NEVER APPLIED",
        result.contains("pre-filter: NEVER APPLIED"));
    assertTrue("Should contain SELECTIVITY_TOO_LOW",
        result.contains("SELECTIVITY_TOO_LOW"));
    assertTrue("Should contain threshold value",
        result.contains(
            "threshold=" + TraversalPreFilterHelper.indexLookupMaxSelectivity()));
  }

  /**
   * PROFILE output for OVERLAP_RATIO_TOO_HIGH shows the edgeLookupMaxRatio
   * threshold.
   */
  @Test
  public void testMatchStepPrettyPrintNeverAppliedOverlapRatioTooHigh()
      throws Exception {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    setCounterField(edge, "lastSkipReason",
        PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH);

    var step = new MatchStep(ctx, edge, true);
    var result = step.prettyPrint(0, 2);

    assertTrue("Should contain NEVER APPLIED",
        result.contains("pre-filter: NEVER APPLIED"));
    assertTrue("Should contain OVERLAP_RATIO_TOO_HIGH",
        result.contains("OVERLAP_RATIO_TOO_HIGH"));
    assertTrue("Should contain threshold value",
        result.contains(
            "threshold=" + TraversalPreFilterHelper.edgeLookupMaxRatio()));
  }

  /**
   * PROFILE output for BUILD_NOT_AMORTIZED shows "NEVER APPLIED" with the
   * reason but no threshold (falls through to default in appendSkipDiagnostic).
   */
  @Test
  public void testMatchStepPrettyPrintNeverAppliedBuildNotAmortized()
      throws Exception {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    setCounterField(edge, "lastSkipReason",
        PreFilterSkipReason.BUILD_NOT_AMORTIZED);

    var step = new MatchStep(ctx, edge, true);
    var result = step.prettyPrint(0, 2);

    assertTrue("Should contain NEVER APPLIED",
        result.contains("pre-filter: NEVER APPLIED"));
    assertTrue("Should contain BUILD_NOT_AMORTIZED",
        result.contains("BUILD_NOT_AMORTIZED"));
    assertFalse("Should NOT contain threshold= (no diagnostic for this reason)",
        result.contains("threshold="));
    assertFalse("Should NOT contain cap= (wrong diagnostic for this reason)",
        result.contains("cap="));
  }

  /**
   * PROFILE output for LINKBAG_TOO_SMALL shows "NEVER APPLIED" with the
   * reason but no threshold (falls through to default in appendSkipDiagnostic).
   */
  @Test
  public void testMatchStepPrettyPrintNeverAppliedLinkBagTooSmall()
      throws Exception {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    setCounterField(edge, "lastSkipReason",
        PreFilterSkipReason.LINKBAG_TOO_SMALL);

    var step = new MatchStep(ctx, edge, true);
    var result = step.prettyPrint(0, 2);

    assertTrue("Should contain NEVER APPLIED",
        result.contains("pre-filter: NEVER APPLIED"));
    assertTrue("Should contain LINKBAG_TOO_SMALL",
        result.contains("LINKBAG_TOO_SMALL"));
    assertFalse("Should NOT contain threshold= (no diagnostic for this reason)",
        result.contains("threshold="));
    assertFalse("Should NOT contain cap= (wrong diagnostic for this reason)",
        result.contains("cap="));
  }

  /**
   * PROFILE output with default counters (no skip recorded): shows bare
   * "NEVER APPLIED" without a parenthetical reason.
   */
  @Test
  public void testMatchStepPrettyPrintNeverAppliedNoReason() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    edge.setIntersectionDescriptor(
        new RidFilterDescriptor.EdgeRidLookup(
            "Knows", "out", new SQLExpression(-1), false));

    // All counters at defaults (0), lastSkipReason at NONE
    var step = new MatchStep(ctx, edge, true);
    var result = step.prettyPrint(0, 2);

    assertTrue("Should contain bare NEVER APPLIED",
        result.contains("pre-filter: NEVER APPLIED"));
    assertFalse("Should NOT contain '(reason:'",
        result.contains("(reason:"));
  }

  private static void setCounterField(
      Object target, String fieldName, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  // -- MatchFirstStep tests --

  /** Verifies MatchFirstStep.copy() with node only preserves alias in output. */
  @Test
  public void testMatchFirstStepCopyNoPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "a";
    var step = new MatchFirstStep(ctx, node, true);

    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof MatchFirstStep);
    var copy = (MatchFirstStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    // prettyPrint should contain the alias "a", proving the node was copied
    assertTrue(copy.prettyPrint(0, 2).contains("a"));
    assertFalse("copy without plan should not contain AS",
        copy.prettyPrint(0, 2).contains("AS"));
  }

  /** Verifies MatchFirstStep.copy() with plan preserves alias and sub-plan. */
  @Test
  public void testMatchFirstStepCopyWithPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "a";
    var plan = new SelectExecutionPlan(ctx);
    var step = new MatchFirstStep(ctx, node, plan, true);

    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof MatchFirstStep);
    var copy = (MatchFirstStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    // With a plan, prettyPrint should contain both the alias "a" and "AS"
    var pp = copy.prettyPrint(0, 2);
    assertTrue("copy with plan should contain alias", pp.contains("a"));
    assertTrue("copy with plan should contain AS block", pp.contains("AS"));
  }

  /** Verifies MatchFirstStep.reset() with null plan does not throw. */
  @Test
  public void testMatchFirstStepResetNullPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "a";
    var step = new MatchFirstStep(ctx, node, false);
    // Should not throw
    step.reset();
  }

  /** Verifies MatchFirstStep.prettyPrint() includes "AS" when sub-plan is present. */
  @Test
  public void testMatchFirstStepPrettyPrintWithPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "a";
    var plan = new SelectExecutionPlan(ctx);
    var step = new MatchFirstStep(ctx, node, plan, false);
    var result = step.prettyPrint(0, 2);
    assertTrue(result.contains("SET"));
    assertTrue(result.contains("AS"));
  }

  /**
   * Verifies MatchFirstStep.reset() delegates to the execution plan. After starting
   * the step (which consumes prefetched data), reset() should allow re-starting.
   */
  @Test
  public void testMatchFirstStepResetWithPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "a";

    // Build a sub-plan that returns a single result
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var r = new ResultInternal(session);
        r.setProperty("val", 1);
        return ExecutionStream.singleton(r);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    });
    var step = new MatchFirstStep(ctx, node, plan, false);

    // First start: consume the sub-plan results
    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    stream.next(ctx);
    stream.close(ctx);

    // reset() should delegate to executionPlan.reset(), allowing a fresh start
    step.reset();

    // After reset, the step should produce results again
    var stream2 = step.start(ctx);
    assertTrue("after reset, step should produce results again", stream2.hasNext(ctx));
    stream2.close(ctx);
  }

  /**
   * Verifies internalStart() uses prefetched data from the context variable when
   * available, wrapping each result into a new row keyed by the node alias.
   */
  @Test
  public void testMatchFirstStepInternalStartWithPrefetchedData() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "person";

    // Simulate MatchPrefetchStep having stored results in the context
    var prefetchedResult = new ResultInternal(session);
    prefetchedResult.setProperty("name", "Alice");
    List<Result> prefetched = List.of(prefetchedResult);
    ctx.setVariable(
        MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + "person", prefetched);

    // No sub-plan needed since prefetched data is available
    var step = new MatchFirstStep(ctx, node, false);
    var stream = step.start(ctx);

    assertTrue(stream.hasNext(ctx));
    var row = stream.next(ctx);
    // The output row must wrap the exact prefetched result under the alias key
    Result wrapped = row.getProperty("person");
    assertSame(prefetchedResult, wrapped);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies internalStart() falls back to the execution plan when no prefetched
   * data is present in the context, and correctly wraps results under the alias.
   */
  @Test
  public void testMatchFirstStepInternalStartWithExecutionPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "city";

    // Build a sub-plan that returns a single result
    var plan = new SelectExecutionPlan(ctx);
    var subResult = new ResultInternal(session);
    subResult.setProperty("title", "Berlin");
    plan.chain(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.singleton(subResult);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    });

    var step = new MatchFirstStep(ctx, node, plan, false);
    var stream = step.start(ctx);

    assertTrue(stream.hasNext(ctx));
    var row = stream.next(ctx);
    // The output row must wrap the exact sub-plan result under the "city" alias
    Result wrapped = row.getProperty("city");
    assertSame(subResult, wrapped);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that internalStart() sets the "$matched" context variable to the
   * current row, enabling downstream WHERE clauses to reference matched aliases.
   */
  @Test
  public void testMatchFirstStepSetsMatchedContextVariable() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "x";

    var prefetchedResult = new ResultInternal(session);
    prefetchedResult.setProperty("id", 42);
    ctx.setVariable(
        MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + "x",
        List.of(prefetchedResult));

    var step = new MatchFirstStep(ctx, node, false);
    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var row = stream.next(ctx);
    stream.close(ctx);

    // The "$matched" context variable must point to the produced row
    var matched = ctx.getVariable("$matched");
    assertNotNull(matched);
    assertSame(row, matched);
  }

  /**
   * Verifies internalStart() drains the previous step (if set) before producing
   * its own data. The drain counter on the prev step confirms this.
   */
  @Test
  public void testMatchFirstStepDrainsPreviousStep() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "v";

    // Track whether the previous step was drained
    var drained = new boolean[] {false};
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        drained[0] = true;
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };

    // Use prefetched data so we don't need a sub-plan
    ctx.setVariable(
        MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + "v",
        List.of(new ResultInternal(session)));

    var step = new MatchFirstStep(ctx, node, false);
    step.setPrevious(prevStep);
    var stream = step.start(ctx);

    // The previous step must have been drained
    assertTrue(drained[0]);
    // The stream should still produce data from prefetched results
    assertTrue(stream.hasNext(ctx));
    stream.next(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /** Verifies MatchFirstStep.prettyPrint() without a sub-plan omits the "AS" block. */
  @Test
  public void testMatchFirstStepPrettyPrintNoPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "a";
    var step = new MatchFirstStep(ctx, node, false);
    var result = step.prettyPrint(0, 2);
    assertTrue(result.contains("SET"));
    assertTrue(result.contains("a"));
    assertFalse(result.contains("AS"));
  }

  // -- OptionalMatchStep tests --

  /** Verifies OptionalMatchStep.prettyPrint() for reverse direction shows "<----". */
  @Test
  public void testOptionalMatchStepPrettyPrintReverse() {
    var ctx = createCommandContext();
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var item = new SQLMatchPathItem(-1);
    nodeA.addEdge(item, nodeB);
    var patternEdge = nodeA.out.iterator().next();
    var edge = new EdgeTraversal(patternEdge, false); // reverse

    var step = new OptionalMatchStep(ctx, edge, false);
    var result = step.prettyPrint(0, 2);
    assertTrue(result.contains("OPTIONAL MATCH"));
    assertTrue(result.contains("<----"));
  }

  // -- RemoveEmptyOptionalsStep tests --

  /** Verifies RemoveEmptyOptionalsStep.copy() preserves profiling flag. */
  @Test
  public void testRemoveEmptyOptionalsStepCopy() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, true);
    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof RemoveEmptyOptionalsStep);
    var copy = (RemoveEmptyOptionalsStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    assertEquals(step.prettyPrint(0, 2), copy.prettyPrint(0, 2));
  }

  /** Verifies prettyPrint() returns the expected "REMOVE EMPTY OPTIONALS" string. */
  @Test
  public void testRemoveEmptyOptionalsStepPrettyPrint() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    var result = step.prettyPrint(0, 2);
    assertEquals("+ REMOVE EMPTY OPTIONALS", result);
  }

  /** Verifies prettyPrint() applies indentation when depth > 0. */
  @Test
  public void testRemoveEmptyOptionalsStepPrettyPrintWithDepth() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    var result = step.prettyPrint(1, 3);
    assertEquals("   + REMOVE EMPTY OPTIONALS", result); // 1 * 3 = 3 spaces
  }

  /** Verifies prettyPrint() asserts when depth is negative. */
  @Test(expected = AssertionError.class)
  public void testRemoveEmptyOptionalsStepPrettyPrintNegativeDepth() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    step.prettyPrint(-1, 2);
  }

  /** Verifies prettyPrint() asserts when indent is negative. */
  @Test(expected = AssertionError.class)
  public void testRemoveEmptyOptionalsStepPrettyPrintNegativeIndent() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    step.prettyPrint(0, -1);
  }

  /**
   * Verifies that internalStart replaces EMPTY_OPTIONAL sentinel values with null
   * while preserving non-sentinel property values. This is the core logic of the step:
   * after optional traversals complete, sentinel placeholders must be normalized to null
   * for the final output.
   */
  @Test
  public void testRemoveEmptyOptionalsStepReplacesEmptyOptionals() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var result = new ResultInternal(session);
        result.setProperty("matched", "Alice");
        result.setProperty("optional", OptionalMatchEdgeTraverser.EMPTY_OPTIONAL);
        return ExecutionStream.singleton(result);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    // Non-sentinel value must be preserved
    assertEquals("Alice", result.getProperty("matched"));
    // EMPTY_OPTIONAL sentinel must be replaced with null
    assertNull(result.getProperty("optional"));
    assertFalse(stream.hasNext(ctx));
  }

  /**
   * Verifies that internalStart preserves all property values when none of them
   * are EMPTY_OPTIONAL sentinels. The step must be a no-op for non-optional rows.
   */
  @Test
  public void testRemoveEmptyOptionalsStepPreservesNonOptionals() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var result = new ResultInternal(session);
        result.setProperty("a", "value1");
        result.setProperty("b", 42);
        return ExecutionStream.singleton(result);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals("value1", result.getProperty("a"));
    assertEquals(42, (int) result.getProperty("b"));
    assertFalse(stream.hasNext(ctx));
  }

  /**
   * Verifies that internalStart handles an empty upstream stream correctly.
   * When there are no results to process, the step should return an empty stream.
   */
  @Test
  public void testRemoveEmptyOptionalsStepEmptyStream() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertNotNull(stream);
    assertFalse(stream.hasNext(ctx));
  }

  /**
   * Verifies that internalStart correctly handles a result row with no properties.
   * The for loop body should not execute, and the empty result is returned unchanged.
   */
  @Test
  public void testRemoveEmptyOptionalsStepEmptyProperties() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        // Result with no properties
        return ExecutionStream.singleton(new ResultInternal(session));
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertTrue(result.getPropertyNames().isEmpty());
    assertFalse(stream.hasNext(ctx));
  }

  /**
   * Verifies that internalStart asserts when the previous step is null.
   * This guards against a pipeline configuration error where the step is
   * started without being connected to an upstream.
   */
  @Test(expected = AssertionError.class)
  public void testRemoveEmptyOptionalsStepAssertNullPrev() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    // No setPrevious call — prev remains null
    step.start(ctx);
  }

  // -- ReturnMatchPathsStep tests --

  /** Verifies ReturnMatchPathsStep.copy() preserves profiling flag. */
  @Test
  public void testReturnMatchPathsStepCopy() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, true);
    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof ReturnMatchPathsStep);
    var copy = (ReturnMatchPathsStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    assertEquals(step.prettyPrint(0, 2), copy.prettyPrint(0, 2));
  }

  /** Verifies prettyPrint() returns the expected "RETURN $paths" string with indentation. */
  @Test
  public void testReturnMatchPathsStepPrettyPrint() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    var result = step.prettyPrint(0, 2);
    assertNotNull(result);
    assertEquals("+ RETURN $paths", result);
  }

  /** Verifies prettyPrint() applies indentation when depth > 0. */
  @Test
  public void testReturnMatchPathsStepPrettyPrintWithDepth() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    var result = step.prettyPrint(1, 3);
    assertNotNull(result);
    assertTrue(result.endsWith("+ RETURN $paths"));
    assertTrue(result.startsWith("   ")); // 1 * 3 = 3 spaces
  }

  /** Verifies prettyPrint() asserts when depth is negative. */
  @Test(expected = AssertionError.class)
  public void testReturnMatchPathsStepPrettyPrintNegativeDepth() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    step.prettyPrint(-1, 2);
  }

  /** Verifies prettyPrint() asserts when indent is negative. */
  @Test(expected = AssertionError.class)
  public void testReturnMatchPathsStepPrettyPrintNegativeIndent() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    step.prettyPrint(0, -1);
  }

  /**
   * Verifies internalStart() forwards upstream data unchanged. ReturnMatchPathsStep
   * is a pass-through step: rows with all aliases (including auto-generated) are kept.
   */
  @Test
  public void testReturnMatchPathsStepForwardsUpstreamData() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var result = new ResultInternal(session);
        result.setProperty("a", "value_a");
        result.setProperty("b", "value_b");
        return ExecutionStream.singleton(result);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);
    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    // ReturnMatchPathsStep passes through all properties unchanged
    assertEquals("value_a", result.getProperty("a"));
    assertEquals("value_b", result.getProperty("b"));
    assertFalse(stream.hasNext(ctx));
  }

  // -- ReturnMatchPatternsStep tests --

  /** Verifies ReturnMatchPatternsStep.copy() preserves profiling flag. */
  @Test
  public void testReturnMatchPatternsStepCopy() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, true);
    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof ReturnMatchPatternsStep);
    var copy = (ReturnMatchPatternsStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    assertEquals(step.prettyPrint(0, 2), copy.prettyPrint(0, 2));
  }

  /** Verifies prettyPrint() returns the expected "RETURN $patterns" string. */
  @Test
  public void testReturnMatchPatternsStepPrettyPrint() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, false);
    var result = step.prettyPrint(0, 2);
    assertNotNull(result);
    assertEquals("+ RETURN $patterns", result);
  }

  /** Verifies prettyPrint() applies indentation when depth > 0. */
  @Test
  public void testReturnMatchPatternsStepPrettyPrintWithDepth() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, false);
    var result = step.prettyPrint(1, 3);
    assertEquals("   + RETURN $patterns", result); // 1 * 3 = 3 spaces
  }

  /** Verifies prettyPrint() asserts when depth is negative. */
  @Test(expected = AssertionError.class)
  public void testReturnMatchPatternsStepPrettyPrintNegativeDepth() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, false);
    step.prettyPrint(-1, 2);
  }

  /** Verifies prettyPrint() asserts when indent is negative. */
  @Test(expected = AssertionError.class)
  public void testReturnMatchPatternsStepPrettyPrintNegativeIndent() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, false);
    step.prettyPrint(0, -1);
  }

  /**
   * Verifies internalStart() strips properties with the auto-generated alias prefix
   * while keeping user-defined aliases. Covers the true branch of the startsWith check.
   */
  @Test
  public void testReturnMatchPatternsStepStripsDefaultAliases() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var result = new ResultInternal(session);
        result.setProperty("userAlias", "value1");
        result.setProperty(
            MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX + "0", "value2");
        return ExecutionStream.singleton(result);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    // User-defined alias must be preserved
    assertTrue(result.getPropertyNames().contains("userAlias"));
    // Auto-generated alias must be stripped
    assertFalse(result.getPropertyNames().contains(
        MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX + "0"));
    assertFalse(stream.hasNext(ctx));
  }

  /**
   * Verifies internalStart() keeps all properties when none have the default alias
   * prefix. Covers the false branch of the startsWith check.
   */
  @Test
  public void testReturnMatchPatternsStepKeepsUserAliases() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var result = new ResultInternal(session);
        result.setProperty("alias1", "v1");
        result.setProperty("alias2", "v2");
        return ExecutionStream.singleton(result);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertTrue(result.getPropertyNames().contains("alias1"));
    assertTrue(result.getPropertyNames().contains("alias2"));
    assertEquals(2, result.getPropertyNames().size());
    assertFalse(stream.hasNext(ctx));
  }

  /**
   * Verifies internalStart() handles an empty upstream stream correctly.
   */
  @Test
  public void testReturnMatchPatternsStepEmptyStream() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertNotNull(stream);
    assertFalse(stream.hasNext(ctx));
  }

  // -- ReturnMatchElementsStep tests --

  /** Verifies ReturnMatchElementsStep.copy() preserves profiling flag. */
  @Test
  public void testReturnMatchElementsStepCopy() {
    var ctx = createCommandContext();
    var step = new ReturnMatchElementsStep(ctx, true);
    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof ReturnMatchElementsStep);
    var copy = (ReturnMatchElementsStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
  }

  // -- ReturnMatchPathElementsStep tests --

  /** Verifies ReturnMatchPathElementsStep.copy() preserves profiling flag. */
  @Test
  public void testReturnMatchPathElementsStepCopy() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, true);
    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof ReturnMatchPathElementsStep);
    var copy = (ReturnMatchPathElementsStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    assertEquals(step.prettyPrint(0, 2), copy.prettyPrint(0, 2));
  }

  /** Verifies prettyPrint() returns the expected "UNROLL $pathElements" string. */
  @Test
  public void testReturnMatchPathElementsStepPrettyPrint() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    var result = step.prettyPrint(0, 2);
    assertEquals("+ UNROLL $pathElements", result);
  }

  /** Verifies prettyPrint() applies indentation when depth > 0. */
  @Test
  public void testReturnMatchPathElementsStepPrettyPrintWithDepth() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    var result = step.prettyPrint(1, 3);
    assertEquals("   + UNROLL $pathElements", result); // 1 * 3 = 3 spaces
  }

  /** Verifies prettyPrint() asserts when depth is negative. */
  @Test(expected = AssertionError.class)
  public void testReturnMatchPathElementsStepPrettyPrintNegativeDepth() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    step.prettyPrint(-1, 2);
  }

  /** Verifies prettyPrint() asserts when indent is negative. */
  @Test(expected = AssertionError.class)
  public void testReturnMatchPathElementsStepPrettyPrintNegativeIndent() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    step.prettyPrint(0, -1);
  }

  /**
   * Verifies that unroll extracts Result-typed properties from the upstream row
   * and emits each as a separate result. Both user-defined and auto-generated
   * aliases are included, unlike ReturnMatchElementsStep which skips auto-generated
   * aliases. This covers the {@code elem instanceof Result} true branch.
   */
  @Test
  public void testReturnMatchPathElementsStepUnrollsResultProperties() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var row = new ResultInternal(session);
        var r1 = new ResultInternal(session);
        r1.setProperty("name", "Alice");
        var r2 = new ResultInternal(session);
        r2.setProperty("name", "Bob");
        row.setProperty("userAlias", r1);
        row.setProperty(
            MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX + "0", r2);
        return ExecutionStream.singleton(row);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    // Both aliases (user and auto-generated) should produce results
    var names = new java.util.HashSet<String>();
    assertTrue(stream.hasNext(ctx));
    names.add(stream.next(ctx).getProperty("name"));
    assertTrue(stream.hasNext(ctx));
    names.add(stream.next(ctx).getProperty("name"));
    assertFalse(stream.hasNext(ctx));
    // Verify actual content, not just count
    assertTrue(names.contains("Alice"));
    assertTrue(names.contains("Bob"));
  }

  /**
   * Verifies that unroll wraps Identifiable values into ResultInternal before
   * emitting them. This covers the {@code elem instanceof Identifiable} true
   * branch, followed by the {@code elem instanceof Result} true branch (since
   * the wrapped ResultInternal is a Result).
   */
  @Test
  public void testReturnMatchPathElementsStepWrapsIdentifiable() {
    session.createClassIfNotExist("PathElemV", "V");
    session.begin();
    try {
      var ctx = createCommandContext();
      var step = new ReturnMatchPathElementsStep(ctx, false);
      var vertex = session.newVertex("PathElemV");
      var prevStep = new AbstractExecutionStep(ctx, false) {
        @Override
        public ExecutionStream internalStart(CommandContext ctx) {
          var row = new ResultInternal(session);
          row.setProperty("node", vertex);
          return ExecutionStream.singleton(row);
        }

        @Override
        public String prettyPrint(int depth, int indent) {
          return "";
        }

        @Override
        public ExecutionStep copy(CommandContext ctx) {
          return this;
        }
      };
      step.setPrevious(prevStep);

      var stream = step.start(ctx);
      assertTrue(stream.hasNext(ctx));
      var result = stream.next(ctx);
      // The Identifiable should have been wrapped into a Result
      assertNotNull(result);
      assertTrue(result.isEntity());
      assertFalse(stream.hasNext(ctx));
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies that unroll silently skips properties whose values are neither
   * Result nor Identifiable (e.g. primitives). This covers the false branch
   * of both {@code instanceof Identifiable} and {@code instanceof Result}.
   */
  @Test
  public void testReturnMatchPathElementsStepSkipsPrimitives() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var row = new ResultInternal(session);
        // Primitive values: String and Integer
        row.setProperty("scalarString", "hello");
        row.setProperty("scalarInt", 42);
        return ExecutionStream.singleton(row);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    // Primitives are skipped, so the stream should be empty
    assertFalse(stream.hasNext(ctx));
  }

  /**
   * Verifies that unroll handles an empty upstream result (no properties) by
   * returning an empty collection. The for-each loop body never executes.
   */
  @Test
  public void testReturnMatchPathElementsStepEmptyProperties() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.singleton(new ResultInternal(session));
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    // No properties → no unrolled results
    assertFalse(stream.hasNext(ctx));
  }

  /**
   * Verifies that unroll handles a mix of Result, Identifiable, and primitive
   * properties: only Result and wrapped-Identifiable values are emitted, and
   * primitives are discarded.
   */
  @Test
  public void testReturnMatchPathElementsStepMixedProperties() {
    session.createClassIfNotExist("PathMixV", "V");
    session.begin();
    try {
      var ctx = createCommandContext();
      var step = new ReturnMatchPathElementsStep(ctx, false);
      var vertex = session.newVertex("PathMixV");
      var prevStep = new AbstractExecutionStep(ctx, false) {
        @Override
        public ExecutionStream internalStart(CommandContext ctx) {
          var row = new ResultInternal(session);
          var resultVal = new ResultInternal(session);
          resultVal.setProperty("x", 1);
          row.setProperty("resultProp", resultVal); // Result → emitted
          row.setProperty("identProp", vertex); // Identifiable → wrapped & emitted
          row.setProperty("primitiveProp", "ignored"); // Primitive → skipped
          return ExecutionStream.singleton(row);
        }

        @Override
        public String prettyPrint(int depth, int indent) {
          return "";
        }

        @Override
        public ExecutionStep copy(CommandContext ctx) {
          return this;
        }
      };
      step.setPrevious(prevStep);

      var stream = step.start(ctx);
      // 2 emitted: Result + wrapped Identifiable; primitive skipped
      boolean foundEntity = false;
      boolean foundResultWithX = false;
      assertTrue(stream.hasNext(ctx));
      var r1 = stream.next(ctx);
      assertTrue(stream.hasNext(ctx));
      var r2 = stream.next(ctx);
      assertFalse(stream.hasNext(ctx));
      // Verify content: one is an entity (wrapped Identifiable), one has property x=1
      for (var r : List.of(r1, r2)) {
        if (r.isEntity()) {
          foundEntity = true;
        }
        if (Integer.valueOf(1).equals(r.<Integer>getProperty("x"))) {
          foundResultWithX = true;
        }
      }
      assertTrue("Expected a wrapped Identifiable (entity)", foundEntity);
      assertTrue("Expected a Result with x=1", foundResultWithX);
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies that internalStart (inherited from AbstractUnrollStep) throws
   * CommandExecutionException when no previous step is connected. This tests
   * the inherited null-prev guard in AbstractUnrollStep.
   */
  @Test(expected = CommandExecutionException.class)
  public void testReturnMatchPathElementsStepNullPrevThrows() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    // No setPrevious call — prev remains null
    step.start(ctx);
  }

  /**
   * Verifies that internalStart handles an empty upstream stream correctly.
   * When there are no upstream results, the step should produce an empty stream.
   */
  @Test
  public void testReturnMatchPathElementsStepEmptyUpstream() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertNotNull(stream);
    assertFalse(stream.hasNext(ctx));
  }

  // -- FilterNotMatchPatternStep tests --

  /** Verifies FilterNotMatchPatternStep.copy() preserves profiling flag and empty sub-steps. */
  @Test
  public void testFilterNotMatchPatternStepCopy() {
    var ctx = createCommandContext();
    var step = new FilterNotMatchPatternStep(List.of(), ctx, true);
    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof FilterNotMatchPatternStep);
    var copy = (FilterNotMatchPatternStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    assertEquals(0, copy.getSubSteps().size());
  }

  /**
   * Verifies that constructing FilterNotMatchPatternStep with a null sub-steps list
   * triggers the assertion guard. The constructor requires a non-null list because
   * a null would cause NPE during pattern evaluation later.
   */
  @Test(expected = AssertionError.class)
  public void testFilterNotMatchPatternStepConstructorNullSteps() {
    var ctx = createCommandContext();
    new FilterNotMatchPatternStep(null, ctx, false);
  }

  /**
   * Verifies that internalStart throws IllegalStateException when no previous step
   * is connected. The step requires upstream input to filter; starting it without
   * a predecessor is a pipeline configuration error.
   */
  @Test(expected = IllegalStateException.class)
  public void testFilterNotMatchPatternStepNullPrevThrows() {
    var ctx = createCommandContext();
    var step = new FilterNotMatchPatternStep(List.of(), ctx, false);
    // No setPrevious call — prev remains null
    step.start(ctx);
  }

  /**
   * Verifies that when the NOT pattern matches (sub-steps produce a result),
   * the upstream row is discarded. With an empty sub-steps list, the internal
   * plan consists solely of ChainStep which always emits a copy of the row —
   * so the pattern always "matches" and every upstream row is dropped.
   */
  @Test
  public void testFilterNotMatchPatternStepDiscardsMatchingRows() {
    var ctx = createCommandContext();
    // Empty sub-steps: ChainStep alone always produces output → pattern matches
    var step = new FilterNotMatchPatternStep(List.of(), ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var result = new ResultInternal(session);
        result.setProperty("name", "Alice");
        return ExecutionStream.singleton(result);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    // Pattern matches → row is discarded → stream is empty
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that when the NOT pattern does NOT match (sub-steps produce no result),
   * the upstream row passes through. A sub-step that always returns empty causes
   * the NOT-pattern plan to produce no results, so the row is kept.
   */
  @Test
  public void testFilterNotMatchPatternStepKeepsNonMatchingRows() {
    var ctx = createCommandContext();
    // Sub-step that always returns empty → pattern never matches → rows pass through
    var step = new FilterNotMatchPatternStep(
        List.of(createEmptySubStep(ctx)), ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var result = new ResultInternal(session);
        result.setProperty("name", "Bob");
        return ExecutionStream.singleton(result);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    // Pattern does not match → row passes through
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals("Bob", result.<String>getProperty("name"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that when upstream is empty, the filtered stream is also empty
   * regardless of the sub-steps configuration. No rows means nothing to evaluate.
   */
  @Test
  public void testFilterNotMatchPatternStepEmptyUpstream() {
    var ctx = createCommandContext();
    var step = new FilterNotMatchPatternStep(List.of(), ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that the NOT-pattern evaluation correctly processes upstream rows with
   * metadata (ResultInternal). ChainStep internally copies both properties and
   * metadata. With an empty sub-steps list, ChainStep always produces output,
   * so the pattern "matches" and the upstream row is discarded.
   */
  @Test
  public void testFilterNotMatchPatternStepWithMetadataUpstream() {
    var ctx = createCommandContext();
    var step = new FilterNotMatchPatternStep(List.of(), ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var result = new ResultInternal(session);
        result.setProperty("key", "value");
        result.setMetadata("md_key", "md_value");
        return ExecutionStream.singleton(result);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    // Pattern always matches (empty sub-steps) → row discarded without error
    var stream = step.start(ctx);
    assertFalse("metadata-bearing row should still be discarded when pattern matches",
        stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that the NOT-pattern evaluation handles non-ResultInternal Result
   * implementations without error. ChainStep copies only properties (no metadata)
   * for non-ResultInternal types. With empty sub-steps, pattern matches and the
   * row is discarded.
   */
  @Test
  public void testFilterNotMatchPatternStepNonResultInternalUpstream() {
    var ctx = createCommandContext();
    var step = new FilterNotMatchPatternStep(List.of(), ctx, false);
    var prevStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.singleton(new NonResultInternalStub("prop1", "val1"));
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    step.setPrevious(prevStep);

    // Non-ResultInternal upstream should be handled without error
    var stream = step.start(ctx);
    assertFalse("non-ResultInternal row should still be discarded when pattern matches",
        stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that getSubSteps() returns the sub-steps list provided at construction.
   * The returned list should reflect the NOT-pattern's traversal edges.
   */
  @Test
  public void testFilterNotMatchPatternStepGetSubSteps() {
    var ctx = createCommandContext();
    var subStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "sub";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    var step = new FilterNotMatchPatternStep(List.of(subStep), ctx, false);
    var subSteps = step.getSubSteps();
    assertEquals(1, subSteps.size());
    assertSame(subStep, subSteps.get(0));
  }

  /**
   * Verifies that prettyPrint() outputs the "NOT" block header with each sub-step
   * indented inside parentheses. This is the human-readable representation used
   * in EXPLAIN output.
   */
  @Test
  public void testFilterNotMatchPatternStepPrettyPrint() {
    var ctx = createCommandContext();
    var subStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "  child_step";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
    var step = new FilterNotMatchPatternStep(List.of(subStep), ctx, false);
    var output = step.prettyPrint(0, 2);
    assertTrue(output.contains("NOT"));
    assertTrue(output.contains("child_step"));
    assertTrue(output.contains(")"));
  }

  /**
   * Verifies that copy() preserves sub-steps by copying each one via its own copy()
   * method. The copied step should have the same number of sub-steps as the original.
   */
  @Test
  public void testFilterNotMatchPatternStepCopyPreservesSubSteps() {
    var ctx = createCommandContext();
    var subStep = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return new AbstractExecutionStep(ctx, false) {
          @Override
          public ExecutionStream internalStart(CommandContext ctx) {
            return ExecutionStream.empty();
          }

          @Override
          public String prettyPrint(int depth, int indent) {
            return "";
          }

          @Override
          public ExecutionStep copy(CommandContext ctx) {
            return this;
          }
        };
      }
    };
    var step = new FilterNotMatchPatternStep(List.of(subStep), ctx, false);
    var copy = (FilterNotMatchPatternStep) step.copy(ctx);
    assertNotSame(step, copy);
    assertEquals(1, copy.getSubSteps().size());
    // Sub-step should be a copy, not the same instance
    assertNotSame(subStep, copy.getSubSteps().get(0));
  }

  // -- MatchPrefetchStep tests --

  /** Verifies MatchPrefetchStep.copy() preserves alias and profiling flag. */
  @Test
  public void testMatchPrefetchStepCopy() {
    var ctx = createCommandContext();
    var plan = new SelectExecutionPlan(ctx);
    var step = new MatchPrefetchStep(ctx, plan, "myAlias", true);
    var rawCopy = step.copy(ctx);
    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof MatchPrefetchStep);
    var copy = (MatchPrefetchStep) rawCopy;
    assertTrue("copy should preserve profilingEnabled", copy.isProfilingEnabled());
    // Verify alias is preserved by checking prettyPrint output
    assertTrue("copy should preserve alias in output",
        copy.prettyPrint(0, 2).contains("myAlias"));
  }

  /** Verifies MatchPrefetchStep.prettyPrint() includes "PREFETCH" and the alias. */
  @Test
  public void testMatchPrefetchStepPrettyPrint() {
    var ctx = createCommandContext();
    var plan = new SelectExecutionPlan(ctx);
    var step = new MatchPrefetchStep(ctx, plan, "myAlias", false);
    var result = step.prettyPrint(0, 2);
    assertTrue(result.contains("PREFETCH"));
    assertTrue(result.contains("myAlias"));
  }

  /**
   * Verifies MatchPrefetchStep.reset() delegates to the sub-plan, allowing the
   * prefetch to be re-executed after reset.
   */
  @Test
  public void testMatchPrefetchStepReset() {
    var ctx = createCommandContext();
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        var r = new ResultInternal(session);
        r.setProperty("x", 1);
        return ExecutionStream.singleton(r);
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    });
    var step = new MatchPrefetchStep(ctx, plan, "alias", false);

    // First start: populates the context variable
    step.start(ctx).close(ctx);
    assertNotNull("prefetched data should be stored in context",
        ctx.getVariable(MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + "alias"));

    // Reset and re-start should work without error
    step.reset();
    step.start(ctx).close(ctx);
    assertNotNull("after reset, prefetched data should still be in context",
        ctx.getVariable(MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + "alias"));
  }

  // -- AbstractExecutionStep base behavior tests (exercised via match steps) --

  /**
   * Verifies sendTimeout() is safe to call when no previous step is connected.
   * This is the base case for the first step in a pipeline.
   */
  @Test
  public void testSendTimeoutNoPreviousStepDoesNotThrow() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    // First step in pipeline has no prev — sendTimeout must be a no-op
    step.sendTimeout();
  }

  /**
   * Verifies profilingEnabled getter/setter round-trip and that the flag is
   * reflected in step behavior (profiled steps track execution cost).
   */
  @Test
  public void testProfilingEnabledRoundTrip() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    assertFalse("default should be non-profiling", step.isProfilingEnabled());

    step.setProfilingEnabled(true);
    assertTrue("should be profiling after set", step.isProfilingEnabled());

    step.setProfilingEnabled(false);
    assertFalse("should revert to non-profiling", step.isProfilingEnabled());
  }

  // -- toExecutionStream tests (shared utility method) --

  /** Verifies toExecutionStream returns empty for null input. */
  @Test
  public void testToExecutionStreamNull() {
    var ctx = createCommandContext();
    var stream = MatchEdgeTraverser.toExecutionStream(null, session);
    assertFalse(stream.hasNext(ctx));
  }

  /** Verifies toExecutionStream wraps a ResultInternal as a singleton stream. */
  @Test
  public void testToExecutionStreamResultInternal() {
    var ctx = createCommandContext();
    var result = new ResultInternal(session);
    result.setProperty("x", 1);
    var stream = MatchEdgeTraverser.toExecutionStream(result, session);
    assertTrue(stream.hasNext(ctx));
    assertEquals(1, (int) stream.next(ctx).getProperty("x"));
    assertFalse(stream.hasNext(ctx));
  }

  /** Verifies toExecutionStream wraps an Identifiable as a singleton stream. */
  @Test
  public void testToExecutionStreamIdentifiable() {
    var ctx = createCommandContext();
    session.begin();
    var vertex = session.newVertex("V");
    session.commit();

    session.begin();
    var stream = MatchEdgeTraverser.toExecutionStream(vertex, session);
    assertTrue(stream.hasNext(ctx));
    assertNotNull(stream.next(ctx));
    assertFalse(stream.hasNext(ctx));
    session.commit();
  }

  /** Verifies toExecutionStream wraps an Iterable as a multi-element stream. */
  @Test
  public void testToExecutionStreamIterable() {
    var ctx = createCommandContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("n", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("n", 2);
    Iterable<ResultInternal> iterable = List.of(r1, r2);

    var stream = MatchEdgeTraverser.toExecutionStream(iterable, session);
    assertTrue(stream.hasNext(ctx));
    stream.next(ctx);
    assertTrue(stream.hasNext(ctx));
    stream.next(ctx);
    assertFalse(stream.hasNext(ctx));
  }

  /** Verifies toExecutionStream returns empty for unrecognized types (default branch). */
  @Test
  public void testToExecutionStreamDefault() {
    var ctx = createCommandContext();
    // A plain String is not a recognized traversal result type
    var stream = MatchEdgeTraverser.toExecutionStream("unexpected", session);
    assertFalse(stream.hasNext(ctx));
  }

  // -- matchesRid tests --

  /** Verifies matchesRid returns true when rid is null (no constraint). */
  @Test
  public void testMatchesRidNullRid() {
    var ctx = createCommandContext();
    assertTrue(MatchEdgeTraverser.matchesRid(ctx, null, new ResultInternal(session)));
  }

  /** Verifies matchesRid returns false when origin is null. */
  @Test
  public void testMatchesRidNullOrigin() {
    var ctx = createCommandContext();
    var rid = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid(-1);
    assertFalse(MatchEdgeTraverser.matchesRid(ctx, rid, null));
  }

  /** Verifies matchesRid returns false when origin has no identity. */
  @Test
  public void testMatchesRidNullIdentity() {
    var ctx = createCommandContext();
    var rid = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid(-1);
    var result = new ResultInternal(session);
    // ResultInternal with no entity has null identity
    assertFalse(MatchEdgeTraverser.matchesRid(ctx, rid, result));
  }

  // -- matchesClass tests --

  /** Verifies matchesClass returns true when className is null (no constraint). */
  @Test
  public void testMatchesClassNullClassName() {
    var ctx = createCommandContext();
    assertTrue(MatchEdgeTraverser.matchesClass(ctx, null, new ResultInternal(session)));
  }

  /** Verifies matchesClass returns false when the entity has no schema class. */
  @Test
  public void testMatchesClassNoEntity() {
    var ctx = createCommandContext();
    // ResultInternal with no entity returns null from asEntityOrNull()
    assertFalse(MatchEdgeTraverser.matchesClass(ctx, "Person", new ResultInternal(session)));
  }

  // -- matchesFilters tests --

  /** Verifies matchesFilters returns true when filter is null (no constraint). */
  @Test
  public void testMatchesFiltersNullFilter() {
    var ctx = createCommandContext();
    assertTrue(MatchEdgeTraverser.matchesFilters(ctx, null, new ResultInternal(session)));
  }

  // -- MatchAssertions tests --

  /** Verifies checkNotNull returns true for non-null value. */
  @Test
  public void testCheckNotNullPasses() {
    assertTrue(MatchAssertions.checkNotNull("value", "label"));
  }

  /** Verifies checkNotNull throws AssertionError for null value. */
  @Test(expected = AssertionError.class)
  public void testCheckNotNullFails() {
    MatchAssertions.checkNotNull(null, "label");
  }

  /** Verifies checkNotEmpty returns true for non-empty string. */
  @Test
  public void testCheckNotEmptyPasses() {
    assertTrue(MatchAssertions.checkNotEmpty("value", "label"));
  }

  /** Verifies checkNotEmpty throws AssertionError for null string. */
  @Test(expected = AssertionError.class)
  public void testCheckNotEmptyFailsNull() {
    MatchAssertions.checkNotEmpty(null, "label");
  }

  /** Verifies checkNotEmpty throws AssertionError for empty string. */
  @Test(expected = AssertionError.class)
  public void testCheckNotEmptyFailsEmpty() {
    MatchAssertions.checkNotEmpty("", "label");
  }

  // -- validateEdgeTraversalArgs tests --

  /** Verifies validateEdgeTraversalArgs returns true for a valid edge. */
  @Test
  public void testValidateEdgeTraversalArgsPasses() {
    var edge = createTestPatternEdge();
    assertTrue(MatchAssertions.validateEdgeTraversalArgs(edge));
  }

  /** Verifies validateEdgeTraversalArgs throws for null edge. */
  @Test(expected = AssertionError.class)
  public void testValidateEdgeTraversalArgsNullEdge() {
    MatchAssertions.validateEdgeTraversalArgs(null);
  }

  /** Verifies validateEdgeTraversalArgs throws for edge with null source node. */
  @Test(expected = AssertionError.class)
  public void testValidateEdgeTraversalArgsNullOut() {
    var edge = new PatternEdge();
    edge.in = new PatternNode();
    edge.out = null;
    MatchAssertions.validateEdgeTraversalArgs(edge);
  }

  /** Verifies validateEdgeTraversalArgs throws for edge with null target node. */
  @Test(expected = AssertionError.class)
  public void testValidateEdgeTraversalArgsNullIn() {
    var edge = new PatternEdge();
    edge.out = new PatternNode();
    edge.in = null;
    MatchAssertions.validateEdgeTraversalArgs(edge);
  }

  // -- validateAddEdgeArgs tests --

  /** Verifies validateAddEdgeArgs returns true when both arguments are non-null. */
  @Test
  public void testValidateAddEdgeArgsPasses() {
    var item = new SQLMatchPathItem(-1);
    var node = new PatternNode();
    assertTrue(MatchAssertions.validateAddEdgeArgs(item, node));
  }

  /** Verifies validateAddEdgeArgs throws for null path item. */
  @Test(expected = AssertionError.class)
  public void testValidateAddEdgeArgsNullItem() {
    MatchAssertions.validateAddEdgeArgs(null, new PatternNode());
  }

  /** Verifies validateAddEdgeArgs throws for null target node. */
  @Test(expected = AssertionError.class)
  public void testValidateAddEdgeArgsNullNode() {
    MatchAssertions.validateAddEdgeArgs(new SQLMatchPathItem(-1), null);
  }

  // -- HashJoinMatchStep tests --

  /**
   * Creates a synthetic build-side plan that emits the given rows.
   * The plan consists of a single source step that streams the rows.
   */
  private SelectExecutionPlan createBuildPlan(CommandContext ctx, ResultInternal... rows) {
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.resultIterator(java.util.Arrays.asList(rows).iterator());
      }

      @Override
      public boolean canBeCached() {
        return true;
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "+ TEST SOURCE";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return createBuildPlan(ctx, rows).getSteps().getFirst();
      }
    });
    return plan;
  }

  /**
   * Creates a synthetic upstream step that emits the given rows.
   */
  private AbstractExecutionStep createUpstreamStep(
      CommandContext ctx, ResultInternal... rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.resultIterator(java.util.Arrays.asList(rows).iterator());
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "+ UPSTREAM";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
  }

  /** Creates a ResultInternal with a single alias property set to a RID. */
  private ResultInternal createRow(String alias, RID rid) {
    var row = new ResultInternal(session);
    row.setProperty(alias, rid);
    return row;
  }

  /** Creates a ResultInternal with two alias properties set to RIDs. */
  private ResultInternal createRow(String alias1, RID rid1, String alias2, RID rid2) {
    var row = new ResultInternal(session);
    row.setProperty(alias1, rid1);
    row.setProperty(alias2, rid2);
    return row;
  }

  /**
   * Anti-join discards upstream rows whose key exists in the build side.
   * Build side has friend=#1:1, upstream has friend=#1:1 and friend=#1:2.
   * Expected: only friend=#1:2 passes.
   */
  @Test
  public void testHashJoinAntiJoinDiscardsMatchingRows() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)),
        createRow("friend", new RecordId(1, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals(new RecordId(1, 2), result.getProperty("friend"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Anti-join passes all upstream rows when build side is empty.
   */
  @Test
  public void testHashJoinAntiJoinEmptyBuildSide() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx);
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)),
        createRow("friend", new RecordId(1, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    assertEquals(new RecordId(1, 1), stream.next(ctx).getProperty("friend"));
    assertTrue(stream.hasNext(ctx));
    assertEquals(new RecordId(1, 2), stream.next(ctx).getProperty("friend"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Anti-join produces empty output when all upstream rows match.
   */
  @Test
  public void testHashJoinAntiJoinAllFiltered() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)),
        createRow("friend", new RecordId(1, 2)));
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)),
        createRow("friend", new RecordId(1, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Semi-join keeps only upstream rows whose key exists in the build side.
   */
  @Test
  public void testHashJoinSemiJoinKeepsMatchingRows() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)),
        createRow("friend", new RecordId(1, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.SEMI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals(new RecordId(1, 1), result.getProperty("friend"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Build-phase context isolation: the build plan's execution must not
   * pollute $matched in the parent context.
   */
  @Test
  public void testHashJoinBuildPhaseContextIsolation() {
    var ctx = createCommandContext();
    var sentinel = "original-matched-value";
    ctx.setVariable("$matched", sentinel);

    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    while (stream.hasNext(ctx)) {
      stream.next(ctx);
    }
    stream.close(ctx);

    // Parent context's $matched must be unchanged
    assertEquals(sentinel, ctx.getVariable("$matched"));
  }

  /**
   * Multi-alias composite key: anti-join with two shared aliases.
   * Build side has (friend=#1:1, tag=#2:1). Upstream has that pair
   * and a different pair. Only the different pair passes.
   */
  @Test
  public void testHashJoinCompositeKeyAntiJoin() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1), "tag", new RecordId(2, 1)));
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1), "tag", new RecordId(2, 1)),
        createRow("friend", new RecordId(1, 1), "tag", new RecordId(2, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan,
        List.of("friend", "tag"), JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    // Assert both alias values to confirm correct composite key matching
    assertEquals(new RecordId(1, 1), result.getProperty("friend"));
    assertEquals(new RecordId(2, 2), result.getProperty("tag"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Anti-join with null key: upstream row missing the shared alias
   * should be conservatively kept.
   */
  @Test
  public void testHashJoinAntiJoinNullKeyKeepsRow() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));
    var missingAliasRow = new ResultInternal(session);
    missingAliasRow.setProperty("other", "value");
    var upstream = createUpstreamStep(ctx, missingAliasRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    // Verify the specific row with missing alias was kept
    assertEquals("value", result.getProperty("other"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Semi-join with null key: upstream row missing the shared alias
   * should be conservatively discarded.
   */
  @Test
  public void testHashJoinSemiJoinNullKeyDiscardsRow() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));
    var missingAliasRow = new ResultInternal(session);
    missingAliasRow.setProperty("other", "value");
    var upstream = createUpstreamStep(ctx, missingAliasRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.SEMI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Semi-join with empty build side filters all upstream rows.
   */
  @Test
  public void testHashJoinSemiJoinEmptyBuildSide() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx);
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)),
        createRow("friend", new RecordId(1, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.SEMI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN 1:1: one upstream row matching one build-side row produces
   * one merged row containing properties from both sides.
   */
  @Test
  public void testHashJoinInnerJoinOneToOne() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("friend", new RecordId(1, 1));
    buildRow.setProperty("city", "Berlin");
    var buildPlan = createBuildPlan(ctx, buildRow);

    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("friend", new RecordId(1, 1));
    upstreamRow.setProperty("person", new RecordId(2, 1));
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    // Merged row has upstream + build-side properties
    assertEquals(new RecordId(1, 1), result.getProperty("friend"));
    assertEquals(new RecordId(2, 1), result.getProperty("person"));
    assertEquals("Berlin", result.getProperty("city"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN 1:N: one upstream row matching two build-side rows produces
   * two merged rows, each with the upstream properties plus one build-side row.
   */
  @Test
  public void testHashJoinInnerJoinOneToMany() {
    var ctx = createCommandContext();
    var build1 = new ResultInternal(session);
    build1.setProperty("friend", new RecordId(1, 1));
    build1.setProperty("city", "Berlin");
    var build2 = new ResultInternal(session);
    build2.setProperty("friend", new RecordId(1, 1));
    build2.setProperty("city", "Paris");
    var buildPlan = createBuildPlan(ctx, build1, build2);

    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("friend", new RecordId(1, 1));
    upstreamRow.setProperty("person", new RecordId(2, 1));
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var r1 = stream.next(ctx);
    assertEquals(new RecordId(1, 1), r1.getProperty("friend"));
    assertEquals(new RecordId(2, 1), r1.getProperty("person"));
    assertEquals("Berlin", r1.getProperty("city"));

    assertTrue(stream.hasNext(ctx));
    var r2 = stream.next(ctx);
    assertEquals(new RecordId(1, 1), r2.getProperty("friend"));
    assertEquals(new RecordId(2, 1), r2.getProperty("person"));
    assertEquals("Paris", r2.getProperty("city"));

    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN with no match: upstream key not in build side → row dropped.
   */
  @Test
  public void testHashJoinInnerJoinNoMatch() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN with empty build side: all upstream rows are dropped
   * (no matches possible).
   */
  @Test
  public void testHashJoinInnerJoinEmptyBuildSide() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx);
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN with null key in upstream row → row dropped (cannot extract key).
   */
  @Test
  public void testHashJoinInnerJoinNullKeyDropsRow() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));
    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("other", "value"); // "friend" missing → null key
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN merge does not mutate the original upstream row — a fresh
   * ResultInternal is created for each merged result.
   */
  @Test
  public void testHashJoinInnerJoinDoesNotMutateUpstream() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("friend", new RecordId(1, 1));
    buildRow.setProperty("city", "Berlin");
    var buildPlan = createBuildPlan(ctx, buildRow);

    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("friend", new RecordId(1, 1));
    upstreamRow.setProperty("person", new RecordId(2, 1));
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertNotSame(upstreamRow, result);
    // Original upstream row must NOT have the build-side property
    assertNull(upstreamRow.getProperty("city"));
    // Original upstream properties must be untouched
    assertEquals(new RecordId(1, 1), upstreamRow.getProperty("friend"));
    assertEquals(new RecordId(2, 1), upstreamRow.getProperty("person"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN build-side rows are flattened: stored as plain ResultInternal,
   * not retaining MatchResultRow chains. Verified by checking all properties
   * are present on the merged result.
   */
  @Test
  public void testHashJoinInnerJoinBuildSideFlattened() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("friend", new RecordId(1, 1));
    buildRow.setProperty("city", "Berlin");
    buildRow.setProperty("age", 42);
    var buildPlan = createBuildPlan(ctx, buildRow);

    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals("Berlin", result.getProperty("city"));
    assertEquals(Integer.valueOf(42), result.getProperty("age"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN N:M: two upstream rows each matching different build-side rows
   * produce the correct number of merged rows (Cartesian product per key).
   */
  @Test
  public void testHashJoinInnerJoinManyToMany() {
    var ctx = createCommandContext();
    // Build side: two rows for key #1:1, one row for key #1:2
    var b1 = new ResultInternal(session);
    b1.setProperty("friend", new RecordId(1, 1));
    b1.setProperty("city", "Berlin");
    var b2 = new ResultInternal(session);
    b2.setProperty("friend", new RecordId(1, 1));
    b2.setProperty("city", "Paris");
    var b3 = new ResultInternal(session);
    b3.setProperty("friend", new RecordId(1, 2));
    b3.setProperty("city", "Rome");
    var buildPlan = createBuildPlan(ctx, b1, b2, b3);

    // Upstream: two rows with different keys
    var u1 = new ResultInternal(session);
    u1.setProperty("friend", new RecordId(1, 1));
    u1.setProperty("person", new RecordId(2, 1));
    var u2 = new ResultInternal(session);
    u2.setProperty("friend", new RecordId(1, 2));
    u2.setProperty("person", new RecordId(2, 2));
    var upstream = createUpstreamStep(ctx, u1, u2);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    // u1 matches b1,b2 → 2 rows; u2 matches b3 → 1 row; total = 3
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    assertEquals(3, results.size());
    assertEquals(new RecordId(1, 1), results.get(0).getProperty("friend"));
    assertEquals(new RecordId(2, 1), results.get(0).getProperty("person"));
    assertEquals("Berlin", results.get(0).getProperty("city"));
    assertEquals(new RecordId(1, 1), results.get(1).getProperty("friend"));
    assertEquals(new RecordId(2, 1), results.get(1).getProperty("person"));
    assertEquals("Paris", results.get(1).getProperty("city"));
    assertEquals(new RecordId(1, 2), results.get(2).getProperty("friend"));
    assertEquals(new RecordId(2, 2), results.get(2).getProperty("person"));
    assertEquals("Rome", results.get(2).getProperty("city"));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN property collision: when both upstream and build side have
   * the same non-shared property name, the upstream value takes precedence.
   */
  @Test
  public void testHashJoinInnerJoinUpstreamPropertyTakesPrecedence() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("friend", new RecordId(1, 1));
    buildRow.setProperty("score", 100);
    var buildPlan = createBuildPlan(ctx, buildRow);

    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("friend", new RecordId(1, 1));
    upstreamRow.setProperty("score", 999);
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    // Upstream value must win for non-shared properties
    assertEquals(Integer.valueOf(999), result.getProperty("score"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN with composite key: two shared aliases must both match
   * for the join to produce a merged row.
   */
  @Test
  public void testHashJoinInnerJoinCompositeKey() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("a", new RecordId(1, 1));
    buildRow.setProperty("b", new RecordId(2, 1));
    buildRow.setProperty("extra", "found");
    var buildPlan = createBuildPlan(ctx, buildRow);

    // Matches on both aliases
    var match = new ResultInternal(session);
    match.setProperty("a", new RecordId(1, 1));
    match.setProperty("b", new RecordId(2, 1));
    // Matches on only one alias — should not join
    var partial = new ResultInternal(session);
    partial.setProperty("a", new RecordId(1, 1));
    partial.setProperty("b", new RecordId(2, 99));
    var upstream = createUpstreamStep(ctx, match, partial);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("a", "b"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals("found", result.getProperty("extra"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN with non-RID key (String alias value) exercises the
   * Object[] key fallback path in buildHashMap and mergeMatches.
   */
  @Test
  public void testHashJoinInnerJoinNonRidKey() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("name", "Alice");
    buildRow.setProperty("city", "Berlin");
    var buildPlan = createBuildPlan(ctx, buildRow);

    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("name", "Alice");
    upstreamRow.setProperty("age", 30);
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("name"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals("Alice", result.getProperty("name"));
    assertEquals("Berlin", result.getProperty("city"));
    assertEquals(Integer.valueOf(30), result.getProperty("age"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN build-side null key: a build-side row missing the shared
   * alias is silently dropped; only non-null-key rows participate in join.
   */
  @Test
  public void testHashJoinInnerJoinBuildSideNullKeyDropped() {
    var ctx = createCommandContext();
    var nullRow = new ResultInternal(session);
    nullRow.setProperty("other", "irrelevant"); // "friend" missing → null key
    var goodRow = new ResultInternal(session);
    goodRow.setProperty("friend", new RecordId(1, 1));
    goodRow.setProperty("city", "Berlin");
    var buildPlan = createBuildPlan(ctx, nullRow, goodRow);

    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals("Berlin", result.getProperty("city"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN with empty upstream: build side is populated but no upstream
   * rows arrive, so the result stream must be empty. Verifies that the eager
   * buildHashMap call does not cause issues when no upstream rows follow.
   */
  @Test
  public void testHashJoinInnerJoinEmptyUpstream() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));
    var upstream = createUpstreamStep(ctx);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * INNER_JOIN with composite key where one alias is a RID and the other is
   * a String, exercising the Object[] fallback path in extractKey on both
   * build and probe sides.
   */
  @Test
  public void testHashJoinInnerJoinCompositeKeyMixedTypes() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("person", new RecordId(1, 1));
    buildRow.setProperty("tag", "sports");
    buildRow.setProperty("extra", "found");
    var buildPlan = createBuildPlan(ctx, buildRow);

    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("person", new RecordId(1, 1));
    upstreamRow.setProperty("tag", "sports");
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("person", "tag"),
        JoinMode.INNER_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals("found", result.getProperty("extra"));
    assertEquals(new RecordId(1, 1), result.getProperty("person"));
    assertEquals("sports", result.getProperty("tag"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Anti-join with non-RID alias values exercises the Object[] fallback path.
   */
  @Test
  public void testHashJoinAntiJoinNonRidSingleAlias() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("name", "Alice");
    var buildPlan = createBuildPlan(ctx, buildRow);

    var upstreamAlice = new ResultInternal(session);
    upstreamAlice.setProperty("name", "Alice");
    var upstreamBob = new ResultInternal(session);
    upstreamBob.setProperty("name", "Bob");
    var upstream = createUpstreamStep(ctx, upstreamAlice, upstreamBob);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("name"),
        JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    assertEquals("Bob", stream.next(ctx).getProperty("name"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Build-side row with null alias value is skipped during build phase.
   * Only the non-null build-side key (#1:1) is added to the hash set.
   */
  @Test
  public void testHashJoinBuildSideNullKeyIsSkipped() {
    var ctx = createCommandContext();
    var nullRow = new ResultInternal(session);
    nullRow.setProperty("other", "irrelevant"); // "friend" alias missing → null
    var buildPlan = createBuildPlan(ctx,
        nullRow,
        createRow("friend", new RecordId(1, 1)));
    var upstream = createUpstreamStep(ctx,
        createRow("friend", new RecordId(1, 1)),
        createRow("friend", new RecordId(1, 2)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    // Only #1:2 passes; #1:1 is in build set, null-key row was skipped
    assertEquals(new RecordId(1, 2), stream.next(ctx).getProperty("friend"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Multi-alias composite key with mixed RID and non-RID values exercises
   * the Object[] fallback path in extractKey for multi-alias keys.
   */
  @Test
  public void testHashJoinCompositeKeyMixedRidAndNonRid() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("friend", new RecordId(1, 1));
    buildRow.setProperty("label", "close");
    var buildPlan = createBuildPlan(ctx, buildRow);

    var matchRow = new ResultInternal(session);
    matchRow.setProperty("friend", new RecordId(1, 1));
    matchRow.setProperty("label", "close");
    var noMatchRow = new ResultInternal(session);
    noMatchRow.setProperty("friend", new RecordId(1, 1));
    noMatchRow.setProperty("label", "distant");
    var upstream = createUpstreamStep(ctx, matchRow, noMatchRow);

    var step = new HashJoinMatchStep(ctx, buildPlan,
        List.of("friend", "label"), JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    assertEquals("distant", stream.next(ctx).getProperty("label"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /** Verifies canBeCached delegates to the build plan — cacheable plan. */
  @Test
  public void testHashJoinCanBeCachedTrue() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    assertTrue(step.canBeCached());
  }

  /** Verifies canBeCached delegates to the build plan — non-cacheable plan. */
  @Test
  public void testHashJoinCanBeCachedFalse() {
    var ctx = createCommandContext();
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public boolean canBeCached() {
        return false;
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "+ NON-CACHEABLE";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    });
    var step = new HashJoinMatchStep(ctx, plan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    assertFalse(step.canBeCached());
  }

  /** Verifies prettyPrint output format for ANTI_JOIN mode. */
  @Test
  public void testHashJoinPrettyPrintAntiJoin() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx);
    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    var output = step.prettyPrint(0, 2);
    assertTrue(output.contains("+ HASH ANTI_JOIN on [friend]"));
    assertTrue(output.contains("("));
    assertTrue(output.contains(")"));
  }

  /** Verifies prettyPrint output format for SEMI_JOIN mode with multiple aliases. */
  @Test
  public void testHashJoinPrettyPrintSemiJoin() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx);
    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend", "tag"),
        JoinMode.SEMI_JOIN, false);
    var output = step.prettyPrint(0, 2);
    assertTrue(output.contains("+ HASH SEMI_JOIN on [friend, tag]"));
  }

  /** Verifies prettyPrint applies indentation when depth > 0. */
  @Test
  public void testHashJoinPrettyPrintWithDepth() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx);
    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    var output = step.prettyPrint(1, 3);
    assertTrue(output.startsWith("   ")); // 1 * 3 = 3 spaces
  }

  /** Verifies getSubSteps returns the build plan's steps. */
  @Test
  public void testHashJoinGetSubSteps() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx);
    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    var subSteps = step.getSubSteps();
    assertFalse(subSteps.isEmpty());
    assertEquals(buildPlan.getSteps().size(), subSteps.size());
  }

  /** Verifies copy() produces a distinct instance with equivalent state. */
  @Test
  public void testHashJoinCopy() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.ANTI_JOIN, false);
    var rawCopy = step.copy(ctx);

    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof HashJoinMatchStep);
    // Verify the copy preserves join mode and shared aliases via prettyPrint
    assertEquals(step.prettyPrint(0, 2),
        ((HashJoinMatchStep) rawCopy).prettyPrint(0, 2));
  }

  /** Verifies prettyPrint output format for INNER_JOIN mode. */
  @Test
  public void testHashJoinPrettyPrintInnerJoin() {
    var ctx = createCommandContext();
    var step = new HashJoinMatchStep(ctx, createBuildPlan(ctx), List.of("friend"),
        JoinMode.INNER_JOIN, false);
    var output = step.prettyPrint(0, 2);
    assertTrue(output.contains("+ HASH INNER_JOIN on [friend]"));
    assertTrue(output.contains("("));
    assertTrue(output.contains(")"));
  }

  /** Verifies prettyPrint for INNER_JOIN with multiple aliases. */
  @Test
  public void testHashJoinPrettyPrintInnerJoinMultiAlias() {
    var ctx = createCommandContext();
    var step = new HashJoinMatchStep(ctx, createBuildPlan(ctx),
        List.of("friend", "tag"), JoinMode.INNER_JOIN, false);
    var output = step.prettyPrint(0, 2);
    assertTrue(output.contains("+ HASH INNER_JOIN on [friend, tag]"));
    assertTrue(output.contains("("));
    assertTrue(output.contains(")"));
  }

  /** Verifies copy() preserves INNER_JOIN mode and shared aliases. */
  @Test
  public void testHashJoinCopyInnerJoin() {
    var ctx = createCommandContext();
    var buildPlan = createBuildPlan(ctx,
        createRow("friend", new RecordId(1, 1)));

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
        JoinMode.INNER_JOIN, false);
    var rawCopy = step.copy(ctx);

    assertNotSame(step, rawCopy);
    assertTrue(rawCopy instanceof HashJoinMatchStep);
    assertEquals(step.prettyPrint(0, 2),
        ((HashJoinMatchStep) rawCopy).prettyPrint(0, 2));
  }

  /**
   * Verifies canBeCached() delegates correctly for INNER_JOIN mode
   * (same behavior as ANTI_JOIN — mode-agnostic).
   */
  @Test
  public void testHashJoinCanBeCachedInnerJoin() {
    var ctx = createCommandContext();
    var step = new HashJoinMatchStep(ctx, createBuildPlan(ctx), List.of("friend"),
        JoinMode.INNER_JOIN, false);
    assertTrue(step.canBeCached());
  }

  // -- HashJoinMatchStep nested-loop fallback tests --

  /**
   * When the build-side hash set exceeds the runtime threshold, ANTI_JOIN
   * falls back to per-row nested-loop evaluation. Build side has 2 entries
   * but threshold is 1 → nestedLoopProbe is used instead.
   * Expected: friend=#1:1 is excluded (found in build), friend=#1:2 passes.
   */
  @Test
  public void testHashJoinAntiJoinFallbackNestedLoop() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
      var ctx = createCommandContext();
      var buildPlan = createBuildPlan(ctx,
          createRow("friend", new RecordId(1, 1)),
          createRow("friend", new RecordId(1, 2)));
      var upstream = createUpstreamStep(ctx,
          createRow("friend", new RecordId(1, 1)),
          createRow("friend", new RecordId(1, 3)));

      var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
          JoinMode.ANTI_JOIN, false);
      step.setPrevious(upstream);

      var stream = step.start(ctx);
      assertTrue(stream.hasNext(ctx));
      assertEquals(new RecordId(1, 3), stream.next(ctx).getProperty("friend"));
      assertFalse(stream.hasNext(ctx));
      stream.close(ctx);
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Nested-loop fallback for SEMI_JOIN: build side exceeds threshold, so
   * nestedLoopProbe is used. Only upstream rows with matching build-side
   * keys are kept.
   */
  @Test
  public void testHashJoinSemiJoinFallbackNestedLoop() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
      var ctx = createCommandContext();
      var buildPlan = createBuildPlan(ctx,
          createRow("friend", new RecordId(1, 1)),
          createRow("friend", new RecordId(1, 2)));
      var upstream = createUpstreamStep(ctx,
          createRow("friend", new RecordId(1, 1)),
          createRow("friend", new RecordId(1, 3)));

      var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
          JoinMode.SEMI_JOIN, false);
      step.setPrevious(upstream);

      var stream = step.start(ctx);
      assertTrue(stream.hasNext(ctx));
      assertEquals(new RecordId(1, 1), stream.next(ctx).getProperty("friend"));
      assertFalse(stream.hasNext(ctx));
      stream.close(ctx);
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Nested-loop fallback for INNER_JOIN: build map exceeds threshold, so
   * nestedLoopInnerJoin is used. Upstream rows with matching keys are merged
   * with build-side properties.
   */
  @Test
  public void testHashJoinInnerJoinFallbackNestedLoop() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
      var ctx = createCommandContext();
      var buildRow1 = new ResultInternal(session);
      buildRow1.setProperty("friend", new RecordId(1, 1));
      buildRow1.setProperty("extra", "fromBuild");
      var buildRow2 = new ResultInternal(session);
      buildRow2.setProperty("friend", new RecordId(1, 2));
      buildRow2.setProperty("extra", "fromBuild2");
      var buildPlan = createBuildPlan(ctx, buildRow1, buildRow2);

      var upstreamRow = new ResultInternal(session);
      upstreamRow.setProperty("friend", new RecordId(1, 1));
      upstreamRow.setProperty("person", "Alice");
      var upstream = createUpstreamStep(ctx, upstreamRow);

      var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
          JoinMode.INNER_JOIN, false);
      step.setPrevious(upstream);

      var stream = step.start(ctx);
      assertTrue(stream.hasNext(ctx));
      var result = stream.next(ctx);
      assertEquals(new RecordId(1, 1), result.getProperty("friend"));
      assertEquals("Alice", result.getProperty("person"));
      assertEquals("fromBuild", result.getProperty("extra"));
      assertFalse(stream.hasNext(ctx));
      stream.close(ctx);
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Nested-loop ANTI_JOIN fallback with a null key in the upstream: rows with
   * null keys are conservatively kept (same as hash path behavior).
   */
  @Test
  public void testHashJoinAntiJoinFallbackNullKeyKeepsRow() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
      var ctx = createCommandContext();
      var buildPlan = createBuildPlan(ctx,
          createRow("friend", new RecordId(1, 1)),
          createRow("friend", new RecordId(1, 2)));

      // Upstream row missing the "friend" property → null key
      var nullRow = new ResultInternal(session);
      nullRow.setProperty("other", "value");
      var upstream = createUpstreamStep(ctx, nullRow);

      var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
          JoinMode.ANTI_JOIN, false);
      step.setPrevious(upstream);

      var stream = step.start(ctx);
      assertTrue("null key should be kept in ANTI_JOIN fallback",
          stream.hasNext(ctx));
      assertEquals("value", stream.next(ctx).getProperty("other"));
      assertFalse(stream.hasNext(ctx));
      stream.close(ctx);
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Nested-loop SEMI_JOIN fallback with a null key: rows with null keys
   * are discarded (same as hash path behavior).
   */
  @Test
  public void testHashJoinSemiJoinFallbackNullKeyDiscardsRow() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
      var ctx = createCommandContext();
      var buildPlan = createBuildPlan(ctx,
          createRow("friend", new RecordId(1, 1)),
          createRow("friend", new RecordId(1, 2)));

      var nullRow = new ResultInternal(session);
      nullRow.setProperty("other", "value");
      var upstream = createUpstreamStep(ctx, nullRow);

      var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
          JoinMode.SEMI_JOIN, false);
      step.setPrevious(upstream);

      var stream = step.start(ctx);
      assertFalse("null key should be discarded in SEMI_JOIN fallback",
          stream.hasNext(ctx));
      stream.close(ctx);
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Nested-loop INNER_JOIN fallback with a null key: upstream row is
   * dropped (returns empty stream).
   */
  @Test
  public void testHashJoinInnerJoinFallbackNullKeyDropsRow() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
      var ctx = createCommandContext();
      var buildPlan = createBuildPlan(ctx,
          createRow("friend", new RecordId(1, 1)),
          createRow("friend", new RecordId(1, 2)));

      var nullRow = new ResultInternal(session);
      nullRow.setProperty("other", "value");
      var upstream = createUpstreamStep(ctx, nullRow);

      var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
          JoinMode.INNER_JOIN, false);
      step.setPrevious(upstream);

      var stream = step.start(ctx);
      assertFalse("null key should produce no results in INNER_JOIN fallback",
          stream.hasNext(ctx));
      stream.close(ctx);
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Nested-loop INNER_JOIN fallback with no match: upstream key doesn't
   * match any build-side key, so no merged rows are produced.
   */
  @Test
  public void testHashJoinInnerJoinFallbackNoMatch() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
      var ctx = createCommandContext();
      var buildPlan = createBuildPlan(ctx,
          createRow("friend", new RecordId(1, 1)),
          createRow("friend", new RecordId(1, 2)));

      var upstream = createUpstreamStep(ctx,
          createRow("friend", new RecordId(1, 99)));

      var step = new HashJoinMatchStep(ctx, buildPlan, List.of("friend"),
          JoinMode.INNER_JOIN, false);
      step.setPrevious(upstream);

      var stream = step.start(ctx);
      assertFalse("non-matching key should produce no results",
          stream.hasNext(ctx));
      stream.close(ctx);
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Multi-alias extractKey: null value in the middle of the array
   * (second alias of three is null) → key is null, row handled according
   * to join mode.
   */
  @Test
  public void testHashJoinCompositeKeyNullInMiddle() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("a", new RecordId(1, 1));
    buildRow.setProperty("b", new RecordId(2, 2));
    buildRow.setProperty("c", new RecordId(3, 3));
    var buildPlan = createBuildPlan(ctx, buildRow);

    // Upstream row has null for middle alias "b"
    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("a", new RecordId(1, 1));
    // "b" is missing → null
    upstreamRow.setProperty("c", new RecordId(3, 3));
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("a", "b", "c"),
        JoinMode.ANTI_JOIN, false);
    step.setPrevious(upstream);

    // Null key in ANTI_JOIN → row is kept (conservative)
    var stream = step.start(ctx);
    assertTrue(stream.hasNext(ctx));
    assertEquals(new RecordId(1, 1), stream.next(ctx).getProperty("a"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Multi-alias extractKey with non-RID value after a RID: exercises the
   * Object[] fallback path where the third alias has a null value, causing
   * extractKey to return null.
   */
  @Test
  public void testHashJoinCompositeKeyNullAfterNonRid() {
    var ctx = createCommandContext();
    var buildRow = new ResultInternal(session);
    buildRow.setProperty("a", new RecordId(1, 1));
    buildRow.setProperty("b", "text");
    buildRow.setProperty("c", "value");
    var buildPlan = createBuildPlan(ctx, buildRow);

    // Upstream: first is RID, second is non-RID, third is null
    var upstreamRow = new ResultInternal(session);
    upstreamRow.setProperty("a", new RecordId(1, 1));
    upstreamRow.setProperty("b", "text");
    // "c" missing → null → extractKey returns null
    var upstream = createUpstreamStep(ctx, upstreamRow);

    var step = new HashJoinMatchStep(ctx, buildPlan, List.of("a", "b", "c"),
        JoinMode.SEMI_JOIN, false);
    step.setPrevious(upstream);

    // Null key in SEMI_JOIN → row is discarded
    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  // -- JoinKey tests --

  /** Verifies JoinKey.ofRid equality: same collection+position → equal. */
  @Test
  public void testJoinKeyOfRidEqualsSameValues() {
    var rid1 = new RecordId(10, 42);
    var rid2 = new RecordId(10, 42);
    var key1 = JoinKey.ofRid(rid1);
    var key2 = JoinKey.ofRid(rid2);

    assertEquals(key1, key2);
    assertEquals(key1.hashCode(), key2.hashCode());
  }

  /** Verifies JoinKey.ofRid inequality: different values → not equal. */
  @Test
  public void testJoinKeyOfRidNotEqualDifferentValues() {
    var key1 = JoinKey.ofRid(new RecordId(10, 42));
    var key2 = JoinKey.ofRid(new RecordId(10, 99));

    assertFalse(key1.equals(key2));
  }

  /** Verifies JoinKey.ofRid self-equality via identity. */
  @Test
  public void testJoinKeyOfRidSelfEquality() {
    var key = JoinKey.ofRid(new RecordId(5, 1));
    assertEquals(key, key);
  }

  /** Verifies JoinKey.ofRid is not equal to null or unrelated object. */
  @Test
  public void testJoinKeyOfRidNotEqualToNullOrOther() {
    var key = JoinKey.ofRid(new RecordId(5, 1));
    assertFalse(key.equals(null));
    assertFalse(key.equals("not a JoinKey"));
  }

  /** Verifies JoinKey.ofRids equality: same RID arrays → equal. */
  @Test
  public void testJoinKeyOfRidsEquals() {
    var rids1 = new RID[] {new RecordId(1, 10), new RecordId(2, 20)};
    var rids2 = new RID[] {new RecordId(1, 10), new RecordId(2, 20)};
    var key1 = JoinKey.ofRids(rids1);
    var key2 = JoinKey.ofRids(rids2);

    assertEquals(key1, key2);
    assertEquals(key1.hashCode(), key2.hashCode());
  }

  /** Verifies JoinKey.ofRids inequality: different RID arrays → not equal. */
  @Test
  public void testJoinKeyOfRidsNotEqual() {
    var rids1 = new RID[] {new RecordId(1, 10), new RecordId(2, 20)};
    var rids2 = new RID[] {new RecordId(1, 10), new RecordId(3, 30)};
    var key1 = JoinKey.ofRids(rids1);
    var key2 = JoinKey.ofRids(rids2);

    assertFalse(key1.equals(key2));
  }

  /** Verifies JoinKey.ofObjects equality: same object values → equal. */
  @Test
  public void testJoinKeyOfObjectsEquals() {
    var key1 = JoinKey.ofObjects(new Object[] {"hello", 42});
    var key2 = JoinKey.ofObjects(new Object[] {"hello", 42});

    assertEquals(key1, key2);
    assertEquals(key1.hashCode(), key2.hashCode());
  }

  /** Verifies JoinKey.ofObjects inequality: different values → not equal. */
  @Test
  public void testJoinKeyOfObjectsNotEqual() {
    var key1 = JoinKey.ofObjects(new Object[] {"hello", 42});
    var key2 = JoinKey.ofObjects(new Object[] {"world", 42});

    assertFalse(key1.equals(key2));
  }

  /** Verifies keys of different kinds are never equal (symmetric). */
  @Test
  public void testJoinKeyDifferentKindsNotEqual() {
    var rid1 = new RecordId(1, 10);
    var rid2 = new RecordId(2, 20);
    var singleKey = JoinKey.ofRid(rid1);
    var arrayKey = JoinKey.ofRids(new RID[] {rid1, rid2});
    var objectKey = JoinKey.ofObjects(new Object[] {rid1, rid2});

    // Both directions for symmetry
    assertFalse(singleKey.equals(arrayKey));
    assertFalse(arrayKey.equals(singleKey));
    assertFalse(singleKey.equals(objectKey));
    assertFalse(objectKey.equals(singleKey));
    assertFalse(arrayKey.equals(objectKey));
    assertFalse(objectKey.equals(arrayKey));
  }

  /** Verifies JoinKey.toString() includes RID values for all three kinds. */
  @Test
  public void testJoinKeyToStringAllKinds() {
    var ridKey = JoinKey.ofRid(new RecordId(10, 42));
    assertTrue(ridKey.toString().contains("#10:42"));

    var ridsKey = JoinKey.ofRids(
        new RID[] {new RecordId(1, 10), new RecordId(2, 20)});
    assertTrue(ridsKey.toString().contains("#1:10"));
    assertTrue(ridsKey.toString().contains("#2:20"));

    var objKey = JoinKey.ofObjects(new Object[] {"hello", 42});
    assertTrue(objKey.toString().contains("hello"));
    assertTrue(objKey.toString().contains("42"));
  }

  /** Verifies JoinKey works correctly as a HashMap key (core use case). */
  @Test
  public void testJoinKeyWorksAsHashMapKey() {
    var map = new java.util.HashMap<JoinKey, String>();

    // Single RID
    var ridKey = JoinKey.ofRid(new RecordId(10, 42));
    map.put(ridKey, "rid");
    assertEquals("rid", map.get(JoinKey.ofRid(new RecordId(10, 42))));

    // RID array
    var ridsKey = JoinKey.ofRids(
        new RID[] {new RecordId(1, 10), new RecordId(2, 20)});
    map.put(ridsKey, "rids");
    assertEquals("rids", map.get(
        JoinKey.ofRids(new RID[] {new RecordId(1, 10), new RecordId(2, 20)})));

    // Object array
    var objKey = JoinKey.ofObjects(new Object[] {"hello", 42});
    map.put(objKey, "obj");
    assertEquals("obj", map.get(JoinKey.ofObjects(new Object[] {"hello", 42})));
  }

  /** Verifies defensive copy: mutating source array does not corrupt the key. */
  @Test
  public void testJoinKeyOfRidsDefensiveCopy() {
    var rids = new RID[] {new RecordId(1, 10), new RecordId(2, 20)};
    var key = JoinKey.ofRids(rids);

    // Mutate the source array after key creation
    rids[1] = new RecordId(9, 99);

    // Key must still match the original values, not the mutated array
    var freshKey = JoinKey.ofRids(
        new RID[] {new RecordId(1, 10), new RecordId(2, 20)});
    assertEquals(key, freshKey);
    assertEquals(key.hashCode(), freshKey.hashCode());
  }

  /** Verifies ofObjects handles null elements correctly. */
  @Test
  public void testJoinKeyOfObjectsWithNullElements() {
    var key1 = JoinKey.ofObjects(new Object[] {null, "value"});
    var key2 = JoinKey.ofObjects(new Object[] {null, "value"});

    assertEquals(key1, key2);
    assertEquals(key1.hashCode(), key2.hashCode());

    var key3 = JoinKey.ofObjects(new Object[] {"value", null});
    assertFalse(key1.equals(key3));
  }

  // -- MatchReverseEdgeTraverser tests --

  /**
   * Verifies that the reverse traverser constructor correctly swaps the aliases:
   * startingPointAlias comes from edge.in.alias, endPointAlias from edge.out.alias.
   */
  @Test
  public void testReverseEdgeTraverserConstructor() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);

    // edge.in.alias = "b" (target in pattern → starting point in reverse)
    assertEquals("b", traverser.getStartingPointAlias());
    // edge.out.alias = "a" (source in pattern → endpoint in reverse)
    assertEquals("a", traverser.getEndpointAlias());
  }

  /**
   * Verifies that the constructor asserts when lastUpstreamRecord is null.
   * Covers the false branch of the upstream record null check.
   */
  @Test(expected = AssertionError.class)
  public void testReverseEdgeTraverserConstructorNullUpstream() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    new MatchReverseEdgeTraverser(null, edgeTraversal);
  }

  /**
   * Verifies that the constructor asserts when edge.in.alias is null,
   * which would result in a null startingPointAlias.
   */
  @Test(expected = AssertionError.class)
  public void testReverseEdgeTraverserConstructorNullStartingAlias() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = null; // null in-alias → null startingPointAlias
    var item = new SQLMatchPathItem(-1);
    nodeA.addEdge(item, nodeB);
    var patternEdge = nodeA.out.iterator().next();
    var edgeTraversal = new EdgeTraversal(patternEdge, false);
    var sourceResult = new ResultInternal(session);

    new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
  }

  /**
   * Verifies that the constructor asserts when edge.out.alias is null,
   * which would result in a null endPointAlias.
   */
  @Test(expected = AssertionError.class)
  public void testReverseEdgeTraverserConstructorNullEndpointAlias() {
    var nodeA = new PatternNode();
    nodeA.alias = null; // null out-alias → null endPointAlias
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var item = new SQLMatchPathItem(-1);
    nodeA.addEdge(item, nodeB);
    var patternEdge = nodeA.out.iterator().next();
    var edgeTraversal = new EdgeTraversal(patternEdge, false);
    var sourceResult = new ResultInternal(session);

    new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
  }

  /**
   * Verifies that targetClassName delegates to edge.getLeftClass(),
   * returning the planner-provided class constraint for the reverse target.
   */
  @Test
  public void testReverseEdgeTraverserTargetClassName() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    edgeTraversal.setLeftClass("Person");
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
    var ctx = createCommandContext();
    // targetClassName returns edge.getLeftClass(), ignoring the item parameter
    assertEquals("Person", traverser.targetClassName(null, ctx));
  }

  /**
   * Verifies that targetRid delegates to edge.getLeftRid(),
   * returning the planner-provided RID constraint for the reverse target.
   */
  @Test
  public void testReverseEdgeTraverserTargetRid() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    var rid = new SQLRid(-1);
    edgeTraversal.setLeftRid(rid);
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
    var ctx = createCommandContext();
    assertEquals(rid, traverser.targetRid(null, ctx));
  }

  /**
   * Verifies that getTargetFilter delegates to edge.getLeftFilter(),
   * returning the planner-provided WHERE clause for the reverse target.
   */
  @Test
  public void testReverseEdgeTraverserGetTargetFilter() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    var filter = new SQLWhereClause(-1);
    edgeTraversal.setLeftFilter(filter);
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
    assertEquals(filter, traverser.getTargetFilter(null));
  }

  /**
   * Verifies that traversePatternEdge calls executeReverse() on the path item's
   * method and returns an execution stream. Uses a vertex with no incoming edges
   * so the reverse of out() (i.e. in()) yields an empty stream.
   */
  @Test
  public void testReverseEdgeTraverserTraversePatternEdge() {
    session.begin();
    session.createClassIfNotExist("V");
    session.createClassIfNotExist("E");
    var vertex = session.newVertex("V");
    session.commit();

    session.begin();
    try {
      var traverser = createReverseTraverserWithOutPath();
      var startResult = new ResultInternal(session, vertex);
      var ctx = createCommandContext();
      // Reverse of out('E') = in('E'); vertex has no incoming edges → empty stream
      var stream = traverser.traversePatternEdge(startResult, ctx);
      assertFalse(stream.hasNext(ctx));
    } finally {
      session.commit();
    }
  }

  /**
   * Verifies that traversePatternEdge asserts when startingPoint is null.
   * Covers the false branch of the starting-point null check.
   */
  @Test(expected = AssertionError.class)
  public void testReverseEdgeTraverserTraversePatternEdgeNullStartingPoint() {
    var traverser = createReverseTraverserWithOutPath();
    var ctx = createCommandContext();
    traverser.traversePatternEdge(null, ctx);
  }

  // NOTE: getStartingPointAlias() and getEndpointAlias() are already verified
  // by testReverseEdgeTraverserConstructor() above.

  // -- MatchFieldTraverser tests --

  /**
   * Verifies that MatchFieldTraverser constructed with a raw SQLFieldMatchPathItem
   * (sub-traversal mode) correctly evaluates the field expression on the starting
   * point record. When the field resolves to a ResultInternal, the traverser should
   * produce a singleton stream containing that result.
   */
  @Test
  public void testFieldTraverserSubTraversalResolvesFieldToResult() {
    var ctx = createCommandContext();

    // Build a SQLFieldMatchPathItem that resolves property "address"
    var fieldItem = new SQLFieldMatchPathItem(-1);
    fieldItem.getExp().setIdentifier(new SQLIdentifier("address"));

    // Create a starting point with property "address" pointing to a result record
    var linkedRecord = new ResultInternal(session);
    linkedRecord.setProperty("city", "Berlin");
    var startingPoint = new ResultInternal(session);
    startingPoint.setProperty("address", linkedRecord);

    // Construct via (Result, SQLMatchPathItem) — the sub-traversal constructor
    var traverser = new MatchFieldTraverser(startingPoint, fieldItem);

    // traversePatternEdge should resolve "address" → linkedRecord → singleton stream
    var stream = traverser.traversePatternEdge(startingPoint, ctx);
    assertTrue(stream.hasNext(ctx));
    var result = stream.next(ctx);
    assertEquals("Berlin", result.<String>getProperty("city"));
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that MatchFieldTraverser constructed with a raw SQLFieldMatchPathItem
   * returns an empty stream when the field resolves to null (property not present).
   * This is the expected behavior for traversing a missing/null link.
   */
  @Test
  public void testFieldTraverserSubTraversalNullFieldYieldsEmptyStream() {
    var ctx = createCommandContext();

    var fieldItem = new SQLFieldMatchPathItem(-1);
    fieldItem.getExp().setIdentifier(new SQLIdentifier("missingField"));

    var startingPoint = new ResultInternal(session);
    // "missingField" not set → getExp().execute() returns null → empty stream

    var traverser = new MatchFieldTraverser(startingPoint, fieldItem);
    var stream = traverser.traversePatternEdge(startingPoint, ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that MatchFieldTraverser's traversePatternEdge correctly saves and
   * restores the $current context variable, even when constructed via the
   * sub-traversal constructor.
   */
  @Test
  public void testFieldTraverserSubTraversalRestoresCurrentVariable() {
    var ctx = createCommandContext();
    var sentinel = "previousCurrentValue";
    ctx.setVariable("$current", sentinel);

    var fieldItem = new SQLFieldMatchPathItem(-1);
    fieldItem.getExp().setIdentifier(new SQLIdentifier("someField"));

    var startingPoint = new ResultInternal(session);
    var traverser = new MatchFieldTraverser(startingPoint, fieldItem);
    var stream = traverser.traversePatternEdge(startingPoint, ctx);
    stream.close(ctx);

    // $current must be restored to the sentinel after traversal
    assertEquals(sentinel, ctx.getVariable("$current"));
  }

  // -- MatchMultiEdgeTraverser.toOResultInternal tests --

  /** Verifies toOResultInternal passes through a ResultInternal unchanged. */
  @Test
  public void testToOResultInternalResultInternal() {
    var result = new ResultInternal(session);
    result.setProperty("key", "value");
    var converted = MatchMultiEdgeTraverser.toOResultInternal(session, result);
    assertSame(result, converted);
    assertEquals("value", converted.getProperty("key"));
  }

  /** Verifies toOResultInternal wraps an Identifiable into a ResultInternal. */
  @Test
  public void testToOResultInternalIdentifiable() {
    session.createClassIfNotExist("V");

    session.begin();
    try {
      var vertex = session.newVertex("V");
      var converted = MatchMultiEdgeTraverser.toOResultInternal(session, vertex);
      assertNotNull(converted);
      assertTrue(converted.isEntity());
    } finally {
      session.rollback();
    }
  }

  /** Verifies toOResultInternal wraps an Edge into a ResultInternal. */
  @Test
  public void testToOResultInternalEdge() {
    session.createClassIfNotExist("V");
    session.createClassIfNotExist("TestEdge", "E");

    session.begin();
    try {
      var v1 = session.newVertex("V");
      var v2 = session.newVertex("V");
      var edge = v1.addEdge(v2, "TestEdge");
      var converted = MatchMultiEdgeTraverser.toOResultInternal(session, edge);
      assertNotNull(converted);
    } finally {
      session.rollback();
    }
  }

  /** Verifies toOResultInternal throws CommandExecutionException for unrecognized type. */
  @Test(expected = CommandExecutionException.class)
  public void testToOResultInternalUnrecognizedType() {
    MatchMultiEdgeTraverser.toOResultInternal(session, "unknown-type");
  }

  /** Verifies toOResultInternal asserts when session is null. */
  @Test(expected = AssertionError.class)
  public void testToOResultInternalNullSession() {
    MatchMultiEdgeTraverser.toOResultInternal(null, new ResultInternal(session));
  }

  /** Verifies toOResultInternal asserts when object is null. */
  @Test(expected = AssertionError.class)
  public void testToOResultInternalNullObject() {
    MatchMultiEdgeTraverser.toOResultInternal(session, null);
  }

  // -- MatchMultiEdgeTraverser.matchesCondition tests --

  /** Verifies matchesCondition returns true when filter is null. */
  @Test
  public void testMultiMatchesConditionNullFilter() {
    var result = new ResultInternal(session);
    var ctx = createCommandContext();
    assertTrue(MatchMultiEdgeTraverser.matchesCondition(result, null, ctx));
  }

  /** Verifies matchesCondition returns true when filter's WHERE clause is null. */
  @Test
  public void testMultiMatchesConditionNullWhere() {
    var result = new ResultInternal(session);
    var ctx = createCommandContext();
    // Filter with no WHERE clause items — getFilter() returns null
    var filter = new SQLMatchFilter(-1);
    assertTrue(MatchMultiEdgeTraverser.matchesCondition(result, filter, ctx));
  }

  /** Verifies matchesCondition returns true when WHERE clause evaluates to true. */
  @Test
  public void testMultiMatchesConditionPassingFilter() {
    var result = new ResultInternal(session);
    var ctx = createCommandContext();
    var filter = new SQLMatchFilter(-1);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(SQLBooleanExpression.TRUE);
    filter.setFilter(where);
    assertTrue(MatchMultiEdgeTraverser.matchesCondition(result, filter, ctx));
  }

  /** Verifies matchesCondition returns false when WHERE clause evaluates to false. */
  @Test
  public void testMultiMatchesConditionFailingFilter() {
    var result = new ResultInternal(session);
    var ctx = createCommandContext();
    var filter = new SQLMatchFilter(-1);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(SQLBooleanExpression.FALSE);
    filter.setFilter(where);
    assertFalse(MatchMultiEdgeTraverser.matchesCondition(result, filter, ctx));
  }

  /** Verifies matchesCondition asserts when candidate record is null. */
  @Test(expected = AssertionError.class)
  public void testMultiMatchesConditionNullRecord() {
    var ctx = createCommandContext();
    MatchMultiEdgeTraverser.matchesCondition(null, null, ctx);
  }

  // -- MatchMultiEdgeTraverser.dispatchTraversalResult tests --

  /**
   * Verifies dispatchTraversalResult handles a Collection of ResultInternal
   * by converting and filtering each element.
   */
  @Test
  public void testDispatchCollectionOfResultInternal() {
    var ctx = createCommandContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("n", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("n", 2);
    List<ResultInternal> collection = List.of(r1, r2);
    List<ResultInternal> rightSide = new ArrayList<>();

    // null filter means all pass
    MatchMultiEdgeTraverser.dispatchTraversalResult(
        collection, session, null, ctx, rightSide);
    assertEquals(2, rightSide.size());
  }

  /**
   * Verifies dispatchTraversalResult handles a Collection with Identifiable elements,
   * converting them to ResultInternal via toOResultInternal.
   */
  @Test
  public void testDispatchCollectionOfIdentifiable() {
    session.createClassIfNotExist("V");

    session.begin();
    try {
      var ctx = createCommandContext();
      var v1 = session.newVertex("V");
      var v2 = session.newVertex("V");
      List<Object> collection = List.of(v1, v2);
      List<ResultInternal> rightSide = new ArrayList<>();

      MatchMultiEdgeTraverser.dispatchTraversalResult(
          collection, session, null, ctx, rightSide);
      assertEquals(2, rightSide.size());
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies dispatchTraversalResult handles a Collection with a filter that
   * rejects all elements.
   */
  @Test
  public void testDispatchCollectionWithRejectingFilter() {
    var ctx = createCommandContext();
    var r1 = new ResultInternal(session);
    List<ResultInternal> collection = List.of(r1);
    List<ResultInternal> rightSide = new ArrayList<>();

    var filter = new SQLMatchFilter(-1);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(SQLBooleanExpression.FALSE);
    filter.setFilter(where);

    MatchMultiEdgeTraverser.dispatchTraversalResult(
        collection, session, filter, ctx, rightSide);
    assertTrue(rightSide.isEmpty());
  }

  /** Verifies dispatchTraversalResult handles a single Identifiable. */
  @Test
  public void testDispatchSingleIdentifiable() {
    session.createClassIfNotExist("V");

    session.begin();
    try {
      var ctx = createCommandContext();
      var vertex = session.newVertex("V");
      List<ResultInternal> rightSide = new ArrayList<>();

      MatchMultiEdgeTraverser.dispatchTraversalResult(
          vertex, session, null, ctx, rightSide);
      assertEquals(1, rightSide.size());
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies dispatchTraversalResult handles a single Identifiable with a filter
   * that rejects it.
   */
  @Test
  public void testDispatchSingleIdentifiableRejected() {
    session.createClassIfNotExist("V");

    session.begin();
    try {
      var ctx = createCommandContext();
      var vertex = session.newVertex("V");
      List<ResultInternal> rightSide = new ArrayList<>();

      var filter = new SQLMatchFilter(-1);
      var where = new SQLWhereClause(-1);
      where.setBaseExpression(SQLBooleanExpression.FALSE);
      filter.setFilter(where);

      MatchMultiEdgeTraverser.dispatchTraversalResult(
          vertex, session, filter, ctx, rightSide);
      assertTrue(rightSide.isEmpty());
    } finally {
      session.rollback();
    }
  }

  /** Verifies dispatchTraversalResult handles a single Edge. */
  @Test
  public void testDispatchSingleEdge() {
    session.createClassIfNotExist("V");
    session.createClassIfNotExist("DispatchRelEdge", "E");

    session.begin();
    try {
      var ctx = createCommandContext();
      var v1 = session.newVertex("V");
      var v2 = session.newVertex("V");
      var edge = v1.addEdge(v2, "DispatchRelEdge");
      List<ResultInternal> rightSide = new ArrayList<>();

      MatchMultiEdgeTraverser.dispatchTraversalResult(
          edge, session, null, ctx, rightSide);
      assertEquals(1, rightSide.size());
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies dispatchTraversalResult handles a single Edge with a filter
   * that rejects it.
   */
  @Test
  public void testDispatchSingleEdgeRejected() {
    session.createClassIfNotExist("V");
    session.createClassIfNotExist("DispatchRelEdge2", "E");

    session.begin();
    try {
      var ctx = createCommandContext();
      var v1 = session.newVertex("V");
      var v2 = session.newVertex("V");
      var edge = v1.addEdge(v2, "DispatchRelEdge2");
      List<ResultInternal> rightSide = new ArrayList<>();

      var filter = new SQLMatchFilter(-1);
      var where = new SQLWhereClause(-1);
      where.setBaseExpression(SQLBooleanExpression.FALSE);
      filter.setFilter(where);

      MatchMultiEdgeTraverser.dispatchTraversalResult(
          edge, session, filter, ctx, rightSide);
      assertTrue(rightSide.isEmpty());
    } finally {
      session.rollback();
    }
  }

  /** Verifies dispatchTraversalResult handles a single ResultInternal. */
  @Test
  public void testDispatchSingleResultInternal() {
    var ctx = createCommandContext();
    var result = new ResultInternal(session);
    result.setProperty("key", "val");
    List<ResultInternal> rightSide = new ArrayList<>();

    MatchMultiEdgeTraverser.dispatchTraversalResult(
        result, session, null, ctx, rightSide);
    assertEquals(1, rightSide.size());
    assertSame(result, rightSide.get(0));
  }

  /**
   * Verifies dispatchTraversalResult handles a single ResultInternal with a
   * filter that rejects it.
   */
  @Test
  public void testDispatchSingleResultInternalRejected() {
    var ctx = createCommandContext();
    var result = new ResultInternal(session);
    List<ResultInternal> rightSide = new ArrayList<>();

    var filter = new SQLMatchFilter(-1);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(SQLBooleanExpression.FALSE);
    filter.setFilter(where);

    MatchMultiEdgeTraverser.dispatchTraversalResult(
        result, session, filter, ctx, rightSide);
    assertTrue(rightSide.isEmpty());
  }

  /**
   * Verifies dispatchTraversalResult handles an Iterable that is not a Collection.
   * Covers the {@code instanceof Iterable} branch (but not Collection).
   */
  @Test
  public void testDispatchNonCollectionIterable() {
    var ctx = createCommandContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("n", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("n", 2);
    // Lambda-based Iterable is NOT a Collection
    Iterable<ResultInternal> iterable = () -> List.of(r1, r2).iterator();
    List<ResultInternal> rightSide = new ArrayList<>();

    MatchMultiEdgeTraverser.dispatchTraversalResult(
        iterable, session, null, ctx, rightSide);
    assertEquals(2, rightSide.size());
  }

  /**
   * Verifies dispatchTraversalResult handles an Iterable with a filter that
   * rejects elements.
   */
  @Test
  public void testDispatchNonCollectionIterableWithRejectingFilter() {
    var ctx = createCommandContext();
    var r1 = new ResultInternal(session);
    Iterable<ResultInternal> iterable = () -> List.of(r1).iterator();
    List<ResultInternal> rightSide = new ArrayList<>();

    var filter = new SQLMatchFilter(-1);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(SQLBooleanExpression.FALSE);
    filter.setFilter(where);

    MatchMultiEdgeTraverser.dispatchTraversalResult(
        iterable, session, filter, ctx, rightSide);
    assertTrue(rightSide.isEmpty());
  }

  /**
   * Verifies dispatchTraversalResult handles a raw Iterator.
   * Covers the {@code instanceof Iterator} branch.
   */
  @Test
  public void testDispatchIterator() {
    var ctx = createCommandContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("n", 1);
    Iterator<ResultInternal> iterator = List.of(r1).iterator();
    List<ResultInternal> rightSide = new ArrayList<>();

    MatchMultiEdgeTraverser.dispatchTraversalResult(
        iterator, session, null, ctx, rightSide);
    assertEquals(1, rightSide.size());
  }

  /**
   * Verifies dispatchTraversalResult handles a raw Iterator with a filter that
   * rejects the element.
   */
  @Test
  public void testDispatchIteratorWithRejectingFilter() {
    var ctx = createCommandContext();
    var r1 = new ResultInternal(session);
    Iterator<ResultInternal> iterator = List.of(r1).iterator();
    List<ResultInternal> rightSide = new ArrayList<>();

    var filter = new SQLMatchFilter(-1);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(SQLBooleanExpression.FALSE);
    filter.setFilter(where);

    MatchMultiEdgeTraverser.dispatchTraversalResult(
        iterator, session, filter, ctx, rightSide);
    assertTrue(rightSide.isEmpty());
  }

  /**
   * Verifies dispatchTraversalResult handles null input by adding nothing to the
   * right side (no branch matches for null).
   */
  @Test
  public void testDispatchNull() {
    var ctx = createCommandContext();
    List<ResultInternal> rightSide = new ArrayList<>();

    MatchMultiEdgeTraverser.dispatchTraversalResult(
        null, session, null, ctx, rightSide);
    assertTrue(rightSide.isEmpty());
  }

  /** Verifies dispatchTraversalResult asserts when rightSide is null. */
  @Test(expected = AssertionError.class)
  public void testDispatchNullRightSide() {
    var ctx = createCommandContext();
    MatchMultiEdgeTraverser.dispatchTraversalResult(
        new ResultInternal(session), session, null, ctx, null);
  }

  // -- MatchMultiEdgeTraverser.traversePatternEdge integration tests --

  /**
   * Verifies the multi-edge traverser performs a basic two-step pipeline through
   * real graph edges: A -[TestE]-> B -[TestE]-> C. The pipeline has two
   * out('TestE') sub-items, starting from A and ending at C.
   */
  @Test
  public void testMultiEdgeTraverserBasicPipeline() {
    session.createClassIfNotExist("MTestV", "V");
    session.createClassIfNotExist("MTestE", "E");

    session.begin();
    try {
      var ctx = createCommandContext();
      var a = session.newVertex("MTestV");
      a.setProperty("name", "A");
      var b = session.newVertex("MTestV");
      b.setProperty("name", "B");
      var c = session.newVertex("MTestV");
      c.setProperty("name", "C");
      a.addEdge(b, "MTestE");
      b.addEdge(c, "MTestE");

      var multiItem = createMultiItemWithOutSteps("MTestE", "MTestE");
      var traverser = createMultiEdgeTraverser(multiItem);
      var startResult = new ResultInternal(session, a);

      var stream = traverser.traversePatternEdge(startResult, ctx);
      // Pipeline: A → out('MTestE') → B → out('MTestE') → C
      assertTrue(stream.hasNext(ctx));
      var result = stream.next(ctx);
      assertEquals("C", result.<String>getProperty("name"));
      assertFalse(stream.hasNext(ctx));
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies the multi-edge traverser returns an empty stream when the pipeline
   * produces no results (vertex has no outgoing edges).
   */
  @Test
  public void testMultiEdgeTraverserEmptyResult() {
    session.createClassIfNotExist("MTestV", "V");
    session.createClassIfNotExist("MTestE", "E");

    session.begin();
    try {
      var ctx = createCommandContext();
      var isolated = session.newVertex("MTestV");
      isolated.setProperty("name", "isolated");

      var multiItem = createMultiItemWithOutSteps("MTestE");
      var traverser = createMultiEdgeTraverser(multiItem);
      var startResult = new ResultInternal(session, isolated);

      var stream = traverser.traversePatternEdge(startResult, ctx);
      assertFalse(stream.hasNext(ctx));
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies the multi-edge traverser properly saves and restores the $current
   * context variable after traversal.
   */
  @Test
  public void testMultiEdgeTraverserRestoresCurrentVariable() {
    session.createClassIfNotExist("MTestV", "V");
    session.createClassIfNotExist("MTestE", "E");

    session.begin();
    try {
      var ctx = createCommandContext();
      var a = session.newVertex("MTestV");
      var b = session.newVertex("MTestV");
      a.addEdge(b, "MTestE");

      var sentinel = "sentinel-value";
      ctx.setVariable("$current", sentinel);

      var multiItem = createMultiItemWithOutSteps("MTestE");
      var traverser = createMultiEdgeTraverser(multiItem);
      var startResult = new ResultInternal(session, a);

      traverser.traversePatternEdge(startResult, ctx);
      // $current must be restored to its original value
      assertEquals(sentinel, ctx.getVariable("$current"));
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies the multi-edge traverser handles a pipeline with a sub-item that
   * has a null filter (no filter at all on the sub-item). Covers the
   * {@code sub.getFilter() == null} branch of the whileCondition ternary.
   */
  @Test
  public void testMultiEdgeTraverserSubItemNullFilter() {
    session.createClassIfNotExist("MTestV", "V");
    session.createClassIfNotExist("MTestE", "E");

    session.begin();
    try {
      var ctx = createCommandContext();
      var a = session.newVertex("MTestV");
      var b = session.newVertex("MTestV");
      b.setProperty("name", "B");
      a.addEdge(b, "MTestE");

      // Create a sub-item with no filter
      var multiItem = new SQLMultiMatchPathItem(-1);
      var subItem = new SQLMatchPathItem(-1);
      subItem.outPath(null); // out('E')
      // No filter set — filter remains null
      multiItem.addItem(subItem);

      var traverser = createMultiEdgeTraverser(multiItem);
      var startResult = new ResultInternal(session, a);

      var stream = traverser.traversePatternEdge(startResult, ctx);
      assertTrue(stream.hasNext(ctx));
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies the multi-edge traverser handles a pipeline with a sub-item
   * whose filter has a WHERE clause that rejects all candidates.
   */
  @Test
  public void testMultiEdgeTraverserSubItemWithRejectingFilter() {
    session.createClassIfNotExist("MTestV", "V");
    session.createClassIfNotExist("MTestE", "E");

    session.begin();
    try {
      var ctx = createCommandContext();
      var a = session.newVertex("MTestV");
      var b = session.newVertex("MTestV");
      a.addEdge(b, "MTestE");

      var multiItem = new SQLMultiMatchPathItem(-1);
      var subItem = new SQLMatchPathItem(-1);
      subItem.outPath(null);
      var filter = new SQLMatchFilter(-1);
      filter.setAlias("result");
      var where = new SQLWhereClause(-1);
      where.setBaseExpression(SQLBooleanExpression.FALSE);
      filter.setFilter(where);
      subItem.setFilter(filter);
      multiItem.addItem(subItem);

      var traverser = createMultiEdgeTraverser(multiItem);
      var startResult = new ResultInternal(session, a);

      var stream = traverser.traversePatternEdge(startResult, ctx);
      // Filter rejects everything
      assertFalse(stream.hasNext(ctx));
    } finally {
      session.rollback();
    }
  }

  /** Verifies traversePatternEdge asserts when startingPoint is null. */
  @Test(expected = AssertionError.class)
  public void testMultiEdgeTraverserNullStartingPoint() {
    var multiItem = createMultiItemWithOutSteps("E");
    var traverser = createMultiEdgeTraverser(multiItem);
    var ctx = createCommandContext();
    traverser.traversePatternEdge(null, ctx);
  }

  /**
   * Verifies the multi-edge traverser handles a pipeline where the sub-item's
   * filter has an alias but no WHERE and no WHILE. Covers the path where
   * {@code sub.getFilter() != null} but {@code getWhileCondition() == null}.
   */
  @Test
  public void testMultiEdgeTraverserFilterWithoutWhere() {
    session.createClassIfNotExist("MTestV", "V");
    session.createClassIfNotExist("MTestE", "E");

    session.begin();
    try {
      var ctx = createCommandContext();
      var a = session.newVertex("MTestV");
      var b = session.newVertex("MTestV");
      b.setProperty("name", "B");
      a.addEdge(b, "MTestE");

      // Sub-item with filter that has alias but no WHERE and no WHILE
      var multiItem = new SQLMultiMatchPathItem(-1);
      var subItem = new SQLMatchPathItem(-1);
      subItem.outPath(null);
      var filter = new SQLMatchFilter(-1);
      filter.setAlias("result");
      subItem.setFilter(filter);
      multiItem.addItem(subItem);

      var traverser = createMultiEdgeTraverser(multiItem);
      var startResult = new ResultInternal(session, a);

      var stream = traverser.traversePatternEdge(startResult, ctx);
      assertTrue(stream.hasNext(ctx));
      var result = stream.next(ctx);
      assertEquals("B", result.<String>getProperty("name"));
    } finally {
      session.rollback();
    }
  }

  // -- MatchResultRow tests --

  /**
   * Verifies that MatchResultRow returns the new alias value for the assigned alias
   * and delegates to the parent for all other property lookups.
   */
  @Test
  public void testMatchResultRowLayeredPropertyAccess() {
    var parent = new ResultInternal(session);
    parent.setProperty("a", "Alice");
    parent.setProperty("b", "Bob");

    var row = new MatchResultRow(session, parent, "c", "Carol");

    // New alias is accessible
    assertEquals("Carol", row.<String>getProperty("c"));
    // Parent properties are accessible via delegation
    assertEquals("Alice", row.<String>getProperty("a"));
    assertEquals("Bob", row.<String>getProperty("b"));
    // Non-existent property returns null
    assertNull(row.getProperty("nonexistent"));
  }

  /**
   * Verifies that setProperty on the new alias updates it directly, and setting
   * a different property creates a local content override.
   */
  @Test
  public void testMatchResultRowSetProperty() {
    var parent = new ResultInternal(session);
    parent.setProperty("a", "Alice");

    var row = new MatchResultRow(session, parent, "b", "Bob");

    // Overwrite the new alias
    row.setProperty("b", "Bobby");
    assertEquals("Bobby", row.<String>getProperty("b"));

    // Add a new local property (stored in content map)
    row.setProperty("depth", 3);
    assertEquals(3, (int) row.<Integer>getProperty("depth"));
    // Parent is unaffected
    assertEquals("Alice", row.<String>getProperty("a"));
  }

  /**
   * Verifies that hasProperty correctly checks the new alias, local content,
   * and parent delegation.
   */
  @Test
  public void testMatchResultRowHasProperty() {
    var parent = new ResultInternal(session);
    parent.setProperty("a", "Alice");

    var row = new MatchResultRow(session, parent, "b", "Bob");

    assertTrue(row.hasProperty("b")); // new alias
    assertTrue(row.hasProperty("a")); // parent property
    assertFalse(row.hasProperty("z")); // does not exist

    // Add a local content property
    row.setProperty("extra", "val");
    assertTrue(row.hasProperty("extra"));
  }

  /**
   * Verifies that getPropertyNames merges the parent names, the new alias,
   * and any local content properties into a single list.
   */
  @Test
  public void testMatchResultRowGetPropertyNamesMergesAllSources() {
    var parent = new ResultInternal(session);
    parent.setProperty("a", "Alice");
    parent.setProperty("b", "Bob");

    var row = new MatchResultRow(session, parent, "c", "Carol");
    // Add a local content property (e.g. $depth alias from WHILE traversal)
    row.setProperty("depth", 0);

    var names = row.getPropertyNames();
    assertTrue(names.contains("a"));
    assertTrue(names.contains("b"));
    assertTrue(names.contains("c"));
    assertTrue(names.contains("depth"));
    assertEquals(4, names.size());
  }

  /**
   * Verifies that removing the new alias hides it from getProperty, hasProperty,
   * and getPropertyNames.
   */
  @Test
  public void testMatchResultRowRemoveNewAlias() {
    var parent = new ResultInternal(session);
    parent.setProperty("a", "Alice");

    var row = new MatchResultRow(session, parent, "b", "Bob");
    row.removeProperty("b");

    // New alias is hidden after removal
    assertNull(row.getProperty("b"));
    assertFalse(row.hasProperty("b"));
    assertFalse(row.getPropertyNames().contains("b"));
    // Parent property is still accessible
    assertEquals("Alice", row.<String>getProperty("a"));
  }

  /**
   * Verifies that removing a parent property shadows it with a sentinel,
   * hiding it without modifying the parent.
   */
  @Test
  public void testMatchResultRowRemoveParentProperty() {
    var parent = new ResultInternal(session);
    parent.setProperty("a", "Alice");
    parent.setProperty("b", "Bob");

    var row = new MatchResultRow(session, parent, "c", "Carol");
    row.removeProperty("a");

    // Removed parent property is hidden
    assertNull(row.getProperty("a"));
    assertFalse(row.hasProperty("a"));
    assertFalse(row.getPropertyNames().contains("a"));
    // Other parent property is still accessible
    assertEquals("Bob", row.<String>getProperty("b"));
    // New alias is still accessible
    assertEquals("Carol", row.<String>getProperty("c"));
  }

  /**
   * Verifies that removing a property that only exists in local content
   * (not in parent) removes it from the content map rather than adding a sentinel.
   */
  @Test
  public void testMatchResultRowRemoveLocalContentProperty() {
    var parent = new ResultInternal(session);
    parent.setProperty("a", "Alice");

    var row = new MatchResultRow(session, parent, "b", "Bob");
    row.setProperty("depth", 5);

    // Verify content property exists
    assertEquals(5, (int) row.<Integer>getProperty("depth"));

    // Remove the content-only property
    row.removeProperty("depth");

    // It should no longer be accessible via any accessor
    assertNull(row.getProperty("depth"));
    assertFalse(row.hasProperty("depth"));
    assertFalse(row.getPropertyNames().contains("depth"));
  }

  /**
   * Verifies that getPropertyNames correctly excludes the new alias from
   * parent names when the alias exists in both parent and this row but has
   * been removed via removeProperty.
   */
  @Test
  public void testMatchResultRowGetPropertyNamesExcludesRemovedNewAliasFromParent() {
    var parent = new ResultInternal(session);
    parent.setProperty("a", "Alice");
    // Parent already has a property with the same key as the new alias
    parent.setProperty("b", "OldBob");

    var row = new MatchResultRow(session, parent, "b", "NewBob");

    // Before removal: newAlias overrides the parent value
    assertEquals("NewBob", row.<String>getProperty("b"));
    assertTrue(row.getPropertyNames().contains("b"));

    // After removal: the alias is hidden even though parent has it
    row.removeProperty("b");
    assertNull(row.getProperty("b"));
    assertFalse(row.hasProperty("b"));
    assertFalse(row.getPropertyNames().contains("b"));
  }

  /**
   * Verifies that getLink returns the RID of an Identifiable-valued property
   * accessed through the layered getProperty mechanism.
   */
  @Test
  public void testMatchResultRowGetLinkReturnsRid() {
    session.createClassIfNotExist("MatchLinkV", "V");
    session.begin();
    try {
      var vertex = session.newVertex("MatchLinkV");
      var rid = vertex.getIdentity();

      var parent = new ResultInternal(session);
      var row = new MatchResultRow(session, parent, "target", vertex);

      // getLink should extract the RID from the Identifiable
      var link = row.getLink("target");
      assertNotNull(link);
      assertEquals(rid, link);
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies that getLink returns null when the property does not exist.
   */
  @Test
  public void testMatchResultRowGetLinkReturnsNullForMissing() {
    var parent = new ResultInternal(session);
    var row = new MatchResultRow(session, parent, "a", "text");

    // Property "missing" does not exist → null
    assertNull(row.getLink("missing"));
  }

  /**
   * Verifies that getLink throws IllegalStateException when the property
   * value is neither null nor Identifiable (e.g. a plain String).
   */
  @Test(expected = IllegalStateException.class)
  public void testMatchResultRowGetLinkThrowsForNonIdentifiable() {
    var parent = new ResultInternal(session);
    var row = new MatchResultRow(session, parent, "a", "plainText");

    // "a" is a String, not an Identifiable → should throw
    row.getLink("a");
  }

  /**
   * Verifies that isProjection returns true for MatchResultRow, which is
   * always a projection (computed row, not backed by a stored record).
   */
  @Test
  public void testMatchResultRowIsProjection() {
    var parent = new ResultInternal(session);
    var row = new MatchResultRow(session, parent, "a", "value");
    assertTrue(row.isProjection());
  }

  /**
   * Verifies that MatchResultRow correctly chains multiple layers of parent
   * delegation, simulating a multi-step MATCH pipeline.
   */
  @Test
  public void testMatchResultRowMultiLayerChain() {
    var root = new ResultInternal(session);
    root.setProperty("person", "Alice");

    // First traversal step: add "friend" alias
    var layer1 = new MatchResultRow(session, root, "friend", "Bob");
    // Second traversal step: add "colleague" alias
    var layer2 = new MatchResultRow(session, layer1, "colleague", "Carol");

    // All aliases are accessible through the chain
    assertEquals("Alice", layer2.<String>getProperty("person"));
    assertEquals("Bob", layer2.<String>getProperty("friend"));
    assertEquals("Carol", layer2.<String>getProperty("colleague"));

    var names = layer2.getPropertyNames();
    assertEquals(3, names.size());
    assertTrue(names.contains("person"));
    assertTrue(names.contains("friend"));
    assertTrue(names.contains("colleague"));
  }

  /**
   * Verifies that setProperty on the new alias after it was removed re-activates it.
   */
  @Test
  public void testMatchResultRowReAddRemovedAlias() {
    var parent = new ResultInternal(session);
    var row = new MatchResultRow(session, parent, "x", "original");

    row.removeProperty("x");
    assertNull(row.getProperty("x"));

    // Re-set the alias
    row.setProperty("x", "restored");
    assertEquals("restored", row.<String>getProperty("x"));
    assertTrue(row.hasProperty("x"));
    assertTrue(row.getPropertyNames().contains("x"));
  }

  /**
   * Verifies that MatchResultRow works correctly with a non-ResultInternal parent
   * (any Result implementation), validating the interface-based delegation.
   */
  @Test
  public void testMatchResultRowWithNonResultInternalParent() {
    var parent = new NonResultInternalStub("name", "Alice");
    var row = new MatchResultRow(session, parent, "age", 30);

    assertEquals("Alice", row.<String>getProperty("name"));
    assertEquals(30, (int) row.<Integer>getProperty("age"));

    var names = row.getPropertyNames();
    assertTrue(names.contains("name"));
    assertTrue(names.contains("age"));
    assertEquals(2, names.size());
  }

  // -- Helper methods --

  /** Creates a SQLMultiMatchPathItem with one out(edgeLabel) sub-item per label. */
  private SQLMultiMatchPathItem createMultiItemWithOutSteps(String... edgeLabels) {
    var multiItem = new SQLMultiMatchPathItem(-1);
    for (var label : edgeLabels) {
      var subItem = new SQLMatchPathItem(-1);
      var edgeName = new SQLIdentifier(label);
      subItem.outPath(edgeName);
      var filter = new SQLMatchFilter(-1);
      filter.setAlias("step_" + label);
      subItem.setFilter(filter);
      multiItem.addItem(subItem);
    }
    return multiItem;
  }

  /**
   * Creates a MatchMultiEdgeTraverser with the given multi-item as the edge's
   * path item.
   */
  private MatchMultiEdgeTraverser createMultiEdgeTraverser(
      SQLMultiMatchPathItem multiItem) {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    nodeA.addEdge(multiItem, nodeB);
    var patternEdge = nodeA.out.iterator().next();
    var edgeTraversal = new EdgeTraversal(patternEdge, true);
    var sourceResult = new ResultInternal(session);
    return new MatchMultiEdgeTraverser(sourceResult, edgeTraversal);
  }

  private CommandContext createCommandContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /** Creates a PatternEdge from two nodes with a bare SQLMatchPathItem. */
  private PatternEdge createTestPatternEdge() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var item = new SQLMatchPathItem(-1);
    nodeA.addEdge(item, nodeB);
    return nodeA.out.iterator().next();
  }

  /** Creates a PatternEdge with a fully-initialized outPath method (toString-safe). */
  private PatternEdge createTestPatternEdgeWithOutPath() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var item = new SQLMatchPathItem(-1);
    item.outPath(null);
    nodeA.addEdge(item, nodeB);
    return nodeA.out.iterator().next();
  }

  /** Creates a MatchReverseEdgeTraverser with an outPath method (supports executeReverse). */
  private MatchReverseEdgeTraverser createReverseTraverserWithOutPath() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var item = new SQLMatchPathItem(-1);
    item.outPath(null);
    nodeA.addEdge(item, nodeB);
    var patternEdge = nodeA.out.iterator().next();
    var edgeTraversal = new EdgeTraversal(patternEdge, false);
    var sourceResult = new ResultInternal(session);
    return new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
  }

  private EdgeTraversal createTestEdgeTraversal() {
    return new EdgeTraversal(createTestPatternEdge(), true);
  }

  /** Creates a sub-step that always returns an empty stream. */
  private AbstractExecutionStep createEmptySubStep(CommandContext ctx) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext ctx) {
        return ExecutionStream.empty();
      }

      @Override
      public String prettyPrint(int depth, int indent) {
        return "";
      }

      @Override
      public ExecutionStep copy(CommandContext ctx) {
        return this;
      }
    };
  }

  /**
   * Minimal Result stub that is NOT a ResultInternal. Used to test the false
   * branch of the {@code instanceof ResultInternal} check in ChainStep's copy
   * logic. Only {@link #getPropertyNames()} and {@link #getProperty(String)}
   * are functional; all other methods return safe defaults or throw.
   */
  // Most methods intentionally return null for this test-only stub
  @SuppressWarnings("NullableProblems")
  private static class NonResultInternalStub implements Result {

    private final Map<String, Object> properties = new LinkedHashMap<>();

    NonResultInternalStub(String key, Object value) {
      properties.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(@Nonnull String name) {
      return (T) properties.get(name);
    }

    @Nonnull
    @Override
    public List<String> getPropertyNames() {
      return new ArrayList<>(properties.keySet());
    }

    @Override
    public boolean hasProperty(@Nonnull String varName) {
      return properties.containsKey(varName);
    }

    @Override
    public boolean isIdentifiable() {
      return false;
    }

    @Override
    public RID getIdentity() {
      return null;
    }

    @Override
    public boolean isProjection() {
      return true;
    }

    @Nonnull
    @Override
    public Map<String, Object> toMap() {
      return Collections.unmodifiableMap(properties);
    }

    @Nonnull
    @Override
    public String toJSON() {
      return properties.toString();
    }

    @Override
    public RID getLink(@Nonnull String name) {
      return null;
    }

    @Override
    public DatabaseSessionEmbedded getBoundedToSession() {
      return null;
    }

    @Nonnull
    @Override
    public Result detach() {
      return this;
    }

    @Override
    public Result getResult(@Nonnull String name) {
      return null;
    }

    @Override
    public Entity getEntity(@Nonnull String name) {
      return null;
    }

    @Override
    public Blob getBlob(@Nonnull String name) {
      return null;
    }

    @Override
    public boolean isEntity() {
      return false;
    }

    @Nonnull
    @Override
    public Entity asEntity() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Entity asEntityOrNull() {
      return null;
    }

    @Override
    public boolean isVertex() {
      return false;
    }

    @Override
    public boolean isEdge() {
      return false;
    }

    @Nonnull
    @Override
    public Edge asEdge() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Edge asEdgeOrNull() {
      return null;
    }

    @Override
    public boolean isBlob() {
      return false;
    }

    @Nonnull
    @Override
    public Blob asBlob() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Blob asBlobOrNull() {
      return null;
    }

    @Nonnull
    @Override
    public DBRecord asRecord() {
      throw new UnsupportedOperationException();
    }

    @Override
    public DBRecord asRecordOrNull() {
      return null;
    }

    @Nonnull
    @Override
    public Identifiable asIdentifiable() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Identifiable asIdentifiableOrNull() {
      return null;
    }
  }
}
