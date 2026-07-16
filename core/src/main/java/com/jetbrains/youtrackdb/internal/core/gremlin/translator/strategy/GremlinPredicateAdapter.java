package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeqOperator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;

/**
 * Translates a TinkerPop {@link HasContainer} (one {@code has(key, predicate)} clause) into a MATCH
 * {@code WHERE} {@link SQLBooleanExpression}, or declines by returning {@code null}.
 *
 * <h2>Chokepoint role</h2>
 *
 * This is the seam every {@code has(...)}-bearing recogniser routes through, so predicate coverage
 * lives in one reviewable place. It maps the whole Phase-1 predicate surface:
 *
 * <ul>
 *   <li>the six scalar {@link Compare} comparisons ({@code eq} / {@code neq} / {@code lt} / {@code
 *       lte} / {@code gt} / {@code gte}) over a rendered literal;
 *   <li>{@link Contains} membership ({@code within} → {@code IN}, {@code without} → {@code NOT IN});
 *   <li>the {@link Text} / {@code TextP} string predicates ({@code containing} / {@code
 *       startingWith} / {@code endingWith} / {@code regex} and their {@code not*} forms) via the
 *       string-predicate AST nodes ({@code SQLContainsTextCondition} collate transform, {@code
 *       SQLEndsWithCondition}, find-mode {@code SQLMatchesCondition});
 *   <li>the connectives {@code P.and} / {@code P.or} ({@link AndP} / {@link OrP}) and negation
 *       {@code P.not} ({@link NotP}), recursing into each child. {@code between} / {@code inside} /
 *       {@code outside} need no special case: TinkerPop already decomposes them into an {@code
 *       AndP} / {@code OrP} of scalar comparisons — {@code between(lo, hi)} arrives as {@code
 *       AndP[gte lo, lt hi]}, the right-exclusive {@code [lo, hi)} range, which is why this adapter
 *       never emits an {@code SQLBetweenCondition} (that node is the closed {@code [lo, hi]} range).
 * </ul>
 *
 * <h2>Absent-property guard</h2>
 *
 * Native {@code HasContainer.test} excludes an element that lacks the key: it iterates {@code
 * element.properties(key)}, gets an empty iterator, and returns false without ever consulting the
 * predicate. A translated WHERE clause must reproduce that exclusion. Most YTDB operators already
 * agree — {@code =} / {@code <} / {@code <=} / {@code >} / {@code >=} / {@code IN} / {@code
 * CONTAINSTEXT} all evaluate false on an absent (null) operand — but every predicate whose SQL
 * evaluates <em>true</em> on an absent property needs an explicit {@code key IS DEFINED} guard.
 * That covers {@code neq} ({@code <>} negates equality, which is false on null, so {@code <>} is
 * true on absent) and every negated form ({@code without}, the {@code not*} text predicates, and
 * any {@link NotP}): {@code NOT(false-on-absent)} is true on absent. {@link #guarded} wraps those
 * translations in {@code key IS DEFINED AND (…)}.
 *
 * <h2>NULL comparands</h2>
 *
 * {@code eq(null)} rewrites to {@code key IS DEFINED AND key IS NULL}, not a bare {@code IS NULL}:
 * YTDB {@code IS NULL} conflates absent and literal-null, so a bare {@code IS NULL} would match an
 * element that lacks the key, which native excludes. {@code neq(null)} rewrites to {@code NOT(key IS
 * NULL)} ({@code IS NOT NULL}), which is false on absent and needs no guard. A null comparand on the
 * four range comparisons has no defined membership meaning and declines.
 *
 * <h2>Decline (return {@code null}) — never throw</h2>
 *
 * A recogniser turns a {@code null} here into a whole-traversal decline, so this adapter never
 * throws on an unrecognised predicate. It declines when:
 *
 * <ul>
 *   <li>the key is null or blank, or lands in a reserved namespace (the {@code ~} hidden-key space
 *       from {@code hasLabel} / {@code hasId}, the translator's minted-alias {@code $} space, or
 *       YouTrackDB's record-attribute {@code @} space) — such a key would reach a WHERE identifier
 *       the executor resolves as a reserved token, a context variable, or record metadata rather
 *       than a plain property. {@code ~label} / {@code ~id} narrowing is the recogniser's job before
 *       the adapter runs;
 *   <li>the predicate's bi-predicate is a custom {@code BiPredicate} (not {@link Compare} / {@link
 *       Contains} / {@link Text} / {@link Text.RegexPredicate}) — the translator cannot reproduce
 *       arbitrary user logic;
 *   <li>the comparand is a size-1 collection under {@code eq} / {@code neq}: {@code
 *       QueryOperatorEquals} auto-unboxes a singleton against a scalar, and field cardinality is
 *       unknown at translation time, so the two pipelines could disagree. Size 0 and size ≥2
 *       collections translate normally;
 *   <li>a {@code startingWith} prefix is empty (its exclusive upper bound is undefined), a {@code
 *       within} / {@code without} member or a scalar comparand is null, or the comparand is a type
 *       {@link MatchLiteralBuilder} cannot render (e.g. a deferred {@code GValue} parameter).
 * </ul>
 */
final class GremlinPredicateAdapter {

  /** Singleton — the adapter is stateless and cheap to share across recogniser calls. */
  static final GremlinPredicateAdapter INSTANCE = new GremlinPredicateAdapter();

  /** Stateless builder for the WHERE AST; construction is trivial so a shared instance is fine. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private GremlinPredicateAdapter() {
    // Singleton — instantiate via INSTANCE.
  }

  /**
   * Translates one {@link HasContainer} into a {@code WHERE} boolean expression, or returns {@code
   * null} to decline (see the class Javadoc for the decline cases). Never throws.
   */
  @Nullable SQLBooleanExpression toFilter(HasContainer container) {
    if (container == null) {
      return null;
    }
    var key = container.getKey();
    if (key == null || key.isBlank() || WalkerContext.isReservedHasKey(key)) {
      // Blank or reserved-namespace keys are out of scope. A reserved key (the minted-alias $
      // space, the ~label/~id hidden-key space, or the @-record-attribute space; see
      // WalkerContext.isReservedHasKey) would reach a WHERE identifier the executor resolves as a
      // context variable, a reserved token, or record metadata rather than a plain property, so it
      // declines to keep native behaviour. ~label/~id narrowing is the recogniser's job.
      return null;
    }
    var predicate = container.getPredicate();
    if (predicate == null) {
      return null;
    }
    return translate(key, predicate);
  }

  /**
   * Translates one predicate against {@code key}. Dispatches the connectives ({@link NotP} / {@link
   * AndP} / {@link OrP}) before inspecting the bi-predicate — a connective's own bi-predicate is not
   * one of the leaf types, so checking it first would misroute the whole predicate to a decline.
   * Returns {@code null} to decline (propagated to a whole-traversal decline by the caller).
   */
  private @Nullable SQLBooleanExpression translate(String key, P<?> predicate) {
    if (predicate instanceof NotP<?> notP) {
      // NotP has no public getter for its wrapped predicate, but negate() returns it (a NotP is
      // built by P.negate(), and negating it back yields the original). Translate the inner
      // predicate positively, negate the SQL, and guard for absent: native NotP excludes an absent
      // property (HasContainer.test's empty iterator is false whatever the inner predicate), so
      // without IS DEFINED the NOT of a false-on-absent inner would wrongly include absent rows.
      var inner = translate(key, notP.negate());
      if (inner == null) {
        return null;
      }
      return guarded(key, WHERE.not(inner));
    }
    if (predicate instanceof AndP<?> andP) {
      return combine(key, andP.getPredicates(), /* and= */ true);
    }
    if (predicate instanceof OrP<?> orP) {
      return combine(key, orP.getPredicates(), /* and= */ false);
    }
    // Leaf predicate — dispatch on the concrete bi-predicate type.
    var biPredicate = predicate.getBiPredicate();
    var value = predicate.getValue();
    if (biPredicate instanceof Compare compare) {
      return translateCompare(key, compare, value);
    }
    if (biPredicate instanceof Contains contains) {
      return translateContains(key, contains, value);
    }
    if (biPredicate instanceof Text text) {
      return translateText(key, text, value);
    }
    if (biPredicate instanceof Text.RegexPredicate regex) {
      // Text.regex / Text.notRegex do not use a Text enum constant; their bi-predicate is a
      // RegexPredicate carrying the pattern and a negate flag.
      return translateRegex(key, regex);
    }
    // Custom BiPredicate (a user lambda or a predicate type the translator does not model) —
    // decline rather than guess at its semantics.
    return null;
  }

  /**
   * Translates the children of an {@link AndP} / {@link OrP}, combining them with {@code AND} /
   * {@code OR}. Any child that declines fails the whole connective (all-or-nothing). Each child
   * carries its own absent-property guard where needed, so the combined block reproduces native
   * membership without a connective-level guard.
   */
  private @Nullable SQLBooleanExpression combine(String key, List<? extends P<?>> children,
      boolean and) {
    if (children == null || children.isEmpty()) {
      // A connective with no children is degenerate; decline rather than emit an empty block.
      return null;
    }
    var translated = new ArrayList<SQLBooleanExpression>(children.size());
    for (var child : children) {
      var expr = translate(key, child);
      if (expr == null) {
        return null;
      }
      translated.add(expr);
    }
    var operands = translated.toArray(new SQLBooleanExpression[0]);
    return and ? WHERE.and(operands) : WHERE.or(operands);
  }

  /**
   * Translates a scalar {@link Compare}. Handles the {@code eq(null)} / {@code neq(null)} rewrites
   *, the singleton-collection decline, and the {@code neq} absent-property guard.
   */
  private @Nullable SQLBooleanExpression translateCompare(String key, Compare compare,
      @Nullable Object value) {
    if (value == null) {
      // Only eq/neq have a defined absent-safe null rewrite; a range comparison against null has no
      // membership meaning and declines.
      return switch (compare) {
        case eq -> guarded(key, WHERE.isNull(key)); // key IS DEFINED AND key IS NULL
        case neq -> WHERE.not(WHERE.isNull(key)); // NOT(key IS NULL) = key IS NOT NULL (false on absent)
        default -> null;
      };
    }
    // Singleton-collection equality declines: QueryOperatorEquals auto-unboxes a size-1
    // collection against a scalar, and field cardinality is unknown at translation time, so the
    // translated and native pipelines could disagree. Size 0 and ≥2 fall through and translate.
    if ((compare == Compare.eq || compare == Compare.neq)
        && value instanceof Collection<?> collection && collection.size() == 1) {
      return null;
    }
    SQLExpression literal;
    try {
      literal = MatchLiteralBuilder.toLiteral(value);
    } catch (IllegalArgumentException unsupportedType) {
      return null;
    }
    var comparison = WHERE.op(key, toOperator(compare), literal);
    // neq (<>) is true on an absent property (SQLNeqOperator negates QueryOperatorEquals.equals,
    // which is false on a null operand → true), so guard it. The other five comparisons are false
    // on absent already and need no guard.
    return compare == Compare.neq ? guarded(key, comparison) : comparison;
  }

  /**
   * Translates {@link Contains} membership. {@code within} → {@code key IN [..]}; {@code without} →
   * {@code key IS DEFINED AND NOT(key IN [..])} — {@code NOT IN} is true on an absent property, so
   * it takes the absent-property guard.
   */
  private @Nullable SQLBooleanExpression translateContains(String key, Contains contains,
      @Nullable Object value) {
    if (!(value instanceof Collection<?> elements)) {
      return null;
    }
    var literals = new ArrayList<SQLExpression>(elements.size());
    for (var element : elements) {
      if (element == null) {
        // A null member is not renderable as a literal (toLiteral rejects null); decline whole.
        return null;
      }
      try {
        literals.add(MatchLiteralBuilder.toLiteral(element));
      } catch (IllegalArgumentException unsupportedType) {
        return null;
      }
    }
    return switch (contains) {
      case within -> WHERE.in(key, literals);
      case without -> guarded(key, WHERE.notIn(key, literals));
    };
  }

  /**
   * Translates the {@link Text} string predicates onto the string-predicate AST nodes. The {@code not*} forms
   * are the negation of their positive counterpart and are true on an absent property, so they take
   * the absent-property guard. {@code startingWith} / {@code notStartingWith} decline an empty
   * prefix (its exclusive upper bound is undefined — {@link MatchWhereBuilder#startsWith} throws).
   */
  private @Nullable SQLBooleanExpression translateText(String key, Text text,
      @Nullable Object value) {
    if (!(value instanceof String string)) {
      return null;
    }
    return switch (text) {
      case containing -> WHERE.containsText(key, string);
      case notContaining -> guarded(key, WHERE.not(WHERE.containsText(key, string)));
      case startingWith -> string.isEmpty() ? null : WHERE.startsWith(key, string);
      case notStartingWith ->
          string.isEmpty() ? null : guarded(key, WHERE.not(WHERE.startsWith(key, string)));
      case endingWith -> WHERE.endsWith(key, string);
      case notEndingWith -> guarded(key, WHERE.not(WHERE.endsWith(key, string)));
    };
  }

  /**
   * Translates a regex {@link Text.RegexPredicate} onto a find-mode {@code SQLMatchesCondition}.
   * {@code notRegex} (the negate flag) is the negation of the positive match and is true on an
   * absent property, so it takes the absent-property guard. Regex stays case-sensitive
   * regardless of collation (collate-transforming a pattern would change its meaning).
   */
  private @Nullable SQLBooleanExpression translateRegex(String key, Text.RegexPredicate regex) {
    var pattern = regex.getPattern();
    if (pattern == null) {
      return null;
    }
    var matches = WHERE.matchesRegex(key, pattern);
    return regex.isNegate() ? guarded(key, WHERE.not(matches)) : matches;
  }

  /**
   * Wraps {@code expr} in {@code key IS DEFINED AND (expr)} — the absent-property guard. Used
   * for every translation whose SQL evaluates true on an absent property (the negated forms), so
   * the translated WHERE reproduces native's exclusion of elements that lack the key.
   */
  private static SQLBooleanExpression guarded(String key, SQLBooleanExpression expr) {
    return WHERE.and(WHERE.isDefined(key), expr);
  }

  /**
   * Maps a TinkerPop {@link Compare} onto the matching SQL comparison operator. The {@code switch}
   * is exhaustive over the six scalar comparisons; a bi-predicate that is not one of them was
   * already ruled out by the {@code instanceof Compare} gate in {@link #translate}.
   */
  private static @Nonnull SQLBinaryCompareOperator toOperator(Compare compare) {
    return switch (compare) {
      case eq -> SQLEqualsOperator.INSTANCE;
      case neq -> SQLNeqOperator.INSTANCE;
      case lt -> SQLLtOperator.INSTANCE;
      case lte -> SQLLeOperator.INSTANCE;
      case gt -> SQLGtOperator.INSTANCE;
      case gte -> SQLGeOperator.INSTANCE;
    };
  }
}
