package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.GlobalConfigurationScope;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Query-level equivalence and work bounds for indexed equality traversal. */
@Category(SequentialTest.class)
public class IndexOrderedEqualityAcceptanceTest extends DbTestBase {

  /** Early and late clusters must reach phase 2, not merely return the correct LIMIT prefix. */
  @Test
  public void candidateLoadEarlyAndLateMatchesPlainWithinLoadPrice() {
    seed();
    for (var predicate : List.of("date < 180", "date >= 820")) {
      session.execute("UPDATE Message SET tag = 'common'").close();
      session.execute("UPDATE Message SET tag = 'medium' WHERE " + predicate).close();
      analyzeCandidates();
      for (var direction : List.of("ASC", "DESC")) {
        candidateProbe(query(direction).replace("LIMIT 20", "LIMIT 200"), 180);
      }
    }
  }

  /** A disjoint admitted bitmap returns immediately without loading a target record. */
  @Test
  public void candidateEmptyReturnsWithoutRecordLoads() {
    seed(" WHERE date >= 200");
    session.execute("UPDATE Message SET tag = 'medium' WHERE date < 180").close();
    analyzeCandidates();
    var result = candidateProbe(query("ASC"), 0);
    assertThat(result.rids()).isEmpty();
    assertThat(result.step().lastCandidateLoads()).isZero();
  }

  /** Multiplicity, RID ties, null placement and SKIP survive the reused membership bitmap. */
  @Test
  public void candidateLoadPreservesTiesNullsParallelEdgesAndSkip() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium', date = 7 WHERE date >= 820").close();
    session.execute("UPDATE Message SET date = null WHERE tag = 'medium' LIMIT 20").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE tag = 'medium')").close();
    analyzeCandidates();
    for (var direction : List.of("ASC", "DESC")) {
      for (var nulls : List.of("FIRST", "LAST")) {
        var sql = query(direction).replace("LIMIT 20", "SKIP 3 LIMIT 400")
            .replace("m.date " + direction, "m.date " + direction + " NULLS " + nulls);
        var result = candidateProbe(sql, 180);
        assertThat(result.rids()).hasSize(357);
        assertThat(result.step().executionTraversal().getPreFilterTotalProbed()).isEqualTo(1180);
      }
    }
  }

  /** The superset is not a full predicate result, and later edges must see beyond LIMIT. */
  @Test
  public void candidateLoadRechecksResidualFilterAndKeepsRowsForLaterEdges() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    session.execute("CREATE PROPERTY Message.keep BOOLEAN").close();
    session.execute("UPDATE Message SET keep = true WHERE date >= 900").close();
    session.execute("CREATE CLASS replyOf EXTENDS E").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE date >= 995)"
        + " TO (SELECT FROM Author)").close();
    analyzeCandidates();
    var filtered = query("ASC").replace("tag = :tag", "tag = :tag AND keep = true");
    assertThat(candidateProbe(filtered.replace("LIMIT 20", "LIMIT 200"), 180).rids()).hasSize(100);
    var sql = filtered.replace(" RETURN", ".out('replyOf'){as:z} RETURN")
        .replace("LIMIT 20", "LIMIT 2");
    assertThat(candidateProbe(sql, 180).rids()).hasSize(2);
  }

  /** An early ordering win keeps phase 1 and never resolves or builds the candidate superset. */
  @Test
  public void candidatePhaseDoesNotPreemptAnEarlyOrderingWin() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date < 180").close();
    analyzeCandidates();
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000001)) {
      var result = equivalent(query("ASC").replace("LIMIT 20", "LIMIT 1"), "medium",
          IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN);
      assertThat(result.step().lastScanConsumedEntries()).isEqualTo(1);
      assertThat(result.step().lastCandidateCount()).isEqualTo(-1);
      assertThat(result.step().executionTraversal().getPreFilterAppliedCount()).isZero();
    }
  }

  /** Refused admission is reused at final load, and a reset clears execution-only decisions. */
  @Test
  public void candidateAdmissionRefusalIsNotAccumulatedTwice() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    analyzeCandidates();
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000001);
        var selectivity = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_PREFILTER_INDEX_LOOKUP_MAX_SELECTIVITY, 0.01);
        var cap = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES, 0)) {
      var result =
          equivalent(query("ASC"), "medium", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertThat(result.step().lastCandidateCount()).isEqualTo(-1);
      assertThat(result.step().executionTraversal().getPreFilterSkippedCount()).isEqualTo(1);
      result.step().reset();
      assertThat(result.step().executionTraversal().getPreFilterSkippedCount()).isZero();
      assertThat(result.step().lastCandidateCount()).isEqualTo(-1);
    }
  }

  /** Polymorphic target membership admits subclasses but never unrelated linked records. */
  @Test
  public void candidateLoadIncludesSubclassAndRejectsOtherClasses() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    session.execute("CREATE CLASS Reply EXTENDS Message").close();
    session.execute("CREATE CLASS Other EXTENDS V").close();
    session.execute("CREATE VERTEX Reply SET tag = 'medium', date = 9999").close();
    session.execute("CREATE VERTEX Other").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author) TO (SELECT FROM Reply)").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author) TO (SELECT FROM Other)").close();
    analyzeCandidates();
    assertThat(candidateProbe(query("DESC").replace("LIMIT 20", "LIMIT 200"), 181).rids())
        .hasSize(181);
  }

  /** A cap refusal leaves the final load intact and records admission only once. */
  @Test
  public void candidateRidCapRefusalRetainsPlainLoad() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    analyzeCandidates();
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000001);
        var equalityCap = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES, 0);
        var ridCap = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE, 100)) {
      var result =
          equivalent(query("ASC"), "medium", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      assertThat(result.step().lastCandidateCount()).isEqualTo(-1);
      assertThat(result.step().executionTraversal().getPreFilterSkippedCount()).isEqualTo(1);
    }
  }

  /** A bound target alias rejects the rest of the superset without changing its work limit. */
  @Test
  public void candidateLoadRechecksBoundAlias() throws Exception {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    analyzeCandidates();
    var step = candidateProbe(query("ASC"), 180).step();
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "medium"));
    var upstream = new ResultInternal(session);
    String boundRid;
    try (var rows = session.query("SELECT FROM Message WHERE date = 999")) {
      var bound = rows.next();
      upstream.setProperty("m", bound);
      boundRid = bound.getIdentity().toString();
    }
    var load = IndexOrderedEdgeStep.class.getDeclaredMethod(
        "candidateLoad", LinkBag.class,
        Result.class, CommandContext.class);
    load.setAccessible(true);
    try (var authors = session.query("SELECT FROM Author")) {
      var bag = (LinkBag) ((EntityImpl) authors.next().asEntityOrNull())
          .getPropertyInternal("out_wrote");
      prepareCompletedMembership(step, bag);
      var previousLoads = step.lastCandidateLoads();
      var stream = (ExecutionStream) load.invoke(step, bag, upstream, context);
      // Setup must retain no loaded candidate records when there are no later edges.
      assertThat(step.lastCandidateLoads()).isEqualTo(previousLoads);
      var actual = new ArrayList<String>();
      try {
        while (stream.hasNext(context)) {
          actual.add(stream.next(context).getEntity("m").getIdentity().toString());
        }
      } finally {
        stream.close(context);
      }
      var sql = query("ASC");
      var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
          .replace("tag = :tag", "tag = :tag AND @rid = " + boundRid)
          + " RETURN m.@rid AS rid, m.date AS d) ORDER BY d ASC, rid ASC LIMIT 20";
      assertThat(actual).containsExactlyElementsOf(run(plain, "medium", false).rids());
      assertThat(step.getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.CANDIDATE_LOAD);
      assertThat(step.lastCandidateLoads()).isEqualTo(previousLoads + 180);
      assertCandidatePrice(step, 180, 180, 20);
    }
  }

  /** Warm executions must not share admitted RID sets, refused admission or phase-2 observations. */
  @Test
  public void candidateAdmissionIsPrivateAcrossWarmExecutions() {
    seed(" WHERE date >= 200");
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    analyzeCandidates();
    var sql = query("ASC");
    candidateProbe(sql, 180);
    session.execute("UPDATE Message SET tag = 'common' WHERE tag = 'medium'").close();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date < 180").close();
    analyzeCandidates();
    candidateProbe(sql, 0);
    session.execute("UPDATE Message SET tag = 'common' WHERE tag = 'medium'").close();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    analyzeCandidates();
    var step = candidateProbe(sql, 180).step();
    step.reset();
    assertThat(step.lastCandidateCount()).isEqualTo(-1);
    assertThat(step.lastCandidateLoads()).isZero();
    assertThat(step.lastCandidateLoadPrice()).isZero();
    assertThat(step.executionTraversal().getPreFilterAppliedCount()).isZero();
  }

  private static void assertCandidatePrice(
      IndexOrderedEdgeStep step, long records, long links, long target) {
    double sorted = target > 0 ? Math.min(links, target) : links;
    double sort = sorted <= 1 ? 0 : links * Math.log(sorted) / Math.log(2);
    double expected = records * IndexOrderedCostModel.recordReadEntryUnits()
        + sort * IndexOrderedCostModel.reverseEdgeEntryUnits();
    assertThat(step.lastCandidateLoadPrice())
        .isCloseTo(expected, org.assertj.core.data.Offset.offset(0.000001));
  }

  private static void setStepField(IndexOrderedEdgeStep step, String name, Object value)
      throws Exception {
    var field = IndexOrderedEdgeStep.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(step, value);
  }

  private static void prepareCompletedMembership(IndexOrderedEdgeStep step, LinkBag bag)
      throws Exception {
    var members = new RidSet();
    var duplicates = new HashMap<RID, Integer>();
    for (var pair : bag) {
      var rid = pair.secondaryRid();
      if (!members.add(rid)) {
        duplicates.merge(rid, 2, (count, ignored) -> count + 1);
      }
    }
    setStepField(step, "completedMembers", members);
    setStepField(step, "completedDuplicates", duplicates);
  }

  /** Repeated several-source bindings must resolve each row, including a deferred first build. */
  @Test
  public void repeatedSourceLoadDoesNotCacheDeferral() throws Exception {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    analyzeCandidates();
    var step = candidateProbe(query("ASC"), 180).step();
    step.reset();
    step.executionTraversal().setRootSourceRows(-1);
    step.executionTraversal().setForecastN(1000000);
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "medium"));
    var resolve = IndexOrderedEdgeStep.class.getDeclaredMethod(
        "resolveLoadPrefilter", LinkBag.class, CommandContext.class);
    resolve.setAccessible(true);
    try (var authors = session.query("SELECT FROM Author")) {
      var bag = (LinkBag) ((EntityImpl) authors.next().asEntityOrNull())
          .getPropertyInternal("out_wrote");
      assertThat(resolve.invoke(step, bag, context)).isNull();
      assertThat(resolve.invoke(step, bag, context)).isNull();
      assertThat(step.executionTraversal().getPreFilterSkippedCount()).isEqualTo(2);
      var accumulated = EdgeTraversal.class.getDeclaredField("accumulatedLinkBagTotal");
      accumulated.setAccessible(true);
      assertThat(accumulated.getLong(step.executionTraversal())).isEqualTo(2000);
    }
  }

  /** An admitted set can overflow a lowered cap without double-counting fallback probes. */
  @Test
  public void admittedIntersectionOverflowKeepsOrdinaryLoadMetrics() throws Exception {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    analyzeCandidates();
    var expected = candidateProbe(query("ASC"), 180);
    var step = expected.step();
    step.reset();
    setStepField(step, "singleSourceLoad", true);
    setStepField(step, "rowTarget", 20L);
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "medium"));
    var upstream = new ResultInternal(session);
    var resolve = IndexOrderedEdgeStep.class.getDeclaredMethod(
        "resolveLoadPrefilter", LinkBag.class, CommandContext.class);
    var candidate = IndexOrderedEdgeStep.class.getDeclaredMethod(
        "candidateLoad", LinkBag.class, Result.class, CommandContext.class);
    var terminal = IndexOrderedEdgeStep.class.getDeclaredMethod(
        "terminalLoad", LinkBag.class, Result.class, CommandContext.class);
    resolve.setAccessible(true);
    candidate.setAccessible(true);
    terminal.setAccessible(true);
    try (var authors = session.query("SELECT FROM Author")) {
      var author = authors.next();
      upstream.setProperty("a", author);
      var bag = (LinkBag) ((EntityImpl) author.asEntityOrNull()).getPropertyInternal("out_wrote");
      prepareCompletedMembership(step, bag);
      assertThat(resolve.invoke(step, bag, context)).isNotNull();
      try (var cap = GlobalConfigurationScope.set(
          GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE, 100)) {
        assertThat(candidate.invoke(step, bag, upstream, context)).isNull();
        var stream = (ExecutionStream) terminal.invoke(step, bag, upstream, context);
        var actual = new ArrayList<String>();
        try {
          while (stream.hasNext(context)) {
            actual.add(stream.next(context).getEntity("m").getIdentity().toString());
          }
        } finally {
          stream.close(context);
        }
        // Terminal loading leaves sorting and LIMIT to ORDER BY. Compare its entire population.
        var sql = query("ASC").replace("LIMIT 20", "LIMIT 200");
        var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
            + " RETURN m.@rid AS rid, m.date AS d) ORDER BY d ASC, rid ASC LIMIT 200";
        assertThat(actual).containsExactlyInAnyOrderElementsOf(run(plain, "medium", false).rids());
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
        assertThat(step.executionTraversal().getPreFilterTotalProbed()).isEqualTo(1000);
      }
    }
  }

  private void analyzeCandidates() {
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
  }

  private Observed candidateProbe(String sql, int candidates) {
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000001)) {
      var result = equivalent(sql, "medium", null);
      var step = result.step();
      assertThat(step.lastCandidateCount()).isEqualTo(candidates);
      assertThat(step.lastScanConsumedEntries()).isBetween(1L, step.lastScanBudget());
      assertThat(step.getChosenRuntimePath()).isIn(
          IndexOrderedEdgeStep.RuntimePath.CANDIDATE_EMPTY,
          IndexOrderedEdgeStep.RuntimePath.CANDIDATE_LOAD,
          IndexOrderedEdgeStep.RuntimePath.CANDIDATE_SCAN);
      assertThat(step.lastCandidateLoads()).isEqualTo(
          step.getChosenRuntimePath() == IndexOrderedEdgeStep.RuntimePath.CANDIDATE_SCAN
              ? 0 : candidates);
      // Only remaining distinct loads and sorted link rows contribute to the price.
      long links = sql.contains("SKIP 3") ? 2L * candidates : candidates;
      var limit = java.util.regex.Pattern.compile("LIMIT (\\d+)").matcher(sql);
      assertThat(limit.find()).isTrue();
      long target = Long.parseLong(limit.group(1)) + (sql.contains("SKIP 3") ? 3 : 0);
      assertCandidatePrice(step, candidates, links,
          sql.contains("replyOf") ? -1 : target);
      assertThat(step.executionTraversal().getPreFilterAppliedCount()).isEqualTo(1);
      assertThat(step.executionTraversal().getPreFilterTotalProbed())
          .isBetween((long) candidates, (long) sourceBagSize());
      return result;
    }
  }

  /** Prefix handoff preserves duplicate rows, null placement and SKIP in both directions. */
  @Test
  public void candidateScanPreservesPlainMatchRows() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    session.execute("UPDATE Message SET date = null WHERE date >= 990").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE tag = 'medium')").close();
    analyzeCandidates();
    try (var cpu = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR, 0.01);
        var bias = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000001)) {
      for (var direction : List.of("ASC", "DESC")) {
        for (var nulls : List.of("FIRST", "LAST")) {
          var sql = query(direction).replace("LIMIT 20", "SKIP 3 LIMIT 200")
              .replace("m.date " + direction, "m.date " + direction + " NULLS " + nulls);
          var result = equivalent(sql, "medium", IndexOrderedEdgeStep.RuntimePath.CANDIDATE_SCAN);
          var step = result.step();
          assertCandidatePrice(step, 180, 360, 203);
          var scan = step.lastCandidateScanObservation();
          long entries = direction.equals("ASC") ? 922 : 102;
          assertThat(scan.entries()).isEqualTo(entries);
          assertThat(scan.loads()).isEqualTo(102);
          assertThat(scan.buffered()).isEqualTo(203);
          assertThat(scan.exhausted()).isFalse();
          assertScanWork(step, entries, 102);
        }
      }
    }
  }

  /** EOF before the target returns the complete result and pays its final reservation. */
  @Test
  public void candidateScanCompletesBeforeRowTarget() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE tag = 'medium')").close();
    analyzeCandidates();
    try (var cpu = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR, 0.01);
        var bias = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000001)) {
      var result = equivalent(query("ASC").replace("LIMIT 20", "LIMIT 400"), "medium",
          IndexOrderedEdgeStep.RuntimePath.CANDIDATE_SCAN);
      var scan = result.step().lastCandidateScanObservation();
      assertThat(scan.buffered()).isEqualTo(360);
      assertThat(scan.entries()).isEqualTo(1000);
      assertThat(scan.loads()).isEqualTo(180);
      assertThat(scan.exhausted()).isFalse();
      assertScanWork(result.step(), 1001, 180);
    }
  }

  /** Late members make load cheaper, and later edges exclude even an artificially cheap scan. */
  @Test
  public void candidateLoadPriceAndLaterEdgesExcludeScan() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 820").close();
    analyzeCandidates();
    var load = candidateProbe(query("ASC").replace("LIMIT 20", "LIMIT 200"), 180).step();
    assertThat(load.lastCandidateScanObservation().work()).isZero();
    session.execute("CREATE CLASS replyOf EXTENDS E").close();
    session.execute("CREATE EDGE replyOf FROM (SELECT FROM Message WHERE date >= 995)"
        + " TO (SELECT FROM Author)").close();
    try (var cpu = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR, 0.01)) {
      var later = candidateProbe(query("ASC")
          .replace(" RETURN", ".out('replyOf'){as:z} RETURN"), 180).step();
      assertThat(later.lastCandidateScanObservation().work()).isZero();
    }
  }

  /** An underestimated order index exhausts phase 2 after buffering real rows, then loads all. */
  @Test
  public void candidateScanDiscardsPrefixBeforeFinalLoad() throws Exception {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date = 0 OR date >= 821").close();
    analyzeCandidates();
    session.execute("CREATE PROPERTY Message.keep BOOLEAN").close();
    session.execute("UPDATE Message SET keep = true WHERE date = 0 OR date >= 995").close();
    var sql = query("ASC").replace("LIMIT 20", "LIMIT 100")
        .replace("tag = :tag", "tag = :tag AND keep = true");
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "medium"));
    var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(context, false);
    var step = find(plan.getSteps());
    var real = session.getIndex("Message_date");
    var stale = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.core.index.Index.class,
        org.mockito.AdditionalAnswers.delegatesTo(real));
    org.mockito.Mockito.doReturn(1L).when(stale).estimateEntryCount(session);
    setStepField(step, "index", stale);
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000001)) {
      var stream = plan.start();
      var actual = new ArrayList<String>();
      try {
        while (stream.hasNext(context)) {
          actual.add(stream.next(context).getProperty("rid").toString());
        }
      } finally {
        stream.close(context);
        plan.close();
      }
      var plain = "SELECT FROM (" + sql.substring(0, sql.indexOf(" RETURN"))
          + " RETURN m.@rid AS rid, m.date AS d) ORDER BY d ASC, rid ASC LIMIT 100";
      assertThat(actual).containsExactlyElementsOf(run(plain, "medium", false).rids());
      assertThat(step.getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.CANDIDATE_LOAD);
      assertCandidatePrice(step, 180, 180, 100);
      var scan = step.lastCandidateScanObservation();
      assertThat(scan.exhausted()).isTrue();
      assertThat(scan.buffered()).isPositive().isLessThan(180);
      // One early member is buffered. The rest start at entry 822 and fail the residual.
      double read = IndexOrderedCostModel.recordReadEntryUnits();
      double seek = IndexOrderedCostModel.indexSeekEntryUnits();
      double limit = 180 * read + 180 * Math.log(100) / Math.log(2)
          * IndexOrderedCostModel.reverseEdgeEntryUnits();
      long paidLoads = (long) Math.floor((limit - seek - 821) / (read + 1));
      assertThat(scan.buffered()).isEqualTo(1);
      assertThat(scan.loads()).isEqualTo(paidLoads);
      assertThat(scan.entries()).isEqualTo(821 + paidLoads);
      double charged = IndexOrderedCostModel.indexSeekEntryUnits() + scan.entries()
          + scan.loads() * IndexOrderedCostModel.recordReadEntryUnits();
      assertThat(scan.work()).isCloseTo(charged,
          org.assertj.core.data.Offset.offset(1.000001));
      double bound = 180 * IndexOrderedCostModel.recordReadEntryUnits()
          + (180 * Math.log(100) / Math.log(2))
              * IndexOrderedCostModel.reverseEdgeEntryUnits();
      assertThat(scan.work()).isLessThanOrEqualTo(bound);
      assertThat(step.lastCandidateLoads()).isEqualTo(180);
    }
  }

  private static void assertScanWork(IndexOrderedEdgeStep step, long entries, long records) {
    double expected = IndexOrderedCostModel.indexSeekEntryUnits() + entries
        + records * IndexOrderedCostModel.recordReadEntryUnits();
    assertThat(step.lastCandidateScanObservation().work())
        .isCloseTo(expected, org.assertj.core.data.Offset.offset(0.000001));
    assertThat(expected).isLessThanOrEqualTo(step.lastCandidateLoadPrice());
  }

  /** Default costs choose phase 2 and stop after one early candidate, not the whole index. */
  @Test
  public void candidateScanStopsAtSmallLimitWithDefaultCosts() {
    seed();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 200 AND date < 380")
        .close();
    analyzeCandidates();
    var result = equivalent(query("ASC").replace("LIMIT 20", "LIMIT 1"), "medium",
        IndexOrderedEdgeStep.RuntimePath.CANDIDATE_SCAN);
    var scan = result.step().lastCandidateScanObservation();
    assertThat(scan.entries()).isEqualTo(201);
    assertThat(scan.loads()).isEqualTo(1);
    assertThat(scan.buffered()).isEqualTo(1);
    assertScanWork(result.step(), 201, 1);
    assertCandidatePrice(result.step(), 180, 180, 1);
  }

  /** Rejected superset members do not fill the row target. Ties need the cursor after handoff. */
  @Test
  public void candidateScanRechecksResidualAndContinuesForRidTies() {
    seed();
    session.execute("CREATE PROPERTY Message.keep BOOLEAN").close();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 200 AND date < 380")
        .close();
    session.execute("UPDATE Message SET keep = true WHERE date >= 210").close();
    session.execute("UPDATE Message SET date = 200 WHERE tag = 'medium'").close();
    session.execute("UPDATE Message SET date = 199 WHERE tag = 'medium' AND keep IS NULL")
        .close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE tag = 'medium')").close();
    analyzeCandidates();
    var sql = query("ASC").replace("tag = :tag", "tag = :tag AND keep = true")
        .replace(", m.@rid ASC", ", m.@rid DESC")
        .replace("LIMIT 20", "SKIP 3 LIMIT 2");
    var result = equivalent(sql, "medium", IndexOrderedEdgeStep.RuntimePath.CANDIDATE_SCAN);
    assertThat(result.rids()).hasSize(2);
    var scan = result.step().lastCandidateScanObservation();
    assertThat(scan.buffered()).isEqualTo(5);
    assertThat(scan.loads()).isEqualTo(13);
    assertThat(scan.entries()).isEqualTo(213);
    assertScanWork(result.step(), 213, 13);

  }

  /**
   * Opposite RID ordering needs the tied rows after handoff. The highest-RID candidate
   * fails the residual there, so skipping the continuation recheck changes the LIMIT result.
   */
  @Test
  public void candidateScanRechecksResidualAfterHandoffForRidTies() {
    seed();
    session.execute("CREATE PROPERTY Message.keep BOOLEAN").close();
    session.execute("UPDATE Message SET tag = 'medium' WHERE date >= 200 AND date < 380")
        .close();
    session.execute("UPDATE Message SET keep = true WHERE tag = 'medium'").close();
    // Records span collections, so insertion order does not determine the greatest RID.
    try (var rows = session.query(
        "SELECT FROM Message WHERE tag = 'medium' ORDER BY @rid DESC LIMIT 1")) {
      var rejected = rows.next().getIdentity();
      session.execute("UPDATE Message SET keep = false WHERE @rid = " + rejected).close();
    }
    session.execute("UPDATE Message SET date = 200 WHERE tag = 'medium'").close();
    analyzeCandidates();
    var sql = query("ASC").replace("tag = :tag", "tag = :tag AND keep = true")
        .replace(", m.@rid ASC", ", m.@rid DESC")
        .replace("LIMIT 20", "LIMIT 1");
    var result = equivalent(sql, "medium", IndexOrderedEdgeStep.RuntimePath.CANDIDATE_SCAN);
    assertThat(result.rids()).hasSize(1);
    var scan = result.step().lastCandidateScanObservation();
    // Entry 201 fills the prefix. The remaining 179 tied candidates include one rejection.
    assertThat(scan.buffered()).isEqualTo(1);
    assertThat(scan.loads()).isEqualTo(1);
    assertThat(scan.entries()).isEqualTo(201);
    assertScanWork(result.step(), 201, 1);
    assertCandidatePrice(result.step(), 180, 180, 1);
  }

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
        IndexOrderedEdgeStep.RuntimePath.CANDIDATE_LOAD);
    assertThat(result.rids()).hasSize(2);
    assertCandidatePrice(result.step(), 2, 2, 2);
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

  /**
   * {@code authorCount} authors, two of kind x. The unindexed kind filter plans half the
   * authors as roots. With 100 authors that is 50 roots and a forecast of 450 links, so plain
   * MATCH builds eagerly (50 >= 30 and 450 > m = 62) although only two sources materialize.
   * Their 710 + 190 links stay below the CLT-fail trigger 15m = 922. 1,000 messages: dates
   * below 860 are medium, so the RID set holds 860 members.
   */
  private void seedPlanRootGate(int authorCount) {
    seed(" WHERE date < 0");
    session.execute("UPDATE Message SET tag = 'medium' WHERE date < 860").close();
    session.execute("UPDATE Message SET tag = 'common' WHERE date >= 860").close();
    var messages = new ArrayList<Vertex>();
    try (var rows = session.query("SELECT FROM Message ORDER BY date")) {
      while (rows.hasNext()) {
        messages.add(rows.next().asVertexOrNull());
      }
    }
    var authors = new ArrayList<Vertex>();
    try (var rows = session.query("SELECT FROM Author")) {
      authors.add(rows.next().asVertexOrNull());
    }
    authors.get(0).setProperty("kind", "x");
    for (int i = 1; i < authorCount; i++) {
      var author = session.newVertex("Author");
      author.setProperty("name", "a" + i);
      author.setProperty("kind", i == 1 ? "x" : "y");
      authors.add(author);
    }
    // Ten medium links plus each of the 140 commons five times: 710 links.
    for (int i = 0; i < 10; i++) {
      authors.get(0).addEdge(messages.get(i), "wrote");
    }
    for (int round = 0; round < 5; round++) {
      for (int i = 860; i < 1000; i++) {
        authors.get(0).addEdge(messages.get(i), "wrote");
      }
    }
    // Each common once plus fifty commons again: 190 links.
    for (int i = 860; i < 1000; i++) {
      authors.get(1).addEdge(messages.get(i), "wrote");
    }
    for (int i = 860; i < 910; i++) {
      authors.get(1).addEdge(messages.get(i), "wrote");
    }
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
  }

  private String planRootGateQuery(boolean bound) {
    return severalQuery("ASC", bound).replace("name IN ['one', 'two']", "kind = 'x'")
        .replace("LIMIT 40", "LIMIT 10");
  }

  /**
   * Plan roots pass the CLT gate while fewer than 30 sources materialize. Plain MATCH builds
   * the RID set eagerly, so the step must price the prefiltered load and use it.
   */
  @Test
  public void limitPricesPrefilterWhenPlanRootsPassTheCltGate() {
    seedPlanRootGate(100);
    // Remove ordered alternatives so equality fetch competes only with the LinkBag load.
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 1000000)) {
      for (var bound : List.of(false, true)) {
        var result = equivalent(planRootGateQuery(bound), "medium",
            IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
        assertThat(result.rids()).hasSize(10);
        assertThat(result.step().lastEqualitySourceEdges()).isEqualTo(900);
        assertThat(result.step().lastEqualityObservation()).isNull();
        var traversal = result.step().executionTraversal();
        assertThat(traversal.getRootSourceRows()).isEqualTo(50);
        assertThat(traversal.getForecastN()).isEqualTo(450);
        assertThat(traversal.getPreFilterRidSetSize()).isEqualTo(860);
        assertThat(traversal.getPreFilterAppliedCount()).isEqualTo(2);
        assertThat(traversal.getPreFilterTotalProbed()).isEqualTo(900);
        assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(10);
      }
    }
  }

  /**
   * Negative control with the same sources and links: 50 authors plan 25 roots, below the CLT
   * gate. Plain MATCH defers and its 900 accumulated links never reach 15m = 922, so no RID
   * set is built and equality fetch remains the cheaper choice.
   */
  @Test
  public void limitKeepsEqualityWhenPlanRootsFailTheCltGate() {
    seedPlanRootGate(50);
    try (var bias = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 1000000)) {
      for (var bound : List.of(false, true)) {
        var result = equivalent(planRootGateQuery(bound), "medium",
            IndexOrderedEdgeStep.RuntimePath.EQUALITY_FETCH);
        assertThat(result.rids()).hasSize(10);
        assertThat(result.step().lastEqualityObservation().complete()).isTrue();
        assertThat(result.step().lastEqualityObservation().entries()).isEqualTo(860);
        var traversal = result.step().executionTraversal();
        assertThat(traversal.getRootSourceRows()).isEqualTo(25);
        assertThat(traversal.getForecastN()).isEqualTo(450);
        assertThat(traversal.getPreFilterRidSetSize()).isZero();
        assertThat(traversal.getPreFilterAppliedCount()).isZero();
      }
    }
  }

  /**
   * A large root admits one cached prefilter even when the next 100-link bag misses its own
   * build trigger. Plan roots 1 fail the CLT gate, so the trigger is 15m = ceil(15 * 900 /
   * (100 * (1 - 900 / 1900))) = 257 accumulated links. The first bag alone crosses it.
   */
  @Test
  public void limitPricesPrefilterForMixedHundredLinkBag() {
    assertMixedBagPrefilter(100);
  }

  /** A ten-link root cannot force global equality loads after a large root admits the build. */
  @Test
  public void limitPricesPrefilterForMixedTenLinkBag() {
    assertMixedBagPrefilter(10);
  }

  /**
   * 1,900 messages, 900 tagged medium. Author one links the ten mediums dated 0..9 and 990
   * commons. Author two links {@code secondBagSize} of the same commons. Ten rows match.
   */
  private void assertMixedBagPrefilter(int secondBagSize) {
    seed(" WHERE date < 0");
    try (var authors = session.query("SELECT FROM Author")) {
      var one = authors.next().asVertexOrNull();
      var two = session.newVertex("Author");
      two.setProperty("name", "two");
      try (var messages = session.query("SELECT FROM Message")) {
        while (messages.hasNext()) {
          var message = messages.next().asVertexOrNull();
          int date = message.getProperty("date");
          message.setProperty("tag", date < 900 ? "medium" : "common");
          if (date < 10 || date >= 900) {
            one.addEdge(message, "wrote");
          }
          if (date >= 900 && date < 900 + secondBagSize) {
            two.addEdge(message, "wrote");
          }
        }
      }
      for (int i = 1000; i < 1900; i++) {
        var message = session.newVertex("Message");
        message.setProperty("date", i);
        message.setProperty("tag", "common");
        if (i < 1890) {
          one.addEdge(message, "wrote");
        }
      }
    }
    session.commit();
    session.getIndex("Message_tag").analyzeHistogram(session);
    session.begin();
    for (var bound : List.of(false, true)) {
      var result = equivalent(severalQuery("ASC", bound).replace("LIMIT 40", "LIMIT 500"),
          "medium", IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
      assertThat(result.rids()).hasSize(10);
      assertThat(result.step().lastEqualitySourceEdges()).isEqualTo(1000 + secondBagSize);
      assertThat(result.step().lastEqualityObservation()).isNull();
      var traversal = result.step().executionTraversal();
      assertThat(traversal.getRootSourceRows()).isLessThan(EdgeTraversal.MIN_FOR_CLT);
      assertThat(traversal.getPreFilterRidSetSize()).isEqualTo(900);
      // The ten-link bag is below the ordinary minimum and loads its ten nonmembers.
      assertThat(traversal.getPreFilterAppliedCount()).isEqualTo(secondBagSize == 10 ? 1 : 2);
      assertThat(result.step().lastMultiSourceLoadCalls())
          .isEqualTo(secondBagSize == 10 ? 20 : 10);
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
    if (path != null) {
      assertThat(actual.step().getChosenRuntimePath()).isEqualTo(path);
    }
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

  /** Without load prefiltering, an incomplete equality attempt retries ordering before emission. */
  @Test
  public void incompleteEqualityRetriesOrderedBeforeEmission() {
    seed();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date < 990").close();
    try (var minimum = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE, 100000);
        var bias = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.02)) {
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

  /** A global scan charges each loaded record, not just its cheap cursor advance. */
  @Test
  public void multiSourceGlobalScanConvertsPrefilterBudgetToRecordReads() {
    severalSources(" WHERE date >= 700");
    session.execute("CREATE CLASS Root EXTENDS V").close();
    session.execute("CREATE PROPERTY Root.name STRING").close();
    session.execute("CREATE INDEX Root_name ON Root (name) UNIQUE").close();
    session.execute("CREATE CLASS members EXTENDS E").close();
    session.execute("CREATE VERTEX Root SET name = 'root'").close();
    session.execute("CREATE EDGE members FROM (SELECT FROM Root)"
        + " TO (SELECT FROM Author)").close();
    var sql = severalQuery("ASC", true).replace("LIMIT 40", "LIMIT 1")
        .replace("MATCH {class: Author",
            "MATCH {class: Root, as: r, where: (name = 'root')}.out('members'){class: Author");
    try (var cpu = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR, 1000);
        var memory = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES, 0);
        var bias = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.000000001)) {
      var result = equivalent(sql, "rare", IndexOrderedEdgeStep.RuntimePath.SCAN_BUDGET_BAILOUT);
      // Scan CPU factor 1000: one entry costs 1/200 + 1000 * 0.01 = 10.005. A record read is
      // 4.01 / 10.005 = 0.4008 entries, a link probe 0.01 / 10.005, a seek 16 / 10.005.
      // Prefiltered load for 610 links, 10 matches, population 1000, LIMIT 1: linked matches
      // min(10, 610, 6.1) = 6.1, so 1.5992 + 10 + 2 * 610 * 0.0009995 + 6.1 * 0.4008 = 15.26.
      // The stored budget truncates to 15 entries. One global-scan entry costs a cursor
      // advance plus a record read, 1.4008 entries, so the cap is (long) (15 / 1.4008) = 10.
      long expectedBudget = 10;
      assertThat(result.step().lastScanBudget()).as(result.step().prettyPrint(0, 2))
          .isEqualTo(expectedBudget);
      assertThat(result.step().lastScanConsumedEntries()).isBetween(expectedBudget,
          expectedBudget + 1);
      assertThat(result.step().lastMultiSourceLoadCalls()).isEqualTo(30);
      assertThat(result.rids()).hasSize(1);
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

  /** An exhausted ordered buffer is discarded before a complete equality retry emits any row. */
  @Test
  public void exhaustedOrderedScanRetriesEqualityBeforeEmission() {
    seed();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date = 0").close();
    try (var minimum = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE, 100000);
        var bias = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS, 0.0001)) {
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
          equivalent(query("ASC"), "rare", IndexOrderedEdgeStep.RuntimePath.CANDIDATE_LOAD);
      assertThat(result.step().lastCandidateCount()).isEqualTo(11);
      assertCandidatePrice(result.step(), 11, 11, 20);
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
