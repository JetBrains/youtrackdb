package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Tests the {@link CountFromClassStep} which provides an O(1) optimization for
 * {@code SELECT count(*) FROM ClassName} queries by reading the record count
 * directly from class metadata instead of scanning all records.
 *
 * <p>Covers:
 * <ul>
 *   <li>Correct count value returned from class metadata</li>
 *   <li>Count on an empty class returns zero</li>
 *   <li>Only a single result is produced (the count value)</li>
 *   <li>Predecessor step is drained for side effects before producing results</li>
 *   <li>Pretty-print rendering with and without profiling</li>
 *   <li>Step is never cacheable (counts can change between executions)</li>
 *   <li>Copy produces an independent, functional step</li>
 * </ul>
 */
public class CountFromClassStepTest extends TestUtilsFixture {

  private static final String ALIAS = "size";

  // --- Core counting behavior ---

  /**
   * The step should read the record count directly from the class metadata,
   * returning the exact number of records that were inserted.
   */
  @Test
  public void shouldCountRecordsOfClass() {
    var schemaClass = createClassInstance();
    for (var i = 0; i < 20; i++) {
      session.begin();
      session.newEntity(schemaClass);
      session.commit();
    }

    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, ALIAS, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Long>getProperty(ALIAS)).isEqualTo(20L);
  }

  /**
   * When the class has no records, the step should return a count of zero
   * rather than an empty stream — a count query always produces exactly one row.
   */
  @Test
  public void emptyClassReturnsZeroCount() {
    var schemaClass = createClassInstance();

    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, ALIAS, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Long>getProperty(ALIAS)).isEqualTo(0L);
  }

  /**
   * The result property should be set under the alias name provided at
   * construction time, allowing different aliases like "cnt" or "total".
   */
  @Test
  public void resultUsesProvidedAlias() {
    var schemaClass = createClassInstance();
    session.begin();
    session.newEntity(schemaClass);
    session.commit();

    var customAlias = "totalRecords";
    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, customAlias, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results.get(0).<Long>getProperty(customAlias)).isEqualTo(1L);
  }

  // --- Predecessor draining ---

  /**
   * When a predecessor step is chained before this source step (e.g. a
   * GlobalLetExpressionStep), the predecessor's stream must be started and
   * closed for its side effects before the count is produced.
   */
  @Test
  public void predecessorIsStartedAndClosedBeforeCountIsProduced() {
    var schemaClass = createClassInstance();
    session.begin();
    session.newEntity(schemaClass);
    session.commit();

    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, ALIAS, ctx, false);

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

    assertThat(prevStarted).isTrue();
    assertThat(prevStreamClosed).isTrue();
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Long>getProperty(ALIAS)).isEqualTo(1L);
  }

  // --- prettyPrint ---

  /**
   * Without profiling, prettyPrint should render the step label with the
   * target class name but no timing information.
   */
  @Test
  public void prettyPrintWithoutProfilingRendersStepLabelAndClassName() {
    var schemaClass = createClassInstance();
    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, ALIAS, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CALCULATE CLASS SIZE");
    assertThat(output).contains(schemaClass.getName());
    assertThat(output).doesNotContain("μs");
  }

  /**
   * With profiling enabled, prettyPrint should include the cost suffix
   * in microseconds alongside the step label.
   */
  @Test
  public void prettyPrintWithProfilingIncludesCost() {
    var schemaClass = createClassInstance();
    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, ALIAS, ctx, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CALCULATE CLASS SIZE");
    assertThat(output).contains("μs");
  }

  /**
   * prettyPrint with non-zero depth applies the expected indentation
   * (depth * indent leading spaces).
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var schemaClass = createClassInstance();
    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, ALIAS, ctx, false);

    // depth=1, indent=4 → 4 leading spaces
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
    assertThat(output).contains("CALCULATE CLASS SIZE");
  }

  // --- canBeCached ---

  /**
   * CountFromClassStep is never cacheable because the record count can change
   * between executions and security policies may require per-record filtering
   * in a different session context.
   */
  @Test
  public void stepIsNeverCacheable() {
    var schemaClass = createClassInstance();
    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, ALIAS, ctx, false);

    assertThat(step.canBeCached()).isFalse();
  }

  // --- copy ---

  /**
   * copy() should produce a new CountFromClassStep that is structurally
   * equivalent but not the same instance. The copy should preserve the target
   * class, alias, and profiling setting.
   */
  @Test
  public void copyProducesIndependentStepWithSameSettings() {
    var schemaClass = createClassInstance();
    var ctx = newContext();
    var step = new CountFromClassStep(schemaClass, ALIAS, ctx, true);

    var copied = step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied).isInstanceOf(CountFromClassStep.class);
    var copiedStep = (CountFromClassStep) copied;
    assertThat(copiedStep.isProfilingEnabled()).isTrue();
    // The copy should also not be cacheable (same semantics)
    assertThat(copiedStep.canBeCached()).isFalse();
    // The copy should preserve the alias and target class
    var results = drain(copiedStep.start(ctx), ctx);
    assertThat(results.get(0).getPropertyNames()).contains(ALIAS);
  }

  /**
   * A copied step should be fully functional — producing the correct count
   * independently of the original.
   */
  @Test
  public void copiedStepCountsRecordsIndependently() {
    var schemaClass = createClassInstance();
    session.begin();
    session.newEntity(schemaClass);
    session.commit();

    var ctx = newContext();
    var original = new CountFromClassStep(schemaClass, ALIAS, ctx, false);
    var copied = (CountFromClassStep) original.copy(ctx);

    var results = drain(copied.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Long>getProperty(ALIAS)).isEqualTo(1L);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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
