package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.structure.PropertyType;

/**
 * Recogniser for a terminal {@link PropertiesStep} in its {@code values(key)} form ({@link
 * PropertyType#VALUE}): maps to a single field-access RETURN column with {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#SINGLE_VALUE}
 * and {@code dropOnAbsent}. Multi-key {@code values(...)} and property-map shapes decline.
 *
 * <p>The element-returning {@code properties(key)} form ({@link PropertyType#PROPERTY}) declines
 * unless nothing downstream can observe the difference — see {@link #elementFormIsUnobserved}. The two
 * {@link PropertyType}s are interchangeable in an existence or count position and not in a projection
 * one: {@code properties(key)} emits a {@code VertexProperty} element carrying its own id, key and
 * meta-properties, where {@code values(key)} emits the payload. Projecting the element as a field
 * access swaps one for the other, which was measured as {@code properties(k).has(metaKey, v)}
 * returning nothing translated against one row natively, and as
 * {@code group().by(properties(k))} collapsing two native buckets into one.
 */
final class PropertiesStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final PropertiesStepRecogniser INSTANCE = new PropertiesStepRecogniser();

  private PropertiesStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof PropertiesStep<?> propertiesStep)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    var returnType = propertiesStep.getReturnType();
    if (returnType != PropertyType.VALUE && returnType != PropertyType.PROPERTY) {
      return Outcome.DECLINE;
    }
    if (returnType == PropertyType.PROPERTY && !elementFormIsUnobserved(cursor, ctx)) {
      return Outcome.DECLINE;
    }
    var keys = propertiesStep.getPropertyKeys();
    if (keys.length != 1) {
      // Multi-key values() flatMaps — no MATCH boundary equivalent in Phase 1.
      return Outcome.DECLINE;
    }
    var contributePresenceConjunct = false;
    if (!ctx.projectsReturnedPayload()) {
      var drop = capturedSuccessorDrop(cursor.peek(0));
      if (drop == CapturedDrop.UNCLASSIFIED) {
        return Outcome.DECLINE;
      }
      contributePresenceConjunct = drop == CapturedDrop.PRESERVED;
    }
    return GremlinProjectionAssembler.configureSingleKeyValues(
        ctx, keys[0], contributePresenceConjunct);
  }

  /**
   * What the rest of a captured child does to the drop {@code values(key)} performs. A combinator
   * child contributes exactly one thing to its parent — whether it produced a traverser — so the
   * question the conjunct answers is not "did the projection drop the element" but "does the child
   * still emit nothing once every remaining step has run".
   */
  private enum CapturedDrop {
    /** No remaining step can turn the dropped element's empty stream into output — filter on it. */
    PRESERVED,
    /** A remaining step emits for an empty stream, so the element survives — contribute nothing. */
    DESTROYED,
    /** Not classified. The caller declines rather than guess in either direction. */
    UNCLASSIFIED
  }

  /**
   * Classifies the step following a {@code values(key)} projection inside a captured child. Called
   * only there: on the main line the drop travels as result shaping, which the plan step applies to
   * the rows it returns, and no successor changes that.
   *
   * <p>Matching on the exact class throughout, mirroring the walker's class-keyed dispatch: an
   * unregistered subclass declines the walk anyway, so routing it to {@link
   * CapturedDrop#UNCLASSIFIED} here costs nothing and keeps both classified arms exact.
   *
   * <ul>
   *   <li><b>End of the child</b> — the drop is the child's whole answer. {@link
   *       CapturedDrop#PRESERVED}.
   *   <li><b>{@code count()}</b> — counts an empty stream as {@code 0} and emits it, so the element
   *       survives natively and a presence conjunct would filter a row native keeps. {@link
   *       CapturedDrop#DESTROYED}.
   *   <li><b>{@code dedup()}</b> — never turns a non-empty stream empty or an empty one non-empty,
   *       and {@link DedupGlobalStepRecogniser} refuses every {@code by(...)} modulator, so it
   *       commits no filter of its own that could interact. {@link CapturedDrop#PRESERVED}.
   * </ul>
   *
   * <p>Everything else declines, and two reachable successors are deliberately in that bucket rather
   * than classified. A slice selects by <em>position</em>: {@code limit(0)} empties every stream and
   * {@code skip(n)} empties a stream of {@code n} values, so it preserves the drop only for some
   * bounds, and reading those bounds here would restate the slice recogniser's normalisation in a
   * second place. {@code order()} carries comparator modulators that read properties of their own and
   * commit their own conjuncts through {@link ByModulatorPresence}, so its child contribution is not
   * the projection's drop alone. Both spellings translated before this classification existed and
   * both disagreed with native — {@code and(values(age).limit(1))} and {@code and(values(age).order())}
   * each returned every vertex against native's key-bearers — so declining them loses no shape that
   * was answering correctly. That holds for the whole bucket: before this gate a captured child with
   * any successor committed no conjunct and therefore filtered nothing, which agrees with native only
   * where the successor emits for an empty stream, and {@code count()} is the only such successor the
   * registry accepts.
   */
  private static CapturedDrop capturedSuccessorDrop(@Nullable Step<?, ?> successor) {
    if (successor == null) {
      return CapturedDrop.PRESERVED;
    }
    if (successor.getClass() == CountGlobalStep.class) {
      return CapturedDrop.DESTROYED;
    }
    if (successor.getClass() == DedupGlobalStep.class) {
      return CapturedDrop.PRESERVED;
    }
    return CapturedDrop.UNCLASSIFIED;
  }

  /**
   * Whether an element-returning {@code properties(key)} can be projected as a value anyway, because
   * no downstream step can tell the element from its payload. Two such positions exist, and both are
   * {@code AdjacentToIncidentStrategy} rewrites of a written {@code values(key)} rather than shapes a
   * caller writes by hand — the strategy only performs the rewrite where the value is unread, so
   * matching its two output shapes is what keeps this recogniser accepting everything it accepted
   * before the element form started declining.
   *
   * <ul>
   *   <li><b>The end step of a sub-walk capture</b> ({@code and(values(a), values(b))} and the other
   *       combinator children that route through {@link RecognitionContext#walkChild}). The
   *       projection itself is discarded on commit and only the presence conjunct
   *       {@link GremlinProjectionAssembler#configureSingleKeyValues} contributes survives, so the
   *       element and its payload are indistinguishable to the caller. The position inside the child
   *       is as load-bearing as the capture: a step after the projection reads the payload and has
   *       its own filter committed to the parent on the <em>element's</em> alias, which is how
   *       {@code where(properties(k).has(metaKey, v))} became a top-level {@code metaKey} filter and
   *       returned a row set disjoint from native's. The child's end step is also the only position
   *       {@code AdjacentToIncidentStrategy} rewrites there (its {@code i == size} arm).
   *   <li><b>A count-consumed step</b> ({@code values(key).count()}, measured as arriving here with a
   *       {@link CountGlobalStep} successor, on the main line and inside a child alike). One property
   *       element per value means the row count is the same either way. Matched by exact class rather
   *       than {@code instanceof} for the reason {@code RangeGlobalStepRecogniser.followedByCount}
   *       records: a {@code CountGlobalStep} subclass has no registry entry and would decline the
   *       walk anyway, so treating it as a count here is the one direction that is not fail-closed.
   * </ul>
   *
   * <p>Anything else declines, which is the safe direction: the shape runs natively and the two arms
   * agree by construction. The position that costs the most to lose is a count with a slice between
   * it and the projection, in either of its two spellings: {@code values(key).limit(n).count()} as
   * written, and {@code values(key).count().is(gt(n))}, which {@code CountStrategy} rewrites into the
   * same shape. The strategy still hands both over in the element form — it skips a
   * {@code RangeGlobalStep} when it tracks its predecessor, so any number of slices between the
   * projection and the count still produce it — and both therefore arrive here with the slice at
   * {@code peek(0)} and decline at this gate.
   *
   * <p>Seeing through the slice would open neither, which is why the look-ahead is not worth
   * widening: {@code GremlinAggregateAssembler.configureCount} refuses a count whose input already
   * carries a captured {@code limit} / {@code skip} / {@code dedup}, so with this gate reverted both
   * spellings decline one step later there instead. Relaxing that cardinality gate is the change that
   * would make the position translatable; extending this one is not. The trailing {@code is(...)} is
   * not what holds either spelling out — the first has none, and the second declines two steps before
   * reaching it.
   */
  private static boolean elementFormIsUnobserved(StepCursor cursor, RecognitionContext ctx) {
    var successor = cursor.peek(0);
    if (successor == null) {
      // Nothing reads the projection only when the walk it ends is a captured child, whose payload
      // is dropped. On the main line the element would be returned to the caller as its own value.
      return !ctx.projectsReturnedPayload();
    }
    return successor.getClass() == CountGlobalStep.class;
  }
}
