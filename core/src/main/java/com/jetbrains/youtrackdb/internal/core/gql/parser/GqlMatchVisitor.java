package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLBaseVisitor;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;

/// Visitor that extracts variable (alias) and label from a MATCH node pattern.
///
/// For `MATCH (a:Person)`:
/// - alias = "a"
/// - label = "Person"
public class GqlMatchVisitor extends GQLBaseVisitor<Void> {

  private String label;
  private String alias;

  @Override
  public Void visitMatch_statement(GQLParser.Match_statementContext ctx) {
    return visitChildren(ctx);
  }

  @Override
  public Void visitNode_pattern(GQLParser.Node_patternContext ctx) {
    var patternFiller = ctx.pattern_filler();
    if (patternFiller == null) {
      return null;
    }

    var variableCtx = patternFiller.graph_pattern_variable();
    if (variableCtx != null) {
      this.alias = variableCtx.getText();
    }

    var labelCondition = patternFiller.is_label_condition();
    if (labelCondition != null && labelCondition.label_expression() != null) {
      this.label = labelCondition.label_expression().getText();
    }

    return null;
  }

  public String getLabel() {
    return label;
  }

  public String getAlias() {
    return alias;
  }
}
