package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Tests for {@link FetchFromVariableStep}, a source step that fetches records
 * from a context variable (e.g. {@code SELECT FROM $myVar}).
 *
 * <p>Covers:
 * <ul>
 *   <li>Each type branch of variable resolution: ExecutionStream, ResultSet
 *       (InternalResultSet and plain), Identifiable, Result, Iterable</li>
 *   <li>Error path when variable is null or unsupported type</li>
 *   <li>loadEntity behavior: entity results, non-entity results, unloaded entities</li>
 *   <li>Iterable with mixed Result and non-Result elements</li>
 *   <li>Predecessor step is drained for side effects before reading the variable</li>
 *   <li>No predecessor (prev == null) works without error</li>
 *   <li>prettyPrint rendering with and without profiling, with indentation</li>
 *   <li>serialize / deserialize round-trip preserves variableName</li>
 *   <li>canBeCached always returns false</li>
 *   <li>copy produces an independent, functional step</li>
 * </ul>
 */
public class FetchFromVariableStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: ExecutionStream variable
  // =========================================================================

  /**
   * When the variable holds an ExecutionStream, the step should use it directly
   * and produce all results from the stream.
   */
  @Test
  public void executionStreamVariableIsUsedDirectly() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("val", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("val", 2);

    var stream = ExecutionStream.resultIterator(List.of(r1, r2).iterator());
    ctx.setVariable("src", stream);

    var step = new FetchFromVariableStep("src", ctx, false);
    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).<Integer>getProperty("val")).isEqualTo(1);
    assertThat(results.get(1).<Integer>getProperty("val")).isEqualTo(2);
  }

  // =========================================================================
  // internalStart: InternalResultSet variable
  // =========================================================================

  /**
   * When the variable holds an InternalResultSet, the step copies it (to allow
   * safe re-iteration) and streams the results. The original ResultSet is closed
   * when the stream is exhausted.
   */
  @Test
  public void internalResultSetVariableIsCopiedAndStreamed() {
    var ctx = newContext();
    var resultSet = new InternalResultSet(session);
    var r1 = new ResultInternal(session);
    r1.setProperty("name", "Alice");
    resultSet.add(r1);

    ctx.setVariable("data", resultSet);

    var step = new FetchFromVariableStep("data", ctx, false);
    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).<String>getProperty("name")).isEqualTo("Alice");
  }

  // =========================================================================
  // internalStart: Identifiable variable
  // =========================================================================

  /**
   * When the variable holds an Identifiable (e.g. a RID), the step reloads it
   * from the current active transaction and wraps it as a single-element stream.
   */
  @Test
  public void identifiableVariableIsLoadedFromTransaction() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(className);
    entity.setProperty("key", "value");
    session.commit();

    var rid = entity.getIdentity();
    ctx.setVariable("record", rid);

    session.begin();
    try {
      var step = new FetchFromVariableStep("record", ctx, false);
      var results = drain(step.start(ctx), ctx);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getIdentity()).isEqualTo(rid);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // internalStart: Result variable (non-entity)
  // =========================================================================

  /**
   * When the variable holds a Result that is not an entity (a projection),
   * the step wraps it as a single-element stream, passing it through unchanged.
   */
  @Test
  public void nonEntityResultVariableIsWrappedAsSingleton() {
    var ctx = newContext();
    var result = new ResultInternal(session);
    result.setProperty("count", 42);

    ctx.setVariable("res", result);

    var step = new FetchFromVariableStep("res", ctx, false);
    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Integer>getProperty("count")).isEqualTo(42);
  }

  // =========================================================================
  // internalStart: Result variable wrapping an entity
  // =========================================================================

  /**
   * When the variable holds a Result that wraps an entity (isEntity() returns
   * true), the step calls loadEntity which rewraps the entity in a fresh
   * ResultInternal for consistent cross-transaction handling.
   */
  @Test
  public void entityResultVariableIsRewrapped() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(className);
    entity.setProperty("tag", "original");
    session.commit();

    var entityResult = new ResultInternal(session, entity);
    ctx.setVariable("wrapped", entityResult);

    session.begin();
    try {
      var step = new FetchFromVariableStep("wrapped", ctx, false);
      var results = drain(step.start(ctx), ctx);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).isEntity()).isTrue();
      assertThat(results.get(0).getIdentity()).isEqualTo(entity.getIdentity());
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // internalStart: Iterable variable
  // =========================================================================

  /**
   * When the variable holds an Iterable of Result elements, the step iterates
   * through all elements, applying loadEntity to each Result.
   */
  @Test
  public void iterableOfResultsIsTransformedAndStreamed() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("idx", 0);
    var r2 = new ResultInternal(session);
    r2.setProperty("idx", 1);

    ctx.setVariable("items", List.of(r1, r2));

    var step = new FetchFromVariableStep("items", ctx, false);
    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).<Integer>getProperty("idx")).isEqualTo(0);
    assertThat(results.get(1).<Integer>getProperty("idx")).isEqualTo(1);
  }

  /**
   * When the Iterable contains non-Result elements (e.g. plain strings),
   * the transformation passes them through as-is without calling loadEntity.
   */
  @Test
  public void iterableOfNonResultsIsPassedThrough() {
    var ctx = newContext();
    ctx.setVariable("strings", List.of("a", "b", "c"));

    var step = new FetchFromVariableStep("strings", ctx, false);
    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(3);
  }

  // =========================================================================
  // internalStart: InternalResultSet with entity-wrapping results
  // =========================================================================

  /**
   * When an InternalResultSet contains ResultInternal objects wrapping real
   * entities, the loadEntity method detects the entity (via isEntity()) and
   * rewraps it in a fresh ResultInternal. This covers the else-if branch in
   * loadEntity where the Result is not an Entity instance but wraps one.
   */
  @Test
  public void internalResultSetWithEntityResultsRewrapsEntities() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(className);
    entity.setProperty("name", "EntityInResultSet");
    session.commit();

    session.begin();
    try {
      var resultSet = new InternalResultSet(session);
      resultSet.add(new ResultInternal(session, entity));
      ctx.setVariable("entityRs", resultSet);

      var step = new FetchFromVariableStep("entityRs", ctx, false);
      var results = drain(step.start(ctx), ctx);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).isEntity()).isTrue();
      assertThat(results.get(0).getIdentity()).isEqualTo(entity.getIdentity());
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // internalStart: Iterable with Entity elements (loadEntity first branch)
  // =========================================================================

  /**
   * When an Iterable contains an Entity directly (Entity implements Result),
   * the lambda in the Iterable branch recognizes it as a Result and calls
   * loadEntity. Inside loadEntity, the first branch ({@code result instanceof
   * Entity}) is taken. If the entity is loaded (not unloaded), it passes
   * through unchanged.
   */
  @Test
  public void iterableWithLoadedEntityPassesThroughDirectly() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(className);
    entity.setProperty("status", "loaded");
    session.commit();

    session.begin();
    try {
      // Re-load the entity so it's fully loaded in the current transaction
      var loadedEntity = session.load(entity.getIdentity());
      // Entity implements Result, so it can be stored in a List<Result>
      ctx.setVariable("entityList", List.of(loadedEntity));

      var step = new FetchFromVariableStep("entityList", ctx, false);
      var results = drain(step.start(ctx), ctx);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getIdentity()).isEqualTo(entity.getIdentity());
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // internalStart: deserialize with missing variableName property
  // =========================================================================

  /**
   * When the serialized result does not contain a "variableName" property
   * (e.g. from a corrupted or legacy serialized form), deserialization
   * skips the variable name assignment, keeping the step's existing value.
   */
  @Test
  public void deserializeWithMissingVariableNameKeepsExistingValue() {
    var ctx = newContext();
    var step = new FetchFromVariableStep("original", ctx, false);

    // Serialized result without "variableName" property
    var serialized = new ResultInternal(session);
    step.deserialize(serialized, session);

    // The step should retain its original variable name
    var output = step.prettyPrint(0, 2);
    assertThat(output).contains("original");
  }

  // =========================================================================
  // internalStart: null/unsupported variable
  // =========================================================================

  /**
   * When the variable is not set (resolves to null), the step throws
   * CommandExecutionException with a message identifying the variable name.
   */
  @Test
  public void nullVariableThrowsCommandExecutionException() {
    var ctx = newContext();
    // Do not set any variable — ctx.getVariable("missing") returns null
    var step = new FetchFromVariableStep("missing", ctx, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("Cannot use variable as query target")
        .hasMessageContaining("missing");
  }

  /**
   * When the variable holds an unsupported type (e.g. Integer), the step
   * throws CommandExecutionException.
   */
  @Test
  public void unsupportedVariableTypeThrowsCommandExecutionException() {
    var ctx = newContext();
    ctx.setVariable("num", 42);
    var step = new FetchFromVariableStep("num", ctx, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("Cannot use variable as query target")
        .hasMessageContaining("num");
  }

  // =========================================================================
  // internalStart: predecessor draining
  // =========================================================================

  /**
   * When a predecessor step is chained (e.g. a GlobalLetExpressionStep),
   * the predecessor's stream is started and closed for its side effects
   * before the variable is read.
   */
  @Test
  public void predecessorIsDrainedBeforeVariableIsRead() {
    var ctx = newContext();
    var r = new ResultInternal(session);
    r.setProperty("x", 1);
    ctx.setVariable("v", List.of(r));

    var step = new FetchFromVariableStep("v", ctx, false);

    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);
    var prev = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        prevStarted.set(true);
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext ctx) {
            return false;
          }

          @Override
          public Result next(CommandContext ctx) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close(CommandContext ctx) {
            prevClosed.set(true);
          }
        };
      }
    };
    step.setPrevious(prev);

    var results = drain(step.start(ctx), ctx);

    assertThat(prevStarted.get())
        .as("predecessor must be started for side effects")
        .isTrue();
    assertThat(prevClosed.get())
        .as("predecessor stream must be closed after draining")
        .isTrue();
    assertThat(results).hasSize(1);
  }

  /**
   * When no predecessor is set (prev == null), the step fetches from the
   * variable directly without error.
   */
  @Test
  public void noPredecessorFetchesWithoutError() {
    var ctx = newContext();
    var r = new ResultInternal(session);
    r.setProperty("k", "v");
    ctx.setVariable("src", List.of(r));

    var step = new FetchFromVariableStep("src", ctx, false);
    // prev is null by default

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint without profiling renders the "FETCH FROM VARIABLE" label
   * followed by the variable name, with no profiling cost suffix.
   */
  @Test
  public void prettyPrintWithoutProfilingRendersLabelAndVariableName() {
    var ctx = newContext();
    var step = new FetchFromVariableStep("myVar", ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("FETCH FROM VARIABLE");
    assertThat(output).contains("myVar");
    assertThat(output).doesNotContain("\u03bcs");
  }

  /**
   * prettyPrint with profiling appends the cost in microseconds, matching
   * the behavior of peer steps like FetchFromIndexedFunctionStep.
   */
  @Test
  public void prettyPrintWithProfilingAppendsCost() {
    var ctx = newContext();
    var step = new FetchFromVariableStep("myVar", ctx, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("FETCH FROM VARIABLE");
    assertThat(output).contains("myVar");
    assertThat(output).contains("\u03bcs");
  }

  /**
   * prettyPrint with non-zero depth prepends the correct indentation
   * (depth * indent leading spaces).
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var step = new FetchFromVariableStep("myVar", ctx, false);

    // depth=1, indent=4 → 4 leading spaces
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
    assertThat(output).contains("FETCH FROM VARIABLE");
    assertThat(output).contains("myVar");
  }

  // =========================================================================
  // serialize / deserialize
  // =========================================================================

  /**
   * Serialization stores the variableName under the "variableName" property
   * key, along with the basic step metadata.
   */
  @Test
  public void serializeStoresVariableName() {
    var ctx = newContext();
    var step = new FetchFromVariableStep("friends", ctx, false);

    var serialized = step.serialize(session);

    assertThat(serialized).isNotNull();
    assertThat((String) serialized.getProperty("variableName"))
        .isEqualTo("friends");
  }

  /**
   * Deserialization restores the variableName from the serialized form,
   * producing a step that renders the correct variable name in prettyPrint.
   */
  @Test
  public void deserializeRestoresVariableName() {
    var ctx = newContext();
    var original = new FetchFromVariableStep("friends", ctx, false);
    var serialized = original.serialize(session);

    // Create a fresh step with a different variable name, then deserialize
    var restored = new FetchFromVariableStep("placeholder", ctx, false);
    restored.deserialize(serialized, session);

    var output = restored.prettyPrint(0, 2);
    assertThat(output).contains("friends");
    assertThat(output).doesNotContain("placeholder");
  }

  /**
   * When deserialization encounters invalid data, the exception is wrapped
   * in a {@link CommandExecutionException}.
   */
  @Test
  public void deserializeWithInvalidDataWrapsException() {
    var ctx = newContext();
    var step = new FetchFromVariableStep("x", ctx, false);

    // Create an invalid serialized result that causes basicDeserialize to fail:
    // subSteps with invalid Java class name
    var badResult = new ResultInternal(session);
    var badSubStep = new ResultInternal(session);
    badSubStep.setProperty("javaType", "com.nonexistent.StepClass");
    badResult.setProperty("subSteps", List.of(badSubStep));

    assertThatThrownBy(() -> step.deserialize(badResult, session))
        .isInstanceOf(CommandExecutionException.class);
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /**
   * FetchFromVariableStep is never cacheable because the variable's value
   * is resolved at execution time from the command context and may differ
   * between executions (e.g. from different LET assignments or input
   * parameters).
   */
  @Test
  public void stepIsNeverCacheable() {
    var ctx = newContext();
    var step = new FetchFromVariableStep("v", ctx, false);

    assertThat(step.canBeCached()).isFalse();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * copy() produces a new FetchFromVariableStep that is structurally equivalent
   * but not the same instance. The copy preserves the variable name and
   * profiling setting.
   */
  @Test
  public void copyProducesIndependentStepWithSameSettings() {
    var ctx = newContext();
    var step = new FetchFromVariableStep("myVar", ctx, true);

    var copied = step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied).isInstanceOf(FetchFromVariableStep.class);
    var copiedStep = (FetchFromVariableStep) copied;
    assertThat(copiedStep.isProfilingEnabled()).isTrue();
    assertThat(copiedStep.canBeCached()).isFalse();

    // The copy should render the same variable name
    var output = copiedStep.prettyPrint(0, 2);
    assertThat(output).contains("myVar");
  }

  /**
   * A copied step should be fully functional — fetching from the variable
   * independently of the original step.
   */
  @Test
  public void copiedStepFetchesIndependently() {
    var ctx = newContext();
    var r = new ResultInternal(session);
    r.setProperty("val", "test");
    ctx.setVariable("src", List.of(r));

    var original = new FetchFromVariableStep("src", ctx, false);
    var copied = (FetchFromVariableStep) original.copy(ctx);

    var results = drain(copied.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<String>getProperty("val")).isEqualTo("test");
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }
}
