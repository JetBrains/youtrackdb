package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;

/**
 * The {@link StepCursor} implementation over a traversal's step list. Holds the backing list, the set
 * of transparent step classes, and a single position that only ever moves forward. Package-private
 * and owned by {@link GremlinStepWalker}: recognisers see it only through the {@link StepCursor}
 * interface, and the walker reads {@link #position()} to guard that each accept advanced.
 *
 * <h2>Transparent steps</h2>
 *
 * A step whose exact class is in {@code transparentSteps} (today {@code NoOpBarrierStep}, the barrier
 * {@code LazyBarrierStrategy} wedges between chained hops) is skipped by every operation and counted
 * as consumed. Skipping happens at the head before each read: a leading run, a run interleaved inside
 * a {@link #takeWhile}, and a trailing run at the end of the stream are all skipped, so once the
 * significant steps are consumed the position reaches the end of the list and the walker's
 * "every step recognised" invariant holds. A recogniser never sees a transparent step.
 *
 * <h2>Exact-class matching</h2>
 *
 * {@link #takeIf} and {@link #takeWhile} match on {@code step.getClass() == exact}, so a subclass of
 * a matched type is left in place rather than consumed. This is the same exact-class rule the walker
 * dispatches by (see {@link GremlinStepWalker}).
 */
final class StepStreamCursor implements StepCursor {

  /** The traversal's step list, stored raw because {@code Traversal.Admin.getSteps()} returns a raw
   *  {@code List<Step>}; elements are read back through the reifiable {@code Step<?, ?>} cast. */
  private final List<?> steps;

  /** Step classes skipped and counted as consumed by every operation. Matched by exact class. */
  private final Set<Class<?>> transparentSteps;

  /** Forward-only position into {@link #steps}. Never rewound: a decline discards the whole walk. */
  private int position;

  StepStreamCursor(List<?> steps, Set<Class<?>> transparentSteps) {
    this.steps = steps;
    this.transparentSteps = transparentSteps;
  }

  @Nullable @Override
  public Step<?, ?> peek() {
    skipTransparent();
    return position < steps.size() ? stepAt(position) : null;
  }

  @Nullable @Override
  public Step<?, ?> peek(int ahead) {
    if (ahead < 0) {
      throw new IllegalArgumentException("peek lookahead must be >= 0, was " + ahead);
    }
    // Scan forward from the head, skipping transparent steps, until `ahead` significant steps have
    // been passed. A local probe leaves the cursor's position untouched — peek consumes nothing.
    int probe = position;
    int seen = 0;
    while (probe < steps.size()) {
      if (isTransparent(stepAt(probe))) {
        probe++;
        continue;
      }
      if (seen == ahead) {
        return stepAt(probe);
      }
      seen++;
      probe++;
    }
    return null;
  }

  @Override
  public Step<?, ?> take() {
    skipTransparent();
    if (position >= steps.size()) {
      throw new NoSuchElementException("take() past the end of the step stream");
    }
    return stepAt(position++);
  }

  @Nullable @Override
  public <T extends Step<?, ?>> T takeIf(Class<T> exact, Predicate<T> cond) {
    skipTransparent();
    if (position >= steps.size()) {
      return null;
    }
    var next = stepAt(position);
    if (next.getClass() != exact) {
      return null;
    }
    var typed = exact.cast(next);
    if (!cond.test(typed)) {
      return null;
    }
    position++;
    return typed;
  }

  @Override
  public <T extends Step<?, ?>> List<T> takeWhile(Class<T> exact, Predicate<T> cond) {
    var run = new ArrayList<T>();
    while (true) {
      // Skip transparent steps before each element so a barrier interleaved inside the run is
      // consumed rather than ending the run early.
      skipTransparent();
      if (position >= steps.size()) {
        break;
      }
      var next = stepAt(position);
      if (next.getClass() != exact) {
        break;
      }
      var typed = exact.cast(next);
      if (!cond.test(typed)) {
        break;
      }
      run.add(typed);
      position++;
    }
    return run;
  }

  /** The current forward position, read by the walker to confirm an accept advanced the cursor. */
  int position() {
    return position;
  }

  /** Advances the position past any transparent steps at the head. Idempotent. */
  private void skipTransparent() {
    while (position < steps.size() && isTransparent(stepAt(position))) {
      position++;
    }
  }

  private boolean isTransparent(Step<?, ?> step) {
    return transparentSteps.contains(step.getClass());
  }

  /** Casts element {@code i} to {@code Step<?, ?>} — a reifiable cast, so no unchecked warning. */
  private Step<?, ?> stepAt(int i) {
    return (Step<?, ?>) steps.get(i);
  }
}
