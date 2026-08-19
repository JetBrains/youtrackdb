package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchInfo;
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

  private final TextCollationResolver collate = new TextCollationResolver();

  /**
   * When {@code true}, a present non-{@code String} left operand throws {@link
   * com.jetbrains.youtrackdb.internal.core.exception.NonStringTextOperandException} instead of
   * yielding {@code false}, mirroring native TinkerPop {@code Text.endingWith} (String-only). It is
   * set only when the node is built programmatically by the Gremlin adapter; parser-built (SQL/GQL)
   * nodes leave it {@code false}, so their lenient behavior is unchanged. The flag is value-carrying:
   * it participates in {@link #copy}, {@link #equals} / {@link #hashCode}, and {@link
   * #splitForAggregation}, and is reflected in {@link #toGenericStatement} so a strict node and a
   * lenient node on the same operands do not collide on their plan-cache fingerprint.
   */
  protected boolean strict = false;

  public SQLEndsWithCondition(int id) {
    super(id);
  }

  public SQLEndsWithCondition(YouTrackDBSql p, int id) {
    super(p, id);
  }

  @Override
  public boolean evaluate(Identifiable currentRecord, CommandContext ctx) {
    // Type-check the left operand before any collate transform: strict throws on a present
    // non-String, lenient (and null/absent) yields false.
    var leftValue =
        TextCollationResolver.requireStringOperand(left.execute(currentRecord, ctx), strict,
            "ENDSWITH");
    if (leftValue == null) {
      return false;
    }
    if (!(right.execute(currentRecord, ctx) instanceof String rightValue)) {
      return false;
    }
    return endsWithCollated(leftValue, rightValue, collate.resolve(left, right, currentRecord, ctx));
  }

  @Override
  public boolean evaluate(Result currentRecord, CommandContext ctx) {
    var leftValue =
        TextCollationResolver.requireStringOperand(left.execute(currentRecord, ctx), strict,
            "ENDSWITH");
    if (leftValue == null) {
      return false;
    }
    if (!(right.execute(currentRecord, ctx) instanceof String rightValue)) {
      return false;
    }
    return endsWithCollated(leftValue, rightValue, collate.resolve(left, right, currentRecord, ctx));
  }

  /** Applies {@code collate} (if any) to both operands, then tests the suffix. */
  private static boolean endsWithCollated(String value, String suffix, @Nullable Collate collate) {
    return TextCollationResolver.apply(value, collate)
        .endsWith(TextCollationResolver.apply(suffix, collate));
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
    // Distinct token in strict mode so a strict node and a lenient node on the same operands produce
    // different plan-cache fingerprints. Parser-built nodes are lenient, so their token is unchanged.
    builder.append(strict ? " ENDSWITH(strict) " : " ENDSWITH ");
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
    result.strict = strict;
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
    if (!Objects.equals(right, that.right)) {
      return false;
    }
    return strict == that.strict;
  }

  @Override
  public int hashCode() {
    var result = left != null ? left.hashCode() : 0;
    result = 31 * result + (right != null ? right.hashCode() : 0);
    result = 31 * result + (strict ? 1 : 0);
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

  public void setStrict(boolean strict) {
    this.strict = strict;
  }

  public boolean isStrict() {
    return strict;
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
    result.strict = strict;
    return result;
  }
}
