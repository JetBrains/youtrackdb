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
    return GremlinProjectionAssembler.configureSingleKeyValues(ctx, keys[0]);
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
   *   <li><b>A sub-walk capture</b> ({@code where(values(key))} and the other combinator children
   *       that route through {@link RecognitionContext#walkChild}). Only the presence conjunct the
   *       projection contributes survives the commit; the projection itself is discarded, so the
   *       element and its payload are indistinguishable to the caller.
   *   <li><b>A count-consumed main-line step</b> ({@code values(key).count()}, measured as arriving
   *       here with a {@link CountGlobalStep} successor). One property element per value means the row
   *       count is the same either way.
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
    return !ctx.projectsReturnedPayload() || cursor.peek(0) instanceof CountGlobalStep<?>;
  }
}
