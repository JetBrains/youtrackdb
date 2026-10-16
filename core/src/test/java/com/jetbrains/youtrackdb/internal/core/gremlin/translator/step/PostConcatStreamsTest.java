package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.ExecutionThreadLocal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link PostConcatStreams}, the decorators that realise a recognised {@code
 * count()} / {@code skip()} / {@code limit()} / {@code dedup()} / {@code order()} suffix over a
 * union's concatenated child streams.
 *
 * <p>Each test drives a {@link ListStub} — a real, non-mock {@link ExecutionStream} over a fixed row
 * list that records how many times it was closed and how many rows were actually pulled from it.
 * Those two counters are what distinguish a correct decorator from one that merely returns the right
 * answer: a limit that drains its upstream anyway would still emit the right rows, and a
 * drain-and-count that closes its upstream twice would still report the right total.
 */
public class PostConcatStreamsTest {

  private CommandContext ctx;

  @Before
  public void setUp() {
    // singleCountRow builds a ResultInternal against the context's session, and every
    // ResultInternal mutation asserts the session is active; a bare mock answers false and trips
    // that assert under -ea.
    var session = mock(DatabaseSessionEmbedded.class);
    lenient().when(session.assertIfNotActive()).thenReturn(true);
    var basic = new BasicCommandContext();
    basic.setDatabaseSession(session);
    ctx = basic;
  }

  // ---- count() over an already-reduced concatenation ----

  /**
   * The non-push-down count drains its upstream and emits exactly one row carrying the row count.
   * This is the path {@code union(…).limit(n).count()} and {@code union(…).dedup().count()} take:
   * the lone-{@code Count} push-down is unavailable once another reduction precedes the count, so
   * the reduced rows have to be counted one at a time.
   */
  @Test
  public void count_drainsUpstreamAndEmitsExactlyOneTotalRow() {
    var upstream = new ListStub(rows(3));

    var counted = PostConcatStreams.count(upstream);

    assertThat(counted.hasNext(ctx)).isTrue();
    assertThat(counted.next(ctx).<Long>getProperty("count")).isEqualTo(3L);
    assertThat(counted.hasNext(ctx)).as("the count row is emitted once").isFalse();
    assertThat(upstream.pulled).as("every upstream row is consumed").isEqualTo(3);
  }

  /** An empty concatenation still emits a row, holding zero — not an empty stream. */
  @Test
  public void count_emptyUpstream_emitsZeroRatherThanNoRow() {
    var counted = PostConcatStreams.count(new ListStub(rows(0)));

    assertThat(counted.hasNext(ctx)).isTrue();
    assertThat(counted.next(ctx).<Long>getProperty("count")).isEqualTo(0L);
  }

  /**
   * The count stream closes its upstream exactly once even though it closes eagerly at the end of
   * the drain and the boundary step closes it again when it releases the arming. {@link
   * ExecutionStream} promises no close idempotency, so a second close would re-enter the whole child
   * plan chain.
   */
  @Test
  public void count_closesUpstreamExactlyOnce_acrossEagerDrainAndExplicitClose() {
    var upstream = new ListStub(rows(2));
    var counted = PostConcatStreams.count(upstream);

    counted.next(ctx);
    counted.close(ctx);
    counted.close(ctx);

    assertThat(upstream.closes).isEqualTo(1);
  }

  /** Pulling a second row after the count row has been taken is a programming error, not an empty. */
  @Test
  public void count_nextAfterTotalTaken_throws() {
    var counted = PostConcatStreams.count(new ListStub(rows(1)));
    counted.next(ctx);

    assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> counted.next(ctx));
  }

  // ---- skip() ----

  /** {@code skip(n)} drops exactly the first n rows and passes the remainder through in order. */
  @Test
  public void skip_dropsLeadingRowsAndPassesTheRestThrough() {
    var all = rows(5);
    var skipped = PostConcatStreams.skip(new ListStub(all), 2);

    var seen = new ArrayList<Result>();
    while (skipped.hasNext(ctx)) {
      seen.add(skipped.next(ctx));
    }

    assertThat(seen).containsExactly(all.get(2), all.get(3), all.get(4));
  }

  /** A skip larger than the concatenation yields nothing rather than throwing or wrapping around. */
  @Test
  public void skip_beyondUpstreamSize_yieldsNothing() {
    var skipped = PostConcatStreams.skip(new ListStub(rows(2)), 5);

    assertThat(skipped.hasNext(ctx)).isFalse();
    assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> skipped.next(ctx));
  }

  /**
   * {@code hasNext} is idempotent: repeated probes must not advance the skip counter past its
   * budget and swallow rows the caller has not seen yet.
   */
  @Test
  public void skip_repeatedHasNext_doesNotConsumeExtraRows() {
    var all = rows(3);
    var skipped = PostConcatStreams.skip(new ListStub(all), 1);

    assertThat(skipped.hasNext(ctx)).isTrue();
    assertThat(skipped.hasNext(ctx)).isTrue();
    assertThat(skipped.next(ctx)).isSameAs(all.get(1));
  }

  // ---- dedup() ----

  /**
   * Dedup keeps the first row per distinct boundary identity across the whole concatenation, so a
   * duplicate contributed by a later union child is dropped even though its child never saw the
   * earlier occurrence.
   */
  @Test
  public void dedup_keepsFirstRowPerIdentityAcrossTheWholeConcatenation() {
    var idA = mock(RID.class);
    var idB = mock(RID.class);
    var a = ridRow(idA);
    var b = ridRow(idB);
    var aAgain = ridRow(idA);
    var deduped = PostConcatStreams.dedup(new ListStub(List.of(a, b, aAgain)), "v");

    var seen = new ArrayList<Result>();
    while (deduped.hasNext(ctx)) {
      seen.add(deduped.next(ctx));
    }

    assertThat(seen).containsExactly(a, b);
  }

  /**
   * The identity comes off the boundary column directly, never through {@code getEntity}, which
   * would load the whole record to read a RID the row already holds. Stubbing only {@code
   * getProperty} pins that: a regression to {@code getEntity} sees a null entity and drops every
   * row.
   */
  @Test
  public void dedup_readsIdentityWithoutLoadingTheRecord() {
    var row = mock(Result.class);
    var identifiable = mock(Identifiable.class);
    lenient().when(identifiable.getIdentity()).thenReturn(mock(RID.class));
    lenient().when(row.getProperty("v")).thenReturn(identifiable);

    var deduped = PostConcatStreams.dedup(new ListStub(List.of(row)), "v");

    assertThat(deduped.hasNext(ctx)).isTrue();
    assertThat(deduped.next(ctx)).isSameAs(row);
  }

  /** A row with no boundary value is dropped rather than treated as one more null-keyed duplicate. */
  @Test
  public void dedup_dropsRowsWithNoBoundaryValue() {
    var empty = mock(Result.class);
    lenient().when(empty.getProperty("v")).thenReturn(null);
    var kept = ridRow(mock(RID.class));

    var deduped = PostConcatStreams.dedup(new ListStub(List.of(empty, kept)), "v");

    var seen = new ArrayList<Result>();
    while (deduped.hasNext(ctx)) {
      seen.add(deduped.next(ctx));
    }
    assertThat(seen).containsExactly(kept);
  }

  // ---- PostConcatOp value semantics ----

  /**
   * A negative skip is rejected at construction. The recogniser declines a negative low before it
   * ever builds a {@link PostConcatOp.Range}, so this guard exists to keep a future caller from
   * quietly producing a stream that skips backwards.
   */
  @Test
  public void range_negativeSkip_isRejectedAtConstruction() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PostConcatOp.Range(-1L, 5L))
        .withMessageContaining("post-concat skip must be >= 0");
  }

  /** A negative limit is the skip-only (unbounded high) encoding and must stay legal. */
  @Test
  public void range_negativeLimit_encodesUnboundedHigh() {
    assertThat(new PostConcatOp.Range(3L, -1L).limit()).isNegative();
  }

  /**
   * Post-union {@code order().by(score)} drains the concatenation, sorts by the ORDER BY item, and
   * closes the upstream once. Pins the {@code SQLOrderByItem.compare(..., nullsDefault)} call that
   * broke the build after the null-placement signature change (#1285).
   */
  @Test
  public void sort_ordersRowsByScoreAscendingAndClosesUpstreamOnce() {
    var session = (DatabaseSessionEmbedded) ctx.getDatabaseSession();
    var low = new ResultInternal(session);
    low.setProperty("score", 1);
    var mid = new ResultInternal(session);
    mid.setProperty("score", 5);
    var high = new ResultInternal(session);
    high.setProperty("score", 9);

    var item = new SQLOrderByItem();
    item.setAlias("score");
    item.setType(SQLOrderByItem.ASC);

    var upstream = new ListStub(List.of(high, low, mid));
    var sorted = PostConcatStreams.sort(upstream, List.of(item));

    var seen = new ArrayList<Result>();
    while (sorted.hasNext(ctx)) {
      seen.add(sorted.next(ctx));
    }
    sorted.close(ctx);

    assertThat(seen).containsExactly(low, mid, high);
    assertThat(upstream.pulled).isEqualTo(3);
    assertThat(upstream.closes).isEqualTo(1);
  }

  /**
   * The push-down shape is exactly one {@code Count} and nothing else: a count preceded or followed
   * by another reduction has to be counted row by row, because the per-child {@code RETURN count(*)}
   * rewrite would count rows the other reduction removes.
   */
  @Test
  public void isPushDownCountOnly_holdsOnlyForALoneCount() {
    assertThat(PostConcatOp.isPushDownCountOnly(List.of(PostConcatOp.Count.INSTANCE))).isTrue();
    assertThat(PostConcatOp.isPushDownCountOnly(List.of())).isFalse();
    assertThat(PostConcatOp.isPushDownCountOnly(List.of(PostConcatOp.Dedup.INSTANCE))).isFalse();
    assertThat(
        PostConcatOp.isPushDownCountOnly(
            List.of(new PostConcatOp.Range(0L, 2L), PostConcatOp.Count.INSTANCE)))
        .isFalse();
    assertThat(
        PostConcatOp.isPushDownCountOnly(
            List.of(PostConcatOp.Count.INSTANCE, PostConcatOp.Count.INSTANCE)))
        .isFalse();
  }

  /**
   * A bounded sort must match the stable full sort by row identity, including a tie at the cutoff.
   * Smaller keys arrive late to force eviction. Long ends must not be narrowed or preallocated.
   */
  @Test
  public void boundedSort_matchesFullSortWithStableTiesAndLongEnds() {
    var input = new ArrayList<Result>();
    for (int i = 39; i >= 0; i--) {
      input.add(scoreRow(i % 4, i));
    }
    for (String direction : List.of(SQLOrderByItem.ASC, SQLOrderByItem.DESC)) {
      for (var keys : List.of(List.of(scoreItem(direction)),
          List.of(scoreItem(direction), keyItem("v", SQLOrderByItem.DESC)))) {
        var full = drain(PostConcatStreams.sort(new ListStub(input), keys));
        for (long end : new long[] {0, 1, 7, 40, 41, 600_000,
            (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
          var upstream = new ListStub(input);
          var bounded = PostConcatStreams.sort(upstream, keys, end);
          var actual = drain(bounded);
          var expected = full.subList(0, (int) Math.min(end, full.size()));
          assertThat(actual).hasSize(expected.size());
          for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i)).isSameAs(expected.get(i));
          }
          bounded.close(ctx);
          bounded.close(ctx);
          assertThat(upstream.closes).isEqualTo(1);
          assertThat(upstream.pulled).isEqualTo(input.size());
          assertThatExceptionOfType(NoSuchElementException.class)
              .isThrownBy(() -> bounded.next(ctx));
        }
      }
    }
    assertThat(drain(PostConcatStreams.sort(new ListStub(List.of()),
        List.of(scoreItem(SQLOrderByItem.ASC)), 5))).isEmpty();
  }

  /**
   * Three tied rows fill the buffer before a better fourth row triggers heap creation and eviction.
   * Buffered rows must keep their arrival ordinals so the earliest two ties survive in input order,
   * matching the stable full sort's first three rows in both ascending and descending order.
   */
  @Test
  public void boundedSort_preservesBufferedTieOrdinalsWhenOverflowCreatesHeap() {
    for (String direction : List.of(SQLOrderByItem.ASC, SQLOrderByItem.DESC)) {
      int betterScore = direction.equals(SQLOrderByItem.ASC) ? 0 : 2;
      var input = List.of(scoreRow(1, 0), scoreRow(1, 1), scoreRow(1, 2),
          scoreRow(betterScore, 3));
      var keys = List.of(scoreItem(direction));
      var expected = drain(PostConcatStreams.sort(new ListStub(input), keys)).subList(0, 3);
      assertThat(expected).extracting(row -> row.<Integer>getProperty("v"))
          .containsExactly(3, 0, 1);

      var actual = drain(PostConcatStreams.sort(new ListStub(input), keys, 3));
      assertThat(actual).hasSize(expected.size());
      for (int i = 0; i < expected.size(); i++) {
        assertThat(actual.get(i)).as("%s row %s after buffer-to-heap transfer", direction, i)
            .isSameAs(expected.get(i));
      }
    }
  }

  /**
   * Inspect both row buffers during input, after materialization, and throughout replay. A heap
   * left populated after transfer must fail even though it returns the correct output rows.
   */
  @Test
  public void boundedSort_retainsAtMostEndRowsThroughoutDrainAndReplay() {
    for (long end : new long[] {0, 1, 13, Long.MAX_VALUE}) {
      var input = new ArrayList<Result>();
      for (int i = 99; i >= 0; i--) {
        input.add(scoreRow(i, i));
      }
      var upstream = new ListStub(input);
      var sorted = PostConcatStreams.sort(upstream, List.of(scoreItem(SQLOrderByItem.ASC)), end);
      int[] peak = {0};
      Runnable probe = () -> {
        int retained = retainedRows(sorted);
        assertThat((long) retained).as("retained rows across drain and replay")
            .isLessThanOrEqualTo(end);
        assertThat(retained).isLessThanOrEqualTo(upstream.pulled);
        peak[0] = Math.max(peak[0], retained);
      };
      upstream.probe = probe;
      int emitted = 0;
      try {
        while (sorted.hasNext(ctx)) {
          probe.run();
          assertThat(sortBuffer(sorted, "heap")).as("heap released before replay").isNull();
          sorted.next(ctx);
          emitted++;
          probe.run();
        }
        probe.run();
        assertThat(emitted).isEqualTo((int) Math.min(end, input.size()));
        assertThat(peak[0]).isEqualTo(emitted);
      } finally {
        sorted.close(ctx);
      }
      assertThat(retainedRows(sorted)).isZero();
      assertThat(upstream.closes).isEqualTo(1);
    }
  }

  /**
   * A huge end over three rows must not reserve memory from end. Observe actual backing arrays,
   * not collection sizes, so a capped eager allocation of 600000 slots fails without an OOM.
   */
  @Test
  public void boundedSort_growsBackingArraysOnlyWithActualInput() {
    for (long end : new long[] {600_000, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
      var upstream = new ListStub(List.of(scoreRow(3, 3), scoreRow(1, 1), scoreRow(2, 2)));
      var sorted = PostConcatStreams.sort(upstream, List.of(scoreItem(SQLOrderByItem.ASC)), end);
      Runnable probe = () -> {
        assertThat(sortBuffer(sorted, "heap")).as("no heap until input exceeds end").isNull();
        var buffer = sortBuffer(sorted, "sorted");
        assertThat(((Object[]) fieldValue(buffer, ArrayList.class, "elementData")).length)
            .as("buffer capacity depends on actual input, not end").isLessThanOrEqualTo(10);
      };
      upstream.probe = probe;
      probe.run();
      try {
        assertThat(sorted.hasNext(ctx)).isTrue();
        probe.run();
        assertThat(sortBuffer(sorted, "sorted")).hasSize(3);
      } finally {
        sorted.close(ctx);
      }
    }
  }

  /**
   * Inputs that fit the end use exactly the stable full sort's comparisons. Sorted inputs retain
   * TimSort's linear comparison count instead of paying for heap insertion and extraction.
   */
  @Test
  public void boundedSort_inputsWithinEndKeepFullSortComparisonCounts() {
    var ascending = new ArrayList<Result>();
    for (int i = 0; i < 1024; i++) {
      ascending.add(scoreRow(i, i));
    }
    var descending = new ArrayList<>(ascending);
    Collections.reverse(descending);
    var shuffled = new ArrayList<>(ascending);
    Collections.shuffle(shuffled, new Random(1419));
    for (var input : List.of(ascending, descending, shuffled)) {
      var key = new CountingOrderByItem();
      var full = drain(PostConcatStreams.sort(new ListStub(input), List.of(key)));
      int fullComparisons = key.comparisons;
      if (input != shuffled) {
        assertThat(fullComparisons).isEqualTo(input.size() - 1);
      }
      for (long end : new long[] {input.size(), input.size() + 1, Long.MAX_VALUE}) {
        key.comparisons = 0;
        assertThat(drain(PostConcatStreams.sort(new ListStub(input), List.of(key), end)))
            .containsExactlyElementsOf(full);
        assertThat(key.comparisons).as("comparison count for end %s", end)
            .isEqualTo(fullComparisons);
      }
    }
  }

  /**
   * The assembled pipeline, not just the standalone sorter, must retain at most the range end.
   * This fails if assembly silently uses the full sort or forgets to move a following dedup.
   */
  @Test
  public void pipeline_appliesTheBoundToTheSortWithEitherDedupPosition() throws Exception {
    for (boolean dedupFirst : List.of(false, true)) {
      var input = new ArrayList<Result>();
      for (int i = 49; i >= 0; i--) {
        input.add(scoreRow(i, i));
        input.add(scoreRow(i, i));
      }
      var ops = new ArrayList<PostConcatOp>();
      if (dedupFirst) {
        ops.add(PostConcatOp.Dedup.INSTANCE);
      }
      ops.add(new PostConcatOp.Order(List.of(scoreItem(SQLOrderByItem.ASC))));
      if (!dedupFirst) {
        ops.add(PostConcatOp.Dedup.INSTANCE);
      }
      ops.add(new PostConcatOp.Range(1, 2));
      var upstream = new ListStub(input);
      var pipeline = PostConcatStreams.apply(upstream, ops, "v");
      var sort = pipeline;
      while (!sort.getClass().getSimpleName().equals("SortStream")) {
        var field = sort.getClass().getDeclaredField("upstream");
        field.setAccessible(true);
        sort = (ExecutionStream) field.get(sort);
      }
      var sorter = sort;
      int[] peak = {0};
      upstream.probe = () -> {
        int retained = retainedRows(sorter);
        assertThat(retained).as("assembly must select the bounded sort").isLessThanOrEqualTo(3);
        var heap = sortBuffer(sorter, "heap");
        if (heap != null) {
          assertThat(((Object[]) fieldValue(heap, PriorityQueue.class, "queue")).length)
              .as("heap grows with the three retained rows").isLessThanOrEqualTo(4);
        }
        peak[0] = Math.max(peak[0], retained);
      };
      assertThat(drain(pipeline)).extracting(row -> row.<Integer>getProperty("v"))
          .containsExactly(1, 2);
      assertThat(peak[0]).isEqualTo(3);
    }
  }

  /**
   * Cross-branch duplicates must not exhaust a distinct top-N budget. Both dedup positions give
   * exactly the stable full-sort/dedup/range result, and count observes that same sliced stream.
   */
  @Test
  public void pipeline_dedupOnEitherSideOfOrderBoundsDistinctRowsBeforeRangeAndCount() {
    var input = List.of(scoreRow(1, 1), scoreRow(1, 1), scoreRow(2, 2),
        scoreRow(2, 2), scoreRow(3, 3), scoreRow(4, 4));
    var keys = List.of(scoreItem(SQLOrderByItem.ASC));
    var full = PostConcatStreams.dedup(
        PostConcatStreams.sort(new ListStub(input), keys), "v");
    var expected = drain(PostConcatStreams.skip(full, 1).limit(2));
    assertThat(expected.stream().map(row -> row.<Integer>getProperty("v")))
        .containsExactly(2, 3);
    for (boolean dedupFirst : List.of(false, true)) {
      var ops = new ArrayList<PostConcatOp>();
      if (dedupFirst) {
        ops.add(PostConcatOp.Dedup.INSTANCE);
      }
      ops.add(new PostConcatOp.Order(keys));
      if (!dedupFirst) {
        ops.add(PostConcatOp.Dedup.INSTANCE);
      }
      ops.add(new PostConcatOp.Range(1, 2));
      assertThat(drain(PostConcatStreams.apply(new ListStub(input), ops, "v")))
          .containsExactlyElementsOf(expected);
      ops.add(PostConcatOp.Count.INSTANCE);
      var counted = drain(PostConcatStreams.apply(new ListStub(input), ops, "v"));
      assertThat(counted).hasSize(1);
      assertThat(counted.getFirst().<Long>getProperty("count")).isEqualTo(2L);
    }
  }

  /**
   * Skip-only slices retain the full sort, with or without dedup. Count and an intervening slice
   * remain barriers, so a later ended slice must not truncate an earlier sort incorrectly.
   */
  @Test
  public void pipeline_keepsUnboundedSortsAndDoesNotBoundAcrossBarriers() {
    var input = List.of(scoreRow(3, 3), scoreRow(1, 1), scoreRow(1, 1), scoreRow(2, 2));
    var order = new PostConcatOp.Order(List.of(scoreItem(SQLOrderByItem.ASC)));
    var ops = List.<PostConcatOp>of(order, new PostConcatOp.Range(1, -1));
    assertThat(drain(PostConcatStreams.apply(new ListStub(input), ops, "v")))
        .extracting(row -> row.<Integer>getProperty("score")).containsExactly(1, 2, 3);
    ops = List.of(order, PostConcatOp.Dedup.INSTANCE, new PostConcatOp.Range(1, -1));
    assertThat(drain(PostConcatStreams.apply(new ListStub(input), ops, "v")))
        .extracting(row -> row.<Integer>getProperty("score")).containsExactly(2, 3);
    ops = List.of(order, PostConcatOp.Count.INSTANCE, new PostConcatOp.Range(0, 1));
    assertThat(drain(PostConcatStreams.apply(new ListStub(input), ops, "v"))
        .getFirst().<Long>getProperty("count")).isEqualTo(4L);
    assertThat(drain(PostConcatStreams.apply(new ListStub(input), List.of(order), "v")))
        .hasSize(4);
    assertThat(drain(PostConcatStreams.apply(new ListStub(input),
        List.of(order, PostConcatOp.Dedup.INSTANCE), "v"))).hasSize(3);
  }

  /** Empty slices still drain the sort, and overflowing bounds saturate instead of wrapping. */
  @Test
  public void pipeline_zeroAndOverflowingEndsPreserveDrainingAndSaturate() {
    var input = List.of(scoreRow(2, 2), scoreRow(1, 1));
    var order = new PostConcatOp.Order(List.of(scoreItem(SQLOrderByItem.ASC)));
    for (long skip : new long[] {0, 600_000}) {
      var upstream = new ListStub(input);
      assertThat(drain(PostConcatStreams.apply(upstream,
          List.of(order, new PostConcatOp.Range(skip, 0)), "v"))).isEmpty();
      assertThat(upstream.pulled).isEqualTo(input.size());
      assertThat(upstream.closes).isEqualTo(1);
    }
    assertThat(drain(PostConcatStreams.apply(new ListStub(input),
        List.of(order, new PostConcatOp.Range(1, Long.MAX_VALUE)), "v")))
        .containsExactly(input.getFirst());
  }

  /** A zero end retains no rows but still surfaces a failure after reading an upstream row. */
  @Test
  public void boundedSort_zeroEndPropagatesUpstreamFailureAndClosesOnce() {
    for (boolean pipeline : List.of(false, true)) {
      var upstream = new ListStub(List.of(scoreRow(1, 1)));
      var failure = new IllegalStateException("branch failed");
      upstream.probe = () -> {
        if (upstream.pulled == 1) {
          throw failure;
        }
      };
      var keys = List.of(scoreItem(SQLOrderByItem.ASC));
      var sorted = pipeline
          ? PostConcatStreams.apply(upstream,
              List.of(new PostConcatOp.Order(keys), new PostConcatOp.Range(0, 0)), "v")
          : PostConcatStreams.sort(upstream, keys, 0);
      try {
        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> sorted.hasNext(ctx)).isSameAs(failure);
        assertThat(retainedRows(sorted)).isZero();
      } finally {
        sorted.close(ctx);
        sorted.close(ctx);
      }
      assertThat(upstream.pulled).isEqualTo(1);
      assertThat(upstream.closes).isEqualTo(1);
    }
  }

  /** Both sort paths keep upstream cancellation checks and close the upstream once on failure. */
  @Test
  public void boundedSort_preservesCancellationAndClosesUpstreamOnFailure() {
    for (long end : new long[] {-1, 0, 2}) {
      var upstream = new ListStub(List.of(scoreRow(1, 1)));
      var sorted = PostConcatStreams.sort(upstream.interruptable(),
          List.of(scoreItem(SQLOrderByItem.ASC)), end);
      try (var interruption = mockStatic(ExecutionThreadLocal.class)) {
        interruption.when(ExecutionThreadLocal::isInterruptCurrentOperation)
            .thenReturn(false, false, true);
        assertThatExceptionOfType(CommandInterruptedException.class)
            .isThrownBy(() -> sorted.hasNext(ctx));
      } finally {
        sorted.close(ctx);
      }
      assertThat(upstream.closes).isEqualTo(1);
    }
  }

  // ---- helpers ----

  /** Centralize the white-box observation needed to distinguish retention from equal output. */
  private static Collection<?> sortBuffer(ExecutionStream stream, String name) {
    return (Collection<?>) fieldValue(stream, stream.getClass(), name);
  }

  private static int retainedRows(ExecutionStream stream) {
    var heap = sortBuffer(stream, "heap");
    return (heap == null ? 0 : heap.size()) + sortBuffer(stream, "sorted").size();
  }

  private static Object fieldValue(Object target, Class<?> owner, String name) {
    try {
      var field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** Count real SQL key comparisons without replacing the sort implementation. */
  private static final class CountingOrderByItem extends SQLOrderByItem {

    private int comparisons;

    CountingOrderByItem() {
      setAlias("score");
      setType(ASC);
    }

    @Override
    public int compare(Result a, Result b, CommandContext context,
        ResolvedOrderByNullsPlacement nullsDefault) {
      comparisons++;
      return super.compare(a, b, context, nullsDefault);
    }
  }

  private List<Result> drain(ExecutionStream stream) {
    var results = new ArrayList<Result>();
    try {
      while (stream.hasNext(ctx)) {
        results.add(stream.next(ctx));
      }
      return results;
    } finally {
      stream.close(ctx);
    }
  }

  private Result scoreRow(int score, int identity) {
    var result = new ResultInternal((DatabaseSessionEmbedded) ctx.getDatabaseSession());
    result.setProperty("score", score);
    result.setProperty("v", identity);
    return result;
  }

  private static SQLOrderByItem scoreItem(String direction) {
    return keyItem("score", direction);
  }

  private static SQLOrderByItem keyItem(String key, String direction) {
    var item = new SQLOrderByItem();
    item.setAlias(key);
    item.setType(direction);
    return item;
  }

  private static List<Result> rows(int count) {
    var list = new ArrayList<Result>(count);
    for (int i = 0; i < count; i++) {
      list.add(mock(Result.class));
    }
    return List.copyOf(list);
  }

  /**
   * A row whose boundary column holds an {@link Identifiable} resolving to {@code identity}. Two
   * rows built from the same RID instance are duplicates; the dedup set keys on the RID, so distinct
   * instances are distinct rows.
   */
  private static Result ridRow(RID identity) {
    var identifiable = mock(Identifiable.class);
    lenient().when(identifiable.getIdentity()).thenReturn(identity);
    var row = mock(Result.class);
    lenient().when(row.getProperty("v")).thenReturn(identifiable);
    return row;
  }

  /** A real {@link ExecutionStream} over a fixed row list that counts pulls and closes. */
  private static final class ListStub implements ExecutionStream {

    private final List<Result> rows;
    private int pos;
    private int pulled;
    private int closes;
    private Runnable probe = () -> {
    };

    ListStub(List<Result> rows) {
      this.rows = rows;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      probe.run();
      return pos < rows.size();
    }

    @Override
    public Result next(CommandContext ctx) {
      if (!hasNext(ctx)) {
        throw new NoSuchElementException();
      }
      pulled++;
      return rows.get(pos++);
    }

    @Override
    public void close(CommandContext ctx) {
      closes++;
    }
  }
}
