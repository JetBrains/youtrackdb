package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Database-backed checks for the bounded candidate reader, before runtime dispatch enables it. */
@Category(SequentialTest.class)
public class IndexOrderedEqualityFetchTest extends DbTestBase {

  private RID source;

  private void seed() {
    session.execute("CREATE CLASS Author EXTENDS V").close();
    session.execute("CREATE CLASS Message EXTENDS V").close();
    session.execute("CREATE CLASS Reply EXTENDS Message").close();
    session.execute("CREATE PROPERTY Message.date INTEGER").close();
    session.execute("CREATE PROPERTY Message.tag STRING").close();
    session.execute("CREATE INDEX Message_date ON Message (date) NOTUNIQUE").close();
    session.execute("CREATE INDEX Message_tag ON Message (tag) NOTUNIQUE").close();
    session.execute("CREATE CLASS wrote EXTENDS E").close();
    session.begin();
    session.execute("CREATE VERTEX Author").close();
    for (int i = 0; i < 100; i++) {
      session.execute("CREATE VERTEX Message SET date = " + i + ", tag = '"
          + (i == 0 ? "rare" : "common") + "'").close();
    }
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date < 80)").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date = 0)").close();
    session.execute("CREATE VERTEX Reply SET date = 101, tag = 'common'").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Reply)").close();
    session.commit();
    session.begin();
    try (var rows = session.query("SELECT FROM Author")) {
      source = rows.next().getIdentity();
    }
  }

  private IndexOrderedEdgeStep.EqualityAttempt read(
      String condition, String targetClass, long memoryLimit, Result bound) {
    return read(condition, targetClass, memoryLimit, bound, Double.POSITIVE_INFINITY);
  }

  private IndexOrderedEdgeStep.EqualityAttempt read(
      String condition, String targetClass, long memoryLimit, Result bound, double remaining) {
    return read(condition, targetClass, memoryLimit, bound, remaining, probe -> {
    });
  }

  private static final class ReadProbe {
    private final Map<String, Integer> counts = new HashMap<>();
    private double spent;
    private double initial;
    private IndexOrderedCostModel.WorkAccount account;
    private IndexOrderedCostModel.EqualityCosts costs;
    private int openedRanges;
    private int closedRanges;
    private int closedCursors;
    private boolean openedCursor;

    void visit(String operation, double units) {
      spent += units;
      assertThat(account.allowance(costs)).as("charge before %s", operation)
          .isCloseTo(initial - spent, within(0.00001));
      counts.merge(operation, 1, Integer::sum);
    }

    int count(String operation) {
      return counts.getOrDefault(operation, 0);
    }
  }

  private IndexOrderedEdgeStep.EqualityAttempt read(
      String condition, String targetClass, long memoryLimit, Result bound, double remaining,
      Consumer<ReadProbe> check) {
    var context = new BasicCommandContext(session);
    context.setInputParameters(Map.of("tag", "rare"));
    var sql = "MATCH {class: Author, as: a, where: (@rid = " + source + ")}"
        + ".out('wrote'){class: " + targetClass + ", as: m, where: (" + condition + ")}"
        + " RETURN m.date AS d ORDER BY m.date ASC LIMIT 5";
    var plan = ((SQLMatchStatement) SQLEngine.parse(sql, session))
        .createExecutionPlanNoCache(context, false);
    try {
      var step = plan.getSteps().stream().filter(IndexOrderedEdgeStep.class::isInstance)
          .map(IndexOrderedEdgeStep.class::cast).findFirst()
          .orElseThrow(() -> new AssertionError(plan.prettyPrint(0, 2)));
      assertThat(step.targetIndexDescriptor()).isNotNull();
      var upstream = new ResultInternal(session);
      if (bound != null) {
        upstream.setProperty("m", bound);
      }
      var account = new IndexOrderedCostModel.WorkAccount(10000);
      var costs = new IndexOrderedCostModel.EqualityCosts(1000000, 1, 1000000);
      assertThat(account.choose(costs)).isEqualTo(IndexOrderedCostModel.EqualityPath.EQUALITY);
      if (Double.isFinite(remaining)) {
        assertThat(account.spend(account.allowance(costs) - remaining)).isTrue();
      }
      var probe = new ReadProbe();
      probe.account = account;
      probe.costs = costs;
      probe.initial = account.allowance(costs);
      var descriptor = step.targetIndexDescriptor();
      // Observe actual range acquisition, not the callback that is supposed to charge it.
      var index = mock(Index.class, invocation -> {
        boolean lookup = invocation.getMethod().getName().equals("streamEntriesBetween")
            || invocation.getMethod().getName().equals("getNullKeyRids");
        if (lookup) {
          probe.visit("seek", IndexOrderedCostModel.indexSeekEntryUnits());
        }
        try {
          var value =
              invocation.getMethod().invoke(descriptor.getIndex(), invocation.getArguments());
          if (lookup && value instanceof Stream<?> stream) {
            probe.openedRanges++;
            return stream.onClose(() -> probe.closedRanges++);
          }
          return value;
        } catch (InvocationTargetException e) {
          throw e.getCause();
        }
      });
      var reads = step.new EqualityReads() {
        @Override
        IndexSearchDescriptor descriptor() {
          return mock(IndexSearchDescriptor.class, invocation -> {
            if (invocation.getMethod().getName().equals("getIndex")) {
              return index;
            }
            invocation.getMethod().setAccessible(true);
            return invocation.getMethod().invoke(descriptor, invocation.getArguments());
          });
        }

        @Override
        ExecutionStream open(CommandContext ctx, Runnable beforeRangeLookup) {
          var cursor = super.open(ctx, beforeRangeLookup);
          probe.openedCursor = true;
          return cursor.onClose(ignored -> probe.closedCursors++);
        }

        @Override
        boolean advance(ExecutionStream cursor, CommandContext ctx) {
          probe.visit("advance", 1);
          return super.advance(cursor, ctx);
        }

        @Override
        Result record(RID rid, CommandContext ctx) {
          probe.visit("record", IndexOrderedCostModel.recordReadEntryUnits());
          return super.record(rid, ctx);
        }

        @Override
        Iterator<RidPair> edges(LinkBag bag) {
          probe.visit("edges", IndexOrderedCostModel.reverseEdgeEntryUnits());
          return super.edges(bag);
        }

        @Override
        RidPair next(Iterator<RidPair> edges) {
          probe.visit("edgeNext", IndexOrderedCostModel.reverseEdgeEntryUnits());
          return super.next(edges);
        }
      };
      var sourceBag = (LinkBag) ((EntityImpl) session.getActiveTransaction().load(source))
          .getPropertyInternal("out_wrote");
      var result = step.readEqualityCandidates(
          upstream, source, sourceBag, context, account, costs, memoryLimit, reads);
      assertThat(probe.closedRanges).as("all acquired range streams close")
          .isEqualTo(probe.openedRanges);
      assertThat(probe.closedCursors).as("reader closes its cursor on every exit")
          .isEqualTo(probe.openedCursor ? 1 : 0);
      check.accept(probe);
      assertThat(step.getChosenRuntimePath()).isNull();
      assertThat(step.lastScanConsumedEntries()).isEqualTo(-1);
      return result;
    } finally {
      plan.close();
    }
  }

  private IndexOrderedEdgeStep.EqualityAttempt read(String condition) {
    return read(condition, "Message", 1000, null);
  }

  /** A point miss completes with no candidates and never starts the ordering cursor. */
  @Test
  public void missingEqualityCompletesWithoutOrderingScan() {
    seed();
    var attempt = read("tag = 'absent'");
    assertThat(attempt.complete()).isTrue();
    assertThat(attempt.observedEntries()).isZero();
    assertThat(attempt.candidates()).isEmpty();
    assertThat(attempt.requiresLinkBagLoad()).isFalse();
  }

  /** One candidate carries two source links, rather than losing a parallel edge. */
  @Test
  public void hitPreservesParallelEdgesAndRechecksRemainingFilter() {
    seed();
    var attempt = read("tag = :tag AND date = 0");
    assertThat(attempt.complete()).isTrue();
    assertThat(attempt.candidates()).hasSize(1);
    assertThat(attempt.candidates().getFirst().links()).isEqualTo(2);
    assertThat((Integer) attempt.candidates().getFirst().record().getProperty("date")).isZero();
    assertThat(read("tag = :tag AND date < 0").candidates()).isEmpty();
  }

  /** Repeated IN keys must not multiply candidate rows or their edge counts. */
  @Test
  public void repeatedIndexRidsAreDeduplicated() {
    seed();
    var attempt = read("tag IN ['rare', 'rare']");
    assertThat(attempt.complete()).isTrue();
    assertThat(attempt.observedEntries()).isGreaterThan(1);
    assertThat(attempt.candidates()).hasSize(1);
    assertThat(attempt.candidates().getFirst().links()).isEqualTo(2);
  }

  /** A superclass index hit is not a match for the requested subclass. */
  @Test
  public void superclassIndexCannotReturnSiblingRecords() {
    seed();
    var attempt = read("tag = :tag", "Reply", 1000, null);
    assertThat(attempt.complete()).isTrue();
    assertThat(attempt.observedEntries()).isPositive();
    assertThat(attempt.candidates()).isEmpty();
  }

  /** A target alias bound to another record rejects an otherwise valid index candidate. */
  @Test
  public void alreadyBoundAliasIsRechecked() {
    seed();
    try (var rows = session.query("SELECT FROM Message WHERE date = 1")) {
      var attempt = read("tag = :tag", "Message", 1000, rows.next());
      assertThat(attempt.complete()).isTrue();
      assertThat(attempt.candidates()).isEmpty();
    }
  }

  /** A candidate outside the source bag contributes no row even when its filter matches. */
  @Test
  public void unrelatedCandidateDoesNotContributeLinks() {
    seed();
    session.execute("CREATE VERTEX Author").close();
    session.execute("UPDATE Message SET tag = 'outside' WHERE date = 99").close();
    session.execute("CREATE EDGE wrote FROM (SELECT FROM Author)"
        + " TO (SELECT FROM Message WHERE date = 99)").close();
    // The added edge links both authors. Select the second author as the source, then ask for rare.
    try (var rows = session.query("SELECT FROM Author WHERE @rid <> " + source)) {
      source = rows.next().getIdentity();
    }
    var attempt = read("tag = :tag");
    assertThat(attempt.complete()).isTrue();
    assertThat(attempt.candidates()).isEmpty();
  }

  /** A memory overrun discards the whole attempt, including any earlier accepted candidates. */
  @Test
  public void memoryLimitDiscardsPartialResults() {
    seed();
    var attempt = read("tag IN ['rare', 'common']", "Message", 1, null);
    assertThat(attempt.complete()).isFalse();
    assertThat(attempt.observedEntries()).isGreaterThan(1);
    assertThat(attempt.candidates()).isEmpty();
    assertThat(attempt.requiresLinkBagLoad()).isFalse();
  }

  /** A missing reverse field is an empty adjacency, so selective fetch completes without load. */
  @Test
  public void missingReverseFieldCompletesWithNoLinks() {
    seed();
    try (var rows = session.query("SELECT FROM Message WHERE date = 0")) {
      ((EntityImpl) rows.next().asEntityOrNull()).setPropertyInternal("in_wrote", null);
    }
    var attempt = read("tag = :tag");
    assertThat(attempt.complete()).isTrue();
    assertThat(attempt.requiresLinkBagLoad()).isFalse();
    assertThat(attempt.candidates()).isEmpty();
  }

  /** A real non-LinkBag representation still requires the terminal source-side load. */
  @Test
  public void nonLinkBagReverseFieldRequiresLoad() {
    seed();
    try (var rows = session.query("SELECT FROM Message WHERE date = 0")) {
      ((EntityImpl) rows.next().asEntityOrNull()).setPropertyInternal("in_wrote", "unsupported");
    }
    var attempt = read("tag = :tag");
    assertThat(attempt.complete()).isFalse();
    assertThat(attempt.requiresLinkBagLoad()).isTrue();
    assertThat(attempt.candidates()).isEmpty();
  }

  /** A refused point seek must not open the equality cursor or report any observed entry. */
  @Test
  public void exhaustedSeekBudgetStopsBeforeReading() {
    seed();
    var attempt = read("tag = :tag", "Message", 1000, null, 0.1);
    assertThat(attempt.complete()).isFalse();
    assertThat(attempt.observedEntries()).isZero();
    assertThat(attempt.candidates()).isEmpty();
  }

  /** The next cursor advance needs its own charge after the point seek. */
  @Test
  public void exhaustedIndexBudgetStopsBeforeReading() {
    seed();
    var attempt = read("tag = :tag", "Message", 1000, null,
        IndexOrderedCostModel.indexSeekEntryUnits());
    assertThat(attempt.complete()).isFalse();
    assertThat(attempt.observedEntries()).isZero();
  }

  /** Spending on the seek can leave too little for the first candidate record read. */
  @Test
  public void exhaustedRecordBudgetDiscardsCandidate() {
    seed();
    var attempt = read("tag = :tag", "Message", 1000, null,
        IndexOrderedCostModel.indexSeekEntryUnits() + 1
            + IndexOrderedCostModel.recordReadEntryUnits() / 2);
    assertThat(attempt.complete()).isFalse();
    assertThat(attempt.observedEntries()).isEqualTo(1);
    assertThat(attempt.candidates()).isEmpty();
  }

  /** Every visited reverse edge needs a separate charge, even for an already loaded record. */
  @Test
  public void exhaustedReverseEdgeBudgetDiscardsCandidate() {
    seed();
    var attempt = read("tag = :tag", "Message", 1000, null,
        IndexOrderedCostModel.indexSeekEntryUnits() + 1
            + IndexOrderedCostModel.recordReadEntryUnits()
            + IndexOrderedCostModel.reverseEdgeEntryUnits() / 2);
    assertThat(attempt.complete()).isFalse();
    assertThat(attempt.observedEntries()).isEqualTo(1);
    assertThat(attempt.candidates()).isEmpty();
  }

  /** Absent IN keys cannot perform more seeks than the account permits. */
  @Test
  public void absentInKeysStopBeforeRefusedSeek() {
    seed();
    var attempt = read("tag IN ['missing1', 'missing2', 'missing3']", "Message", 1000, null,
        IndexOrderedCostModel.indexSeekEntryUnits() * 1.5, probe -> {
          assertThat(probe.count("seek")).isEqualTo(1);
          assertThat(probe.count("advance")).isZero();
        });
    assertThat(attempt.complete()).isFalse();
    assertThat(attempt.candidates()).isEmpty();
  }

  /** Refused reads never cross the observed storage boundary, even for cached records. */
  @Test
  public void refusedOperationsDoNotRead() {
    seed();
    double seek = IndexOrderedCostModel.indexSeekEntryUnits();
    double record = IndexOrderedCostModel.recordReadEntryUnits();
    double edge = IndexOrderedCostModel.reverseEdgeEntryUnits();
    read("tag = :tag", "Message", 1000, null, seek / 2,
        probe -> assertThat(probe.count("seek")).isZero());
    read("tag = :tag", "Message", 1000, null, seek,
        probe -> assertThat(probe.count("advance")).isZero());
    read("tag = :tag", "Message", 1000, null, seek + 1 + record / 2,
        probe -> assertThat(probe.count("record")).isZero());
    read("tag = :tag", "Message", 1000, null, seek + 1 + record + edge / 2,
        probe -> assertThat(probe.count("edges")).isZero());
    read("tag = :tag", "Message", 1000, null, seek + 1 + record + edge * 1.5,
        probe -> assertThat(probe.count("edgeNext")).isZero());
  }

  /** Exhaustion after accepting the rare candidate must discard it at every later read stage. */
  @Test
  public void laterExhaustionDiscardsAcceptedPrefix() {
    seed();
    double seek = IndexOrderedCostModel.indexSeekEntryUnits();
    double record = IndexOrderedCostModel.recordReadEntryUnits();
    double edge = IndexOrderedCostModel.reverseEdgeEntryUnits();
    // Two ranges open before reading. The rare candidate has two links and is accepted first.
    double first = seek * 2 + 1 + record + edge * 3;
    for (double remaining : new double[] {first + 0.5, first + 1 + record / 2,
        first + 1 + record + edge / 2}) {
      var attempt = read("tag IN ['rare', 'common']", "Message", 1000, null, remaining, probe -> {
        assertThat(probe.count("edgeNext")).isGreaterThanOrEqualTo(2);
      });
      assertThat(attempt.complete()).isFalse();
      assertThat(attempt.candidates()).isEmpty();
    }
  }

  /** Transaction-local equality updates are visible without committing or rebuilding the plan. */
  @Test
  public void pendingTargetUpdateIsVisible() {
    seed();
    session.execute("UPDATE Message SET tag = 'common' WHERE date = 0").close();
    session.execute("UPDATE Message SET tag = 'rare' WHERE date = 1").close();
    var attempt = read("tag = :tag");
    assertThat(attempt.complete()).isTrue();
    assertThat(attempt.candidates()).hasSize(1);
    assertThat((Integer) attempt.candidates().getFirst().record().getProperty("date")).isEqualTo(1);
    assertThat(attempt.candidates().getFirst().links()).isEqualTo(1);
  }
}
