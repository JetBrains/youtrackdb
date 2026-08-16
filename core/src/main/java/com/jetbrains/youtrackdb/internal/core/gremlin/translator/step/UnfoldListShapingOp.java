package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The {@code unfold()} flat-map: expands each upstream payload into the payloads it contains and
 * passes an atomic payload through unchanged. One payload in, zero or more out.
 *
 * <h2>Five arms, because that is what native {@code unfold()} has</h2>
 *
 * {@code UnfoldStep.flatMap} classifies the traverser's value in a fixed order and this stage
 * reproduces it arm for arm, because every arm is reachable through a live boundary output type:
 *
 * <ul>
 *   <li>An {@link Iterator} is returned as it stands.
 *   <li>An {@link Iterable} is expanded through its own iterator.
 *   <li>A {@link Map} is expanded into its {@code entrySet()} — <em>entries</em>, not keys and not
 *       values. This arm carries the ordinary idioms: {@code MAP} is what {@code group()},
 *       {@code groupCount()}, {@code valueMap()}, {@code elementMap()}, {@code project()} and
 *       multi-alias {@code select()} pin, and the boundary emits {@code LinkedHashMap} payloads for
 *       all of them, so {@code groupCount().unfold()} and {@code valueMap().unfold()} land here.
 *   <li>An array is expanded element by element, boxing primitives.
 *   <li>Anything else is one payload, emitted unchanged. {@code g.V().unfold()} over
 *       {@code ELEMENT} payloads passes each vertex through rather than dropping it.
 * </ul>
 *
 * <p>The array arm uses {@link Array} reflection for object and primitive arrays alike, where native
 * fast-paths {@code Object[]} and falls back to the same reflection for primitives. The two produce
 * the same elements in the same order; one branch is easier to read than two.
 *
 * <p><b>A null payload is emitted, where native throws.</b> {@code UnfoldStep.flatMap} reaches
 * {@code value.getClass().isArray()} with no null check, so a null traverser value raises a
 * {@link NullPointerException} there. Null payloads are legitimate on this side — {@code values(key)}
 * projects a present-null property as a null payload and an unmatched optional element projects as a
 * null vertex — and the exception would surface mid-iteration, long after
 * {@code GremlinToMatchStrategy}'s throw-safety net could decline to the native pipeline, so it would
 * reach the caller rather than degrade. Treating null as the atomic arm is the one deliberate
 * deviation in this stage: a null payload passes through the way every other atomic value does.
 *
 * <h2>Laziness and per-call state</h2>
 *
 * The expansion of the payload being consumed is held inside the returned iterator, so the stage
 * emits the first element of the first payload without touching the second payload — the first-result
 * latency {@link ListShapingOp} asks a per-payload stage to preserve. That buffer crosses
 * {@code next()} calls but never {@link #apply} calls: the boundary base calls {@code apply} afresh on
 * every (re)open of an arming, and {@code AbstractStep.clone()} copies the shaping — and with it this
 * op — by reference, so a buffer held in a field would replay a previous arming's elements and would
 * be a data race between two concurrently iterated clones. With it per call, an instance carries no
 * state and sharing it is safe.
 *
 * <p>Deliberately neither a {@code record} nor a singleton, so two instances compare unequal.
 * {@code UnionStepRecogniser} requires every arm of a {@code union(...)} to agree on its
 * {@link ResultShaping} and compares the records element-wise, so a value-equal or shared op would
 * make {@code union(__.unfold(), __.unfold())} agree and translate. That shape's answer is the same
 * either way — a per-payload stage over the concatenation and one per arm coincide — but the same
 * reference inequality is what declines {@code union(__.out().fold(), __.in().fold())}, whose answers
 * differ, so the four stages answer identity the same way rather than each arguing its own case.
 */
public final class UnfoldListShapingOp implements ListShapingOp {

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    return new Iterator<>() {
      /**
       * The elements of the payload currently being expanded. Empty means "pull the next payload",
       * which is also the starting state.
       */
      private Iterator<?> pending = Collections.emptyIterator();

      @Override
      public boolean hasNext() {
        // Loops rather than tests once because a payload may expand to nothing — an empty list, an
        // empty map, a zero-length array — and native emits nothing for such a traverser too, so the
        // stage has to skip to the next payload instead of reporting exhaustion.
        while (!pending.hasNext()) {
          if (!upstream.hasNext()) {
            return false;
          }
          pending = expand(upstream.next());
        }
        return true;
      }

      @Override
      public Object next() {
        if (!hasNext()) {
          throw new NoSuchElementException("unfold has no further payload to expand");
        }
        return pending.next();
      }
    };
  }

  /**
   * Classifies {@code payload} into the elements it expands to, in {@code UnfoldStep.flatMap}'s own
   * order. {@code Iterator} before {@code Iterable} matters — an {@code Iterator} that also
   * implemented {@code Iterable} would be consumed once either way, but the returned instance differs
   * — and {@code Map} before the array test matters not at all, the two being disjoint; the order is
   * native's regardless, so the two classifications can be compared line for line.
   */
  private static Iterator<?> expand(Object payload) {
    if (payload instanceof Iterator<?> iterator) {
      return iterator;
    }
    if (payload instanceof Iterable<?> iterable) {
      return iterable.iterator();
    }
    if (payload instanceof Map<?, ?> map) {
      return map.entrySet().iterator();
    }
    if (payload != null && payload.getClass().isArray()) {
      return arrayElements(payload);
    }
    // The atomic arm. singletonList rather than List.of because a null payload is legitimate here
    // and List.of rejects nulls; see the class Javadoc on the deviation from native.
    return Collections.singletonList(payload).iterator();
  }

  /**
   * The elements of {@code array} — an object array or a primitive one — as a list, boxing primitives
   * the way native's reflective branch does. Copied into a list rather than read lazily off the array
   * so a payload the caller mutates after the stage claimed it cannot change what the stage emits.
   */
  private static Iterator<?> arrayElements(Object array) {
    var length = Array.getLength(array);
    var elements = new ArrayList<>(length);
    for (var i = 0; i < length; i++) {
      elements.add(Array.get(array, i));
    }
    return elements.iterator();
  }
}
