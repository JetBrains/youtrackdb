package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link FetchFromCorrelatedRidStep}: cacheability, pretty-print, serialize
 * payload, copy, predecessor drain, coerce edges (non-Collection multi-value / size-1 Collection),
 * and serialize/deserialize restore. End-to-end planner behaviour lives in
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

  /**
   * With a non-null predecessor, {@code internalStart} drains (start + close) the upstream stream
   * before evaluating the RID — the same side-effect contract as other leaf fetch steps.
   */
  @Test
  public void internalStartDrainsPredecessorBeforeFetch() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "with-prev");
    var rid = (RecordIdInternal) doc.getIdentity();
    session.commit();

    var ctx = newContext();
    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);
    var step = newStep(parseExpression("SELECT '" + rid + "' AS x"), ids(rid.getCollectionId()));
    step.setPrevious(trackingPredecessor(ctx, prevStarted, prevClosed));

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      Object tag = results.get(0).getProperty("tag");
      assertThat(tag).isEqualTo("with-prev");
    } finally {
      session.rollback();
    }

    assertThat(prevStarted.get()).as("predecessor must be started").isTrue();
    assertThat(prevClosed.get()).as("predecessor stream must be closed").isTrue();
  }

  /**
   * A non-Collection multi-value ({@code Object[]}) must coerce to no RID — matches
   * {@code QueryOperatorEquals}, which only unwraps {@link java.util.Collection}.
   */
  @Test
  public void objectArrayRhsYieldsEmpty() {
    var className = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(className);
    a.setProperty("tag", "a");
    var b = session.newInstance(className);
    b.setProperty("tag", "b");
    var ridA = (RecordIdInternal) a.getIdentity();
    var ridB = (RecordIdInternal) b.getIdentity();
    session.commit();

    var ctx = newContext();
    ctx.setVariable("arr", new Object[] {ridA, ridB});
    var step = newStep(parseExpression("SELECT $arr AS x"), ids(ridA.getCollectionId()));

    session.begin();
    try {
      assertThat(drain(step.start(ctx), ctx)).isEmpty();
    } finally {
      session.rollback();
    }
  }

  /**
   * A size-1 {@link List} unwraps to that single RID and loads the record — the Collection arm of
   * {@code coerceEqualityRid}, distinct from the array rejection above.
   */
  @Test
  public void sizeOneListRhsFetchesRecord() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "from-list");
    var rid = (RecordIdInternal) doc.getIdentity();
    session.commit();

    var ctx = newContext();
    ctx.setVariable("one", List.of(rid));
    var step = newStep(parseExpression("SELECT $one AS x"), ids(rid.getCollectionId()));

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      Object tag = results.get(0).getProperty("tag");
      assertThat(tag).isEqualTo("from-list");
    } finally {
      session.rollback();
    }
  }

  /**
   * Serialize then deserialize must restore both the RID expression and collection-id set so a
   * restored step still fetches the same record. Uses a literal-value expression (no
   * {@code SQLBaseExpression}) because math-expression serde requires an {@code Integer} ctor that
   * {@code SQLBaseExpression} does not expose.
   */
  @Test
  public void serializeDeserializeRoundTripFetchesRecord() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "round-trip");
    var rid = (RecordIdInternal) doc.getIdentity();
    session.commit();

    var expr = new SQLExpression(-1);
    expr.setLiteralValue(rid);
    var original = newStep(expr, ids(rid.getCollectionId()));
    var serialized = original.serialize(session);

    var restored =
        new FetchFromCorrelatedRidStep(new SQLExpression(-1), ids(0), newContext(), false);
    restored.deserialize(serialized, session);

    List<Integer> restoredIds = restored.serialize(session).getProperty("classCollectionIds");
    assertThat(restoredIds).containsExactly(rid.getCollectionId());
    assertThat(restored.prettyPrint(0, 2)).contains("FETCH FROM CORRELATED RID");

    var ctx = newContext();
    session.begin();
    try {
      var results = drain(restored.start(ctx), ctx);
      assertThat(results).hasSize(1);
      Object tag = results.get(0).getProperty("tag");
      assertThat(tag).isEqualTo("round-trip");
    } finally {
      session.rollback();
    }
  }

  /**
   * When {@code ridExpression} is null, serialize omits it; {@code copy} keeps a null expression.
   * Null {@code classCollectionIds} is omitted from serialize only (the constructor still requires
   * a non-null set for live steps).
   */
  @Test
  public void serializeOmitsNullExpressionAndCollectionIds() throws Exception {
    var step = newStep(parseExpression("SELECT $parent.$current.ref AS x"), ids(1, 2));
    setPrivateField(step, "ridExpression", null);

    var withoutExpr = step.serialize(session);
    assertThat((Object) withoutExpr.getProperty("ridExpression")).isNull();
    List<Integer> serializedIds = withoutExpr.getProperty("classCollectionIds");
    assertThat(serializedIds).containsExactlyInAnyOrder(1, 2);

    var copied = (FetchFromCorrelatedRidStep) step.copy(newContext());
    assertThat(copied.canBeCached()).isFalse();
    assertThat((Object) copied.serialize(session).getProperty("ridExpression")).isNull();

    setPrivateField(step, "classCollectionIds", null);
    var withoutIds = step.serialize(session);
    assertThat((Object) withoutIds.getProperty("classCollectionIds")).isNull();
  }

  /**
   * Deserialize with neither optional property present must not fail — skips the AST and
   * collection-id restore branches (sparse payload after basicSerialize only).
   */
  @Test
  public void deserializeSparsePayloadSkipsOptionalFields() {
    var step =
        new FetchFromCorrelatedRidStep(
            parseExpression("SELECT $parent.$current.ref AS x"), ids(1), newContext(), false);
    var sparse = ExecutionStepInternal.basicSerialize(session, step);

    step.deserialize(sparse, session);

    // Expression and ids from the constructor remain; sparse payload simply did not overwrite.
    assertThat(step.prettyPrint(0, 2)).contains("$parent");
    List<Integer> ids = step.serialize(session).getProperty("classCollectionIds");
    assertThat(ids).containsExactly(1);
  }

  /** Duplicate identifiers in the input collapse to one row, matching scan iteration. */
  @Test
  public void equalityMatchSet_duplicateIds_collapsesToOne() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    var rid = (RecordIdInternal) doc.getIdentity();
    session.commit();

    var wrapper = new ResultInternal(session);
    wrapper.setProperty("ids", List.of(rid, rid));

    var matchSet = FetchFromCorrelatedRidStep.equalityMatchSet(wrapper, ids(rid.getCollectionId()));
    assertThat(matchSet).containsExactly(rid);
  }

  /** Position {@code -1} is dropped so the load path never throws. */
  @Test
  public void equalityMatchSet_positionMinusOne_yieldsEmpty() {
    var invalid = new RecordId(1, -1);
    var matchSet = FetchFromCorrelatedRidStep.equalityMatchSet(invalid, ids(1));
    assertThat(matchSet).isEmpty();
  }

  /** A {@code Result} with two properties never matches — same as {@code QueryOperatorEquals}. */
  @Test
  public void equalityMatchSet_twoPropertyResult_yieldsEmpty() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    var rid = (RecordIdInternal) doc.getIdentity();
    session.commit();

    var wrapper = new ResultInternal(session);
    wrapper.setProperty("a", rid);
    wrapper.setProperty("b", rid);

    var matchSet = FetchFromCorrelatedRidStep.equalityMatchSet(wrapper, ids(rid.getCollectionId()));
    assertThat(matchSet).isEmpty();
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

  private ExecutionStepInternal trackingPredecessor(
      CommandContext ctx, AtomicBoolean started, AtomicBoolean closed) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        started.set(true);
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext c2) {
            return false;
          }

          @Override
          public Result next(CommandContext c2) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close(CommandContext c2) {
            closed.set(true);
          }
        };
      }
    };
  }

  private static void setPrivateField(Object target, String name, Object value) throws Exception {
    Field field = FetchFromCorrelatedRidStep.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
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
