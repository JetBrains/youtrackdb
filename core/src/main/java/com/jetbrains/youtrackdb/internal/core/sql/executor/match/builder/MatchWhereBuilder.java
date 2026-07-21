package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBetweenCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCollection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsTextCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEndsWithCondition;
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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchesCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStartsWithCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

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
   * Builds {@code @class = 'className'} — the exact-class narrowing predicate a Gremlin recogniser
   * attaches when a step names an explicit schema class (the folded {@code hasLabel(L)}). {@code
   * @class} reads the record's own leaf class, so this selects exactly the named class, unlike the
   * polymorphic MATCH {@code class:} node type. The left side is an {@link SQLRecordAttribute} (like
   * {@code @rid}, or {@code @class IS NULL} via {@link #isNullAttribute}), which the runtime
   * evaluator dispatches through a different path than a plain property identifier.
   *
   * <p>Throws {@link IllegalArgumentException} on a null or blank name: a caller only reaches this
   * with a concrete user-named class, so a blank name is a caller bug — failing loud beats emitting
   * a silently-wrong {@code @class = ''} predicate. Narrowing is correct only for an explicit user
   * class; a bare chain hop ({@code out(L)}) and the start step must NOT narrow — they root at the
   * generic {@code V} polymorphically — so the translator calls this only from its explicit-class
   * path.
   */
  public SQLBooleanExpression classEquals(String className) {
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("class name for @class narrowing must be non-blank");
    }
    var classAttr = new SQLRecordAttribute(-1);
    classAttr.setName("@class");
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(new SQLExpression(classAttr, null));
    condition.setOperator(SQLEqualsOperator.INSTANCE);
    condition.setRight(stringExpression(className));
    return condition;
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
   * Strict variant of {@link #containsText(String, String)} for the Gremlin adapter. When {@code
   * strict} is {@code true} the built node throws on a present non-String left operand instead of
   * yielding {@code false}, matching native TinkerPop {@code Text.containing} (String-only)
   * semantics. GQL keeps using the two-arg lenient method; only the Gremlin adapter passes {@code
   * strict = true}.
   */
  public SQLBooleanExpression containsText(String field, String substring, boolean strict) {
    var condition = (SQLContainsTextCondition) containsText(field, substring);
    condition.setStrict(strict);
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
   * Builds a suffix match {@code field ENDSWITH suffix} via the hand-written {@link
   * SQLEndsWithCondition} node. Unlike {@link #startsWith}, a suffix match has no index range
   * representation, so it is always a full scan ({@code isIndexAware() == false}); it does honor the
   * property collation, so a {@code ci} property matches case-insensitively.
   */
  public SQLBooleanExpression endsWith(String field, String suffix) {
    var condition = new SQLEndsWithCondition(-1);
    condition.setLeft(fieldExpression(field));
    condition.setRight(stringExpression(suffix));
    return condition;
  }

  /**
   * Strict variant of {@link #endsWith(String, String)} for the Gremlin adapter. When {@code strict}
   * is {@code true} the built node throws on a present non-String left operand instead of yielding
   * {@code false}, matching native TinkerPop {@code Text.endingWith} (String-only) semantics. GQL
   * keeps using the two-arg lenient method; only the Gremlin adapter passes {@code strict = true}.
   */
  public SQLBooleanExpression endsWith(String field, String suffix, boolean strict) {
    var condition = (SQLEndsWithCondition) endsWith(field, suffix);
    condition.setStrict(strict);
    return condition;
  }

  /**
   * Builds a strict prefix match {@code field STARTSWITH prefix} via the hand-written full-scan
   * {@link SQLStartsWithCondition} node, for the Gremlin adapter's {@code Text.startingWith}
   * predicate. It throws on a present non-String left operand (native String-only parity) instead of
   * yielding {@code false}.
   *
   * <p>This is the full-scan counterpart to {@link #startsWith(String, String)}: the latter builds
   * an index-aware half-open range for the declared-String path, but a range cannot throw on a
   * non-String operand, so the strict Gremlin path needs a dedicated node. It honors the property
   * collation, so a {@code ci} property matches case-insensitively.
   */
  public SQLBooleanExpression startsWithStrict(String field, String prefix) {
    var condition = new SQLStartsWithCondition(-1);
    condition.setLeft(fieldExpression(field));
    condition.setRight(stringExpression(prefix));
    condition.setStrict(true);
    return condition;
  }

  /**
   * Builds a regex match {@code field MATCHES pattern} in find mode — an unanchored match anywhere
   * in the value, which is Gremlin {@code Text.regex} semantics. Sets {@link
   * SQLMatchesCondition#setFindMode} so the evaluator uses {@link java.util.regex.Matcher#find()}
   * rather than {@link java.util.regex.Matcher#matches()}, and supplies the pattern as an expression
   * (not the quoted-literal {@code right} field the parser fills) so no quote stripping applies.
   * Regex stays case-sensitive regardless of the property collation: collate-transforming a pattern
   * would change its meaning.
   */
  public SQLBooleanExpression matchesRegex(String field, String pattern) {
    var condition = new SQLMatchesCondition(-1);
    condition.setExpression(fieldExpression(field));
    condition.setRightExpression(stringExpression(pattern));
    condition.setFindMode(true);
    return condition;
  }

  /**
   * Strict variant of {@link #matchesRegex(String, String)} for the Gremlin adapter. When {@code
   * strict} is {@code true} the built node throws on a present non-String value instead of yielding
   * {@code false}, matching native TinkerPop {@code Text.regex} (String-only) semantics; a
   * present-null value still yields {@code false} (native regex NPEs there — an accepted non-goal).
   * GQL keeps using the two-arg lenient method; only the Gremlin adapter passes {@code strict =
   * true}.
   */
  public SQLBooleanExpression matchesRegex(String field, String pattern, boolean strict) {
    var condition = (SQLMatchesCondition) matchesRegex(field, pattern);
    condition.setStrict(strict);
    return condition;
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

  /**
   * Builds {@code @rid} as a record-attribute expression for the alias filter's current node — the
   * shape the parser emits for {@code WHERE (@rid = ...)} on a pattern alias.
   */
  public SQLExpression boundaryRidExpression() {
    var ridAttr = new SQLRecordAttribute(-1);
    ridAttr.setName("@rid");
    return new SQLExpression(ridAttr, null);
  }

  /**
   * Builds {@code $matched.<alias>.<segments...>} — the cross-alias accessor MATCH uses for
   * {@code where(P.eq("label"))} label references. Each segment is either a property name or a
   * record attribute such as {@code @rid}.
   */
  public SQLExpression matchedAccess(String alias, String... segments) {
    if (alias == null || alias.isBlank() || segments == null || segments.length == 0) {
      throw new IllegalArgumentException("matched access requires a non-blank alias and segments");
    }
    var path = new StringBuilder("$matched.").append(alias);
    for (var segment : segments) {
      path.append('.').append(segment);
    }
    return parseMatchedRhsExpression(path.toString());
  }

  /**
   * Parses a {@code $matched} accessor via the SQL parser so the emitted AST matches hand-written
   * MATCH {@code WHERE} text exactly.
   */
  private static SQLExpression parseMatchedRhsExpression(String matchedPath) {
    try {
      var sql = "SELECT FROM V WHERE @rid = " + matchedPath;
      var parser =
          new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
      var stmt = (SQLSelectStatement) parser.parse();
      var where = stmt.getWhereClause();
      if (where == null || where.getBaseExpression() == null) {
        throw new IllegalArgumentException("failed to parse matched access: " + matchedPath);
      }
      var bin = unwrapBinaryCondition(where.getBaseExpression());
      if (bin == null) {
        throw new IllegalArgumentException("failed to parse matched access: " + matchedPath);
      }
      return bin.getRight();
    } catch (ParseException e) {
      throw new IllegalArgumentException("failed to parse matched access: " + matchedPath, e);
    }
  }

  /**
   * The SQL parser wraps a lone {@code WHERE} predicate in a single-element {@link SQLOrBlock}; peel
   * that (and any parenthesis / AND wrapper) to reach the underlying {@link SQLBinaryCondition}.
   */
  private static @Nullable SQLBinaryCondition unwrapBinaryCondition(SQLBooleanExpression base) {
    if (base instanceof SQLBinaryCondition bin) {
      return bin;
    }
    if (base instanceof SQLOrBlock or) {
      var subs = or.getSubBlocks();
      if (subs == null || subs.isEmpty()) {
        return null;
      }
      // Parser may emit a single OR block or flatten multiple; take the first binary leaf.
      for (var sub : subs) {
        var bin = unwrapBinaryCondition(sub);
        if (bin != null) {
          return bin;
        }
      }
      return null;
    }
    if (base instanceof SQLAndBlock and) {
      var subs = and.getSubBlocks();
      if (subs == null || subs.isEmpty()) {
        return null;
      }
      for (var sub : subs) {
        var bin = unwrapBinaryCondition(sub);
        if (bin != null) {
          return bin;
        }
      }
      return null;
    }
    if (base instanceof SQLNotBlock not && !not.isNegate() && not.getSub() != null) {
      return unwrapBinaryCondition(not.getSub());
    }
    return null;
  }

  /**
   * Builds a binary comparison between two arbitrary {@link SQLExpression} operands — used when the
   * right-hand side is a {@code $matched} reference rather than an inline literal.
   */
  public SQLBooleanExpression compareExpressions(
      SQLExpression left, SQLBinaryCompareOperator operator, SQLExpression right) {
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(operator);
    condition.setRight(right);
    return condition;
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
  public static SQLBaseExpression literalCollectionExpression(List<SQLExpression> values) {
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
