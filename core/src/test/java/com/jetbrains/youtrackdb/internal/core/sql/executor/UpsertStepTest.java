package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link UpsertStep}, which creates a new record from an UPDATE's initial
 * WHERE filter if the upstream has no matches.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Match path: upstream produces at least one row → upsert forwards the upstream stream
 *       (no new record).
 *   <li>No-match path (entity class): upstream empty → a new entity is created via
 *       {@code session.newEntity(cls)} and the WHERE equality conditions are applied as SET
 *       operations.
 *   <li>No-match path (vertex class): upstream empty → a new vertex is created via
 *       {@code session.newVertex(cls)}.
 *   <li>Edge class rejection: when the target is an edge type, UPSERT throws
 *       {@link CommandExecutionException} with an "edge type" hint.
 *   <li>Missing class rejection: when the target class is absent,
 *       {@link CommandExecutionException} names the offending target.
 *   <li>OR-combined WHERE clause rejection: {@code a=1 OR b=2} flattens to multiple AND-blocks,
 *       which UPSERT disallows.
 *   <li>prettyPrint: label, target, content (indentation-respecting).
 *   <li>{@code copy()}: independent step with copied AST nodes, profiling preserved.
 * </ul>
 */
public class UpsertStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: match / no-match paths
  // =========================================================================

  /**
   * If the upstream has at least one result, UPSERT is a no-op — the upstream stream is returned
   * as-is.
   */
  @Test
  public void upstreamMatchForwardsStream() {
    var className = createClassInstance().getName();

    session.begin();
    try {
      var ctx = newContext();
      var target = parseTarget("UPDATE " + className + " SET unused=0 UPSERT WHERE a=1");
      var where = parseWhere("UPDATE " + className + " SET unused=0 UPSERT WHERE a=1");
      var step = new UpsertStep(target, where, ctx, false);

      var existing = new UpdatableResult(session,
          (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
              .newEntity(className));
      step.setPrevious(sourceStep(ctx, List.of(existing)));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      // The original upstream row is forwarded unchanged — not a newly-constructed record.
      assertThat(results.get(0)).isSameAs(existing);
    } finally {
      session.rollback();
    }
  }

  /**
   * When the upstream is empty, UPSERT creates a fresh entity and applies the WHERE equalities
   * as SET operations. The initial filter {@code a=1 AND b='x'} becomes two SET assignments on
   * the new record.
   */
  @Test
  public void noMatchCreatesEntityFromWhereEqualities() {
    var className = createClassInstance().getName();

    session.begin();
    try {
      var ctx = newContext();
      var sql = "UPDATE " + className + " SET z=0 UPSERT WHERE a=1 AND b='x'";
      var target = parseTarget(sql);
      var where = parseWhere(sql);
      var step = new UpsertStep(target, where, ctx, false);

      step.setPrevious(sourceStep(ctx, List.of()));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.get(0)).isInstanceOf(UpdatableResult.class);
      assertThat((Object) results.get(0).getProperty("a")).isEqualTo(1);
      assertThat((Object) results.get(0).getProperty("b")).isEqualTo("x");
    } finally {
      session.rollback();
    }
  }

  /**
   * No-match path with a vertex class routes through {@code session.newVertex(cls)}.
   */
  @Test
  public void noMatchCreatesVertexOnVertexClass() {
    var vName = "UpsertV_" + System.nanoTime();
    session.createVertexClass(vName);

    session.begin();
    try {
      var ctx = newContext();
      var sql = "UPDATE " + vName + " SET z=0 UPSERT WHERE key=42";
      var target = parseTarget(sql);
      var where = parseWhere(sql);
      var step = new UpsertStep(target, where, ctx, false);

      step.setPrevious(sourceStep(ctx, List.of()));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.get(0).isVertex()).isTrue();
      assertThat((Object) results.get(0).getProperty("key")).isEqualTo(42);
    } finally {
      session.rollback();
    }
  }

  /**
   * UPSERT on an edge class is rejected with {@link CommandExecutionException} containing "edge
   * type".
   */
  @Test
  public void noMatchOnEdgeClassIsRejected() {
    var eName = "UpsertE_" + System.nanoTime();
    session.createEdgeClass(eName);

    session.begin();
    try {
      var ctx = newContext();
      var sql = "UPDATE " + eName + " SET z=0 UPSERT WHERE k=1";
      var target = parseTarget(sql);
      var where = parseWhere(sql);
      var step = new UpsertStep(target, where, ctx, false);

      step.setPrevious(sourceStep(ctx, List.of()));

      assertThatThrownBy(() -> drain(step.start(ctx), ctx))
          .isInstanceOf(CommandExecutionException.class)
          .hasMessageContaining("edge type");
    } finally {
      session.rollback();
    }
  }

  /**
   * UPSERT on a non-existent class is rejected because {@code commandTarget.getSchemaClass(...)}
   * returns null.
   */
  @Test
  public void noMatchOnMissingClassIsRejected() {
    session.begin();
    try {
      var ctx = newContext();
      var sql = "UPDATE NoSuchClassQQQ SET z=0 UPSERT WHERE k=1";
      var target = parseTarget(sql);
      var where = parseWhere(sql);
      var step = new UpsertStep(target, where, ctx, false);

      step.setPrevious(sourceStep(ctx, List.of()));

      assertThatThrownBy(() -> drain(step.start(ctx), ctx))
          .isInstanceOf(CommandExecutionException.class)
          .hasMessageContaining("Cannot execute UPSERT on target");
    } finally {
      session.rollback();
    }
  }

  /**
   * An OR-combined WHERE flattens to multiple AND-blocks, which UPSERT disallows because the
   * new record would need a unique set of equalities.
   */
  @Test
  public void noMatchWithOrClauseIsRejected() {
    var className = createClassInstance().getName();

    session.begin();
    try {
      var ctx = newContext();
      var sql = "UPDATE " + className + " SET z=0 UPSERT WHERE a=1 OR b=2";
      var target = parseTarget(sql);
      var where = parseWhere(sql);
      var step = new UpsertStep(target, where, ctx, false);

      step.setPrevious(sourceStep(ctx, List.of()));

      assertThatThrownBy(() -> drain(step.start(ctx), ctx))
          .isInstanceOf(CommandExecutionException.class)
          .hasMessageContaining("Cannot UPSERT on OR conditions");
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint header is "+ INSERT (upsert, if needed)" and includes target + content lines.
   */
  @Test
  public void prettyPrintShowsHeaderTargetAndContent() {
    var ctx = newContext();
    var sql = "UPDATE X SET z=0 UPSERT WHERE a=1";
    var target = parseTarget(sql);
    var where = parseWhere(sql);
    var step = new UpsertStep(target, where, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ INSERT (upsert, if needed)");
    assertThat(output).contains("target:");
    assertThat(output).contains("content:");
  }

  /**
   * depth × indent controls the leading whitespace prefix.
   */
  @Test
  public void prettyPrintRespectsIndent() {
    var ctx = newContext();
    var sql = "UPDATE X SET z=0 UPSERT WHERE a=1";
    var target = parseTarget(sql);
    var where = parseWhere(sql);
    var step = new UpsertStep(target, where, ctx, false);

    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy()} deep-copies both the target and where-clause and preserves profiling.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var sql = "UPDATE X SET z=0 UPSERT WHERE a=1";
    var target = parseTarget(sql);
    var where = parseWhere(sql);
    var step = new UpsertStep(target, where, ctx, true);

    var copy = (UpsertStep) step.copy(ctx);

    assertThat(copy).isNotSameAs(step);
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).contains("+ INSERT (upsert, if needed)");
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static SQLFromClause parseTarget(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLUpdateStatement) parser.parse();
      return stm.getTarget();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse target: " + sql, e);
    }
  }

  private static SQLWhereClause parseWhere(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLUpdateStatement) parser.parse();
      return stm.getWhereClause();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse where: " + sql, e);
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
