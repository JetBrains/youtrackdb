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
import org.junit.Test;

/**
 * Direct-step tests for {@link CopyRecordContentBeforeUpdateStep}, used by
 * {@code UPDATE … RETURN BEFORE} to snapshot the pre-update state of each row before subsequent
 * UPDATE steps mutate it.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Persisted entity: snapshot carries {@code @rid}, {@code @version}, {@code @class}, plus
 *       every current property — stored on the {@link UpdatableResult#previousValue} field.
 *   <li>New (unsaved) entity: {@code @rid}, {@code @version}, {@code @class} are set, but the
 *       property-copy loop is skipped because {@code getIdentity().isNew()} is true.
 *   <li>Non-{@link UpdatableResult} upstream row: the step throws
 *       {@link CommandExecutionException} with "Cannot fetch previous value".
 *   <li>prettyPrint: label with and without profiling, respecting indentation.
 *   <li>{@code copy()}: independent step with profiling flag preserved.
 * </ul>
 */
public class CopyRecordContentBeforeUpdateStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: snapshot semantics
  // =========================================================================

  /**
   * A persisted entity snapshot contains {@code @rid}, {@code @version}, {@code @class}, and
   * every current property — attached to {@link UpdatableResult#previousValue}.
   */
  @Test
  public void persistedEntitySnapshotIncludesAllProperties() {
    var className = createClassInstance().getName();

    session.begin();
    var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
        .newEntity(className);
    entity.setProperty("name", "Alice");
    entity.setProperty("age", 30);
    var rid = entity.getIdentity();
    session.commit();

    session.begin();
    try {
      var ctx = newContext();
      var step = new CopyRecordContentBeforeUpdateStep(ctx, false);

      var loaded = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .getActiveTransaction().load(rid);
      var upstreamResult = new UpdatableResult(session, loaded);
      step.setPrevious(sourceStep(ctx, List.of(upstreamResult)));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      var updatable = (UpdatableResult) results.get(0);
      var prev = updatable.previousValue;
      assertThat(prev).isNotNull();
      // Pin the exact property set so a regression that leaked an internal field
      // (e.g. @fieldTypes) into the RETURN BEFORE snapshot is caught.
      assertThat(prev.getPropertyNames())
          .containsExactlyInAnyOrder("@rid", "@version", "@class", "name", "age");
      assertThat((Object) prev.getProperty("@rid")).isEqualTo(rid);
      assertThat((Object) prev.getProperty("@version")).isEqualTo(loaded.getVersion());
      assertThat((Object) prev.getProperty("@class")).isEqualTo(className);
      assertThat((Object) prev.getProperty("name")).isEqualTo("Alice");
      assertThat((Object) prev.getProperty("age")).isEqualTo(30);
    } finally {
      session.rollback();
    }
  }

  /**
   * A new (unsaved) entity's identity reports {@code isNew() == true}, so the property-copy
   * loop is skipped. The snapshot still records the meta-fields ({@code @rid} → new-RID,
   * {@code @version} → 0, {@code @class} → className) so downstream projection behaves
   * consistently.
   */
  @Test
  public void newEntitySnapshotSkipsPropertyLoop() {
    var className = createClassInstance().getName();

    session.begin();
    try {
      var ctx = newContext();
      var step = new CopyRecordContentBeforeUpdateStep(ctx, false);

      var fresh = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .newEntity(className);
      fresh.setProperty("willNotLeakIntoPrevious", "x");
      var upstreamResult = new UpdatableResult(session, fresh);
      step.setPrevious(sourceStep(ctx, List.of(upstreamResult)));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      var updatable = (UpdatableResult) results.get(0);
      var prev = updatable.previousValue;
      assertThat(prev).isNotNull();
      // Meta-fields are recorded.
      assertThat((Object) prev.getProperty("@class")).isEqualTo(className);
      // Property loop skipped — only the three meta-fields are present.
      assertThat(prev.getPropertyNames()).containsExactlyInAnyOrder("@rid", "@version", "@class");
    } finally {
      session.rollback();
    }
  }

  /**
   * When an upstream row is not an {@link UpdatableResult}, the snapshot step cannot recover the
   * pre-update value and reports a {@link CommandExecutionException}.
   */
  @Test
  public void nonUpdatableUpstreamIsRejected() {
    var className = createClassInstance().getName();

    session.begin();
    var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
        .newEntity(className);
    entity.setProperty("name", "Alice");
    var rid = entity.getIdentity();
    session.commit();

    session.begin();
    try {
      var ctx = newContext();
      var step = new CopyRecordContentBeforeUpdateStep(ctx, false);

      var loaded = session.getActiveTransaction().load(rid);
      var nonUpdatable = new ResultInternal(session, loaded);
      step.setPrevious(sourceStep(ctx, List.of(nonUpdatable)));

      assertThatThrownBy(() -> drain(step.start(ctx), ctx))
          .isInstanceOf(CommandExecutionException.class)
          .hasMessageContaining("Cannot fetch previous value");
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint header is "+ COPY RECORD CONTENT BEFORE UPDATE"; no cost suffix unless profiling.
   */
  @Test
  public void prettyPrintWithoutProfiling() {
    var ctx = newContext();
    var step = new CopyRecordContentBeforeUpdateStep(ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ COPY RECORD CONTENT BEFORE UPDATE");
    assertThat(output).doesNotContain("μs");
  }

  /**
   * With profiling enabled, the header line appends the cost in microseconds.
   */
  @Test
  public void prettyPrintWithProfilingIncludesCost() {
    var ctx = newContext();
    var step = new CopyRecordContentBeforeUpdateStep(ctx, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ COPY RECORD CONTENT BEFORE UPDATE");
    assertThat(output).contains("μs");
  }

  /**
   * depth × indent controls the leading whitespace prefix.
   */
  @Test
  public void prettyPrintRespectsIndent() {
    var ctx = newContext();
    var step = new CopyRecordContentBeforeUpdateStep(ctx, false);

    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy()} produces a new step with the same profiling flag.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var step = new CopyRecordContentBeforeUpdateStep(ctx, true);

    var copy = step.copy(ctx);

    assertThat(copy).isNotSameAs(step).isInstanceOf(CopyRecordContentBeforeUpdateStep.class);
    assertThat(((CopyRecordContentBeforeUpdateStep) copy).isProfilingEnabled()).isTrue();
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
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
