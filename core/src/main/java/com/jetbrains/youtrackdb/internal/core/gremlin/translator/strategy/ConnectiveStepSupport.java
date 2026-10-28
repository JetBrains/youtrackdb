package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchBooleanCheck;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.ConnectiveStep;

/**
 * Shared child sub-walk and commit helpers for {@link AndStepRecogniser} and {@link
 * OrStepRecogniser}. Each connective drives every child through {@link RecognitionContext#walkChild}
 * and then commits captured state per its connective semantics (AND distributes over pattern
 * fragments and WHERE conjuncts. OR scopes detached checks within each complete arm).
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
   * Commits a pure-filter child: AND-composes captured alias filters into {@code ctx}, applies any
   * boundary-node re-types the child captured in its pattern buffer (a folded {@code hasLabel(L)}
   * re-types through {@code addNode} without flipping {@link SubTraversalPredicateAdapter#hasEdges()}),
   * and forwards detached checks captured from nested conjunctive filters.
   *
   * <p>Forwarding detached checks is sound on this path and only on this path. Both callers are
   * conjunctive — an AND arm and a positive {@code where} / {@code filter} child both have to hold
   * for the row to pass — and the plan-level NOT and exists sinks apply their
   * expressions conjunctively over the whole match, so the two agree. OR instead scopes each
   * complete arm in a tree. An enclosing {@code not(...)} must not forward these conjuncts.
   *
   * <p>Only {@code boundary} may be re-typed on this pure-filter path. Hop targets travel in
   * detached exists checks, not in the positive pattern.
   */
  static void commitPureFilterChild(
      RecognitionContext ctx, SubTraversalPredicateAdapter adapter, String boundary) {
    if (ctx instanceof SubTraversalPredicateAdapter capture) {
      capture.inheritCapturedClassChange(adapter);
      capture.inheritPolymorphicLabels(adapter);
    }
    for (var entry : adapter.capturedAliasFilters().entrySet()) {
      ctx.putAliasFilter(entry.getKey(), entry.getValue());
    }
    for (var entry : adapter.capturedPattern().registeredAliasClasses().entrySet()) {
      assert boundary == null || boundary.equals(entry.getKey())
          : "pure-filter child re-typed non-boundary alias " + entry.getKey();
      ctx.addNode(entry.getKey(), entry.getValue());
    }
    for (var notExpression : adapter.capturedNotExpressions()) {
      ctx.addNotMatchExpression(notExpression);
    }
    for (var existsExpression : adapter.capturedExistsExpressions()) {
      ctx.addExistsMatchExpression(existsExpression);
    }
    if (adapter.booleanCheck() != null) {
      ctx.addBooleanCheck(adapter.booleanCheck());
    }
  }

  /**
   * Builds one detached exists expression for a linear hop child. The origin is already in the
   * positive pattern and has no child-local filter or class. Nested detached checks cannot be
   * pushed through the child's hop chain. Every captured edge must belong to the chain, or a
   * branching or disconnected fragment would silently lose a predicate.
   */
  static SQLMatchExpression detachedExists(
      RecognitionContext ctx, SubTraversalPredicateAdapter adapter) {
    var boundary = ctx.boundaryAlias();
    if (boundary == null || !ctx.positivePatternHasAlias(boundary)
        || adapter.capturedAliasFilters().containsKey(boundary)
        || adapter.capturedPattern().registeredAliasClasses().containsKey(boundary)
        || adapter.hasCapturedChecks()) {
      return null;
    }
    try {
      var expression = adapter.capturedPattern().buildNotExpression(
          boundary, adapter.capturedAliasFilters(), WalkerContext.VERTEX_ROOT_CLASS);
      // A disconnected fragment or a cycle could otherwise be silently omitted by the linear
      // builder. Each captured pattern edge must appear exactly once in the detached chain.
      return expression.getItems().size() == adapter.capturedPattern().edgeCount()
          ? expression : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Commits a single accepted child sub-walk from a positive filter ({@code where(traversal)} /
   * {@code filter(traversal)} / {@link
   * org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep}): pure-filter
   * children merge into the boundary {@code WHERE}, while linear hop children append a detached
   * exists check. An invalid child declines without changing the outer context.
   */
  static Outcome commitPositiveFilterChild(
      RecognitionContext ctx, SubTraversalPredicateAdapter adapter) {
    if (adapter.outcome() != Outcome.ACCEPTED) {
      return Outcome.DECLINE;
    }
    if (adapter.hasEdges()) {
      var expression = detachedExists(ctx, adapter);
      if (expression == null) {
        return Outcome.DECLINE;
      }
      ctx.addExistsMatchExpression(expression);
      return Outcome.ACCEPTED;
    }
    commitPureFilterChild(ctx, adapter, ctx.boundaryAlias());
    return Outcome.ACCEPTED;
  }

  /** Captures a whole OR arm. No leaf becomes a mandatory positive-pattern predicate. */
  static MatchBooleanCheck booleanArm(
      RecognitionContext ctx, SubTraversalPredicateAdapter adapter, String boundary) {
    // A tree belongs to the bound boundary, not to a target reached by a detached chain.
    if (adapter.hasEdges() && adapter.booleanCheck() != null) {
      return null;
    }
    var children = new ArrayList<MatchBooleanCheck>();
    if (adapter.hasEdges()) {
      if (boundary == null || !ctx.positivePatternHasAlias(boundary)
          || adapter.capturedPattern().registeredAliasClasses().containsKey(boundary)) {
        return null;
      }
      try {
        // The chain builder consumes target filters only. Keep the origin filter as a sibling
        // conjunct in this arm, not a mandatory predicate on the enclosing MATCH.
        var expression = adapter.capturedPattern().buildNotExpression(
            boundary, adapter.capturedAliasFilters(), WalkerContext.VERTEX_ROOT_CLASS);
        if (expression.getItems().size() != adapter.capturedPattern().edgeCount()) {
          return null;
        }
        children.add(new MatchBooleanCheck.Probe(expression));
      } catch (IllegalArgumentException e) {
        return null;
      }
      var filter = adapter.capturedAliasFilters().get(boundary);
      if (filter != null) {
        children.add(new MatchBooleanCheck.Filter(boundary, filter));
      }
    } else if (!adapter.capturedAliasFilters().isEmpty()
        || !adapter.capturedPattern().registeredAliasClasses().isEmpty()) {
      var filter = singleCapturedFilter(adapter, boundary);
      if (filter == null || adapter.capturedAliasFilters().keySet().stream()
          .anyMatch(alias -> !boundary.equals(alias))) {
        return null;
      }
      children.add(new MatchBooleanCheck.Filter(boundary, WHERE.wrap(filter)));
    }
    for (var expression : adapter.capturedNotExpressions()) {
      children.add(new MatchBooleanCheck.Not(new MatchBooleanCheck.Probe(expression)));
    }
    for (var expression : adapter.capturedExistsExpressions()) {
      children.add(new MatchBooleanCheck.Probe(expression));
    }
    if (adapter.booleanCheck() != null) {
      children.add(adapter.booleanCheck());
    }
    if (children.isEmpty()) {
      return null;
    }
    return children.size() == 1 ? children.getFirst() : new MatchBooleanCheck.And(children);
  }

  /**
   * Reads the one WHERE expression a pure-filter child captured on {@code boundary}. Exact-mode
   * labels and several-label calls already contribute their named tests to that WHERE. Polymorphic
   * single-label calls re-type the scan without a WHERE test, so fold their captured names into
   * subclass-expanded {@link MatchWhereBuilder#classIn} operands. A several-label scan's internal
   * least common ancestor is never a label test. A missing boundary filter or a contribution on
   * another alias makes the child inexpressible as one OR/NOT operand and declines.
   */
  static SQLBooleanExpression singleCapturedFilter(
      SubTraversalPredicateAdapter adapter, String boundary) {
    if (adapter.changedCapturedClass()) {
      return null;
    }
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
    }
    for (var entry : adapter.capturedPolymorphicLabels().entrySet()) {
      if (!boundary.equals(entry.getKey())) {
        return null;
      }
      for (var label : entry.getValue()) {
        var classNames = adapter.expandPolymorphicClassClosure(List.of(label));
        expr = WHERE.and(WHERE.classIn(classNames), expr);
      }
    }
    return expr;
  }
}
