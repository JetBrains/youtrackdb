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
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.QueryPlanningInfo;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
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

  /** Verifies internalStart() forwards the upstream stream unchanged. */
  @Test
  public void testReturnMatchPathsStepInternalStart() {
    var ctx = createCommandContext();
    var step = new ReturnMatchPathsStep(ctx, false);
    // Create a simple prev step that returns an empty stream
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
   * Verifies that targetClassName returns null when no left class is set on the edge.
   */
  @Test
  public void testReverseEdgeTraverserTargetClassNameNull() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    // leftClass is not set, defaults to null
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
    var ctx = createCommandContext();
    assertNull(traverser.targetClassName(null, ctx));
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
   * Verifies that targetRid returns null when no left RID is set.
   */
  @Test
  public void testReverseEdgeTraverserTargetRidNull() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
    var ctx = createCommandContext();
    assertNull(traverser.targetRid(null, ctx));
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
   * Verifies that getTargetFilter returns null when no left filter is set.
   */
  @Test
  public void testReverseEdgeTraverserGetTargetFilterNull() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
    assertNull(traverser.getTargetFilter(null));
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

  /**
   * Verifies that getStartingPointAlias returns edge.in.alias (the syntactic
   * target, which is the starting point in reverse traversal).
   */
  @Test
  public void testReverseEdgeTraverserGetStartingPointAlias() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
    // edge.in.alias = "b"
    assertEquals("b", traverser.getStartingPointAlias());
  }

  /**
   * Verifies that getEndpointAlias returns edge.out.alias (the syntactic
   * source, which is the endpoint in reverse traversal).
   */
  @Test
  public void testReverseEdgeTraverserGetEndpointAlias() {
    var edge = createTestPatternEdge();
    var edgeTraversal = new EdgeTraversal(edge, false);
    var sourceResult = new ResultInternal(session);

    var traverser = new MatchReverseEdgeTraverser(sourceResult, edgeTraversal);
    // edge.out.alias = "a"
    assertEquals("a", traverser.getEndpointAlias());
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
}
