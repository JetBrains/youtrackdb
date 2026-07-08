package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <h2>Index-driven cursor for multi-step claims</h2>
 *
 * The walk is driven by a cursor ({@link WalkerContext#stepIndex}), not a for-each: each
 * iteration reads {@code steps.get(stepIndex)}, dispatches it, and lets the claiming recogniser
 * advance the cursor past every step it consumed. A recogniser may consume several steps in one
 * claim (the non-adjacent {@code outE(L).has(...).inV()} chain), so the recognised set is bounded
 * by which step <em>classes</em> have a recogniser, not by a step count. The walker therefore has
 * no upper-bound size gate: a long but fully-recognised chain translates, and the first
 * unrecognised step class declines the whole traversal. An empty traversal is still declined up
 * front (nothing to translate, no boundary to pin).
 *
 * <h2>Reserved-prefix pre-flight scan</h2>
 *
 * Before dispatching any step, the walker scans every step's labels and declines the whole
 * traversal if any user label starts with {@code $}. The {@code $} space is reserved for the
 * translator's minted {@code $g2m_} aliases (see {@link WalkerContext#ANON_VERTEX_ALIAS_PREFIX}),
 * so a user label in that space could collide with a minted one. Declining (not throwing)
 * preserves the all-or-nothing fallback: such a traversal runs unchanged on the native pipeline.
 * The scan is purely lexical, so it runs before the session-dependent polymorphism resolution.
 *
 * <h2>Per-step shape gates in recognisers; the graph-level flag in the walker</h2>
 *
 * Every per-step shape gate (start-step shape, vertex-vs-edge, ID convertibility, hasContainer
 * presence, predicate well-formedness, …) lives inside the responsible recogniser, so the walker
 * stays agnostic to the recognised-step set as it grows track by track. The one value the walker
 * resolves up front is the traversal's graph-level polymorphism flag ({@code
 * YTDBStrategyUtil.isPolymorphic}), stored on the {@link WalkerContext} as a plain boolean so the
 * recognisers read one resolved setting without each re-resolving it. Resolving it in the walker
 * is safe: {@code isPolymorphic} is null-safe (it gates on an attached YTDB graph and transaction
 * before touching {@code tx()}, so a detached {@code EmptyGraph} or non-YTDB graph yields {@code
 * null} rather than throwing), and a {@code null} result declines the whole walk.
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
   * Prefix the reserved-prefix pre-flight scan forbids in user labels. The translator mints all
   * of its internal aliases under {@code $} ({@link WalkerContext#ANON_VERTEX_ALIAS_PREFIX} /
   * {@link WalkerContext#EDGE_ALIAS_PREFIX}), so a user label in this space could collide with a
   * minted one; a traversal carrying such a label declines to the native pipeline. Kept as one
   * greppable constant tied to the reserved namespace.
   */
  private static final String RESERVED_ALIAS_PREFIX = "$";

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
    // Empty-traversal gate, before any per-step work. A step-less traversal has nothing to
    // translate and could never pin a boundary, so decline it here rather than let it fall through
    // to the invariant assert below — an empty traversal is a normal shape, not a recogniser bug.
    // There is no upper-bound size gate: a recogniser may consume several steps in one claim, so
    // the recognised set is bounded by which step classes have a recogniser, not by a step count,
    // and a long but fully-recognised chain must translate. Any unrecognised step class declines
    // the whole traversal in the dispatch loop below.
    var steps = traversal.getSteps();
    if (steps.isEmpty()) {
      return null;
    }

    // Reserved-prefix pre-flight: decline the whole traversal if any user label starts with '$',
    // which is the translator's reserved namespace for minted $g2m_ aliases. Purely lexical (no
    // graph access), so it runs before the session-dependent polymorphism resolution below.
    // Declining (not throwing) keeps such a traversal on the native pipeline unchanged.
    if (hasReservedPrefixLabel(steps)) {
      return null;
    }

    // Resolve the graph-level polymorphism flag once, up front, so recognisers work with a plain
    // boolean and none of them re-resolves it. isPolymorphic is null-safe:
    // YTDBStrategyUtil.resolveYtdbSession gates on an attached YTDBGraph + YTDBTransaction before
    // touching tx(), so a detached EmptyGraph or non-YTDB graph yields null rather than throwing. A
    // null result means the traversal has no resolvable polymorphism setting and cannot be
    // translated faithfully — decline the whole walk before building the context. Owning the
    // resolution here keeps every recogniser free of the flag's initialisation.
    Boolean resolved = YTDBStrategyUtil.isPolymorphic(traversal);
    if (resolved == null) {
      return null;
    }
    boolean polymorphic = resolved;

    var ctx = new WalkerContext(traversal, polymorphic);

    // Index-driven dispatch. Each iteration reads the step at the cursor and dispatches it to the
    // recogniser registered for its concrete runtime class; the claiming recogniser advances the
    // cursor past every step it consumed (one for a single-step claim, N for a multi-step claim),
    // so the walker no longer advances it. Class-keyed dispatch: no entry — an unregistered type
    // or an unexpected subclass — declines the whole traversal (all-or-nothing), as does a
    // registered recogniser that rejects the step as malformed.
    while (ctx.stepIndex < steps.size()) {
      int indexBefore = ctx.stepIndex;
      Step<?, ?> step = steps.get(indexBefore);
      var recogniser = recognisers.get(step.getClass());
      if (recogniser == null || !recogniser.recognize(step, ctx)) {
        return null;
      }
      // The claiming recogniser must advance the cursor past every step it consumed: forward by at
      // least one, and never past the end of the list. A recogniser that returns true without
      // advancing would spin this loop forever; one that overruns the list would skip a step the
      // walk never validated. Both are recogniser-logic bugs, so the assert surfaces them loudly
      // under -ea (an AssertionError, which GremlinToMatchStrategy's RuntimeException-only
      // throw-safety net does not swallow). Under -da the defensive decline keeps such a bug from
      // hanging or mis-translating a live query — the traversal falls back to the native pipeline.
      assert ctx.stepIndex > indexBefore && ctx.stepIndex <= steps.size()
          : "recogniser for "
              + step.getClass().getSimpleName()
              + " returned true but advanced the cursor from "
              + indexBefore
              + " to "
              + ctx.stepIndex
              + " (must consume at least one step without overrunning "
              + steps.size()
              + ")";
      if (ctx.stepIndex <= indexBefore || ctx.stepIndex > steps.size()) {
        return null;
      }
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
   * Returns {@code true} if any step carries a user label starting with the reserved {@code $}
   * prefix ({@link #RESERVED_ALIAS_PREFIX}). Scans every step's {@code getLabels()} once; the
   * scan is purely lexical (no graph access), so the walker runs it before resolving any
   * session-dependent state. A match declines the whole traversal rather than throwing, so a
   * pre-existing {@code as("$foo")} query keeps its native behaviour.
   */
  private static boolean hasReservedPrefixLabel(List<?> steps) {
    for (Object raw : steps) {
      // getSteps() is a raw List<Step>; each element is a Step whose labels are user-supplied.
      var step = (Step<?, ?>) raw;
      for (String label : step.getLabels()) {
        // A step's label set can contain a null: as((String) null) reaches AbstractStep.addLabel,
        // which adds the label with no null guard. Skip nulls so this purely lexical scan declines
        // (never throws) — a null label cannot collide with the reserved '$' namespace anyway.
        if (label != null && label.startsWith(RESERVED_ALIAS_PREFIX)) {
          return true;
        }
      }
    }
    return false;
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
