package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import org.junit.Test;

/**
 * Tests for the {@link ExecutionStepInternal} interface, covering default methods
 * and the static {@code getIndent} utility.
 *
 * <p>The test verifies the contract of every default method ({@code getName},
 * {@code getType}, {@code getDescription}, {@code prettyPrint}, {@code getTargetNode},
 * {@code getSubSteps}, {@code getSubExecutionPlans}, {@code reset}, {@code canBeCached})
 * and all branch paths in the {@code getIndent} helper.
 *
 * <p>Fixture classes implement {@link ExecutionStepInternal} directly (rather than
 * extending {@link AbstractExecutionStep}) so that the default method tests verify
 * the interface defaults, not the abstract class overrides.
 */
public class ExecutionStepInternalTest extends DbTestBase {

  // =========================================================================
  // Default method behavior
  // =========================================================================

  /**
   * The default {@link ExecutionStepInternal#getName()} returns the simple class
   * name of the concrete step implementation.
   */
  @Test
  public void getNameReturnsSimpleClassName() {
    var step = new MinimalTestStep();
    assertThat(step.getName()).isEqualTo("MinimalTestStep");
  }

  /**
   * The default {@link ExecutionStepInternal#getType()} returns the simple class
   * name, matching {@link ExecutionStepInternal#getName()} for consistent plan
   * comparison and analysis.
   */
  @Test
  public void getTypeMatchesGetName() {
    var step = new MinimalTestStep();
    assertThat(step.getType()).isEqualTo("MinimalTestStep");
    assertThat(step.getType()).isEqualTo(step.getName());
  }

  /**
   * The default {@link ExecutionStepInternal#getDescription()} delegates to
   * {@code prettyPrint(0, 3)}, producing a description at depth 0 with 3-space
   * indentation.
   */
  @Test
  public void getDescriptionDelegatesToPrettyPrint() {
    var step = new MinimalTestStep();
    assertThat(step.getDescription()).isEqualTo(step.prettyPrint(0, 3));
    // At depth 0, no indentation prefix
    assertThat(step.getDescription()).isEqualTo("MinimalTestStep");
  }

  /**
   * The default {@link ExecutionStepInternal#prettyPrint} at non-zero depth
   * prepends the correct amount of indentation before the class name.
   */
  @Test
  public void prettyPrintWithNonZeroDepthPrependsIndentation() {
    var step = new MinimalTestStep();
    // depth=2, indent=3 -> 6 spaces prefix
    assertThat(step.prettyPrint(2, 3)).isEqualTo("      MinimalTestStep");
  }

  /**
   * Embedded steps always report their target node as {@code "<local>"} since
   * they execute in-process rather than on a remote server.
   */
  @Test
  public void getTargetNodeReturnsLocal() {
    var step = new MinimalTestStep();
    assertThat(step.getTargetNode()).isEqualTo("<local>");
  }

  /**
   * By default, a step has no nested sub-steps (e.g., it is not a composite
   * step like CartesianProductStep).
   */
  @Test
  public void getSubStepsReturnsEmptyListByDefault() {
    var step = new MinimalTestStep();
    assertThat(step.getSubSteps()).isEmpty();
  }

  /**
   * By default, a step has no embedded sub-execution-plans (e.g., it does not
   * contain subqueries).
   */
  @Test
  public void getSubExecutionPlansReturnsEmptyListByDefault() {
    var step = new MinimalTestStep();
    assertThat(step.getSubExecutionPlans()).isEmpty();
  }

  /**
   * The default {@link ExecutionStepInternal#reset()} is a no-op. Steps without
   * internal mutable state (counters, buffers, cursors) do not need to reset
   * anything between re-executions.
   */
  @Test
  public void resetIsNoOpByDefault() {
    var step = new MinimalTestStep();
    // Should complete without throwing
    step.reset();
  }

  /**
   * The default {@link ExecutionStepInternal#canBeCached()} returns {@code false}
   * (safe-by-default: steps must explicitly opt in to caching).
   */
  @Test
  public void canBeCachedReturnsFalseByDefault() {
    var step = new MinimalTestStep();
    assertThat(step.canBeCached()).isFalse();
  }

  // =========================================================================
  // getIndent
  // =========================================================================

  /**
   * When depth is 0, no indentation is produced regardless of indent size.
   * The outer loop does not execute, so the result is always empty.
   */
  @Test
  public void getIndentWithZeroDepthReturnsEmptyString() {
    assertThat(ExecutionStepInternal.getIndent(0, 4)).isEmpty();
  }

  /**
   * When indent is 0, no spaces are produced per level regardless of depth.
   * The inner loop does not execute, so the result is always empty.
   */
  @Test
  public void getIndentWithZeroIndentReturnsEmptyString() {
    assertThat(ExecutionStepInternal.getIndent(3, 0)).isEmpty();
  }

  /**
   * With depth=2 and indent=3, the result is 6 spaces (2 levels * 3 spaces each).
   */
  @Test
  public void getIndentReturnsCorrectNumberOfSpaces() {
    assertThat(ExecutionStepInternal.getIndent(2, 3)).isEqualTo("      ");
  }

  // =========================================================================
  // Test fixture classes
  //
  // All step fixtures implement ExecutionStepInternal directly (not via
  // AbstractExecutionStep) to test the interface defaults without interference
  // from the abstract class. A shared BaseTestStep provides the common
  // boilerplate required by all fixtures.
  // =========================================================================

  /**
   * Common base for all test fixtures. Provides no-op implementations of
   * the abstract interface methods so that each concrete fixture only
   * declares its unique overrides.
   */
  private abstract static class BaseTestStep
      implements ExecutionStepInternal {

    @Override
    public ExecutionStream start(CommandContext ctx) {
      return ExecutionStream.empty();
    }

    @Override
    public void sendTimeout() {
    }

    @Override
    public void setPrevious(ExecutionStepInternal step) {
    }

    @Override
    public void setNext(ExecutionStepInternal step) {
    }

    @Override
    public void close() {
    }

    @Override
    public ExecutionStep copy(CommandContext ctx) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Minimal step that relies entirely on default methods. Used to verify that
   * defaults behave correctly when no override is present.
   */
  private static class MinimalTestStep extends BaseTestStep {
  }
}
