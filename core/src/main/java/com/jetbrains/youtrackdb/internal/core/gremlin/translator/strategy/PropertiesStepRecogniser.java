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
    // The drop the projection performs is real only where nothing consumes the projection: a
    // following count() emits 0 for an element without the property rather than no traverser.
    return GremlinProjectionAssembler.configureSingleKeyValues(ctx, keys[0],
        cursor.peek(0) == null);
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
   * agree by construction. That includes one further position the strategy does rewrite —
   * {@code values(key).count().is(gt(n))}, where {@code CountStrategy} inserts a {@code limit} between
   * the projection and the count. No look-ahead handles it because the trailing {@code is(...)} has no
   * recogniser, so the walk declines a step later regardless; a look-ahead would be untestable defence.
   * Whoever adds an {@code IsStep} recogniser should extend this gate at the same time.
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
