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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Query-level equivalence and work bounds for one source with LIMIT. */
@Category(SequentialTest.class)
public class IndexOrderedEqualityAcceptanceTest extends DbTestBase {

  private void seed() {
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
        + " TO (SELECT FROM Message)").close();
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
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
    var inner = sql.substring("SELECT FROM (".length(), sql.lastIndexOf(')'));
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

  /** Underestimated equality exhausts its attempt before an ordered retry succeeds. */
  @Test
  public void incompleteEqualityRetriesOrderedBeforeEmission() {
    seed();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date < 990").close();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(0.02);
      var result = equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN);
      assertThat(result.rids()).hasSize(20);
      assertIncompleteEquality(result.step());
      assertThat(result.step().lastScanConsumedEntries()).isBetween(20L, 40L);
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

  /** A deliberately optimistic ordered price must discard its scan before equality fetch. */
  @Test
  public void exhaustedOrderedScanRetriesEqualityBeforeEmission() {
    seed();
    // One early match makes the abandoned ordered buffer nonempty.
    session.execute("UPDATE Message SET tag = 'rare' WHERE date = 0").close();
    var bias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS;
    try (var ignored = GlobalConfigurationScope.capture(bias)) {
      bias.setValue(0.0001);
      var result =
          equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
      assertThat(result.step().lastScanConsumedEntries()).isBetween(1L, 989L);
      assertThat(result.step().lastOrderedBufferedRows()).isPositive();
      assertThat(result.rids()).hasSize(11);
      assertThat(result.step().lastEqualityObservation().complete()).isTrue();
      result.step().reset();
      assertThat(result.step().lastEqualityObservation()).isNull();
      assertThat(result.step().lastOrderedBufferedRows()).isZero();
    }
  }
}
