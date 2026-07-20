package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.structure.PropertyType;

/**
 * Recogniser for the bare presence form {@code has(key)}, which TinkerPop 3.8.1 desugars to {@code
 * TraversalFilterStep(__.values(key))} — a filter that keeps an element only when it has at least one
 * value under {@code key}. It maps to {@code key IS DEFINED}, the entity-presence predicate: unlike
 * {@code IS NULL}, {@code IS DEFINED} matches a property stored with a literal {@code null} value,
 * mirroring TinkerPop's {@code Property.isPresent()} (native {@code values(key)} yields the stored
 * null and the filter passes).
 *
 * <h2>Only the has(key) desugar — every other TraversalFilterStep declines</h2>
 *
 * The recogniser claims the step only when its filter traversal is exactly a single {@code
 * values(key)} {@link PropertiesStep} over one property key. A general {@code filter(sub-traversal)},
 * a {@code valueMap} / {@code propertyMap} filter, a multi-key {@code values}, or a reserved / blank
 * key all decline the whole traversal to native. {@code hasNot(key)} is a distinct shape — it
 * desugars to a {@code NotStep(__.values(key))}, a different class this recogniser never sees.
 */
final class TraversalFilterStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final TraversalFilterStepRecogniser INSTANCE = new TraversalFilterStepRecogniser();

  /** Stateless builder for the IS DEFINED / where-clause wrap; construction is trivial. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private TraversalFilterStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    // Take the head the walker dispatched by class. Defence in depth: re-assert a TraversalFilterStep
    // so a direct mis-call declines cleanly rather than throwing.
    var step = cursor.take();
    if (!(step instanceof TraversalFilterStep<?> filterStep)) {
      return Outcome.DECLINE;
    }
    // A presence filter must follow a pinned node to filter. A null boundary means the step reached
    // the walker before any node was pinned — decline.
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var key = presenceKey(filterStep);
    if (key == null) {
      // Not the has(key) desugar (a general filter, a multi-key or non-value properties step, or a
      // reserved / blank key) — decline the whole traversal to native.
      return Outcome.DECLINE;
    }
    // key IS DEFINED, AND-composed with any filter an earlier step contributed to the same alias.
    ctx.putAliasFilter(boundary, WHERE.wrap(WHERE.isDefined(key)));
    return Outcome.ACCEPTED;
  }

  /**
   * Extracts the property key from a {@code has(key)} desugar, or {@code null} when the step is not
   * that shape. {@code has(key)} is {@code TraversalFilterStep(__.values(key))}, so the filter
   * traversal must be exactly one {@link PropertiesStep} over a single non-blank, non-reserved key.
   * Both properties-step return types are the presence form: an optimisation strategy rewrites the
   * {@code values(key)} ({@link PropertyType#VALUE}) into {@code properties(key)} ({@link
   * PropertyType#PROPERTY}) before this recogniser runs, and both mean "the element has at least one
   * value under {@code key}" — the filter keeps the element iff the property exists, which maps to
   * {@code IS DEFINED} either way. A reserved key (the {@code $} / {@code ~} / {@code @} namespaces)
   * declines for the same reason the predicate adapter declines it: as a bare {@code IS DEFINED}
   * identifier it would resolve as a context variable, a hidden token, or record metadata rather than
   * a plain property. {@code valueMap} / {@code propertyMap} are a distinct {@code PropertyMapStep}
   * class this never matches.
   */
  private static @Nullable String presenceKey(TraversalFilterStep<?> step) {
    var filterTraversal = step.getFilterTraversal();
    if (filterTraversal == null) {
      return null;
    }
    var steps = filterTraversal.getSteps();
    if (steps.size() != 1) {
      // has(key) is exactly one properties/values step; a longer child is a general filter(...).
      return null;
    }
    if (!(steps.getFirst() instanceof PropertiesStep<?> propertiesStep)) {
      return null;
    }
    var returnType = propertiesStep.getReturnType();
    if (returnType != PropertyType.VALUE && returnType != PropertyType.PROPERTY) {
      return null;
    }
    var keys = propertiesStep.getPropertyKeys();
    if (keys.length != 1) {
      // has(key) carries exactly one key; a multi-key values() is a different shape.
      return null;
    }
    var key = keys[0];
    if (key == null || key.isBlank() || WalkerContext.isReservedHasKey(key)) {
      return null;
    }
    return key;
  }
}
