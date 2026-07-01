package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import java.util.Optional;
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
 * step list, or at least one step is unrecognized and the facade returns
 * {@link Optional#empty()} so the native TinkerPop pipeline keeps handling the traversal
 * verbatim. There is no partial / prefix translation — the boundary step is always the
 * only step in a translated traversal.
 *
 * <p>The facade is package-private to the {@code translator.strategy} package: it has no
 * purpose outside the strategy, and exposing it would let callers bypass the strategy's
 * gating (kill-switch, idempotency, start-step shape).
 *
 * <p><b>Current state — declines every shape.</b> This class is a skeleton: {@link
 * #translate} returns {@link Optional#empty()} for every input. The strategy is therefore
 * a structural no-op — it walks its gates and then declines, leaving the native pipeline in
 * charge. The real walker (the {@code GremlinStepWalker} + {@code StepRecogniser} registry)
 * lands in a follow-up step; wiring the decline-only facade now lets the strategy's gating
 * cascade and its throw-safety net be implemented and unit-tested in isolation, before any
 * recognizer runs under the strategy. Any later fully-recognizing implementation must honor
 * the same empty-on-no-translation contract so the strategy never has to guard against a
 * non-empty result that would not actually rewrite the step list.
 */
final class GremlinToMatchTranslator {

  private GremlinToMatchTranslator() {
    // Static facade — no instances.
  }

  /**
   * Attempts to translate {@code traversal} whole into MATCH plan inputs.
   *
   * <p>Returns {@link Optional#empty()} when the traversal contains any unrecognized step
   * (the all-or-nothing decline), and — in the current skeleton — for <em>every</em> input,
   * since no recognizer is wired yet. The strategy treats an empty result as "no
   * translation" and leaves the traversal untouched, preserving the native execution path.
   *
   * @param traversal the traversal to inspect; never {@code null}
   * @return the whole-traversal translation, or {@link Optional#empty()} to decline; always
   *     empty in the current skeleton
   */
  static Optional<TranslationResult> translate(Traversal.Admin<?, ?> traversal) {
    // Skeleton: see class Javadoc. The real whole-traversal walk lands in the next step of
    // this track. Declining here keeps the strategy a structural no-op end to end.
    return Optional.empty();
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
   */
  record TranslationResult(
      MatchPlanInputs inputs,
      String boundaryAlias,
      BoundaryOutputType outputType,
      Class<? extends Element> returnClass) {

    /** Compact constructor enforces the non-null invariants on every field. */
    public TranslationResult {
      if (inputs == null) {
        throw new NullPointerException("inputs must not be null");
      }
      if (boundaryAlias == null) {
        throw new NullPointerException("boundaryAlias must not be null");
      }
      if (outputType == null) {
        throw new NullPointerException("outputType must not be null");
      }
      if (returnClass == null) {
        throw new NullPointerException("returnClass must not be null");
      }
    }
  }
}
