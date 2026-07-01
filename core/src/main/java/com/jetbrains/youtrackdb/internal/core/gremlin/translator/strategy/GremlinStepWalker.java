package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;

/**
 * Iterates a {@link Traversal.Admin}'s step list in order and dispatches each step to a
 * fixed-order list of {@link StepRecogniser} instances. The walker is the entry point
 * the {@link GremlinToMatchTranslator} delegates to; recognisers carry the per-step
 * recognition logic so the walker itself stays a thin orchestration loop.
 *
 * <h2>All-or-nothing translation</h2>
 *
 * The walker enforces all-or-nothing translation mechanically: any step the registered
 * recognisers cannot claim causes the entire walk to decline (returning {@link
 * Optional#empty()}). There is no "partial prefix" mechanism — either every step is
 * recognised or the traversal is declined whole and stays on the native TinkerPop
 * pipeline.
 *
 * <h2>Per-step gates only — no walker-level pre-check</h2>
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
   * Production recogniser registry, keyed on the concrete step class (D9). Phase 1 registers
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
   * Attempts to translate {@code traversal} by walking its steps in order. Returns a
   * non-empty {@link GremlinToMatchTranslator.TranslationResult} when every step was
   * recognised, otherwise {@link Optional#empty()}.
   */
  Optional<GremlinToMatchTranslator.TranslationResult> walk(
      Traversal.Admin<?, ?> traversal) {
    var ctx = new WalkerContext(traversal);

    for (Step<?, ?> step : traversal.getSteps()) {
      // Class-keyed dispatch (D9): the recogniser registered for this step's concrete runtime
      // class owns it. No entry — an unregistered type or an unexpected subclass — declines the
      // whole traversal (all-or-nothing), as does a registered recogniser that rejects the step
      // as malformed.
      var recogniser = recognisers.get(step.getClass());
      if (recogniser == null || !recogniser.recognize(step, ctx)) {
        return Optional.empty();
      }
      ctx.stepIndex++;
    }

    // A successful walk must pin the terminator metadata — boundary alias, output
    // type, and return class — via one of the recognisers. A zero-step traversal
    // (the for-loop never runs) or a registry of recognisers that all claim their
    // step without pinning the boundary fields would otherwise reach buildResult
    // with null fields, where TranslationResult's compact ctor throws NPE. Decline
    // cleanly instead so the strategy's outer try/catch is not the primary
    // surfacing path for this contract violation.
    if (ctx.boundaryAlias == null
        || ctx.outputType == null
        || ctx.returnClass == null) {
      return Optional.empty();
    }

    return Optional.of(buildResult(ctx));
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

    var inputs =
        new MatchPlanInputs(
            ir.pattern(),
            ir.aliasClasses(),
            finalAliasFilters,
            ctx.aliasRids,
            List.of(),
            List.of(),
            ctx.returnItems,
            ctx.returnAliases,
            ctx.returnNestedProjections,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false);

    return new GremlinToMatchTranslator.TranslationResult(
        inputs,
        ctx.boundaryAlias,
        ctx.outputType,
        ctx.returnClass);
  }
}
