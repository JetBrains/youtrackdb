package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Counters distinguish remembered answers from merely correct repeated execution. */
@Category(SequentialTest.class)
public class DetachedCheckMemoryTest {

  private Object threshold;
  private Object minimum;

  @Before
  public void configure() {
    threshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    minimum = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(2L);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(5L);
  }

  @After
  public void restore() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(threshold);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(minimum);
  }

  /** Every alias, including an optional null, participates. Non-records bypass memory. */
  @Test
  public void completeIdentityTupleIncludesNullAndRejectsNonRecords() {
    var memory = new DetachedCheckMemory(new DetachedCheckReadSet(List.of("p", "q"), false, true));
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(20L);
    memory.start();
    var calls = new AtomicInteger();
    var p = new RecordId(10, 1);
    var q = new RecordId(10, 2);
    for (var row : List.of(row(p, null), row(p, q), row(q, q), row(p, null), row(p, q),
        row(q, q), row(p, "not a record"), row(p, "not a record"))) {
      boolean expected = row.getProperty("q") == null;
      assertThat(memory.matches(row, () -> {
        calls.incrementAndGet();
        return expected;
      }))
          .isEqualTo(expected);
    }
    assertThat(calls).hasValue(5);
    assertThat(memory.hits()).isEqualTo(3);
    assertThat(memory.size()).isEqualTo(3);
    var identity = mock(Result.class);
    when(identity.getIdentity()).thenReturn(p);
    assertThat(memory.matches(row(identity, q), () -> true)).isFalse();
    var projection = new ResultInternal(null);
    assertThat(memory.matches(row(projection, q), () -> true)).isTrue();
    assertThat(memory.size()).isEqualTo(3);
  }

  /** Full memory retains old true and false answers, probes overflow keys and clears on restart. */
  @Test
  public void limitRetainsAnswersAndRestartCloseAndZeroDiscardState() {
    var memory = new DetachedCheckMemory(new DetachedCheckReadSet(List.of("p"), true, true));
    memory.start();
    var calls = new AtomicInteger();
    for (int id : List.of(1, 2, 3, 1, 2, 3)) {
      boolean expected = id % 2 == 0;
      assertThat(memory.matches(row(new RecordId(10, id), null),
          () -> {
            calls.incrementAndGet();
            return expected;
          })).isEqualTo(expected);
    }
    assertThat(calls).hasValue(4);
    assertThat(memory.size()).isEqualTo(2);
    assertThat(memory.hits()).isEqualTo(2);
    memory.start();
    assertThat(memory.matches(row(new RecordId(10, 1), null), () -> true)).isTrue();
    memory.clear();
    assertThat(memory.size()).isZero();
    assertThat(memory.hits()).isZero();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);
    memory.start();
    for (int i = 0; i < 2; i++) {
      memory.matches(row(new RecordId(10, 1), null), () -> {
        calls.incrementAndGet();
        return true;
      });
    }
    assertThat(calls).hasValue(6);
    assertThat(memory.size()).isZero();
  }

  /** Unsafe syntax never stores answers. Parameters and bound alias property reads are safe. */
  @Test
  public void syntaxWhitelistDistinguishesAliasParametersAndHiddenState() throws Exception {
    for (var predicate : List.of("name = :name", "name = $matched.q.name", "name = 'yes'",
        "@rid = $matched.q.@rid")) {
      var reads = reads(predicate);
      assertThat(reads.rememberable()).as(predicate).isTrue();
      assertThat(reads.independent()).isEqualTo(!predicate.contains("$matched"));
    }
    assertThat(reads("name = $matched.q.name").aliases()).containsExactly("p", "q");
    var positive = new Pattern();
    parse("MATCH {as:p}, {as:q} RETURN p").getMatchExpressions().forEach(positive::addExpression);
    var dynamicLabel = parse("MATCH {as:p}.out($matched.q.name){as:x} RETURN p")
        .getMatchExpressions().getFirst();
    var labelReads = DetachedCheckReadSet.describe(dynamicLabel, positive);
    assertThat(labelReads.aliases()).containsExactly("p", "q");
    assertThat(labelReads.rememberable()).isTrue();
    assertThat(labelReads.independent()).isFalse();
    assertThat(MatchExecutionPlanner.canUseHashJoin(dynamicLabel, java.util.Map.of("p", "P"),
        java.util.Map.of(), java.util.Map.of(), null, positive)).isFalse();
    var unknownLabel = parse("MATCH {as:p}.out($matched.unknown.name){as:x} RETURN p")
        .getMatchExpressions().getFirst();
    assertThat(MatchExecutionPlanner.canUseHashJoin(unknownLabel, java.util.Map.of("p", "P"),
        java.util.Map.of(), java.util.Map.of(), null, positive)).isFalse();
    var literalRid = parse("MATCH {as:p}.out('E'){as:x, rid:#10:1} RETURN p")
        .getMatchExpressions().getFirst();
    assertThat(DetachedCheckReadSet.describe(literalRid, positive).rememberable()).isTrue();
    com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid.internalPromoteExpression(
        literalRid.getItems().getFirst().getFilter().getRid(null),
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression(
            new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier("$unrelated")));
    assertThat(DetachedCheckReadSet.describe(literalRid, positive).rememberable()).isFalse();
    dynamicLabel.setItems(
        List.of(new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem(-1)));
    assertThat(DetachedCheckReadSet.describe(dynamicLabel, positive).rememberable()).isFalse();
    for (var predicate : List.of("name = $other", "name = $parent.name", "name = random()",
        "name = sysdate()", "name = uuid()", "name = sequence('x').next()",
        "name = $matched.missing.name")) {
      var reads = reads(predicate);
      assertThat(reads.rememberable()).as(predicate).isFalse();
      var memory = new DetachedCheckMemory(reads);
      memory.start();
      var calls = new AtomicInteger();
      for (int i = 0; i < 2; i++) {
        memory.matches(row(new RecordId(10, 1), null), () -> {
          calls.incrementAndGet();
          return true;
        });
      }
      assertThat(calls).hasValue(2);
      assertThat(memory.size()).isZero();
    }
  }

  /** Both flat polarities and tree probes own fresh per-start state and preserve duplicate rows. */
  @Test
  public void evaluatingStepsRememberOnceAndRestartWithNewAnswers() throws Exception {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.assertIfNotActive()).thenReturn(true);
    var ctx = new BasicCommandContext(session);
    var reads = reads("name = :name");
    for (int kind = 0; kind < 3; kind++) {
      var calls = new AtomicInteger();
      var answer = new java.util.concurrent.atomic.AtomicBoolean(true);
      var probe = new AbstractExecutionStep(ctx, false) {
        @Override
        public ExecutionStream internalStart(CommandContext context) {
          calls.incrementAndGet();
          return answer.get() ? ExecutionStream.singleton(new ResultInternal(null))
              : ExecutionStream.empty();
        }

        @Override
        public ExecutionStep copy(CommandContext context) {
          return this;
        }
      };
      AbstractExecutionStep filter = switch (kind) {
        case 0 -> new FilterExistsMatchPatternStep(List.of(probe), ctx, false, reads);
        case 1 -> new FilterNotMatchPatternStep(List.of(probe), ctx, false, reads);
        default -> new FilterBooleanMatchStep(ctx, false, FilterBooleanMatchStep.Kind.PROBE,
            List.of(), null, null, List.of(probe), null, reads);
      };
      var row = row(new RecordId(10, 1), null);
      filter.setPrevious(new AbstractExecutionStep(ctx, false) {
        @Override
        public ExecutionStream internalStart(CommandContext context) {
          return ExecutionStream.resultIterator(List.<Result>of(row, row, row).iterator());
        }

        @Override
        public ExecutionStep copy(CommandContext context) {
          throw new UnsupportedOperationException();
        }
      });
      for (int start = 0; start < 3; start++) {
        answer.set(start == 0);
        var stream = filter.start(ctx);
        assertThat(stream.stream(ctx).toList()).hasSize((kind == 1) != answer.get() ? 3 : 0);
        stream.close(ctx);
        assertThat(calls).hasValue(start + 1);
        if (start == 0) {
          filter.reset();
        } else {
          filter.close();
        }
      }
    }
  }

  /** Failed probes publish no answer. A fresh copy cannot observe the original memory. */
  @Test
  public void failedProbeAndIndependentOwnersCannotPublishAnswers() {
    var reads = new DetachedCheckReadSet(List.of("p"), true, true);
    var first = new DetachedCheckMemory(reads);
    var copy = new DetachedCheckMemory(reads);
    first.start();
    copy.start();
    var row = row(new RecordId(10, 1), null);
    assertThatThrownBy(() -> first.matches(row, () -> {
      throw new IllegalStateException("failed");
    }))
        .hasMessage("failed");
    assertThat(first.size()).isZero();
    assertThat(first.matches(row, () -> true)).isTrue();
    assertThat(copy.matches(row, () -> false)).isFalse();
    assertThat(first.matches(row, () -> false)).isTrue();
  }

  /** Repeated keys favor memory, but overflow keys with low match fraction still favor hash. */
  @Test
  public void costCountsRememberedFailuresAndEveryOverflowRow() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(256L);
    var rows = OptionalLong.of(8192);
    var walk = OptionalDouble.of(10);
    assertThat(MatchExecutionPlanner.detachedHashCostWins(rows, 64, walk,
        OptionalLong.empty(), OptionalDouble.empty(), OptionalDouble.of(64))).isFalse();
    assertThat(MatchExecutionPlanner.detachedHashCostWins(rows, 512, walk,
        OptionalLong.empty(), OptionalDouble.empty(), OptionalDouble.of(512))).isTrue();
    assertThat(MatchExecutionPlanner.rememberedProbeCount(8192, OptionalDouble.of(512), 256))
        .isEqualTo(4352);
    assertThat(MatchExecutionPlanner.rememberedProbeCount(0, OptionalDouble.of(1), 2)).isZero();
    assertThat(MatchExecutionPlanner.rememberedProbeCount(10, OptionalDouble.empty(), 2))
        .isEqualTo(10);
    assertThat(MatchExecutionPlanner.rememberedProbeCount(10, OptionalDouble.of(0), 2))
        .isEqualTo(10);
    assertThat(MatchExecutionPlanner.rememberedProbeCount(10, OptionalDouble.of(1), 0))
        .isEqualTo(10);
  }

  private static ResultInternal row(Object p, Object q) {
    var row = new ResultInternal(null);
    row.setProperty("p", p);
    row.setProperty("q", q);
    return row;
  }

  private static DetachedCheckReadSet reads(String predicate) throws Exception {
    var positive = parse("MATCH {as:p}, {as:q} RETURN p");
    var pattern = new Pattern();
    positive.getMatchExpressions().forEach(pattern::addExpression);
    return DetachedCheckReadSet.describe(parse("MATCH {as:p}.out('E'){as:x, where:("
        + predicate + ")} RETURN p").getMatchExpressions().getFirst(), pattern);
  }

  private static SQLMatchStatement parse(String sql) throws Exception {
    return (SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
        sql.getBytes(StandardCharsets.UTF_8))).parse();
  }
}
