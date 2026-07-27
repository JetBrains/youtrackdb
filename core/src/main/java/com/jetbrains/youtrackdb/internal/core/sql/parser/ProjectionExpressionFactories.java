package com.jetbrains.youtrackdb.internal.core.sql.parser;

/**
 * Builds {@link SQLExpression} projection nodes in the same AST shape as {@link YouTrackDBSql}
 * without going through a SQL text round-trip. Shared by {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder}
 * and other MATCH IR front-ends.
 */
public final class ProjectionExpressionFactories {

  private ProjectionExpressionFactories() {
    // Static helper — no instances.
  }

  /** {@code count(*)} — global aggregate over the match bucket. */
  public static SQLExpression countStar() {
    return functionCall("count", starArgument());
  }

  /** {@code list($currentMatch)} — default {@code group()} value column. */
  public static SQLExpression listCurrentMatch() {
    return functionCall("list", contextVariable("$currentMatch"));
  }

  /**
   * {@code fn(field)} where {@code field} is a bare property name ({@code values("age").mean()}).
   */
  public static SQLExpression propertyAggregate(String functionName, String propertyField) {
    if (functionName == null || functionName.isBlank()) {
      throw new IllegalArgumentException("blank aggregate function name");
    }
    if (propertyField == null || propertyField.isBlank()) {
      throw new IllegalArgumentException("blank property field");
    }
    return functionCall(functionName, bareIdentifier(propertyField));
  }

  /** {@code fn(expr)} with a caller-built field expression (e.g. {@code alias.age}). */
  public static SQLExpression propertyAggregate(String functionName, SQLExpression field) {
    if (functionName == null || functionName.isBlank()) {
      throw new IllegalArgumentException("blank aggregate function name");
    }
    if (field == null) {
      throw new IllegalArgumentException("null field expression");
    }
    return functionCall(functionName, field);
  }

  static SQLExpression functionCall(String functionName, SQLExpression... params) {
    var call = new SQLFunctionCall(-1);
    call.name = new SQLIdentifier(functionName);
    for (var param : params) {
      call.addParam(param);
    }
    var levelZero = new SQLLevelZeroIdentifier(-1);
    levelZero.functionCall = call;
    var baseIdentifier = new SQLBaseIdentifier(-1);
    baseIdentifier.levelZero = levelZero;
    var base = new SQLBaseExpression(-1);
    base.setIdentifier(baseIdentifier);
    var expr = new SQLExpression(-1);
    expr.setMathExpression(base);
    return expr;
  }

  static SQLExpression starArgument() {
    var suffix = new SQLSuffixIdentifier(-1);
    suffix.star = true;
    var baseIdentifier = new SQLBaseIdentifier(-1);
    baseIdentifier.suffix = suffix;
    var base = new SQLBaseExpression(-1);
    base.setIdentifier(baseIdentifier);
    var expr = new SQLExpression(-1);
    expr.setMathExpression(base);
    return expr;
  }

  static SQLExpression bareIdentifier(String name) {
    return new SQLExpression(new SQLIdentifier(name));
  }

  static SQLExpression contextVariable(String name) {
    return bareIdentifier(name);
  }
}
