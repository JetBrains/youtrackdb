/*
 * Copyright 2018 YouTrackDB
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link UnwindStep}, the step that "unwinds" (flattens) collection-valued
 * fields, emitting one row per element.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} throws {@link CommandExecutionException} when no predecessor is
 *       attached.
 *   <li>Empty unwindFields list passes the record through unchanged (base case of recursion,
 *       line 82).
 *   <li>Null field value: the field path recurses on remaining fields without emitting extra
 *       rows (line 89 {@code fieldValue == null} branch).
 *   <li>{@link EntityImpl} field value is treated as a scalar (not unwound) — pins the
 *       {@code fieldValue instanceof EntityImpl} branch at line 89.
 *   <li>Non-iterable, non-array scalar passes through unchanged (line 94).
 *   <li>{@link Iterable} field unwinds to one row per element (lines 99-124).
 *   <li>Array field unwinds via {@code MultiValue.getMultiValueIterator}.
 *   <li>Empty iterable emits one row with the field set to null (line 106-111).
 *   <li>Multiple unwind fields produce a Cartesian product over collections.
 *   <li>{@code prettyPrint} renders {@code "+ UNWIND..."}.
 *   <li>{@code canBeCached} returns {@code true}.
 *   <li>{@code copy} produces an independent step with a deep-copied {@link SQLUnwind}.
 * </ul>
 */
public class UnwindStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart — prev-null guard
  // =========================================================================

  /**
   * Without a predecessor {@code internalStart} throws {@link CommandExecutionException}. Pins
   * the {@code prev == null} guard at line 58.
   */
  @Test
  public void internalStartWithoutPrevThrowsCommandExecutionException() {
    var ctx = newContext();
    var step = new UnwindStep(unwind("tags"), ctx, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("unwind");
  }

  // =========================================================================
  // Unwind semantics
  // =========================================================================

  /**
   * An empty {@link SQLUnwind} (no items) passes through every upstream record unchanged. Pins
   * the base-case {@code unwindFields.isEmpty()} branch at line 81.
   */
  @Test
  public void emptyUnwindListPassesThroughUnchanged() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var step = new UnwindStep(emptyUnwind(), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Integer>getProperty("id")).isEqualTo(1);
  }

  /**
   * A null field value skips to the next unwind field (recursion with {@code nextFields}). Pins
   * line 89 {@code fieldValue == null} true branch. With a single unwind field, the record
   * passes through unchanged.
   */
  @Test
  public void nullFieldValuePassesThroughUnchanged() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    r1.setProperty("tags", null);
    var step = new UnwindStep(unwind("tags"), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(1);
    assertThat((Object) results.get(0).getProperty("tags")).isNull();
  }

  /**
   * An {@link EntityImpl} field is treated as a scalar — the record is NOT unwound. Pins the
   * {@code instanceof EntityImpl} branch at line 89.
   */
  @Test
  public void entityImplFieldIsTreatedAsScalar() {
    var ctx = newContext();
    var className = createClassInstance().getName();
    session.begin();
    try {
      // Any EntityImpl instance exercises the `instanceof EntityImpl` scalar branch — whether
      // standalone or embedded is irrelevant. newEntity() is simpler than newEmbeddedEntity,
      // which requires an abstract class.
      var entity = (EntityImpl) session.newEntity(className);
      entity.setProperty("x", 7);
      var r1 = new ResultInternal(session);
      r1.setProperty("id", 1);
      r1.setProperty("ent", entity);
      var step = new UnwindStep(unwind("ent"), ctx, false);
      step.setPrevious(sourceStep(ctx, List.of(r1)));

      var results = drain(step.start(ctx), ctx);

      assertThat(results)
          .as("EntityImpl value is not unwound; one row pass-through")
          .hasSize(1);
    } finally {
      session.rollback();
    }
  }

  /**
   * A non-iterable, non-array scalar field (e.g. a String) is passed through unchanged. Pins the
   * {@code !(fieldValue instanceof Iterable) && !fieldValue.getClass().isArray()} branch at
   * line 94.
   */
  @Test
  public void nonIterableScalarFieldPassesThroughUnchanged() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    r1.setProperty("tag", "solo");
    var step = new UnwindStep(unwind("tag"), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).<String>getProperty("tag")).isEqualTo("solo");
  }

  /**
   * An {@link Iterable} field produces one row per element. Pins the happy path through
   * lines 102-118 (iterator loop + per-element copy + recursion).
   */
  @Test
  public void iterableFieldUnwindsToOneRowPerElement() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    r1.setProperty("tags", List.of("java", "db", "sql"));
    var step = new UnwindStep(unwind("tags"), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(3);
    assertThat(results.stream().map(r -> (String) r.getProperty("tags")).toList())
        .containsExactly("java", "db", "sql");
    // All output rows carry the same id (copied from the source).
    assertThat(results.stream().map(r -> (Integer) r.getProperty("id")).distinct())
        .containsExactly(1);
  }

  /**
   * An array field is unwound via {@code MultiValue.getMultiValueIterator}. Pins the
   * {@code fieldValue.getClass().isArray()} branch at lines 100-101.
   */
  @Test
  public void arrayFieldUnwindsToOneRowPerElement() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    r1.setProperty("scores", new Integer[] {10, 20, 30});
    var step = new UnwindStep(unwind("scores"), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(3);
    assertThat(results.stream().map(r -> (Integer) r.getProperty("scores")).toList())
        .containsExactly(10, 20, 30);
  }

  /**
   * An empty iterable emits exactly one output row with the field set to {@code null}. Pins the
   * empty-collection branch at lines 106-111.
   */
  @Test
  public void emptyIterableEmitsOneRowWithNullField() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    r1.setProperty("tags", new ArrayList<>());
    var step = new UnwindStep(unwind("tags"), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(1);
    assertThat((Object) results.get(0).getProperty("tags")).isNull();
    assertThat(results.get(0).<Integer>getProperty("id")).isEqualTo(1);
  }

  /**
   * Multiple unwind fields produce a Cartesian product across collections. Pins the recursion
   * at lines 111 + 118 combined with {@code unwindFields.subList(1, ...)}.
   */
  @Test
  public void multipleUnwindFieldsProduceCartesianProduct() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    r1.setProperty("a", List.of("x", "y"));
    r1.setProperty("b", List.of(1, 2, 3));
    var step = new UnwindStep(unwind("a", "b"), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var results = drain(step.start(ctx), ctx);

    assertThat(results)
        .as("2 elements × 3 elements = 6 rows")
        .hasSize(6);
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /** {@code prettyPrint} renders "+ UNWIND..." (the exact body is the {@link SQLUnwind}'s toString). */
  @Test
  public void prettyPrintRendersUnwindHeader() {
    var ctx = newContext();
    var step = new UnwindStep(unwind("tags"), ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ ").contains("UNWIND");
  }

  /** A non-zero depth applies leading indent. */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new UnwindStep(unwind("x"), ctx, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /** {@code canBeCached} always returns true. */
  @Test
  public void stepIsAlwaysCacheable() {
    var ctx = newContext();
    var step = new UnwindStep(unwind("x"), ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} deep-copies the {@link SQLUnwind} AST and carries the profilingEnabled flag.
   * Pins line 146.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var original = new UnwindStep(unwind("x", "y"), ctx, true);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(UnwindStep.class);
    var copy = (UnwindStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).isEqualTo(original.prettyPrint(0, 2));
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /** Builds a {@link SQLUnwind} with the given field names via the subclass-setter idiom. */
  private static SQLUnwind unwind(String... fieldNames) {
    return new SQLUnwind(-1) {
      {
        var items = new ArrayList<SQLIdentifier>();
        for (var name : fieldNames) {
          items.add(new SQLIdentifier(name));
        }
        this.items = items;
      }
    };
  }

  /** Builds an empty {@link SQLUnwind}. */
  private static SQLUnwind emptyUnwind() {
    return new SQLUnwind(-1) {
      {
        this.items = new ArrayList<>();
      }
    };
  }

  private ExecutionStepInternal sourceStep(CommandContext ctx, List<? extends Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(new ArrayList<Result>(rows).iterator());
      }
    };
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
