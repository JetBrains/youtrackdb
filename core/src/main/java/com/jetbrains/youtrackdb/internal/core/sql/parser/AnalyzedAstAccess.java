package com.jetbrains.youtrackdb.internal.core.sql.parser;

import javax.annotation.Nullable;

/// Read-only accessor for the package-visible fields of the {@code SQL*} parse nodes that the
/// analyzed-expression lowering pass needs but cannot reach through a public getter.
///
/// The lowering pass lives in {@code com.jetbrains.youtrackdb.internal.core.query.analyzed} and
/// reads the AST to build the analyzed-expression IR. Most of the fields it needs already have
/// public getters ({@code SQLExpression.getMathExpression()},
/// {@code SQLBaseExpression.getIdentifier()}, and so on); the lowering pass uses those directly. A
/// handful of fields are {@code protected} or package-private and have no getter, so a same-package
/// accessor is the only forward-dependency way to read them without editing the generated
/// parse-node classes. This class supplies exactly those reads.
///
/// The class is deliberately one-directional: it takes {@code sql.parser} AST nodes and returns
/// {@code sql.parser} / {@code java} types only, naming no {@code query.analyzed} type. So the
/// dependency edge stays {@code query.analyzed → sql.parser} (the lowering pass depends on the
/// AST), never the reverse. It is final, has a private constructor, and exposes only static read
/// methods — it adds no behavior to the AST and holds no state.
///
/// `SQLExpression.literalValue` is intentionally NOT exposed. It is `private` (unreadable even from
/// this same-package accessor) and is never assigned on the SQL `Expression()` parse path — only
/// the GQL lowering and copy paths write it. The lowering pass dispatches on the recognized typed
/// fields and throws on everything else as its default, so a hypothetically-set `literalValue`
/// (GQL-originated) is covered by that throw-default. Exposing it here is deferred to the
/// GQL-lowering slice.
public final class AnalyzedAstAccess {

  private AnalyzedAstAccess() {
  }

  /// The boolean-literal payload of a `TRUE` / `FALSE` expression, or {@code null} when the
  /// expression is not a boolean literal.
  @Nullable
  public static Boolean booleanValue(SQLExpression expression) {
    return expression.booleanValue;
  }

  /// Whether the expression is the `null` literal.
  public static boolean isNull(SQLExpression expression) {
    return expression.isNull;
  }

  /// The boolean-expression payload (a comparison, `NOT`, `AND`/`OR`, …), or {@code null} when
  /// the expression carries no boolean expression.
  @Nullable
  public static SQLBooleanExpression booleanExpression(SQLExpression expression) {
    return expression.booleanExpression;
  }

  /// The numeric-literal node of a base expression, or {@code null} when the base expression is not
  /// a numeric literal.
  @Nullable
  public static SQLNumber number(SQLBaseExpression baseExpression) {
    return baseExpression.number;
  }

  /// The bind-parameter node of a base expression, or {@code null} when the base expression is not
  /// a bind parameter.
  @Nullable
  public static SQLInputParameter inputParam(SQLBaseExpression baseExpression) {
    return baseExpression.inputParam;
  }

  /// The grouped inner expression of a parenthesis node (e.g. the `a + b` in `(a + b)`), or
  /// {@code null} when the parenthesis wraps a subquery statement instead.
  @Nullable
  public static SQLExpression parenExpression(SQLParenthesisExpression parenthesis) {
    return parenthesis.expression;
  }

  /// The subquery statement of a parenthesis node (e.g. the `SELECT …` in `(SELECT …)`), or
  /// {@code null} when the parenthesis wraps a grouped expression instead.
  @Nullable
  public static SQLStatement parenStatement(SQLParenthesisExpression parenthesis) {
    return parenthesis.statement;
  }
}
