package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLBaseVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import javax.annotation.Nullable;

/// Visitor that identifies query type and delegates to specialized visitors.
///
/// Acts as a router - detects what type of query it is (MATCH, RETURN, etc.)
/// and collects the parse context for specialized visitors.
///
/// Usage:
/// ```
/// var visitor = new GqlQueryVisitor();
/// visitor.visit(parser.graph_query());
/// if (visitor.hasMatch()) {
///     var matchVisitor = new GqlMatchVisitor();
///     matchVisitor.visit(visitor.getMatchContext());
/// }
/// ```
@SuppressWarnings("unused")
public class GqlQueryVisitor extends GQLBaseVisitor<Void> {

  // Query type flags
  private boolean hasMatch = false;

  @Nullable
  private GQLParser.Match_statementContext matchContext = null;

  // Future: add more query types

  @Override
  public Void visitMatch_statement(GQLParser.Match_statementContext ctx) {
    hasMatch = true;
    matchContext = ctx;
    return null;
  }

  // Query type checks

  /// Returns true if query contains MATCH clause.
  public boolean hasMatch() {
    return hasMatch;
  }

  // Future: add methods for other query types
  // public boolean hasReturn() { return hasReturn; }

  // Context getters for specialized visitors

  /// Returns the MATCH statement context for GqlMatchVisitor.
  @Nullable
  public GQLParser.Match_statementContext getMatchContext() {
    return matchContext;
  }
}
