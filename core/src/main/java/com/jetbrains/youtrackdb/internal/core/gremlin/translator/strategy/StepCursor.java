package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;

/**
 * The narrow view a {@link StepRecogniser} has of the traversal's step stream: a forward cursor that
 * reads and consumes steps without exposing an index, a consumed count, or the traversal. A
 * recogniser reads its head with {@link #take()}, then consumes any trailing steps of its shape with
 * the conditional matchers. It never sees a transparent step (a barrier): every operation skips those
 * and counts each skipped one as consumed, so a recogniser writes no barrier-handling of its own.
 *
 * <h2>Matching is by exact class</h2>
 *
 * {@link #takeIf} and {@link #takeWhile} match a step only when {@code step.getClass() == exact},
 * never {@code isInstance}. This mirrors {@link GremlinStepWalker}'s class-keyed dispatch: a
 * recogniser can neither be handed nor consume a step of a type it does not own, and an unrecognised
 * subclass declines the whole traversal rather than being mishandled as its parent.
 *
 * <h2>Deciding what to consume</h2>
 *
 * The condition is a function the cursor evaluates <em>before</em> consuming, so a recogniser does
 * not walk the stream itself:
 *
 * <ul>
 *   <li>{@link #takeIf(Class, Predicate)} consumes the next step only when it is a matching {@code T}
 *       and otherwise leaves it in place, for a step that might belong to another recogniser (folding
 *       {@code out(L).hasLabel(X)});
 *   <li>{@link #takeWhile(Class, Predicate)} consumes the leading run of matching {@code T}s and
 *       stops at the first step that is not, for a homogeneous run ({@code has(...)} predicates on an
 *       edge);
 *   <li>{@link #peek()} / {@link #peek(int)} read ahead without consuming, for a decision that needs
 *       a later step or a heterogeneous run the conditional matchers cannot express.
 * </ul>
 *
 * A step the cursor leaves is deferred to dispatch, not dropped: the walker re-routes it by class,
 * and the traversal translates only if that step finds its own recogniser.
 */
interface StepCursor {

  /**
   * Returns the next significant step without consuming it, or {@code null} once the stream is
   * exhausted. Transparent steps at the head are skipped (and counted as consumed).
   */
  @Nullable Step<?, ?> peek();

  /**
   * Returns the significant step {@code ahead} positions past the head without consuming anything, or
   * {@code null} when fewer than {@code ahead + 1} significant steps remain. {@code peek(0)} equals
   * {@link #peek()}. Transparent steps are skipped and not counted toward {@code ahead}.
   *
   * @param ahead how many significant steps beyond the head to look; must be {@code >= 0}
   */
  @Nullable Step<?, ?> peek(int ahead);

  /**
   * Consumes and returns the next significant step — the head the walker already matched by class, or
   * the head of a delegating recogniser's shape. Transparent steps at the head are skipped first.
   *
   * @throws java.util.NoSuchElementException if the stream is exhausted; the walker only dispatches
   *     when {@link #peek()} is non-null, so a {@code take()} past the end is a recogniser bug rather
   *     than a normal decline path.
   */
  Step<?, ?> take();

  /**
   * Consumes and returns the next significant step only when its class is exactly {@code exact} and
   * it satisfies {@code cond}; otherwise consumes nothing and returns {@code null}. Any transparent
   * steps before it are skipped whether or not the match succeeds.
   *
   * @param cond a side-effect-free predicate evaluated before the step is consumed
   */
  @Nullable <T extends Step<?, ?>> T takeIf(Class<T> exact, Predicate<T> cond);

  /**
   * Consumes and returns the leading run of significant steps whose class is exactly {@code exact}
   * and that satisfy {@code cond}, in order, stopping at (and leaving) the first step that is not.
   * Transparent steps interleaved in the run are skipped and not returned. Returns an empty list when
   * the head does not match.
   *
   * @param cond a side-effect-free predicate evaluated before each step is consumed
   */
  <T extends Step<?, ?>> List<T> takeWhile(Class<T> exact, Predicate<T> cond);

  /** Consumes the next step only when its class is exactly {@code exact}; see {@link #takeIf}. */
  @Nullable default <T extends Step<?, ?>> T takeIf(Class<T> exact) {
    return takeIf(exact, t -> true);
  }

  /** Consumes every consecutive step whose class is exactly {@code exact}; see {@link #takeWhile}. */
  default <T extends Step<?, ?>> List<T> takeWhile(Class<T> exact) {
    return takeWhile(exact, t -> true);
  }
}
