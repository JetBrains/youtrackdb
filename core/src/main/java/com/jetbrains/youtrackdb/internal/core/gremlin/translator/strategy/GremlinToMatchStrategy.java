package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider-optimization strategy that rewrites a fully-recognized Gremlin traversal into a
 * YouTrackDB MATCH execution plan and replaces the traversal's entire step list with a single
 * {@link YTDBMatchPlanStep} boundary. Translation is all-or-nothing: if
 * <em>any</em> step is outside the recognized set the strategy declines the whole traversal
 * and the native TinkerPop pipeline — including the three pre-existing YTDB half-measure
 * strategies — keeps handling it verbatim. A translated traversal therefore contains exactly
 * one step (the boundary); a declined traversal is left byte-for-byte unchanged.
 *
 * <h2>Current state — recognizes the vertex source only</h2>
 *
 * <p>The whole-traversal walk is delegated to {@link GremlinToMatchTranslator#translate},
 * which drives the shared {@code GremlinStepWalker} + {@code StepRecogniser} registry. Phase 1
 * recognizes only the vertex source ({@code g.V()} / {@code g.V(ids)}); any traversal carrying
 * an unrecognized step declines whole and stays on the native pipeline. On a recognized shape
 * {@code apply} runs its gates, receives a non-empty translation, and splices the boundary
 * step in place of the entire step list ({@link #applyTranslation}). Landing the gating
 * cascade + throw-safety net + kill-switch before any recognizer ran under the strategy was
 * deliberate: it guarantees the "a translator bug in {@code apply} can only ever decline, never break
 * a query" invariant holds from the moment the strategy is first registered. The one throw meant to
 * reach the caller is a {@link ReservedAliasException} — a prohibited user alias in the reserved
 * {@code $} namespace — which the net re-throws rather than degrades (see "Throw-safety net").
 *
 * <h2>Gating cascade</h2>
 *
 * The strategy declines (returns without mutating the traversal) when any of the following is
 * true, checked in order:
 *
 * <ol>
 *   <li><b>No YouTrackDB session / kill switch.</b> The traversal has no attached {@link
 *       YTDBGraph} (e.g. an anonymous {@code __.V()} detached traversal, or a non-YTDB graph),
 *       the session exposes no {@code ContextConfiguration} (its {@code getConfiguration()} is
 *       {@code @Nullable}), or {@link
 *       GlobalConfiguration#QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED} is {@code false} for the
 *       traversal's session. Reading the flag per-session gives operators a runtime kill-switch
 *       without a redeploy; an unresolvable configuration declines rather than throwing.</li>
 *   <li><b>Non-graph / non-vertex start.</b> The first step is not a start-emitting {@link
 *       GraphStep}, or it is an edge start ({@code g.E()}). The current translator only models
 *       vertex-rooted patterns; edge starts are a later milestone. This O(1) start-shape gate
 *       runs before the O(steps) idempotency scan below, so a non-vertex traversal declines
 *       without walking its whole step list. Gating on the plain TinkerPop
 *       {@link GraphStep} (not {@code
 *       YTDBGraphStep}) is deliberate: this strategy runs <em>before</em> {@code
 *       YTDBGraphStepStrategy} — the sole producer of {@code YTDBGraphStep} — so at translator
 *       time the start step is still a plain {@code GraphStep}. Keying on {@code YTDBGraphStep}
 *       would decline every recognized shape. The check is also ordering-robust, since a
 *       {@code YTDBGraphStep} <em>is</em> a {@code GraphStep}.</li>
 *   <li><b>Idempotency.</b> The traversal already contains a {@link YTDBMatchPlanStep}
 *       anywhere in its step list. A traversal's strategy chain can be applied more than once
 *       (clone-for-reuse, test-harness re-application, lazy first-iteration apply); leaving an
 *       already-translated traversal alone keeps rewriting deterministic and avoids discarding
 *       a built plan.</li>
 *   <li><b>No translation.</b> {@link GremlinToMatchTranslator#translate} returns {@code null}
 *       — no whole-traversal translation is available because the walker declined a step.
 *       Replacing zero steps would be a no-op, so the strategy returns.</li>
 * </ol>
 *
 * <h2>Throw-safety net</h2>
 *
 * {@code apply} runs inside {@code traversal.applyStrategies()}, which fires on <em>every</em>
 * Gremlin traversal compilation, and the strategy is registered globally for all YTDB graph
 * traversals. An uncaught exception in {@code apply} would abort compilation for that
 * traversal, and the blast radius of a translator / walker bug would be every Gremlin query
 * the server runs, not only the recognized shapes. The whole body therefore runs inside a
 * {@code try} that catches {@link RuntimeException} and turns any ordinary failure — a walker bug, a
 * recognizer NPE, a malformed {@code MatchPlanInputs}, a planner exception — into a clean
 * decline: the method returns and the original step list is preserved for the native pipeline
 * (which, under the all-or-nothing rule, is at least as well-served as before). The net
 * degrades a translator bug to native execution rather than a broken query, and it exists from
 * the skeleton so the invariant holds before any recognizer runs under the strategy.
 *
 * <p>The net makes one deliberate exception. A {@link ReservedAliasException} — thrown by the
 * walker's reserved-prefix pre-flight when a user {@code as(...)} label sits in the reserved {@code
 * $} namespace — rejects prohibited input rather than reporting a translator failure, so the {@code
 * catch} re-throws it (caught before the {@link RuntimeException} clause) and the query fails with a
 * clear error. Native execution would accept the {@code $} label, so degrading this to a decline
 * would let a prohibited alias run silently; propagating it is the point.
 *
 * <p>The catch is narrowed to {@link RuntimeException}, so {@link Error} — including {@link
 * AssertionError} — is never swallowed: it is not a {@code RuntimeException} and propagates
 * untouched. Under {@code -ea} (the test/CI default) a genuine invariant violation in the walk
 * or plan build must surface loudly instead of degrading to a silent decline that masks a real
 * bug, and a fatal {@code OutOfMemoryError} / {@code StackOverflowError} must not be handed to
 * the native pipeline to re-attempt in an already-exhausted JVM. The body throws only unchecked
 * exceptions — its calls go through the {@code TraversalTranslator} / {@code MatchPlanBuilder}
 * seams and TinkerPop APIs — so {@code RuntimeException} covers every failure that can actually
 * occur.
 *
 * <p>The net catches during the walk and the plan build; the actual step-list mutation happens
 * only after both succeed (see {@link #applyTranslation}), so a caught exception always leaves
 * the step list untouched.
 *
 * <h2>Strategy ordering</h2>
 *
 * The strategy declares <b>empty</b> {@link #applyPrior()} and {@link #applyPost()}. Ordering
 * relative to the three half-measure strategies is established the canonical TinkerPop way:
 * each half-measure strategy lists {@code GremlinToMatchStrategy} in its <em>own</em> {@code
 * applyPrior()}, so TinkerPop's topological sort runs this strategy first and the
 * half-measures become the decline fallback. Those half-measure {@code applyPrior()} edits and
 * the registration into the optimization chain are in place — see {@code YTDBGraphImplAbstract}
 * and each half-measure strategy's {@code applyPrior()}; this strategy only declares its own
 * (empty) ordering constraints.
 *
 * <h2>Plan caching</h2>
 *
 * Cache-eligible walks build through {@link GremlinPlanCache}, keyed by {@link
 * GremlinPlanFingerprint} on the post-walk {@link MatchPlanInputs}. RID-bearing shapes ({@code
 * g.V(ids)}, {@code hasId(...)}) bypass the cache. Per-walk predicate values bind as positional
 * parameters and are installed on the boundary step at execution time.
 *
 * <h2>Testability</h2>
 *
 * The translator and plan builder are injected as the {@link TraversalTranslator} and {@link
 * MatchPlanBuilder} seams through package-private constructors, so unit tests can drive the
 * post-gate splice path with a fixture {@link GremlinToMatchTranslator.TranslationResult} (and
 * can supply a throwing fixture to exercise the throw-safety net) without wiring a real walker.
 * Production code uses {@link #instance()}, which wires the production facade.
 */
public final class GremlinToMatchStrategy
    extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
    implements TraversalStrategy.ProviderOptimizationStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(GremlinToMatchStrategy.class);

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

  private final TraversalTranslator translator;

  private final MatchPlanBuilder planBuilder;

  /**
   * Package-private — tests construct a strategy with a fixture translator (and the production
   * plan builder). Production code goes through {@link #instance()}.
   */
  GremlinToMatchStrategy(TraversalTranslator translator) {
    this(translator, GremlinToMatchStrategy::buildPlan);
  }

  /**
   * Package-private — tests inject both a fixture translator and a fixture plan builder so the
   * splice path ({@link #applyTranslation}) can be exercised with a stub plan, without
   * standing up the real {@link MatchExecutionPlanner}. Production code goes through {@link
   * #instance()}, which wires the production translator and the production plan builder.
   */
  GremlinToMatchStrategy(TraversalTranslator translator, MatchPlanBuilder planBuilder) {
    this.translator = translator;
    this.planBuilder = planBuilder;
  }

  /** Singleton accessor — the strategy is stateless and cheap to share. */
  public static GremlinToMatchStrategy instance() {
    return INSTANCE;
  }

  @Override
  public void apply(Traversal.Admin<?, ?> traversal) {
    // Throw-safety net: the whole body runs on every Gremlin compilation and the strategy is
    // registered globally, so any recognizer/planner RuntimeException here must degrade to a
    // decline (leave the native step list untouched), never abort compilation. The catch is
    // narrowed to RuntimeException so Error and AssertionError are NOT caught — they propagate,
    // surfacing JVM errors and -ea invariant violations instead of masking them (see the class
    // Javadoc "Throw-safety net"). The mutation in applyTranslation runs only after the walk and
    // the plan build both succeed, so a caught exception always leaves the step list unmodified.
    try {
      applyOrDecline(traversal);
    } catch (ReservedAliasException e) {
      // The one deliberate hard rejection: a user as(...) label in the reserved '$' namespace is
      // prohibited input, not a best-effort-translation failure. Propagate it so the query fails with
      // a clear error rather than silently degrading to native (which accepts the '$' label). It must
      // be caught before the RuntimeException clause below, which would otherwise turn it into a
      // decline — ReservedAliasException is a RuntimeException subtype.
      throw e;
    } catch (RuntimeException e) {
      // Swallow every other unchecked exception deliberately: translation is a best-effort
      // optimization. A recognizer/planner failure declines to the native pipeline, which handles the
      // traversal correctly. Rethrowing would break every Gremlin query, recognized or not. Error and
      // AssertionError are not RuntimeExceptions, so they are intentionally not caught here — a
      // JVM Error or an -ea invariant violation must surface loudly, never degrade to a silent
      // decline.
      declineOnThrow(traversal, e);
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
    // Run the O(1) start-step gate before the O(steps) boundary scan, so a traversal that does not
    // start at a vertex GraphStep declines without walking the whole step list. Idempotency still
    // holds: an already-translated traversal's start step is a YTDBMatchPlanStep (not a GraphStep),
    // so hasVertexGraphStart declines it here anyway; the boundary scan below stays as the guard for
    // the defensive case where an ordinary step is prepended in front of the boundary.
    if (!hasVertexGraphStart(traversal)) {
      return;
    }
    if (containsBoundaryStep(traversal)) {
      return;
    }
    var translation = translator.translate(traversal);
    if (translation == null) {
      return;
    }
    applyTranslation(traversal, session, translation);
  }

  /**
   * Hook for the throw-safety net so a caught exception has a single, greppable landing point.
   * Only {@link RuntimeException}s reach here — {@link Error} (including {@link AssertionError})
   * and checked exceptions are not caught by {@link #apply} and propagate. The decline itself is
   * the absence of a mutation (the traversal is already unmodified because the step-list swap
   * runs last); this hook additionally records the swallowed exception at {@code DEBUG} so an
   * operator diagnosing "why is nothing being translated?" has a signal. {@code DEBUG} rather
   * than {@code WARN} keeps a deterministic translator bug — which would otherwise log on every
   * matching traversal — from flooding the log, while staying discoverable when the level is
   * raised.
   */
  private static void declineOnThrow(Traversal.Admin<?, ?> traversal, RuntimeException cause) {
    // Log the step-class SHAPE, not traversal.toString(): the latter renders inline literal
    // predicate values (e.g. has("ssn", "...")), so logging the shape keeps sensitive query values
    // — and any newline / control characters that could forge log lines — out of the log while
    // still identifying which traversal shape declined.
    LogManager.instance()
        .debug(
            GremlinToMatchStrategy.class,
            "Gremlin-to-MATCH translation declined after an unexpected exception;"
                + " falling back to native execution for traversal shape: %s",
            LOGGER,
            cause,
            stepShape(traversal));
  }

  /**
   * Renders the traversal as its ordered list of step class simple names, e.g. {@code [GraphStep,
   * HasStep]}. A diagnostic shape that omits the inline literal values {@code Traversal.toString()}
   * would include; see {@link #declineOnThrow}.
   */
  private static String stepShape(Traversal.Admin<?, ?> traversal) {
    var shape = new StringBuilder("[");
    var steps = traversal.getSteps();
    for (int i = 0; i < steps.size(); i++) {
      if (i > 0) {
        shape.append(", ");
      }
      shape.append(steps.get(i).getClass().getSimpleName());
    }
    return shape.append("]").toString();
  }

  /**
   * Returns the traversal's session iff a YouTrackDB graph is attached and the kill-switch is
   * on, or {@code null} to signal "decline".
   *
   * <p>Session resolution is delegated to {@link YTDBStrategyUtil#resolveYtdbSession}, which
   * returns {@code null} (never throws) on a detached, {@code EmptyGraph}, or non-YTDB traversal.
   * Reading the flag from the session's {@code ContextConfiguration} (rather than the JVM-global
   * {@link GlobalConfiguration}) lets operators — and tests — flip it per-session without mutating
   * global state. That {@code getConfiguration()} is {@code @Nullable}; a null result is treated
   * as "decline" so the kill-switch read never dereferences a null configuration.
   */
  @Nullable private static DatabaseSessionEmbedded resolveSessionIfEnabled(
      Traversal.Admin<?, ?> traversal) {
    var session = YTDBStrategyUtil.resolveYtdbSession(traversal);
    if (session == null) {
      return null;
    }
    // getConfiguration() is @Nullable (it delegates to storage.getContextConfiguration()). A
    // null ContextConfiguration cannot be dereferenced for the flag, so treat it as "not
    // enabled" and decline explicitly rather than relying on the throw-safety net to catch an
    // NPE — declining is the safe default when the kill-switch cannot be resolved.
    var configuration = session.getConfiguration();
    if (configuration == null) {
      return null;
    }
    var enabled =
        configuration.getValueAsBoolean(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
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
    InternalExecutionPlan plan = planBuilder.buildPlan(session, translation);
    replaceAllStepsWithBoundary(traversal, plan, translation);
  }

  /**
   * Production plan builder: routes the translated {@link MatchPlanInputs} through the additive
   * {@link MatchExecutionPlanner#MatchExecutionPlanner(MatchPlanInputs) constructor} and builds
   * the plan eagerly. Cache-eligible shapes get/put through {@link GremlinPlanCache}; RID-bearing
   * shapes always build uncached.
   */
  private static InternalExecutionPlan buildPlan(
      DatabaseSessionEmbedded session, GremlinToMatchTranslator.TranslationResult translation) {
    if (!translation.cacheEligible()) {
      return buildPlanUncached(session, translation.inputs());
    }
    var fingerprint = GremlinPlanFingerprint.fingerprint(translation.inputs());
    var ctx = new BasicCommandContext(session);
    var cached = GremlinPlanCache.get(fingerprint, ctx, session);
    if (cached != null) {
      return cached;
    }
    var plan = buildPlanUncached(session, translation.inputs());
    GremlinPlanCache.put(fingerprint, plan, session);
    return plan.copy(ctx);
  }

  private static InternalExecutionPlan buildPlanUncached(
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
    var boundary =
        new YTDBMatchPlanStep(
            traversalRaw,
            translation.returnClass(),
            plan,
            translation.boundaryAlias(),
            translation.outputType(),
            translation.inputParameters());
    TraversalHelper.removeAllSteps(traversalRaw);
    traversalRaw.addStep(boundary);
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return NO_ORDERING;
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPost() {
    return NO_ORDERING;
  }

  /**
   * Injection seam for the whole-traversal translation step. Production wires {@link
   * GremlinToMatchTranslator#translate}; tests pass a fixture (or a throwing fixture for the
   * throw-safety net) without a real walker. Named rather than a bare {@code Function} so the
   * call site reads {@code translator.translate(traversal)}, and returns {@code null} to decline
   * rather than an empty {@link java.util.Optional} — the seam is package-private, so a nullable
   * return is simpler than wrapping at the single call site.
   */
  @FunctionalInterface
  interface TraversalTranslator {
    @Nullable GremlinToMatchTranslator.TranslationResult translate(Traversal.Admin<?, ?> traversal);
  }

  /**
   * Injection seam for building the MATCH execution plan from translated inputs. Production wires
   * {@link #buildPlan}; tests pass a stub so the splice path runs without a real {@link
   * MatchExecutionPlanner}. Named rather than a bare {@code BiFunction} so the call site reads
   * {@code planBuilder.buildPlan(session, inputs)}.
   */
  @FunctionalInterface
  interface MatchPlanBuilder {
    InternalExecutionPlan buildPlan(
        DatabaseSessionEmbedded session, GremlinToMatchTranslator.TranslationResult translation);
  }
}
