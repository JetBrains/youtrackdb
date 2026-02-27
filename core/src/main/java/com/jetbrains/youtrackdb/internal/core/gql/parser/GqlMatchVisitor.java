package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLBaseVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

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
    Map<String, Object> properties = Map.of();

    if (patternFiller != null) {
      var variableCtx = patternFiller.graph_pattern_variable();
      if (variableCtx != null) {
        alias = variableCtx.getText();
      }

      var labelCondition = patternFiller.is_label_condition();
      if (labelCondition != null && labelCondition.label_expression() != null) {
        label = labelCondition.label_expression().getText();
      }

      var propFilters = patternFiller.property_filters();
      if (propFilters != null && propFilters.property_list() != null) {
        properties = extractProperties(propFilters.property_list());
      }
    }

    // Build SQLMatchFilter using YQL IR factory method
    matchFilters.add(SQLMatchFilter.fromGqlNode(alias, label, properties));
    return null;
  }

  /// Returns all match filters (unified YQL IR) from MATCH clause.
  /// These SQLMatchFilter instances are converted to Pattern + PatternNode in GqlMatchStatement.buildPlan().
  public List<SQLMatchFilter> getMatchFilters() {
    return matchFilters;
  }

  private static Map<String, Object> extractProperties(
      GQLParser.Property_listContext listCtx) {
    var result = new LinkedHashMap<String, Object>();
    for (var assignment : listCtx.property_assignment()) {
      var key = assignment.ID().getText();
      var value = extractLiteralValue(assignment.value_expression());
      result.put(key, value);
    }
    return result;
  }

  /// Extracts a Java literal value from a GQL value_expression parse context.
  /// Supports: string literals, integers, floating-point numbers, and booleans.
  static Object extractLiteralValue(GQLParser.Value_expressionContext valueCtx) {
    if (valueCtx.STRING() != null) {
      var text = valueCtx.STRING().getText();
      return text.substring(1, text.length() - 1);
    }

    var text = valueCtx.getText();

    if ("true".equalsIgnoreCase(text)) {
      return true;
    }
    if ("false".equalsIgnoreCase(text)) {
      return false;
    }

    if (valueCtx.math_expression() != null) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
      }
      try {
        return Double.parseDouble(text);
      } catch (NumberFormatException ignored) {
      }
    }

    throw new IllegalArgumentException(
        "Unsupported inline property value: " + text
            + ". Only string, numeric, and boolean literals are supported.");
  }
}
