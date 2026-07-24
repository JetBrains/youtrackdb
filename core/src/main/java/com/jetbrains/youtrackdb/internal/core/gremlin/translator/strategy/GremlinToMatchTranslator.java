package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * Facade that turns a fully-recognized TinkerPop traversal into the MATCH plan inputs the
 * {@link GremlinToMatchStrategy} needs to build a boundary step, or declines the whole
 * traversal.
 *
 * <p>Under the all-or-nothing rule, translation is a whole-traversal
 * decision: either <em>every</em> step is in the recognized set and the facade returns a
 * {@link TranslationResult} describing the single boundary step that replaces the entire
 * step list, or at least one step is unrecognized and the facade returns {@code null} so the
 * native TinkerPop pipeline keeps handling the traversal verbatim. There is no partial / prefix
 * translation — the boundary step is always the only step in a translated traversal.
 *
 * <p>The facade is package-private to the {@code translator.strategy} package: it has no
 * purpose outside the strategy, and exposing it would let callers bypass the strategy's
 * gating (kill-switch, idempotency, start-step shape).
 *
 * <p>Translation is delegated to the shared production {@link GremlinStepWalker}, which walks
 * the step list and dispatches each step to the registered {@link StepRecogniser}s. Phase 1
 * recognises only the vertex source ({@code g.V()} / {@code g.V(ids)}); any unrecognised step
 * declines the whole traversal (the all-or-nothing contract) so the native TinkerPop pipeline
 * keeps handling it verbatim. Later tracks widen the recognised set by adding recognisers to
 * the walker's registry — this facade does not change.
 */
final class GremlinToMatchTranslator {

  private GremlinToMatchTranslator() {
    // Static facade — no instances.
  }

  /**
   * Attempts to translate {@code traversal} whole into MATCH plan inputs by delegating to the
   * shared production {@link GremlinStepWalker}.
   *
   * <p>Returns {@code null} when the traversal contains any unrecognized step (the all-or-nothing
   * decline). The strategy treats a null result as "no translation" and leaves the traversal
   * untouched, preserving the native execution path.
   *
   * @param traversal the traversal to inspect; never {@code null}
   * @return the whole-traversal translation, or {@code null} to decline
   */
  @Nullable static TranslationResult translate(Traversal.Admin<?, ?> traversal) {
    return GremlinStepWalker.production().walk(traversal);
  }

  /**
   * Everything the strategy needs to replace a fully-recognized traversal's step list with a
   * single {@code YTDBMatchPlanStep}.
   *
   * @param inputs the assembled MATCH plan inputs handed to {@code MatchExecutionPlanner}
   * @param boundaryAlias the alias under which the matched element appears in each result
   *     row (the boundary step projects rows onto traversers by this alias)
   * @param outputType how each row is projected onto a traverser payload
   * @param returnClass the TinkerPop element class the boundary step emits, mirroring the
   *     {@link Element} subclass the original terminal step produced (e.g. {@code
   *     Vertex.class} for a {@code g.V()} traversal)
   * @param inputParameters positional-parameter values keyed by slot ({@code 0}, {@code 1}, …) for
   *     this walk; installed on the boundary step before each execution
   * @param cacheEligible {@code false} when the walk is RID-bearing and must bypass the plan cache
   */
  record TranslationResult(
      @Nonnull MatchPlanInputs inputs,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType,
      @Nonnull Class<? extends Element> returnClass,
      @Nonnull Map<Object, Object> inputParameters,
      boolean cacheEligible) {
  }
}
