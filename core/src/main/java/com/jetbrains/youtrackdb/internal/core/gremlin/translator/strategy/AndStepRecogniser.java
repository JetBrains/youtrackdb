package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;

/**
 * Recogniser for {@link AndStep}, the {@code ConnectiveStrategy} form of logical AND over child
 * sub-traversals. Each child is driven through the sub-walker ({@link RecognitionContext#walkChild});
 * the whole step declines when any child declines.
 *
 * <ul>
 *   <li><b>Pure-filter children</b> ({@link SubTraversalPredicateAdapter#hasEdges()} {@code false})
 *       — captured alias filters AND-composed into the parent boundary via {@link
 *       RecognitionContext#putAliasFilter}; a boundary re-type from a folded {@code hasLabel(L)}
 *       captured in the child's pattern buffer is committed through {@link RecognitionContext#addNode}.
 *   <li><b>Edge-bearing children</b> contribute detached exists checks, one per child. A filter
 *       check does not multiply rows in the positive pattern.
 * </ul>
 */
final class AndStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final AndStepRecogniser INSTANCE = new AndStepRecogniser();

  private AndStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof AndStep<?> andStep)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    var adapters = ConnectiveStepSupport.walkAcceptedChildren(andStep, ctx);
    if (adapters == null) {
      return Outcome.DECLINE;
    }
    // Validate every detached check before committing any child, so a failed arm never leaves
    // another arm's filters on the parent. Child walks have already forwarded parameter binding
    // and alias minting, which cannot change the result of a declined outer walk.
    var exists = new ArrayList<SQLMatchExpression>();
    for (var adapter : adapters) {
      if (adapter.hasEdges()) {
        var expression = ConnectiveStepSupport.detachedExists(ctx, adapter);
        if (expression == null) {
          return Outcome.DECLINE;
        }
        exists.add(expression);
      }
    }
    // Simulate the full commit order before publishing any class or filter contribution.
    var proposedClasses = new LinkedHashMap<String, String>();
    for (var adapter : adapters) {
      for (var entry : adapter.capturedPattern().registeredAliasClasses().entrySet()) {
        var alias = entry.getKey();
        var current = proposedClasses.containsKey(alias)
            ? proposedClasses.get(alias) : ctx.classForAlias(alias);
        if (!ctx.canNarrowClass(current, entry.getValue())) {
          return Outcome.DECLINE;
        }
        proposedClasses.put(alias, entry.getValue());
      }
    }
    for (var adapter : adapters) {
      if (!adapter.hasEdges()) {
        ConnectiveStepSupport.commitPureFilterChild(ctx, adapter, ctx.boundaryAlias());
      }
    }
    for (var expression : exists) {
      ctx.addExistsMatchExpression(expression);
    }
    return Outcome.ACCEPTED;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    return step instanceof AndStep<?>;
  }
}
