package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Direct-step coverage for the small {@code sql/executor/resultset/*} execution-stream wrappers
 * (Filter/Mapper/FlatMap/Limited/Iterator/ResultIterator/Singleton/Empty/Produce/OnClose/Multiple/
 * CostMeasure) and the default + static factory methods on {@link ExecutionStream}.
 *
 * <p>Extends {@link DbTestBase} because two of the wrappers touch a session on each call:
 * {@link IteratorExecutionStream} materializes primitives via
 * {@link ResultInternal#toResult(Object, com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded, String)},
 * which in turn calls {@link CommandContext#getDatabaseSession()} — that method throws when no
 * session is attached. We therefore bind a real session into every {@link BasicCommandContext}.
 *
 * <p>See {@link ExecutionResultSetTest} and {@link LoaderExecutionStreamTest} for the wrappers
 * that do more than propagate hasNext/next between layers.
 */
public class ExecutionStreamWrappersTest extends DbTestBase {

  private BasicCommandContext newContext() {
    return new BasicCommandContext(session);
  }

  // =========================================================================
  // EmptyExecutionStream
  // =========================================================================

  /**
   * EmptyExecutionStream reports no elements ({@code hasNext==false}), throws
   * {@link IllegalStateException} on {@code next()}, and is a no-op on close.
   */
  @Test
  public void emptyStreamReportsNoElementsAndThrowsOnNext() {
    var ctx = newContext();
    var stream = ExecutionStream.empty();

    assertThat(stream.hasNext(ctx)).isFalse();
    assertThatThrownBy(() -> stream.next(ctx)).isInstanceOf(IllegalStateException.class);
    // close is a no-op: does not throw.
    stream.close(ctx);
  }

  /**
   * The {@code empty()} factory always returns the same singleton instance so that
   * identity checks in planners (which sometimes short-circuit on the known empty sentinel)
   * remain valid.
   */
  @Test
  public void emptyFactoryReturnsSingletonSentinel() {
    assertThat(ExecutionStream.empty()).isSameAs(ExecutionStream.empty());
  }

  // =========================================================================
  // SingletonExecutionStream
  // =========================================================================

  /**
   * SingletonExecutionStream yields its single result exactly once; a second {@code next()}
   * call throws {@link IllegalStateException}.
   */
  @Test
  public void singletonYieldsResultExactlyOnce() {
    var ctx = newContext();
    var result = new ResultInternal(
        (com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded) null);
    result.setProperty("x", 1);
    var stream = ExecutionStream.singleton(result);

    assertThat(stream.hasNext(ctx)).isTrue();
    assertThat(stream.next(ctx)).isSameAs(result);
    assertThat(stream.hasNext(ctx)).isFalse();
    assertThatThrownBy(() -> stream.next(ctx)).isInstanceOf(IllegalStateException.class);
    stream.close(ctx);
  }

  // =========================================================================
  // IteratorExecutionStream (primitives → ResultInternal{value=...})
  // =========================================================================

  /**
   * IteratorExecutionStream wraps non-Result values into {@code ResultInternal} and stores
   * them under the supplied alias. The default alias via {@code iterator(it)} is null, which
   * makes {@link ResultInternal#toResult} use {@code "value"} as the property key.
   */
  @Test
  public void iteratorStreamWrapsPrimitivesUsingDefaultValueKey() {
    var ctx = newContext();
    var stream = ExecutionStream.iterator(Arrays.asList(1, 2, 3).iterator());

    var results = drain(stream, ctx);
    assertThat(results).hasSize(3);
    assertThat(results.get(0).<Integer>getProperty("value")).isEqualTo(1);
    assertThat(results.get(1).<Integer>getProperty("value")).isEqualTo(2);
    assertThat(results.get(2).<Integer>getProperty("value")).isEqualTo(3);
  }

  /**
   * An explicit alias replaces the default "value" property name for primitives.
   */
  @Test
  public void iteratorStreamUsesExplicitAliasForPrimitives() {
    var ctx = newContext();
    var stream = ExecutionStream.iterator(Arrays.asList("a", "b").iterator(), "letter");

    var results = drain(stream, ctx);
    assertThat(results.get(0).<String>getProperty("letter")).isEqualTo("a");
    assertThat(results.get(0).<Object>getProperty("value")).isNull();
    assertThat(results.get(1).<String>getProperty("letter")).isEqualTo("b");
  }

  /**
   * An empty underlying iterator produces an empty stream and {@code close()} is a no-op.
   */
  @Test
  public void iteratorStreamWithEmptyIteratorProducesNothing() {
    var ctx = newContext();
    var stream = ExecutionStream.iterator(Collections.emptyIterator());

    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  // =========================================================================
  // ResultIteratorExecutionStream (pre-built Result iterator)
  // =========================================================================

  /**
   * ResultIteratorExecutionStream forwards existing {@link Result} instances without wrapping
   * them again. Close is a no-op so repeated closes are safe.
   */
  @Test
  public void resultIteratorStreamForwardsResultsIdentity() {
    var ctx = newContext();
    var r1 = new ResultInternal(null);
    var r2 = new ResultInternal(null);
    var stream = ExecutionStream.resultIterator(Arrays.asList((Result) r1, r2).iterator());

    assertThat(stream.hasNext(ctx)).isTrue();
    assertThat(stream.next(ctx)).isSameAs(r1);
    assertThat(stream.hasNext(ctx)).isTrue();
    assertThat(stream.next(ctx)).isSameAs(r2);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
    stream.close(ctx); // idempotent no-op
  }

  // =========================================================================
  // FilterExecutionStream
  // =========================================================================

  /**
   * FilterExecutionStream drains the upstream, keeps results for which the filter
   * returns a non-null (potentially different) value, and skips those for which it
   * returns null.
   */
  @Test
  public void filterKeepsNonNullResultsAndSkipsNullMappedOnes() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2, 3, 4);

    var filtered = upstream.filter((r, c) -> {
      int v = r.<Integer>getProperty("value");
      return v % 2 == 0 ? r : null;
    });

    var results = drain(filtered, ctx);
    assertThat(results.stream().map(r -> (int) r.<Integer>getProperty("value")))
        .containsExactly(2, 4);
  }

  /**
   * The filter may swap in a different result; it is a filter-map, not just a predicate.
   */
  @Test
  public void filterCanReplaceResult() {
    var ctx = newContext();
    var upstream = streamOfInts(10);
    var replacement = new ResultInternal(null);
    replacement.setProperty("value", 99);
    var filtered = upstream.filter((r, c) -> replacement);

    assertThat(drain(filtered, ctx)).singleElement().isSameAs(replacement);
  }

  /**
   * When the filter rejects every upstream item the stream is empty and {@code next()}
   * throws {@link IllegalStateException} per the wrapper contract.
   */
  @Test
  public void filterRejectingEverythingProducesEmptyAndNextThrows() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2, 3);
    var filtered = upstream.filter((r, c) -> null);

    assertThat(filtered.hasNext(ctx)).isFalse();
    assertThatThrownBy(() -> filtered.next(ctx)).isInstanceOf(IllegalStateException.class);
  }

  /**
   * Closing a FilterExecutionStream closes its upstream exactly once. We verify via a
   * {@code CloseTracker} stream.
   */
  @Test
  public void filterCloseDelegatesToUpstream() {
    var ctx = newContext();
    var upstream = new CloseTracker(streamOfInts(1));
    var filtered = upstream.filter((r, c) -> r);

    filtered.close(ctx);
    assertThat(upstream.closeCount()).isEqualTo(1);
  }

  // =========================================================================
  // MapperExecutionStream
  // =========================================================================

  /**
   * MapperExecutionStream applies the mapper to every element and preserves count.
   */
  @Test
  public void mapperTransformsEveryElement() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2, 3);
    var mapped = upstream.map((r, c) -> {
      var copy = new ResultInternal(null);
      copy.setProperty("value", (int) r.<Integer>getProperty("value") * 10);
      return copy;
    });

    var results = drain(mapped, ctx);
    assertThat(results.stream().map(r -> (int) r.<Integer>getProperty("value")))
        .containsExactly(10, 20, 30);
  }

  /**
   * Constructing a MapperExecutionStream with either null upstream or null mapper is a
   * programming error that throws {@link NullPointerException}.
   */
  @Test
  public void mapperConstructorRejectsNulls() {
    assertThatThrownBy(() -> new MapperExecutionStream(null, (r, c) -> r))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MapperExecutionStream(streamOfInts(1), null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * Closing a MapperExecutionStream closes its upstream.
   */
  @Test
  public void mapperCloseDelegatesToUpstream() {
    var ctx = newContext();
    var upstream = new CloseTracker(streamOfInts(1));
    var mapped = upstream.map((r, c) -> r);

    mapped.close(ctx);
    assertThat(upstream.closeCount()).isEqualTo(1);
  }

  // =========================================================================
  // FlatMapExecutionStream
  // =========================================================================

  /**
   * FlatMapExecutionStream fans each upstream result into a downstream {@link ExecutionStream}
   * produced by the mapping function, preserving order within each sub-stream and across the
   * upstream.
   */
  @Test
  public void flatMapConcatenatesSubStreamsInOrder() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2, 3);

    var flat = upstream.flatMap((next, c) -> {
      int v = next.<Integer>getProperty("value");
      return streamOfInts(v, v + 10);
    });

    var results = drain(flat, ctx);
    assertThat(results.stream().map(r -> (int) r.<Integer>getProperty("value")))
        .containsExactly(1, 11, 2, 12, 3, 13);
  }

  /**
   * Empty sub-streams are skipped transparently; the top-level {@code hasNext} advances to
   * the next upstream element until a non-empty sub-stream is found.
   */
  @Test
  public void flatMapSkipsEmptySubStreams() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2, 3, 4);

    var flat = upstream.flatMap((next, c) -> {
      int v = next.<Integer>getProperty("value");
      return v % 2 == 0 ? streamOfInts(v) : ExecutionStream.empty();
    });

    var results = drain(flat, ctx);
    assertThat(results.stream().map(r -> (int) r.<Integer>getProperty("value")))
        .containsExactly(2, 4);
  }

  /**
   * Calling {@code next()} on an exhausted FlatMap throws {@link IllegalStateException}.
   */
  @Test
  public void flatMapNextOnExhaustedStreamThrows() {
    var ctx = newContext();
    var flat = streamOfInts().flatMap((next, c) -> ExecutionStream.empty());

    assertThat(flat.hasNext(ctx)).isFalse();
    assertThatThrownBy(() -> flat.next(ctx)).isInstanceOf(IllegalStateException.class);
  }

  /**
   * Closing a FlatMap closes the currently-open sub-stream AND the base upstream. If
   * flatMap has not yet been advanced to a sub-stream, only the base is closed (no NPE).
   */
  @Test
  public void flatMapCloseCascadesToCurrentSubStreamAndBase() {
    var ctx = newContext();
    var base = new CloseTracker(streamOfInts(1, 2));
    var sub1 = new CloseTracker(streamOfInts(10));
    var flat = base.flatMap((next, c) -> sub1);

    // Advance once so the flat map grabs sub1 as currentResultSet.
    assertThat(flat.hasNext(ctx)).isTrue();
    assertThat(flat.next(ctx).<Integer>getProperty("value")).isEqualTo(10);
    flat.close(ctx);

    assertThat(sub1.closeCount()).isGreaterThanOrEqualTo(1);
    assertThat(base.closeCount()).isEqualTo(1);
  }

  /**
   * Closing a FlatMap that has not been advanced yet closes only the base upstream; no
   * currentResultSet exists to close.
   */
  @Test
  public void flatMapCloseBeforeAdvanceClosesOnlyBase() {
    var ctx = newContext();
    var base = new CloseTracker(streamOfInts(1, 2));
    var flat = base.flatMap((next, c) -> ExecutionStream.empty());

    flat.close(ctx);
    assertThat(base.closeCount()).isEqualTo(1);
  }

  // =========================================================================
  // LimitedExecutionStream
  // =========================================================================

  /**
   * LimitedExecutionStream caps the number of delivered results.
   */
  @Test
  public void limitEnforcesCap() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2, 3, 4, 5);
    var limited = upstream.limit(2);

    var results = drain(limited, ctx);
    assertThat(results.stream().map(r -> (int) r.<Integer>getProperty("value")))
        .containsExactly(1, 2);
  }

  /**
   * When the cap is reached {@code hasNext} returns false without asking the upstream and
   * {@code next} throws {@link IllegalStateException}.
   */
  @Test
  public void limitNextThrowsOnceCapReached() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2, 3);
    var limited = upstream.limit(1);

    assertThat(limited.next(ctx).<Integer>getProperty("value")).isEqualTo(1);
    assertThat(limited.hasNext(ctx)).isFalse();
    assertThatThrownBy(() -> limited.next(ctx)).isInstanceOf(IllegalStateException.class);
  }

  /**
   * A zero limit yields an empty stream even when the upstream has elements.
   */
  @Test
  public void limitZeroProducesEmpty() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2);
    var limited = upstream.limit(0);

    assertThat(limited.hasNext(ctx)).isFalse();
    assertThatThrownBy(() -> limited.next(ctx)).isInstanceOf(IllegalStateException.class);
  }

  /**
   * A limit higher than the upstream size is harmless; the stream yields everything and
   * terminates normally.
   */
  @Test
  public void limitLargerThanUpstreamIsHarmless() {
    var ctx = newContext();
    var upstream = streamOfInts(1, 2);
    var limited = upstream.limit(10);

    assertThat(drain(limited, ctx)).hasSize(2);
  }

  /**
   * Closing a LimitedExecutionStream closes its upstream.
   */
  @Test
  public void limitCloseDelegatesToUpstream() {
    var ctx = newContext();
    var upstream = new CloseTracker(streamOfInts(1, 2));
    var limited = upstream.limit(1);

    limited.close(ctx);
    assertThat(upstream.closeCount()).isEqualTo(1);
  }

  // =========================================================================
  // ProduceExecutionStream
  // =========================================================================

  /**
   * ProduceExecutionStream always reports {@code hasNext==true} and invokes the producer
   * on each {@code next()} call.
   */
  @Test
  public void produceAlwaysHasNextAndCallsProducer() {
    var ctx = newContext();
    var n = new AtomicInteger();
    var stream = new ProduceExecutionStream(c -> {
      var r = new ResultInternal(null);
      r.setProperty("value", n.getAndIncrement());
      return r;
    });

    assertThat(stream.hasNext(ctx)).isTrue();
    assertThat(stream.next(ctx).<Integer>getProperty("value")).isEqualTo(0);
    assertThat(stream.next(ctx).<Integer>getProperty("value")).isEqualTo(1);
    // Repeated hasNext without advancing is still true.
    assertThat(stream.hasNext(ctx)).isTrue();
    stream.close(ctx);
  }

  /**
   * Constructing a ProduceExecutionStream with a null producer is a programming error.
   */
  @Test
  public void produceConstructorRejectsNullProducer() {
    assertThatThrownBy(() -> new ProduceExecutionStream(null))
        .isInstanceOf(NullPointerException.class);
  }

  // =========================================================================
  // OnCloseExecutionStream
  // =========================================================================

  /**
   * {@code onClose} wraps an existing stream and runs the supplied callback exactly once
   * before delegating close to the underlying source.
   */
  @Test
  public void onCloseRunsCallbackBeforeSourceClose() {
    var ctx = newContext();
    var callbackInvoked = new AtomicBoolean(false);
    var source = new CloseTracker(streamOfInts(1));
    var onClose = source.onClose(c -> callbackInvoked.set(true));

    // hasNext/next pass-through
    assertThat(onClose.hasNext(ctx)).isTrue();
    assertThat(onClose.next(ctx).<Integer>getProperty("value")).isEqualTo(1);

    onClose.close(ctx);
    assertThat(callbackInvoked.get()).isTrue();
    assertThat(source.closeCount()).isEqualTo(1);
  }

  // =========================================================================
  // MultipleExecutionStream
  // =========================================================================

  /**
   * MultipleExecutionStream concatenates the sub-streams produced by its
   * {@link ExecutionStreamProducer}. Sub-streams that are empty are skipped transparently.
   */
  @Test
  public void multipleStreamConcatenatesProducerOutput() {
    var ctx = newContext();
    var subStreams = new ArrayList<ExecutionStream>(List.of(
        streamOfInts(1, 2),
        ExecutionStream.empty(),
        streamOfInts(3)));
    var producer = producerOf(subStreams);

    var stream = new MultipleExecutionStream(producer);
    var results = drain(stream, ctx);
    assertThat(results.stream().map(r -> (int) r.<Integer>getProperty("value")))
        .containsExactly(1, 2, 3);
  }

  /**
   * When the producer is empty, the stream is empty; {@code next()} on an exhausted
   * MultipleExecutionStream throws {@link IllegalStateException}.
   */
  @Test
  public void multipleStreamOnEmptyProducerHasNoNext() {
    var ctx = newContext();
    var producer = producerOf(new ArrayList<>());

    var stream = new MultipleExecutionStream(producer);
    assertThat(stream.hasNext(ctx)).isFalse();
    assertThatThrownBy(() -> stream.next(ctx)).isInstanceOf(IllegalStateException.class);
  }

  /**
   * Closing a MultipleExecutionStream closes the current sub-stream (if any) and the producer.
   */
  @Test
  public void multipleStreamCloseCascadesCurrentAndProducer() {
    var ctx = newContext();
    var sub = new CloseTracker(streamOfInts(1, 2));
    var producer = producerOf(new ArrayList<>(List.of((ExecutionStream) sub)));
    var stream = new MultipleExecutionStream(producer);

    assertThat(stream.hasNext(ctx)).isTrue();
    stream.close(ctx);
    // Current sub-stream closed at least once (hasNext may also close on drain).
    assertThat(sub.closeCount()).isGreaterThanOrEqualTo(1);
    assertThat(producer.closed).isTrue();
  }

  /**
   * Closing a MultipleExecutionStream that has not advanced yet closes only the producer
   * (currentStream is null and must not NPE).
   */
  @Test
  public void multipleStreamCloseBeforeAdvanceClosesProducerOnly() {
    var ctx = newContext();
    var producer = producerOf(new ArrayList<>(List.of(streamOfInts(1))));
    var stream = new MultipleExecutionStream(producer);

    stream.close(ctx);
    assertThat(producer.closed).isTrue();
  }

  // =========================================================================
  // CostMeasureExecutionStream (ExecutionStream#profile)
  // =========================================================================

  /**
   * CostMeasureExecutionStream delegates hasNext/next/close and accumulates elapsed nanos
   * (monotonically non-decreasing) into its {@code cost} counter for each invocation.
   */
  @Test
  public void costMeasureAccumulatesCostAndDelegatesDelivery() {
    var ctx = newContext();
    var step = new NoOpStep();
    var source = new CloseTracker(streamOfInts(1, 2));
    var profiled = source.profile(step);

    // Initial cost is 0.
    assertThat(profiled.getCost()).isZero();

    assertThat(profiled.hasNext(ctx)).isTrue();
    var costAfterHasNext = profiled.getCost();
    assertThat(costAfterHasNext).isGreaterThanOrEqualTo(0);

    assertThat(profiled.next(ctx).<Integer>getProperty("value")).isEqualTo(1);
    var costAfterNext = profiled.getCost();
    assertThat(costAfterNext).isGreaterThanOrEqualTo(costAfterHasNext);

    profiled.close(ctx);
    assertThat(source.closeCount()).isEqualTo(1);
  }

  // =========================================================================
  // ExecutionStream default methods — ensure each delegates to the right wrapper type
  // =========================================================================

  /**
   * The {@code interruptable()} default returns an {@link InterruptResultSet} that, on a
   * non-{@code SoftThread} (the JUnit test thread), simply forwards hasNext/next/close to
   * the source.
   */
  @Test
  public void interruptableOnNormalThreadDelegatesToSource() {
    var ctx = newContext();
    var source = new CloseTracker(streamOfInts(1));
    var wrapped = source.interruptable();

    assertThat(wrapped).isInstanceOf(InterruptResultSet.class);
    assertThat(wrapped.hasNext(ctx)).isTrue();
    assertThat(wrapped.next(ctx).<Integer>getProperty("value")).isEqualTo(1);
    assertThat(wrapped.hasNext(ctx)).isFalse();
    wrapped.close(ctx);
    assertThat(source.closeCount()).isEqualTo(1);
  }

  /**
   * The {@code stream(ctx)} default exposes the ExecutionStream as a JDK {@link
   * java.util.stream.Stream} whose terminal operation closes the underlying wrapper via
   * {@code Stream#close}.
   */
  @Test
  public void streamAdapterCollectsResultsAndClosesOnTerminate() {
    var ctx = newContext();
    var source = new CloseTracker(streamOfInts(1, 2, 3));

    List<Integer> collected;
    try (var javaStream = source.stream(ctx)) {
      collected = javaStream.map(r -> (int) r.<Integer>getProperty("value"))
          .collect(Collectors.toList());
    }
    assertThat(collected).containsExactly(1, 2, 3);
    // try-with-resources invokes onClose → source.close(ctx).
    assertThat(source.closeCount()).isEqualTo(1);
  }

  /**
   * The {@code stream(ctx)} spliterator has estimateSize Long.MAX_VALUE, characteristics 0,
   * and cannot split. These properties reflect the pull-based, unknown-size nature of the
   * execution pipeline.
   */
  @Test
  public void streamAdapterSpliteratorReportsUnknownSize() {
    var ctx = newContext();
    var source = streamOfInts(1, 2);

    try (var javaStream = source.stream(ctx)) {
      var spl = javaStream.spliterator();
      assertThat(spl.estimateSize()).isEqualTo(Long.MAX_VALUE);
      assertThat(spl.characteristics()).isZero();
      assertThat(spl.trySplit()).isNull();
    }
  }

  /**
   * {@link ExecutionStream#loadIterator} creates a {@link LoaderExecutionStream}. This test
   * verifies only the static factory returns the correct type; full session-dependent
   * behavior is covered in {@link LoaderExecutionStreamTest}.
   */
  @Test
  public void loadIteratorFactoryReturnsLoaderExecutionStream() {
    assertThat(ExecutionStream.loadIterator(Collections.emptyIterator()))
        .isInstanceOf(LoaderExecutionStream.class);
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /**
   * Drains the stream into a list, calling close at the end. Closes independently of
   * hasNext/next so wrappers that rely on explicit close are exercised.
   */
  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var list = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      list.add(stream.next(ctx));
    }
    stream.close(ctx);
    return list;
  }

  /**
   * Builds an in-memory ExecutionStream yielding {@code ResultInternal} entries with their
   * integer value under the key {@code "value"}.
   */
  private static ExecutionStream streamOfInts(int... values) {
    var list = new ArrayList<Result>(values.length);
    for (var v : values) {
      var r = new ResultInternal(null);
      r.setProperty("value", v);
      list.add(r);
    }
    return ExecutionStream.resultIterator(list.iterator());
  }

  /**
   * Wraps an ExecutionStream to count how many times {@link ExecutionStream#close} is
   * invoked. Used to assert close propagation.
   */
  private static class CloseTracker implements ExecutionStream {
    private final ExecutionStream inner;
    private int closeCount;

    CloseTracker(ExecutionStream inner) {
      this.inner = inner;
    }

    int closeCount() {
      return closeCount;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      return inner.hasNext(ctx);
    }

    @Override
    public Result next(CommandContext ctx) {
      return inner.next(ctx);
    }

    @Override
    public void close(CommandContext ctx) {
      closeCount++;
      inner.close(ctx);
    }
  }

  /**
   * Test-only {@link ExecutionStreamProducer} that yields a pre-built list of sub-streams
   * and tracks whether {@code close} was invoked.
   */
  private static class RecordingProducer implements ExecutionStreamProducer {
    private final Iterator<ExecutionStream> it;
    boolean closed;

    RecordingProducer(List<ExecutionStream> subs) {
      this.it = subs.iterator();
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      return it.hasNext();
    }

    @Override
    public ExecutionStream next(CommandContext ctx) {
      if (!it.hasNext()) {
        throw new NoSuchElementException();
      }
      return it.next();
    }

    @Override
    public void close(CommandContext ctx) {
      closed = true;
    }
  }

  private static RecordingProducer producerOf(List<ExecutionStream> subs) {
    return new RecordingProducer(subs);
  }

  /**
   * Minimal {@link ExecutionStep} that satisfies the
   * {@link com.jetbrains.youtrackdb.internal.core.command.CommandContext#startProfiling}
   * identity map. Used as the profiling anchor for {@link CostMeasureExecutionStream}.
   */
  private static class NoOpStep implements ExecutionStep {
    @Override
    public String getName() {
      return "noop";
    }

    @Override
    public String getType() {
      return "noop";
    }

    @Override
    public String getDescription() {
      return null;
    }

    @Override
    public List<ExecutionStep> getSubSteps() {
      return List.of();
    }
  }

  /**
   * Minimal {@link AbstractExecutionStep} unused but kept here for completeness — the
   * ExecutionStream wrapper tests never need a real step.
   */
  @SuppressWarnings("unused")
  private static class UnusedStep extends AbstractExecutionStep {
    UnusedStep(CommandContext ctx) {
      super(ctx, false);
    }

    @Override
    public ExecutionStream internalStart(CommandContext ctx) {
      return ExecutionStream.empty();
    }

    @Override
    public ExecutionStep copy(CommandContext ctx) {
      return this;
    }
  }
}
