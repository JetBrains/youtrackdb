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
   * Commits a pure-filter child: AND-composes captured alias filters into {@code ctx}, applies any
   * boundary-node re-types the child captured in its pattern buffer (a folded {@code hasLabel(L)}
   * re-types through {@code addNode} without flipping {@link SubTraversalPredicateAdapter#hasEdges()}),
   * and forwards any detached anti-join the child captured from a {@code not(hop)}.
   *
   * <p>Forwarding the anti-join is sound on this path and only on this path. Both callers are
   * conjunctive — an AND arm and a positive {@code where} / {@code filter} child both have to hold
   * for the row to pass — and the plan-level {@code notMatchExpressions} sink applies its
   * expressions conjunctively over the whole match, so the two agree. The OR path must not forward
   * (see {@link #collectOrExpressions}), and neither may an enclosing {@code not(...)}.
   *
   * <p>Only {@code boundary} may be re-typed. A hop target always arrives with an {@code addEdge}
   * that flips {@code hasEdges} and sends the child down the decline, so any other alias reaching
   * here means a recogniser registered a node the parent's pattern has no edge to — a disconnected
   * pattern rather than a filter. The assert states that as an invariant rather than committing the
   * node silently; {@link #singleCapturedFilter} declines the same case on the OR path.
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
  }

  /**
   * Whether any accepted child contributed a hop, which makes the whole positive filter
   * inexpressible and forces a decline to the native pipeline.
   *
   * <h2>Why an edge-bearing positive filter cannot be translated</h2>
   *
   * <p>A native {@code where(t)} / {@code and(t1, t2)} is an <b>existence test</b>: the incoming
   * element passes through once when every child yields at least one result, and the child's own
   * results are discarded. Committing the child's hop into the positive pattern instead makes the
   * translation a <b>join</b>, so the plan emits one row per matching path. On the modern graph
   * {@code g.V(marko).where(__.out())} returns marko once natively and once per out-edge
   * translated; {@code g.V().and(__.out("a"), __.out("b"))} multiplies the two fan-outs. The
   * element set is right and the multiset is wrong, which is the silent-wrong-answer shape, so the
   * shape has to leave the translator until the semi-join is modelled.
   *
   * <p>Neither repair available today is sound. {@code RETURN DISTINCT} would collapse the
   * over-emitted rows, but it applies to the whole projection and therefore also collapses the path
   * multiplicity a prefix hop legitimately produces — {@code g.V().out()} yields a target once per
   * in-path, and native Gremlin keeps those duplicates. Expressing the fix inside the child is not
   * possible either: a captured sub-walk cannot contribute result shaping at all, because {@link
   * SubTraversalPredicateAdapter} swallows {@code setReturnDistinct} (and the slice setters) so that
   * only alias filters and pattern writes survive into the parent.
   *
   * <p>{@code not(t)} is the exception: it keeps translating an edge-bearing child, because an
   * anti-join emits its input at most once and so never over-emits. The four connective surfaces
   * therefore agree on <em>conjunctive composition</em>, not on keeping hops native — a
   * {@code not(hop)} still translates wherever the surrounding context is a conjunction, and
   * declines wherever it is not ({@link #collectOrExpressions} on the OR path, {@link
   * NotStepRecogniser} for a nested {@code not}).
   */
  static boolean anyEdgeBearing(List<SubTraversalPredicateAdapter> adapters) {
    for (var adapter : adapters) {
      if (adapter.hasEdges()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Commits a single accepted child sub-walk from a positive filter ({@code where(traversal)} /
   * {@code filter(traversal)} / {@link
   * org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep}): pure-filter
   * children merge into the boundary {@code WHERE}, edge-bearing children decline the whole filter
   * (see {@link #anyEdgeBearing} for why). Nothing is written to {@code ctx} on the decline path, so
   * a declining filter leaves the outer context exactly as it found it.
   */
  static Outcome commitPositiveFilterChild(
      RecognitionContext ctx, SubTraversalPredicateAdapter adapter) {
    if (adapter.outcome() != Outcome.ACCEPTED) {
      return Outcome.DECLINE;
    }
    if (adapter.hasEdges()) {
      return Outcome.DECLINE;
    }
    commitPureFilterChild(ctx, adapter, ctx.boundaryAlias());
    return Outcome.ACCEPTED;
  }

  /**
   * Collects one composable {@link SQLBooleanExpression} per accepted pure-filter child from the
   * child's captured boundary filters. Returns {@code null} when any child is edge-bearing, when a
   * child captured a detached anti-join, when a child contributed no filter, or when
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
      if (adapter.hasEdges() || !adapter.capturedNotExpressions().isEmpty()) {
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
