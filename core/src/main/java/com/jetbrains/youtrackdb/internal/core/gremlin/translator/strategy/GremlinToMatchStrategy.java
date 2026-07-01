package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

/**
 * Provider-optimization strategy that rewrites a fully-recognized Gremlin traversal into a
 * YouTrackDB MATCH execution plan and replaces the traversal's entire step list with a single
 * {@link YTDBMatchPlanStep} boundary. Translation is all-or-nothing: if
 * <em>any</em> step is outside the recognized set the strategy declines the whole traversal
 * and the native TinkerPop pipeline — including the three pre-existing YTDB half-measure
 * strategies — keeps handling it verbatim. A translated traversal therefore contains exactly
 * one step (the boundary); a declined traversal is left byte-for-byte unchanged.
 *
 * <h2>Current state — skeleton, declines every shape</h2>
 *
 * <p>This is the strategy skeleton. The whole-traversal walk is delegated to {@link
 * GremlinToMatchTranslator#translate}, which currently declines every input (its walker is
 * wired in a follow-up step). So today {@code apply} runs its gates, calls the facade,
 * receives an empty result, and returns without mutating the traversal — a structural no-op.
 * The splice path ({@link #applyTranslation}) is fully implemented but unreachable until the
 * facade starts recognizing shapes. Landing the skeleton (gating cascade + throw-safety net +
 * kill-switch) before any recognizer runs under the strategy is deliberate: it guarantees the
 * "a throw in {@code apply} can only ever decline, never break a query" invariant holds from
 * the moment the strategy is first registered.
 *
 * <h2>Gating cascade</h2>
 *
 * The strategy declines (returns without mutating the traversal) when any of the following is
 * true, checked in order:
 *
 * <ol>
 *   <li><b>No YouTrackDB session / kill switch.</b> The traversal has no attached {@link
 *       YTDBGraph} (e.g. an anonymous {@code __.V()} detached traversal, or a non-YTDB graph),
 *       or {@link GlobalConfiguration#QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED} is {@code
 *       false} for the traversal's session. Reading the flag per-session gives operators a
 *       runtime kill-switch without a redeploy.</li>
 *   <li><b>Idempotency.</b> The traversal already contains a {@link YTDBMatchPlanStep}
 *       anywhere in its step list. A traversal's strategy chain can be applied more than once
 *       (clone-for-reuse, test-harness re-application, lazy first-iteration apply); leaving an
 *       already-translated traversal alone keeps rewriting deterministic and avoids discarding
 *       a built plan.</li>
 *   <li><b>Non-graph / non-vertex start.</b> The first step is not a start-emitting {@link
 *       GraphStep}, or it is an edge start ({@code g.E()}). The current translator only models
 *       vertex-rooted patterns; edge starts are a later milestone. Gating on the plain TinkerPop
 *       {@link GraphStep} (not {@code
 *       YTDBGraphStep}) is deliberate: this strategy runs <em>before</em> {@code
 *       YTDBGraphStepStrategy} — the sole producer of {@code YTDBGraphStep} — so at translator
 *       time the start step is still a plain {@code GraphStep}. Keying on {@code YTDBGraphStep}
 *       would decline every recognized shape. The check is also ordering-robust, since a
 *       {@code YTDBGraphStep} <em>is</em> a {@code GraphStep}.</li>
 *   <li><b>Empty translation.</b> {@link GremlinToMatchTranslator#translate} returns {@link
 *       Optional#empty()} — no whole-traversal translation is available (always the case in
 *       the current skeleton). Replacing zero steps would be a no-op, so the strategy
 *       returns.</li>
 * </ol>
 *
 * <h2>Throw-safety net</h2>
 *
 * {@code apply} runs inside {@code traversal.applyStrategies()}, which fires on <em>every</em>
 * Gremlin traversal compilation, and the strategy is registered globally for all YTDB graph
 * traversals. An uncaught throwable in {@code apply} would abort compilation for that
 * traversal, and the blast radius of a translator / walker bug would be every Gremlin query
 * the server runs, not only the recognized shapes. The whole body therefore runs inside a
 * {@code try/catch (Throwable)} that turns any failure — a walker bug, a recognizer NPE, a
 * malformed {@code MatchPlanInputs}, a planner exception — into a clean decline: the method
 * returns and the original step list is preserved for the native pipeline (which, under the
 * all-or-nothing rule, is at least as well-served as before). The net degrades a translator bug
 * to native
 * execution rather than a broken query, and it exists from the skeleton so the invariant
 * holds before any recognizer runs under the strategy. The net catches during the walk and
 * the plan build; the actual step-list mutation happens only after both succeed (see {@link
 * #applyTranslation}), so a caught throw always leaves the step list untouched.
 *
 * <h2>Strategy ordering</h2>
 *
 * The strategy declares <b>empty</b> {@link #applyPrior()} and {@link #applyPost()}. Ordering
 * relative to the three half-measure strategies is established the canonical TinkerPop way:
 * each half-measure strategy lists {@code GremlinToMatchStrategy} in its <em>own</em> {@code
 * applyPrior()}, so TinkerPop's topological sort runs this strategy first and the
 * half-measures become the decline fallback. Those half-measure {@code applyPrior()} edits and
 * the registration into the optimization chain land in a follow-up step; this skeleton only
 * declares its own (empty) ordering constraints.
 *
 * <h2>Plan caching</h2>
 *
 * Plans are built via {@code createExecutionPlan(ctx, profiling=false, useCache=false)}.
 * Caching is disabled because the {@link MatchExecutionPlanner#MatchExecutionPlanner(
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs) additive
 * constructor} leaves the inherited {@code statement} field null, which the planner accepts
 * only on the {@code useCache=false} path — a traversal-shape-keyed cache is a later-phase
 * concern.
 *
 * <h2>Testability</h2>
 *
 * The translator is injected through a package-private constructor so unit tests can drive the
 * post-gate splice path with a fixture {@link GremlinToMatchTranslator.TranslationResult}
 * (and can supply a throwing fixture to exercise the throw-safety net) without wiring a real
 * walker. Production code uses {@link #instance()}, which wires the production facade.
 */
public final class GremlinToMatchStrategy
    extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
    implements TraversalStrategy.ProviderOptimizationStrategy {

  /**
   * Empty prior/post set. Ordering is expressed by the half-measure strategies naming this
   * class in their own {@code applyPrior()}, not by this strategy naming them; see the
   * class Javadoc "Strategy ordering" section. The reference to the half-measure classes is
   * kept alive through the Javadoc {@code @link}s below so the ordering contract stays
   * discoverable from here even though the sets are empty.
   *
   * @see YTDBGraphStepStrategy
   * @see YTDBGraphCountStrategy
   * @see YTDBGraphMatchStepStrategy
   */
  private static final Set<Class<? extends ProviderOptimizationStrategy>> NO_ORDERING =
      Set.of();

  private static final GremlinToMatchStrategy INSTANCE =
      new GremlinToMatchStrategy(
          GremlinToMatchTranslator::translate, GremlinToMatchStrategy::buildPlan);

  private final Function<Traversal.Admin<?, ?>,
      Optional<GremlinToMatchTranslator.TranslationResult>> translator;

  private final BiFunction<DatabaseSessionEmbedded, MatchPlanInputs,
      InternalExecutionPlan> planBuilder;

  /**
   * Package-private — tests construct a strategy with a fixture translator (and the production
   * plan builder). Production code goes through {@link #instance()}.
   */
  GremlinToMatchStrategy(
      Function<Traversal.Admin<?, ?>,
          Optional<GremlinToMatchTranslator.TranslationResult>> translator) {
    this(translator, GremlinToMatchStrategy::buildPlan);
  }

  /**
   * Package-private — tests inject both a fixture translator and a fixture plan builder so the
   * splice path ({@link #applyTranslation}) can be exercised with a stub plan, without
   * standing up the real {@link MatchExecutionPlanner}. Production code goes through {@link
   * #instance()}, which wires the production translator and the production plan builder.
   */
  GremlinToMatchStrategy(
      Function<Traversal.Admin<?, ?>,
          Optional<GremlinToMatchTranslator.TranslationResult>> translator,
      BiFunction<DatabaseSessionEmbedded, MatchPlanInputs, InternalExecutionPlan> planBuilder) {
    this.translator = translator;
    this.planBuilder = planBuilder;
  }

  /** Singleton accessor — the strategy is stateless and cheap to share. */
  public static GremlinToMatchStrategy instance() {
    return INSTANCE;
  }

  @Override
  public void apply(Traversal.Admin<?, ?> traversal) {
    // Throw-safety net: the whole body runs on every Gremlin compilation and the
    // strategy is registered globally, so ANY throwable here must degrade to a decline
    // (leave the native step list untouched), never abort compilation. See class Javadoc
    // "Throw-safety net". The mutation in applyTranslation runs only after the walk and the
    // plan build both succeed, so a caught throw always leaves the step list unmodified.
    try {
      applyOrDecline(traversal);
    } catch (Throwable t) {
      // Swallow deliberately: translation is a best-effort optimization. A failure declines
      // to the native pipeline, which handles the traversal correctly. Rethrowing would
      // break every Gremlin query, recognized or not.
      declineOnThrow(traversal, t);
    }
  }

  /**
   * The gate-and-translate body, extracted so the throw-safety net in {@link #apply} wraps a
   * single call. Returns without mutating the traversal on any decline; on a non-empty
   * translation it splices the boundary step in place of the whole step list.
   */
  private void applyOrDecline(Traversal.Admin<?, ?> traversal) {
    var session = resolveSessionIfEnabled(traversal);
    if (session == null) {
      return;
    }
    if (containsBoundaryStep(traversal)) {
      return;
    }
    if (!hasVertexGraphStart(traversal)) {
      return;
    }
    var translation = translator.apply(traversal);
    if (translation.isEmpty()) {
      return;
    }
    applyTranslation(traversal, session, translation.get());
  }

  /**
   * Hook for the throw-safety net so a caught throwable has a single, greppable landing
   * point. Currently a no-op beyond the decline (the traversal is already unmodified because
   * the mutation runs last); kept as a seam for future diagnostics (e.g. a debug-level log or
   * a metric) without widening the catch block in {@link #apply}.
   */
  @SuppressWarnings("unused")
  private static void declineOnThrow(Traversal.Admin<?, ?> traversal, Throwable ignored) {
    // Intentionally empty: the decline is the absence of a mutation. See apply()'s catch.
  }

  /**
   * Returns the traversal's session iff a YouTrackDB graph is attached and the kill-switch is
   * on, or {@code null} to signal "decline".
   *
   * <p>The {@code instanceof YTDBGraph} gate decides at the graph level so {@code tx()} is
   * never called on a graph that does not support transactions: a detached anonymous
   * traversal ({@code __.V()}) reports TinkerPop's {@code EmptyGraph}, whose {@code tx()}
   * throws {@code UnsupportedOperationException}, and a non-YTDB graph would fail the cast on
   * {@code graph.tx()}. Reading the flag from the session's {@code ContextConfiguration}
   * (rather than the JVM-global {@link GlobalConfiguration}) lets operators — and tests — flip
   * it per-session without mutating global state.
   */
  @Nullable private static DatabaseSessionEmbedded resolveSessionIfEnabled(
      Traversal.Admin<?, ?> traversal) {
    var graph = traversal.getGraph().orElse(null);
    if (!(graph instanceof YTDBGraph)) {
      return null;
    }
    if (!(graph.tx() instanceof YTDBTransaction tx)) {
      return null;
    }
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var enabled =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    return enabled ? session : null;
  }

  /**
   * Scans the entire step list and returns {@code true} as soon as a {@link YTDBMatchPlanStep}
   * is found (the idempotency gate). The scan covers the whole list, not just the start
   * step, because a wrapping traversal source or test harness could place ordinary steps in
   * front of a previously-translated boundary.
   */
  private static boolean containsBoundaryStep(Traversal.Admin<?, ?> traversal) {
    for (Step<?, ?> step : traversal.getSteps()) {
      if (step instanceof YTDBMatchPlanStep<?, ?>) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} iff the traversal's start step is a vertex-emitting {@link GraphStep}.
   * Gates on the plain TinkerPop {@code GraphStep} rather than {@code YTDBGraphStep} because
   * this strategy runs before {@code YTDBGraphStepStrategy} produces the YTDB subclass; see the
   * class Javadoc "Gating cascade". {@code GraphStep.returnsVertex()} distinguishes {@code
   * g.V()} (accept) from {@code g.E()} (decline — edge starts are a later milestone).
   */
  private static boolean hasVertexGraphStart(Traversal.Admin<?, ?> traversal) {
    return traversal.getStartStep() instanceof GraphStep<?, ?> graphStep
        && graphStep.returnsVertex();
  }

  /**
   * Replaces the traversal's entire step list with a single {@link YTDBMatchPlanStep} built
   * from {@code translation} (all-or-nothing). Builds the plan eagerly via {@link
   * MatchExecutionPlanner#createExecutionPlan} so the boundary step has its plan ready before
   * any traverser flows through it, then swaps the step list.
   *
   * <p>Plan build order matters for the throw-safety net: the plan is built <em>before</em>
   * the step list is mutated, so a planner throw is caught by {@link #apply}'s net with the
   * original step list still intact. Only once the plan exists does {@code removeAllSteps} +
   * {@code addStep} run, and those TinkerPop calls do not throw for a well-formed boundary.
   *
   * <p>The plan is built with caching disabled (see class Javadoc): the inherited {@code
   * statement} field on the planner stays {@code null}, which the planner accepts only when
   * {@code useCache=false}.
   */
  private void applyTranslation(
      Traversal.Admin<?, ?> traversal,
      DatabaseSessionEmbedded session,
      GremlinToMatchTranslator.TranslationResult translation) {
    InternalExecutionPlan plan = planBuilder.apply(session, translation.inputs());
    replaceAllStepsWithBoundary(traversal, plan, translation);
  }

  /**
   * Production plan builder: routes the translated {@link MatchPlanInputs} through the additive
   * {@link MatchExecutionPlanner#MatchExecutionPlanner(MatchPlanInputs) constructor} and builds
   * the plan eagerly. Caching is disabled — the inherited {@code statement} field on the
   * planner stays {@code null}, which the planner accepts only when {@code useCache=false} (see
   * class Javadoc "Plan caching").
   */
  private static InternalExecutionPlan buildPlan(
      DatabaseSessionEmbedded session, MatchPlanInputs inputs) {
    var ctx = new BasicCommandContext(session);
    return new MatchExecutionPlanner(inputs)
        .createExecutionPlan(ctx, /* enableProfiling */ false, /* useCache */ false);
  }

  /**
   * Removes every existing step and installs the boundary step as the traversal's sole step.
   * Raw types are unavoidable here: the strategy receives a {@link Traversal.Admin}{@code <?,
   * ?>} (the {@code TraversalStrategy} contract) but the boundary step's {@code <S, E>} type
   * variables have no concrete binding at this point, so both collapse into the same raw
   * container. {@link GremlinToMatchTranslator.TranslationResult#returnClass()} is the runtime
   * source of truth for the emitted element class.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void replaceAllStepsWithBoundary(
      Traversal.Admin<?, ?> traversalRaw,
      InternalExecutionPlan plan,
      GremlinToMatchTranslator.TranslationResult translation) {
    Traversal.Admin traversal = traversalRaw;
    var boundary =
        new YTDBMatchPlanStep(
            traversal,
            translation.returnClass(),
            plan,
            translation.boundaryAlias(),
            translation.outputType());
    TraversalHelper.removeAllSteps(traversal);
    traversal.addStep(boundary);
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return NO_ORDERING;
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPost() {
    return NO_ORDERING;
  }
}
