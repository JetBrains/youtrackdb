package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.NonStringTextOperandException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import javax.annotation.Nullable;

/**
 * Resolves the collation governing a text comparison (CONTAINSTEXT / ENDSWITH) and applies it to a
 * value. Extracted from the previously verbatim-duplicated logic in {@link
 * SQLContainsTextCondition} and {@link SQLEndsWithCondition}.
 *
 * <p>An instance is held per condition node ({@code private final TextCollationResolver}) because it
 * caches the base-identifier property name derived from that node's {@code left} operand; the two
 * nodes carry different left operands, so the resolver must never be shared or static. The left and
 * right operands are passed per call rather than snapshotted, because the parser and {@code
 * MatchWhereBuilder} set them after the node (and this resolver) are constructed.
 */
class TextCollationResolver {

  /**
   * Cached property name of the base-identifier left operand for the {@link #resolve(SQLExpression,
   * SQLExpression, Identifiable, CommandContext)} scan path. {@code left.getDefaultAlias()} allocates
   * a fresh {@link SQLIdentifier} on every call, so reading it per record would reintroduce a per-row
   * allocation heavier than the {@link ResultInternal} wrapper the fast path was added to avoid. The
   * name is derived from the row-invariant {@code left} operand, so it is resolved once and reused;
   * {@code null} means not yet resolved. A benign data race resolves the same immutable String, so no
   * synchronization is needed.
   */
  @Nullable
  private String baseIdentifierName;

  /**
   * Resolves the collation governing this text comparison. The left operand is the property
   * reference, so its declared collation wins; the right operand (the literal) is a fallback, and
   * {@code null} means schema-less / default (raw case-sensitive comparison). Mirrors {@link
   * SQLBinaryCondition}, which collates both operands so an index-backed and a scan-backed
   * evaluation of the same predicate agree on a {@code ci} property.
   */
  @Nullable
  Collate resolve(SQLExpression left, SQLExpression right, Result record, CommandContext ctx) {
    var collate = left.getCollate(record, ctx);
    if (collate == null) {
      collate = right.getCollate(record, ctx);
    }
    return collate;
  }

  /**
   * Resolves the collation for the {@code evaluate(Identifiable)} scan path without the per-row
   * {@link ResultInternal} wrapper the {@link Result} overload needs. When the left operand is a
   * plain property reference on a schema entity, the collation is read straight from the record's
   * schema class — the same value {@link SQLSuffixIdentifier#getCollate} yields through the wrapper.
   * A declared property always resolves to a non-null collate (default or {@code ci}), so on the
   * unindexed hot path the wrapper is never allocated. Any other shape — a schema-less field, a
   * nested link chain, a non-entity record — falls through to the wrapper-based resolution so
   * behavior is unchanged off the common path.
   */
  @Nullable
  Collate resolve(SQLExpression left, SQLExpression right, Identifiable record,
      CommandContext ctx) {
    if (left.isBaseIdentifier() && record instanceof EntityImpl entity) {
      var name = baseIdentifierName;
      if (name == null) {
        // The name comes from the left operand's structure, not the record, so it is row-invariant.
        // Resolve it once here rather than per record: getDefaultAlias() allocates a fresh
        // SQLIdentifier on every call, which on a scan would dominate the allocation the fast path
        // was added to remove.
        name = left.getDefaultAlias().getStringValue();
        baseIdentifierName = name;
      }
      var collate = collateFromSchema(entity, name, ctx);
      if (collate != null) {
        return collate;
      }
    }
    return resolve(left, right, new ResultInternal(ctx.getDatabaseSession(), record), ctx);
  }

  /**
   * Resolves the declared collation of one named property on {@code record}'s class, for the
   * {@code any()} / {@code all()} paths where the left operand is not a single property reference.
   * Returns {@code null} when the record is not a schema entity or the property has no declared
   * collation.
   */
  @Nullable
  static Collate forProperty(Result record, String propertyName, CommandContext ctx) {
    if (record != null && record.isEntity() && record.asEntityOrNull() instanceof EntityImpl impl) {
      return collateFromSchema(impl, propertyName, ctx);
    }
    return null;
  }

  /**
   * Reads the declared collation of {@code propertyName} straight from {@code entity}'s schema
   * class. Returns {@code null} for a schema-less record or an undeclared property, which the callers
   * treat as the raw case-sensitive default.
   */
  @Nullable
  private static Collate collateFromSchema(EntityImpl entity, String propertyName,
      CommandContext ctx) {
    var schemaClass = entity.getImmutableSchemaClass(ctx.getDatabaseSession());
    if (schemaClass != null) {
      var property = schemaClass.getProperty(propertyName);
      if (property != null) {
        return property.getCollate();
      }
    }
    return null;
  }

  /**
   * Type-checks a text-predicate left operand before any collation transform runs, centralizing the
   * strict-vs-lenient rule shared by every text-predicate node. The check MUST happen before {@link
   * #apply} so a non-String value is never handed to {@link Collate#transform}.
   *
   * <ul>
   *   <li>{@code value} is a {@link String} → returned so the caller proceeds to collate + compare.
   *   <li>{@code value} is {@code null} → returns {@code null}; the caller yields {@code false}. A
   *       null/absent operand never throws, matching native TinkerPop {@code Text} predicates, which
   *       exclude an absent property rather than erroring.
   *   <li>{@code value} is present, non-{@code null}, and not a {@link String} → in strict mode
   *       throws {@link NonStringTextOperandException} (native String-only parity); in lenient mode
   *       returns {@code null} so the caller yields {@code false} (unchanged SQL/GQL behavior).
   * </ul>
   *
   * <p>Both the null case and the lenient non-String case return {@code null} because the caller
   * treats them identically (return {@code false}); only strict mode distinguishes a present
   * non-String by throwing.
   */
  @Nullable
  static String requireStringOperand(Object value, boolean strict, String operatorToken) {
    if (value instanceof String s) {
      return s;
    }
    if (value != null && strict) {
      throw new NonStringTextOperandException(operatorToken, value);
    }
    return null;
  }

  /**
   * Applies {@code collate} (if any) to {@code value}, returning the transformed String; returns
   * {@code value} unchanged when {@code collate} is {@code null}. Callers do their own comparison.
   */
  static String apply(String value, @Nullable Collate collate) {
    if (collate == null) {
      return value;
    }
    return (String) collate.transform(value);
  }
}
