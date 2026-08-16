package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.Iterator;

/**
 * One stage of the ordered list-shaping post-process a boundary step applies after row projection.
 * A recognised terminator that reshapes the projected stream ({@code fold}, {@code unfold}, {@code
 * reverse}, {@code tail}) contributes one op; {@link ResultShaping#listShapingOps()} carries them in
 * declared order and the boundary base applies them left to right, so {@code reverse().unfold()} and
 * {@code unfold().reverse()} — both accepted and observably distinct — resolve to different
 * application sequences.
 *
 * <p>An op is a <em>stream stage</em>, not a per-row mapper: it takes the upstream payload iterator
 * and returns the downstream payload iterator, so it may change cardinality. The four ops the
 * terminators register span three cardinality classes:
 *
 * <ul>
 *   <li><b>1&rarr;1 map</b> — one payload in, one payload out. {@code reverse} reverses each
 *       payload's own value — a string's characters, a collection's or an array's elements — and
 *       passes an unreversible payload through unchanged; it never reorders the stream.
 *   <li><b>1&rarr;N flat-map</b> — one payload in, zero or more out. {@code unfold} expands an
 *       iterator, an iterable, a map (into its <em>entries</em>) or an array element by element, and
 *       emits any other payload as a single one, so {@code groupCount().unfold()} yields entries where
 *       {@code g.V().unfold()} passes each vertex through.
 *   <li><b>N&rarr;1 / window drain</b> — several payloads in, one or a bounded window out ({@code
 *       fold} drains the whole stream into one list; {@code tail(n)} keeps the last {@code n}).
 * </ul>
 *
 * <p>A 1&rarr;1 row-mapper contract could express none of the cardinality-changing ops, which is why
 * the carrier is a stream stage. Implementations should stay lazy where the op allows it — {@code
 * unfold} and {@code reverse} can emit before the upstream is drained — so first-result latency is
 * preserved for the ops that do not inherently need the whole stream; {@code fold} and {@code tail}
 * are window drains by nature.
 *
 * <p>{@link #apply} may be called more than once for the same step. The boundary base rebuilds its
 * shaped iterator on every (re)open of an arming — after a {@code reset()} and reopen, and once per
 * child plan for a multi-plan boundary — calling {@code apply} afresh each time. So each call must
 * return an independent iterator and the op must hold no state across calls: an op whose buffer is
 * allocated once outside the returned iterator (rather than per call, the way a buffering {@code
 * fold} or {@code tail} must) would replay stale output on the second arming.
 *
 * <p>The boundary base does not apply any op when the list is empty (the structural bypass in {@link
 * AbstractMatchPlanStep}): the projection stream flows straight through, so a traversal with no
 * list-shaping terminator keeps its per-row laziness untouched.
 */
@FunctionalInterface
public interface ListShapingOp {

  /**
   * Wraps the upstream payload iterator in this stage and returns the downstream payload iterator.
   * The returned iterator may emit more, fewer, or the same number of payloads as the upstream.
   *
   * @param upstream the payloads produced by the prior stage (row projection, or the preceding op)
   * @return the payloads this stage emits
   */
  Iterator<Object> apply(Iterator<Object> upstream);
}
