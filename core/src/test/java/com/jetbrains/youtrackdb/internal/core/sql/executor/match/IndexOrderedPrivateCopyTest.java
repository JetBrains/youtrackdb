package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.GlobalConfigurationScope;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.LimitExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SkipExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Private ordered plans must use current bindings without modifying their unexecuted template. */
@Category(SequentialTest.class)
public class IndexOrderedPrivateCopyTest extends DbTestBase {

  private static final String BASE =
      "MATCH {class: CopySrc, as: s, where: (id = :source)}"
          + ".out('CopyLink'){class: CopyTgt, as: m, where:"
          + " ((score >= :floor OR score IS NULL) AND name.toLowerCase() <> :excluded)}"
          + " RETURN m.name AS name ORDER BY m.score ASC, m.name ASC";

  // Ordinary scalar methods keep their existing statement-level cache refusal.
  private static final String CACHE_BASE = BASE.replace("name.toLowerCase()", "name");

  private void seed() {
    session.execute("CREATE CLASS CopySrc EXTENDS V").close();
    session.execute("CREATE PROPERTY CopySrc.id INTEGER").close();
    session.execute("CREATE INDEX CopySrc_id ON CopySrc (id) UNIQUE").close();
    session.execute("CREATE CLASS CopyTgt EXTENDS V").close();
    session.execute("CREATE PROPERTY CopyTgt.score INTEGER").close();
    session.execute("CREATE INDEX CopyTgt_score ON CopyTgt (score) NOTUNIQUE").close();
    session.execute("CREATE CLASS CopyLink EXTENDS E").close();
    session.execute("CREATE CLASS OtherLink EXTENDS E").close();
    session.begin();
    for (var id = 1; id <= 3; id++) {
      session.execute("CREATE VERTEX CopySrc SET id = " + id).close();
    }
    for (var item : List.of("name = 'null'", "score = 1, name = 'b'",
        "score = 1, name = 'a'", "score = 2, name = 'c'")) {
      session.execute("CREATE VERTEX CopyTgt SET " + item).close();
    }
    for (var i = 0; i < 40; i++) {
      session.execute("CREATE VERTEX CopyTgt SET score = 10, name = 'orphan" + i + "'")
          .close();
    }
    session.execute("CREATE EDGE CopyLink FROM (SELECT FROM CopySrc WHERE id = 1)"
        + " TO (SELECT FROM CopyTgt WHERE score < 10 OR score IS NULL)").close();
    session.execute("CREATE EDGE CopyLink FROM (SELECT FROM CopySrc WHERE id = 2)"
        + " TO (SELECT FROM CopyTgt WHERE name IN ['b', 'c'])").close();
    session.execute("CREATE EDGE OtherLink FROM (SELECT FROM CopySrc WHERE id = 1)"
        + " TO (SELECT FROM CopyTgt WHERE name = 'c')").close();
    session.commit();
  }

  private BasicCommandContext context(DatabaseSessionEmbedded db, Map<Object, Object> bindings) {
    var ctx = new BasicCommandContext(db);
    ctx.setInputParameters(bindings);
    return ctx;
  }

  private InternalExecutionPlan plan(String query, CommandContext ctx) {
    return ((SQLMatchStatement) SQLEngine.parse(query, ctx.getDatabaseSession()))
        .createExecutionPlanNoCache(ctx, false);
  }

  private static Map<Object, Object> bindings(int source, int floor, String excluded,
      int skip, int limit) {
    return Map.of("source", source, "floor", floor, "excluded", excluded,
        "skip", skip, "limit", limit, "label", "CopyLink");
  }

  private static IndexOrderedEdgeStep ordered(InternalExecutionPlan plan) {
    return plan.getSteps().stream().filter(IndexOrderedEdgeStep.class::isInstance)
        .map(IndexOrderedEdgeStep.class::cast).findFirst().orElseThrow();
  }

  private static List<String> drain(ExecutionStream stream, CommandContext ctx) {
    var names = new ArrayList<String>();
    try {
      while (stream.hasNext(ctx)) {
        var row = stream.next(ctx);
        names.add(row.hasProperty("name") ? row.getProperty("name")
            : row.getVertex("m").getProperty("name"));
      }
    } finally {
      stream.close(ctx);
    }
    return names;
  }

  private List<String> run(InternalExecutionPlan plan) {
    try {
      return drain(plan.start(), plan.getContext());
    } finally {
      plan.close();
    }
  }

  /** Alternating source, nested filter and slice bindings match fresh plans, including nulls/ties. */
  @Test
  public void alternatingCopiesUseCurrentBindingsAndLeaveTemplateUnexecuted() {
    seed();
    session.begin();
    var query = BASE + " SKIP :skip LIMIT :limit";
    var first = bindings(1, 0, "none", 0, 1);
    var template = plan(query, context(session, first));
    assertThat(template.canBeCached()).isTrue();
    var inputs = List.of(first, bindings(2, 1, "b", 0, 2),
        bindings(1, 0, "none", 1, -1), bindings(3, 0, "none", 0, 2),
        bindings(1, 0, "none", 0, 4),
        bindings(1, 0, "none", Integer.MAX_VALUE, Integer.MAX_VALUE), first);
    var expected = List.of(List.of("null"), List.of("c"), List.of("a", "b", "c"),
        List.<String>of(), List.of("null", "a", "b", "c"), List.<String>of(), List.of("null"));
    for (var i = 0; i < inputs.size(); i++) {
      var actual = run(template.copy(context(session, inputs.get(i))));
      assertThat(actual).isEqualTo(expected.get(i));
      assertThat(actual).isEqualTo(run(plan(query, context(session, inputs.get(i)))));
    }
    session.execute("CREATE VERTEX CopyTgt SET score = 0, name = 'pending'").close();
    session.execute("CREATE EDGE CopyLink FROM (SELECT FROM CopySrc WHERE id = 1)"
        + " TO (SELECT FROM CopyTgt WHERE name = 'pending')").close();
    assertThat(run(template.copy(context(session, first)))).containsExactly("null");
    var pending = bindings(1, 0, "none", 0, 2);
    assertThat(run(template.copy(context(session, pending)))).containsExactly("null", "pending")
        .isEqualTo(run(plan(query, context(session, pending))));
    // A large finite heap target keeps the existing element-cap error on both routes.
    var tooLarge = bindings(1, 0, "none", 0, Integer.MAX_VALUE);
    assertThatThrownBy(() -> run(template.copy(context(session, tooLarge))))
        .hasMessageContaining("in-heap ORDER BY");
    assertThatThrownBy(() -> run(plan(query, context(session, tooLarge))))
        .hasMessageContaining("in-heap ORDER BY");
    assertThat(ordered(template).getChosenRuntimePath()).isNull();
    assertThat(ordered(template).lastScanConsumedEntries()).isEqualTo(-1);
    session.rollback();
  }

  /** Present built-in slice clauses survive negative first bindings. Absent clauses stay absent. */
  @Test
  public void builtinSlicesRetainPresenceAndAllNegativeLimitsRemainUnbounded() {
    seed();
    session.begin();
    for (var mode : List.of("$paths", "$patterns")) {
      var base = BASE.replace("m.name AS name", mode);
      var query = base + " SKIP :skip LIMIT :limit";
      var template = plan(query, context(session, bindings(1, 0, "none", -2, -2)));
      assertThat(template.getSteps()).anyMatch(SkipExecutionStep.class::isInstance)
          .anyMatch(LimitExecutionStep.class::isInstance);
      for (var limit : List.of(-2, -1, 0, 2, -2)) {
        var params = bindings(1, 0, "none", limit == 2 ? 1 : -2, limit);
        var actual = run(template.copy(context(session, params)));
        assertThat(actual).isEqualTo(limit == 0 ? List.of()
            : limit == 2 ? List.of("a", "b") : List.of("null", "a", "b", "c"));
        assertThat(actual).isEqualTo(run(plan(query, context(session, params))));
      }
      for (var suffix : List.of("", " SKIP :skip", " LIMIT :limit")) {
        var absent = plan(base + suffix, context(session, bindings(1, 0, "none", 0, 2)));
        assertThat(absent.getSteps().stream().anyMatch(SkipExecutionStep.class::isInstance))
            .isEqualTo(suffix.contains("SKIP"));
        assertThat(absent.getSteps().stream().anyMatch(LimitExecutionStep.class::isInstance))
            .isEqualTo(suffix.contains("LIMIT"));
        assertThat(run(absent)).hasSize(suffix.contains("LIMIT") ? 2 : 4);
      }
    }
    session.rollback();
  }

  /** Cold publication and alternating warm bindings agree with fresh plans, including unbounded. */
  @Test
  public void warmStatementsRebindFiltersSourcesAndSlices() {
    seed();
    session.begin();
    var cache = YqlExecutionPlanCache.instance(session);
    var query = CACHE_BASE + " SKIP :skip LIMIT :limit";
    var inputs = List.of(bindings(1, 0, "none", 0, 1), bindings(2, 1, "b", 0, 2),
        bindings(1, 0, "none", 1, -1), bindings(3, 0, "none", 0, 2),
        bindings(1, 0, "none", 0, 0), bindings(1, 0, "none", 0, 4),
        bindings(1, 0, "none", Integer.MAX_VALUE, Integer.MAX_VALUE), inputsFirst());
    var expected = List.of(List.of("null"), List.of("c"), List.of("a", "b", "c"),
        List.<String>of(), List.<String>of(), List.of("null", "a", "b", "c"),
        List.<String>of(), List.of("null"));
    cache.invalidate();
    for (var i = 0; i < inputs.size(); i++) {
      var hits = cache.getHits();
      var execution = cachedPlan(query, context(session, inputs.get(i)));
      assertThat(ordered(execution).getChosenRuntimePath()).isNull();
      assertThat(cache.contains(query)).isTrue();
      assertThat(cache.getHits()).isEqualTo(hits + (i == 0 ? 0 : 1));
      assertThat(run(execution)).isEqualTo(expected.get(i))
          .isEqualTo(run(plan(query, context(session, inputs.get(i)))));
    }
    session.rollback();
  }

  /** Step permission must not override the existing scalar-method statement refusal. */
  @Test
  public void orderedStepKeepsMethodStatementRefusal() {
    seed();
    session.begin();
    var query = BASE + " SKIP :skip LIMIT :limit";
    var cache = YqlExecutionPlanCache.instance(session);
    cache.invalidate();
    for (var i = 0; i < 2; i++) {
      var statement = (SQLMatchStatement) SQLEngine.parse(query, session);
      assertThat(statement.executinPlanCanBeCached(session)).isFalse();
      var execution = statement.createExecutionPlan(context(session, inputsFirst()), false);
      assertThat(ordered(execution).canBeCached()).isTrue();
      assertThat(run(execution)).containsExactly("null");
      assertThat(cache.contains(query)).isFalse();
    }
    session.rollback();
  }

  /** Explicit no-cache and profiling builds do not publish an eligible ordered statement. */
  @Test
  public void orderedStatementsKeepNoCacheAndProfilingRefusals() {
    seed();
    session.begin();
    var query = CACHE_BASE + " LIMIT :limit";
    var cache = YqlExecutionPlanCache.instance(session);
    cache.invalidate();
    var statement = (SQLMatchStatement) SQLEngine.parse(query, session);
    var noCache = statement.createExecutionPlanNoCache(context(session, inputsFirst()), false);
    assertThat(ordered(noCache).canBeCached()).isTrue();
    assertThat(run(noCache)).containsExactly("null");
    assertThat(cache.contains(query)).isFalse();
    var profiled = statement.createExecutionPlan(context(session, inputsFirst()), true);
    assertThat(ordered(profiled).canBeCached()).isTrue();
    assertThat(run(profiled)).containsExactly("null");
    assertThat(cache.contains(query)).isFalse();
    session.rollback();
  }

  private static Map<Object, Object> inputsFirst() {
    return bindings(1, 0, "none", 0, 1);
  }

  private InternalExecutionPlan cachedPlan(String query, CommandContext ctx) {
    return ((SQLMatchStatement) SQLEngine.parse(query, ctx.getDatabaseSession()))
        .createExecutionPlan(ctx, false);
  }

  /** Built-in and projected rows keep present clauses on hits and omit absent clauses. */
  @Test
  public void warmSlicesKeepClausePresenceAndColdNegativeAdmission() {
    seed();
    session.begin();
    var cache = YqlExecutionPlanCache.instance(session);
    for (var projection : List.of("m.name AS name", "$paths", "$patterns")) {
      for (var suffix : List.of("", " SKIP :skip", " LIMIT :limit", " SKIP :skip LIMIT :limit")) {
        var query = CACHE_BASE.replace("m.name AS name", projection) + suffix;
        for (var initialLimit : List.of(-2, 0, 2)) {
          cache.invalidate();
          var first = cachedPlan(query, context(session, bindings(1, 0, "none", 0,
              initialLimit)));
          // A cold unbounded candidate may use ordinary MATCH. Warm copies retain that shape.
          assertThat(first.getSteps()).filteredOn(IndexOrderedEdgeStep.class::isInstance)
              .as("cold admission for %s with limit %s", query, initialLimit)
              .hasSize(suffix.contains("LIMIT") && initialLimit >= 0 ? 1 : 0);
          run(first);
          assertThat(cache.contains(query)).isTrue();
          for (var limit : List.of(2, -1, 0, -2, 2)) {
            var params = bindings(1, 0, "none", limit == 2 ? 1 : 0, limit);
            var hits = cache.getHits();
            var warm = cachedPlan(query, context(session, params));
            assertThat(cache.getHits()).isEqualTo(hits + 1);
            assertThat(warm.getSteps().stream().anyMatch(SkipExecutionStep.class::isInstance))
                .isEqualTo(suffix.contains("SKIP"));
            assertThat(warm.getSteps().stream().anyMatch(LimitExecutionStep.class::isInstance))
                .isEqualTo(suffix.contains("LIMIT"));
            assertThat(run(warm)).isEqualTo(run(plan(query, context(session, params))));
          }
        }
      }
    }
    session.rollback();
  }

  /** Two threads open distinct ordered cursors from the same SQL cache entry before either closes. */
  @Test
  public void concurrentWarmCopiesKeepStreamingStateAndClosePrivate() throws Exception {
    seed();
    session.begin();
    for (var i = 0; i < 20; i++) {
      session.execute("CREATE VERTEX CopyTgt SET score = " + (100 + i)
          + ", name = 's" + String.format("%02d", i) + "'").close();
      session.execute("CREATE EDGE CopyLink FROM (SELECT FROM CopySrc WHERE id IN [1, 2])"
          + " TO (SELECT FROM CopyTgt WHERE score = " + (100 + i) + ")").close();
    }
    session.commit();
    session.begin();
    var query = CACHE_BASE.replace(", m.name ASC", "") + " SKIP :skip LIMIT :limit";
    var cache = YqlExecutionPlanCache.instance(session);
    cache.invalidate();
    cachedPlan(query, context(session, bindings(1, 100, "null", 0, 2))).close();
    var firstBindings = bindings(1, 100, "null", 0, -1);
    var secondBindings = bindings(2, 105, "s07", 1, -1);
    var firstExpected = run(plan(query, context(session, firstBindings)));
    var secondExpected = run(plan(query, context(session, secondBindings)));
    var started = new CountDownLatch(2);
    var firstClosed = new CountDownLatch(1);
    var firstPlan = new java.util.concurrent.atomic.AtomicReference<InternalExecutionPlan>();
    var secondPlan = new java.util.concurrent.atomic.AtomicReference<InternalExecutionPlan>();
    // Session opening can itself hit SQL caches. Finish it before measuring the two ordered hits.
    var firstDb = openDatabase();
    var secondDb = openDatabase();
    session.activateOnCurrentThread();
    var hits = cache.getHits();
    var workers = Executors.newFixedThreadPool(2);
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.0);
        var heap = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP, 1)) {
      var first = workers.submit(() -> {
        try (var db = firstDb) {
          db.activateOnCurrentThread();
          db.begin();
          var ctx = context(db, firstBindings);
          var copy = (InternalExecutionPlan) cache.getInternal(query, ctx, db);
          firstPlan.set(copy);
          try {
            var stream = copy.start();
            try {
              assertStreamingScan(copy, ctx);
              var rows = new ArrayList<String>();
              rows.add(stream.next(ctx).getProperty("name"));
              started.countDown();
              assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
              rows.addAll(drain(stream, ctx));
              assertThat(rows).isEqualTo(firstExpected);
            } finally {
              stream.close(ctx);
            }
          } finally {
            copy.close();
            firstClosed.countDown();
            db.rollback();
          }
          return true;
        }
      });
      var second = workers.submit(() -> {
        try (var db = secondDb) {
          db.activateOnCurrentThread();
          db.begin();
          var ctx = context(db, secondBindings);
          var copy = (InternalExecutionPlan) cache.getInternal(query, ctx, db);
          secondPlan.set(copy);
          try {
            var stream = copy.start();
            try {
              assertStreamingScan(copy, ctx);
              var rows = new ArrayList<String>();
              rows.add(stream.next(ctx).getProperty("name"));
              started.countDown();
              assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
              assertThat(firstClosed.await(30, TimeUnit.SECONDS)).isTrue();
              rows.addAll(drain(stream, ctx));
              assertThat(rows).isEqualTo(secondExpected);
            } finally {
              stream.close(ctx);
            }
          } finally {
            copy.close();
            db.rollback();
          }
          return true;
        }
      });
      assertThat(first.get(60, TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(60, TimeUnit.SECONDS)).isTrue();
      assertThat(cache.getHits()).isEqualTo(hits + 2);
      assertThat(firstPlan.get().getContext()).isNotSameAs(secondPlan.get().getContext());
      for (var name : List.of("targetFilter", "comparisonItem", "skipClause", "limitClause")) {
        assertThat(field(ordered(firstPlan.get()), name))
            .isNotSameAs(field(ordered(secondPlan.get()), name));
      }
      session.activateOnCurrentThread();
      var untouched = (InternalExecutionPlan) cache.getInternal(query,
          context(session, firstBindings), session);
      assertThat(ordered(untouched).getChosenRuntimePath()).isNull();
      assertThat(ordered(untouched).lastScanConsumedEntries()).isEqualTo(-1);
      untouched.close();
    } finally {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      session.activateOnCurrentThread();
      session.rollback();
    }
  }

  /** Measure the executable AST and full cache-copy costs against fresh planning without a gate. */
  @Test
  public void measureOrderedCacheCopyCost() throws Exception {
    seed();
    session.begin();
    var query = CACHE_BASE + " SKIP :skip LIMIT :limit";
    var ctx = context(session, inputsFirst());
    var cache = YqlExecutionPlanCache.instance(session);
    cache.invalidate();
    var template = cachedPlan(query, ctx);
    var step = ordered(template);
    var filter = (SQLWhereClause) field(step, "targetFilter");
    var comparison = (com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem) field(step,
        "comparisonItem");
    var skip =
        (com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip) field(step, "skipClause");
    var limit =
        (com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit) field(step, "limitClause");
    var copies = 10_000;
    for (var i = 0; i < 1000; i++) {
      filter.copy();
      comparison.copy();
      skip.copy();
      limit.copy();
    }
    var start = System.nanoTime();
    long checksum = 0;
    for (var i = 0; i < copies; i++) {
      checksum += System.identityHashCode(filter.copy());
      checksum += System.identityHashCode(comparison.copy());
      checksum += System.identityHashCode(skip.copy());
      checksum += System.identityHashCode(limit.copy());
    }
    var astNanos = (System.nanoTime() - start) / copies;
    start = System.nanoTime();
    for (var i = 0; i < 1000; i++) {
      ((InternalExecutionPlan) cache.getInternal(query, ctx, session)).close();
    }
    var hitNanos = (System.nanoTime() - start) / 1000;
    start = System.nanoTime();
    for (var i = 0; i < 100; i++) {
      plan(query, ctx).close();
    }
    var freshNanos = (System.nanoTime() - start) / 100;
    System.out.printf("Ordered copy timing: AST=%d ns, hit=%d ns, fresh=%d ns, checksum=%d%n",
        astNanos, hitNanos, freshNanos, checksum);
    template.close();
    session.rollback();
  }

  /** Only plain literal labels admit the ordered step. Bound/computed labels track current values. */
  @Test
  public void nonLiteralLabelsUseNormalMatchAndFollowAlternatingLabels() {
    seed();
    session.begin();
    for (var label : List.of("'CopyLink'", ":label", "ifnull(:label, 'CopyLink')",
        "'CopyLink'.asString()")) {
      var query = CACHE_BASE.replace("'CopyLink'", label) + " LIMIT :limit";
      assertThat(((SQLMatchStatement) SQLEngine.parse(query, session))
          .executinPlanCanBeCached(session)).isTrue();
      var cache = YqlExecutionPlanCache.instance(session);
      cache.invalidate();
      var cold = true;
      for (var edge : List.of("CopyLink", "OtherLink", "CopyLink")) {
        var params = new java.util.HashMap<>(bindings(1, 0, "none", 0, 4));
        params.put("label", edge);
        var hits = cache.getHits();
        var execution = cachedPlan(query, context(session, params));
        assertThat(cache.getHits()).isEqualTo(hits + (cold ? 0 : 1));
        cold = false;
        assertThat(cache.contains(query)).isTrue();
        assertThat(execution.getSteps().stream().anyMatch(IndexOrderedEdgeStep.class::isInstance))
            .as(label).isEqualTo(label.equals("'CopyLink'"));
        var actual = run(execution);
        assertThat(actual).isEqualTo(run(plan(query, context(session, params))));
        assertThat(actual).isEqualTo(label.equals(":label") || label.startsWith("ifnull")
            ? edge.equals("OtherLink") ? List.of("c") : List.of("null", "a", "b", "c")
            : List.of("null", "a", "b", "c"));
      }
    }
    session.rollback();
  }

  /** Closing one live ordered cursor must not close or advance another session's continuation. */
  @Test
  public void overlappingSessionsKeepFiltersObservationsAndResourcesPrivate() throws Exception {
    overlapStreamingCopies(true);
  }

  /** Interleaved live scans must produce both complete ordered sequences with private filters. */
  @Test
  public void overlappingSessionsProduceBothCompleteOrderedSequences() throws Exception {
    overlapStreamingCopies(false);
  }

  private void overlapStreamingCopies(boolean closeFirstEarly) throws Exception {
    seed();
    session.begin();
    // Both sources exceed the scan threshold. Unique scores allow a single-key streaming sort.
    for (var i = 0; i < 20; i++) {
      var name = "s" + String.format("%02d", i);
      session.execute("CREATE VERTEX CopyTgt SET score = " + (100 + i)
          + ", name = '" + name + "'").close();
      session.execute("CREATE EDGE CopyLink FROM (SELECT FROM CopySrc WHERE id IN [1, 2])"
          + " TO (SELECT FROM CopyTgt WHERE name = '" + name + "')").close();
    }
    session.commit();
    session.begin();
    var query = CACHE_BASE.replace(", m.name ASC", "") + " SKIP :skip LIMIT :limit";
    var firstBindings = bindings(1, 100, "null", 0, -1);
    var secondBindings = bindings(2, 105, "s07", 1, -1);
    var firstExpected = List.of("s00", "s01", "s02", "s03", "s04", "s05", "s06", "s07",
        "s08", "s09", "s10", "s11", "s12", "s13", "s14", "s15", "s16", "s17", "s18", "s19");
    var secondExpected = List.of("s06", "s08", "s09", "s10", "s11", "s12", "s13", "s14",
        "s15", "s16", "s17", "s18", "s19");
    var cache = YqlExecutionPlanCache.instance(session);
    cache.invalidate();
    var template = cachedPlan(query, context(session, bindings(1, 100, "null", 0, 2)));
    assertThat(cache.contains(query)).isTrue();
    // Unbounded scans do not prefill, so unread rows remain in the live index cursor.
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.0);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG, 10);
        var heap = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP, 1);
        var other = openDatabase()) {
      other.begin();
      var firstCtx = context(session, firstBindings);
      var secondCtx = context(other, secondBindings);
      var hits = cache.getHits();
      var first = (InternalExecutionPlan) cache.getInternal(query, firstCtx, session);
      var second = (InternalExecutionPlan) cache.getInternal(query, secondCtx, other);
      assertThat(cache.getHits()).isEqualTo(hits + 2);
      ExecutionStream firstStream = null;
      ExecutionStream secondStream = null;
      try {
        for (var field : List.of("targetFilter", "comparisonItem", "skipClause", "limitClause")) {
          var original = field(ordered(template), field);
          assertThat(field(ordered(first), field)).isNotSameAs(original)
              .isNotSameAs(field(ordered(second), field));
        }
        var originalFilter = (SQLWhereClause) field(ordered(template), "targetFilter");
        var firstFilter = (SQLWhereClause) field(ordered(first), "targetFilter");
        var secondFilter = (SQLWhereClause) field(ordered(second), "targetFilter");
        assertPrivateTree(originalFilter, firstFilter, secondFilter);
        var templateText = originalFilter.toString();
        // Flattening a private nested expression must not populate or alter the template.
        session.activateOnCurrentThread();
        firstFilter.flatten(firstCtx, session.getMetadata().getImmutableSchemaSnapshot()
            .getClassInternal("CopyTgt"));
        assertThat(field(firstFilter, "flattened")).isNotSameAs(field(originalFilter, "flattened"));
        assertThat(originalFilter.toString()).isEqualTo(templateText);
        firstStream = first.start();
        assertStreamingScan(first, firstCtx);
        var firstRows = new ArrayList<String>();
        firstRows.add(firstStream.next(firstCtx).getProperty("name"));
        assertThat(firstRows).containsExactly("s00");
        other.activateOnCurrentThread();
        secondStream = second.start();
        assertStreamingScan(second, secondCtx);
        var secondRows = new ArrayList<String>();
        secondRows.add(secondStream.next(secondCtx).getProperty("name"));
        assertThat(secondRows).containsExactly("s06");
        if (closeFirstEarly) {
          session.activateOnCurrentThread();
          firstStream.close(firstCtx);
          firstStream = null;
          first.close();
          ordered(first).reset();
          assertThat(ordered(first).getChosenRuntimePath()).isNull();
          assertThat(ordered(first).lastScanConsumedEntries()).isEqualTo(-1);
          assertStreamingScan(second, secondCtx);
          other.activateOnCurrentThread();
          secondRows.addAll(drain(secondStream, secondCtx));
          secondStream = null;
        } else {
          // Alternate active sessions for every row while both ordered continuations are open.
          while (true) {
            session.activateOnCurrentThread();
            var firstHasNext = firstStream.hasNext(firstCtx);
            if (firstHasNext) {
              firstRows.add(firstStream.next(firstCtx).getProperty("name"));
            }
            other.activateOnCurrentThread();
            var secondHasNext = secondStream.hasNext(secondCtx);
            if (secondHasNext) {
              secondRows.add(secondStream.next(secondCtx).getProperty("name"));
            }
            if (!firstHasNext && !secondHasNext) {
              break;
            }
          }
          assertThat(firstRows).isEqualTo(firstExpected);
        }
        assertThat(secondRows).isEqualTo(secondExpected);
        // Fresh unbounded plans use ordinary MATCH and sort. Lift the cap only for the reference.
        try (var freshHeap = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP, -1)) {
          other.activateOnCurrentThread();
          assertThat(secondRows).isEqualTo(run(plan(query, context(other, secondBindings))));
          session.activateOnCurrentThread();
          assertThat(run(plan(query, context(session, firstBindings)))).isEqualTo(firstExpected);
        }
        assertThat(ordered(template).getChosenRuntimePath()).isNull();
        assertThat(ordered(template).lastScanConsumedEntries()).isEqualTo(-1);
      } finally {
        session.activateOnCurrentThread();
        try {
          if (firstStream != null) {
            firstStream.close(firstCtx);
          }
        } finally {
          first.close();
          other.activateOnCurrentThread();
          try {
            if (secondStream != null) {
              secondStream.close(secondCtx);
            }
          } finally {
            second.close();
            other.rollback();
          }
        }
      }
    } finally {
      session.activateOnCurrentThread();
      template.close();
      session.rollback();
    }
  }

  private static void assertStreamingScan(InternalExecutionPlan plan, CommandContext ctx) {
    assertThat(ordered(plan).getChosenRuntimePath())
        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN);
    assertThat(ctx.<Boolean>getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED))
        .isEqualTo(Boolean.TRUE);
    // Unbounded scans do no prefill. The one-row heap cap rejects any blocking ORDER BY.
    assertThat(ordered(plan).lastScanBudget()).isPositive();
    assertThat(ordered(plan).lastScanConsumedEntries()).isZero();
  }

  /** Inspect executable AST fields, not parser token/parent links. Names/operators are read-only. */
  private static void assertPrivateTree(Object original, Object first, Object second)
      throws Exception {
    if (original instanceof Iterable<?> values) {
      assertThat(first).isNotSameAs(original).isNotSameAs(second);
      var a = ((Iterable<?>) first).iterator();
      var b = ((Iterable<?>) second).iterator();
      for (var value : values) {
        assertPrivateTree(value, a.next(), b.next());
      }
    } else if (original instanceof SimpleNode && !(original instanceof SQLIdentifier)
        && !(original instanceof SQLBinaryCompareOperator)) {
      assertThat(first).isNotSameAs(original).isNotSameAs(second);
      for (var type = original.getClass(); type != SimpleNode.class; type = type.getSuperclass()) {
        for (var field : type.getDeclaredFields()) {
          if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
            field.setAccessible(true);
            assertPrivateTree(field.get(original), field.get(first), field.get(second));
          }
        }
      }
    }
  }

  private static Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }
}
