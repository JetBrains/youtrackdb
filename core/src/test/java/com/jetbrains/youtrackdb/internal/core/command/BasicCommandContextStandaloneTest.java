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
package com.jetbrains.youtrackdb.internal.core.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import java.util.List;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 * Standalone unit tests for {@link BasicCommandContext} branches that do not require a live
 * {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded}. Track 9 Phase A review
 * T6 split {@code BasicCommandContext} testing across two files:
 *
 * <ul>
 *   <li>This class — standalone: variable-set/-get non-DB branches, {@code incrementVariable}
 *       type-mismatch, {@code getParentWhereExpressions} merge, {@code setChild(null)} removal,
 *       profiling LIFO, system-variable parent fallbacks, and the {@code copy()} null-child NPE
 *       pin (T4).
 *   <li>{@link BasicCommandContextTest} — DbTestBase: branches that dereference a session (name
 *       resolution through {@code EntityHelper.getFieldValue}).
 * </ul>
 *
 * <p>All tests here pass {@code null} for the session field when possible. The few branches that
 * eventually reach {@code getDatabaseSession()} are not exercised by this suite — they belong in
 * {@link BasicCommandContextTest}.
 */
public class BasicCommandContextStandaloneTest {

  // ---------------------------------------------------------------------------
  // setVariable — dot-path propagation to a nested CommandContext
  // Source: BasicCommandContext.java:277-288. The outer ctx looks up the prefix,
  // and if it resolves to a CommandContext, forwards the suffix to it.
  // ---------------------------------------------------------------------------

  /**
   * {@code setVariable("outer.inner", v)} on a context where {@code $outer} resolves to another
   * CommandContext must forward the write to the inner context under key {@code inner}. The outer
   * context must not gain a key named {@code outer.inner}.
   */
  @Test
  public void setVariableDotPathForwardsToNestedCommandContext() {
    var outer = new BasicCommandContext();
    var inner = new BasicCommandContext();
    outer.setVariable("delegate", inner);

    outer.setVariable("delegate.leaf", "written-to-inner");

    assertEquals("leaf must live on the inner context",
        "written-to-inner", inner.getVariable("leaf"));
    // Outer holds only the delegate key — the dot-path suffix is NOT a literal variable name.
    assertFalse("outer must not hold a literal 'delegate.leaf' key",
        outer.getVariables().containsKey("delegate.leaf"));
  }

  /**
   * When the prefix of a dot-path does not resolve to a CommandContext, {@code setVariable} is a
   * no-op on the outer context (the branch at lines 283-285 is gated on
   * {@code instanceof CommandContext}). This pins that the outer's variable map is unchanged —
   * the dot-suffix literal is never stored as a top-level key, and the scalar prefix value is
   * preserved.
   *
   * <p>Uses {@link BasicCommandContext#getVariables()} (pure map clone, no session) to inspect
   * state so this test stays standalone.
   */
  @Test
  public void setVariableDotPathNoOpWhenPrefixNotCommandContext() {
    var ctx = new BasicCommandContext();
    ctx.setVariable("scalarPrefix", 42);

    // Should not throw. The nested setVariable branch is skipped because 42 is not a
    // CommandContext; setVariable returns immediately after the skipped branch.
    ctx.setVariable("scalarPrefix.child", "ignored");

    var snapshot = ctx.getVariables();
    assertEquals("outer variable count is unchanged", 1, snapshot.size());
    assertEquals("scalar prefix value is unchanged", 42, snapshot.get("scalarPrefix"));
    assertFalse("dot-suffix key must not appear in the outer map",
        snapshot.containsKey("scalarPrefix.child"));
  }

  /**
   * {@code setVariable("$name", v)} must strip a single leading {@code $} before storing, so
   * {@code getVariable("name")} and {@code getVariable("$name")} resolve to the same value.
   */
  @Test
  public void setVariableStripsLeadingDollarPrefix() {
    var ctx = new BasicCommandContext();
    ctx.setVariable("$withDollar", "v1");

    assertEquals("$-prefix must be stripped on write", "v1", ctx.getVariable("withDollar"));
    assertEquals("read via $-prefix resolves to same entry", "v1", ctx.getVariable("$withDollar"));
  }

  /**
   * {@code setVariable(null, v)} is a no-op (returns null immediately without throwing).
   * Source: BasicCommandContext.java:267-269.
   */
  @Test
  public void setVariableNullNameIsNoOpReturnsNull() {
    var ctx = new BasicCommandContext();
    assertNull(ctx.setVariable(null, "anything"));
    assertTrue("no variables were stored", ctx.getVariables().isEmpty());
  }

  // ---------------------------------------------------------------------------
  // getVariable — parent-hierarchy traversal for names only present on a grandparent
  // Source: BasicCommandContext.java:251-259 (getVariableFromParentHierarchy).
  // ---------------------------------------------------------------------------

  /**
   * When a variable is defined only on the grandparent, {@code getVariable} on the leaf must
   * traverse the parent chain via {@code getVariableFromParentHierarchy}.
   */
  @Test
  public void getVariableResolvesThroughGrandparent() {
    var grandparent = new BasicCommandContext();
    var parent = new BasicCommandContext();
    var leaf = new BasicCommandContext();
    grandparent.setChild(parent);
    parent.setChild(leaf);

    grandparent.setVariable("rootOnly", "deep-value");

    assertEquals("leaf must walk the parent chain to find rootOnly",
        "deep-value", leaf.getVariable("rootOnly"));
    // Middle parent has no 'rootOnly' variable set directly.
    assertEquals("deep-value", parent.getVariable("rootOnly"));
  }

  /**
   * {@code getVariable} with a name absent from every level returns the provided default.
   */
  @Test
  public void getVariableReturnsDefaultWhenMissing() {
    var parent = new BasicCommandContext();
    var child = new BasicCommandContext();
    parent.setChild(child);

    assertEquals("sentinel",
        child.getVariable("definitelyNotThere", "sentinel"));
  }

  // ---------------------------------------------------------------------------
  // incrementVariable — type mismatch branch
  // Source: BasicCommandContext.java:341-344. Non-number existing value must throw
  // IllegalArgumentException mentioning the variable name and its actual class.
  // ---------------------------------------------------------------------------

  /**
   * {@code incrementVariable} on a variable that already holds a non-Number value must throw
   * {@link IllegalArgumentException} and must leave the existing value unchanged.
   */
  @Test
  public void incrementVariableThrowsWhenExistingValueIsNotNumber() {
    var ctx = new BasicCommandContext();
    ctx.setVariable("counter", "not-a-number");

    var ex =
        assertThrows(IllegalArgumentException.class, () -> ctx.incrementVariable("counter"));
    // Message must name the variable and its actual class so misuse is easy to debug.
    assertTrue("exception message must name the variable: " + ex.getMessage(),
        ex.getMessage().contains("counter"));
    assertTrue("exception message must name the class: " + ex.getMessage(),
        ex.getMessage().contains("String"));
    assertEquals("value must be unchanged after failed increment",
        "not-a-number", ctx.getVariable("counter"));
  }

  /**
   * {@code incrementVariable} on an absent variable must seed it with {@code 1}. This pins the
   * default-seed branch at {@code BasicCommandContext.java:337-338}.
   */
  @Test
  public void incrementVariableSeedsWithOneWhenAbsent() {
    var ctx = new BasicCommandContext();
    ctx.incrementVariable("fresh");

    assertEquals(1, ctx.getVariable("fresh"));
  }

  /**
   * Repeated {@code incrementVariable} on a Number must promote through the {@code
   * PropertyTypeInternal.increment} path (line 340). Integer seed, two increments → 3.
   */
  @Test
  public void incrementVariableIncrementsExistingNumber() {
    var ctx = new BasicCommandContext();
    ctx.setVariable("tally", 10);
    ctx.incrementVariable("tally");
    ctx.incrementVariable("tally");

    assertEquals(12, ctx.getVariable("tally"));
  }

  // ---------------------------------------------------------------------------
  // getParentWhereExpressions — merge paths
  // Source: BasicCommandContext.java:619-631. Three branches:
  //   (1) parent == null                            → return own list
  //   (2) parent != null && own.isEmpty()           → delegate to parent
  //   (3) parent != null && !own.isEmpty()          → merge own + parent's
  // ---------------------------------------------------------------------------

  /**
   * With no parent, {@code getParentWhereExpressions} returns only the expressions registered on
   * this context (branch 1).
   */
  @Test
  public void getParentWhereExpressionsNoParentReturnsOwnList() {
    var ctx = new BasicCommandContext();
    ctx.registerBooleanExpression(SQLBooleanExpression.TRUE);
    ctx.registerBooleanExpression(SQLBooleanExpression.FALSE);

    var exprs = ctx.getParentWhereExpressions();

    assertEquals(2, exprs.size());
    assertSame(SQLBooleanExpression.TRUE, exprs.get(0));
    assertSame(SQLBooleanExpression.FALSE, exprs.get(1));
  }

  /**
   * When self's list is empty and a parent exists, the parent's list is returned directly
   * (branch 2 — delegate without copying).
   */
  @Test
  public void getParentWhereExpressionsEmptySelfDelegatesToParent() {
    var parent = new BasicCommandContext();
    var child = new BasicCommandContext();
    parent.setChild(child);
    parent.registerBooleanExpression(SQLBooleanExpression.TRUE);

    var exprs = child.getParentWhereExpressions();

    assertEquals(1, exprs.size());
    assertSame(SQLBooleanExpression.TRUE, exprs.get(0));
  }

  /**
   * When both self and parent hold expressions, the merge path (branch 3) returns a fresh list
   * with self's entries first, then parent's entries. Must not mutate either source.
   */
  @Test
  public void getParentWhereExpressionsMergesSelfBeforeParent() {
    var parent = new BasicCommandContext();
    var child = new BasicCommandContext();
    parent.setChild(child);

    parent.registerBooleanExpression(SQLBooleanExpression.TRUE);
    child.registerBooleanExpression(SQLBooleanExpression.FALSE);

    var merged = child.getParentWhereExpressions();

    assertEquals("merged list must have child's + parent's entries",
        2, merged.size());
    assertSame("child's entry comes first", SQLBooleanExpression.FALSE, merged.get(0));
    assertSame("parent's entry comes second", SQLBooleanExpression.TRUE, merged.get(1));
    // Source lists must be unchanged.
    assertEquals(1, parent.getParentWhereExpressions().size());
  }

  // ---------------------------------------------------------------------------
  // setChild(null) — removal path
  // Source: BasicCommandContext.java:388-405. Must null out child and clear the
  // old child's parent + session fields.
  // ---------------------------------------------------------------------------

  /**
   * {@code setChild(null)} on a context that currently holds a child must detach that child —
   * nulling both its parent pointer and its session — and leave the current context with no child.
   * This pins the {@code if (child != null)} inner branch at line 390.
   */
  @Test
  public void setChildNullDetachesExistingChild() {
    var parent = new BasicCommandContext();
    var child = new BasicCommandContext();
    parent.setChild(child);
    assertSame("precondition: parent is child's parent before detach",
        parent, child.getParent());

    parent.setChild(null);

    // Parent no longer holds the child: variables from the former child are no longer visible.
    child.setVariable("k", "v");
    assertNull("parent must not see child's variables after detach",
        parent.getVariable("k"));
    assertNull("former child's parent pointer must be cleared", child.getParent());
  }

  /**
   * {@code setChild(null)} when no child exists must be a no-op and must not throw.
   */
  @Test
  public void setChildNullWithoutExistingChildIsNoOp() {
    var ctx = new BasicCommandContext();

    // Precondition: no child.
    // Must not NPE even though the inner branch is skipped.
    ctx.setChild(null);

    assertTrue(ctx.getVariables().isEmpty());
  }

  // ---------------------------------------------------------------------------
  // startProfiling / endProfiling — LIFO stack semantics
  // Source: BasicCommandContext.java:574-596.
  // Nested begin/end must pause the outer while the inner runs, resume it on inner
  // end, and update totalCost for both.
  // ---------------------------------------------------------------------------

  /**
   * Nested {@code startProfiling / endProfiling} must honor LIFO: starting an inner step pauses
   * the outer, ending the inner resumes the outer, and ending the outer leaves the stack empty.
   * The outer step's {@link StepStats#getCount()} must reflect a single start, and its total cost
   * must be non-negative (time elapsed).
   */
  @Test
  public void profilingStartEndNestedLifoUpdatesBothStats() throws InterruptedException {
    var ctx = new BasicCommandContext();
    var outer = new NamedStep("outer");
    var inner = new NamedStep("inner");

    ctx.startProfiling(outer);
    // Spend real wall-clock so nanoTime is strictly positive.
    Thread.sleep(1);
    ctx.startProfiling(inner);
    Thread.sleep(1);
    ctx.endProfiling(inner);
    Thread.sleep(1);
    ctx.endProfiling(outer);

    var outerStats = ctx.getStats(outer);
    var innerStats = ctx.getStats(inner);

    assertNotNull("outer stats must be recorded", outerStats);
    assertNotNull("inner stats must be recorded", innerStats);
    assertEquals("outer start count", 1, outerStats.getCount());
    assertEquals("inner start count", 1, innerStats.getCount());
    // Total cost is measured in nanoseconds; Thread.sleep(1) ensures strictly > 0.
    assertTrue("outer total cost must cover pause + resume windows",
        outerStats.getCost() > 0);
    assertTrue("inner total cost must be > 0", innerStats.getCost() > 0);
  }

  /**
   * A second {@code startProfiling} on the SAME step reuses the existing {@link StepStats} (the
   * {@code stats == null} branch at line 577 is skipped) and bumps {@code count} by one.
   */
  @Test
  public void profilingSecondStartOnSameStepReusesExistingStats() {
    var ctx = new BasicCommandContext();
    var step = new NamedStep("repeat");

    ctx.startProfiling(step);
    ctx.endProfiling(step);
    var afterFirst = ctx.getStats(step);

    ctx.startProfiling(step);
    ctx.endProfiling(step);
    var afterSecond = ctx.getStats(step);

    assertSame("stats instance must be reused across repeated start calls",
        afterFirst, afterSecond);
    assertEquals("count must reflect both start calls", 2, afterSecond.getCount());
  }

  /**
   * {@code endProfiling} on an empty stack is a no-op (lines 590-595 guard on {@code
   * !currentStepStats.isEmpty()}).
   */
  @Test
  public void profilingEndOnEmptyStackIsNoOp() {
    var ctx = new BasicCommandContext();
    var step = new NamedStep("never-started");

    ctx.endProfiling(step);

    assertNull("no stats should be recorded for a step that was never started",
        ctx.getStats(step));
  }

  // ---------------------------------------------------------------------------
  // hasSystemVariable / getSystemVariable — parent-chain fallbacks
  // Source: BasicCommandContext.java:93-116.
  // ---------------------------------------------------------------------------

  /**
   * {@code hasSystemVariable} must walk the parent chain. A system variable set on the
   * grandparent must be visible on the child.
   */
  @Test
  public void hasSystemVariableWalksParentChain() {
    var grandparent = new BasicCommandContext();
    var parent = new BasicCommandContext();
    var child = new BasicCommandContext();
    grandparent.setChild(parent);
    parent.setChild(child);

    grandparent.setSystemVariable(CommandContext.VAR_CURRENT, "root-current");

    assertTrue("child must see grandparent's system variable",
        child.hasSystemVariable(CommandContext.VAR_CURRENT));
    assertFalse("an unset id must report false at every level",
        child.hasSystemVariable(CommandContext.VAR_DEPTH));
  }

  /**
   * {@code getSystemVariable} on a child for an id set only on the parent must fall through to the
   * parent (line 112-114).
   */
  @Test
  public void getSystemVariableFallsThroughToParent() {
    var parent = new BasicCommandContext();
    var child = new BasicCommandContext();
    parent.setChild(child);
    parent.setSystemVariable(CommandContext.VAR_MATCHED, "row-42");

    Object got = child.getSystemVariable(CommandContext.VAR_MATCHED);

    assertEquals("child must resolve matched from parent", "row-42", got);
  }

  /**
   * {@code getSystemVariable} with no value anywhere in the chain returns {@code null}.
   */
  @Test
  public void getSystemVariableReturnsNullWhenMissingEverywhere() {
    var parent = new BasicCommandContext();
    var child = new BasicCommandContext();
    parent.setChild(child);

    assertNull(child.getSystemVariable(CommandContext.VAR_DEPTH));
  }

  /**
   * {@code setSystemVariable} on a child for an id already present on the parent must write to the
   * parent (line 82-87) so that both parent and child observe the new value.
   */
  @Test
  public void setSystemVariableForwardsToParentWhenAlreadyPresentThere() {
    var parent = new BasicCommandContext();
    var child = new BasicCommandContext();
    parent.setChild(child);
    parent.setSystemVariable(CommandContext.VAR_CURRENT, "initial");

    child.setSystemVariable(CommandContext.VAR_CURRENT, "updated-from-child");

    assertEquals("parent observes the update",
        "updated-from-child", parent.getSystemVariable(CommandContext.VAR_CURRENT));
    assertEquals("child observes the update via fallback",
        "updated-from-child", child.getSystemVariable(CommandContext.VAR_CURRENT));
  }

  // ---------------------------------------------------------------------------
  // copy() — null-child NullPointerException pin (T4)
  //
  // Falsifiable-regression: BasicCommandContext.copy() dereferences child at line 492
  // without a null guard. A freshly-constructed context with no child must NPE.
  //
  // WHEN-FIXED: Track 22 — when copy() is null-guarded (e.g., `copy.child = child != null
  //             ? child.copy() : null;`) this pin flips from "throws NPE" to "returns a
  //             copy with a null child". Re-pin at that time rather than deleting: the
  //             observed-shape assertion protects callers that rely on today's behavior.
  // ---------------------------------------------------------------------------

  /**
   * Current observed behavior: {@link BasicCommandContext#copy()} NPEs when called on a context
   * with no child. This pin locks in the present shape so any refactor to null-guard the copy path
   * is detected and deliberately re-pinned via Track 22.
   */
  @Test
  public void copyWithNullChildThrowsNpe_T4Pin() {
    var ctx = new BasicCommandContext();
    // Variables present → the copy path reaches the child.copy() dereference.
    ctx.setVariable("k", "v");

    // Today: copy() accesses child.copy() at line 492 without a null check.
    // WHEN-FIXED: Track 22 — null-guard copy(), then flip this to assertNotNull(ctx.copy()).
    assertThrows(NullPointerException.class, ctx::copy);
  }

  // ---------------------------------------------------------------------------
  // Minimal ExecutionStep used only for profiling identity in tests.
  // Must be a fresh instance per test so the IdentityHashMap key space does not leak.
  // ---------------------------------------------------------------------------

  private static final class NamedStep implements ExecutionStep {
    private final String name;

    NamedStep(String name) {
      this.name = name;
    }

    @Nonnull
    @Override
    public String getName() {
      return name;
    }

    @Nonnull
    @Override
    public String getType() {
      return "TEST";
    }

    @Override
    public String getDescription() {
      return null;
    }

    @Nonnull
    @Override
    public List<ExecutionStep> getSubSteps() {
      return List.of();
    }
  }
}
