package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLBaseVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import java.util.ArrayList;
import java.util.List;

/// Visitor that extracts node patterns from MATCH clause.
///
/// For `MATCH (a:Person)`:
/// - Collects NodePattern(alias="a", label="Person")
///
/// For `MATCH (a:Person), (b:Work)`:
/// - Collects [NodePattern("a", "Person"), NodePattern("b", "Work")]
///
/// Returns raw parsed values - default values are applied by the planner.
public class GqlMatchVisitor extends GQLBaseVisitor<Void> {

  /// Represents a node pattern like (a:Person) in MATCH clause.
  /// Contains raw parsed values (may be null if not specified in query).
  public record NodePattern(String alias, String label) {
  }

  private final List<NodePattern> nodePatterns = new ArrayList<>();

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

    nodePatterns.add(new NodePattern(alias, label));
    return null;
  }

  /// Returns all node patterns from MATCH clause.
  public List<NodePattern> getNodePatterns() {
    return nodePatterns;
  }
}
