package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;

/**
 * Narrow seam {@link UnionStepRecogniser} uses to fork the recognised prefix into each union child
 * without seeing the parent {@code Traversal.Admin}. The walker owns the parent traversal and the
 * step cursor; this host exposes only the prefix snapshot, a fork-and-walk operation, and the
 * multi-plan stash used by {@code buildResult}.
 */
interface UnionForkHost {

  /**
   * Steps strictly before the just-consumed {@code UnionStep} (including any transparent barriers
   * that sit in that range). Empty when the union was the first step — a start-position union.
   */
  @Nonnull
  List<Step<?, ?>> recognisedPrefixSteps();

  /**
   * Builds a fresh traversal from the recognised prefix plus {@code childSuffix} (caller has already
   * stripped trailing {@code EndStep}s), attaches the parent walk's graph and strategies privately,
   * and runs a full production walk. Returns {@code null} when the fork declines.
   */
  @Nullable GremlinToMatchTranslator.TranslationResult walkFork(
      @Nonnull List<Step<?, ?>> childSuffix);

  /**
   * Stashes the ordered child plan inputs, positional-parameter maps, and per-child plan-cache
   * eligibility for {@code buildResult} to emit a multi-plan {@link
   * GremlinToMatchTranslator.TranslationResult}.
   */
  void stashAcceptedChildren(
      @Nonnull List<MatchPlanInputs> childInputs,
      @Nonnull List<Map<Object, Object>> childInputParameters,
      @Nonnull List<Boolean> childCacheEligible);
}
