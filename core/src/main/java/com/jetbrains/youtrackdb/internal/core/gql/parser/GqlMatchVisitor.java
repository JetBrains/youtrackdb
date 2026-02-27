package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLBaseVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/// Visitor that extracts node patterns from the MATCH clause.
///
/// For `MATCH (a:Person)`:
/// - Collects NodePattern(alias="a", label="Person", properties={})
///
/// For `MATCH (a:Person {name: 'Karl'})`:
/// - Collects NodePattern(alias="a", label="Person", properties={name: "Karl"})
///
/// Returns raw parsed values - default handling is applied by the planner.
@SuppressWarnings({"unused", "ConstantConditions"})
public class GqlMatchVisitor extends GQLBaseVisitor<Void> {

  /// Represents a node pattern like `(a:Person {name: 'Karl'})` in the MATCH clause.
  /// Contains raw parsed values (may be null if not specified in query).
  ///
  /// @param alias      variable name, or null for anonymous patterns
  /// @param label      class/label name, or null for untyped patterns
  /// @param properties inline property filters (`{key: value}` pairs), never null
  public record NodePattern(
      @Nullable String alias,
      @Nullable String label,
      Map<String, Object> properties) {

    /// Convenience constructor for patterns without inline properties.
    public NodePattern(@Nullable String alias, @Nullable String label) {
      this(alias, label, Map.of());
    }
  }

  private final List<NodePattern> nodePatterns = new ArrayList<>();

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

    nodePatterns.add(new NodePattern(alias, label, properties));
    return null;
  }

  /// Returns all node patterns from MATCH clause.
  public List<NodePattern> getNodePatterns() {
    return nodePatterns;
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
