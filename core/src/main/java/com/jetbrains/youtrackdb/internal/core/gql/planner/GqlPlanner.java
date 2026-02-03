package com.jetbrains.youtrackdb.internal.core.gql.planner;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlFetchFromClassStep;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLLexer;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import com.jetbrains.youtrackdb.internal.core.gql.step.GqlMatchStep;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/// Planner that converts GQL query string into execution plan.
///
/// Flow:
/// 1. Parse GQL query string using ANTLR
/// 2. Visit parse tree to extract query components
/// 3. Build GqlExecutionPlan with appropriate steps
/// 4. Wrap in GqlMatchStep for TinkerPop integration
///
/// For now, only supports simple MATCH (a:Label) queries.
public class GqlPlanner {

  /// Parse and plan a GQL query.
  ///
  /// @param query     The GQL query string
  /// @param traversal The traversal to add steps to
  /// @return The created GqlMatchStep wrapping the execution plan
  public static GqlMatchStep plan(String query, Traversal.Admin<?, ?> traversal) {
    // 1. Parse
    var lexer = new GQLLexer(CharStreams.fromString(query));
    var tokens = new CommonTokenStream(lexer);
    var parser = new GQLParser(tokens);

    // 2. Visit to extract query components
    var visitor = new GqlMatchVisitor();
    visitor.visit(parser.graph_query());

    var alias = visitor.getAlias();
    var label = visitor.getLabel();

    // 3. Build execution plan
    var executionPlan = createExecutionPlan(alias, label);

    // 4. Wrap step
    return new GqlMatchStep(traversal, executionPlan, alias, label);
  }

  /// Create an execution plan for a simple MATCH query.
  ///
  /// @param alias The variable name (e.g., "a")
  /// @param label The class/label name (e.g., "Person")
  /// @return The execution plan
  public static GqlExecutionPlan createExecutionPlan(String alias, String label) {
    var plan = new GqlExecutionPlan();

    var fetchStep = new GqlFetchFromClassStep(alias, label, true);
    plan.chain(fetchStep);

    return plan;
  }
}
