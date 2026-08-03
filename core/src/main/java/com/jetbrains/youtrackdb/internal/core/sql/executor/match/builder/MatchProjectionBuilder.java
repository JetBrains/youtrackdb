package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import java.util.Locale;

/**
 * Fluent, IR-first builders for MATCH {@code RETURN} projection items consumed by {@link
 * MatchExecutionPlanner}. Gremlin aggregate terminators and GQL projection wiring should prefer
 * these factories over parsing SQL text fragments.
 */
public final class MatchProjectionBuilder {

  /** Context variable for the candidate row in a group bucket ({@code list($currentMatch)}). */
  public static final String CURRENT_MATCH_VARIABLE = "$currentMatch";

  private MatchProjectionBuilder() {
    // Static helper — no instances.
  }

  /** {@code count(*)}. */
  public static SQLExpression countStar() {
    return ProjectionExpressionFactories.countStar();
  }

  /** {@code list($currentMatch)}. */
  public static SQLExpression listCurrentMatch() {
    return ProjectionExpressionFactories.listCurrentMatch();
  }

  /** {@code list(alias)} — collects the grouped element under {@code alias}. */
  public static SQLExpression listAlias(String alias) {
    return ProjectionExpressionFactories.listAlias(alias);
  }

  /**
   * {@code list(expr)} — collects a caller-built expression rather than a whole element, for
   * {@code values(key).group()}, which buckets the projected values.
   */
  public static SQLExpression listExpression(SQLExpression expression) {
    return ProjectionExpressionFactories.listExpression(expression);
  }

  /**
   * {@code fn(property)} for a bare property name emitted by {@code values("age").mean()} and
   * similar shapes.
   */
  public static SQLExpression propertyAggregate(String functionName, String propertyField) {
    return ProjectionExpressionFactories.propertyAggregate(
        functionName.toLowerCase(Locale.ROOT), propertyField);
  }

  /** {@code fn(fieldExpr)} when the field is already a resolved {@link SQLExpression}. */
  public static SQLExpression propertyAggregate(String functionName, SQLExpression field) {
    return ProjectionExpressionFactories.propertyAggregate(
        functionName.toLowerCase(Locale.ROOT), field);
  }
}
