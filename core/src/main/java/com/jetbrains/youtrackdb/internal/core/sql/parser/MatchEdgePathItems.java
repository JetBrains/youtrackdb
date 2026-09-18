package com.jetbrains.youtrackdb.internal.core.sql.parser;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Front-end factory for the two {@link SQLMatchPathItem}s that make up the edge-as-node MATCH form
 * {@code outE(L){as: $edge, where: <filter>}.inV(){as: $target}}. Front-ends (the Gremlin-to-MATCH
 * translator) build this shape directly, without a SQL text round-trip.
 *
 * <p>The class lives in the parser package on purpose: {@link SQLMethodCall#methodName} is {@code
 * protected} with no public setter, so a method call naming an edge-traversal method ({@code
 * outE}/{@code inE}/{@code bothE}/{@code inV}/{@code outV}/{@code bothV}) can only be assembled from
 * inside this package. Everything else it touches ({@link SQLMatchPathItem#setMethod}, {@link
 * SQLMatchPathItem#setFilter}, {@link SQLMatchFilter#fromAliasAndClass}) is already public. It is a
 * new hand-written helper, not a JJTree-generated node, so grammar regeneration does not touch it.
 *
 * <p>This mirrors the existing front-end seams for MATCH IR construction: {@link
 * SQLMatchPathItem#outPath}/{@code inPath}/{@code bothPath} for vertex hops, and {@link
 * SQLMatchFilter#fromAliasAndClass} for filter blocks — both already used by the shared {@code
 * MatchPatternBuilder}. The vocabulary here is the edge-returning ({@code outE}/{@code inE}/{@code
 * bothE}) and vertex-returning ({@code inV}/{@code outV}/{@code bothV}) methods the MATCH executor
 * recognises (see {@code SQLMethodCall.graphMethods}); {@code otherV} is deliberately absent because
 * the executor has no such method.
 */
public final class MatchEdgePathItems {

  private MatchEdgePathItems() {
    // Static factory — no instances.
  }

  /**
   * Builds the edge path item {@code <methodName>(L){as: edgeAlias, where: edgeFilter}} for a single
   * edge label. Delegates to {@link #edgeMethodItem(String, String[], String, SQLWhereClause)}.
   */
  public static SQLMatchPathItem edgeMethodItem(
      @Nonnull String methodName, @Nullable String edgeLabel, @Nonnull String edgeAlias,
      @Nullable SQLWhereClause edgeFilter) {
    String[] labels =
        edgeLabel != null && !edgeLabel.isBlank() ? new String[] {edgeLabel} : null;
    return edgeMethodItem(methodName, labels, edgeAlias, edgeFilter);
  }

  /**
   * Builds the edge path item {@code <methodName>('L1', 'L2', …){as: edgeAlias, where: edgeFilter}} —
   * an edge-returning method call carrying zero or more edge labels as parameters, and a filter block
   * that names the edge alias and (optionally) carries the edge {@code WHERE}. The edge alias makes
   * the edge a distinct pattern node so a {@code has(...)} predicate filters the edge itself rather
   * than the target vertex. A null/empty labels array emits the method with no parameter (all edge
   * types).
   *
   * @param methodName the edge-returning method: {@code outE}, {@code inE}, or {@code bothE}
   * @param edgeLabels edge labels passed as method parameters; null/empty → no parameter
   * @param edgeAlias  non-null alias the edge binds to in the pattern
   * @param edgeFilter the edge {@code WHERE}, or null when the edge is unfiltered
   */
  public static SQLMatchPathItem edgeMethodItem(
      @Nonnull String methodName,
      @Nullable String[] edgeLabels,
      @Nonnull String edgeAlias,
      @Nullable SQLWhereClause edgeFilter) {
    var method = new SQLMethodCall(-1);
    method.methodName = new SQLIdentifier(methodName);
    if (edgeLabels != null) {
      // User-supplied edge labels enter the AST as literal parameter nodes; MatchPlanInputs is
      // consumed in-memory (no text round-trip), so labels cannot inject query syntax.
      for (var edgeLabel : edgeLabels) {
        if (edgeLabel == null || edgeLabel.isBlank()) {
          continue;
        }
        var param = new SQLExpression(-1);
        param.setMathExpression(new SQLBaseExpression(edgeLabel));
        method.addParam(param);
      }
    }
    var item = new SQLMatchPathItem(-1);
    item.setMethod(method);
    var filter = SQLMatchFilter.fromAliasAndClass(edgeAlias, null);
    if (edgeFilter != null) {
      filter.setFilter(edgeFilter);
    }
    item.setFilter(filter);
    return item;
  }

  /**
   * Builds the closing vertex path item {@code <methodName>(){as: targetAlias}} — a vertex-returning
   * method call with no parameter, and a filter block that names the target alias. The target is the
   * vertex on the far side of the edge, and becomes the traversal's result.
   *
   * @param methodName the vertex-returning method: {@code inV}, {@code outV}, or {@code bothV}
   * @param targetAlias non-null alias the target vertex binds to in the pattern
   */
  public static SQLMatchPathItem vertexMethodItem(
      @Nonnull String methodName, @Nonnull String targetAlias) {
    var method = new SQLMethodCall(-1);
    method.methodName = new SQLIdentifier(methodName);
    var item = new SQLMatchPathItem(-1);
    item.setMethod(method);
    item.setFilter(SQLMatchFilter.fromAliasAndClass(targetAlias, null));
    return item;
  }
}
