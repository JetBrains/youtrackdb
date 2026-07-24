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
 * {@code field STARTSWITH prefix} prefix-match condition.
 *
 * <p>This node has no grammar production — it is not reachable from parsed SQL/GQL text and javacc
 * never generates it. It is built programmatically by {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder#startsWithStrict}
 * to back the Gremlin {@code Text.startingWith} predicate, so it lives beside the sibling {@code
 * SQL*Condition} nodes and is edited by hand. It extends {@link SQLBooleanExpression} and is
 * constructed with {@code id = -1}, the established builder pattern for a hand-assembled AST node.
 *
 * <p>Semantics mirror {@link SQLEndsWithCondition} but test a prefix ({@link String#startsWith})
 * rather than a suffix, and honor the left property's declared collation so a {@code ci} property
 * matches case-insensitively. Unlike the index-aware half-open range {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder#startsWith}
 * builds for the declared-String path, this node is a full scan ({@link #isIndexAware} is always
 * {@code false}). It exists for the Gremlin strict path, where native TinkerPop {@code Text} parity
 * requires throwing on a non-String operand — a behavior the range form cannot express.
 *
 * <p>The node round-trips through {@link #copy}, {@link #toGenericStatement}, and {@link
 * #splitForAggregation} and is compared by value in {@link #equals} / {@link #hashCode}. The SQL
 * engine deep-copies plans on {@code clone()} / cache-get and reconstructs conditions field-by-field
 * in {@code splitForAggregation}, so every reconstruction site must carry both operands and the
 * strict flag or a copied plan silently loses the predicate (or its strictness).
 */
public class SQLStartsWithCondition extends SQLBooleanExpression {

  protected SQLExpression left;
  protected SQLExpression right;

  private final TextCollationResolver collate = new TextCollationResolver();

  /**
   * When {@code true}, a present non-{@code String} left operand throws {@link
   * com.jetbrains.youtrackdb.internal.core.exception.NonStringTextOperandException} instead of
   * yielding {@code false}, mirroring native TinkerPop {@code Text.startingWith} (String-only). It is
   * set only when the node is built programmatically by the Gremlin adapter; a lenient node leaves it
   * {@code false}. The flag is value-carrying: it participates in {@link #copy}, {@link #equals} /
   * {@link #hashCode}, and {@link #splitForAggregation}, and is reflected in {@link
   * #toGenericStatement} so a strict node and a lenient node on the same operands do not collide on
   * their plan-cache fingerprint.
   */
  protected boolean strict = false;

  public SQLStartsWithCondition(int id) {
    super(id);
  }

  public SQLStartsWithCondition(YouTrackDBSql p, int id) {
    super(p, id);
  }

  @Override
  public boolean evaluate(Identifiable currentRecord, CommandContext ctx) {
    // Type-check the left operand before any collate transform: strict throws on a present
    // non-String, lenient (and null/absent) yields false.
    var leftValue =
        TextCollationResolver.requireStringOperand(left.execute(currentRecord, ctx), strict,
            "STARTSWITH");
    if (leftValue == null) {
      return false;
    }
    if (!(right.execute(currentRecord, ctx) instanceof String rightValue)) {
      return false;
    }
    return startsWithCollated(leftValue, rightValue,
        collate.resolve(left, right, currentRecord, ctx));
  }

  @Override
  public boolean evaluate(Result currentRecord, CommandContext ctx) {
    var leftValue =
        TextCollationResolver.requireStringOperand(left.execute(currentRecord, ctx), strict,
            "STARTSWITH");
    if (leftValue == null) {
      return false;
    }
    if (!(right.execute(currentRecord, ctx) instanceof String rightValue)) {
      return false;
    }
    return startsWithCollated(leftValue, rightValue,
        collate.resolve(left, right, currentRecord, ctx));
  }

  /** Applies {@code collate} (if any) to both operands, then tests the prefix. */
  private static boolean startsWithCollated(String value, String prefix,
      @Nullable Collate collate) {
    return TextCollationResolver.apply(value, collate)
        .startsWith(TextCollationResolver.apply(prefix, collate));
  }

  @Override
  public void toString(Map<Object, Object> params, StringBuilder builder) {
    left.toString(params, builder);
    builder.append(" STARTSWITH ");
    right.toString(params, builder);
  }

  @Override
  public void toGenericStatement(StringBuilder builder) {
    left.toGenericStatement(builder);
    // Distinct token in strict mode so a strict node and a lenient node on the same operands produce
    // different plan-cache fingerprints.
    builder.append(strict ? " STARTSWITH(strict) " : " STARTSWITH ");
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
  public SQLStartsWithCondition copy() {
    var result = new SQLStartsWithCondition(-1);
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

    var that = (SQLStartsWithCondition) o;

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
    var result = new SQLStartsWithCondition(-1);
    result.left = left == null ? null : left.splitForAggregation(aggregateProj, ctx);
    result.right = right == null ? null : right.splitForAggregation(aggregateProj, ctx);
    result.strict = strict;
    return result;
  }
}
