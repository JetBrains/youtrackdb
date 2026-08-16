package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * The {@code fold()} drain: consumes the whole upstream payload stream and emits one {@code List}
 * payload holding every payload in arrival order. A dry upstream still emits one empty list, which is
 * native {@code FoldStep}'s answer over an empty stream and the reason the stage cannot be expressed
 * as a per-row projection — there is no row to project.
 *
 * <p>The stage builds its list out of whatever the preceding projection produced, so the boundary's
 * {@link BoundaryOutputType} is left alone: {@code g.V().fold()} folds element payloads,
 * {@code g.V().values("name").fold()} folds single values, {@code g.V().valueMap().fold()} folds
 * maps, and {@code g.V().groupCount().fold()} folds the one accumulated map. Re-pinning the output
 * type to a list constant would erase the very thing that tells the boundary how to build each
 * element.
 *
 * <p>The buffer is allocated inside the returned iterator rather than in a field, and that placement
 * is load-bearing twice over. {@link ListShapingOp#apply} runs afresh on every (re)open of an arming,
 * so a field would replay the first arming's payloads after a {@code reset()} and reopen. And
 * {@code AbstractStep.clone()} copies the shaping — and with it this op — by reference, so two
 * concurrently iterated clones of one boundary share the instance; a field would be a data race that
 * hands each clone a list of the other's rows. With the buffer per call, an instance carries no state
 * and sharing it is safe.
 *
 * <p>The folded payload is a mutable {@link ArrayList}, matching what native {@code fold()} hands
 * back, and the mutability is not the only reason: {@code List.copyOf} rejects null elements, while a
 * null payload is legitimate here — {@code values(key)} projects a present-null property as a null
 * traverser, and an unmatched optional element projects as a null vertex.
 *
 * <p>Deliberately neither a {@code record} nor a singleton, so two instances compare unequal.
 * {@code UnionStepRecogniser} requires every arm of a {@code union(...)} to agree on its
 * {@link ResultShaping} and compares the records element-wise, so a value-equal or shared op would
 * make {@code union(__.out().fold(), __.in().fold())} agree and translate — one list over the
 * concatenation where native produces one list per arm. Reference inequality is what declines that
 * shape.
 */
public final class FoldListShapingOp implements ListShapingOp {

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    return new Iterator<>() {
      /** Whether the one folded payload has been handed out; the stage emits exactly one. */
      private boolean emitted;

      @Override
      public boolean hasNext() {
        return !emitted;
      }

      @Override
      public Object next() {
        if (emitted) {
          throw new NoSuchElementException("fold emits a single list payload");
        }
        // Drained here rather than in hasNext() so the cost of consuming the stream falls on the pull
        // that takes the result: the boundary base calls hasNext() to decide whether an arming is
        // dry, and draining there would charge that decision with the whole stream. The drain still
        // happens inside the base's try block, which is what releases the plan if a row throws.
        var folded = new ArrayList<>();
        while (upstream.hasNext()) {
          folded.add(upstream.next());
        }
        emitted = true;
        return folded;
      }
    };
  }
}
