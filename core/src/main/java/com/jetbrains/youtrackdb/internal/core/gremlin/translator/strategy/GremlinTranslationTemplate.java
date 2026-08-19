package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * Cached outcome of a Gremlin-to-MATCH walk, keyed by {@link GremlinStepWalker#extractShape}. A hit
 * skips the walker: {@link Decline} returns from {@code apply} immediately, and {@link Translate}
 * splices the stored plan template with this invocation's harvested bindings.
 *
 * <p>The plan template is the closed copy {@link GremlinPlanCache} already stores. The boundary
 * step must not execute or close it; it copies on first {@code openArming()}.
 */
sealed interface GremlinTranslationTemplate {

  /** Shared decline sentinel — declining shapes have no per-entry payload. */
  Decline DECLINE = new Decline();

  /** The walker declined this shape; {@code apply} returns without mutating the traversal. */
  record Decline() implements GremlinTranslationTemplate {
  }

  /**
   * A recognised single-plan translation. {@code bindingCount} is the number of positional slots
   * the walker allocated; a harvested map of a different size falls through to a full walk rather
   * than splicing the wrong plan.
   */
  record Translate(
      @Nonnull InternalExecutionPlan planTemplate,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType,
      @Nonnull Class<? extends Element> returnClass,
      @Nonnull ResultShaping shaping,
      int bindingCount)
      implements GremlinTranslationTemplate {
  }
}
