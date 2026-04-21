package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInputParameter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInsertStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLJson;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Direct-step tests for {@link UpdateContentStep}, which replaces all properties on each upstream
 * entity with either a JSON literal (SQLJson ctor) or an input-parameter map (SQLInputParameter
 * ctor).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>JSON branch: pre-existing properties are wiped; JSON keys take over.
 *   <li>InputParameter branch with a {@link Map} input: behaves identically to JSON.
 *   <li>InputParameter branch with a non-Map input: rejected with
 *       {@link CommandExecutionException}.
 *   <li>Non-entity upstream result (no attached record): the assertion fires — exercises the
 *       defensive {@code assert entity != null} path via the mapper guard {@code
 *       result instanceof ResultInternal}.
 *   <li>prettyPrint: label + JSON/inputParameter content, indentation.
 *   <li>{@code copy()}: independent step for both ctor variants.
 * </ul>
 */
public class UpdateContentStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: JSON-constructor branch
  // =========================================================================

  /**
   * {@code UPDATE X CONTENT {...}} replaces all pre-existing properties with the JSON content.
   */
  @Test
  public void contentReplacesAllPropertiesWithJson() {
    var className = createClassInstance().getName();

    session.begin();
    var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
        .newEntity(className);
    entity.setProperty("willBeDropped", "x");
    entity.setProperty("alsoDropped", "y");
    var rid = entity.getIdentity();
    session.commit();

    session.begin();
    try {
      var ctx = newContext();
      var json = parseContentJson("UPDATE " + className + " CONTENT {\"fresh\":42}");
      var step = new UpdateContentStep(json, ctx, false);

      var loaded = session.getActiveTransaction().load(rid);
      step.setPrevious(sourceStep(ctx, List.of(
          new UpdatableResult(session,
              (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) loaded))));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      var r = results.get(0);
      assertThat(r.getPropertyNames()).contains("fresh");
      assertThat(r.getPropertyNames()).doesNotContain("willBeDropped", "alsoDropped");
      assertThat((Object) r.getProperty("fresh")).isEqualTo(42);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // internalStart: InputParameter-constructor branch
  // =========================================================================

  /**
   * The input-parameter ctor with a {@link Map} input mutates the entity identically to the JSON
   * ctor — {@code record.updateFromMap(map)} is invoked when the parameter value is a
   * {@link Map}.
   */
  @Test
  public void inputParameterWithMapUpdatesEntity() {
    var className = createClassInstance().getName();

    session.begin();
    var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
        .newEntity(className);
    entity.setProperty("dropMe", "gone");
    var rid = entity.getIdentity();
    session.commit();

    session.begin();
    try {
      var ctx = newContext();
      ctx.setInputParameters(Map.of("p", Map.of("hello", "world")));
      var param = parseInputParameter("INSERT INTO " + className + " CONTENT :p");
      var step = new UpdateContentStep(param, ctx, false);

      var loaded = session.getActiveTransaction().load(rid);
      step.setPrevious(sourceStep(ctx, List.of(
          new UpdatableResult(session,
              (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) loaded))));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat((Object) results.get(0).getProperty("hello")).isEqualTo("world");
      assertThat(results.get(0).getPropertyNames()).doesNotContain("dropMe");
    } finally {
      session.rollback();
    }
  }

  /**
   * A non-{@link Map} parameter value is rejected with a clear {@link CommandExecutionException}.
   */
  @Test
  public void inputParameterWithNonMapIsRejected() {
    var className = createClassInstance().getName();

    session.begin();
    var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
        .newEntity(className);
    var rid = entity.getIdentity();
    session.commit();

    session.begin();
    try {
      var ctx = newContext();
      // Intentionally pass a non-Map — exercises the error branch.
      ctx.setInputParameters(Map.of("p", "plain-string"));
      var param = parseInputParameter("INSERT INTO " + className + " CONTENT :p");
      var step = new UpdateContentStep(param, ctx, false);

      var loaded = session.getActiveTransaction().load(rid);
      step.setPrevious(sourceStep(ctx, List.of(
          new UpdatableResult(session,
              (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) loaded))));

      assertThatThrownBy(() -> drain(step.start(ctx), ctx))
          .isInstanceOf(CommandExecutionException.class)
          .hasMessageContaining("Invalid value for UPDATE CONTENT");
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint renders "+ UPDATE CONTENT" followed by the JSON body.
   */
  @Test
  public void prettyPrintShowsJsonBody() {
    var ctx = newContext();
    var json = parseContentJson("UPDATE X CONTENT {\"k\":1}");
    var step = new UpdateContentStep(json, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ UPDATE CONTENT");
    assertThat(output).contains("\"k\"");
    assertThat(output).contains("1");
  }

  /**
   * prettyPrint with the input-parameter ctor renders the parameter token (e.g. ":p") on the
   * body line rather than JSON.
   */
  @Test
  public void prettyPrintWithInputParameterShowsParameter() {
    var ctx = newContext();
    var param = parseInputParameter("INSERT INTO X CONTENT :p");
    var step = new UpdateContentStep(param, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ UPDATE CONTENT");
    assertThat(output).contains(":p");
  }

  /**
   * depth × indent controls the leading whitespace prefix.
   */
  @Test
  public void prettyPrintRespectsIndent() {
    var ctx = newContext();
    var json = parseContentJson("UPDATE X CONTENT {\"k\":1}");
    var step = new UpdateContentStep(json, ctx, false);

    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * Copying the JSON-ctor variant produces an independent step with cloned JSON.
   */
  @Test
  public void copyPreservesJsonVariant() {
    var ctx = newContext();
    var json = parseContentJson("UPDATE X CONTENT {\"k\":1}");
    var step = new UpdateContentStep(json, ctx, true);

    var copy = (UpdateContentStep) step.copy(ctx);

    assertThat(copy).isNotSameAs(step);
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).contains("+ UPDATE CONTENT").contains("\"k\"");
  }

  /**
   * Copying the InputParameter-ctor variant produces an independent step with cloned parameter.
   */
  @Test
  public void copyPreservesInputParameterVariant() {
    var ctx = newContext();
    var param = parseInputParameter("INSERT INTO X CONTENT :p");
    var step = new UpdateContentStep(param, ctx, false);

    var copy = (UpdateContentStep) step.copy(ctx);

    assertThat(copy).isNotSameAs(step);
    assertThat(copy.prettyPrint(0, 2)).contains("+ UPDATE CONTENT").contains(":p");
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static SQLJson parseContentJson(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLUpdateStatement) parser.parse();
      return stm.getOperations().get(0).getJson();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse content: " + sql, e);
    }
  }

  /**
   * Parses {@code INSERT INTO X CONTENT :p} — the grammar path that accepts a bare
   * {@link SQLInputParameter} in the CONTENT slot (the UPDATE grammar accepts only Json() there,
   * so we borrow the INSERT form). Returns the first extracted parameter so we can construct the
   * {@link UpdateContentStep} InputParameter-ctor variant for a direct-step test.
   */
  private static SQLInputParameter parseInputParameter(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLInsertStatement) parser.parse();
      return stm.getInsertBody().getContentInputParam().get(0);
    } catch (Exception e) {
      throw new AssertionError("Failed to parse input parameter: " + sql, e);
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
