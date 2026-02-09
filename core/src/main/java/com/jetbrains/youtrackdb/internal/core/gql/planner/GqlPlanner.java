package com.jetbrains.youtrackdb.internal.core.gql.planner;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchStatement;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlQueryVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlStatement;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLLexer;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

/// Entry point for GQL query parsing.
///
/// Parses GQL query string and returns appropriate GqlStatement.
/// Each statement type has its own visitor and planner.
///
/// Flow:
/// 1. GqlPlanner.parse(query) → GqlStatement
/// 2. GqlStatement.createExecutionPlan(ctx) → GqlExecutionPlan
/// 3. GqlExecutionPlan.start(ctx) → GqlExecutionStream
///
/// Similar to SQL's SQLEngine.parse() returning SQLStatement.
public class GqlPlanner {

  public static GqlStatement getStatement(String query, DatabaseSessionInternal session) {
    if (session == null) {
      return parse(query);
    }

    var cache = session.getSharedContext().getGqlStatementCache();
    return cache.getCached(query);
  }

  /// Parse a GQL query string into a statement.
  ///
  /// @param query The GQL query string
  /// @return The parsed statement
  /// @throws IllegalArgumentException if query type is not supported
  public static GqlStatement parse(String query) {
    // 1. Parse with ANTLR
    var lexer = new GQLLexer(CharStreams.fromString(query));
    var tokens = new CommonTokenStream(lexer);
    var parser = new GQLParser(tokens);

    // 2. Use router visitor to identify query type
    var routerVisitor = new GqlQueryVisitor();
    routerVisitor.visit(parser.graph_query());

    // 3. Dispatch to specialized visitor based on query type
    if (routerVisitor.hasMatch()) {
      var matchVisitor = new GqlMatchVisitor();
      matchVisitor.visit(routerVisitor.getMatchContext());
      var statement = new GqlMatchStatement(matchVisitor.getNodePatterns());
      statement.setOriginalStatement(query);
      return statement;
    }

    // Future: add more statement types
    // if (routerVisitor.hasReturn()) {
    //   var returnVisitor = new GqlReturnVisitor();
    //   returnVisitor.visit(routerVisitor.getReturnContext());
    //   return new GqlReturnStatement(...);
    // }

    throw new IllegalArgumentException(
        "Unsupported GQL query type. Currently only MATCH is supported.");
  }
}
