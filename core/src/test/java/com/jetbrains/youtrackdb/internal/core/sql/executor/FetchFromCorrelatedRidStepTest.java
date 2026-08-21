package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link FetchFromCorrelatedRidStep}: cacheability, pretty-print, serialize
 * payload, copy, and the string-RID coerce path that is awkward to keep as a plain STRING through
 * {@code $parent} field evaluation. End-to-end planner behaviour lives in
 * {@link SelectExecutionPlannerRidEqualityTest}.
 */
public class FetchFromCorrelatedRidStepTest extends TestUtilsFixture {

  /**
   * Correlated RID fetch must not be cached — the RID expression closes over {@code $parent} and
   * would otherwise reuse a plan keyed without the parent row.
   */
  @Test
  public void canBeCachedReturnsFalse() {
    var step = newStep(parseExpression("SELECT $parent.$current.ref AS x"), ids(1, 2));
    assertThat(step.canBeCached()).isFalse();
  }

  /**
   * {@code prettyPrint} must name the step and echo the correlated expression so EXPLAIN and plan
   * dumps stay diagnosable.
   */
  @Test
  public void prettyPrintRendersHeaderAndExpression() {
    var expr = parseExpression("SELECT $parent.$current.companyRef AS x");
    var step = newStep(expr, ids(10));
    var out = step.prettyPrint(0, 2);
    assertThat(out).contains("FETCH FROM CORRELATED RID");
    assertThat(out).contains("$parent");
    assertThat(out).contains("companyRef");
  }

  /**
   * Serialize must persist both the RID expression AST and the polymorphic collection-id set —
   * dropping either would break remote/plan restore or class-membership checks after deserialize.
   */
  @Test
  public void serializeStoresExpressionAndCollectionIds() {
    var expr = parseExpression("SELECT $parent.$current.ref AS x");
    var collectionIds = ids(3, 7, 11);
    var step = newStep(expr, collectionIds);

    var serialized = step.serialize(session);
    Object ridExpression = serialized.getProperty("ridExpression");
    assertThat(ridExpression).as("ridExpression must be present").isNotNull();
    assertThat(ridExpression.toString())
        .contains("$parent")
        .contains("ref");
    List<Integer> ids = serialized.getProperty("classCollectionIds");
    assertThat(ids).containsExactlyInAnyOrder(3, 7, 11);
  }

  /**
   * {@code copy} must keep {@code canBeCached() == false} and render the same correlated
   * expression — a copy that dropped the expression would silently fetch nothing.
   */
  @Test
  public void copyPreservesExpressionAndCacheFlag() {
    var expr = parseExpression("SELECT $parent.$current.ref AS x");
    var original = newStep(expr, ids(5));
    var copied = (FetchFromCorrelatedRidStep) original.copy(newContext());

    assertThat(copied.canBeCached()).isFalse();
    assertThat(copied.prettyPrint(0, 2))
        .contains("FETCH FROM CORRELATED RID")
        .contains("$parent")
        .contains("ref");
  }

  /**
   * A malformed deserialize payload must surface as {@link CommandExecutionException}, not an
   * unchecked failure from the AST restore path.
   */
  @Test
  public void deserializeFailureWrapsInCommandExecutionException() {
    var step = newStep(parseExpression("SELECT $parent.$current.ref AS x"), ids(1));
    var bad = new ResultInternal(session);
    var badSub = new ResultInternal(session);
    badSub.setProperty("javaType", "com.nonexistent.Step");
    bad.setProperty("subSteps", List.of(badSub));

    assertThatThrownBy(() -> step.deserialize(bad, session))
        .isInstanceOf(CommandExecutionException.class);
  }

  /**
   * A string RID literal expression must load the record via {@code toRecordIdCandidate}'s String
   * arm (the path {@code $parent} STRING fields are awkward to keep as plain strings end-to-end).
   */
  @Test
  public void stringLiteralRidFetchesRecord() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "from-string");
    var rid = (RecordIdInternal) doc.getIdentity();
    session.commit();

    var expr = parseExpression("SELECT '" + rid + "' AS x");
    var step = newStep(expr, ids(rid.getCollectionId()));
    var ctx = newContext();

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      Object tag = results.get(0).getProperty("tag");
      assertThat(tag).isEqualTo("from-string");
    } finally {
      session.rollback();
    }
  }

  /**
   * A RID string whose collection id is outside the accepted set must yield empty — membership
   * filter at the step, independent of the planner's class resolution.
   */
  @Test
  public void stringLiteralRidWrongCollectionYieldsEmpty() {
    var className = createClassInstance().getName();
    session.begin();
    var rid = (RecordIdInternal) session.newInstance(className).getIdentity();
    session.commit();

    var expr = parseExpression("SELECT '" + rid + "' AS x");
    var step = newStep(expr, ids(rid.getCollectionId() + 10_000));
    var ctx = newContext();

    session.begin();
    try {
      assertThat(drain(step.start(ctx), ctx)).isEmpty();
    } finally {
      session.rollback();
    }
  }

  private FetchFromCorrelatedRidStep newStep(SQLExpression expression, IntSet collectionIds) {
    return new FetchFromCorrelatedRidStep(expression, collectionIds, newContext(), false);
  }

  private static IntSet ids(int... values) {
    var set = new IntOpenHashSet(values.length);
    for (var v : values) {
      set.add(v);
    }
    return set;
  }

  /**
   * Parses {@code SELECT <expr> AS alias} and returns the projection expression — same construction
   * path the LET planner uses for correlated RHS expressions.
   */
  private static SQLExpression parseExpression(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getProjection().getItems().get(0).getExpression();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse expression from: " + selectSql, e);
    }
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
