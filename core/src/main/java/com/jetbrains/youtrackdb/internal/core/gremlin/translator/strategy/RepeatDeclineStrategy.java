package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.RepeatUnrollStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
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
 * <p>The veto swaps the traversal's {@code TraversalStrategies} reference for a {@link
 * VetoedStrategies} view of the same list. That view forwards every operation to the list it wraps
 * and adds one bit of its own — its type. {@link #isVetoed} reads that bit; {@link
 * GremlinToMatchStrategy} calls it and declines. Nothing is added to, removed from, or reordered
 * within the strategy list itself, so a vetoed traversal compiles through exactly the strategies, in
 * exactly the order, an unvetoed one does.
 *
 * <p>Recording the veto as the presence of a marker, rather than as the absence of the translator,
 * is what keeps it from over-reaching. A sub-traversal's own strategy list normally comes from
 * {@code EmptyGraph} and never carried the translator to begin with, so "the translator is missing
 * from this list" cannot separate a vetoed traversal from an ordinary child, and a check keyed on it
 * declines every sub-traversal in the process.
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
 * <p>Marking one traversal must not mark its neighbours, and the wrapper is per-traversal for that
 * reason. Each traversal in a tree holds its own {@code TraversalStrategies} reference until {@code
 * lock()} runs — a root's list comes from the graph's {@code GlobalCache}, a child's from {@code
 * EmptyGraph}'s — so replacing one reference reaches that traversal and nothing else. A vetoed root
 * therefore leaves a repeat-free sibling or child free to translate on its own account, which is the
 * property {@code RepeatDeclineStrategyTest} pins directly.
 *
 * <p>The reference is replaced rather than the list mutated in place. {@code
 * traversal.getStrategies()} returns the process-wide {@code TraversalStrategies.GlobalCache}
 * singleton registered for the graph class — one object shared by every graph instance and every
 * thread in the JVM — its backing collection is a plain {@code LinkedHashSet}, and {@code
 * applyStrategies} holds a fail-fast iterator over it for the whole compilation. An in-place edit
 * therefore raises {@link java.util.ConcurrentModificationException} in every thread that is
 * compiling a traversal against that graph class at the time, not only in the editing one. The
 * wrapper never edits the list it wraps, so the singleton is left exactly as it was found.
 *
 * <p>Because the replacement lands after the iteration has already captured the old set, the
 * translator is still invoked in this pass; it honours the marker itself by calling {@link
 * #isVetoed} before translating. That check lives in {@link GremlinToMatchStrategy}.
 *
 * <h2>Channels that were measured and rejected</h2>
 *
 * <p>{@code traversal.getSideEffects()} is the obvious per-traversal channel and it fails twice,
 * measured rather than argued. A single side-effect key of any name flips {@code
 * getTraverserRequirements()} from {@code [BULK, OBJECT]} to {@code [BULK, OBJECT, SIDE_EFFECTS]} —
 * {@code DefaultTraversal} adds {@link TraverserRequirement#SIDE_EFFECTS} whenever {@code
 * getSideEffects().keys()} is non-empty — which swaps the traverser generator from {@code
 * B_O_TraverserGenerator} to {@code B_O_S_SE_SL_TraverserGenerator}. That is a change to the native
 * execution path of every repeat-bearing traversal, including with the translator switched off,
 * which is the deviation this carrier exists to remove. And a traversal shares one {@code
 * TraversalSideEffects} instance with its direct children: for {@code g.V().union(a, b)} both
 * children read the root's object, so a plain boolean key marked at the root would veto every
 * sibling and withdraw translation from correct shapes.
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
 * additionally pays one {@link VetoedStrategies} allocation and a field write. It pays no {@code
 * clone()} of the strategy list and no {@code TraversalStrategies.sortStrategies} — the earlier
 * clone-and-add form cost roughly 19 us on the production 23-strategy list, of which the re-sort was
 * all but 0.7 us.
 *
 * <p>Avoiding the re-sort matters beyond its cost. TinkerPop's sort orders strategies by category
 * and by the {@code applyPrior} / {@code applyPost} edges they declare, and resolves everything else
 * by the iteration order of the maps it builds — {@code RepeatUnrollStrategy}, for one, declares no
 * constraints at all. Sorting a set with one more element in it can hand a repeat-bearing traversal
 * a different order among the unconstrained optimizations than an unmarked traversal gets. Every one
 * of those optimizations preserves semantics, so the answer would not move; the native plan could.
 * {@code g.V().repeat(__.out()).times(n).count()} is the case to know about: whether {@code
 * AdjacentToIncidentStrategy} rewrites the last unrolled hop into an edge hop turns on exactly that
 * unconstrained position. A wrapper that forwards {@code iterator()} leaves the order alone, so the
 * translator-off arm compiles a repeat-bearing traversal to the same native step list it would
 * without this strategy registered at all.
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
      // list a vetoed parent pushed down when it locked — needs no second wrapper. Double-wrapping
      // would still read as vetoed, but each layer adds a hop to every forwarded call.
      if (strategies instanceof VetoedStrategies) {
        return;
      }
      traversal.setStrategies(new VetoedStrategies(strategies));
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
   * Reads the veto off {@code traversal}. The single reader in production is {@link
   * GremlinToMatchStrategy}; the marker means "this traversal was written with {@code repeat(...)},
   * do not translate it" and says nothing about any other traversal in the same tree.
   *
   * <p>Answering from the reference's type rather than from the list's contents is what makes the
   * read O(1) and the mark free of ordering effects. Note that after {@code lock()} a traversal's
   * reference is overwritten with its parent's, so a descendant of a vetoed root reads as vetoed
   * once compilation has finished; every production read happens before that, during the strategy
   * pass, while each traversal still holds its own reference. Both halves of that — the propagation
   * and the read's position relative to it — are measured in {@code RepeatDeclineStrategyTest}, so a
   * reader added after {@code lock()} shows up there as a translation loss rather than as silence.
   */
  static boolean isVetoed(Admin<?, ?> traversal) {
    return traversal.getStrategies() instanceof VetoedStrategies;
  }

  /**
   * The veto carrier: a {@link TraversalStrategies} view that forwards every operation to the list
   * it wraps and carries the veto in its own type. It contributes no strategy, so {@code iterator()}
   * yields the wrapped list unchanged and {@code TraversalStrategies.sortStrategies} never runs.
   *
   * <p>The wrapped list may be the process-wide {@code GlobalCache} singleton, so this class must
   * never mutate it on its own account. It does not: the two mutators below forward verbatim, which
   * leaves a caller holding a wrapped list in exactly the position it would be in holding the
   * unwrapped one. Every method here is transparent except {@link #clone()}, which keeps the veto on
   * the copy — a clone of a vetoed traversal describes the same repeat-bearing query, and keeping
   * the decline is the safe direction to err in.
   */
  static final class VetoedStrategies implements TraversalStrategies {

    private static final long serialVersionUID = 1L;

    private final TraversalStrategies delegate;

    VetoedStrategies(TraversalStrategies delegate) {
      this.delegate = delegate;
    }

    @Override
    public Iterator<TraversalStrategy<?>> iterator() {
      return delegate.iterator();
    }

    @Override
    public List<TraversalStrategy<?>> toList() {
      return delegate.toList();
    }

    @Override
    public <T extends TraversalStrategy> Optional<T> getStrategy(Class<T> strategyClass) {
      return delegate.getStrategy(strategyClass);
    }

    @Override
    public TraversalStrategies addStrategies(TraversalStrategy<?>... strategies) {
      delegate.addStrategies(strategies);
      return this;
    }

    @SafeVarargs
    @Override
    public final TraversalStrategies removeStrategies(
        Class<? extends TraversalStrategy>... strategyClasses) {
      delegate.removeStrategies(strategyClasses);
      return this;
    }

    @Override
    public TraversalStrategies clone() {
      return new VetoedStrategies(delegate.clone());
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
