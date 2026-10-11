package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Test;

/** Single-source assumptions must not discard bindings or suppress the global MATCH sort. */
public class IndexOrderedSingleSourceCheckTest extends DbTestBase {

  private static final String UNIQUE_QUERY =
      "MATCH {class:Forum,as:f,where:(title=:t)}"
          + ".out('HAS_MEMBER'){class:Person,as:p,where:(id=:pid)}"
          + ".out('LIKES'){class:Message,as:m}"
          + " RETURN $patterns ORDER BY m.creationDate LIMIT 10";

  private void seed(boolean forums) {
    session.execute("CREATE CLASS Person EXTENDS V").close();
    session.execute("CREATE PROPERTY Person.id INTEGER").close();
    session.execute("CREATE INDEX Person_id ON Person (id) UNIQUE").close();
    session.execute("CREATE CLASS Message EXTENDS V").close();
    session.execute("CREATE PROPERTY Message.creationDate INTEGER").close();
    session.execute("CREATE INDEX Message_date ON Message (creationDate) NOTUNIQUE").close();
    session.execute("CREATE CLASS LIKES EXTENDS E").close();
    session.execute("CREATE CLASS Reply EXTENDS V").close();
    session.execute("CREATE CLASS NEXT EXTENDS E").close();
    if (forums) {
      session.execute("CREATE CLASS Forum EXTENDS V").close();
      session.execute("CREATE PROPERTY Forum.title STRING").close();
      session.execute("CREATE INDEX Forum_title ON Forum (title) NOTUNIQUE").close();
      session.execute("CREATE CLASS HAS_MEMBER EXTENDS E").close();
    }
    session.begin();
    for (var i = 0; i < (forums ? 150 : 2); i++) {
      session.execute("CREATE VERTEX Person SET id = " + i + ", altId = " + i).close();
    }
    for (var i = 0; i < 250; i++) {
      session.execute("CREATE VERTEX Message SET creationDate = " + i).close();
    }
    for (var i = 0; i < 12; i++) {
      session.execute("CREATE EDGE LIKES FROM (SELECT FROM Person WHERE id = " + (i % 2)
          + ") TO (SELECT FROM Message WHERE creationDate = " + i + ")").close();
      session.execute("CREATE VERTEX Reply SET id = " + i).close();
      session.execute("CREATE EDGE NEXT FROM (SELECT FROM Message WHERE creationDate = " + i
          + ") TO (SELECT FROM Reply WHERE id = " + i + ")").close();
    }
    if (forums) {
      // Title statistics distinguish rare=1 from common=100. Person's count/2 estimate is 75.
      // Only three common forums reach Person 0. The other common forums affect estimates only.
      for (var i = 0; i < 5000; i++) {
        var title = i == 0 ? "rare" : i <= 100 ? "common" : "noise";
        session.execute("CREATE VERTEX Forum SET fid = " + i + ", title = '" + title + "'")
            .close();
      }
      session.execute("CREATE EDGE HAS_MEMBER FROM (SELECT FROM Forum WHERE fid <= 3)"
          + " TO (SELECT FROM Person WHERE id = 0)").close();
    }
    session.commit();
    if (forums) {
      session.execute("ANALYZE INDEX Forum_title").close();
    }
    session.begin();
  }

  private BasicCommandContext context(Map<Object, Object> bindings) {
    var ctx = new BasicCommandContext(session);
    ctx.setInputParameters(bindings);
    return ctx;
  }

  private InternalExecutionPlan plan(String query, CommandContext ctx) {
    return ((SQLMatchStatement) SQLEngine.parse(query, session))
        .createExecutionPlanNoCache(ctx, false);
  }

  private static IndexOrderedEdgeStep ordered(InternalExecutionPlan plan) {
    return plan.getSteps().stream().filter(IndexOrderedEdgeStep.class::isInstance)
        .map(IndexOrderedEdgeStep.class::cast).findFirst()
        .orElseThrow(() -> new AssertionError(plan.prettyPrint(0, 2)));
  }

  private static String root(InternalExecutionPlan plan) {
    return plan.getSteps().stream().filter(MatchFirstStep.class::isInstance)
        .map(MatchFirstStep.class::cast).findFirst().orElseThrow()
        .prettyPrint(0, 2).split("\n")[1].trim();
  }

  private List<Integer> dates(InternalExecutionPlan plan) {
    try {
      var stream = plan.start();
      try {
        return stream.stream(plan.getContext()).map(row -> row.<Integer>getProperty("d"))
            .toList();
      } finally {
        stream.close(plan.getContext());
      }
    } finally {
      plan.close();
    }
  }

  private List<String> forumBindings(InternalExecutionPlan plan) {
    try {
      var stream = plan.start();
      try {
        return stream.stream(plan.getContext())
            .map(row -> row.getVertex("m").getProperty("creationDate") + ":"
                + row.getVertex("p").getProperty("id") + ":"
                + row.getVertex("f").getProperty("fid"))
            .toList();
      } finally {
        stream.close(plan.getContext());
      }
    } finally {
      plan.close();
    }
  }

  private static void assertSliceBindings(List<String> actual, List<String> expected,
      long skip, long limit) {
    var sliced = expected.stream()
        .sorted(Comparator.comparingInt(value -> Integer.parseInt(value.split(":")[0])))
        .skip(skip).limit(limit).toList();
    assertThat(actual.stream().map(value -> value.split(":")[0]).toList())
        .containsExactlyElementsOf(sliced.stream().map(value -> value.split(":")[0]).toList());
    var expectedCounts = expected.stream()
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    var actualCounts = actual.stream()
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    // Exact dates fix each tie group's size. Occurrence bounds then force the full multiset
    // for every uncut group. Only a cut boundary group may omit any of its tied bindings.
    actualCounts.forEach((row, count) -> assertThat(count).as("occurrences of %s", row)
        .isLessThanOrEqualTo(expectedCounts.getOrDefault(row, 0L)));
  }

  /** A rare Forum-root private template preserves three repeated Person bindings on common input. */
  @Test
  public void uniqueSourceCopiesPreserveMultiplicityAcrossRootSchedules() {
    seed(true);
    var rare = Map.<Object, Object>of("t", "rare", "pid", 0, "label", "LIKES");
    var common = Map.<Object, Object>of("t", "common", "pid", 0, "label", "LIKES");
    var template = plan(UNIQUE_QUERY, context(rare));
    try {
      assertThat(root(template)).isEqualTo("f");
      assertThat(ordered(template).prettyPrint(0, 2)).doesNotContain("(FILTERED");
      assertThat(template.canBeCached()).isTrue();
      var forumClass = session.getMetadata().getImmutableSchemaSnapshot().getClassInternal("Forum");
      var personClass = session.getMetadata().getImmutableSchemaSnapshot()
          .getClassInternal("Person");
      var statement = (SQLMatchStatement) SQLEngine.parse(UNIQUE_QUERY, session);
      var forumFilter = statement.getMatchExpressions().getFirst().getOrigin().getFilter();
      assertThat(forumFilter.estimate(forumClass, 100, context(rare))).isLessThan(75);
      var commonFilter = ((SQLMatchStatement) SQLEngine.parse(UNIQUE_QUERY, session))
          .getMatchExpressions().getFirst().getOrigin().getFilter().copy();
      assertThat(commonFilter.estimate(forumClass, 100, context(common))).isGreaterThan(75);
      assertThat(personClass.approximateCount(session) / 2).isEqualTo(75);
      for (var binding : List.of(rare, common, rare)) {
        var fresh = plan(UNIQUE_QUERY, context(binding));
        assertThat(root(fresh)).isEqualTo(binding == common ? "p" : "f");
        var copy = template.copy(context(binding));
        var expected = new ArrayList<String>();
        for (var fid : binding == common ? List.of(1, 2, 3) : List.of(0)) {
          for (var date = 0; date < 12; date += 2) {
            expected.add(date + ":0:" + fid);
          }
        }
        assertSliceBindings(forumBindings(copy), expected, 0, 10);
        assertSliceBindings(forumBindings(fresh), expected, 0, 10);
        var reference = plan(UNIQUE_QUERY.replace(".out('LIKES')", ".out(:label)"),
            context(binding));
        assertThat(reference.getSteps()).noneMatch(IndexOrderedEdgeStep.class::isInstance);
        assertSliceBindings(forumBindings(reference), expected, 0, 10);
        if (binding == common) {
          assertThat(ordered(copy).getChosenRuntimePath())
              .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
          assertThat(copy.getContext().<Boolean>getSystemVariable(
              CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isFalse();
        }
      }
      assertThat(ordered(template).getChosenRuntimePath()).isNull();
    } finally {
      template.close();
      session.rollback();
    }
  }

  /** Warm rare-root plans preserve common-input multiplicity and agree with fresh root schedules. */
  @Test
  public void warmUniqueSourcePreservesMultiplicityAcrossRootSchedules() {
    seed(true);
    var cache = YqlExecutionPlanCache.instance(session);
    var rare = Map.<Object, Object>of("t", "rare", "pid", 0);
    var common = Map.<Object, Object>of("t", "common", "pid", 0);
    cache.invalidate();
    var cold = ((SQLMatchStatement) SQLEngine.parse(UNIQUE_QUERY, session))
        .createExecutionPlan(context(rare), false);
    assertThat(root(cold)).isEqualTo("f");
    ordered(cold);
    cold.close();
    assertThat(cache.contains(UNIQUE_QUERY)).isTrue();
    for (var binding : List.of(rare, common, rare)) {
      var hits = cache.getHits();
      var warm = ((SQLMatchStatement) SQLEngine.parse(UNIQUE_QUERY, session))
          .createExecutionPlan(context(binding), false);
      assertThat(cache.getHits()).isEqualTo(hits + 1);
      assertThat(root(warm)).isEqualTo("f");
      var fresh = plan(UNIQUE_QUERY, context(binding));
      assertThat(root(fresh)).isEqualTo(binding == common ? "p" : "f");
      var expected = new ArrayList<String>();
      for (var fid : binding == common ? List.of(1, 2, 3) : List.of(0)) {
        for (var date = 0; date < 12; date += 2) {
          expected.add(date + ":0:" + fid);
        }
      }
      assertSliceBindings(forumBindings(warm), expected, 0, 10);
      assertSliceBindings(forumBindings(fresh), expected, 0, 10);
      if (binding == common) {
        assertThat(ordered(warm).getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
        assertThat(warm.getContext().<Boolean>getSystemVariable(
            CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isFalse();
      }
    }
    // A cold rejected candidate remains cacheable when a later estimate admits ordering.
    cache.invalidate();
    var query = UNIQUE_QUERY.replace("LIMIT 10", "LIMIT :limit");
    var rejected = ((SQLMatchStatement) SQLEngine.parse(query, session))
        .createExecutionPlan(context(Map.of("t", "rare", "pid", 0, "limit", -1)), false);
    assertThat(rejected.getSteps()).noneMatch(IndexOrderedEdgeStep.class::isInstance);
    rejected.close();
    var hits = cache.getHits();
    var params = Map.<Object, Object>of("t", "rare", "pid", 0, "limit", 10);
    var warm = ((SQLMatchStatement) SQLEngine.parse(query, session))
        .createExecutionPlan(context(params), false);
    assertThat(cache.getHits()).isEqualTo(hits + 1);
    assertThat(warm.getSteps()).noneMatch(IndexOrderedEdgeStep.class::isInstance);
    var fresh = plan(query, context(params));
    ordered(fresh);
    assertThat(forumBindings(warm)).isEqualTo(forumBindings(fresh));
    session.rollback();
  }

  /** Field-to-field equality can match two UNIQUE ids. Both sources must contribute in date order. */
  @Test
  public void fieldEqualityKeepsBothSourcesAndMatchesOrdinaryMatch() {
    seed(false);
    var query = "MATCH {class:Person,as:p,where:(id = altId)}"
        + ".out('LIKES'){class:Message,as:m}"
        + " RETURN m.creationDate AS d ORDER BY m.creationDate LIMIT 20";
    var execution = plan(query, context(Map.of()));
    assertThat(ordered(execution).prettyPrint(0, 2)).doesNotContain("(FILTERED");
    var actual = dates(execution);
    assertThat(actual).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    assertThat(ordered(execution).getChosenRuntimePath())
        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
    assertThat(execution.getContext().<Boolean>getSystemVariable(
        CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isFalse();
    var reference = plan(query.replace(".out('LIKES')", ".out(:label)"),
        context(Map.of("label", "LIKES")));
    assertThat(reference.getSteps()).noneMatch(IndexOrderedEdgeStep.class::isInstance);
    assertThat(actual).isEqualTo(dates(reference));
    session.rollback();
  }

  /** Several small sources with a downstream hop must use global sorting, not singleton LOAD_SORT. */
  @Test
  public void severalSourcesDoNotSignalLocallySortedOutputAsGloballySorted() {
    seed(false);
    var query = "MATCH {class:Person,as:p,where:(id = altId)}"
        + ".out('LIKES'){class:Message,as:m}.out('NEXT'){class:Reply,as:r}"
        + " RETURN m.creationDate AS d ORDER BY m.creationDate SKIP 1 LIMIT 10";
    // Each source alone takes LOAD_SORT. Combining those locally sorted sequences still needs
    // the global sort, so the multi-row execution must withdraw the pre-sorted signal.
    var singleton = plan(query.replace("id = altId", "id = 0"), context(Map.of()));
    assertThat(dates(singleton)).containsExactly(2, 4, 6, 8, 10);
    assertThat(ordered(singleton).getChosenRuntimePath())
        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
    assertThat(singleton.getContext().<Boolean>getSystemVariable(
        CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isTrue();
    var execution = plan(query, context(Map.of()));
    var actual = dates(execution);
    assertThat(actual).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    assertThat(ordered(execution).getChosenRuntimePath())
        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
    assertThat(execution.getContext().<Boolean>getSystemVariable(
        CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isFalse();
    var reference = plan(query.replace(".out('LIKES')", ".out(:label)"),
        context(Map.of("label", "LIKES")));
    assertThat(reference.getSteps()).noneMatch(IndexOrderedEdgeStep.class::isInstance);
    assertThat(actual).isEqualTo(dates(reference));
    session.rollback();
  }

  /** Buffered downstream rows keep their own Message and Reply on load, scan and unbounded copies. */
  @Test
  public void downstreamBindingsStayPairedAcrossEverySingletonRuntimePath() {
    session.execute("CREATE CLASS Person EXTENDS V").close();
    session.execute("CREATE PROPERTY Person.id INTEGER").close();
    session.execute("CREATE INDEX Person_id ON Person (id) UNIQUE").close();
    session.execute("CREATE CLASS Message EXTENDS V").close();
    session.execute("CREATE PROPERTY Message.creationDate INTEGER").close();
    session.execute("CREATE INDEX Message_date ON Message (creationDate) NOTUNIQUE").close();
    session.execute("CREATE CLASS Reply EXTENDS V").close();
    session.execute("CREATE CLASS LIKES EXTENDS E").close();
    session.execute("CREATE CLASS NEXT EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX Person SET id = 0").close();
    addPairedTargets(0, 2);
    session.commit();
    session.begin();
    var query = "MATCH {class:Person,as:p,where:(id=0)}"
        + ".out('LIKES'){class:Message,as:m}.out('NEXT'){class:Reply,as:r}"
        + " RETURN $patterns ORDER BY m.creationDate, r.id LIMIT :n";
    var template = plan(query, context(Map.of("n", 2)));
    try {
      assertThat(template.canBeCached()).isTrue();
      var bounded = template.copy(context(Map.of("n", 2)));
      assertThat(pairs(bounded)).containsExactly("0:0", "1:1");
      assertThat(ordered(bounded).getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
      var unbounded = template.copy(context(Map.of("n", -1)));
      assertThat(pairs(unbounded)).containsExactly("0:0", "1:1");
      assertThat(ordered(unbounded).getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED);
      // Increase degree above the scan threshold without adding unreachable index entries.
      addPairedTargets(2, 20);
      session.commit();
      session.begin();
      var scanned = plan(query, context(Map.of("n", 2)));
      assertThat(pairs(scanned)).containsExactly("0:0", "1:1");
      assertThat(ordered(scanned).getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.INDEX_SCAN);
      assertThat(ordered(template).getChosenRuntimePath()).isNull();
    } finally {
      template.close();
      session.rollback();
    }
  }

  private void addPairedTargets(int first, int end) {
    for (var i = first; i < end; i++) {
      session.execute("CREATE VERTEX Message SET creationDate = " + i).close();
      session.execute("CREATE VERTEX Reply SET id = " + i).close();
      session.execute("CREATE EDGE LIKES FROM (SELECT FROM Person WHERE id = 0)"
          + " TO (SELECT FROM Message WHERE creationDate = " + i + ")").close();
      session.execute("CREATE EDGE NEXT FROM (SELECT FROM Message WHERE creationDate = " + i
          + ") TO (SELECT FROM Reply WHERE id = " + i + ")").close();
    }
  }

  private List<String> pairs(InternalExecutionPlan plan) {
    try {
      var stream = plan.start();
      try {
        return stream.stream(plan.getContext())
            .map(row -> row.getVertex("m").getProperty("creationDate") + ":"
                + row.getVertex("r").getProperty("id"))
            .toList();
      } finally {
        stream.close(plan.getContext());
      }
    } finally {
      plan.close();
    }
  }

  private InternalExecutionPlan boundaryPlan(String query, CommandContext ctx, boolean downstream) {
    var original = plan(query, ctx);
    if (original.getSteps().stream().anyMatch(IndexOrderedEdgeStep.class::isInstance)) {
      return original;
    }
    // Admission requires LIMIT. Construct the same singleton boundary with absent clauses to
    // exercise the step's contract independently of that cost-only admission rule.
    var statement = (SQLMatchStatement) SQLEngine.parse(query, session);
    var comparison = new SQLOrderByItem();
    comparison.setRecordAttr("creationDate");
    comparison.setType(SQLOrderByItem.ASC);
    var index = session.getMetadata().getImmutableSchemaSnapshot().getClassInternal("Message")
        .getIndexesInternal().iterator().next();
    var step = new IndexOrderedEdgeStep(ctx, "p", "m", "LIKES", "out_LIKES", index, true,
        comparison, ResolvedOrderByNullsPlacement.SHIPPED, statement.getSkip(),
        statement.getLimit(), null, null, null, null, "Message", false,
        downstream ? 1 : 0, false, false);
    var result = new SelectExecutionPlan(ctx);
    var replaced = false;
    for (var item : original.getSteps()) {
      if (!replaced && item instanceof MatchStep) {
        result.chain(step);
        replaced = true;
      } else {
        result.chain((ExecutionStepInternal) item);
      }
    }
    assertThat(replaced).isTrue();
    return result;
  }

  /** The first upstream probe must preserve its failure and close upstream exactly once. */
  @Test
  public void firstProbeFailureClosesUpstreamOnce() {
    seed(false);
    assertUpstreamFailure(1, 2, new IllegalStateException("read-failed"), null);
    session.rollback();
  }

  /** Failures in the singleton check and later drain stay primary when cleanup succeeds. */
  @Test
  public void iterationFailureWithSuccessfulCloseStaysPrimary() {
    seed(false);
    for (var probe : List.of(2, 3, 4)) {
      assertUpstreamFailure(probe, 2, new IllegalStateException("read-failed"), null);
    }
    session.rollback();
  }

  /** Singleton-check and first-probe failures retain a close failure as a suppressed error. */
  @Test
  public void dualFailuresKeepReadPrimaryAndSuppressClose() {
    seed(false);
    for (var probe : List.of(2, 1)) {
      assertUpstreamFailure(probe, 2, new IllegalStateException("read-failed"),
          new IllegalArgumentException("close-failed"));
    }
    session.rollback();
  }

  /** Failures while draining multiple rows stay primary even when upstream close also fails. */
  @Test
  public void dualDrainFailuresKeepReadPrimaryAndSuppressClose() {
    seed(false);
    for (var probe : List.of(3, 4)) {
      assertUpstreamFailure(probe, 2, new IllegalStateException("read-failed"),
          new IllegalArgumentException("close-failed"));
    }
    session.rollback();
  }

  /** Cleanup alone must fail empty, singleton and multi-row startup, with one upstream close. */
  @Test
  public void closeOnlyFailurePropagatesOnEveryRowCount() {
    seed(false);
    for (var count : List.of(0, 1, 2)) {
      assertUpstreamFailure(0, count, null, new IllegalArgumentException("close-failed"));
    }
    session.rollback();
  }

  /** Reusing the same failure for read and close must not replace it with self-suppression. */
  @Test
  public void identicalReadAndCloseFailureStaysPrimary() {
    seed(false);
    var failure = new IllegalStateException("shared-failure");
    assertUpstreamFailure(2, 2, failure, failure);
    session.rollback();
  }

  private void assertUpstreamFailure(int failingProbe, int count, RuntimeException readFailure,
      RuntimeException closeFailure) {
    var ctx = context(Map.of());
    var execution = plan("MATCH {class:Person,as:p,where:(id=0)}"
        + ".out('LIKES'){class:Message,as:m}"
        + " RETURN $patterns ORDER BY m.creationDate LIMIT 10", ctx);
    var closed = new AtomicInteger();
    var step = ordered(execution);
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return new ExecutionStream() {
          private int probes;
          private int rows;

          @Override
          public boolean hasNext(CommandContext context) {
            if (++probes == failingProbe) {
              throw readFailure;
            }
            return rows < count;
          }

          @Override
          public Result next(CommandContext context) {
            rows++;
            return new ResultInternal(session);
          }

          @Override
          public void close(CommandContext context) {
            closed.incrementAndGet();
            if (closeFailure != null) {
              throw closeFailure;
            }
          }
        };
      }

      @Override
      public AbstractExecutionStep copy(CommandContext context) {
        throw new UnsupportedOperationException("Failure source is execution-local");
      }
    });
    try {
      var actual = assertThrows(RuntimeException.class, () -> step.internalStart(ctx));
      assertThat(actual).isSameAs(readFailure == null ? closeFailure : readFailure);
      assertThat(actual.getSuppressed()).containsExactly(
          readFailure != null && closeFailure != null && readFailure != closeFailure
              ? new Throwable[] {closeFailure} : new Throwable[0]);
      assertThat(closed.get()).isEqualTo(1);
    } finally {
      execution.close();
    }
    assertThat(closed.get()).isEqualTo(1);
  }

  /**
   * RID-pinned fresh steps and copies check empty, one, two and many rows before any output.
   * Repeated sources retain distinct markers. A row without a source contributes no targets.
   */
  @Test
  public void ridPinnedBoundaryChecksAllRowsBindingsAndGlobalSortBeforeOutput() {
    seed(false);
    var persons = session.query("SELECT FROM Person ORDER BY id").toList();
    var sources = new ArrayList<Result>();
    for (var i = 0; i < 5; i++) {
      var row = new ResultInternal(session);
      row.setProperty("p", persons.get(i % 2));
      row.setProperty("marker", "binding" + i);
      sources.add(row);
    }
    sources.add(new ResultInternal(session));
    for (var downstream : List.of(false, true)) {
      for (var suffix : List.of("", " SKIP 1", " LIMIT 10", " SKIP 1 LIMIT 10")) {
        var query = "MATCH {class:Person,as:p,where:(@rid IN :rids)}"
            + ".out('LIKES'){class:Message,as:m}"
            + (downstream ? ".out('NEXT'){class:Reply,as:r}" : "")
            + " RETURN $patterns ORDER BY m.creationDate" + suffix;
        var bindings = Map.<Object, Object>of("rids", List.of(persons.getFirst().getIdentity()));
        var template = boundaryPlan(query, context(bindings), downstream);
        try {
          for (var count : List.of(0, 1, 2, 3, 5, 6)) {
            for (var privateCopy : List.of(false, true)) {
              var execution = privateCopy ? template.copy(context(bindings))
                  : boundaryPlan(query, context(bindings), downstream);
              var step = ordered(execution);
              assertThat(step.prettyPrint(0, 2)).doesNotContain("(FILTERED");
              var consumed = new AtomicInteger();
              var closed = new AtomicInteger();
              step.setPrevious(new AbstractExecutionStep(execution.getContext(), false) {
                @Override
                public ExecutionStream internalStart(CommandContext ctx) {
                  if (count > 1) {
                    ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED,
                        Boolean.TRUE);
                  }
                  return ExecutionStream.resultIterator(sources.subList(0, count).iterator())
                      .map((row, ignored) -> {
                        consumed.incrementAndGet();
                        return row;
                      }).onClose(ignored -> closed.incrementAndGet());
                }

                @Override
                public AbstractExecutionStep copy(CommandContext ctx) {
                  throw new UnsupportedOperationException("Boundary source is execution-local");
                }
              });
              try {
                var stream = execution.start();
                try {
                  // start() must exhaust and close upstream before exposing even the first row.
                  assertThat(consumed.get()).isEqualTo(count);
                  assertThat(closed.get()).isEqualTo(1);
                  if (count > 1) {
                    assertThat(step.getChosenRuntimePath())
                        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
                    assertThat(execution.getContext().<Boolean>getSystemVariable(
                        CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isFalse();
                  } else if (count == 1 && downstream && suffix.contains("LIMIT")) {
                    assertThat(step.getChosenRuntimePath())
                        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
                  }
                  var expected = new ArrayList<String>();
                  for (var i = 0; i < Math.min(count, 5); i++) {
                    for (var date = i % 2; date < 12; date += 2) {
                      expected.add(date + ":" + (i % 2) + ":binding" + i);
                    }
                  }
                  expected.sort(Comparator.comparingInt(
                      value -> Integer.parseInt(value.substring(0, value.indexOf(':')))));
                  var actual = stream.stream(execution.getContext())
                      .map(row -> row.getVertex("m").getProperty("creationDate") + ":"
                          + row.getVertex("p").getProperty("id") + ":"
                          + row.<String>getProperty("marker"))
                      .toList();
                  assertSliceBindings(actual, expected, suffix.contains("SKIP") ? 1 : 0,
                      suffix.contains("LIMIT") ? 10 : Long.MAX_VALUE);
                } finally {
                  stream.close(execution.getContext());
                }
              } finally {
                execution.close();
              }
            }
          }
          assertThat(ordered(template).getChosenRuntimePath()).isNull();
        } finally {
          template.close();
        }
      }
    }
    session.rollback();
  }
}
