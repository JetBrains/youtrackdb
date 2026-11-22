package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.GlobalConfigurationScope;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Query-level equivalence and work bounds for indexed equality traversal. */
@Category(SequentialTest.class)
public class IndexOrderedEqualityAcceptanceTest extends DbTestBase {

  private void seed() {
    seed("");
  }

  private void seed(String targetFilter) {
    session.execute("CREATE CLASS Author EXTENDS V").close();
    session.execute("CREATE PROPERTY Author.name STRING").close();
    session.execute("CREATE INDEX Author_name ON Author (name) UNIQUE").close();
    session.execute("CREATE CLASS Message EXTENDS V").close();
    session.execute("CREATE PROPERTY Message.date INTEGER").close();
    session.execute("CREATE PROPERTY Message.tag STRING").close();
    session.execute("CREATE INDEX Message_date ON Message (date) NOTUNIQUE").close();
    session.execute("CREATE INDEX Message_tag ON Message (tag) NOTUNIQUE").close();
    session.execute("CREATE CLASS wrote EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX Author SET name = 'one'").close();
    for (int i = 0; i < 1000; i++) {
      session.execute("CREATE VERTEX Message SET date = " + i + ", tag = '"
          + (i >= 990 ? "rare" : "common") + "'").close();
    }
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message" + targetFilter + ")").close();
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
  }

  /** Unbounded equality returns every edge, with the same null and RID ordering as plain MATCH. */
  @Test
  public void noLimitEqualityPreservesAllRowsAndOrdering() {
    seed();
    session.execute("UPDATE Message SET date = 7 WHERE date >= 990 AND date < 995").close();
    session.execute("UPDATE Message SET date = null WHERE date >= 995").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE tag = 'rare' AND date = 7)").close();
    session.execute("CREATE CLASS replyOf EXTENDS E").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE date = 7)"
        + " TO (SELECT FROM Author)").close();
    for (var direction : List.of("ASC", "DESC")) {
      for (var nulls : List.of("FIRST", "LAST")) {
        var sql = query(direction).replace(" LIMIT 20", "")
            .replace("m.date " + direction, "m.date " + direction + " NULLS " + nulls);
        var all = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
        assertThat(all.rids()).hasSize(15);
        assertThat(all.step().lastScanConsumedEntries()).isEqualTo(-1);
        var rejected = equivalent(sql.replace(" RETURN", ".out('replyOf'){as:z} RETURN"),
            "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
        assertThat(rejected.rids()).hasSize(10);
        assertThat(rejected.step().lastScanConsumedEntries()).isEqualTo(-1);
      }
    }
  }

  /** A common key uses the ordinary prefiltered LinkBag path, never the ordering index. */
  @Test
  public void noLimitCommonUsesLinkBagAndUnfilteredStillSkips() {
    seed();
    var sql = query("ASC").replace(" LIMIT 20", "");
    // Even an artificially cheap ordered scan must not compete without LIMIT.
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000000001)) {
      var common = equivalent(sql, "common", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertThat(common.rids()).hasSize(990);
      assertThat(common.step().lastScanConsumedEntries()).isEqualTo(-1);
    }
    for (var shape : List.of(sql.replace(", where: (tag = :tag)", ""),
        sql.replace("name = 'one'", "name LIKE '%'"))) {
      try (var rows = session.query(shape, Map.of("tag", "rare"))) {
        assertThat(find(rows.getExecutionPlan().getSteps())).isNull();
      }
    }
  }

  /** Exhaustion and unsupported candidate bags end at a complete unbounded LinkBag load. */
  @Test
  public void noLimitFailedEqualityLoadsAllRowsWithoutOrderingScan() {
    seed();
    try (var rows = session.query("SELECT FROM Message WHERE date = 990")) {
      ((EntityImpl) rows.next().asEntityOrNull())
          .setPropertyInternal("in_wrote", new ArrayList<>());
    }
    var sql = query("ASC").replace(" LIMIT 20", "");
    var unsupported = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
    assertThat(unsupported.rids()).hasSize(10);
    assertIncompleteEquality(unsupported.step());
    assertThat(unsupported.step().lastScanConsumedEntries()).isEqualTo(-1);
    session.execute("UPDATE Message SET tag = 'rare' WHERE date < 990").close();
    var exhausted = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
    assertThat(exhausted.rids()).hasSize(1000);
    assertIncompleteEquality(exhausted.step());
    assertThat(exhausted.step().lastScanConsumedEntries()).isEqualTo(-1);
  }

  /** A refused equality cap retains ordinary class and equality RID filtering before loads. */
  @Test
  public void noLimitLoadRetainsPlainPrefilterAndClassRejection() {
    seed();
    session.execute("CREATE CLASS Other EXTENDS V").close();
    session.execute("CREATE VERTEX Other").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author) TO (SELECT FROM Other)").close();
    try (var cap = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES, 0)) {
      var loaded = equivalent(query("ASC").replace(" LIMIT 20", ""), "rare",
          IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertThat(loaded.rids()).hasSize(10);
      assertThat(loaded.step().lastScanConsumedEntries()).isEqualTo(-1);
      assertThat(loaded.step().executionTraversal().getPreFilterRidSetSize()).isEqualTo(10);
      assertThat(loaded.step().executionTraversal().getPreFilterTotalFiltered()).isPositive();
    }
  }

  /** Many global hits but no linked hits must not trigger global candidate record loads. */
  @Test
  public void noLimitPricesTheAdmittedPrefilterRatherThanEveryLinkedRecord() {
    seed(" WHERE date >= 200");
    session.execute("UPDATE Message SET tag = 'medium' WHERE date < 180").close();
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
    var sql = query("ASC").replace(" LIMIT 20", "");
    var many = equivalent(sql, "medium", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
    assertThat(many.rids()).isEmpty();
    assertThat(many.step().lastEqualityObservation()).isNull();
    assertThat(many.step().executionTraversal().getPreFilterRidSetSize()).isEqualTo(180);
    assertThat(many.step().executionTraversal().getPreFilterTotalFiltered()).isEqualTo(800);
    var rare = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    assertThat(rare.rids()).hasSize(10);
    assertThat(rare.step().lastEqualityObservation().complete()).isTrue();
  }

  /** LIMIT prices selective RID probes instead of loading every source or global candidate. */
  @Test
  public void limitPricesPrefilterForSingleSource() {
    seed(" WHERE date >= 200");
    session.execute("UPDATE Message SET tag = 'medium' WHERE date < 180 OR date >= 998").close();
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
    var result = equivalent(query("ASC").replace("LIMIT 20", "LIMIT 2"), "medium",
        IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
    assertThat(result.rids()).hasSize(2);
    assertThat(result.step().lastEqualityObservation()).isNull();
    assertThat(result.step().lastScanConsumedEntries()).isBetween(1L, 300L);
    var traversal = result.step().executionTraversal();
    assertThat(traversal.getPreFilterRidSetSize()).isEqualTo(182);
    // Every link is probed, but only the two members reach the record loader, as in plain MATCH.
    assertThat(traversal.getPreFilterTotalProbed()
        - traversal.getPreFilterTotalFiltered()).isEqualTo(2);
    equivalent(query("ASC").replace("LIMIT 20", "LIMIT 2"), "rare",
        IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
  }

  /** Filtered several-source LIMIT avoids global record loads in both source-binding modes. */
  @Test
  public void limitPricesPrefilterForSeveralSources() {
    severalSources(" WHERE date >= 200");
    // Keep global selectivity low and the combined source bags smaller than the population.
    for (int i = 1000; i < 4000; i++) {
      session.execute("CREATE VERTEX Message SET date = " + i + ", tag = 'common'").close();
    }
    session.execute("UPDATE Message SET tag = 'medium'"
        + " WHERE date < 180 OR (date >= 998 AND date < 1000)").close();
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 1000000)) {
      for (var bound : List.of(false, true)) {
        var result = equivalent(severalQuery("ASC", bound).replace("LIMIT 40", "LIMIT 4"),
            "medium", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
        assertThat(result.rids()).hasSize(4);
        assertThat(result.step().lastEqualityObservation()).isNull();
        assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(6);
        assertThat(result.step().executionTraversal().getPreFilterRidSetSize()).isEqualTo(182);
      }
    }
  }

  /** Rare unbounded fetch must not build the load's RID set or scan the large source bag. */
  @Test
  public void noLimitRareFetchDoesNotPrepareTheLoadAlternative() {
    seed();
    // Grow only common links, leaving the ten rare candidates and their reverse bags small.
    for (int i = 0; i < 20; i++) {
      session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
          + " TO (SELECT FROM Message WHERE tag = 'common')").close();
    }
    var rare = equivalent(query("ASC").replace(" LIMIT 20", ""), "rare",
        IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    assertThat(rare.rids()).hasSize(10);
    assertThat(rare.step().lastEqualityObservation().entries()).isEqualTo(10);
    assertThat(rare.step().lastScanConsumedEntries()).isEqualTo(-1);
    var traversal = rare.step().executionTraversal();
    assertThat(traversal.getPreFilterRidSetSize()).isZero();
    assertThat(traversal.getPreFilterAppliedCount()).isZero();
    assertThat(traversal.getPreFilterTotalProbed()).isZero();
  }

  /** SKIP alone retains the full global sort before dropping the requested prefix. */
  @Test
  public void noLimitSkipMatchesPlain() {
    seed();
    assertThat(equivalent(query("ASC").replace(" LIMIT 20", " SKIP 3"), "rare",
        IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH).rids()).hasSize(7);
  }

  /** DISTINCT removes parallel-edge duplicates only after the full unbounded traversal. */
  @Test
  public void noLimitDistinctMatchesPlain() {
    seed();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE tag = 'rare')").close();
    var sql = query("DESC").replace("RETURN m.", "RETURN DISTINCT m.").replace(" LIMIT 20", "");
    var actual = run(sql, "rare", true);
    var plain = "SELECT DISTINCT rid FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
        + " RETURN m.@rid AS rid, m.date AS d) ORDER BY d DESC, rid DESC";
    assertThat(actual.rids()).containsExactlyElementsOf(run(plain, "rare", false).rids());
    assertThat(actual.rids()).hasSize(10);
    assertThat(actual.step().getChosenRuntimePath())
        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
  }

  /** A non-RID second key must order primary ties, rather than taking PRE_SORTED pass-through. */
  @Test
  public void noLimitSecondaryPropertyMatchesPlain() {
    seed();
    session.execute("CREATE PROPERTY Message.secondary INTEGER").close();
    session.execute("UPDATE Message SET secondary = date").close();
    session.execute("UPDATE Message SET date = 7 WHERE tag = 'rare'").close();
    var sql = query("ASC").replace(", m.@rid ASC", ", m.secondary DESC, m.@rid ASC")
        .replace(" LIMIT 20", "");
    var actual = run(sql, "rare", true);
    var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
        + " RETURN m.@rid AS rid, m.date AS d, m.secondary AS s)"
        + " ORDER BY d ASC, s DESC, rid ASC";
    assertThat(actual.rids()).containsExactlyElementsOf(run(plain, "rare", false).rids());
    assertThat(actual.rids()).hasSize(10);
    assertThat(actual.step().getChosenRuntimePath())
        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
  }

  /** An admitted parameterized LIMIT template keeps clause semantics for negative and zero copies. */
  @Test
  public void equalityLimitTemplateRebindsNegativeAndZero() {
    seed();
    var sql = query("ASC").replace("LIMIT 20", "LIMIT :n");
    var build = new BasicCommandContext(session);
    build.setInputParameters(Map.of("tag", "rare", "n", 20));
    var template = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(build, false);
    try {
      assertThat(find(template.getSteps())).isNotNull();
      for (int n : List.of(-1, 0)) {
        var ctx = new BasicCommandContext(session);
        ctx.setInputParameters(Map.of("tag", "rare", "n", n));
        var copy = template.copy(ctx);
        var actual = new ArrayList<String>();
        try {
          var stream = copy.start();
          try {
            while (stream.hasNext(ctx)) {
              actual.add(stream.next(ctx).getProperty("rid").toString());
            }
          } finally {
            stream.close(ctx);
          }
          var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
              + " RETURN m.@rid AS rid, m.date AS d) ORDER BY d ASC, rid ASC LIMIT " + n;
          assertThat(actual).containsExactlyElementsOf(run(plain, "rare", false).rids());
          assertThat(actual).hasSize(n < 0 ? 10 : 0);
          assertThat(find(copy.getSteps()).getChosenRuntimePath())
              .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
        } finally {
          copy.close();
        }
      }
    } finally {
      template.close();
    }
  }

  /** Wrong-class links are rejected before record loads even when the RID prefilter is disabled. */
  @Test
  public void multiSourceLoadRejectsOtherClassesBeforeLoading() {
    severalSources();
    session.execute("CREATE CLASS Other EXTENDS V").close();
    session.execute("CREATE VERTEX Other").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author) TO (SELECT FROM Other)").close();
    try (var cap = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES, 1);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE, 100000)) {
      var result = equivalent(severalQuery("ASC", true), "rare",
          IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
      assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(2010);
    }
  }

  /** A frozen root schedule repeats the unique source and must reject wrong-class links early. */
  @Test
  public void noLimitRepeatedPinnedSourceMatchesPlain() {
    seed();
    session.execute("CREATE CLASS Root EXTENDS V").close();
    session.execute("CREATE CLASS members EXTENDS E").close();
    session.execute("CREATE VERTEX Root").close();
    session.execute("CREATE VERTEX Root").close();
    session.execute("CREATE EDGE members FROM (SELECT FROM Root)"
        + " TO (SELECT FROM Author)").close();
    session.execute("CREATE CLASS Other EXTENDS V").close();
    session.execute("CREATE VERTEX Other").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author) TO (SELECT FROM Other)").close();
    var sql = query("ASC").replace("MATCH {class: Author",
        "MATCH {class: Root, as: r}.out('members'){class: Author").replace(" LIMIT 20", "");
    try (var minimum = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE, 100000)) {
      var result = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
      assertThat(result.rids()).hasSize(20);
      assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(1000);
    }
  }

  private String query(String direction) {
    return "MATCH {class: Author, as: a, where: (name = 'one')}"
        + ".out('wrote'){class: Message, as: m, where: (tag = :tag)}"
        + " RETURN m.@rid AS rid ORDER BY m.date " + direction
        + ", m.@rid " + direction + " LIMIT 20";
  }

  private static IndexOrderedEdgeStep find(List<ExecutionStep> steps) {
    for (var step : steps) {
      if (step instanceof IndexOrderedEdgeStep ordered) {
        return ordered;
      }
      var nested = find(step.getSubSteps());
      if (nested != null) {
        return nested;
      }
      if (step instanceof ExecutionStepInternal internal) {
        for (var plan : internal.getSubExecutionPlans()) {
          nested = find(plan.getSteps());
          if (nested != null) {
            return nested;
          }
        }
      }
    }
    return null;
  }

  private record Observed(List<String> rids, IndexOrderedEdgeStep step) {
  }

  private Observed run(String sql, String tag, boolean optimized) {
    try (var rows = session.query(sql, Map.of("tag", tag))) {
      var rids = new ArrayList<String>();
      while (rows.hasNext()) {
        var row = rows.next();
        rids.add(row.hasProperty("rid") ? row.getProperty("rid").toString()
            : row.getEntity("m").getIdentity().toString());
      }
      var plan = rows.getExecutionPlan();
      var step = find(plan.getSteps());
      if (optimized) {
        assertThat(step).as(plan.prettyPrint(0, 2)).isNotNull();
      } else {
        assertPlainMatch(sql, tag);
      }
      return new Observed(rids, step);
    }
  }

  private void assertPlainMatch(String sql, String tag) {
    // SubQueryStep does not expose its plan. Plan the reference's inner MATCH directly.
    var inner = sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')'));
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", tag));
    var plan = ((SQLMatchStatement) SQLEngine.parse(inner, session))
        .createExecutionPlanNoCache(context, false);
    try {
      assertThat(find(plan.getSteps())).as("The reference must use plain MATCH").isNull();
    } finally {
      plan.close();
    }
  }

  /** The reference guard rejects an ordered MATCH even inside an opaque subquery step. */
  @Test
  public void referenceGuardRejectsWrappedOrderedMatch() {
    seed();
    var wrapped = "SELECT FROM (" + query("ASC") + ")";
    assertThatThrownBy(() -> assertPlainMatch(wrapped, "rare"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("The reference must use plain MATCH");
  }

  private Observed equivalent(String sql, String tag, IndexOrderedEdgeStep.RuntimePath path) {
    var actual = run(sql, tag, true);
    // The inner MATCH has no ordering, so it cannot use the ordered traversal step.
    var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
        + " RETURN m.@rid AS rid, m.date AS d) "
        + sql.substring(sql.indexOf("ORDER BY")).replace("m.date", "d").replace("m.@rid", "rid");
    var expected = run(plain, tag, false);
    assertThat(actual.rids()).containsExactlyElementsOf(expected.rids());
    assertThat(actual.step().getChosenRuntimePath()).isEqualTo(path);
    return actual;
  }

  /** An absent key opens no ordering cursor, while a common tag stops at a short prefix. */
  @Test
  public void missAndCommonTitleHaveBoundedOrderingWork() {
    seed();
    var miss = equivalent(query("ASC"), "absent", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    assertThat(miss.rids()).isEmpty();
    assertThat(miss.step().lastScanConsumedEntries()).isEqualTo(-1);
    var common = equivalent(query("ASC"), "common", IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN);
    assertThat(common.rids()).hasSize(20);
    assertThat(common.step().lastScanConsumedEntries()).isBetween(1L, 40L);
    assertThat(session.getIndex("Message_date").size(session)).isEqualTo(1000);
  }

  /** Tied and null keys retain RID tie order and every parallel edge in both directions. */
  @Test
  public void equalityMatchesPlainForTiesNullsAndParallelEdges() {
    seed();
    session.execute("UPDATE Message SET date = 7 WHERE date >= 990 AND date < 995").close();
    session.execute("UPDATE Message SET date = null WHERE date >= 995").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE tag = 'rare' AND date = 7)").close();
    for (var direction : List.of("ASC", "DESC")) {
      for (var nulls : List.of("FIRST", "LAST")) {
        var sql = query(direction).replace("m.date " + direction,
            "m.date " + direction + " NULLS " + nulls);
        assertThat(equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH).rids())
            .hasSize(15);
      }
    }
  }

  /** Later edges expose the local equality sort without a downstream corrective re-sort. */
  @Test
  public void equalityLocalSortPreservesNullsTiesAndParallelEdges() {
    seed();
    session.execute("UPDATE Message SET date = 7 WHERE date >= 990 AND date < 995").close();
    session.execute("UPDATE Message SET date = null WHERE date >= 995").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE tag = 'rare' AND date = 7)").close();
    session.execute("CREATE CLASS replyOf EXTENDS E").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE tag = 'rare')"
        + " TO (SELECT FROM Author)").close();
    for (var direction : List.of("ASC", "DESC")) {
      for (var nulls : List.of("FIRST", "LAST")) {
        var sql = query(direction).replace(" RETURN", ".out('replyOf'){as: z} RETURN")
            .replace("m.date " + direction, "m.date " + direction + " NULLS " + nulls);
        var result = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
        assertThat(result.rids()).hasSize(15);
        assertThat(result.step().lastScanConsumedEntries()).isEqualTo(-1);
        assertLocalPrimaryOrder(sql);
      }
    }
    // Returning only m admits the physical RID tie-break. DESC also requires non-null keys.
    for (var direction : List.of("ASC", "DESC")) {
      var sql = query(direction).replace(" RETURN", ".out(\'replyOf\'){as: z} RETURN")
          .replace("RETURN m.@rid AS rid", "RETURN m")
          .replace("tag = :tag", "tag = :tag AND date IS NOT NULL");
      var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" ORDER BY")) + ") "
          + sql.substring(sql.indexOf("ORDER BY"));
      var actual = run(sql, "rare", true);
      assertThat(actual.rids()).containsExactlyElementsOf(run(plain, "rare", false).rids());
      assertThat(actual.step().getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    }
    var mixed = query("ASC").replace(" RETURN", ".out('replyOf'){as: z} RETURN")
        .replace("m.@rid ASC", "m.@rid DESC");
    assertThat(equivalent(mixed, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH).rids())
        .hasSize(15);
  }

  /** Observe primary order before OrderByStep can repair secondary-key or null ordering. */
  private void assertLocalPrimaryOrder(String sql) {
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "rare"));
    var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(context, false);
    var dates = new ArrayList<Integer>();
    try {
      var step = find(plan.getSteps());
      var stream = step.start(context);
      try {
        while (stream.hasNext(context)) {
          dates.add(stream.next(context).getEntity("m").getProperty("date"));
        }
        assertThat((Object) context.getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED))
            .isEqualTo(Boolean.TRUE);
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
      } finally {
        stream.close(context);
      }
    } finally {
      plan.close();
    }
    var expected = new ArrayList<Integer>();
    var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
        + " RETURN m.date AS d) "
        + sql.substring(sql.indexOf("ORDER BY")).replace("m.date", "d").replace("m.@rid", "d");
    try (var rows = session.query(plain, Map.of("tag", "rare"))) {
      while (rows.hasNext()) {
        expected.add(rows.next().getProperty("d"));
      }
      assertPlainMatch(plain, "rare");
    }
    assertThat(dates).containsExactlyElementsOf(expected);
  }

  /** A target-index hit outside this source has no rows and opens no ordering cursor. */
  @Test
  public void unlinkedEqualityHitIsAnEmptySourceMatch() {
    seed();
    session.execute("CREATE VERTEX Message SET date = -1, tag = 'unlinked'").close();
    var result =
        equivalent(query("ASC"), "unlinked", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    assertThat(result.rids()).isEmpty();
    assertThat(result.step().lastScanConsumedEntries()).isEqualTo(-1);
    assertThat(result.step().lastEqualityObservation().entries()).isEqualTo(1);
  }

  /** A pending update removing an equality match is visible to both query shapes. */
  @Test
  public void pendingUpdateRemovesEqualityMatch() {
    seed();
    session.execute("UPDATE Message SET tag = 'common' WHERE date = 990").close();
    assertThat(equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH)
        .rids()).hasSize(9);
  }

  /** Underestimated equality exhausts the selective load account before an ordered retry. */
  @Test
  public void incompleteEqualityEndsAtPrefilteredLoadBeforeEmission() {
    seed();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date < 990").close();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(0.02);
      var result = equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertThat(result.rids()).hasSize(20);
      assertIncompleteEquality(result.step());
      assertThat(result.step().lastScanConsumedEntries()).isZero();
    }
  }

  /** A later edge rejects the first nine candidates without truncating the equality result. */
  @Test
  public void laterEdgeRejectionKeepsTheLastCandidate() {
    seed();
    session.execute("CREATE CLASS replyOf EXTENDS E").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE date = 999)"
        + " TO (SELECT FROM Author)").close();
    var sql = query("ASC").replace(" RETURN", ".out('replyOf'){as: z} RETURN")
        .replace("LIMIT 20", "LIMIT 1");
    assertThat(equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH).rids())
        .hasSize(1);
  }

  /** A cache hit must resolve fresh parameters, including changes between common and rare paths. */
  @Test
  public void cachedPlanRepricesHitMissAndCommonInBothOrders() {
    seed();
    var sql = query("ASC");
    var cache = YqlExecutionPlanCache.instance(session);
    for (var tags : List.of(List.of("rare", "absent", "common"),
        List.of("absent", "rare", "common"))) {
      cache.invalidate();
      for (int i = 0; i < tags.size(); i++) {
        long hits = cache.getHits();
        var tag = tags.get(i);
        var actual = equivalent(sql, tag, tag.equals("common")
            ? IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN
            : IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
        assertThat(actual.rids()).hasSize(tag.equals("absent") ? 0 : tag.equals("rare") ? 10 : 20);
        assertThat(cache.contains(sql)).isTrue();
        if (i > 0) {
          assertThat(cache.getHits()).isGreaterThan(hits);
        }
      }
    }
  }

  /** Target and edge inserts and deletes remain visible without committing the open transaction. */
  @Test
  public void equalitySeesPendingTargetAndEdgeChanges() {
    seed();
    assertThat(sourceBagSize()).isEqualTo(1000);
    session.execute("DELETE VERTEX Message WHERE date = 990").close();
    // Delete one explicit edge so the source remains large enough to favor equality fetch.
    var edgeRids = new ArrayList<String>();
    try (var edges = session.query("SELECT FROM wrote")) {
      while (edges.hasNext()) {
        var edge = edges.next().asEdgeOrNull();
        if (Integer.valueOf(991).equals(edge.getTo().getProperty("date"))) {
          edgeRids.add(edge.getIdentity().toString());
        }
      }
    }
    assertThat(edgeRids).hasSize(1);
    session.execute("DELETE EDGE " + edgeRids.getFirst()).close();
    assertThat(sourceBagSize()).isEqualTo(998);
    session.execute("CREATE VERTEX Message SET date = -1, tag = 'rare'").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date = -1)").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date = 992)").close();
    assertThat(sourceBagSize()).isEqualTo(1000);
    assertThat(equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH)
        .rids()).hasSize(10);
  }

  /** Several selected authors share candidates, with one extra edge per rare target. */
  private void severalSources() {
    severalSources("");
  }

  private void severalSources(String targetFilter) {
    seed(targetFilter);
    session.execute("CREATE VERTEX Author SET name = 'two'").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author WHERE name = 'two')"
        + " TO (SELECT FROM Message" + targetFilter + ")").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author WHERE name = 'one')"
        + " TO (SELECT FROM Message WHERE tag = 'rare')").close();
  }

  private String severalQuery(String direction, boolean bound) {
    return "MATCH {class: Author, as: a, where: (name IN ['one', 'two'])}"
        + ".out('wrote'){class: Message, as: m, where: (tag = :tag)}"
        + " RETURN m.@rid AS rid" + (bound ? ", a.name AS author" : "")
        + " ORDER BY m.date " + direction + ", m.@rid " + direction + " LIMIT 40";
  }

  /** Shared targets and parallel edges survive a global LIMIT in both binding modes. */
  @Test
  public void severalSourcesFetchCandidatesOnceAndKeepEveryEdge() {
    severalSources();
    for (var bound : List.of(false, true)) {
      for (var direction : List.of("ASC", "DESC")) {
        var result = equivalent(severalQuery(direction, bound), "rare",
            IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
        assertThat(result.rids()).hasSize(30);
        assertThat(result.step().lastEqualityObservation().entries()).isEqualTo(10);
        assertThat(result.step().lastEqualityObservation().complete()).isTrue();
        assertThat(result.step().lastScanConsumedEntries()).isEqualTo(-1);
        if (bound) {
          assertThat(result.step().prettyPrint(0, 2))
              .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
        }
      }
    }
  }

  /** Reusing one parameterized plan reprices rare, absent and common equality keys. */
  @Test
  public void severalSourcesRepriceParametersAndPendingChanges() {
    severalSources();
    var sql = severalQuery("ASC", false);
    equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    assertThat(equivalent(sql, "absent", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH)
        .rids()).isEmpty();
    var common = run(sql, "common", true);
    assertThat(common.rids()).hasSize(40);
    assertThat(common.step().getChosenRuntimePath())
        .isNotEqualTo(IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    session.execute("CREATE VERTEX Message SET date = -1, tag = 'rare'").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date = -1)").close();
    assertThat(equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH)
        .rids()).hasSize(32);
  }

  /** Cache-hit counters prove that both hit/miss orders reprice the same SQL template. */
  @Test
  public void severalSourcesCacheHitsRepriceBothParameterOrders() {
    severalSources();
    session.commit();
    session.begin();
    var cache = YqlExecutionPlanCache.instance(session);
    var sql = severalQuery("ASC", true).replace("name IN ['one', 'two']", "name IS NOT NULL");
    for (var tags : List.of(List.of("rare", "common", "rare"),
        List.of("common", "rare", "common"), List.of("absent", "rare"))) {
      cache.invalidate();
      for (int i = 0; i < tags.size(); i++) {
        long hits = cache.getHits();
        var result = run(sql, tags.get(i), true);
        // Measure the optimized query alone, before the reference can add unrelated cache hits.
        if (i > 0) {
          assertThat(cache.getHits()).isGreaterThan(hits);
        }
        var common = tags.get(i).equals("common");
        assertThat(result.rids()).hasSize(common ? 40 : tags.get(i).equals("rare") ? 30 : 0);
        if (common) {
          assertThat(result.step().lastEqualityObservation()).isNull();
        } else {
          assertThat(result.step().lastEqualityObservation().complete()).isTrue();
          assertThat(result.step().getChosenRuntimePath())
              .isEqualTo(IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
        }
        assertThat(cache.contains(sql)).isTrue();
        equivalent(sql, tags.get(i), result.step().getChosenRuntimePath());
      }
    }
  }

  /** Stale selectivity starts equality, exhausts its account, and loads without a visible prefix. */
  @Test
  public void severalSourcesExhaustEqualityBeforeLoading() {
    severalSources();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date < 990").close();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(0.02);
      var result = equivalent(severalQuery("ASC", true), "rare",
          IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
      assertThat(result.rids()).hasSize(40);
      var observation = result.step().lastEqualityObservation();
      assertThat(observation.entries()).isPositive().isLessThan(1000);
      assertThat(observation.complete()).isFalse();
      assertThat(observation.requiresLinkBagLoad()).isFalse();
      assertThat(result.step().lastScanConsumedEntries()).isEqualTo(-1);
    }
  }

  /** A common key chooses an ordered cursor and stops well before scanning the whole index. */
  @Test
  public void severalSourcesCommonKeyStopsOrderedScanEarly() {
    severalSources();
    // Keep the sampled edge count below the index-size cap, with common keys at the scan head.
    session.execute("DELETE EDGE wrote").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date < 200)").close();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(0.02);
      var sql = severalQuery("ASC", true).replace(", m.@rid ASC", "");
      var result = run(sql, "common", true);
      assertThat(result.rids()).hasSize(40);
      assertThat(result.step().lastEqualityObservation()).isNull();
      assertThat(result.step().getChosenRuntimePath()).isIn(
          IndexOrderedEdgeStep.RuntimePath.UNION_SCAN,
          IndexOrderedEdgeStep.RuntimePath.GLOBAL_SCAN);
      assertThat(result.step().lastScanConsumedEntries()).isBetween(1L, 80L);
      equivalent(sql, "common", result.step().getChosenRuntimePath());
    }
  }

  /** LIMIT belongs after later MATCH rejection, not inside any source's equality result. */
  @Test
  public void severalSourcesLimitDoesNotCutCandidatesBeforeLaterEdge() {
    severalSources();
    session.execute("CREATE CLASS replyOf EXTENDS E").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE date = 995)"
        + " TO (SELECT FROM Message WHERE date = 0)").close();
    var sql = severalQuery("ASC", true).replace(" RETURN",
        ".out('replyOf'){class: Message, as: reply} RETURN").replace("LIMIT 40", "LIMIT 1");
    assertThat(equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH).rids())
        .hasSize(1);
  }

  /** Equality does not admit a filtered multi-source query without LIMIT. */
  @Test
  public void severalSourcesWithoutLimitKeepPlainMatch() {
    severalSources();
    var sql = severalQuery("ASC", false).replace(" LIMIT 40", "");
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "rare"));
    var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(context, false);
    try {
      assertThat(find(plan.getSteps())).isNull();
    } finally {
      plan.close();
    }
  }

  /** The global sort handles null placement, RID order and later rejection without a local cut. */
  @Test
  public void severalSourcesKeepNullTiesAndLaterMatches() {
    severalSources();
    session.execute("UPDATE Message SET date = null WHERE date >= 995").close();
    session.execute("UPDATE Message SET date = 7 WHERE date >= 990").close();
    session.execute("CREATE CLASS replyOf EXTENDS E").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE date IS NULL)"
        + " TO (SELECT FROM Author WHERE name = 'one')").close();
    for (var direction : List.of("ASC", "DESC")) {
      for (var nulls : List.of("FIRST", "LAST")) {
        var sql = severalQuery(direction, true).replace(" RETURN",
            ".out('replyOf'){as: reply} RETURN")
            .replace("m.date " + direction, "m.date " + direction + " NULLS " + nulls)
            .replace(" LIMIT 40", " LIMIT 7");
        assertThat(equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH)
            .rids()).hasSize(7);
      }
    }
  }

  /** Unsupported reverse storage discards every speculative row before the planned load path. */
  @Test
  public void severalSourcesFallbackBeforeEmission() {
    severalSources();
    try (var rows = session.query("SELECT FROM Message WHERE date = 990")) {
      ((EntityImpl) rows.next().asEntityOrNull())
          .setPropertyInternal("in_wrote", new ArrayList<>());
    }
    var result = equivalent(severalQuery("ASC", true), "rare",
        IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
    assertThat(result.rids()).hasSize(30);
    assertThat(result.step().lastEqualityObservation().requiresLinkBagLoad()).isTrue();
    assertThat(result.step().lastEqualityObservation().entries()).isPositive();
    assertThat(result.step().lastEqualityObservation().complete()).isFalse();
    assertThat(result.step().lastScanConsumedEntries()).isEqualTo(-1);
    assertThat(result.step().executionTraversal()).isNotNull();
    assertThat(result.step().executionTraversal().getRootSourceRows()).isGreaterThanOrEqualTo(0);
    assertThat(result.step().executionTraversal().getForecastN()).isGreaterThanOrEqualTo(0);
  }

  /** A target RID IN clause remains a post-fetch filter rather than an equality source. */
  @Test
  public void severalSourcesRidPinFiltersEqualityCandidates() {
    severalSources();
    String rid;
    try (var rows = session.query("SELECT FROM Message WHERE date = 990")) {
      rid = rows.next().getIdentity().toString();
    }
    var sql = severalQuery("ASC", false).replace("tag = :tag",
        "tag = :tag AND @rid IN [" + rid + "]");
    var result = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    assertThat(result.rids()).containsExactly(rid, rid, rid);
    assertThat(result.step().lastEqualityObservation().entries()).isEqualTo(10);
  }

  /** Equality alone opens the filtered gate when ordering scans are deliberately uneconomic. */
  @Test
  public void severalSourcesAdmissionRequiresIndexedEquality() {
    severalSources();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(1000000);
      equivalent(severalQuery("ASC", false), "rare",
          IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
      for (var predicate : List.of("", ", where: (@rid IN [#0:0])",
          ", where: (date >= 990)", ", where: (tag IN ['rare', 'absent'])")) {
        var sql = severalQuery("ASC", false)
            .replace(", where: (tag = :tag)", predicate);
        var context = new BasicCommandContext(session);
        if (predicate.contains("date >=") || predicate.contains("tag IN")) {
          var where =
              ((SQLSelectStatement) SQLEngine
                  .parse("SELECT FROM Message WHERE "
                      + predicate.substring(", where: (".length(), predicate.length() - 1),
                      session))
                  .getWhereClause();
          assertThat(TraversalPreFilterHelper.findIndexForFilter(where, "Message", context))
              .as("Negative must have a usable descriptor").isNotNull();
        }
        var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
            .createExecutionPlanNoCache(context, false);
        try {
          assertThat(find(plan.getSteps())).as("No indexed target equality").isNull();
        } finally {
          plan.close();
        }
      }
    }
  }

  /** A leading composite equality admits the plan and fetches the same rows as plain MATCH. */
  @Test
  public void compositePrefixAdmitsEqualityFetch() {
    severalSources();
    session.commit();
    session.execute("DROP INDEX Message_tag").close();
    session.execute("CREATE INDEX Message_tag_date ON Message (tag, date) NOTUNIQUE").close();
    session.getIndex("Message_tag_date").analyzeHistogram(session);
    session.begin();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(1000000);
      var result = equivalent(severalQuery("ASC", false), "rare",
          IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
      assertThat(result.rids()).hasSize(30);
      assertThat(result.step().lastEqualityObservation().complete()).isTrue();
    }
  }

  /** Source fields with no edges are empty, and a skewed sample cannot size the account. */
  @Test
  public void skewedSourcesUseRealEdgesAndAllowEmptyFields() {
    severalSources();
    session.execute("CREATE VERTEX Author SET name = 'empty'").close();
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX Author SET name = 'small" + i + "'").close();
      session.execute("CREATE EDGE wrote FROM (SELECT FROM Author WHERE name = 'small" + i
          + "') TO (SELECT FROM Message WHERE date = 990)").close();
    }
    var sql = severalQuery("ASC", true).replace("name IN ['one', 'two']", "name IS NOT NULL")
        .replace("LIMIT 40", "LIMIT 100");
    var result = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    assertThat(result.rids()).hasSize(40);
    assertThat(result.step().lastEqualitySourceEdges()).isEqualTo(2020);
    assertThat(result.step().lastEqualityObservation().requiresLinkBagLoad()).isFalse();
  }

  /** Common-enough equality falls back to LinkBags with the ordinary MATCH RID prefilter. */
  @Test
  public void multiSourceLoadUsesPlainMatchPrefilter() {
    severalSources();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date < 500").close();
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    var memory = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES;
    try (var ignored = GlobalConfigurationScope.capture(bias);
        var ignoredMemory = GlobalConfigurationScope.capture(memory)) {
      bias.setValue(1000000);
      memory.setValue(100);
      for (var bound : List.of(true, false)) {
        var result = equivalent(severalQuery("ASC", bound), "medium",
            IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
        assertThat(result.step().lastEqualityObservation()).isNull();
        var traversal = result.step().executionTraversal();
        // Both sources reach each of the 500 members once. The second source reuses the RID set.
        assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(1000);
        assertThat(traversal.getPreFilterRidSetSize()).isEqualTo(500);
        assertThat(traversal.getPreFilterTotalProbed()).isPositive();
        assertThat(traversal.getPreFilterTotalFiltered()).isPositive();
      }
    }
  }

  /** Source-count overflow loads only RID-set members in both binding modes. */
  @Test
  public void multiSourceMaxSourcesLoadsOnlyPrefilterMembers() {
    severalSources();
    try (var cap = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES, 1)) {
      for (var bound : List.of(true, false)) {
        var result = equivalent(severalQuery("ASC", bound), "rare",
            IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
        assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(30);
        assertThat(result.step().executionTraversal().getPreFilterRidSetSize()).isEqualTo(10);
        assertThat(result.step().executionTraversal().getPreFilterAppliedCount()).isEqualTo(2);
      }
    }
  }

  /** Below the ordinary minimum, both source bags are loaded without RID-set admission. */
  @Test
  public void multiSourceSmallBagsFollowPlainMatchMinimum() {
    severalSources();
    try (var cap = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES, 1);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE, 100000)) {
      for (var bound : List.of(true, false)) {
        var result = equivalent(severalQuery("ASC", bound), "rare",
            IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
        assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(2010);
        var traversal = result.step().executionTraversal();
        assertThat(traversal.getPreFilterAppliedCount()).isZero();
        assertThat(traversal.getPreFilterSkippedCount()).isEqualTo(2);
        assertThat(traversal.getLastSkipReason()).isEqualTo(PreFilterSkipReason.LINKBAG_TOO_SMALL);
      }
    }
  }

  /** A scan that exhausts its budget restarts loading with the same RID-set admission. */
  @Test
  public void multiSourceScanBailoutLoadsOnlyPrefilterMembers() {
    severalSources(" WHERE date >= 900");
    assertThat(sourceBagSize()).isBetween(100, 110);
    try (var memory = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES, 0);
        var bias = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000000001);
        var cpu = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR, 100)) {
      var result = equivalent(severalQuery("ASC", true), "rare",
          IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT);
      assertThat(result.step().lastScanBudget()).isPositive();
      assertThat(result.step().lastScanConsumedEntries())
          .isGreaterThanOrEqualTo(result.step().lastScanBudget());
      // The speculative scan is separate. The terminal load reads only the 30 member edges.
      assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(30);
      assertThat(result.step().executionTraversal().getPreFilterRidSetSize()).isEqualTo(10);
    }
  }

  /** Indexed edge predicates cannot open the vertex-only equality admission branch. */
  @Test
  public void edgeTraversalDoesNotUseEqualityAdmission() {
    severalSources();
    session.commit();
    session.execute("CREATE PROPERTY wrote.date INTEGER").close();
    session.execute("CREATE PROPERTY wrote.tag STRING").close();
    session.execute("CREATE INDEX wrote_date ON wrote (date) NOTUNIQUE").close();
    session.execute("CREATE INDEX wrote_tag ON wrote (tag) NOTUNIQUE").close();
    session.begin();
    var sql = severalQuery("ASC", false).replace(".out('wrote'){class: Message",
        ".outE('wrote'){class: wrote");
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(1000000);
      var context = new BasicCommandContext(session);
      context.setInputParameters(Map.of("tag", "rare"));
      var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
          .createExecutionPlanNoCache(context, false);
      try {
        assertThat(find(plan.getSteps())).isNull();
      } finally {
        plan.close();
      }
    }
  }

  /** Repeated upstream source bindings retain their multiplicity through candidate matching. */
  @Test
  public void repeatedSourcesAndEmptyUpstreamMatchPlain() {
    severalSources();
    session.execute("CREATE CLASS Root EXTENDS V").close();
    session.execute("CREATE CLASS members EXTENDS E").close();
    session.execute("CREATE VERTEX Root").close();
    session.execute("CREATE EDGE members FROM (SELECT FROM Root)"
        + " TO (SELECT FROM Author)").close();
    session.execute("CREATE EDGE members FROM (SELECT FROM Root)"
        + " TO (SELECT FROM Author WHERE name = 'one')").close();
    var sql = severalQuery("ASC", true).replace(
        "MATCH {class: Author, as: a, where: (name IN ['one', 'two'])}",
        "MATCH {class: Root, as: r}.out('members'){class: Author, as: a}")
        .replace("LIMIT 40", "LIMIT 100");
    assertThat(equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH).rids())
        .hasSize(50);
    session.execute("DELETE EDGE members").close();
    var empty = run(sql, "rare", true).rids();
    var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
        + " RETURN m.@rid AS rid)";
    assertThat(empty).isEmpty();
    assertThat(empty).isEqualTo(run(plain, "rare", false).rids());
  }

  /** An accepted RID tie-break stays correct when unbound execution chooses equality. */
  @Test
  public void unboundEqualityKeepsAcceptedRidTieBreak() {
    severalSources();
    var sql = severalQuery("ASC", false).replace("RETURN m.@rid AS rid", "RETURN m");
    var result = run(sql, "rare", true);
    assertThat(result.step().getChosenRuntimePath())
        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
    assertThat(result.step().prettyPrint(0, 2))
        .contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" ORDER BY")) + ") "
        + sql.substring(sql.indexOf("ORDER BY"));
    assertThat(result.rids()).containsExactlyElementsOf(run(plain, "rare", false).rids());
  }

  /** Source bindings inside each tied target group retain their exact edge multiplicities. */
  @Test
  public void severalSourcesKeepBindingMultisetsInsideTieGroups() {
    severalSources();
    var sql = severalQuery("ASC", true);
    var actual = new ArrayList<String>();
    try (var rows = session.query(sql, Map.of("tag", "rare"))) {
      while (rows.hasNext()) {
        var row = rows.next();
        actual.add(row.getProperty("rid") + "/" + row.getProperty("author"));
      }
    }
    var expected = new ArrayList<String>();
    try (var rows = session.query("SELECT FROM ("
        + sql.substring(0, sql.indexOf(" RETURN"))
        + " RETURN m.@rid AS rid, a.name AS author)", Map.of("tag", "rare"))) {
      while (rows.hasNext()) {
        var row = rows.next();
        expected.add(row.getProperty("rid") + "/" + row.getProperty("author"));
      }
    }
    // All thirty rows fit the LIMIT. Ordering of indistinguishable target ties is unspecified.
    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(actual).hasSize(30);
  }

  /** Primary-only ordering compares each complete tie group as a binding multiset, not a RID order. */
  @Test
  public void severalSourcesPrimaryOnlyTieMultisetsAndNullPlacement() {
    severalSources();
    session.execute("UPDATE Message SET date = null WHERE date >= 995").close();
    session.execute("UPDATE Message SET date = 7 WHERE date >= 990").close();
    for (var direction : List.of("ASC", "DESC")) {
      for (var nulls : List.of("FIRST", "LAST")) {
        var sql = severalQuery(direction, true).replace(", m.@rid " + direction, "")
            .replace("m.date " + direction, "m.date " + direction + " NULLS " + nulls);
        var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
            + " RETURN m.@rid AS rid, a.name AS author, m.date AS d) "
            + sql.substring(sql.indexOf("ORDER BY")).replace("m.date", "d");
        var expectedDates = new ArrayList<String>();
        var expectedGroups = tieGroups(plain, expectedDates);
        var actualDates = new ArrayList<String>();
        var actualGroups = tieGroups(sql.replace(" AS rid", " AS rid, m.date AS d"), actualDates);
        assertThat(actualDates).containsExactlyElementsOf(expectedDates);
        assertThat(actualGroups).isEqualTo(expectedGroups);
        assertThat(actualDates).hasSize(30);
        assertPlainMatch(plain, "rare");
      }
    }
  }

  private Map<String, List<String>> tieGroups(String sql, List<String> dates) {
    var groups = new java.util.HashMap<String, List<String>>();
    try (var rows = session.query(sql, Map.of("tag", "rare"))) {
      while (rows.hasNext()) {
        var row = rows.next();
        var date = String.valueOf((Object) row.getProperty("d"));
        dates.add(date);
        groups.computeIfAbsent(date, key -> new ArrayList<>())
            .add(row.getProperty("rid") + "/" + row.getProperty("author"));
      }
    }
    groups.values().forEach(group -> group.sort(String::compareTo));
    return groups;
  }

  private int sourceBagSize() {
    try (var rows = session.query("SELECT FROM Author")) {
      var author = (EntityImpl) rows.next().asEntityOrNull();
      return ((LinkBag) author.getPropertyInternal("out_wrote")).size();
    }
  }

  /** An unsupported candidate adjacency representation falls back before emitting any row. */
  @Test
  public void nonLinkBagCandidateFallsBackToSourceLoad() {
    seed();
    try (var rows = session.query("SELECT FROM Message WHERE date = 990")) {
      ((EntityImpl) rows.next().asEntityOrNull())
          .setPropertyInternal("in_wrote", new ArrayList<>());
    }
    var result = equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
    assertThat(result.rids()).hasSize(10);
    assertIncompleteEquality(result.step());
    assertThat(result.step().lastEqualityObservation().requiresLinkBagLoad()).isTrue();
    assertThat(result.step().lastScanConsumedEntries()).isEqualTo(-1);
  }

  /** Stale rare statistics cannot leak a partial equality prefix before the terminal load. */
  @Test
  public void underestimatedEqualityDiscardsItsPrefixBeforeLoad() {
    seed();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date < 990").close();
    var result = equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
    assertThat(result.rids()).hasSize(20);
    assertIncompleteEquality(result.step());
  }

  private void assertIncompleteEquality(IndexOrderedEdgeStep step) {
    assertThat(step.lastEqualityObservation()).isNotNull();
    assertThat(step.lastEqualityObservation().complete()).isFalse();
    assertThat(step.lastEqualityObservation().entries()).isPositive();
  }

  /** An optimistic ordered price discards its buffer before the selective load fallback. */
  @Test
  public void exhaustedOrderedScanEndsAtPrefilteredLoadBeforeEmission() {
    seed();
    // One early match makes the abandoned ordered buffer nonempty.
    session.execute("UPDATE Message SET tag = 'rare' WHERE date = 0").close();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(0.0001);
      var result =
          equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertThat(result.step().lastScanConsumedEntries()).isBetween(1L, 989L);
      assertThat(result.step().lastOrderedBufferedRows()).isPositive();
      assertThat(result.rids()).hasSize(11);
      assertIncompleteEquality(result.step());
      result.step().reset();
      assertThat(result.step().lastEqualityObservation()).isNull();
      assertThat(result.step().lastOrderedBufferedRows()).isZero();
    }
  }
}
