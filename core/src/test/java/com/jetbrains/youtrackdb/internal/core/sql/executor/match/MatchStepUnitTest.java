package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.QueryPlanningInfo;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for MATCH execution step classes that exercise copy(), prettyPrint(),
 * and other methods not covered by integration tests.
 */
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

  /** Verifies MatchStep.copy() creates a distinct new MatchStep instance. */
  @Test
  public void testMatchStepCopy() {
    var ctx = createCommandContext();
    var edge = createTestEdgeTraversal();
    var step = new MatchStep(ctx, edge, false);

    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof MatchStep);
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

  // -- MatchFirstStep tests --

  /** Verifies MatchFirstStep.copy() with node only (no execution plan). */
  @Test
  public void testMatchFirstStepCopyNoPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "a";
    var step = new MatchFirstStep(ctx, node, false);

    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof MatchFirstStep);
  }

  /** Verifies MatchFirstStep.copy() with both node and execution plan. */
  @Test
  public void testMatchFirstStepCopyWithPlan() {
    var ctx = createCommandContext();
    var node = new PatternNode();
    node.alias = "a";
    var plan = new SelectExecutionPlan(ctx);
    var step = new MatchFirstStep(ctx, node, plan, false);

    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof MatchFirstStep);
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

  /** Verifies RemoveEmptyOptionalsStep.copy() returns a distinct instance. */
  @Test
  public void testRemoveEmptyOptionalsStepCopy() {
    var ctx = createCommandContext();
    var step = new RemoveEmptyOptionalsStep(ctx, false);
    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof RemoveEmptyOptionalsStep);
  }

  // -- ReturnMatchPathsStep tests --

  /** Verifies ReturnMatchPathsStep.copy() returns a distinct instance. */
  @Test
  public void testReturnMatchPathsStepCopy() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof ReturnMatchPathsStep);
  }

  // -- ReturnMatchPatternsStep tests --

  /** Verifies ReturnMatchPatternsStep.copy() returns a distinct instance. */
  @Test
  public void testReturnMatchPatternsStepCopy() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPatternsStep(ctx, false);
    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof ReturnMatchPatternsStep);
  }

  // -- ReturnMatchElementsStep tests --

  /** Verifies ReturnMatchElementsStep.copy() returns a distinct instance. */
  @Test
  public void testReturnMatchElementsStepCopy() {
    var ctx = createCommandContext();
    var step = new ReturnMatchElementsStep(ctx, false);
    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof ReturnMatchElementsStep);
  }

  // -- ReturnMatchPathElementsStep tests --

  /** Verifies ReturnMatchPathElementsStep.copy() returns a distinct instance. */
  @Test
  public void testReturnMatchPathElementsStepCopy() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathElementsStep(ctx, false);
    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof ReturnMatchPathElementsStep);
  }

  // -- FilterNotMatchPatternStep tests --

  /** Verifies FilterNotMatchPatternStep.copy() returns a distinct instance. */
  @Test
  public void testFilterNotMatchPatternStepCopy() {
    var ctx = createCommandContext();
    var step = new FilterNotMatchPatternStep(List.of(), ctx, false);
    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof FilterNotMatchPatternStep);
  }

  // -- MatchPrefetchStep tests --

  /** Verifies MatchPrefetchStep.copy() returns a distinct instance. */
  @Test
  public void testMatchPrefetchStepCopy() {
    var ctx = createCommandContext();
    var plan = new SelectExecutionPlan(ctx);
    var step = new MatchPrefetchStep(ctx, plan, "alias", false);
    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof MatchPrefetchStep);
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

  /** Verifies MatchPrefetchStep.reset() does not throw. */
  @Test
  public void testMatchPrefetchStepReset() {
    var ctx = createCommandContext();
    var plan = new SelectExecutionPlan(ctx);
    var step = new MatchPrefetchStep(ctx, plan, "alias", false);
    // reset should not throw
    step.reset();
  }

  // -- AbstractExecutionStep tests --

  /** Verifies sendTimeout propagates to previous step when prev is null (no-op). */
  @Test
  public void testAbstractExecutionStepSendTimeoutNoPrev() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    // sendTimeout with no prev should not throw
    step.sendTimeout();
  }

  /** Verifies isProfilingEnabled/setProfilingEnabled round-trip. */
  @Test
  public void testAbstractExecutionStepProfiling() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    assertFalse(step.isProfilingEnabled());

    step.setProfilingEnabled(true);
    assertTrue(step.isProfilingEnabled());
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

  // -- Helper methods --

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

  private EdgeTraversal createTestEdgeTraversal() {
    return new EdgeTraversal(createTestPatternEdge(), true);
  }
}
