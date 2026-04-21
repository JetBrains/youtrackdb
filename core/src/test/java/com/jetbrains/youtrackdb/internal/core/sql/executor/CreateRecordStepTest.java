package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link CreateRecordStep}, the execution step that emits {@code total}
 * freshly-created records of a named schema class (or schemaless entities when the target class is
 * {@code null}).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Entity branch: schemaless class (targetClass null) creates plain entities.
 *   <li>Entity branch: regular schema class creates entities of that class.
 *   <li>Vertex branch: vertex class routes through {@code newVertex}.
 *   <li>Edge branch: edge class rejects creation with a clear error.
 *   <li>Unknown class: reports a {@link DatabaseException} with the offending class name.
 *   <li>Total=0: the stream terminates immediately without producing anything.
 *   <li>Predecessor draining: any upstream step is started and closed before the produce loop.
 *   <li>prettyPrint: header, singular/plural record wording, profiling suffix, indentation.
 *   <li>copy: produces an independent step that can execute with the same parameters.
 * </ul>
 *
 * <p>Uses direct-step instantiation (no SQL round-trip) and extends {@link TestUtilsFixture} so the
 * {@code @After rollbackIfLeftOpen} safety net cleans up any transaction left open by a failing
 * test method, and {@link DbTestBase} tears down the in-memory database per test.
 */
public class CreateRecordStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: class-type branches
  // =========================================================================

  /**
   * When targetClass is {@code null}, the step calls {@code session.newEntity()} (no class),
   * producing schemaless {@link UpdatableResult} wrappers.
   */
  @Test
  public void nullTargetClassProducesSchemalessEntities() {
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, null, 3, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(3);
      for (var result : results) {
        assertThat(result).isInstanceOf(UpdatableResult.class);
        var entity = result.asEntityOrNull();
        assertThat(entity).isNotNull();
        // Schemaless: routed through newEntity() (no arg), so the entity is neither vertex nor
        // edge — this pins the null-targetClass branch in CreateRecordStep.produce().
        assertThat(result.isVertex()).isFalse();
        assertThat(result.isEdge()).isFalse();
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * A regular schema class (not vertex, not edge) routes the produce loop into the
   * {@code newEntity(targetClass)} branch. Each produced result carries an entity of that class.
   */
  @Test
  public void regularClassProducesEntitiesOfThatClass() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, new SQLIdentifier(className), 2, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(2);
      for (var result : results) {
        var entity = result.asEntityOrNull();
        assertThat(entity.getSchemaClassName()).isEqualTo(className);
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * A vertex class routes through {@code session.newVertex(targetClass)}. The resulting entity
   * must be recognized as a vertex.
   */
  @Test
  public void vertexClassProducesVertices() {
    var vertexClassName = "CreateRecordStepV_" + System.nanoTime();
    session.createVertexClass(vertexClassName);
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, new SQLIdentifier(vertexClassName), 1, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.get(0).isVertex()).isTrue();
    } finally {
      session.rollback();
    }
  }

  /**
   * An edge class is rejected: CREATE EDGE must be used instead. The step throws a
   * {@link DatabaseException} containing the class name and a pointer to the correct command.
   */
  @Test
  public void edgeClassIsRejected() {
    var edgeClassName = "CreateRecordStepE_" + System.nanoTime();
    session.createEdgeClass(edgeClassName);
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, new SQLIdentifier(edgeClassName), 1, false);

    session.begin();
    try {
      assertThatThrownBy(() -> drain(step.start(ctx), ctx))
          .isInstanceOf(DatabaseException.class)
          .hasMessageContaining(edgeClassName)
          .hasMessageContaining("create edge");
    } finally {
      session.rollback();
    }
  }

  /**
   * An unknown class reports a {@link DatabaseException} naming the missing class — this is the
   * "getClass(...) returned null" branch inside {@code produce}.
   */
  @Test
  public void unknownClassIsReported() {
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, new SQLIdentifier("NoSuchClassXYZ"), 1, false);

    session.begin();
    try {
      assertThatThrownBy(() -> drain(step.start(ctx), ctx))
          .isInstanceOf(DatabaseException.class)
          .hasMessageContaining("NoSuchClassXYZ")
          .hasMessageContaining("not found");
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // internalStart: stream control
  // =========================================================================

  /**
   * With total=0 the produce stream is capped by {@code .limit(0)} so nothing is emitted.
   * The stream should be immediately exhausted.
   */
  @Test
  public void zeroTotalProducesEmptyStream() {
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, null, 0, false);

    session.begin();
    try {
      var stream = step.start(ctx);
      assertThat(stream.hasNext(ctx)).isFalse();
      stream.close(ctx);
    } finally {
      session.rollback();
    }
  }

  /**
   * When a predecessor is attached, {@code internalStart} starts AND closes the upstream stream
   * before producing new records (so any side-effect producer like {@code GlobalLetExpressionStep}
   * runs first).
   */
  @Test
  public void predecessorIsStartedAndClosedBeforeProducing() {
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, null, 1, false);

    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
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
    });

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(prevStarted).isTrue();
      assertThat(prevClosed).isTrue();
      assertThat(results).hasSize(1);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint with total==1 uses the singular "1 record" wording; without profiling the cost
   * suffix is absent.
   */
  @Test
  public void prettyPrintSingularWithoutProfiling() {
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, null, 1, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ CREATE EMPTY RECORDS");
    assertThat(output).contains("1 record");
    assertThat(output).doesNotContain("μs");
  }

  /**
   * prettyPrint with total != 1 uses the plural wording. (The source deliberately writes
   * "N record" without the 's' — assertion pins that observable format so a future edit that
   * introduces "N records" would be caught.)
   */
  @Test
  public void prettyPrintPluralWording() {
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, null, 5, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ CREATE EMPTY RECORDS");
    assertThat(output).contains("5 record");
    assertThat(output).doesNotContain("1 record");
  }

  /**
   * With profiling enabled, the header row includes the "(cost μs)" suffix.
   */
  @Test
  public void prettyPrintWithProfilingIncludesCost() {
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, null, 1, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ CREATE EMPTY RECORDS");
    assertThat(output).contains("μs");
  }

  /**
   * depth × indent controls the leading whitespace applied to every line of the rendered step.
   */
  @Test
  public void prettyPrintRespectsIndentation() {
    var ctx = newContext();
    var step = new CreateRecordStep(ctx, null, 1, false);

    var output = step.prettyPrint(1, 4);

    // depth=1, indent=4 → 4 leading spaces on the first line
    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy()} creates an independent step that carries the same parameters and remains
   * functional — producing the same number of records of the same class.
   */
  @Test
  public void copyProducesIndependentFunctionalStep() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var original = new CreateRecordStep(ctx, new SQLIdentifier(className), 2, true);

    var copy = original.copy(ctx);

    assertThat(copy).isNotSameAs(original).isInstanceOf(CreateRecordStep.class);
    var copied = (CreateRecordStep) copy;
    assertThat(copied.isProfilingEnabled()).isTrue();

    session.begin();
    try {
      var results = drain(copied.start(ctx), ctx);
      assertThat(results).hasSize(2);
      for (var result : results) {
        assertThat(result.asEntityOrNull().getSchemaClassName()).isEqualTo(className);
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * Copying a step with a {@code null} targetClass preserves the schemaless behavior.
   */
  @Test
  public void copyPreservesNullTargetClass() {
    var ctx = newContext();
    var original = new CreateRecordStep(ctx, null, 1, false);

    var copy = original.copy(ctx);

    session.begin();
    try {
      var results = drain(((CreateRecordStep) copy).start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.get(0).isVertex()).isFalse();
      assertThat(results.get(0).isEdge()).isFalse();
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    stream.close(ctx);
    return out;
  }
}
