package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBetweenCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCollection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsTextCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNotDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLevelZeroIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent builder for {@code WHERE}-clause AST trees in the unified MATCH IR.
 *
 * <p>Each operation maps to the same parser-emitted AST shape that {@link
 * com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql} produces, so the resulting
 * condition tree is interchangeable with one obtained from parsing SQL text — the planner and
 * executor cannot tell the difference.
 *
 * <p>The builder is stateless: every method returns a fresh AST node and never mutates {@code
 * this}. Instances exist only for fluent ergonomics; reuse a single instance freely or construct
 * one per call.
 */
public final class MatchWhereBuilder {

  /** Builds {@code field = value}. */
  public SQLBooleanExpression eq(String field, SQLExpression value) {
    return op(field, SQLEqualsOperator.INSTANCE, value);
  }

  /**
   * Builds {@code field {op} value} for any {@link SQLBinaryCompareOperator} ({@code =}, {@code
   * !=}, {@code >}, {@code >=}, {@code <}, {@code <=}, {@code LIKE}, …).
   */
  public SQLBooleanExpression op(String field, SQLBinaryCompareOperator operator,
      SQLExpression value) {
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(fieldExpression(field));
    condition.setOperator(operator);
    condition.setRight(value);
    return condition;
  }

  /**
   * Builds {@code field IN [v1, v2, …]}. Element expressions are wrapped in an {@link SQLCollection}
   * under a synthetic {@link SQLBaseExpression} / {@link SQLBaseIdentifier} chain — the same shape
   * the parser emits for inline literal lists, so the runtime's {@link SQLInCondition} evaluator
   * picks up the values via its {@code rightMathExpression} branch. Populates
   * {@link SQLInCondition#setOperator} so plan-time paths such as
   * {@link SQLInCondition#supportsBasicCalculation} do not NPE.
   */
  public SQLBooleanExpression in(String field, List<SQLExpression> values) {
    var condition = new SQLInCondition(-1);
    condition.setLeft(fieldExpression(field));
    condition.setRightMathExpression(literalCollectionExpression(values));
    condition.setOperator(new SQLInOperator(-1));
    return condition;
  }

  /**
   * Builds {@code NOT (field IN [v1, v2, …])}. Composes via {@link #not(SQLBooleanExpression)}
   * instead of producing an {@code SQLNotInCondition} directly — that AST class has no public
   * setters reachable from outside the parser package.
   */
  public SQLBooleanExpression notIn(String field, List<SQLExpression> values) {
    return not(in(field, values));
  }

  /**
   * Builds {@code field BETWEEN lo AND hi}. Uses {@link SQLBetweenCondition} (the parser-emitted
   * shape) rather than {@code lo <= field AND field <= hi} so range-aware index lookups remain
   * available in the planner.
   */
  public SQLBooleanExpression between(String field, SQLExpression lo, SQLExpression hi) {
    var condition = new SQLBetweenCondition(-1);
    condition.setFirst(fieldExpression(field));
    condition.setSecond(lo);
    condition.setThird(hi);
    return condition;
  }

  /**
   * Builds {@code field CONTAINSTEXT 'substring'}. CONTAINSTEXT is a YTDB-native operator distinct
   * from LIKE; uses {@link SQLContainsTextCondition}.
   */
  public SQLBooleanExpression containsText(String field, String substring) {
    var condition = new SQLContainsTextCondition(-1);
    condition.setLeft(fieldExpression(field));
    condition.setRight(stringExpression(substring));
    return condition;
  }

  /**
   * Builds a prefix match {@code field >= prefix AND field < prefix⁺} as two range conditions (no
   * dedicated prefix AST node). A {@code startingWith(p)} match selects exactly the strings in the
   * half-open range {@code [p, p⁺)}, where {@code p⁺} is {@code p} with its last code point
   * incremented. Range operators are index-aware, so a B-tree index on the field turns this into a
   * prefix range scan. The engine applies the field's collation to both range operands automatically
   * (see {@link SQLBinaryCondition}), so the case rule follows the property collation.
   *
   * <p>Throws {@link IllegalArgumentException} on an empty prefix: the upper bound {@code p⁺} is
   * undefined for an empty string. Callers decline (return {@code Optional.empty()}) before
   * reaching this method.
   */
  public SQLBooleanExpression startsWith(String field, String prefix) {
    if (prefix.isEmpty()) {
      throw new IllegalArgumentException("startsWith prefix must be non-empty");
    }
    var lower = op(field, new SQLGeOperator(-1), stringExpression(prefix));
    var upper = op(field, SQLLtOperator.INSTANCE, stringExpression(incrementLastCodePoint(prefix)));
    return and(lower, upper);
  }

  /**
   * Combines operands with logical AND.
   *
   * <ul>
   *   <li>Empty input throws {@link IllegalStateException} — avoids the silent {@code true}
   *       tautology that would change query semantics if a caller bug produced no operands.
   *   <li>Single operand is returned as-is, matching parser parity (the parser never wraps a lone
   *       condition in a one-element block).
   *   <li>Two or more operands produce an {@link SQLAndBlock} with {@link SQLAndBlock#setSubBlocks}.
   * </ul>
   */
  public SQLBooleanExpression and(SQLBooleanExpression... ops) {
    return combine(ops, /*isAnd=*/ true);
  }

  /**
   * Combines operands with logical OR. Same cardinality semantics as {@link
   * #and(SQLBooleanExpression...)}.
   */
  public SQLBooleanExpression or(SQLBooleanExpression... ops) {
    return combine(ops, /*isAnd=*/ false);
  }

  /**
   * AND-merges optional operands. Mirrors {@link #and} but tolerates {@code null} entries and an
   * empty input — useful at recogniser merge sites where some contributing filters may be absent
   * (e.g. polymorphism class-narrowing only applies in non-polymorphic mode; a multi-RID filter
   * only applies when more than one ID was supplied).
   *
   * <p>Three behaviours by effective cardinality:
   *
   * <ul>
   *   <li>0 effective operands (all-null or empty input) → returns {@code null}. Callers typically
   *       interpret this as "nothing to write".
   *   <li>1 effective operand → returned as-is, matching {@link #and}'s parser-parity rule.
   *   <li>2+ effective operands → produces a single {@link SQLAndBlock} with all operands as
   *       sub-blocks.
   * </ul>
   */
  public SQLBooleanExpression andOptional(SQLBooleanExpression... ops) {
    if (ops == null || ops.length == 0) {
      return null;
    }
    var kept = new ArrayList<SQLBooleanExpression>(ops.length);
    for (var op : ops) {
      if (op != null) {
        kept.add(op);
      }
    }
    if (kept.isEmpty()) {
      return null;
    }
    if (kept.size() == 1) {
      return kept.getFirst();
    }
    var block = new SQLAndBlock(-1);
    block.setSubBlocks(new ArrayList<>(kept));
    return block;
  }

  /**
   * Builds {@code field IS NULL} for a property reference (parsed shape: {@link SQLIdentifier}).
   * The runtime evaluator uses {@code expression.execute() == null}, so document stores conflate
   * "property absent" and "property set to literal null". Used by the {@code eq(null)} /
   * {@code neq(null)} predicate adapters (Track 4). Gremlin {@code hasNot(key)} maps to
   * {@link #isNotDefined(String)}, not here — {@code IS NULL} would over-match vertices that store
   * the key with a null value.
   */
  public SQLBooleanExpression isNull(String field) {
    return isNull(fieldExpression(field));
  }

  /**
   * Builds {@code expression IS NULL} for any {@link SQLExpression} left-side — accepts both
   * {@link SQLIdentifier}-wrapped property references and {@link SQLRecordAttribute}-wrapped record
   * attributes (e.g. {@code @class IS NULL}).
   */
  public SQLBooleanExpression isNull(SQLExpression expression) {
    var condition = new SQLIsNullCondition(-1);
    condition.setExpression(expression);
    return condition;
  }

  /**
   * Builds {@code attribute IS NULL} for a record-attribute left-side (e.g. {@code @class IS
   * NULL}). Convenience wrapper that constructs the {@link SQLRecordAttribute} / {@link
   * SQLExpression} chain so callers don't have to.
   */
  public SQLBooleanExpression isNullAttribute(String attributeName) {
    var attr = new SQLRecordAttribute(-1);
    attr.setName(attributeName);
    return isNull(new SQLExpression(attr, null));
  }

  /**
   * Builds {@code field IS DEFINED} — entity-presence predicate distinct from {@code IS NULL}.
   * The emitted condition matches when the record carries {@code field} at the storage layer,
   * regardless of value (including literal {@code null}); the AST node's evaluator routes through
   * {@code SQLExpression.isDefinedFor}. Used by the Gremlin translator's {@code has(key)} filter
   * mapping where TinkerPop's {@code Property.isPresent()} semantics require the entity-layer view,
   * not the value-layer view that {@code IS NOT NULL} provides.
   */
  public SQLBooleanExpression isDefined(String field) {
    var condition = new SQLIsDefinedCondition(-1);
    condition.setExpression(fieldExpression(field));
    return condition;
  }

  /**
   * Builds {@code field IS NOT DEFINED} — symmetric to {@link #isDefined(String)}. Used by the
   * Gremlin translator's {@code hasNot(key)} filter mapping. NOT equivalent to {@code IS NULL}: a
   * property stored with literal {@code null} value is false under {@code IS NOT DEFINED} (the
   * property exists, just with a null value) but true under {@code IS NULL}, mirroring TinkerPop's
   * {@code Property.isPresent()} as true for null-valued YTDB properties.
   */
  public SQLBooleanExpression isNotDefined(String field) {
    var condition = new SQLIsNotDefinedCondition(-1);
    condition.setExpression(fieldExpression(field));
    return condition;
  }

  /**
   * Builds {@code NOT (sub)}. The returned {@link SQLNotBlock} has both {@code sub} set and
   * {@code negate=true}; the latter is essential because {@link SQLNotBlock}'s default of
   * {@code false} would silently produce a pass-through that evaluates the inner block with no
   * negation.
   */
  public SQLBooleanExpression not(SQLBooleanExpression sub) {
    var block = new SQLNotBlock(-1);
    block.setSub(sub);
    block.setNegate(true);
    return block;
  }

  /**
   * Wraps a boolean expression in an {@link SQLWhereClause} so it can be plugged into the unified
   * MATCH IR's {@code aliasFilters} map directly.
   */
  public SQLWhereClause wrap(SQLBooleanExpression expr) {
    var clause = new SQLWhereClause(-1);
    clause.setBaseExpression(expr);
    return clause;
  }

  // ── Internal helpers ──

  /**
   * Computes {@code p⁺}: the smallest string strictly greater than every string that starts with
   * {@code prefix}, used as the exclusive upper bound of the half-open prefix range
   * {@code [prefix, prefix⁺)}. The last code point of {@code prefix} is incremented by one.
   * Surrogate pairs are handled by reading the trailing code point with
   * {@link String#codePointBefore} (which spans the full pair) and replacing it via.
   *
   * <p>Overflow carry: if the trailing code point is the maximum ({@link Character#MAX_CODE_POINT},
   * {@code U+10FFFF}), it cannot be incremented in place. The method drops that code point and
   * increments the preceding one instead — {@code "a􏿿"} (an {@code a} followed by the max code
   * point) becomes {@code "b"}. If every code point is the maximum (e.g. a lone {@code U+10FFFF}),
   * no finite upper bound exists, so the method throws {@link IllegalArgumentException} and the
   * caller declines. This is a pathological input that cannot occur from a realistic prefix
   * string.
   */
  private static String incrementLastCodePoint(String prefix) {
    var cp = prefix.codePointBefore(prefix.length());
    // Number of UTF-16 chars the trailing code point occupies (2 for a surrogate
    // pair, 1 otherwise) — the slice length to strip before re-appending.
    var trailingCharCount = Character.charCount(cp);
    var head = prefix.substring(0, prefix.length() - trailingCharCount);
    if (cp != Character.MAX_CODE_POINT) {
      return new StringBuilder(head).appendCodePoint(cp + 1).toString();
    }
    // Trailing code point is the maximum and cannot be bumped; carry into the head.
    if (head.isEmpty()) {
      throw new IllegalArgumentException(
          "startsWith prefix has no finite upper bound (all code points are the maximum)");
    }
    return incrementLastCodePoint(head);
  }

  private SQLBooleanExpression combine(SQLBooleanExpression[] ops, boolean isAnd) {
    if (ops == null || ops.length == 0) {
      throw new IllegalStateException("at least one operand required");
    }
    if (ops.length == 1) {
      return ops[0];
    }
    if (isAnd) {
      var block = new SQLAndBlock(-1);
      // setSubBlocks replaces the underlying list wholesale, matching the parser-emitted shape.
      block.setSubBlocks(new ArrayList<>(Arrays.asList(ops)));
      return block;
    }
    // SQLOrBlock has no setSubBlocks counterpart — only addSubBlock — so populate iteratively.
    var block = new SQLOrBlock(-1);
    for (var op : ops) {
      block.addSubBlock(op);
    }
    return block;
  }

  /**
   * Builds an {@link SQLExpression} referencing {@code name} as a base identifier — the shape the
   * parser produces for a bare property reference inside a {@code WHERE}.
   */
  private static SQLExpression fieldExpression(String name) {
    return new SQLExpression(new SQLIdentifier(name));
  }

  private static SQLExpression stringExpression(String value) {
    var expr = new SQLExpression(-1);
    expr.setMathExpression(new SQLBaseExpression(value));
    return expr;
  }

  /**
   * Wraps {@code values} in the AST chain {@code SQLBaseExpression → SQLBaseIdentifier →
   * SQLLevelZeroIdentifier → SQLCollection}, matching the parser's representation of an inline
   * literal list (e.g. {@code WHERE x IN [1, 2, 3]}). {@link SQLInCondition#evaluateRight} resolves
   * this via {@code rightMathExpression.execute(...)}, which recurses into the collection's element
   * expressions.
   */
  private static SQLBaseExpression literalCollectionExpression(List<SQLExpression> values) {
    var coll = new SQLCollection(-1);
    for (var v : values) {
      coll.add(v);
    }
    var levelZero = new SQLLevelZeroIdentifier(-1);
    levelZero.setCollection(coll);
    var ident = new SQLBaseIdentifier(-1);
    ident.setLevelZero(levelZero);
    var base = new SQLBaseExpression(-1);
    base.setIdentifier(ident);
    return base;
  }

}
