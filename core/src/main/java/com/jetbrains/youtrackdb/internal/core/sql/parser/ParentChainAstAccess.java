package com.jetbrains.youtrackdb.internal.core.sql.parser;

import java.util.List;
import javax.annotation.Nullable;

/// Read-only accessor for the `SQL*` parse-node fields that the parent-only-chain predicate needs
/// and that no public getter exposes.
///
/// The predicate itself lives in
/// {@code com.jetbrains.youtrackdb.internal.core.sql.executor.ParentOnlyChain}. It is kept outside
/// this package on purpose: the build excludes `**/internal/core/sql/parser/**` from Spotless, from
/// ErrorProne and NullAway, and from the JaCoCo coverage report, so decision logic placed here
/// would escape all three gates. This class therefore holds no decision logic and no branching —
/// every method is a single field read.
///
/// Fields that already have a public getter are not repeated here. The predicate reads those
/// directly: `SQLExpression.getRid()` / `getMathExpression()`, `SQLBaseExpression.getIdentifier()` /
/// `getModifier()`, `SQLBaseIdentifier.getLevelZero()` / `getSuffix()`,
/// `SQLSuffixIdentifier.getIdentifier()` / `getRecordAttribute()` / `isStar()`, and
/// `SQLModifier.getSuffix()` / `getMethodCall()` / `getNext()`.
///
/// {@link AnalyzedAstAccess} is the precedent for this shape and exposes an overlapping handful of
/// reads. It is deliberately not reused: its documented client is the analyzed-expression lowering
/// pass in `com.jetbrains.youtrackdb.internal.core.query.analyzed`, and widening it to a second,
/// unrelated caller would blur that one-directional contract. Merging the two accessors is possible
/// later if a third caller appears.
public final class ParentChainAstAccess {

  private ParentChainAstAccess() {
  }

  /// Whether the expression is the `null` literal.
  public static boolean isNull(SQLExpression expression) {
    return expression.isNull;
  }

  /// The numeric-literal node of a base expression, or {@code null} when there is none.
  @Nullable
  public static SQLNumber number(SQLBaseExpression baseExpression) {
    return baseExpression.number;
  }

  /// The raw string-literal payload of a base expression, quotes included, or {@code null} when
  /// there is none.
  @Nullable
  public static String stringLiteral(SQLBaseExpression baseExpression) {
    return baseExpression.string;
  }

  /// The bind-parameter node of a base expression, or {@code null} when there is none.
  @Nullable
  public static SQLInputParameter inputParam(SQLBaseExpression baseExpression) {
    return baseExpression.inputParam;
  }

  /// Whether this modifier link is a bracket step (`[...]`) rather than a dotted or method step.
  public static boolean hasSquareBrackets(SQLModifier modifier) {
    return modifier.squareBrackets;
  }

  /// The range selector of a bracket step (`[0..2]`), or {@code null} when there is none.
  @Nullable
  public static SQLArrayRangeSelector arrayRange(SQLModifier modifier) {
    return modifier.arrayRange;
  }

  /// The filter condition of a bracket step (`[name = 'x']`), or {@code null} when there is none.
  @Nullable
  public static SQLOrBlock condition(SQLModifier modifier) {
    return modifier.condition;
  }

  /// The index-list selector of a bracket step (`[0]`, `[0,1]`), or {@code null} when there is
  /// none.
  @Nullable
  public static SQLArraySingleValuesSelector arraySingleValues(SQLModifier modifier) {
    return modifier.arraySingleValues;
  }

  /// The right binary condition of a bracket step (`[= 3]`), or {@code null} when there is none.
  @Nullable
  public static SQLRightBinaryCondition rightBinaryCondition(SQLModifier modifier) {
    return modifier.rightBinaryCondition;
  }

  /// The individual index selectors of an index-list selector.
  public static List<SQLArraySelector> items(SQLArraySingleValuesSelector selector) {
    return selector.items;
  }

  /// The RID payload of one index selector, or {@code null} when there is none.
  @Nullable
  public static SQLRid selectorRid(SQLArraySelector selector) {
    return selector.rid;
  }

  /// The bind-parameter payload of one index selector, or {@code null} when there is none.
  @Nullable
  public static SQLInputParameter selectorInputParam(SQLArraySelector selector) {
    return selector.inputParam;
  }

  /// The expression payload of one index selector, or {@code null} when there is none.
  @Nullable
  public static SQLExpression selectorExpression(SQLArraySelector selector) {
    return selector.expression;
  }

  /// The integer payload of one index selector, or {@code null} when there is none.
  @Nullable
  public static SQLInteger selectorInteger(SQLArraySelector selector) {
    return selector.integer;
  }
}
