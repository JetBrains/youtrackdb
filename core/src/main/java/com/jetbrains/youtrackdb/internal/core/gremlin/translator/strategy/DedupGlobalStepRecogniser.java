package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;

/**
 * Recogniser for {@link DedupGlobalStep}: {@code dedup()} and named {@code dedup(labels…)} that
 * only name the <em>current</em> boundary alias set {@code RETURN DISTINCT} and leave the existing
 * RETURN / output type alone.
 *
 * <p>Named labels that resolve to a prior hop (or any alias other than the boundary), and any
 * {@code by(...)} modulator, decline: MATCH {@code DISTINCT} applies to the whole RETURN row and
 * cannot express Gremlin's "unique by path label / modulator, emit current traverser" contract.
 * Rewriting RETURN to the dedup keys while keeping {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#ELEMENT} was
 * wrong — {@code projectElement} looks up the boundary alias and emitted null payloads.
 */
final class DedupGlobalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final DedupGlobalStepRecogniser INSTANCE = new DedupGlobalStepRecogniser();

  private DedupGlobalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof DedupGlobalStep<?> dedup)) {
      return Outcome.DECLINE;
    }
    // by(...) needs DISTINCT-ON-style keys while still emitting the current element — not MATCH.
    if (!dedup.getLocalChildren().isEmpty()) {
      return Outcome.DECLINE;
    }

    var scopeKeys = dedup.getScopeKeys();
    if (scopeKeys == null || scopeKeys.isEmpty()) {
      ctx.setReturnDistinct(true);
      return Outcome.ACCEPTED;
    }

    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    for (String userLabel : scopeKeys) {
      var internalAlias = ctx.resolveUserLabel(userLabel);
      if (internalAlias == null) {
        return Outcome.DECLINE;
      }
      // Prior-hop labels would change uniqueness without changing the emitted object — decline
      // rather than rewrite RETURN (which broke ELEMENT projection) or emit the wrong element.
      if (!boundary.equals(internalAlias)) {
        return Outcome.DECLINE;
      }
    }
    ctx.setReturnDistinct(true);
    return Outcome.ACCEPTED;
  }
}
