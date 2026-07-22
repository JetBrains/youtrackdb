package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.ConnectiveStep;

/**
 * Shared child sub-walk and commit helpers for {@link AndStepRecogniser} and {@link
 * OrStepRecogniser}. Each connective drives every child through {@link RecognitionContext#walkChild}
 * and then commits captured state per its connective semantics (AND distributes over pattern
 * fragments and WHERE conjuncts; OR composes pure-filter booleans only).
 */
final class ConnectiveStepSupport {

  /** Stateless builder for AND / OR composition and WHERE wrapping. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private ConnectiveStepSupport() {
    // Utility — no instances.
  }

  /**
   * Walks every child sub-traversal and returns the accepted captures in input order.
   * Returns {@code null} when the connective is empty or when any child declines.
   *
   * <p>No caller-visible state is committed here: each child still runs behind the
   * sub-walk capture boundary, so a declined child leaves the outer context untouched.
   * Callers keep connective-specific commit semantics separate from this shared walk phase.
   */
  static List<SubTraversalPredicateAdapter> walkAcceptedChildren(
      ConnectiveStep<?> connective, RecognitionContext ctx) {
    var children = connective.getLocalChildren();
    if (children.isEmpty()) {
      return null;
    }
    var adapters = new ArrayList<SubTraversalPredicateAdapter>(children.size());
    for (var child : children) {
      var adapter = ctx.walkChild(child);
      if (adapter.outcome() != Outcome.ACCEPTED) {
        return null;
      }
      adapters.add(adapter);
    }
    return adapters;
  }

  /**
   * Commits a pure-filter child: AND-composes captured alias filters into {@code ctx} and applies any
   * boundary-node re-types the child captured in its pattern buffer (a folded {@code hasLabel(L)}
   * re-types through {@code addNode} without flipping {@link SubTraversalPredicateAdapter#hasEdges()}).
   */
  static void commitPureFilterChild(RecognitionContext ctx, SubTraversalPredicateAdapter adapter) {
    for (var entry : adapter.capturedAliasFilters().entrySet()) {
      ctx.putAliasFilter(entry.getKey(), entry.getValue());
    }
    for (var entry : adapter.capturedPattern().registeredAliasClasses().entrySet()) {
      ctx.addNode(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Commits an edge-bearing child: appends the captured hop fragment to the positive pattern and
   * merges any alias filters the child captured (target-vertex {@code has(...)} after a hop, etc.).
   */
  static void commitEdgeBearingChild(RecognitionContext ctx, SubTraversalPredicateAdapter adapter) {
    ctx.appendPattern(adapter.capturedPattern());
    for (var entry : adapter.capturedAliasFilters().entrySet()) {
      ctx.putAliasFilter(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Commits a single accepted child sub-walk from a positive filter ({@code where(traversal)} /
   * {@link org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep}): pure-filter
   * children merge into the boundary {@code WHERE}; edge-bearing children append hop fragments.
   */
  static Outcome commitPositiveFilterChild(
      RecognitionContext ctx, SubTraversalPredicateAdapter adapter) {
    if (adapter.outcome() != Outcome.ACCEPTED) {
      return Outcome.DECLINE;
    }
    if (!adapter.hasEdges()) {
      commitPureFilterChild(ctx, adapter);
    } else {
      commitEdgeBearingChild(ctx, adapter);
    }
    return Outcome.ACCEPTED;
  }

  /**
   * Collects one composable {@link SQLBooleanExpression} per accepted pure-filter child from the
   * child's captured boundary filters. Returns {@code null} when any child is edge-bearing, when a
   * child contributed no filter, or when {@code boundary} is {@code null}.
   */
  static SQLBooleanExpression collectOrExpressions(
      ConnectiveStep<?> connective, RecognitionContext ctx, String boundary) {
    if (boundary == null) {
      return null;
    }
    var adapters = walkAcceptedChildren(connective, ctx);
    if (adapters == null) {
      return null;
    }
    var exprs = new ArrayList<SQLBooleanExpression>();
    for (var adapter : adapters) {
      if (adapter.hasEdges()) {
        return null;
      }
      var expr = singleCapturedFilter(adapter, boundary);
      if (expr == null) {
        return null;
      }
      exprs.add(expr);
    }
    return exprs.size() == 1 ? exprs.getFirst()
        : WHERE.or(exprs.toArray(new SQLBooleanExpression[0]));
  }

  /**
   * Reads the one WHERE expression a pure-filter child captured on {@code boundary}, folding any
   * boundary-node re-type ({@code hasLabel(L)} via {@code addNode}) into the operand as {@link
   * MatchWhereBuilder#classEquals}. Under polymorphic mode {@code hasLabel} is re-type-only (no
   * {@code classEquals} in the child's WHERE), so without this fold an OR of {@code hasLabel+has}
   * arms would keep only the property predicates and lose label discrimination. Multiple filter
   * entries, a missing filter, or a re-type on a non-boundary alias means the child is not a single
   * composable OR operand — decline.
   */
  static SQLBooleanExpression singleCapturedFilter(
      SubTraversalPredicateAdapter adapter, String boundary) {
    List<SQLWhereClause> onBoundary = new ArrayList<>();
    for (var entry : adapter.capturedAliasFilters().entrySet()) {
      if (boundary.equals(entry.getKey())) {
        onBoundary.add(entry.getValue());
      }
    }
    if (onBoundary.size() != 1) {
      return null;
    }
    var expr = onBoundary.getFirst().getBaseExpression();
    var reTypes = adapter.capturedPattern().registeredAliasClasses();
    for (var entry : reTypes.entrySet()) {
      if (!boundary.equals(entry.getKey())) {
        // A pure-filter OR child should only re-type the boundary; any other alias is inexpressible
        // as a boolean operand on this node.
        return null;
      }
      expr = WHERE.and(WHERE.classEquals(entry.getValue()), expr);
    }
    return expr;
  }
}
