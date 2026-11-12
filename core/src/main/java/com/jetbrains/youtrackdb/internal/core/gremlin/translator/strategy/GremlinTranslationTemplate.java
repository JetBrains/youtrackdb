package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision;
import java.util.List;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * Cached outcome of a Gremlin-to-MATCH walk, keyed by {@link GremlinStepWalker#extractShape}. A hit
 * skips the walker: {@link Decline} returns from {@code apply} immediately, and {@link Translate}
 * uses the stored plain-plan decision to keep native execution or splice the stored template with
 * this invocation's harvested bindings. The decision has exactly the plan entry's validity.
 *
 * <p>The plan template is the closed copy {@link GremlinPlanCache} already stores. The boundary
 * step must not execute or close it; it copies on first {@code openArming()}.
 */
sealed interface GremlinTranslationTemplate {

  /** Complete private preparation. A null translation is a deterministic native refusal. */
  record Candidate(int extent, PrivatePrefixDiscovery.Reason reason, Translate translation)
      implements GremlinTranslationTemplate {
  }

  /** The walker declined this shape; {@code apply} returns without mutating the traversal. */
  record Decline() implements GremlinTranslationTemplate {
  }

  /**
   * A recognised single-plan translation. The shaping contains no deferred property operands.
   * The slot count and each contribution's gate, fold mode and ordered roles must agree before
   * rebinding the template with this invocation's MATCH and native-filter operands.
   */
  record Translate(
      @Nonnull InternalExecutionPlan planTemplate,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType,
      @Nonnull Class<? extends Element> returnClass,
      @Nonnull ResultShaping shaping,
      int bindingCount,
      @Nonnull List<HasBindingContext.Contribution> hasContributions,
      @Nonnull PlainPlanDecision decision)
      implements GremlinTranslationTemplate {
  }
}
