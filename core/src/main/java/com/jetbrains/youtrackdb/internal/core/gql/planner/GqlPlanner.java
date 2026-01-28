package com.jetbrains.youtrackdb.internal.core.gql.planner;

import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLLexer;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import com.jetbrains.youtrackdb.internal.core.gql.step.GqlMatchStep;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/// Planner that converts GQL query string into execution steps.
///
/// For now, only supports simple MATCH (a:Label) queries.
public class GqlPlanner {

  /// Parse and plan a GQL query.
  ///
  /// @param query     The GQL query string
  /// @param traversal The traversal to add steps to
  /// @return The created GqlMatchStep
  public GqlMatchStep plan(String query, Traversal.Admin<?, ?> traversal) {
    // 1. Parse
    var lexer = new GQLLexer(CharStreams.fromString(query));
    var tokens = new CommonTokenStream(lexer);
    var parser = new GQLParser(tokens);

    // 2. Visit
    var visitor = new GqlMatchVisitor();
    visitor.visit(parser.graph_query());

    // 3. Create step
    var alias = visitor.getAlias();
    var label = visitor.getLabel();

    return new GqlMatchStep(traversal, alias, label);
  }
}
