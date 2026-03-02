package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLBaseVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.ArrayList;
import java.util.List;

/// Visitor that extracts node patterns from the MATCH clause using unified YQL IR.
///
/// For `MATCH (a:Person)`:
/// - Builds SQLMatchFilter with alias="a", className="Person" (YQL IR)
/// - Result: Map with binding {"a": vertex}
///
/// For `MATCH (:Person)`:
/// - Builds SQLMatchFilter with alias=null, className="Person" (YQL IR)
/// - Result: just the Vertex directly (no Map wrapper)
///
/// For `MATCH (a:Person), (b:Work)`:
/// - Builds [SQLMatchFilter("a", "Person"), SQLMatchFilter("b", "Work")]
///
/// Uses unified YQL IR (SQLMatchFilter) directly via factory method, which are then converted
/// to Pattern + PatternNode in GqlMatchStatement.buildPlan().
@SuppressWarnings({"unused", "ConstantConditions"})
public class GqlMatchVisitor extends GQLBaseVisitor<Void> {

  private final List<SQLMatchFilter> matchFilters = new ArrayList<>();

  @Override
  public Void visitNode_pattern(GQLParser.Node_patternContext ctx) {
    var patternFiller = ctx.pattern_filler();

    String alias = null;
    String label = null;

    if (patternFiller != null) {
      var variableCtx = patternFiller.graph_pattern_variable();
      if (variableCtx != null) {
        alias = variableCtx.getText();
      }

      var labelCondition = patternFiller.is_label_condition();
      if (labelCondition != null && labelCondition.label_expression() != null) {
        label = labelCondition.label_expression().getText();
      }
    }

    // Build SQLMatchFilter using YQL IR factory method
    matchFilters.add(SQLMatchFilter.fromGqlNode(alias, label));
    return null;
  }

  /// Returns all match filters (unified YQL IR) from MATCH clause.
  /// These SQLMatchFilter instances are converted to Pattern + PatternNode in GqlMatchStatement.buildPlan().
  public List<SQLMatchFilter> getMatchFilters() {
    return matchFilters;
  }
}
