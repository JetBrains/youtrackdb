package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.QueryPlanningInfo;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
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
          row.setProperty("resultProp", resultVal);     // Result → emitted
          row.setProperty("identProp", vertex);          // Identifiable → wrapped & emitted
          row.setProperty("primitiveProp", "ignored");   // Primitive → skipped
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

  /** Verifies FilterNotMatchPatternStep.copy() returns a distinct instance. */
  @Test
  public void testFilterNotMatchPatternStepCopy() {
    var ctx = createCommandContext();
    var step = new FilterNotMatchPatternStep(List.of(), ctx, false);
    var copy = step.copy(ctx);
    assertNotSame(step, copy);
    assertTrue(copy instanceof FilterNotMatchPatternStep);
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
   * Verifies that ChainStep copies both properties and metadata from a
   * ResultInternal source row without error. This covers the true branch
   * of the {@code instanceof ResultInternal} check inside ChainStep's copy
   * logic.
   *
   * <p>Note: ChainStep is a private inner class whose copy output is consumed
   * internally by the NOT-pattern sub-plan. The original upstream row (not the
   * copy) is what passes through or gets discarded. With an empty sub-steps
   * list, ChainStep is the sole plan step and always produces output, so the
   * pattern "matches" and the row is discarded — but the copy path with
   * metadata is fully exercised during evaluation.
   */
  @Test
  public void testFilterNotMatchPatternStepCopiesMetadata() {
    var ctx = createCommandContext();
    // Empty sub-steps: ChainStep alone always produces output → pattern matches.
    // This path invokes ChainStep.copy(Result) with a ResultInternal that has
    // metadata, exercising the instanceof-true branch.
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

    // Pattern matches → row discarded (ChainStep.copy with metadata exercised)
    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
    stream.close(ctx);
  }

  /**
   * Verifies that ChainStep handles non-ResultInternal Result objects correctly,
   * copying only properties and skipping metadata (which is ResultInternal-
   * specific). This covers the false branch of the {@code instanceof
   * ResultInternal} check in ChainStep's copy logic.
   *
   * <p>With an empty sub-steps list, ChainStep is the sole plan step and
   * always produces output, so the pattern "matches" and the row is discarded.
   * The key assertion is that ChainStep.copy() does not throw when the
   * upstream row is a non-ResultInternal Result.
   */
  @Test
  public void testFilterNotMatchPatternStepNonResultInternalUpstream() {
    var ctx = createCommandContext();
    // Empty sub-steps → ChainStep alone → pattern matches → row discarded.
    // ChainStep.copy(Result) receives a NonResultInternalStub, exercising the
    // instanceof-false branch.
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

    // Pattern matches → row discarded (ChainStep.copy without metadata exercised)
    var stream = step.start(ctx);
    assertFalse(stream.hasNext(ctx));
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
   * Verifies that close() can be called without error. The step delegates to
   * AbstractExecutionStep.close() which closes the upstream chain.
   */
  @Test
  public void testFilterNotMatchPatternStepClose() {
    var ctx = createCommandContext();
    var step = new FilterNotMatchPatternStep(List.of(), ctx, false);
    // close() should not throw even without a previous step set
    step.close();
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

  /** Verifies toOResultInternal wraps a Relation into a ResultInternal. */
  @Test
  public void testToOResultInternalRelation() {
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

  /** Verifies dispatchTraversalResult handles a single Relation. */
  @Test
  public void testDispatchSingleRelation() {
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
   * Verifies dispatchTraversalResult handles a single Relation with a filter
   * that rejects it.
   */
  @Test
  public void testDispatchSingleRelationRejected() {
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
    public boolean isRelation() {
      return false;
    }

    @Override
    public Relation<?> asRelation() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Relation<?> asRelationOrNull() {
      return null;
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
    public boolean isStatefulEdge() {
      return false;
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
