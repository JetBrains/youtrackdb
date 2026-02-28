package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Tests for {@link FetchFromIndexedFunctionStep}, a source step that fetches
 * records by executing an indexed function (e.g. a spatial function like
 * {@code ST_Within()}).
 *
 * <p>Covers:
 * <ul>
 *   <li>Records returned from the indexed function are produced as results</li>
 *   <li>Empty indexed function result produces an empty stream</li>
 *   <li>Predecessor step is drained for side effects before fetching</li>
 *   <li>No predecessor (prev == null) path works without error</li>
 *   <li>prettyPrint rendering with and without profiling, with indentation</li>
 *   <li>serialize / deserialize round-trip preserves condition and target</li>
 *   <li>Deserialization failure wraps exception in CommandExecutionException</li>
 *   <li>canBeCached always returns false (index state may change)</li>
 *   <li>copy produces an independent, functional step</li>
 * </ul>
 */
public class FetchFromIndexedFunctionStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: fetching records from indexed function
  // =========================================================================

  /**
   * When the indexed function returns records, the step should produce one
   * result per record in the returned collection.
   */
  @Test
  public void fetchesRecordsFromIndexedFunction() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    session.begin();
    var entity1 = session.newEntity(className);
    var entity2 = session.newEntity(className);
    var entity3 = session.newEntity(className);
    session.commit();

    var ids = List.<Identifiable>of(
        entity1.getIdentity(), entity2.getIdentity(), entity3.getIdentity());

    var condition = createCondition(ids);
    var target = createFromClause(className);
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(3);
    } finally {
      session.rollback();
    }
  }

  /**
   * When the indexed function returns an empty collection, the step should
   * produce an empty stream rather than throwing.
   */
  @Test
  public void emptyIndexedFunctionResultProducesEmptyStream() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("NonExistent");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  // =========================================================================
  // internalStart: predecessor draining
  // =========================================================================

  /**
   * When a predecessor step is chained before the source step (e.g. a
   * GlobalLetExpressionStep), the predecessor's stream must be started and
   * closed for its side effects before fetching from the indexed function.
   */
  @Test
  public void predecessorIsStartedAndClosedBeforeFetching() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("TestClass");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, false);

    var prevStarted = new AtomicBoolean(false);
    var prevStreamClosed = new AtomicBoolean(false);
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
            prevStreamClosed.set(true);
          }
        };
      }
    };
    step.setPrevious(prev);

    var results = drain(step.start(ctx), ctx);

    assertThat(prevStarted.get()).isTrue();
    assertThat(prevStreamClosed.get()).isTrue();
    assertThat(results).isEmpty();
  }

  /**
   * When no predecessor is set (prev == null), the step should still fetch
   * records from the indexed function without error.
   */
  @Test
  public void noPredecessorFetchesWithoutError() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("TestClass");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, false);
    // prev is null by default

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint without profiling renders the "FETCH FROM INDEXED FUNCTION"
   * label followed by the function condition's string representation.
   */
  @Test
  public void prettyPrintWithoutProfilingRendersLabel() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("City");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("FETCH FROM INDEXED FUNCTION");
    assertThat(output).doesNotContain("μs");
  }

  /**
   * prettyPrint with profiling appends the cost in microseconds.
   */
  @Test
  public void prettyPrintWithProfilingAppendsCost() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("City");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("FETCH FROM INDEXED FUNCTION");
    assertThat(output).contains("μs");
  }

  /**
   * prettyPrint with non-zero depth prepends the correct indentation
   * (depth * indent leading spaces).
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("City");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, false);

    // depth=1, indent=4 → 4 leading spaces
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
    assertThat(output).contains("FETCH FROM INDEXED FUNCTION");
  }

  // =========================================================================
  // serialize / deserialize
  // =========================================================================

  /**
   * Serialization round-trip: serialize a step, then deserialize a fresh
   * instance from the serialized form. The deserialized step should produce
   * a valid prettyPrint output containing the function condition.
   */
  @Test
  public void serializeDeserializeRoundTrip() {
    var ctx = newContext();
    var condition = createSerializableCondition();
    var target = createFromClause("City");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, false);

    var serialized = step.serialize(session);

    assertThat(serialized).isNotNull();
    assertThat((Object) serialized.getProperty("functionCondition")).isNotNull();
    assertThat((Object) serialized.getProperty("queryTarget")).isNotNull();

    // Deserialize into a fresh step and verify its toString includes
    // the condition (confirming fields were reconstructed).
    var restored = new FetchFromIndexedFunctionStep(
        new SQLBinaryCondition(-1), new SQLFromClause(-1), ctx, false);
    restored.deserialize(serialized, session);

    var output = restored.prettyPrint(0, 2);
    assertThat(output).contains("FETCH FROM INDEXED FUNCTION");
  }

  /**
   * When deserialization encounters invalid serialized data, the exception
   * is wrapped in a {@link CommandExecutionException}.
   */
  @Test
  public void deserializeWithInvalidDataWrapsException() {
    var ctx = newContext();
    var step = new FetchFromIndexedFunctionStep(
        new SQLBinaryCondition(-1), new SQLFromClause(-1), ctx, false);

    // Create an invalid serialized result that will cause deserialization to fail.
    // Setting functionCondition to a non-Result value will trigger NPE/ClassCastException
    // inside SQLBinaryCondition.deserialize, which is caught and wrapped.
    var badResult = new ResultInternal(session);
    badResult.setProperty("functionCondition", "not-a-result");

    assertThatThrownBy(() -> step.deserialize(badResult, session))
        .isInstanceOf(CommandExecutionException.class);
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /**
   * FetchFromIndexedFunctionStep is never cacheable because indexed function
   * results depend on the current spatial/full-text index state which may
   * change between executions.
   */
  @Test
  public void stepIsNeverCacheable() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("City");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, false);

    assertThat(step.canBeCached()).isFalse();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * copy() should produce a new FetchFromIndexedFunctionStep that is
   * structurally equivalent but not the same instance. The copy should
   * preserve the profiling setting and produce results independently.
   */
  @Test
  public void copyProducesIndependentStepWithSameSettings() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("City");
    var step = new FetchFromIndexedFunctionStep(condition, target, ctx, true);

    var copied = step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied).isInstanceOf(FetchFromIndexedFunctionStep.class);
    var copiedStep = (FetchFromIndexedFunctionStep) copied;
    assertThat(copiedStep.isProfilingEnabled()).isTrue();
    // The copy should also not be cacheable
    assertThat(copiedStep.canBeCached()).isFalse();
  }

  /**
   * A copied step should be fully functional — producing results from the
   * indexed function independently of the original step.
   */
  @Test
  public void copiedStepFetchesIndependently() {
    var ctx = newContext();
    var condition = createCondition(Collections.emptyList());
    var target = createFromClause("City");
    var original = new FetchFromIndexedFunctionStep(condition, target, ctx, false);
    var copied = (FetchFromIndexedFunctionStep) original.copy(ctx);

    var results = drain(copied.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Creates an SQLBinaryCondition whose executeIndexedFunction() returns
   * the given collection of records. Uses a custom left expression that
   * overrides executeIndexedFunction to provide test data.
   */
  private static SQLBinaryCondition createCondition(
      List<Identifiable> records) {
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(new SQLExpression(-1) {
      @Override
      public Iterable<Identifiable> executeIndexedFunction(
          SQLFromClause target, CommandContext context,
          SQLBinaryCompareOperator operator, Object right) {
        return records;
      }

      @Override
      public Object execute(Result iCurrentRecord, CommandContext ctx) {
        return true;
      }

      @Override
      public SQLExpression copy() {
        return createCondition(records).getLeft();
      }

      @Override
      public void toString(Map<Object, Object> params, StringBuilder builder) {
        builder.append("testIndexedFunction()");
      }
    });
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1) {
      @Override
      public Object execute(Result iCurrentRecord, CommandContext ctx) {
        return true;
      }

      @Override
      public SQLExpression copy() {
        return createCondition(records).getRight();
      }

      @Override
      public void toString(Map<Object, Object> params, StringBuilder builder) {
        builder.append("true");
      }
    });
    return condition;
  }

  /**
   * Creates a fully serializable SQLBinaryCondition using minimal parser
   * objects that round-trip through serialize/deserialize correctly.
   * Uses bare SQLExpressions without mathExpression to avoid reflection
   * issues in SQLMathExpression.deserializeFromResult.
   */
  private static SQLBinaryCondition createSerializableCondition() {
    var condition = new SQLBinaryCondition(-1);

    // Left and right: bare expressions (no mathExpression) that serialize
    // to minimal JSON and deserialize without reflection-based constructor lookup
    condition.setLeft(new SQLExpression(-1));
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    return condition;
  }

  /**
   * Creates an SQLFromClause targeting the given class name.
   */
  private static SQLFromClause createFromClause(String className) {
    var fromClause = new SQLFromClause(-1);
    var fromItem = new SQLFromItem(-1);
    fromItem.setIdentifier(new SQLIdentifier(className));
    fromClause.setItem(fromItem);
    return fromClause;
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
