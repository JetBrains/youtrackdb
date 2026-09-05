package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AliasPropertyPresence;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/**
 * Adds the {@code IS DEFINED} conjunct a key-side {@code by(key)} modulator implies, on the alias
 * the modulator reads.
 *
 * <p>A Gremlin {@code by(...)} modulator is a traversal, not a field reference: {@code
 * order().by("age")} maps each element through {@code values("age")}, and an element with no
 * {@code age} produces nothing, so its traverser is dropped. {@code g.V().order().by("age")} on the
 * modern graph therefore emits four vertices, not six. SQL has no such rule — {@code ORDER BY
 * v.age} keeps every row and sorts the missing ones as {@code null}, {@code GROUP BY v.age}
 * collects them into a {@code null} bucket, and a following {@code count()} counts them. Every one
 * of those is a silently larger multiset than Gremlin's, so each recogniser that consumes a
 * key-side modulator calls {@link #requireModulatedProperty} on the alias it modulates.
 *
 * <p>{@code IS DEFINED} rather than {@code IS NOT NULL}: TinkerPop's rule is
 * {@code Property.isPresent()}, so a property stored with a literal {@code null} value is present
 * and its traverser survives. That is the same entity-layer view {@code has(key)} maps to.
 */
final class ByModulatorPresence {

  /** Shared builder — construction is trivial and the builder is stateless. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private ByModulatorPresence() {
    // Static helper — no instances.
  }

  /**
   * Contributes {@code key IS DEFINED} on {@code alias} when {@code modulator} reads a property.
   * A no-op for record-attribute modulators ({@code by(T.id)} / {@code by(T.label)}), for a null
   * or identity modulator, and for any shape {@link ByModulatorTranslator} does not classify — the
   * caller declines those independently, so this method never has to gate on recognisability.
   */
  static void requireModulatedProperty(
      RecognitionContext ctx, String alias, Traversal.Admin<?, ?> modulator) {
    ByModulatorTranslator.keyModulatorPropertyKey(modulator)
        .ifPresent(key -> requireModulatedProperty(ctx, alias, key));
  }

  /**
   * Contributes {@code key IS DEFINED} on {@code alias} for an already-resolved modulator key, or
   * nothing when {@code ProductiveByStrategy} makes that key productive — see {@link
   * RecognitionContext#byModulatorIsProductive}, where the whole inversion is explained.
   */
  static void requireModulatedProperty(RecognitionContext ctx, String alias, String key) {
    if (ctx.byModulatorIsProductive(key)) {
      return;
    }
    contribute(ctx, alias, key);
  }

  /**
   * Post-plan presence for {@code select(…).by(key)}: returns an {@link AliasPropertyPresence} for
   * whole-row drop and map emit, or empty when the modulator is not a property key or is productive
   * under {@code ProductiveByStrategy}. Never contributes pattern {@code IS DEFINED} — see
   * {@link SelectStepRecogniser}. The caller appends the entity RETURN column named by
   * {@link ResultShaping#presenceEntityColumnAlias(String)}.
   *
   * <p>WHY NOT CONTRIBUTE THE CONJUNCT EAGERLY. An early {@code key IS DEFINED} would filter the
   * join for a sparse key, and on a one-percent key that is a hundredfold difference in join
   * work. It is withheld anyway, because the conjunct is not free: the MATCH root estimator has no
   * selectivity for a presence predicate, so an unselective conjunct wins the root slot on
   * selectivity it does not have. That is not a hypothetical — a same-class hop measurably loses
   * its index-ordered plan to it, which
   * {@code PresenceConditionRootChoiceTest} pins. The saving is unmeasured and the loss is
   * measured, so the conjunct waits until something needs it.
   *
   * <p>A SLICE IS WHAT NEEDS IT, and there the conjunct is not an optimization but a correctness
   * requirement: {@code LIMIT} must cut survivors, not rows the post-plan drop has yet to remove.
   * {@link RecognitionContext#promotePresenceDropToPatternFilter} promotes these presences on
   * demand for that case, pinned by
   * {@code ProjectionEquivalenceTest.selectByThenSlice_translatesAndCountsSurvivors}.
   *
   * @param mapKey the {@code select} label under which the value is emitted
   */
  static Optional<AliasPropertyPresence> aliasPresenceForEmit(
      RecognitionContext ctx, String internalAlias, Traversal.Admin<?, ?> modulator,
      String mapKey) {
    var keyOpt = ByModulatorTranslator.keyModulatorPropertyKey(modulator);
    if (keyOpt.isEmpty()) {
      return Optional.empty();
    }
    var key = keyOpt.get();
    if (ctx.byModulatorIsProductive(key)) {
      return Optional.empty();
    }
    return Optional.of(
        new AliasPropertyPresence(
            ResultShaping.presenceEntityColumnAlias(internalAlias), key, mapKey));
  }

  /**
   * Contributes {@code key IS DEFINED} for the drop a {@code values(key)} step performs in its own
   * right, which no by-modulator strategy can invert. {@code ProductiveByStrategy.apply} iterates
   * {@code ByModulating} steps that are also {@code TraversalParent}s, and a {@code PropertiesStep}
   * is neither, so {@code values(key)} drops an element without the property whatever the strategy
   * says. Gating this on {@link RecognitionContext#byModulatorIsProductive} would suppress a filter
   * the native pipeline still applies: {@code g.withStrategies(ProductiveByStrategy).V()
   * .values("foo").sum()} would emit {@code 0} where Gremlin emits no traverser.
   */
  static void requireProjectedProperty(RecognitionContext ctx, String alias, String key) {
    contribute(ctx, alias, key);
  }

  /**
   * @implNote The conjunct is the only filter on the alias whenever the traversal wrote none of its
   *     own, and the MATCH cost model reads any filter as a narrowing:
   *     {@code MatchExecutionPlanner.estimateRootEntries} scores an unfiltered alias
   *     {@code classCount + 1} and a filtered one {@code min(filter.estimate(…), classCount)}, while
   *     {@code SQLWhereClause.estimate} has no estimator for {@code IS DEFINED} and falls back to
   *     {@code classCount / 2}. A presence-only alias therefore looks twice as selective as an
   *     unfiltered one and can win the root slot, scheduling a chain from its tail. The conjunct is
   *     correct and has to stay; the gap is that the estimator has no notion of a presence
   *     predicate, which is executor-side work outside the translator.
   */
  private static void contribute(RecognitionContext ctx, String alias, String key) {
    ctx.recordPresenceConjunct(alias, key);
    ctx.putAliasFilter(alias, WHERE.wrap(WHERE.isDefined(key)));
  }
}
