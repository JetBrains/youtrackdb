package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Step;

/**
 * Pluggable handler that {@link GremlinStepWalker} invokes for each step in a traversal
 * being considered for translation. A recogniser inspects the step, validates its shape
 * against the rules of the Gremlin construct it knows about, and — if everything checks
 * out — appends the step's contribution to the {@link WalkerContext}.
 *
 * <h2>Contract</h2>
 *
 * The walker tries the registered recognisers in declaration order. The first recogniser
 * whose {@link #recognize} call returns {@code true} owns the step; the walker advances
 * to the next step. If no recogniser claims a step, the walker treats the entire
 * traversal as unrecognised and declines under the all-or-nothing translation rule — one
 * unrecognised step declines the whole traversal, which stays on the native TinkerPop
 * pipeline verbatim.
 *
 * <p>{@code recognize} is therefore the union of two questions:
 * <ol>
 *   <li><b>Is this my step shape?</b> — a recogniser starts with a {@code instanceof}
 *       check (or whichever discriminator names the step type it handles) and returns
 *       {@code false} immediately on a non-match so the walker can try the next one.</li>
 *   <li><b>If yes, is it well-formed?</b> — when the recogniser does handle the step
 *       type but the step's payload does not encode a translatable shape (e.g. an
 *       unconvertible ID, an unsupported predicate variant), the recogniser returns
 *       {@code false} as well. The walker treats both negative outcomes the same — the
 *       traversal declines — so the boolean is unambiguous to the caller without an
 *       intermediate "not mine" / "mine but malformed" distinction.</li>
 * </ol>
 *
 * <h2>Discipline</h2>
 *
 * Recognisers MUST validate before mutating {@link WalkerContext}. A recogniser that
 * partially mutates the context and then returns {@code false} would leave a later
 * recogniser inspecting tainted state — the walker does not roll back per-step
 * mutations. The contract is "look first, write last".
 */
@FunctionalInterface
interface StepRecogniser {

  /**
   * Inspects a single step from the traversal. Returns {@code true} when the step has
   * been fully consumed and its effect appended to {@code ctx}; otherwise the walker
   * tries the next recogniser, and if no recogniser claims the step the walk declines.
   *
   * @param step the current step under consideration; never {@code null}
   * @param ctx  the in-progress walker state — recognisers read traversal-level fields
   *             (graph, polymorphism) and append their contribution to the pattern
   *             builder, alias maps, and return-projection lists. Recognisers MUST NOT
   *             mutate {@code ctx} unless they are committing to return {@code true}.
   * @return {@code true} iff the step was recognised and the context was updated.
   */
  boolean recognize(Step<?, ?> step, WalkerContext ctx);
}
