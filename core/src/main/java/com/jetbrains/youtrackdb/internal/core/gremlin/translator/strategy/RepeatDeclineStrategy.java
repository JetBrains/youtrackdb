package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.RepeatUnrollStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps {@link GremlinToMatchStrategy} away from any traversal that was written with {@code
 * repeat(...)}. Variable-depth repetition is out of scope for the current translator, and this is
 * the strategy that makes the decline hold even after another strategy has erased the evidence.
 *
 * <h2>Why a separate strategy is needed</h2>
 *
 * <p>TinkerPop's {@code RepeatUnrollStrategy} rewrites {@code repeat(__.out()).times(n)} into n
 * chained {@code VertexStep}s separated by {@code NoOpBarrierStep}s. {@link GremlinStepWalker}
 * treats {@code NoOpBarrierStep} as transparent, so the unrolled form is byte-for-byte the shape a
 * hand-written n-hop chain produces and no recogniser can tell the two apart. The translator folded
 * the unrolled chain into one MATCH pattern, and MATCH enumerates paths rather than merging
 * traversers into bulks the way the barriers do — on the TinkerPop grateful-dead fixture {@code
 * repeat(__.out()).times(8)} has 2,505,037,961,767,380 paths, so the query never returned while
 * native Gremlin answered it in milliseconds. Strategy categories are applied in a fixed order and
 * the unroll is an {@code OptimizationStrategy} while the translator is a {@code
 * ProviderOptimizationStrategy}, so the unroll always runs first and the translator can never see
 * the {@link RepeatStep} itself.
 *
 * <p>A {@code DecorationStrategy} runs before every optimization, which is early enough to see the
 * {@link RepeatStep}, and the decision it records survives into the provider-optimization pass.
 *
 * <h2>How the veto is recorded</h2>
 *
 * <p>The veto adds the {@link Veto} marker to a clone of the traversal's own strategy list. The
 * marker carries no behaviour; {@link GremlinToMatchStrategy} reads it and declines. Recording the
 * veto as the presence of a marker, rather than as the absence of the translator, is what keeps it
 * from over-reaching. A sub-traversal's own strategy list normally comes from {@code EmptyGraph} and
 * never carried the translator to begin with, so "the translator is missing from this list" cannot
 * separate a vetoed traversal from an ordinary child, and a check keyed on it declines every
 * sub-traversal in the process.
 *
 * <p>Every traversal whose subtree carries a {@link RepeatStep} is marked, not the root alone.
 * {@code Traversal.Admin.applyStrategies} applies each strategy to the root and to every descendant
 * traversal, and the translator likewise sees each of them, so the veto has to reach each of them
 * too. The shape that needs this is a child holding the {@code repeat} and starting at its own
 * {@code V()} — {@code g.V().union(__.V().repeat(__.out()).times(8).count(), __.identity())}. Its
 * session resolves through the parent and a mid-traversal {@code V()} is a vertex-emitting {@code
 * GraphStep}, so once the unroll has flattened the repeat the translator folds that child on its own
 * account. (A child built from the traversal source would carry the graph's own strategy list and be
 * the sharper version of the same shape, but {@code Bytecode.convertArgument} rejects any child
 * argument carrying source instructions, so the fluent API cannot construct one.)
 *
 * <p>The list is replaced through {@code clone()} rather than mutated in place. {@code
 * traversal.getStrategies()} returns the process-wide {@code TraversalStrategies.GlobalCache}
 * singleton registered for the graph class — one object shared by every graph instance and every
 * thread in the JVM — its backing collection is a plain {@code LinkedHashSet}, and {@code
 * applyStrategies} holds a fail-fast iterator over it for the whole compilation. An in-place edit
 * therefore raises {@link java.util.ConcurrentModificationException} in every thread that is
 * compiling a traversal against that graph class at the time, not only in the editing one.
 * Replacing this traversal's own reference keeps the edit local to this one traversal.
 *
 * <p>Because the replacement lands after the iteration has already captured the old set, the
 * translator is still invoked in this pass; it honours the marker itself by reading the traversal's
 * strategy list before translating. That check lives in {@link GremlinToMatchStrategy}.
 *
 * <h2>What it deliberately leaves alone</h2>
 *
 * <p>The veto never touches {@link RepeatUnrollStrategy}. Dropping the unroll would take the
 * barriers with it and move the non-termination from MATCH into the native pipeline, which is the
 * fallback this decline depends on. Leaving the unroll in place means a declined traversal executes
 * exactly as it does with the translator switched off.
 *
 * <h2>Why the kill-switch is not consulted</h2>
 *
 * <p>The marker is added whether or not {@code QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED} is on.
 * Two reads of that flag — one here, one in the translator — can disagree, because it is a runtime
 * kill-switch any thread may flip while a compilation is in flight: an "off" read here followed by
 * an "on" read there translates the unrolled repeat, which is the outcome this strategy exists to
 * prevent. Marking unconditionally changes no behaviour on the disabled arm, because the translator
 * declines at its own session gate — with the translator off, a marked traversal and an unmarked
 * one compile to the same native step list.
 *
 * <h2>Cost</h2>
 *
 * <p>A traversal with no {@code repeat} pays one recursive step-tree scan and stops: no session
 * resolution, no transaction, no allocation past the scan's iterators. A repeat-bearing traversal
 * additionally pays {@code clone()} plus {@code addStrategies}, and {@code addStrategies} re-runs
 * {@code TraversalStrategies.sortStrategies} over the whole list — roughly 19 us on the production
 * 23-strategy list, against 0.7 us for the clone alone. Nothing reads that sorted order in the pass
 * that pays for it, since {@code applyStrategies} captured its iterator before the first strategy
 * ran and the veto needs membership only. The cost is accepted rather than memoized: it lands once
 * per repeat-bearing compilation, on a traversal the translator is about to decline anyway.
 *
 * <p>The re-sort has one observable beyond its cost. TinkerPop's sort orders strategies by category
 * and by the {@code applyPrior} / {@code applyPost} edges they declare, and resolves everything else
 * by the iteration order of the maps it builds — {@code RepeatUnrollStrategy}, for one, declares no
 * constraints at all. Sorting a set with one more element in it can therefore hand a repeat-bearing
 * traversal a different order among the unconstrained optimizations than an unmarked traversal gets.
 * Every one of those optimizations preserves semantics, so the answer does not move; the native plan
 * can. {@code g.V().repeat(__.out()).times(n).count()} is the case to know about: whether {@code
 * AdjacentToIncidentStrategy} rewrites the last unrolled hop into an edge hop turns on exactly that
 * unconstrained position.
 */
public final class RepeatDeclineStrategy
    extends AbstractTraversalStrategy<TraversalStrategy.DecorationStrategy>
    implements TraversalStrategy.DecorationStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(RepeatDeclineStrategy.class);

  private static final RepeatDeclineStrategy INSTANCE = new RepeatDeclineStrategy();

  private RepeatDeclineStrategy() {
  }

  /** Singleton accessor — the strategy is stateless and cheap to share. */
  public static RepeatDeclineStrategy instance() {
    return INSTANCE;
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    // Throw-safety net, the same invariant GremlinToMatchStrategy documents for itself: this
    // strategy is registered globally and runs on every Gremlin compilation, so a failure here has
    // to degrade to "no veto" instead of aborting the query. The catch is narrowed to
    // RuntimeException, so Error and AssertionError still propagate.
    try {
      // Cheapest gate first: a traversal with no repeat anywhere in its subtree is the
      // overwhelmingly common case and pays this scan and nothing else.
      if (!TraversalHelper.hasStepOfAssignableClassRecursively(RepeatStep.class, traversal)) {
        return;
      }
      var strategies = traversal.getStrategies();
      // Idempotent: a traversal that already carries the marker — a re-applied strategy chain, or a
      // list a vetoed parent pushed down when it locked — needs no second clone.
      if (strategies.getStrategy(Veto.class).isPresent()) {
        return;
      }
      traversal.setStrategies(strategies.clone().addStrategies(Veto.instance()));
    } catch (RuntimeException e) {
      // Skipping the veto yields no wrong answer — the translator's own gates then decide, as they
      // did before this strategy existed — so record it at DEBUG and leave the traversal alone.
      LogManager.instance()
          .debug(
              RepeatDeclineStrategy.class,
              "Repeat decline skipped after an unexpected exception; the translator's own gates"
                  + " decide for this traversal",
              LOGGER,
              e);
    }
  }

  /**
   * Marker recording "the translator must leave this traversal alone". It carries no behaviour:
   * {@code apply} does nothing, and the marker is never registered on a graph — {@link
   * RepeatDeclineStrategy} adds it to one traversal's cloned strategy list, and {@link
   * GremlinToMatchStrategy} reads it there. Declaring it a {@code DecorationStrategy} keeps
   * TinkerPop's category sort from placing it among the strategies that do work.
   */
  static final class Veto extends AbstractTraversalStrategy<TraversalStrategy.DecorationStrategy>
      implements TraversalStrategy.DecorationStrategy {

    private static final Veto INSTANCE = new Veto();

    private Veto() {
    }

    static Veto instance() {
      return INSTANCE;
    }

    @Override
    public void apply(Admin<?, ?> traversal) {
      // Nothing to do. RepeatDeclineStrategy took the decision this entry stands for, and the pass
      // that adds the marker never applies it — applyStrategies captured its iterator first.
    }
  }
}
