package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link PostConcatStreams}, the decorators that realise a recognised {@code
 * count()} / {@code skip()} / {@code limit()} / {@code dedup()} suffix over a union's concatenated
 * child streams.
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

  // ---- helpers ----

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

    ListStub(List<Result> rows) {
      this.rows = rows;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
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
