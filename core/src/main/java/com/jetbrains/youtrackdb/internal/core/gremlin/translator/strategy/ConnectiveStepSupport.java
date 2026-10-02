package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

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
   * Commits a pure-filter child: AND-composes captured alias filters into {@code ctx}, applies any
   * boundary-node re-types the child captured in its pattern buffer (a folded {@code hasLabel(L)}
   * re-types through {@code addNode} without flipping {@link SubTraversalPredicateAdapter#hasEdges()}),
   * and forwards detached checks captured from nested conjunctive filters.
   *
   * <p>Forwarding detached checks is sound on this path and only on this path. Both callers are
   * conjunctive — an AND arm and a positive {@code where} / {@code filter} child both have to hold
   * for the row to pass — and the plan-level NOT and exists sinks apply their
   * expressions conjunctively over the whole match, so the two agree. The OR path must not forward
   * (see {@link #collectOrExpressions}), and neither may an enclosing {@code not(...)}.
   *
   * <p>Only {@code boundary} may be re-typed on this pure-filter path. Hop targets travel in
   * detached exists checks, not in the positive pattern.
   */
  static void commitPureFilterChild(
      RecognitionContext ctx, SubTraversalPredicateAdapter adapter, String boundary) {
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
        || !adapter.capturedNotExpressions().isEmpty()
        || !adapter.capturedExistsExpressions().isEmpty()) {
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

  /**
   * Collects one composable {@link SQLBooleanExpression} per accepted pure-filter child from the
   * child's captured boundary filters. Returns {@code null} when any child is edge-bearing, when a
   * child captured a detached check, when a child contributed no filter, or when
   * {@code boundary} is {@code null}.
   *
   * <p>The anti-join check is what keeps a {@code not(hop)} arm out of a disjunction. An OR arm has
   * to reduce to one boolean operand on {@code boundary}, and a detached {@code SQLMatchExpression}
   * is not one: the only place it can go is the plan-level sink, which the planner applies
   * conjunctively over the whole match. Composing the arm's other operands into the OR and letting
   * the anti-join travel to that sink reads
   * {@code or(not(out(a)).has(name, x), has(age, 30))} as {@code (no out-a) AND (name = x OR age =
   * 30)} and drops every row that passed only the second arm.
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
      if (adapter.hasEdges() || !adapter.capturedNotExpressions().isEmpty()
          || !adapter.capturedExistsExpressions().isEmpty()) {
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
