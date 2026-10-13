package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.Resettable;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext.InternalVariable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalContextVariableSuffix;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Pins the AST built by {@link ProjectionExpressionFactories}' field / order / limit / skip
 * factories to the exact rendering the SQL parser produces for the equivalent fragment. This
 * guards the IR-first path used by the Gremlin translator: building {@code alias.property},
 * {@code ORDER BY alias.field}, {@code LIMIT n}, and {@code SKIP n} directly as AST must stay
 * equivalent to parsing the same text, so removing the SQL-text round-trips changed nothing
 * observable (and closed the property-key injection surface, since the key is now a literal
 * identifier that can never be re-tokenized into extra syntax).
 */
public class ProjectionExpressionFactoriesTest {

  /** Builder-proven matched access and its copy bypass named reads, unlike a user expression. */
  @Test
  public void internalMatchedAccessUsesDirectRouteAndUserNamesDoNot() throws ParseException {
    var ctx = new ObservedContext();
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, Map.of("a", Map.of("name", "first")));
    var built = new MatchWhereBuilder().matchedAccess("a", "name");
    var copy = built.copy();
    assertThat(built.execute((Result) null, ctx)).isEqualTo("first");
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, Map.of("a", Map.of("name", "second")));
    assertThat(copy.execute((Result) null, ctx)).isEqualTo("second");
    assertThat(ctx.directReads).isEqualTo(4);
    assertThat(ctx.namedReads).isZero();
    ctx.setDynamicVariable("userName", c -> "user-value");
    assertThat(ProjectionExpressionFactories.contextVariable("$userName")
        .execute((Result) null, ctx)).isEqualTo("user-value");
    var userMatched = parse("SELECT $matched.a.name").getProjection().getItems().getFirst()
        .getExpression();
    assertThat(userMatched.execute((Result) null, ctx)).isEqualTo("second");
    // Each ordinary read enters both overloads. Direct calls to the defaulted overload and
    // bulk named access must also be visible without passing through getVariable(String).
    assertThat(ctx.namedReads).isEqualTo(8);
    assertThat(ctx.getVariable("$userName", "fallback")).isEqualTo("user-value");
    assertThat(ctx.namedReads).isEqualTo(9);
    assertThat(ctx.getVariables()).containsKey("userName");
    assertThat(ctx.namedReads).isEqualTo(10);
    assertThat(ctx.directReads).isEqualTo(4);
  }

  /** The list factory reads CURRENT_MATCH through the Result route, never CURRENT or named lookup. */
  @Test
  public void listCurrentMatchArgumentUsesDirectRouteAndCorrectRole() {
    var ctx = new ObservedContext();
    ctx.setSystemVariable(CommandContext.VAR_CURRENT, "current-row");
    ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, "match-candidate");
    var expression = ProjectionExpressionFactories.listCurrentMatch();
    var base = (SQLBaseExpression) expression.getMathExpression();
    var call = base.getIdentifier().getLevelZero().getFunctionCall();
    assertThat(call.getParams()).hasSize(1);
    var argument = call.getParams().getFirst();
    assertThat(argument.execute((Result) new ResultInternal(null), ctx))
        .isEqualTo("match-candidate");
    assertThat(ctx.namedReads).isZero();
    assertThat(ctx.directReads).isEqualTo(2);
  }

  /** Null context values retain Result columns, metadata, temporary properties, and misses. */
  @Test
  public void directSuffixRetainsNullAndMissingResultFallbacks() {
    var ctx = new ObservedContext();
    var suffix = new InternalContextVariableSuffix(InternalVariable.CURRENT);
    var row = new ResultInternal(null);
    assertThat(suffix.execute((Result) null, ctx)).isNull();
    assertThat(suffix.execute(mock(Result.class), ctx)).isNull();
    assertThat(suffix.execute(row, ctx)).isNull();
    row.setTemporaryProperty("$current", "temporary");
    assertThat(suffix.execute(row, ctx)).isEqualTo("temporary");
    row.setMetadata("$current", "metadata");
    assertThat(suffix.execute(row, ctx)).isEqualTo("metadata");
    row.setProperty("$current", "column");
    assertThat(suffix.execute(row, ctx)).isEqualTo("column");
    assertThat(suffix.execute(row, null)).isEqualTo("column");
    ctx.setVariable("current", null);
    ctx.setSystemVariable(CommandContext.VAR_CURRENT, "slot");
    assertThat(suffix.execute(row, ctx)).isEqualTo("column");
    assertThat(ctx.namedReads).isZero();
  }

  /** Both dynamic callbacks run. Resettable values reset only when enabled and cursors copy. */
  @Test
  public void directSuffixKeepsDynamicCallbacksResetAndCursorCopy() {
    var ctx = new ObservedContext();
    var suffix = new InternalContextVariableSuffix(InternalVariable.CURRENT_MATCH);
    var calls = new AtomicInteger();
    ctx.setDynamicVariable("currentMatch", c -> calls.incrementAndGet());
    assertThat(suffix.execute((Result) null, ctx)).isEqualTo(2);
    var resettable = mock(Resettable.class);
    ctx.setVariable("currentMatch", resettable);
    suffix.execute((Result) null, ctx);
    verify(resettable, never()).reset();
    when(resettable.isResetable()).thenReturn(true);
    assertThat(suffix.execute((Result) null, ctx)).isSameAs(resettable);
    verify(resettable).reset();
    var cursor = new InternalResultSet(null);
    ctx.setVariable("currentMatch", cursor);
    assertThat(suffix.execute((Result) null, ctx)).isInstanceOf(InternalResultSet.class)
        .isNotSameAs(cursor);
    assertThat(ctx.namedReads).isZero();
  }

  /** Concurrent copies read private live values. Re-execution never retains the previous value. */
  @Test
  public void copiedInternalExpressionsReadIndependentExecutionState() throws Exception {
    var expression = ProjectionExpressionFactories.internalVariable(InternalVariable.CURRENT, null);
    var copies = new SQLExpression[] {expression.copy(), expression.copy()};
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> readRepeatedValues(copies[0], "first"));
      var second = pool.submit(() -> readRepeatedValues(copies[1], "second"));
      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
      assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
    }
  }

  private static String readRepeatedValues(SQLExpression expression, String value) {
    var ctx = new ObservedContext();
    for (var i = 0; i < 100; i++) {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT, value + i);
      assertThat(expression.execute((Result) null, ctx)).isEqualTo(value + i);
    }
    assertThat(ctx.namedReads).isZero();
    return value;
  }

  private static final class ObservedContext extends BasicCommandContext {
    int directReads;
    int namedReads;

    @Override
    public Object getVariable(String name) {
      namedReads++;
      return super.getVariable(name);
    }

    @Override
    public Object getVariable(String name, Object defaultValue) {
      namedReads++;
      return super.getVariable(name, defaultValue);
    }

    @Override
    public Map<String, Object> getVariables() {
      namedReads++;
      return super.getVariables();
    }

    @Override
    public Object getInternalVariable(InternalVariable variable) {
      directReads++;
      return super.getInternalVariable(variable);
    }

    @Override
    public DatabaseSessionEmbedded getDatabaseSession() {
      return null;
    }
  }

  private static SQLSelectStatement parse(String sql) throws ParseException {
    return (SQLSelectStatement)
        new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8))).parse();
  }

  private static String render(SQLExpression expression) {
    var sb = new StringBuilder();
    expression.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  private static String render(SQLOrderByItem item) {
    var sb = new StringBuilder();
    item.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  private static String render(SQLLimit limit) {
    var sb = new StringBuilder();
    limit.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  private static String render(SQLSkip skip) {
    var sb = new StringBuilder();
    skip.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  /** {@code alias.property} field access renders identically to the parsed projection item. */
  @Test
  public void aliasProperty_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT v.name FROM V").getProjection().getItems().getFirst().getExpression();
    assertThat(render(ProjectionExpressionFactories.aliasProperty("v", "name")))
        .isEqualTo(render(parsed));
  }

  /** {@code alias.@rid} record-attribute access renders identically to the parsed item. */
  @Test
  public void aliasRecordAttributeRid_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT v.@rid FROM V").getProjection().getItems().getFirst().getExpression();
    assertThat(render(ProjectionExpressionFactories.aliasRecordAttribute("v", "@rid")))
        .isEqualTo(render(parsed));
  }

  /** {@code alias.@class} record-attribute access renders identically to the parsed item. */
  @Test
  public void aliasRecordAttributeClass_rendersLikeParser() throws ParseException {
    var parsed =
        parse("SELECT v.@class FROM V").getProjection().getItems().getFirst().getExpression();
    assertThat(render(ProjectionExpressionFactories.aliasRecordAttribute("v", "@class")))
        .isEqualTo(render(parsed));
  }

  /** {@code ORDER BY alias.property DESC} renders and types identically to the parsed order item. */
  @Test
  public void orderByProperty_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT FROM V ORDER BY v.name DESC").getOrderBy().getItems().getFirst();
    var built = ProjectionExpressionFactories.orderByProperty("v", "name", false);
    assertThat(render(built)).isEqualTo(render(parsed));
    assertThat(built.getType()).isEqualTo(SQLOrderByItem.DESC);
  }

  /** {@code ORDER BY alias.@rid ASC} renders identically to the parsed order item. */
  @Test
  public void orderByRecordAttribute_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT FROM V ORDER BY v.@rid ASC").getOrderBy().getItems().getFirst();
    var built = ProjectionExpressionFactories.orderByRecordAttribute("v", "@rid", true);
    assertThat(render(built)).isEqualTo(render(parsed));
    assertThat(built.getType()).isEqualTo(SQLOrderByItem.ASC);
  }

  /** {@code LIMIT n} renders identically to the parsed limit clause. */
  @Test
  public void limit_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT FROM V LIMIT 5").getLimit();
    assertThat(render(ProjectionExpressionFactories.limit(5))).isEqualTo(render(parsed));
  }

  /** {@code SKIP n} renders identically to the parsed skip clause. */
  @Test
  public void skip_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT FROM V SKIP 3").getSkip();
    assertThat(render(ProjectionExpressionFactories.skip(3))).isEqualTo(render(parsed));
  }

  /**
   * A property key with SQL metacharacters is treated as a literal identifier, never re-tokenized
   * into extra syntax — the AST has exactly one projection value (the injection surface is closed).
   */
  @Test
  public void aliasProperty_injectionKeyStaysLiteral() {
    var built = ProjectionExpressionFactories.aliasProperty("v", "name FROM V WHERE 1=1--");
    // Renders the whole thing as one quoted identifier segment, not a parseable expression tail.
    assertThat(render(built)).contains("name FROM V WHERE 1=1--");
    assertThat(render(built)).startsWith("v.");
  }

  /**
   * {@code propertyKey.type()} renders exactly as the parser renders the same fragment, so the
   * hand-built method-call node is the shape the executor already knows how to evaluate rather than
   * a lookalike.
   */
  @Test
  public void propertyMethodCall_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT age.type() FROM V").getProjection().getItems().getFirst()
        .getExpression();
    assertThat(render(ProjectionExpressionFactories.propertyMethodCall("age", "type")))
        .isEqualTo(render(parsed));
  }

  /**
   * A property key carrying SQL metacharacters stays one literal identifier under a method call —
   * the same injection-surface closure the field-access factories give, on the path the Gremlin
   * type guard supplies its key through.
   */
  @Test
  public void propertyMethodCall_injectionKeyStaysLiteral() {
    var built = ProjectionExpressionFactories.propertyMethodCall("k FROM V WHERE 1=1--", "type");
    assertThat(render(built)).contains("k FROM V WHERE 1=1--").endsWith(".type()");
  }
}
