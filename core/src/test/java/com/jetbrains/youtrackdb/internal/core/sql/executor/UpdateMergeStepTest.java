package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLJson;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link UpdateMergeStep}, which merges a JSON object into each upstream
 * entity result via {@code EntityImpl.updateFromJSON}. The merge preserves existing properties
 * and overlays the JSON keys.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Entity branch: JSON keys are merged into persisted entities; transaction re-loads the
 *       record under {@code resInt.setIdentifiable(transaction.load(identifiable))}.
 *   <li>Non-entity branch: a transient {@link ResultInternal} (no record) is returned
 *       unchanged because {@code result.isEntity()} is false.
 *   <li>prettyPrint: header, JSON body, indentation.
 *   <li>{@code copy()}: new instance, JSON content deep-copied, profiling preserved.
 * </ul>
 */
public class UpdateMergeStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: MERGE semantics
  // =========================================================================

  /**
   * {@code MERGE { "a": 1 }} applied to a saved entity overlays the JSON keys while retaining
   * the pre-existing keys.
   */
  @Test
  public void mergeOverlaysJsonKeysOnPersistedEntity() {
    var className = createClassInstance().getName();

    session.begin();
    var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
        .newEntity(className);
    entity.setProperty("keep", "retained");
    entity.setProperty("overwrite", "old");
    var rid = entity.getIdentity();
    session.commit();

    session.begin();
    try {
      var ctx = newContext();
      var json = parseMergeJson("UPDATE " + className + " MERGE {\"overwrite\":\"new\","
          + "\"added\":7}");
      var step = new UpdateMergeStep(json, ctx, false);

      var loaded = session.getActiveTransaction().load(rid);
      step.setPrevious(sourceStep(ctx, List.of(
          new UpdatableResult(session,
              (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) loaded))));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      var merged = results.get(0);
      assertThat((Object) merged.getProperty("keep")).isEqualTo("retained");
      assertThat((Object) merged.getProperty("overwrite")).isEqualTo("new");
      assertThat((Object) merged.getProperty("added")).isEqualTo(7);
    } finally {
      session.rollback();
    }
  }

  /**
   * If the upstream {@link ResultInternal} has no attached entity (transient, property-only),
   * the step returns it unchanged — the {@code if (!result.isEntity()) { return result; }}
   * branch.
   */
  @Test
  public void transientResultPassesThroughUnchanged() {
    session.begin();
    try {
      var ctx = newContext();
      var json = parseMergeJson("UPDATE X MERGE {\"added\":7}");
      var step = new UpdateMergeStep(json, ctx, false);

      var transientResult = new ResultInternal(session);
      transientResult.setProperty("existing", "value");
      step.setPrevious(sourceStep(ctx, List.of(transientResult)));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.get(0)).isSameAs(transientResult);
      // No merge happened — only pre-existing property remains.
      assertThat(results.get(0).getPropertyNames()).containsOnly("existing");
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint header is "+ UPDATE MERGE" with the JSON content on a subsequent line.
   */
  @Test
  public void prettyPrintShowsHeaderAndJson() {
    var ctx = newContext();
    var json = parseMergeJson("UPDATE X MERGE {\"a\":1}");
    var step = new UpdateMergeStep(json, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ UPDATE MERGE");
    assertThat(output).contains("\"a\"");
    assertThat(output).contains("1");
  }

  /**
   * depth × indent controls the leading whitespace prefix.
   */
  @Test
  public void prettyPrintRespectsIndent() {
    var ctx = newContext();
    var json = parseMergeJson("UPDATE X MERGE {\"a\":1}");
    var step = new UpdateMergeStep(json, ctx, false);

    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy()} creates an independent step; the internal {@link SQLJson} is also cloned via
   * {@link SQLJson#copy()}, and the profiling flag carries over.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var json = parseMergeJson("UPDATE X MERGE {\"a\":1}");
    var step = new UpdateMergeStep(json, ctx, true);

    var copy = (UpdateMergeStep) step.copy(ctx);

    assertThat(copy).isNotSameAs(step);
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).contains("+ UPDATE MERGE").contains("\"a\"");
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
   * Parses an {@code UPDATE … MERGE {...}} statement and extracts the resulting {@link SQLJson}.
   */
  private static SQLJson parseMergeJson(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLUpdateStatement) parser.parse();
      return stm.getOperations().get(0).getJson();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse merge: " + sql, e);
    }
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
