package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.Test;

/**
 * Tests for the {@link ExecutionStepInternal} interface, covering default methods,
 * the static {@code getIndent} utility, and the static {@code basicSerialize} /
 * {@code basicDeserialize} serialization helpers.
 *
 * <p>The test verifies the contract of every default method and exercises all
 * branch paths in the static serialization methods to ensure correct round-trip
 * behavior for steps with sub-steps and sub-execution-plans.
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
   * The default {@link ExecutionStepInternal#serialize} throws
   * {@link UnsupportedOperationException} because not all steps support
   * serialization.
   */
  @Test
  public void serializeThrowsUnsupportedByDefault() {
    var step = new MinimalTestStep();
    assertThatThrownBy(() -> step.serialize(session))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * The default {@link ExecutionStepInternal#deserialize} throws
   * {@link UnsupportedOperationException} because not all steps support
   * deserialization.
   */
  @Test
  public void deserializeThrowsUnsupportedByDefault() {
    var step = new MinimalTestStep();
    var result = new ResultInternal(session);
    assertThatThrownBy(() -> step.deserialize(result, session))
        .isInstanceOf(UnsupportedOperationException.class);
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
  // basicSerialize
  // =========================================================================

  /**
   * basicSerialize always records the step's fully-qualified Java class name
   * under the {@link InternalExecutionPlan#JAVA_TYPE} property key, which is
   * needed for reflection-based deserialization.
   */
  @Test
  public void basicSerializeRecordsJavaTypeForStep() {
    var step = new SerializableTestStep();
    var result = ExecutionStepInternal.basicSerialize(session, step);

    assertThat(result.<String>getProperty(InternalExecutionPlan.JAVA_TYPE))
        .isEqualTo(SerializableTestStep.class.getName());
  }

  /**
   * When a step has no sub-steps and no sub-execution-plans, basicSerialize
   * only records the javaType property. The "subSteps" and "subExecutionPlans"
   * properties are not set.
   */
  @Test
  public void basicSerializeWithNoSubStepsAndNoSubPlans() {
    var step = new SerializableTestStep();
    var result = ExecutionStepInternal.basicSerialize(session, step);

    assertThat(result.getPropertyNames()).doesNotContain("subSteps");
    assertThat(result.getPropertyNames())
        .doesNotContain("subExecutionPlans");
  }

  /**
   * When a step has sub-steps, basicSerialize serializes each sub-step by
   * calling its {@code serialize()} method and stores them in the "subSteps"
   * property.
   */
  @Test
  public void basicSerializeWithSubStepsSerializesThem() {
    var subStep = new SerializableTestStep();
    subStep.testData = "sub-step-data";

    var step = new StepWithSubSteps(List.of(subStep));
    var result = ExecutionStepInternal.basicSerialize(session, step);

    List<Result> serializedSubSteps = result.getProperty("subSteps");
    assertThat(serializedSubSteps).hasSize(1);
    assertThat(serializedSubSteps.get(0)
        .<String>getProperty(InternalExecutionPlan.JAVA_TYPE))
        .isEqualTo(SerializableTestStep.class.getName());
    assertThat(serializedSubSteps.get(0).<String>getProperty("testData"))
        .isEqualTo("sub-step-data");
  }

  /**
   * When a step has sub-execution-plans, basicSerialize serializes each plan
   * and stores them in the "subExecutionPlans" property.
   */
  @Test
  public void basicSerializeWithSubExecutionPlansSerializesThem() {
    var subPlan = new SerializableTestPlan();
    var step = new StepWithSubPlans(List.of(subPlan));
    var result = ExecutionStepInternal.basicSerialize(session, step);

    List<Result> serializedPlans = result.getProperty("subExecutionPlans");
    assertThat(serializedPlans).hasSize(1);
    assertThat(serializedPlans.get(0)
        .<String>getProperty(InternalExecutionPlan.JAVA_TYPE))
        .isEqualTo(SerializableTestPlan.class.getName());
  }

  /**
   * When {@code getSubExecutionPlans()} returns null (rather than an empty list),
   * basicSerialize skips the sub-plans property entirely. This exercises the
   * null-safety guard in the {@code getSubExecutionPlans() != null} check.
   */
  @Test
  public void basicSerializeWithNullSubExecutionPlansOmitsProperty() {
    var step = new StepWithNullSubPlans();
    var result = ExecutionStepInternal.basicSerialize(session, step);

    assertThat(result.getPropertyNames())
        .doesNotContain("subExecutionPlans");
  }

  // =========================================================================
  // basicDeserialize
  // =========================================================================

  /**
   * When the serialized result has no "subSteps" or "subExecutionPlans"
   * properties, basicDeserialize leaves the step's lists unchanged (empty).
   */
  @Test
  public void basicDeserializeWithNoSubStepsOrPlansIsNoOp()
      throws Exception {
    var serialized = new ResultInternal(session);
    var step = new DeserializableTestStep();

    ExecutionStepInternal.basicDeserialize(serialized, step, session);

    assertThat(step.getSubSteps()).isEmpty();
    assertThat(step.getSubExecutionPlans()).isEmpty();
  }

  /**
   * basicDeserialize reconstructs sub-steps from serialized data by:
   * 1. Reading the javaType property to determine the step class
   * 2. Instantiating it via reflection (no-arg constructor)
   * 3. Calling deserialize() on the new instance
   * 4. Adding the reconstructed step to the parent's sub-step list
   */
  @Test
  public void basicDeserializeReconstructsSubSteps() throws Exception {
    var subStepResult = new ResultInternal(session);
    subStepResult.setProperty(InternalExecutionPlan.JAVA_TYPE,
        SerializableTestStep.class.getName());
    subStepResult.setProperty("testData", "recovered");

    var serialized = new ResultInternal(session);
    serialized.setProperty("subSteps", List.of(subStepResult));

    var step = new DeserializableTestStep();
    ExecutionStepInternal.basicDeserialize(serialized, step, session);

    assertThat(step.getSubSteps()).hasSize(1);
    var reconstructed =
        (SerializableTestStep) step.getSubSteps().get(0);
    assertThat(reconstructed.testData).isEqualTo("recovered");
  }

  /**
   * basicDeserialize reconstructs sub-execution-plans from serialized data
   * using the same reflection-based pattern as sub-steps.
   */
  @Test
  public void basicDeserializeReconstructsSubExecutionPlans()
      throws Exception {
    var subPlanResult = new ResultInternal(session);
    subPlanResult.setProperty(InternalExecutionPlan.JAVA_TYPE,
        SerializableTestPlan.class.getName());

    var serialized = new ResultInternal(session);
    serialized.setProperty("subExecutionPlans", List.of(subPlanResult));

    var step = new DeserializableTestStep();
    ExecutionStepInternal.basicDeserialize(serialized, step, session);

    assertThat(step.getSubExecutionPlans()).hasSize(1);
    assertThat(step.getSubExecutionPlans().get(0))
        .isInstanceOf(SerializableTestPlan.class);
  }

  /**
   * Round-trip test: serialize a step with sub-steps via basicSerialize, then
   * deserialize the result via basicDeserialize. The reconstructed sub-steps
   * should preserve all serialized properties.
   */
  @Test
  public void serializeDeserializeRoundTripPreservesSubSteps()
      throws Exception {
    var subStep = new SerializableTestStep();
    subStep.testData = "round-trip-value";
    var original = new StepWithSubSteps(List.of(subStep));

    var serialized =
        ExecutionStepInternal.basicSerialize(session, original);

    var target = new DeserializableTestStep();
    ExecutionStepInternal.basicDeserialize(serialized, target, session);

    assertThat(target.getSubSteps()).hasSize(1);
    var recovered =
        (SerializableTestStep) target.getSubSteps().get(0);
    assertThat(recovered.testData).isEqualTo("round-trip-value");
  }

  /**
   * Round-trip test: serialize a step with sub-execution-plans via
   * basicSerialize, then deserialize via basicDeserialize. The reconstructed
   * plans should be instances of the correct class.
   */
  @Test
  public void serializeDeserializeRoundTripPreservesSubPlans()
      throws Exception {
    var subPlan = new SerializableTestPlan();
    var original = new StepWithSubPlans(List.of(subPlan));

    var serialized =
        ExecutionStepInternal.basicSerialize(session, original);

    var target = new DeserializableTestStep();
    ExecutionStepInternal.basicDeserialize(serialized, target, session);

    assertThat(target.getSubExecutionPlans()).hasSize(1);
    assertThat(target.getSubExecutionPlans().get(0))
        .isInstanceOf(SerializableTestPlan.class);
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

  /**
   * Public test step with a public no-arg constructor, required for
   * {@code Class.forName(className).newInstance()} inside
   * {@link ExecutionStepInternal#basicDeserialize}. Supports
   * serialize/deserialize to enable round-trip testing.
   */
  public static class SerializableTestStep extends BaseTestStep {

    public String testData;

    public SerializableTestStep() {
    }

    @Override
    public Result serialize(DatabaseSessionEmbedded session) {
      var result =
          ExecutionStepInternal.basicSerialize(session, this);
      result.setProperty("testData", testData);
      return result;
    }

    @Override
    public void deserialize(
        Result fromResult, DatabaseSessionEmbedded session) {
      try {
        ExecutionStepInternal.basicDeserialize(
            fromResult, this, session);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      testData = fromResult.getProperty("testData");
    }
  }

  /**
   * Public test plan with a public no-arg constructor, required for
   * {@code Class.forName(className).newInstance()} inside
   * {@link ExecutionStepInternal#basicDeserialize}. Implements the minimum
   * {@link InternalExecutionPlan} contract.
   */
  public static class SerializableTestPlan
      implements InternalExecutionPlan {

    public SerializableTestPlan() {
    }

    @Override
    public void close() {
    }

    @Override
    public ExecutionStream start() {
      return ExecutionStream.empty();
    }

    @Override
    public void reset(CommandContext ctx) {
    }

    @Override
    public CommandContext getContext() {
      return null;
    }

    @Override
    public long getCost() {
      return 0;
    }

    @Override
    public boolean canBeCached() {
      return false;
    }

    @Override
    public @Nonnull List<ExecutionStep> getSteps() {
      return Collections.emptyList();
    }

    @Override
    public @Nonnull String prettyPrint(int depth, int indent) {
      return "";
    }

    @Override
    public @Nonnull Result toResult(
        @Nullable DatabaseSessionEmbedded db) {
      return new ResultInternal(db);
    }

    @Override
    public Result serialize(DatabaseSessionEmbedded session) {
      var result = new ResultInternal(session);
      result.setProperty(
          InternalExecutionPlan.JAVA_TYPE, getClass().getName());
      return result;
    }

    @Override
    public void deserialize(
        Result fromResult, DatabaseSessionEmbedded session) {
      // No-op: test fixture has no state to restore.
    }
  }

  /**
   * Step that exposes a non-empty sub-step list. Used to exercise the
   * sub-step serialization branch in
   * {@link ExecutionStepInternal#basicSerialize}.
   */
  private static class StepWithSubSteps extends BaseTestStep {

    private final List<ExecutionStep> subSteps;

    StepWithSubSteps(List<? extends ExecutionStep> subSteps) {
      this.subSteps = new ArrayList<>(subSteps);
    }

    @Override
    public @Nonnull List<ExecutionStep> getSubSteps() {
      return subSteps;
    }
  }

  /**
   * Step that exposes a non-empty sub-execution-plan list. Used to exercise
   * the sub-plan serialization branch in
   * {@link ExecutionStepInternal#basicSerialize}.
   */
  private static class StepWithSubPlans extends BaseTestStep {

    private final List<ExecutionPlan> subPlans;

    StepWithSubPlans(List<? extends ExecutionPlan> subPlans) {
      this.subPlans = new ArrayList<>(subPlans);
    }

    @Override
    public List<ExecutionPlan> getSubExecutionPlans() {
      return subPlans;
    }
  }

  /**
   * Step whose {@code getSubExecutionPlans()} returns null (instead of an
   * empty list). Used to exercise the null-safety guard in
   * {@link ExecutionStepInternal#basicSerialize}.
   */
  private static class StepWithNullSubPlans extends BaseTestStep {

    @Override
    public List<ExecutionPlan> getSubExecutionPlans() {
      return null;
    }
  }

  /**
   * Step with mutable sub-step and sub-plan lists, used as the target for
   * {@link ExecutionStepInternal#basicDeserialize}. The default
   * implementations return immutable empty lists, so this override is needed
   * to allow basicDeserialize to add reconstructed elements.
   */
  public static class DeserializableTestStep extends BaseTestStep {

    private final List<ExecutionStep> subSteps = new ArrayList<>();
    private final List<ExecutionPlan> subPlans = new ArrayList<>();

    public DeserializableTestStep() {
    }

    @Override
    public @Nonnull List<ExecutionStep> getSubSteps() {
      return subSteps;
    }

    @Override
    public List<ExecutionPlan> getSubExecutionPlans() {
      return subPlans;
    }
  }
}
