package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchInfo;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code field ENDSWITH suffix} suffix-match condition.
 *
 * <p>This node has no grammar production — it is not reachable from parsed SQL/GQL text and javacc
 * never generates it. It is built programmatically by {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder#endsWith} to
 * back the Gremlin {@code Text.endingWith} predicate, so it lives beside the sibling {@code
 * SQL*Condition} nodes and is edited by hand. It extends {@link SQLBooleanExpression} and is
 * constructed with {@code id = -1}, the established builder pattern for a hand-assembled AST node.
 *
 * <p>Semantics mirror {@link SQLContainsTextCondition} but test a suffix ({@link String#endsWith})
 * rather than a substring, and — like CONTAINSTEXT — honor the left property's declared collation so
 * a {@code ci} property matches case-insensitively. The comparison is a full scan ({@link
 * #isIndexAware} is always {@code false}): there is no index representation of a suffix match, unlike
 * the {@code startsWith} half-open range.
 *
 * <p>The node round-trips through {@link #copy}, {@link #toGenericStatement}, and {@link
 * #splitForAggregation} and is compared by value in {@link #equals} / {@link #hashCode}. The SQL
 * engine deep-copies plans on {@code clone()} / cache-get and reconstructs conditions field-by-field
 * in {@code splitForAggregation}, so every reconstruction site must carry both operands or a copied
 * plan silently loses the predicate.
 */
public class SQLEndsWithCondition extends SQLBooleanExpression {

  protected SQLExpression left;
  protected SQLExpression right;

  public SQLEndsWithCondition(int id) {
    super(id);
  }

  public SQLEndsWithCondition(YouTrackDBSql p, int id) {
    super(p, id);
  }

  @Override
  public boolean evaluate(Identifiable currentRecord, CommandContext ctx) {
    if (!(left.execute(currentRecord, ctx) instanceof String leftValue)) {
      return false;
    }
    if (!(right.execute(currentRecord, ctx) instanceof String rightValue)) {
      return false;
    }
    // No getCollate(Identifiable) overload exists, so wrap the record in a Result to reuse the same
    // collation resolution as the Result path. The record is already loaded by the left.execute
    // above, so the wrapper adds no I/O.
    var collate = resolveCollate(new ResultInternal(ctx.getDatabaseSession(), currentRecord), ctx);
    return endsWithCollated(leftValue, rightValue, collate);
  }

  @Override
  public boolean evaluate(Result currentRecord, CommandContext ctx) {
    if (!(left.execute(currentRecord, ctx) instanceof String leftValue)) {
      return false;
    }
    if (!(right.execute(currentRecord, ctx) instanceof String rightValue)) {
      return false;
    }
    return endsWithCollated(leftValue, rightValue, resolveCollate(currentRecord, ctx));
  }

  /**
   * Resolves the collation governing the suffix comparison. The left operand is the property
   * reference, so its declared collation wins; the right operand (the literal suffix) is a fallback,
   * and {@code null} means schema-less / default (raw case-sensitive comparison). Mirrors {@link
   * SQLContainsTextCondition} and {@link SQLBinaryCondition}, which collate both operands so
   * evaluation is consistent regardless of the source path.
   */
  @Nullable
  private Collate resolveCollate(Result record, CommandContext ctx) {
    var collate = left.getCollate(record, ctx);
    if (collate == null) {
      collate = right.getCollate(record, ctx);
    }
    return collate;
  }

  /** Applies {@code collate} (if any) to both operands, then tests the suffix. */
  private static boolean endsWithCollated(String value, String suffix, @Nullable Collate collate) {
    if (collate != null) {
      value = (String) collate.transform(value);
      suffix = (String) collate.transform(suffix);
    }
    return value.endsWith(suffix);
  }

  @Override
  public void toString(Map<Object, Object> params, StringBuilder builder) {
    left.toString(params, builder);
    builder.append(" ENDSWITH ");
    right.toString(params, builder);
  }

  @Override
  public void toGenericStatement(StringBuilder builder) {
    left.toGenericStatement(builder);
    builder.append(" ENDSWITH ");
    right.toGenericStatement(builder);
  }

  @Override
  public boolean supportsBasicCalculation() {
    return true;
  }

  @Override
  protected int getNumberOfExternalCalculations() {
    var total = 0;
    if (!left.supportsBasicCalculation()) {
      total++;
    }
    if (!right.supportsBasicCalculation()) {
      total++;
    }
    return total;
  }

  @Override
  protected List<Object> getExternalCalculationConditions() {
    List<Object> result = new ArrayList<>();
    if (!left.supportsBasicCalculation()) {
      result.add(left);
    }
    if (!right.supportsBasicCalculation()) {
      result.add(right);
    }
    return result;
  }

  @Override
  public boolean needsAliases(Set<String> aliases) {
    if (!left.needsAliases(aliases)) {
      return true;
    }
    return !right.needsAliases(aliases);
  }

  @Override
  public SQLEndsWithCondition copy() {
    var result = new SQLEndsWithCondition(-1);
    result.left = left.copy();
    result.right = right.copy();
    return result;
  }

  @Override
  public void extractSubQueries(SubQueryCollector collector) {
    left.extractSubQueries(collector);
    right.extractSubQueries(collector);
  }

  @Override
  public boolean refersToParent() {
    return left.refersToParent() || right.refersToParent();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (SQLEndsWithCondition) o;

    if (!Objects.equals(left, that.left)) {
      return false;
    }
    return Objects.equals(right, that.right);
  }

  @Override
  public int hashCode() {
    var result = left != null ? left.hashCode() : 0;
    result = 31 * result + (right != null ? right.hashCode() : 0);
    return result;
  }

  @Nullable
  @Override
  public List<String> getMatchPatternInvolvedAliases() {
    var leftX = left == null ? null : left.getMatchPatternInvolvedAliases();
    var rightX = right == null ? null : right.getMatchPatternInvolvedAliases();

    List<String> result = new ArrayList<>();
    if (leftX != null) {
      result.addAll(leftX);
    }
    if (rightX != null) {
      result.addAll(rightX);
    }

    return result.isEmpty() ? null : result;
  }

  @Override
  public boolean isCacheable(DatabaseSessionEmbedded session) {
    if (left != null && !left.isCacheable(session)) {
      return false;
    }
    return right == null || right.isCacheable(session);
  }

  @Override
  public boolean isIndexAware(IndexSearchInfo info, CommandContext ctx) {
    return false;
  }

  @Override
  public boolean isRangeExpression() {
    return false;
  }

  @Nullable
  @Override
  public String getRelatedIndexPropertyName() {
    return null;
  }

  @Nullable
  @Override
  public SQLBooleanExpression mergeUsingAnd(SQLBooleanExpression other,
      @Nonnull CommandContext ctx) {
    return null;
  }

  public void setLeft(SQLExpression left) {
    this.left = left;
  }

  public void setRight(SQLExpression right) {
    this.right = right;
  }

  public SQLExpression getLeft() {
    return left;
  }

  public SQLExpression getRight() {
    return right;
  }

  @Override
  public boolean varMightBeInUse(String varName) {
    return left != null && left.varMightBeInUse(varName) ||
        right != null && right.varMightBeInUse(varName);
  }

  @Override
  public boolean isAggregate(DatabaseSessionEmbedded session) {
    return left != null && left.isAggregate(session)
        || right != null && right.isAggregate(session);
  }

  @Override
  public SQLBooleanExpression splitForAggregation(
      AggregateProjectionSplit aggregateProj, CommandContext ctx) {
    if (!isAggregate(ctx.getDatabaseSession())) {
      return this;
    }
    var result = new SQLEndsWithCondition(-1);
    result.left = left == null ? null : left.splitForAggregation(aggregateProj, ctx);
    result.right = right == null ? null : right.splitForAggregation(aggregateProj, ctx);
    return result;
  }
}
