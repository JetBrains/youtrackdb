package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

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
      var drop = capturedSuccessorDrop(cursor);
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
   * question the conjunct answers is whether the child still emits nothing once every remaining step
   * has run.
   */
  private enum CapturedDrop {
    /** Nothing remains that could turn the dropped element's empty stream into output — filter on it. */
    PRESERVED,
    /** What remains emits for an empty stream, so the element survives — contribute nothing. */
    DESTROYED,
    /** Not classified. The caller declines rather than guess in either direction. */
    UNCLASSIFIED
  }

  /**
   * Classifies what remains of a captured child after a {@code values(key)} projection. Called only
   * there: on the main line the drop travels as result shaping, which the plan step applies to the
   * rows it returns, and no successor changes that.
   *
   * <p>The classification is a <b>termination test</b>, and only two chain shapes pass it — the
   * projection ends the child, or a {@code count()} immediately after the projection ends the child.
   * Anything else is {@link CapturedDrop#UNCLASSIFIED} and declines, whatever its length or members.
   * The shape of the test is the point. Two earlier versions of this gate asked which single step
   * followed the projection and answered from a list of tolerated successors, and each list turned
   * out to be missing an entry. The second admitted {@code dedup()} without looking past it, so
   * {@code and(values(age).dedup().count())} answered 2 against native's 3. "Nothing remains" is a
   * positional fact read straight off the cursor and has nothing left to enumerate.
   *
   * <ul>
   *   <li><b>The projection ends the child</b> — the drop is the child's whole answer, and the
   *       conjunct is the only carrier that reaches the parent. {@link CapturedDrop#PRESERVED}.
   *   <li><b>{@code count()} ends the child</b> — {@code count()} counts an empty stream as {@code 0}
   *       and emits it, so the element survives natively and a presence conjunct would filter a row
   *       native keeps. {@link CapturedDrop#DESTROYED}. Matched on the exact class, mirroring the
   *       walker's class-keyed dispatch: an unregistered subclass declines the walk anyway, so
   *       routing it to {@link CapturedDrop#UNCLASSIFIED} costs nothing and keeps the arm exact.
   * </ul>
   *
   * <p>The surface this costs is small and measured. {@code dedup()} after the projection is the only
   * shape the rule withdraws that was answering correctly, and it is inert where it sits: a captured
   * child is an existence test — {@link SubTraversalPredicateAdapter#projectsReturnedPayload} answers
   * {@code false} unconditionally and the payload is never read — so the parent asks only whether any
   * traverser survived, and {@code dedup()} maps an empty stream to an empty one and a non-empty
   * stream to a non-empty one. {@code and(values(age).dedup())} selects the same vertices as
   * {@code and(values(age))}, measured on a fixture where two vertices share an age. The withdrawn
   * spellings are the ones that wrote a redundant {@code dedup}; {@code and(values(age))} itself is
   * untouched. A {@code dedup} <em>before</em> the projection is untouched too, because the projection
   * still ends the child.
   *
   * <p>The other reachable successors were already declining and keep declining. A slice selects by
   * <em>position</em>: {@code limit(0)} empties every stream and {@code skip(n)} empties a stream of
   * {@code n} values. {@code order()} carries comparator modulators that read properties of their own
   * and commit their own conjuncts through {@link ByModulatorPresence}.
   */
  private static CapturedDrop capturedSuccessorDrop(StepCursor cursor) {
    var successor = cursor.peek(0);
    if (successor == null) {
      return CapturedDrop.PRESERVED;
    }
    // peek(1) == null is the termination test: nothing significant remains after the count().
    if (successor.getClass() == CountGlobalStep.class && cursor.peek(1) == null) {
      return CapturedDrop.DESTROYED;
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
   * <p>This gate answers only whether the element is distinguishable from its payload; inside a
   * captured child {@link #capturedSuccessorDrop} then applies its own, stricter termination test, so
   * a count that passes here still declines unless it ends the child.
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
