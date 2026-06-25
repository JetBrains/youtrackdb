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

/// Fluent builder for `WHERE`-clause AST trees in the unified MATCH IR.
///
/// Each operation maps to the same parser-emitted AST shape that
/// [com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql] produces, so
/// the resulting condition tree is interchangeable with one obtained from parsing
/// SQL text — the planner and executor cannot tell the difference.
///
/// The builder is **stateless**: every method returns a fresh AST node and never
/// mutates `this`. Instances exist only for fluent ergonomics; reuse a single
/// instance freely or construct one per call.
public final class MatchWhereBuilder {

  /// Builds `field = value`.
  public SQLBooleanExpression eq(String field, SQLExpression value) {
    return op(field, SQLEqualsOperator.INSTANCE, value);
  }

  /// Builds `field {op} value` for any [SQLBinaryCompareOperator]
  /// (`=`, `!=`, `>`, `>=`, `<`, `<=`, `LIKE`, …).
  public SQLBooleanExpression op(String field, SQLBinaryCompareOperator operator,
      SQLExpression value) {
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(fieldExpression(field));
    condition.setOperator(operator);
    condition.setRight(value);
    return condition;
  }

  /// Builds `field IN [v1, v2, …]`. Element expressions are wrapped in an
  /// [SQLCollection] under a synthetic [SQLBaseExpression] / [SQLBaseIdentifier]
  /// chain — the same shape the parser emits for inline literal lists, so the
  /// runtime's [SQLInCondition] evaluator picks up the values via its
  /// `rightMathExpression` branch. Populates [SQLInCondition#setOperator] so
  /// plan-time paths such as [SQLInCondition#supportsBasicCalculation] do not NPE.
  public SQLBooleanExpression in(String field, List<SQLExpression> values) {
    var condition = new SQLInCondition(-1);
    condition.setLeft(fieldExpression(field));
    condition.setRightMathExpression(literalCollectionExpression(values));
    condition.setOperator(new SQLInOperator(-1));
    return condition;
  }

  /// Builds `NOT (field IN [v1, v2, …])`. Composes via [#not(SQLBooleanExpression)]
  /// instead of producing an `SQLNotInCondition` directly — that AST class has no
  /// public setters reachable from outside the parser package.
  public SQLBooleanExpression notIn(String field, List<SQLExpression> values) {
    return not(in(field, values));
  }

  /// Builds `field BETWEEN lo AND hi`. Uses [SQLBetweenCondition] (the parser-
  /// emitted shape) rather than `lo <= field AND field <= hi` so range-aware
  /// index lookups remain available in the planner.
  public SQLBooleanExpression between(String field, SQLExpression lo, SQLExpression hi) {
    var condition = new SQLBetweenCondition(-1);
    condition.setFirst(fieldExpression(field));
    condition.setSecond(lo);
    condition.setThird(hi);
    return condition;
  }

  /// Builds `field CONTAINSTEXT 'substring'`. CONTAINSTEXT is a YTDB-native
  /// operator distinct from LIKE; uses [SQLContainsTextCondition].
  public SQLBooleanExpression containsText(String field, String substring) {
    var condition = new SQLContainsTextCondition(-1);
    condition.setLeft(fieldExpression(field));
    condition.setRight(stringExpression(substring));
    return condition;
  }

  /// Builds a prefix match `field >= prefix AND field < prefix⁺` as two range
  /// conditions (no dedicated prefix AST node). A `startingWith(p)` match selects
  /// exactly the strings in the half-open range `[p, p⁺)`, where `p⁺` is `p` with
  /// its last code point incremented. Range operators are index-aware, so a B-tree
  /// index on the field turns this into a prefix range scan. The engine applies
  /// the field's collation to both range operands automatically (see
  /// [SQLBinaryCondition]), so the case rule follows the property collation.
  ///
  /// Throws [IllegalArgumentException] on an empty prefix: the upper bound `p⁺` is
  /// undefined for an empty string. Callers decline (return `Optional.empty()`)
  /// before reaching this method.
  public SQLBooleanExpression startsWith(String field, String prefix) {
    if (prefix.isEmpty()) {
      throw new IllegalArgumentException("startsWith prefix must be non-empty");
    }
    var lower = op(field, new SQLGeOperator(-1), stringExpression(prefix));
    var upper = op(field, SQLLtOperator.INSTANCE, stringExpression(incrementLastCodePoint(prefix)));
    return and(lower, upper);
  }

  /// Combines operands with logical AND.
  ///
  /// - Empty input throws [IllegalStateException] — avoids the silent `true`
  ///   tautology that would change query semantics if a caller bug produced
  ///   no operands.
  /// - Single operand is returned as-is, matching parser parity (the parser
  ///   never wraps a lone condition in a one-element block).
  /// - Two or more operands produce an [SQLAndBlock] with [SQLAndBlock#setSubBlocks].
  public SQLBooleanExpression and(SQLBooleanExpression... ops) {
    return combine(ops, /*isAnd=*/ true);
  }

  /// Combines operands with logical OR. Same cardinality semantics as
  /// [#and(SQLBooleanExpression...)].
  public SQLBooleanExpression or(SQLBooleanExpression... ops) {
    return combine(ops, /*isAnd=*/ false);
  }

  /// AND-merges optional operands. Mirrors [#and] but tolerates `null` entries
  /// and an empty input — useful at recogniser merge sites where some
  /// contributing filters may be absent (e.g. polymorphism class-narrowing only
  /// applies in non-polymorphic mode; a multi-RID filter only applies when more
  /// than one ID was supplied).
  ///
  /// Three behaviours by effective cardinality:
  /// - 0 effective operands (all-null or empty input) → returns `null`. Callers
  ///   typically interpret this as "nothing to write".
  /// - 1 effective operand → returned as-is, matching [#and]'s parser-parity rule.
  /// - 2+ effective operands → produces a single [SQLAndBlock] with all operands
  ///   as sub-blocks.
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
      return kept.get(0);
    }
    var block = new SQLAndBlock(-1);
    block.setSubBlocks(new ArrayList<>(kept));
    return block;
  }

  /// Builds `field IS NULL` for a property reference (parsed shape:
  /// [SQLIdentifier]). The runtime evaluator uses `expression.execute() == null`, so
  /// document stores conflate "property absent" and "property set to literal null".
  /// Used by the `eq(null)` / `neq(null)` predicate adapters (Track 4). Gremlin
  /// {@code hasNot(key)} maps to [#isNotDefined(String)], not here — {@code IS NULL}
  /// would over-match vertices that store the key with a null value.
  public SQLBooleanExpression isNull(String field) {
    return isNull(fieldExpression(field));
  }

  /// Builds `expression IS NULL` for any [SQLExpression] left-side — accepts
  /// both [SQLIdentifier]-wrapped property references and
  /// [SQLRecordAttribute]-wrapped record attributes (e.g. `@class IS NULL`).
  public SQLBooleanExpression isNull(SQLExpression expression) {
    var condition = new SQLIsNullCondition(-1);
    condition.setExpression(expression);
    return condition;
  }

  /// Builds `attribute IS NULL` for a record-attribute left-side (e.g.
  /// `@class IS NULL`). Convenience wrapper that constructs the
  /// [SQLRecordAttribute] / [SQLExpression] chain so callers don't have to.
  public SQLBooleanExpression isNullAttribute(String attributeName) {
    var attr = new SQLRecordAttribute(-1);
    attr.setName(attributeName);
    return isNull(new SQLExpression(attr, null));
  }

  /// Builds `field IS DEFINED` — entity-presence predicate distinct from
  /// `IS NULL`. Returns true when the record carries `field` at the storage
  /// layer, regardless of value (including literal `null`); the AST node's
  /// evaluator routes through `SQLExpression.isDefinedFor`. Used by the
  /// Gremlin translator's `has(key)` filter mapping where TinkerPop's
  /// `Property.isPresent()` semantics require the entity-layer view, not
  /// the value-layer view that `IS NOT NULL` provides.
  public SQLBooleanExpression isDefined(String field) {
    var condition = new SQLIsDefinedCondition(-1);
    condition.setExpression(fieldExpression(field));
    return condition;
  }

  /// Builds `field IS NOT DEFINED` — symmetric to [#isDefined(String)].
  /// Used by the Gremlin translator's `hasNot(key)` filter mapping. NOT
  /// equivalent to `IS NULL`: a property stored with literal `null` value
  /// returns false from `IS NOT DEFINED` (the property exists, just with a
  /// null value) but true from `IS NULL`, mirroring TinkerPop's
  /// `Property.isPresent()` returning true for null-valued YTDB properties.
  public SQLBooleanExpression isNotDefined(String field) {
    var condition = new SQLIsNotDefinedCondition(-1);
    condition.setExpression(fieldExpression(field));
    return condition;
  }

  /// Builds `NOT (sub)`. The returned [SQLNotBlock] has both `sub` set and
  /// `negate=true`; the latter is essential because [SQLNotBlock]'s default of
  /// `false` would silently produce a pass-through that evaluates the inner
  /// block with no negation.
  public SQLBooleanExpression not(SQLBooleanExpression sub) {
    var block = new SQLNotBlock(-1);
    block.setSub(sub);
    block.setNegate(true);
    return block;
  }

  /// Wraps a boolean expression in an [SQLWhereClause] so it can be plugged
  /// into the unified MATCH IR's `aliasFilters` map directly.
  public SQLWhereClause wrap(SQLBooleanExpression expr) {
    var clause = new SQLWhereClause(-1);
    clause.setBaseExpression(expr);
    return clause;
  }

  // ── Internal helpers ──

  /// Computes `p⁺`: the smallest string strictly greater than every string that
  /// starts with `prefix`, used as the exclusive upper bound of the half-open
  /// prefix range `[prefix, prefix⁺)`. The last *code point* of `prefix` is
  /// incremented by one. Surrogate pairs are handled by reading the trailing code
  /// point with [String#codePointBefore] (which spans the full pair) and replacing
  /// it via [String#appendCodePoint].
  ///
  /// Overflow carry: if the trailing code point is the maximum
  /// ([Character#MAX_CODE_POINT], `U+10FFFF`), it cannot be incremented in place.
  /// The method drops that code point and increments the preceding one instead —
  /// `"a􏿿"` (an `a` followed by the max code point) becomes `"b"`. If
  /// every code point is the maximum (e.g. a lone `U+10FFFF`), no finite upper
  /// bound exists, so the method throws [IllegalArgumentException] and the caller
  /// declines. This is a pathological input that cannot occur from a realistic
  /// prefix string.
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

  /// Builds an [SQLExpression] referencing `name` as a base identifier — the
  /// shape the parser produces for a bare property reference inside a `WHERE`.
  private static SQLExpression fieldExpression(String name) {
    return new SQLExpression(new SQLIdentifier(name));
  }

  private static SQLExpression stringExpression(String value) {
    var expr = new SQLExpression(-1);
    expr.setMathExpression(new SQLBaseExpression(value));
    return expr;
  }

  /// Wraps `values` in the AST chain
  /// `SQLBaseExpression → SQLBaseIdentifier → SQLLevelZeroIdentifier → SQLCollection`,
  /// matching the parser's representation of an inline literal list (e.g.
  /// `WHERE x IN [1, 2, 3]`). [SQLInCondition.evaluateRight] resolves this via
  /// `rightMathExpression.execute(...)`, which recurses into the collection's
  /// element expressions.
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
