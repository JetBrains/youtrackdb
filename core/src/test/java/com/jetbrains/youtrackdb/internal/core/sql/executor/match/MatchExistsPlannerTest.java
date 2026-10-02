package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMultiMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Planner and executor tests for detached positive patterns supplied through plan inputs. */
public class MatchExistsPlannerTest extends DbTestBase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    session.execute("CREATE CLASS ExistsPerson EXTENDS V").close();
    session.execute("CREATE CLASS ExistsLink EXTENDS E").close();
    session.begin();
    for (var name : List.of("one", "two", "three")) {
      session.execute("CREATE VERTEX ExistsPerson SET name = ?", name).close();
    }
    for (var names : List.of(List.of("one", "two"), List.of("one", "three"),
        List.of("two", "three"))) {
      session.execute("CREATE EDGE ExistsLink FROM (SELECT FROM ExistsPerson WHERE name = ?)"
          + " TO (SELECT FROM ExistsPerson WHERE name = ?)", names.get(0), names.get(1))
          .close();
    }
    session.commit();
  }

  /** Two edges from one root still yield one row, and a root with no edge yields none. */
  @Test
  public void exists_keepsEachMatchingRootOnce() {
    session.begin();
    var plan = plan("MATCH {class:ExistsPerson, as:p} RETURN p.name as name",
        "MATCH {as:p}.out('ExistsLink'){as:child} RETURN p");
    assertThat(plan.prettyPrint(0, 2)).contains("+ EXISTS (");
    assertThat(plan.getSteps()).anyMatch(FilterExistsMatchPatternStep.class::isInstance);
    var rows = plan.start().stream(plan.getContext())
        .map(row -> row.<String>getProperty("name")).sorted().toList();
    assertThat(rows).containsExactly("one", "two");
    plan.close();
    session.commit();
  }

  /** The check keeps each of two upstream paths from one root separately, not just one root. */
  @Test
  public void exists_preservesRepeatedUpstreamRows() {
    session.begin();
    var plan = plan("MATCH {class:ExistsPerson, as:p}.out('ExistsLink'){as:q}"
        + " RETURN p.name as name",
        "MATCH {as:p}.out('ExistsLink'){as:child} RETURN p");
    var rows = plan.start().stream(plan.getContext())
        .map(row -> row.<String>getProperty("name")).sorted().toList();
    assertThat(rows).containsExactly("one", "one", "two");
    plan.close();
    session.commit();
  }

  /** Count(*) must count only roots that pass EXISTS, not all three class records. */
  @Test
  public void exists_disablesClassCountFastPath() {
    session.begin();
    var plan = plan("MATCH {class:ExistsPerson, as:p} RETURN count(*) as total",
        "MATCH {as:p}.out('ExistsLink'){as:child} RETURN p");
    assertThat(plan.prettyPrint(0, 2)).contains("+ EXISTS (").doesNotContain("COUNT FROM CLASS");
    var stream = plan.start();
    try {
      assertThat(stream.hasNext(plan.getContext())).isTrue();
      assertThat(((Number) stream.next(plan.getContext()).getProperty("total")).longValue())
          .isEqualTo(2L);
    } finally {
      stream.close(plan.getContext());
      plan.close();
    }
    session.commit();
  }

  /** An unknown origin fails planning rather than dropping all rows during execution. */
  @Test
  public void exists_rejectsUnknownOrigin() {
    assertInvalid("MATCH {as:unknown}.out('ExistsLink'){as:child} RETURN unknown",
        "first alias in a EXISTS expression");
  }

  /** A WHERE predicate on the check origin is unsupported, as it is for NOT. */
  @Test
  public void exists_rejectsOriginFilter() {
    assertInvalid("MATCH {as:p, where:(name='one')}.out('ExistsLink'){as:child} RETURN p",
        "WHERE condition on the initial alias");
  }

  /** A multi-path item is unsupported, even when its origin is in the positive pattern. */
  @Test
  public void exists_rejectsMultiPathItem() {
    session.begin();
    var positive = parse("MATCH {class:ExistsPerson, as:p} RETURN p");
    var pattern = new Pattern();
    positive.getMatchExpressions().forEach(pattern::addExpression);
    var check = new SQLMatchExpression(-1);
    var origin = new SQLMatchFilter(-1);
    origin.setAlias("p");
    check.setOrigin(origin);
    var item = new SQLMultiMatchPathItem(-1);
    check.setItems(List.of(item));
    var inputs = MatchPlanInputs.builder(pattern)
        .aliasClasses(Map.of("p", "ExistsPerson"))
        .existsMatchExpressions(List.of(check)).build();
    assertThatThrownBy(() -> new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(), false, false))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("EXISTS expression is not supported");
    session.commit();
  }

  /** A plan copy preserves the check, its label, and the same filtered row set. */
  @Test
  public void exists_planCopyPreservesFilterAndExplain() {
    session.begin();
    var plan = plan("MATCH {class:ExistsPerson, as:p} RETURN p.name as name",
        "MATCH {as:p}.out('ExistsLink'){as:child} RETURN p");
    var copied = (SelectExecutionPlan) plan.copy(plan.getContext());
    assertThat(copied.prettyPrint(0, 2)).contains("+ EXISTS (");
    assertThat(copied.getSteps()).anyMatch(FilterExistsMatchPatternStep.class::isInstance);
    var rows = copied.start().stream(copied.getContext())
        .map(row -> row.<String>getProperty("name")).sorted().toList();
    assertThat(rows).containsExactly("one", "two");
    copied.close();
    plan.close();
    session.commit();
  }

  /** One matching path is sufficient: the step does not consume a second child result. */
  @Test
  public void exists_stopsAfterFirstMatchAndClosesSubstream() {
    session.begin();
    var ctx = context();
    var probed = new AtomicInteger();
    var consumed = new AtomicInteger();
    var closed = new AtomicInteger();
    var row = new ResultInternal(session);
    row.setProperty("p", "root");
    var source = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return ExecutionStream.resultIterator(List.of(row, row).iterator());
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        return this;
      }
    };
    var child = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext ctx) {
            return probed.incrementAndGet() <= 2;
          }

          @Override
          public com.jetbrains.youtrackdb.internal.core.query.Result next(CommandContext ctx) {
            consumed.incrementAndGet();
            return row;
          }

          @Override
          public void close(CommandContext ctx) {
            closed.incrementAndGet();
          }
        };
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        return this;
      }
    };
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(source);
    plan.chain(new FilterExistsMatchPatternStep(List.of(child), ctx, false));
    var stream = plan.start();
    assertThat(stream.stream(ctx).toList()).containsExactly(row, row);
    assertThat(probed.get()).isEqualTo(2);
    assertThat(consumed.get()).isZero();
    assertThat(closed.get()).isEqualTo(2);
    stream.close(ctx);
    session.commit();
  }

  /** An EXISTS traversal must not leak its child alias into the next EXISTS predicate. */
  @Test
  public void existsThenExists_restoresMatchedBeforeNextCheck() {
    assertDetachedChecks(
        "MATCH {class:ExistsPerson, as:p, where:(name='one')} RETURN p.name as name",
        List.of("MATCH {as:p}.out('ExistsLink'){as:child}.out('ExistsLink'){as:grand}"
            + " RETURN p",
            "MATCH {as:p}.out('ExistsLink'){as:x, where:($matched.child IS NULL)}"
                + " RETURN p"),
        1);
  }

  /** A preceding EXISTS must not make a subsequent NOT see its child alias. */
  @Test
  public void existsThenNot_restoresMatchedBeforeNegativeCheck() {
    assertDetachedChecks("MATCH {class:ExistsPerson, as:p, where:(name='one')},"
        + " NOT {as:p}.out('ExistsLink'){as:x, where:($matched.child IS NULL)}"
        + " RETURN p.name as name",
        List.of("MATCH {as:p}.out('ExistsLink'){as:child}.out('ExistsLink'){as:grand}"
            + " RETURN p"),
        0);
  }

  /** Two NOT probes must not leave the first probe's child alias for the second one. */
  @Test
  public void notThenNot_restoresMatchedBeforeNextNegativeCheck() {
    assertDetachedChecks("MATCH {class:ExistsPerson, as:p, where:(name='one')},"
        + " NOT {as:p}.out('ExistsLink'){as:child}.out('ExistsLink')"
        + " {as:grand, where:(name='absent')},"
        + " NOT {as:p}.out('ExistsLink'){as:x, where:($matched.child IS NULL)}"
        + " RETURN p.name as name", List.of(), 0);
  }

  /** RETURN reads the positive root binding after an EXISTS or NOT traversal closes. */
  @Test
  public void detachedCheck_restoresMatchedBeforeReturnProjection() {
    var exists = detachedPlan("MATCH {class:ExistsPerson, as:p, where:(name='one')}"
        + " RETURN p.name as name, $matched.child.name as leaked",
        List.of("MATCH {as:p}.out('ExistsLink'){as:child}.out('ExistsLink'){as:grand}"
            + " RETURN p"));
    session.begin();
    try {
      var stream = exists.start();
      try {
        var rows = stream.stream(exists.getContext()).toList();
        assertThat(rows).hasSize(1);
        assertThat((Object) rows.getFirst().getProperty("leaked")).isNull();
      } finally {
        stream.close(exists.getContext());
        exists.close();
      }
      var rows = session.query("MATCH {class:ExistsPerson, as:p, where:(name='one')},"
          + " NOT {as:p}.out('ExistsLink'){as:child}.out('ExistsLink')"
          + " {as:grand, where:(name='absent')}"
          + " RETURN p.name as name, $matched.child.name as leaked").toList();
      assertThat(rows).hasSize(1);
      assertThat((Object) rows.getFirst().getProperty("leaked")).isNull();
    } finally {
      session.rollback();
    }
  }

  /** The first upstream row has no $matched binding; a detached probe must not add one. */
  @Test
  public void existsWithoutOuterMatched_doesNotCreateBinding() {
    session.begin();
    var ctx = context();
    var row = new ResultInternal(session);
    row.setProperty("p", "root");
    var source = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return ExecutionStream.singleton(row);
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        return this;
      }
    };
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(source);
    plan.chain(new FilterExistsMatchPatternStep(List.of(), ctx, false));
    var stream = plan.start();
    try {
      assertThat(stream.stream(ctx).toList()).containsExactly(row);
      assertThat(ctx.hasSystemVariable(CommandContext.VAR_MATCHED)).isFalse();
    } finally {
      stream.close(ctx);
      plan.close();
      session.commit();
    }
  }

  /** A failing probe still closes its stream and restores the exact outer $matched row. */
  @Test
  public void detachedCheck_restoresMatchedWhenProbeThrows() {
    session.begin();
    var ctx = context();
    var outer = new ResultInternal(session);
    outer.setProperty("p", "outer");
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, outer);
    var row = new ResultInternal(session);
    row.setProperty("p", "root");
    var closed = new AtomicInteger();
    var source = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return ExecutionStream.singleton(row);
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        return this;
      }
    };
    var child = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext context) {
            assertThat(context.<Object>getSystemVariable(CommandContext.VAR_MATCHED))
                .isNotSameAs(outer);
            throw new IllegalStateException("probe failed");
          }

          @Override
          public com.jetbrains.youtrackdb.internal.core.query.Result next(CommandContext context) {
            throw new AssertionError("next must not run");
          }

          @Override
          public void close(CommandContext context) {
            closed.incrementAndGet();
          }
        };
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        return this;
      }
    };
    for (var negative : List.of(false, true)) {
      var plan = new SelectExecutionPlan(ctx);
      plan.chain(source);
      plan.chain(negative ? new FilterNotMatchPatternStep(List.of(child), ctx, false)
          : new FilterExistsMatchPatternStep(List.of(child), ctx, false));
      var stream = plan.start();
      try {
        assertThatThrownBy(() -> stream.hasNext(ctx))
            .isInstanceOf(IllegalStateException.class).hasMessage("probe failed");
        assertThat(ctx.<Object>getSystemVariable(CommandContext.VAR_MATCHED)).isSameAs(outer);
      } finally {
        stream.close(ctx);
        plan.close();
      }
    }
    assertThat(closed.get()).isEqualTo(2);
    session.commit();
  }

  private void assertDetachedChecks(String sql, List<String> existsChecks, int expected) {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      // A nested-loop NOT check exercises the same detached filter as EXISTS.
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);
      session.begin();
      try {
        if (existsChecks.isEmpty()) {
          assertThat(session.query(sql).toList()).hasSize(expected);
        } else {
          var plan = detachedPlan(sql, existsChecks);
          var stream = plan.start();
          try {
            assertThat(stream.stream(plan.getContext()).toList()).hasSize(expected);
          } finally {
            stream.close(plan.getContext());
            plan.close();
          }
        }
      } finally {
        session.rollback();
      }
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  private SelectExecutionPlan detachedPlan(String sql, List<String> existsChecks) {
    var positive = parse(sql);
    var pattern = new Pattern();
    positive.getMatchExpressions().forEach(pattern::addExpression);
    var checks = existsChecks.stream().map(check -> parse(check).getMatchExpressions().getFirst())
        .toList();
    var inputs = MatchPlanInputs.builder(pattern)
        .aliasClasses(Map.of("p", "ExistsPerson"))
        .aliasFilters(Map.of("p", positive.getMatchExpressions().getFirst().getOrigin()
            .getFilter()))
        .notMatchExpressions(positive.getNotMatchExpressions())
        .existsMatchExpressions(checks)
        .returnItems(positive.getReturnItems())
        .returnAliases(positive.getReturnAliases())
        .returnNestedProjections(positive.getReturnNestedProjections())
        .build();
    return (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(), false, false);
  }

  private void assertInvalid(String checkSql, String message) {
    session.begin();
    assertThatThrownBy(() -> plan("MATCH {class:ExistsPerson, as:p} RETURN p", checkSql))
        .isInstanceOf(CommandExecutionException.class).hasMessageContaining(message);
    session.commit();
  }

  private SelectExecutionPlan plan(String positiveSql, String checkSql) {
    var positive = parse(positiveSql);
    var check = parse(checkSql).getMatchExpressions().getFirst();
    var pattern = new Pattern();
    positive.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern)
        .aliasClasses(Map.of("p", "ExistsPerson"))
        .existsMatchExpressions(List.of(check))
        .returnItems(positive.getReturnItems())
        .returnAliases(positive.getReturnAliases())
        .returnNestedProjections(positive.getReturnNestedProjections())
        .build();
    return (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(), false, false);
  }

  private BasicCommandContext context() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static SQLMatchStatement parse(String query) {
    try {
      return (SQLMatchStatement) new YouTrackDBSql(
          new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8))).parse();
    } catch (Exception e) {
      throw new AssertionError("Could not parse " + query, e);
    }
  }
}
