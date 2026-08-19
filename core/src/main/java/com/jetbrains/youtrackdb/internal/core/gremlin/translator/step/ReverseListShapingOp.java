package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

/**
 * The {@code reverse()} transform: reverses each payload's own <em>value</em> and leaves the stream
 * alone. One payload in, one payload out, in arrival order — {@code g.V().values("name").reverse()}
 * returns each name spelled backwards, not the names in the opposite order.
 *
 * <p>That distinction is the whole content of the stage, and getting it backwards would pass a
 * row-count assertion while returning a different multiset on every ordered shape. Native
 * {@code ReverseStep} is a {@code ScalarMapStep}, so it maps per traverser; nothing about it touches
 * traverser order.
 *
 * <h2>Four arms, mirroring {@code ReverseStep.map}</h2>
 *
 * <ul>
 *   <li>{@code null} maps to {@code null}.
 *   <li>A {@link String} maps to its characters reversed.
 *   <li>An {@link Iterable}, an {@link Iterator} or an array maps to a {@link List} of its elements in
 *       reverse order — a list even when the input was an array or an iterator, which is native's
 *       answer too.
 *   <li>Anything else maps to itself. A {@code MAP} payload is not reversible and passes through, as
 *       does a number or a vertex.
 * </ul>
 *
 * <p>The element collection goes through {@link IteratorUtils#asList} — the same helper
 * {@code ReverseStep} calls — so the three collection-shaped arms agree with native on what counts as
 * an element rather than on a second reading of it.
 *
 * <p>The stage is lazy and stateless: each pull maps one payload, so it holds nothing across pulls and
 * nothing across {@link #apply} calls. That matters for the same two reasons a buffering stage's
 * per-call buffer does — the boundary base calls {@code apply} afresh on every (re)open of an arming,
 * and {@code AbstractStep.clone()} copies the shaping, and with it this op, by reference.
 *
 * <p>Deliberately neither a {@code record} nor a singleton, so two instances compare unequal;
 * {@link UnfoldListShapingOp} carries the argument, which is the union-agreement check rather than
 * anything about this stage's own semantics.
 */
public final class ReverseListShapingOp implements ListShapingOp {

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return upstream.hasNext();
      }

      @Override
      public Object next() {
        return reverseValue(upstream.next());
      }
    };
  }

  /** The reversed form of one payload, per the four arms in the class Javadoc. */
  private static Object reverseValue(Object payload) {
    if (payload == null) {
      return null;
    }
    if (payload instanceof String text) {
      return new StringBuilder(text).reverse().toString();
    }
    if (payload instanceof Iterable<?>
        || payload instanceof Iterator<?>
        || payload.getClass().isArray()) {
      // asList returns a fresh mutable ArrayList, which is what lets the in-place reverse below stand
      // in for native's identical two lines; the payload the boundary emitted is never mutated.
      List<?> elements = IteratorUtils.asList(payload);
      Collections.reverse(elements);
      return elements;
    }
    return payload;
  }
}
