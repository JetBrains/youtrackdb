package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;

/**
 * Iterates a {@link Traversal.Admin}'s step list in order and dispatches each step to the
 * {@link StepRecogniser} registered for its concrete runtime class in a class-keyed
 * registry ({@code Map<Class<?>, StepRecogniser>}). The walker is the entry point the
 * {@link GremlinToMatchTranslator} delegates to; recognisers carry the per-step
 * recognition logic so the walker itself stays a thin orchestration loop.
 *
 * <h2>All-or-nothing translation</h2>
 *
 * The walker enforces all-or-nothing translation mechanically: any step the registered
 * recognisers cannot claim causes the entire walk to decline (returning {@code null}). There is
 * no "partial prefix" mechanism — either every step is recognised or the traversal is declined
 * whole and stays on the native TinkerPop pipeline.
 *
 * <h2>Recognition gates live in recognisers, not the walker</h2>
 *
 * Every gate (start-step shape, vertex-vs-edge, ID convertibility, hasContainer
 * presence, polymorphism resolution, predicate well-formedness, …) lives inside the
 * responsible recogniser, so the walker is agnostic to the recognised-step set as it
 * grows track by track. In particular, polymorphism resolution stays inside the
 * start-step recogniser because {@code YTDBStrategyUtil.isPolymorphic} calls
 * {@code graph.tx()} unconditionally — invoking it on graphs that do not support
 * transactions (anonymous traversals attached to {@code EmptyGraph}, non-YTDB graph
 * proxies) would throw before the recogniser's structural gates had a chance to
 * decline.
 *
 * <h2>Result assembly</h2>
 *
 * On a successful walk, the walker calls {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder#build}
 * exactly once to lock the pattern, merges the builder's alias filters with any filters
 * recognisers contributed outside the builder, and packages the {@link MatchPlanInputs}
 * into a {@link GremlinToMatchTranslator.TranslationResult}. The recognised step count
 * equals the traversal's step count by construction (all-or-nothing).
 */
final class GremlinStepWalker {

  /**
   * Production recogniser registry, keyed on the concrete step class. Phase 1 registers
   * one entry — {@link StartStepRecogniser} under {@link GraphStep}; later tracks add one entry
   * per step class they translate. Class-keyed dispatch is O(1) and fails safe: a step whose
   * runtime class has no entry — an unregistered type, or an unexpected subclass — finds no
   * recogniser and declines the whole traversal, rather than being misrouted through a parent
   * recogniser via an {@code instanceof} near-miss.
   */
  private static final Map<Class<?>, StepRecogniser> PRODUCTION_RECOGNISERS =
      Map.of(GraphStep.class, StartStepRecogniser.INSTANCE);

  /**
   * Pre-built production walker. The walker is stateless — only the immutable
   * {@code recognisers} field — so a single shared instance avoids one allocation per
   * Gremlin traversal that reaches the strategy. Mirrors the singleton pattern the
   * strategy itself uses.
   */
  private static final GremlinStepWalker PRODUCTION_INSTANCE =
      new GremlinStepWalker(PRODUCTION_RECOGNISERS);

  /**
   * Largest traversal the current recogniser registry can translate whole. Phase 1 recognises
   * only the single vertex-source step, so a traversal with any follow-up step is a shape this
   * registry cannot cover. The walker declines such traversals up front rather than walking them:
   * the all-or-nothing loop below would decline anyway (a follow-up step has no recogniser), but
   * an explicit size gate makes the recognised-set bound a single, greppable constant and guards
   * against a future recogniser regression that would otherwise let a multi-step traversal slip
   * through. Later tracks that widen the recognised set raise this bound.
   */
  private static final int MAX_RECOGNISED_STEPS = 1;

  private final Map<Class<?>, StepRecogniser> recognisers;

  /**
   * Package-private constructor accepting a curated recogniser registry keyed on step class.
   * Both the production singleton and unit tests use this constructor; production code reaches
   * it via {@link #production()}, tests pass fixture registries directly.
   */
  GremlinStepWalker(Map<Class<?>, StepRecogniser> recognisers) {
    this.recognisers = Map.copyOf(recognisers);
  }

  /** Returns the shared production walker wired with the production recogniser registry. */
  static GremlinStepWalker production() {
    return PRODUCTION_INSTANCE;
  }

  /**
   * Attempts to translate {@code traversal} by walking its steps in order. Returns the
   * {@link GremlinToMatchTranslator.TranslationResult} when every step was recognised, otherwise
   * {@code null}.
   */
  @Nullable GremlinToMatchTranslator.TranslationResult walk(Traversal.Admin<?, ?> traversal) {
    // Size gate, before any per-step work. An empty traversal has nothing to translate and could
    // never pin a boundary, so decline it here rather than let it fall through to the invariant
    // assert below — an empty traversal is a normal shape, not a recogniser bug. The upper bound
    // declines any traversal larger than the current recognised set can translate whole; see
    // MAX_RECOGNISED_STEPS, which holds Phase 1 to the bare vertex source and keeps a follow-up
    // step (out/has/match/…) on the native pipeline.
    var steps = traversal.getSteps();
    if (steps.isEmpty() || steps.size() > MAX_RECOGNISED_STEPS) {
      return null;
    }

    var ctx = new WalkerContext(traversal);

    for (Step<?, ?> step : steps) {
      // Class-keyed dispatch: the recogniser registered for this step's concrete runtime
      // class owns it. No entry — an unregistered type or an unexpected subclass — declines the
      // whole traversal (all-or-nothing), as does a registered recogniser that rejects the step
      // as malformed.
      var recogniser = recognisers.get(step.getClass());
      if (recogniser == null || !recogniser.recognize(step, ctx)) {
        return null;
      }
      ctx.stepIndex++;
    }

    // Invariant: a fully-recognised non-empty traversal has its terminator metadata pinned —
    // boundary alias, output type, and return class — by the recogniser that owns its terminator
    // (in Phase 1, the start-step recogniser). Empty traversals are gated out above, so reaching
    // here with a null field is not a normal decline: it means a recogniser returned true without
    // pinning the boundary, a recogniser-logic bug.
    //
    // The assert surfaces that bug loudly under -ea (the test/CI default): an AssertionError is an
    // Error, so GremlinToMatchStrategy's RuntimeException-only throw-safety net does not swallow it
    // and the bug fails the test instead of hiding as a silent decline. Under -da (production) the
    // assert is a no-op, so the decline below is the safety net: rather than build a null-bearing
    // TranslationResult the strategy would splice over the traversal as a broken boundary step, the
    // walk declines here and the traversal stays on the native pipeline unchanged.
    assert ctx.boundaryAlias != null && ctx.outputType != null && ctx.returnClass != null
        : "walk recognised all " + steps.size() + " step(s) but left the boundary unpinned";
    if (ctx.boundaryAlias == null || ctx.outputType == null || ctx.returnClass == null) {
      return null;
    }

    return buildResult(ctx);
  }

  /**
   * Snapshots the walker context into a {@link GremlinToMatchTranslator.TranslationResult}.
   * Locks the pattern (one-shot {@code build()}), merges builder-supplied alias filters
   * with recogniser-supplied ones (recogniser entries override on the same alias), and
   * packages the {@link MatchPlanInputs}.
   */
  private static GremlinToMatchTranslator.TranslationResult buildResult(WalkerContext ctx) {
    var ir = ctx.patternBuilder.build();

    Map<String, SQLWhereClause> finalAliasFilters = new LinkedHashMap<>(ir.aliasFilters());
    finalAliasFilters.putAll(ctx.aliasFilters);

    // Only the fields a single-node g.V() translation actually carries are set; the rest keep
    // their null/false defaults (matchExpressions/notMatchExpressions normalise to empty lists in
    // the compact constructor). The builder names each field so a future track adding one cannot
    // silently transpose a positional argument.
    var inputs =
        MatchPlanInputs.builder(ir.pattern())
            .aliasClasses(ir.aliasClasses())
            .aliasFilters(finalAliasFilters)
            .aliasRids(ctx.aliasRids)
            .returnItems(ctx.returnItems)
            .returnAliases(ctx.returnAliases)
            .returnNestedProjections(ctx.returnNestedProjections)
            .build();

    return new GremlinToMatchTranslator.TranslationResult(
        inputs,
        ctx.boundaryAlias,
        ctx.outputType,
        ctx.returnClass);
  }
}
