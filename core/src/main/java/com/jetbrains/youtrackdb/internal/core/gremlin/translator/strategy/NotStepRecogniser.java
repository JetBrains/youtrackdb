package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.structure.PropertyType;

/**
 * Recogniser for {@link NotStep}, the single runtime class TinkerPop uses for both {@code
 * hasNot(key)} (which desugars to {@code NotStep(__.values(key))}) and logical {@code not(traversal)}.
 * The fork's {@code NotStep} is {@code final}, so one recogniser registered under {@code
 * NotStep.class} branches load-bearing shapes in this order:
 *
 * <ol>
 *   <li><b>{@code hasNot(key)} presence</b> — a single-child {@link PropertiesStep} over one
 *       non-reserved key ({@link PropertyType#VALUE} or {@link PropertyType#PROPERTY}, mirroring
 *       {@link TraversalFilterStepRecogniser}) maps to {@link MatchWhereBuilder#isNotDefined}.
 *   <li><b>Pure-filter logical NOT</b> — sub-walk with no hops; the captured boundary filter is
 *       wrapped in {@link MatchWhereBuilder#not(...)} and AND-merged into the boundary alias.
 *   <li><b>Edge-bearing logical NOT</b> — sub-walk with hops; a detached {@link
 *       com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression} is appended via
 *       {@link RecognitionContext#addNotMatchExpression}, with the NOT origin pre-validated against
 *       the positive pattern ({@link RecognitionContext#positivePatternHasAlias}).
 * </ol>
 */
final class NotStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final NotStepRecogniser INSTANCE = new NotStepRecogniser();

  /** Stateless builder for {@code IS NOT DEFINED}, WHERE NOT, and clause wrapping. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private NotStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof NotStep<?> notStep)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }

    // Branch the values/properties-child presence form before any generic sub-walk — the child
    // PropertiesStep has no dedicated recogniser yet, so this must run first.
    var hasNotKey = hasNotPresenceKey(notStep);
    if (hasNotKey != null) {
      ctx.putAliasFilter(boundary, WHERE.wrap(WHERE.isNotDefined(hasNotKey)));
      return Outcome.ACCEPTED;
    }

    var children = notStep.getLocalChildren();
    if (children.size() != 1) {
      return Outcome.DECLINE;
    }

    var adapter = ctx.walkChild(children.getFirst());
    if (adapter.outcome() != Outcome.ACCEPTED) {
      return Outcome.DECLINE;
    }

    if (!adapter.hasEdges()) {
      var expr = ConnectiveStepSupport.singleCapturedFilter(adapter, boundary);
      if (expr == null) {
        return Outcome.DECLINE;
      }
      ctx.putAliasFilter(boundary, WHERE.wrap(WHERE.not(expr)));
      return Outcome.ACCEPTED;
    }

    // Edge-bearing NOT — the NOT origin must already exist in the positive pattern.
    if (!ctx.positivePatternHasAlias(boundary)) {
      return Outcome.DECLINE;
    }

    // manageNotPatterns requires a bare NOT origin item — filters on the origin alias cannot be
    // attached to the detached expression. A sub-walk like not(has(city).out(knows)) captures the
    // has on the boundary alias; accepting would emit a filterless NOT anti-join (wrong multiset).
    if (edgeBearingNotCapturesUnsupportedOriginConstraints(adapter, boundary)) {
      return Outcome.DECLINE;
    }

    try {
      var notExpr =
          adapter
              .capturedPattern()
              .buildNotExpression(boundary, adapter.capturedAliasFilters());
      ctx.addNotMatchExpression(notExpr);
      return Outcome.ACCEPTED;
    } catch (IllegalArgumentException e) {
      return Outcome.DECLINE;
    }
  }

  /**
   * Extracts the property key from a {@code hasNot(key)} desugar, or {@code null} when the step is a
   * general logical {@code not(traversal)}. {@code hasNot(key)} is {@code NotStep(__.values(key))};
   * an optimisation strategy may rewrite the child to {@code properties(key)} before g2m runs, and
   * both mean "the element does not have the property", which maps to {@code IS NOT DEFINED}.
   */
  private static @Nullable String hasNotPresenceKey(NotStep<?> step) {
    var children = step.getLocalChildren();
    if (children.size() != 1) {
      return null;
    }
    var childSteps = children.getFirst().getSteps();
    if (childSteps.size() != 1) {
      return null;
    }
    if (!(childSteps.getFirst() instanceof PropertiesStep<?> propertiesStep)) {
      return null;
    }
    var returnType = propertiesStep.getReturnType();
    if (returnType != PropertyType.VALUE && returnType != PropertyType.PROPERTY) {
      return null;
    }
    var keys = propertiesStep.getPropertyKeys();
    if (keys.length != 1) {
      return null;
    }
    var key = keys[0];
    if (key == null || key.isBlank() || WalkerContext.isReservedHasKey(key)) {
      return null;
    }
    return key;
  }

  /**
   * Returns {@code true} when the edge-bearing sub-walk captured constraints on the NOT origin alias
   * that {@link com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder#buildNotExpression}
   * cannot express (bare origin only; supplemental filters merge onto hop targets, not the origin).
   */
  private static boolean edgeBearingNotCapturesUnsupportedOriginConstraints(
      SubTraversalPredicateAdapter adapter, String boundary) {
    if (adapter.capturedAliasFilters().containsKey(boundary)) {
      return true;
    }
    return adapter.capturedPattern().registeredAliasClasses().containsKey(boundary);
  }
}
