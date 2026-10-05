package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Planner and executor tests for detached positive patterns supplied through plan inputs. */
@Category(SequentialTest.class)
public class MatchExistsPlannerTest extends DbTestBase {

  private Object savedMinimum;
  private Object savedThreshold;
  private Object savedSelectivity;

  @After
  public void restoreHashSettings() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(savedMinimum);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
    GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY.setValue(savedSelectivity);
  }

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    savedMinimum = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    savedSelectivity = GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY.getValue();
    // Plan-shape tests disable the cost guards. Guard tests set their own positive minimum.
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10_000L);
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

  /**
   * A zero-hop probe keeps an optional null origin, but a forced hash semi-join drops it.
   * Compare sorted row multisets before enabling the planner's exists hash path.
   */
  @Test
  public void exists_zeroHopOptionalOrigin_forcedHashDiffersFromPerRowProbe() {
    session.begin();
    try {
      var rows = session.query("MATCH {class:ExistsPerson, as:p}.out('ExistsLink')"
          + "{class:ExistsPerson, as:q, optional:true} RETURN q as q, p.name as name")
          .toList();
      var ctx = context();
      var nested = new SelectExecutionPlan(ctx);
      nested.chain(new AbstractExecutionStep(ctx, false) {
        @Override
        public ExecutionStream internalStart(CommandContext context) {
          return ExecutionStream.resultIterator(rows.iterator());
        }

        @Override
        public ExecutionStep copy(CommandContext context) {
          return this;
        }
      });
      nested.chain(new FilterExistsMatchPatternStep(List.of(), ctx, false));
      var nestedRows = nested.start().stream(ctx)
          .map(row -> row.<String>getProperty("name")).sorted().toList();
      var build = (SelectExecutionPlan) new MatchExecutionPlanner(
          parse("MATCH {class:ExistsPerson, as:q} RETURN q as q"))
          .createExecutionPlan(ctx, false, false);
      var hash = new SelectExecutionPlan(ctx);
      hash.chain(new AbstractExecutionStep(ctx, false) {
        @Override
        public ExecutionStream internalStart(CommandContext context) {
          return ExecutionStream.resultIterator(rows.iterator());
        }

        @Override
        public ExecutionStep copy(CommandContext context) {
          return this;
        }
      });
      hash.chain(new HashJoinMatchStep(ctx, build, List.of("q"), JoinMode.SEMI_JOIN, false));
      var hashRows = hash.start().stream(ctx)
          .map(row -> row.<String>getProperty("name")).sorted().toList();
      assertThat(nestedRows).containsExactly("one", "one", "three", "two");
      assertThat(hashRows).containsExactly("one", "one", "two");
      assertThat(hashRows).isNotEqualTo(nestedRows);
      nested.close();
      hash.close();
    } finally {
      session.rollback();
    }
  }

  /** Two edges from one root still yield one row, and a root with no edge yields none. */
  @Test
  public void exists_keepsEachMatchingRootOnce() {
    session.begin();
    var plan = plan("MATCH {class:ExistsPerson, as:p} RETURN p.name as name",
        "MATCH {as:p}.out('ExistsLink'){as:child} RETURN p");
    assertThat(plan.prettyPrint(0, 2)).contains("+ HASH SEMI_JOIN");
    assertThat(plan.getSteps()).anyMatch(HashJoinMatchStep.class::isInstance);
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

  /** A nested-loop EXISTS from a nullable optional origin keeps only its bound, matching row. */
  @Test
  public void exists_optionalOrigin_usesPositiveBinding() {
    session.begin();
    var plan = plan("MATCH {class:ExistsPerson, as:p}.out('ExistsLink')"
        + "{as:q, optional:true} RETURN p.name as name",
        "MATCH {as:q}.out('ExistsLink'){as:child} RETURN q");
    assertThat(plan.prettyPrint(0, 2)).contains("+ EXISTS (");
    var stream = plan.start();
    try {
      assertThat(stream.stream(plan.getContext())
          .map(row -> row.<String>getProperty("name")).sorted().toList())
          .containsExactly("one");
    } finally {
      stream.close(plan.getContext());
      plan.close();
      session.commit();
    }
  }

  /** Count(*) must count only roots that pass EXISTS, not all three class records. */
  @Test
  public void exists_disablesClassCountFastPath() {
    session.begin();
    var plan = plan("MATCH {class:ExistsPerson, as:p} RETURN count(*) as total",
        "MATCH {as:p}.out('ExistsLink'){as:child} RETURN p");
    assertThat(plan.prettyPrint(0, 2)).contains("+ HASH SEMI_JOIN")
        .doesNotContain("COUNT FROM CLASS");
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
    assertThat(copied.prettyPrint(0, 2)).contains("+ HASH SEMI_JOIN");
    assertThat(copied.getSteps()).anyMatch(HashJoinMatchStep.class::isInstance);
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

  /** Several matching child edges keep each duplicate outer row once on either path. */
  @Test
  public void detachedChecks_duplicateOuterRowsAndMultipleMatches_agreeOnBothPaths() {
    assertBothModes("MATCH {class:ExistsPerson, as:p}.out('ExistsLink'){as:q}",
        "{as:p}.out('ExistsLink'){as:child}", "p.name", List.of("one", "one", "two"),
        List.of(), true);
  }

  /** An optional shared target is unbound in the per-row probe, so hash stays ineligible. */
  @Test
  public void detachedChecks_optionalSharedAlias_agreeOnBothPaths() {
    assertBothModes("MATCH {class:ExistsPerson, as:p}.out('ExistsLink')"
        + "{as:q, optional:true}", "{as:p}.out('ExistsLink'){as:q}", "p.name",
        List.of("one", "one", "two"), List.of("three"), false);
  }

  /** A shared target omitted from RETURN still constrains both paths to the positive binding. */
  @Test
  public void detachedChecks_unreturnedSharedAlias_agreeOnBothPaths() {
    assertBothModes("MATCH {class:ExistsPerson, as:p}.out('ExistsLink'){as:q}",
        "{as:p}.out('ExistsLink'){as:q, where:(name='three')}", "p.name",
        List.of("one", "two"), List.of("one"), true);
  }

  /** Correlated WHILE reads the positive q, so both modes must keep the per-row path. */
  @Test
  public void detachedChecks_correlatedWhile_agreeOnBothPaths() {
    assertBothModes("MATCH {class:ExistsPerson, as:p}.out('ExistsLink'){as:q}",
        "{as:p}.out('ExistsLink'){as:x, maxDepth:2, while:($matched.q IS NOT NULL),"
            + " where:(name='three')}",
        "p.name", List.of("one", "one", "two"),
        List.of(), false);
  }

  /** The reproduced zero-hop null-origin mismatch keeps both modes on the per-row path. */
  @Test
  public void detachedChecks_zeroHopOptionalOrigin_rejectHash() {
    assertBothModes("MATCH {class:ExistsPerson, as:p}.out('ExistsLink')"
        + "{class:ExistsPerson, as:q, optional:true}", "{as:q}", "p.name",
        List.of("one", "one", "three", "two"), List.of(), false);
  }

  /** Hops from an optional origin cannot traverse null, so hash and per-row checks agree. */
  @Test
  public void detachedChecks_optionalOriginWithHop_agreeOnBothPaths() {
    assertBothModes("MATCH {class:ExistsPerson, as:p}.out('ExistsLink')"
        + "{class:ExistsPerson, as:q, optional:true}",
        "{as:q}.out('ExistsLink'){as:child}", "p.name", List.of("one"),
        List.of("one", "three", "two"), true);
  }

  /** The first guard rejects a small outer input even when the build is eligible. */
  @Test
  public void exists_guardOneSmallOuter_usesNestedLoop() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(100L);
    assertExistsChoice("MATCH {class:ExistsPerson, as:p} RETURN p.name as name", false);
  }

  /** Above the minimum, a one-root build costs more than probing that one root directly. */
  @Test
  public void exists_guardTwoBuildMoreExpensive_usesNestedLoop() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(1L);
    assertExistsChoice("MATCH {class:ExistsPerson, as:p, where:(name='one')}"
        + " RETURN p.name as name", false);
  }

  /** Repeated origins make the eligible hash build cheaper than repeated complete walks. */
  @Test
  public void exists_guardTwoRepeatedOrigins_usesHash() {
    session.begin();
    for (var i = 0; i < 9; i++) {
      session.execute("CREATE EDGE ExistsLink FROM (SELECT FROM ExistsPerson WHERE name='one')"
          + " TO (SELECT FROM ExistsPerson WHERE name='three')").close();
    }
    session.commit();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(1L);
    assertExistsChoice("MATCH {class:ExistsPerson, as:p}.out('ExistsLink'){as:q}"
        + " RETURN p.name as name", true);
  }

  /** Unknown recursive walk cost bypasses both guards, including a large minimum. */
  @Test
  public void exists_recursiveWalkBypassesGuards_usesHash() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(1000L);
    session.begin();
    try {
      var plan = plan("MATCH {class:ExistsPerson, as:p} RETURN p.name as name",
          "MATCH {as:p}.out('ExistsLink'){as:child, maxDepth:2} RETURN p");
      assertThat(plan.prettyPrint(0, 2)).contains("+ HASH SEMI_JOIN");
      plan.close();
    } finally {
      session.rollback();
    }
  }

  /**
   * Plan both modes with an eligible estimate, lower the limit only at execution, and copy.
   * Origin-only and wider-key builds must fall back to the same per-row row multiset.
   */
  @Test
  public void detachedChecks_runtimeOverflowAndCopy_agreeWithPerRowProbe() {
    session.begin();
    try {
      for (var negative : List.of(false, true)) {
        for (var sharedTarget : List.of(false, true)) {
          GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10_000L);
          var positive = "MATCH {class:ExistsPerson, as:p}.out('ExistsLink'){as:q}";
          var check = "{as:p}.out('ExistsLink'){as:" + (sharedTarget ? "q" : "child") + "}";
          var sql = positive + (negative ? ", NOT " + check : "") + " RETURN p.name as name";
          var plan = detachedPlan(sql,
              negative ? List.of() : List.of("MATCH " + check + " RETURN p"));
          assertThat(plan.prettyPrint(0, 2))
              .contains("+ HASH " + (negative ? "ANTI_JOIN" : "SEMI_JOIN"));
          var copied = (SelectExecutionPlan) plan.copy(context());
          GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
          var stream = copied.start();
          try {
            var rows = stream.stream(copied.getContext())
                .map(row -> row.<String>getProperty("name")).sorted().toList();
            assertThat(rows).isEqualTo(negative ? List.of() : List.of("one", "one", "two"));
          } finally {
            stream.close(copied.getContext());
            copied.close();
            plan.close();
          }
        }
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * An origin-only build probes each scanned origin once and never consumes a matching child.
   * Both modes preserve duplicate incoming rows. The detached child streams are always closed.
   */
  @Test
  public void detachedHash_originOnlyBuild_stopsAtFirstMatch() {
    assertDetachedHashWork(false, false);
  }

  /**
   * Overflow on a copied detached step must not restart the whole origin scan per outer row.
   * The fallback probes only incoming rows and stops at the first child in both modes.
   */
  @Test
  public void detachedHash_overflow_usesPerRowProbeWithoutRescanningBuild() {
    assertDetachedHashWork(true, false);
  }

  /** Wider-key builds must consume every key, and overflow must retain the detached fallback. */
  @Test
  public void detachedHash_widerKeyBuildAndOverflow_preserveFullBuild() {
    assertDetachedHashWork(false, true);
    assertDetachedHashWork(true, true);
  }

  /** Literal LIMIT changes both EXPLAIN modes from hash to per-row without changing sliced rows. */
  @Test
  public void detachedLimit_frequentPassesAndSkip_choosePerRow() {
    seedSliceOrigins();
    for (var negative : List.of(false, true)) {
      var check = "{as:p}.out('SliceCheck'){as:child"
          + (negative ? ", where:(flag=true)" : "") + "}";
      assertSlicePlan(negative, check, "p.name as name", "", true, 300);
      assertSlicePlan(negative, check, "p.name as name", " LIMIT 1", false, 1);
      assertSlicePlan(negative, check, "p.name as name", " SKIP 1 LIMIT 2", false, 2);
      assertSlicePlan(negative, check, "p.name as name", " LIMIT 0", false, 0);
      assertSlicePlan(negative, check, "p.name as name", " LIMIT -1", true, 300);
      assertSlicePlan(negative, check, "p.name as name", " ORDER BY name LIMIT 1", true, 1);
    }
  }

  /** Rare passing origins require full input, even though LIMIT asks for only one row. */
  @Test
  public void detachedLimit_rarePasses_keepHashAndResults() {
    seedSliceOrigins();
    GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY.setValue(0.0001);
    assertSlicePlan(false, "{as:p}.out('SliceCheck'){as:child, where:(flag=false)}",
        "p.name as name", " LIMIT 1", true, 1);
    // NOT's pass fraction is exp(-10), so S exceeds B even with a one-row slice.
    assertSlicePlan(true, "{as:p}.out('SliceCheck'){as:child}",
        "p.name as name", " LIMIT 1", true, 0);
  }

  /** EXPAND, element unrolling, legacy DISTINCT and wider keys keep the full-input hash choice. */
  @Test
  public void detachedLimit_rowChangingReturnsAndWiderKeys_keepHash() {
    seedSliceOrigins();
    for (var negative : List.of(false, true)) {
      var check = "{as:p}.out('SliceCheck'){as:child"
          + (negative ? ", where:(flag=true)" : "") + "}";
      for (var projection : List.of("expand(p)", "$elements", "$pathElements",
          "distinct(p.name) as name")) {
        assertSlicePlan(negative, check, projection, " LIMIT 1", true, 1);
      }
      var wide = "{as:p}.out('SliceCheck'){as:q"
          + (negative ? ", where:(flag=true)" : "") + "}";
      assertSlicePlan(negative, wide, "p.name as name", " LIMIT 1", true, 1);
    }
  }

  /** Zero-hop exists passes every origin. Zero-hop NOT passes none and never discounts input. */
  @Test
  public void detachedLimit_zeroHopAndOptionalOrigin_preserveExactSemantics() {
    seedSliceOrigins();
    assertSlicePlan(false, "{as:p}", "p.name as name", " LIMIT 1", false, 1);
    assertSlicePlan(true, "{as:p}", "p.name as name", " LIMIT 1", false, 0);
    session.begin();
    try {
      for (var negative : List.of(false, true)) {
        var sql = "MATCH {class:ExistsPerson, as:p}.out('ExistsLink')"
            + "{class:ExistsPerson, as:q, optional:true}"
            + (negative ? ", NOT {as:q}" : "") + " RETURN p.name as name LIMIT 1";
        var plan = detachedPlan(sql, negative ? List.of() : List.of("MATCH {as:q} RETURN q"));
        assertThat(plan.prettyPrint(0, 2)).doesNotContain("+ HASH")
            .contains(negative ? "+ NOT (" : "+ EXISTS (");
        var stream = plan.start();
        try {
          assertThat(stream.stream(plan.getContext()).toList()).hasSize(negative ? 0 : 1);
        } finally {
          stream.close(plan.getContext());
          plan.close();
        }
      }
    } finally {
      session.rollback();
    }
  }

  /** Parameter LIMIT never discounts. SQL cache reuse keeps hash but reads each slice binding. */
  @Test
  public void detachedLimit_parameterCacheReuse_readsFreshBindings() {
    seedSliceOrigins();
    var sql = slicePositive() + ", NOT {as:p}.out('SliceCheck')"
        + "{as:child, where:(flag=true)} RETURN p.name as name LIMIT :n";
    session.begin();
    try {
      for (var n : List.of(1, 17)) {
        try (var rows = session.query(sql, Map.of("n", n))) {
          assertThat(rows.getExecutionPlan().prettyPrint(0, 2)).contains("+ HASH ANTI_JOIN");
          assertThat(rows.toList()).hasSize(n);
        }
        var ctx = context();
        ctx.setInputParameters(Map.of("n", n));
        var cached = YqlExecutionPlanCache.get(sql, ctx, session);
        assertThat(cached).isNotNull();
        assertThat(cached.prettyPrint(0, 2)).contains("+ HASH ANTI_JOIN");
      }
    } finally {
      session.rollback();
    }
  }

  private static String slicePositive() {
    return "MATCH {class:SliceOrigin, as:p}.out('SliceFan'){as:q}";
  }

  /** Thirty origins each generate ten outer rows and ten detached candidates. */
  private void seedSliceOrigins() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(1L);
    GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY.setValue(0.1);
    session.execute("CREATE CLASS SliceOrigin EXTENDS V").close();
    session.execute("CREATE CLASS SliceTarget EXTENDS V").close();
    session.execute("CREATE CLASS SliceFan EXTENDS E").close();
    session.execute("CREATE CLASS SliceCheck EXTENDS E").close();
    session.begin();
    var target = session.newVertex("SliceTarget");
    target.setProperty("flag", false);
    for (var i = 0; i < 30; i++) {
      var origin = session.newVertex("SliceOrigin");
      origin.setProperty("name", "origin" + i);
      for (var j = 0; j < 10; j++) {
        origin.addEdge(target, "SliceFan");
        origin.addEdge(target, "SliceCheck");
      }
    }
    session.commit();
  }

  private void assertSlicePlan(boolean negative, String check, String projection, String tail,
      boolean hash, int expected) {
    session.begin();
    try {
      List<com.jetbrains.youtrackdb.internal.core.query.Result> chosenRows = null;
      for (var threshold : List.of(10_000L, 0L)) {
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(threshold);
        var sql = slicePositive() + (negative ? ", NOT " + check : "")
            + " RETURN " + projection + tail;
        var plan = detachedPlan(sql,
            negative ? List.of() : List.of("MATCH " + check + " RETURN p"));
        assertThat(plan.prettyPrint(0, 2)).contains(threshold > 0 && hash
            ? "+ HASH " + (negative ? "ANTI_JOIN" : "SEMI_JOIN")
            : negative ? "+ NOT (" : "+ EXISTS (");
        var stream = plan.start();
        try {
          var rows = stream.stream(plan.getContext()).toList();
          assertThat(rows).hasSize(expected);
          if (chosenRows != null) {
            assertThat(rows).isEqualTo(chosenRows);
          }
          chosenRows = rows;
        } finally {
          stream.close(plan.getContext());
          plan.close();
        }
      }
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10_000L);
      session.rollback();
    }
  }

  private void assertDetachedHashWork(boolean overflow, boolean wide) {
    session.begin();
    try {
      for (var mode : List.of(JoinMode.ANTI_JOIN, JoinMode.SEMI_JOIN)) {
        var ctx = context();
        var scans = new AtomicInteger();
        var consumed = new AtomicInteger();
        var probes = new AtomicInteger();
        var closed = new AtomicInteger();
        var first = new ResultInternal(session);
        first.setProperty("p", "first");
        first.setProperty("q", "target1");
        var second = new ResultInternal(session);
        second.setProperty("p", "second");
        second.setProperty("q", "target2");
        var absent = new ResultInternal(session);
        absent.setProperty("p", "absent");
        absent.setProperty("q", "target3");
        var child = new AbstractExecutionStep(ctx, false) {
          @Override
          public ExecutionStream internalStart(CommandContext context) {
            return new ExecutionStream() {
              @Override
              public boolean hasNext(CommandContext context) {
                probes.incrementAndGet();
                var matched = context.<ResultInternal>getSystemVariable(CommandContext.VAR_MATCHED);
                return !"absent".equals(matched.getProperty("p"));
              }

              @Override
              public com.jetbrains.youtrackdb.internal.core.query.Result next(CommandContext c) {
                throw new AssertionError("an early-stop probe must not consume its matching child");
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
        var build = new SelectExecutionPlan(ctx);
        build.chain(countedSource(ctx, List.of(first, second), scans, consumed));
        var step = new HashJoinMatchStep(ctx, build, wide ? List.of("p", "q") : List.of("p"),
            mode, List.of(child), false);
        var plan = new SelectExecutionPlan(ctx);
        plan.chain(countedSource(ctx, List.of(first, first, absent), new AtomicInteger(),
            new AtomicInteger()));
        plan.chain((AbstractExecutionStep) step.copy(ctx));
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(overflow ? 1L : 10_000L);
        var stream = plan.start();
        try {
          assertThat(stream.stream(ctx).toList()).containsExactlyElementsOf(
              mode == JoinMode.SEMI_JOIN ? List.of(first, first) : List.of(absent));
          assertThat(scans.get()).isEqualTo(1);
          assertThat(consumed.get()).isEqualTo(2);
          assertThat(probes.get()).isEqualTo((wide ? 0 : 2) + (overflow ? 3 : 0));
          assertThat(closed.get()).isEqualTo(probes.get());
        } finally {
          stream.close(ctx);
          plan.close();
          step.close();
        }
      }
    } finally {
      session.rollback();
    }
  }

  private AbstractExecutionStep countedSource(CommandContext ctx, List<ResultInternal> rows,
      AtomicInteger starts, AtomicInteger consumed) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        starts.incrementAndGet();
        return ExecutionStream.resultIterator(rows.iterator()).map((row, c) -> {
          consumed.incrementAndGet();
          return row;
        });
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        return countedSource(context, rows, starts, consumed);
      }
    };
  }

  private void assertExistsChoice(String positive, boolean hash) {
    session.begin();
    try {
      var plan = detachedPlan(positive,
          List.of("MATCH {as:p}.out('ExistsLink'){as:child} RETURN p"));
      assertThat(plan.prettyPrint(0, 2)).contains(hash ? "+ HASH SEMI_JOIN" : "+ EXISTS (");
      plan.close();
    } finally {
      session.rollback();
    }
  }

  private void assertBothModes(String positive, String check, String projection,
      List<String> existsRows, List<String> notRows, boolean eligible) {
    session.begin();
    try {
      for (var negative : List.of(false, true)) {
        List<String> first = null;
        for (var threshold : List.of(10_000L, 0L)) {
          GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(threshold);
          var sql = positive + (negative ? ", NOT " + check : "")
              + " RETURN " + projection + " as name";
          var plan = detachedPlan(sql,
              negative ? List.of() : List.of("MATCH " + check + " RETURN p"));
          var label = threshold > 0 && eligible
              ? "+ HASH " + (negative ? "ANTI_JOIN" : "SEMI_JOIN")
              : negative ? "+ NOT (" : "+ EXISTS (";
          assertThat(plan.prettyPrint(0, 2)).contains(label);
          // Exercise copy() on each chosen path, without relying on SQL plan-cache invalidation.
          var copied = (SelectExecutionPlan) plan.copy(context());
          var stream = copied.start();
          try {
            var rows = stream.stream(copied.getContext())
                .map(row -> row.<String>getProperty("name")).sorted().toList();
            assertThat(rows).isEqualTo(negative ? notRows : existsRows);
            if (first != null) {
              assertThat(rows).isEqualTo(first);
            }
            first = rows;
          } finally {
            stream.close(copied.getContext());
            copied.close();
            plan.close();
          }
        }
      }
    } finally {
      session.rollback();
    }
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
    var classes = new HashMap<String, String>();
    var filters = new HashMap<String, SQLWhereClause>();
    for (var expression : positive.getMatchExpressions()) {
      var nodes = new ArrayList<SQLMatchFilter>();
      nodes.add(expression.getOrigin());
      expression.getItems().forEach(item -> nodes.add(item.getFilter()));
      for (var node : nodes) {
        var className = node.getClassName(context());
        if (className != null) {
          classes.put(node.getAlias(), className);
        }
        if (node.getFilter() != null) {
          filters.put(node.getAlias(), node.getFilter());
        }
      }
    }
    var inputs = MatchPlanInputs.builder(pattern)
        .aliasClasses(classes)
        .aliasFilters(filters)
        .notMatchExpressions(positive.getNotMatchExpressions())
        .existsMatchExpressions(checks)
        .returnItems(positive.getReturnItems())
        .returnAliases(positive.getReturnAliases())
        .returnNestedProjections(positive.getReturnNestedProjections())
        .limit(positive.getLimit()).skip(positive.getSkip())
        .orderBy(positive.getOrderBy()).groupBy(positive.getGroupBy())
        .unwind(positive.getUnwind()).returnDistinct(positive.isReturnDistinct())
        .returnElements(positive.returnsElements()).returnPaths(positive.returnsPaths())
        .returnPatterns(positive.returnsPatterns())
        .returnPathElements(positive.returnsPathElements())
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
    return detachedPlan(positiveSql, List.of(checkSql));
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
