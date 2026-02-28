package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Tests the {@link ConvertToResultInternalStep} which converts {@link UpdatableResult} instances
 * back to immutable {@link ResultInternal} instances after DML operations.
 *
 * <p>Covers:
 * <ul>
 *   <li>Entity-wrapping conversion: UpdatableResult with entity → new ResultInternal</li>
 *   <li>Non-UpdatableResult filtering: plain ResultInternal items are discarded</li>
 *   <li>Mixed stream handling: only UpdatableResult entities survive the filter</li>
 *   <li>Error on missing predecessor step</li>
 *   <li>Pretty-print rendering with and without profiling</li>
 *   <li>Cacheability and copy contracts</li>
 * </ul>
 */
public class ConvertToResultInternalStepTest extends DbTestBase {

  // --- Core conversion logic ---

  /**
   * UpdatableResult rows wrapping entities should be unwrapped into fresh
   * ResultInternal instances that expose the same entity and its properties.
   */
  @Test
  public void updatableResultWithEntityIsConvertedToResultInternal() {
    session.begin();
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    var entity = session.newEntity();
    entity.setProperty("name", "Alice");
    entity.setProperty("age", 30);

    step.setPrevious(sourceStep(ctx, List.of(new UpdatableResult(session, entity))));
    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(1);
    var converted = results.get(0);
    // Must be a plain ResultInternal, not an UpdatableResult
    assertThat(converted.getClass()).isEqualTo(ResultInternal.class);
    // Entity properties must be preserved through conversion
    assertThat(converted.asEntity().<String>getProperty("name")).isEqualTo("Alice");
    assertThat(converted.asEntity().<Integer>getProperty("age")).isEqualTo(30);
    session.commit();
  }

  /**
   * Multiple UpdatableResult rows should all be converted while preserving order
   * and individual entity data.
   */
  @Test
  public void multipleUpdatableResultsAreConvertedInOrder() {
    session.begin();
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    var entity1 = session.newEntity();
    entity1.setProperty("index", 1);
    var entity2 = session.newEntity();
    entity2.setProperty("index", 2);
    var entity3 = session.newEntity();
    entity3.setProperty("index", 3);

    step.setPrevious(sourceStep(ctx, List.of(
        new UpdatableResult(session, entity1),
        new UpdatableResult(session, entity2),
        new UpdatableResult(session, entity3))));
    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(3);
    for (int i = 0; i < 3; i++) {
      assertThat(results.get(i).getClass()).isEqualTo(ResultInternal.class);
      assertThat(results.get(i).asEntity().<Integer>getProperty("index")).isEqualTo(i + 1);
    }
    session.commit();
  }

  // --- Filtering logic ---

  /**
   * Plain ResultInternal items (not produced by DML) should be silently
   * discarded because they were not created by the DML step.
   */
  @Test
  public void nonUpdatableResultsAreFilteredOut() {
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    var plainResult = new ResultInternal(session);
    plainResult.setProperty("key", "value");

    step.setPrevious(sourceStep(ctx, List.of(plainResult)));
    var results = drain(step.start(ctx), ctx);

    assertThat(results).isEmpty();
  }

  /**
   * A mixed stream of UpdatableResult and plain ResultInternal items should
   * retain only the UpdatableResult-originated entries after conversion.
   */
  @Test
  public void mixedStreamRetainsOnlyConvertedUpdatableResults() {
    session.begin();
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    var entity = session.newEntity();
    entity.setProperty("source", "dml");
    var updatable = new UpdatableResult(session, entity);

    var plainResult = new ResultInternal(session);
    plainResult.setProperty("source", "query");

    // Feed mixed stream: plain, updatable, plain
    step.setPrevious(sourceStep(ctx, List.of(plainResult, updatable, plainResult)));
    var results = drain(step.start(ctx), ctx);

    // Only the UpdatableResult with entity survives
    assertThat(results).hasSize(1);
    assertThat(results.get(0).asEntity().<String>getProperty("source")).isEqualTo("dml");
    session.commit();
  }

  /**
   * An empty upstream produces an empty result -- no conversion needed.
   */
  @Test
  public void emptyUpstreamProducesEmptyResult() {
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    step.setPrevious(sourceStep(ctx, List.of()));
    var results = drain(step.start(ctx), ctx);

    assertThat(results).isEmpty();
  }

  // --- Error handling ---

  /**
   * Starting the step without a predecessor should fail with a clear error
   * because ConvertToResultInternalStep cannot produce data on its own.
   */
  @Test
  public void startWithoutPreviousStepThrowsIllegalState() {
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires a previous step");
  }

  // --- prettyPrint ---

  /**
   * Without profiling, prettyPrint should render the step label without
   * timing information.
   */
  @Test
  public void prettyPrintWithoutProfilingRendersLabelOnly() {
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CONVERT TO REGULAR RESULT ITEM");
    assertThat(output).doesNotContain("μs");
  }

  /**
   * With profiling enabled, prettyPrint should include the cost suffix
   * in microseconds.
   */
  @Test
  public void prettyPrintWithProfilingIncludesCost() {
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CONVERT TO REGULAR RESULT ITEM");
    assertThat(output).contains("μs");
  }

  /**
   * prettyPrint with non-zero depth applies the expected indentation.
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    // depth=1, indent=4 → 4 leading spaces
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
    assertThat(output).contains("CONVERT TO REGULAR RESULT ITEM");
  }

  // --- canBeCached ---

  /**
   * This step is stateless (it only converts result types per record),
   * so it should always report as cacheable.
   */
  @Test
  public void stepIsAlwaysCacheable() {
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // --- copy ---

  /**
   * copy() should produce a new ConvertToResultInternalStep that is structurally
   * equivalent but not the same instance. The copy should preserve the profiling
   * setting and function independently.
   */
  @Test
  public void copyProducesIndependentStepWithSameProfilingSetting() {
    var ctx = newContext();
    var step = new ConvertToResultInternalStep(ctx, true);

    var copied = step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied).isInstanceOf(ConvertToResultInternalStep.class);
    // The copy should preserve the profiling enabled flag
    assertThat(((AbstractExecutionStep) copied).isProfilingEnabled()).isTrue();
  }

  /**
   * A copied step should be fully functional -- able to convert results
   * independently of the original.
   */
  @Test
  public void copiedStepConvertsResultsIndependently() {
    session.begin();
    var ctx = newContext();
    var original = new ConvertToResultInternalStep(ctx, false);

    var copied = (ConvertToResultInternalStep) original.copy(ctx);

    var entity = session.newEntity();
    entity.setProperty("val", 42);
    copied.setPrevious(sourceStep(ctx, List.of(new UpdatableResult(session, entity))));

    var results = drain(copied.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).asEntity().<Integer>getProperty("val")).isEqualTo(42);
    session.commit();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Creates a source step that emits the given results when started.
   */
  private AbstractExecutionStep sourceStep(CommandContext ctx, List<? extends Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        return sourceStep(c, rows);
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(new ArrayList<>(rows).iterator());
      }
    };
  }

  /**
   * Drains all results from a stream into a list.
   */
  private List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }
}
