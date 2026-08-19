package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The {@code tail(n)} window: keeps the last {@code n} payloads in arrival order and drops the rest.
 * {@code n} payloads out of however many came in, so the stage is a bounded drain — it cannot know
 * which payloads are the last until the upstream is exhausted, which is native
 * {@code TailGlobalStep}'s position too (it is a {@code FilteringBarrier}).
 *
 * <h2>The window is a ring, and the ring holds nulls</h2>
 *
 * Retention is bounded to {@code n} slots: once the window is full, each further payload overwrites
 * the oldest slot and the read cursor rotates. So a {@code tail(3)} over a million rows holds three
 * payloads rather than a million, and emission walks the ring from the oldest slot forward, which is
 * arrival order.
 *
 * <p>The backing store is an {@link ArrayList} used as the ring rather than an {@code ArrayDeque},
 * which would be the obvious bounded deque and is what native uses. {@code ArrayDeque} rejects null
 * elements, and a null payload is legitimate here — {@code values(key)} projects a present-null
 * property as a null payload and an unmatched optional element projects as a null vertex — so a deque
 * would turn those rows into a {@link NullPointerException} mid-iteration. Native never meets the
 * problem because its deque holds traversers, which are never null.
 *
 * <h2>Zero and negative windows</h2>
 *
 * {@code n == 0} emits nothing, which is what native answers: {@code TailGlobalStep} trims its deque
 * back to the limit after every add, so a limit of zero trims everything away. The upstream is still
 * drained, because the stage is a barrier and the rows behind it must be consumed either way.
 *
 * <p>A negative {@code n} never reaches this stage — {@code TailGlobalStepRecogniser} declines it and
 * the traversal runs natively — so the constructor rejects it rather than inventing a meaning for a
 * window of negative size.
 *
 * <h2>Per-call state</h2>
 *
 * The ring is allocated inside the returned iterator, not in a field, for the two reasons
 * {@link ListShapingOp} gives: the boundary base calls {@link #apply} afresh on every (re)open of an
 * arming, so a field would emit the previous arming's window after a {@code reset()} and reopen; and
 * {@code AbstractStep.clone()} copies the shaping — and with it this op — by reference, so two
 * concurrently iterated clones would share one ring and each get a window of the right size holding
 * the other's payloads, which no size assertion catches. With the ring per call the instance carries no
 * state beyond its immutable limit and sharing it is safe.
 *
 * <p>Deliberately neither a {@code record} nor a singleton, so two instances compare unequal even when
 * their limits match. That is load-bearing here rather than merely consistent:
 * {@code UnionStepRecogniser} requires every arm of a {@code union(...)} to agree on its
 * {@link ResultShaping} and compares the records element-wise, so a record carrying {@code limit} would
 * make {@code union(__.out().tail(1), __.in().tail(1))} agree and translate — one window over the
 * concatenation where native takes one per arm, and the concatenation's order is not native's besides.
 * {@link UnfoldListShapingOp} carries the same argument for the per-payload stages.
 */
public final class TailListShapingOp implements ListShapingOp {

  /** Payloads to retain; never negative. */
  private final long limit;

  public TailListShapingOp(long limit) {
    if (limit < 0) {
      throw new IllegalArgumentException(
          "tail window must not be negative; the recogniser declines a negative limit: " + limit);
    }
    this.limit = limit;
  }

  /** The window size this stage retains, for the recogniser's own tests to read back. */
  public long limit() {
    return limit;
  }

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    return new Iterator<>() {
      /** The ring, or {@code null} until the first pull fills it. At most {@code limit} entries. */
      private List<Object> window;

      /** Index of the oldest retained payload in {@link #window}; the emission cursor's origin. */
      private int oldest;

      /** Payloads handed out so far, so emission walks the ring exactly once. */
      private int emitted;

      @Override
      public boolean hasNext() {
        fill();
        return emitted < window.size();
      }

      @Override
      public Object next() {
        fill();
        if (emitted >= window.size()) {
          throw new NoSuchElementException("tail(" + limit + ") has no further payload");
        }
        return window.get((oldest + emitted++) % window.size());
      }

      /**
       * Drains the upstream into the ring on the first pull, retaining the last {@code limit} payloads.
       * Idempotent: a non-null window means the drain already happened, so {@code hasNext()} may call
       * this as often as the caller asks.
       */
      private void fill() {
        if (window != null) {
          return;
        }
        window = new ArrayList<>();
        while (upstream.hasNext()) {
          var payload = upstream.next();
          if (limit == 0) {
            // A zero window retains nothing, and the branch also keeps the ring arithmetic below from
            // having to describe a ring with no slots.
            continue;
          }
          if (window.size() < limit) {
            window.add(payload);
          } else {
            // Full: overwrite the oldest slot and rotate the cursor onto the next-oldest.
            window.set(oldest, payload);
            oldest++;
            if (oldest == window.size()) {
              oldest = 0;
            }
          }
        }
      }
    };
  }
}
