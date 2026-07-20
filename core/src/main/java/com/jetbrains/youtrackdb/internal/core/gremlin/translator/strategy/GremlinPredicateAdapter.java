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
 *       SQLEndsWithCondition}, {@code SQLStartsWithCondition}, find-mode {@code SQLMatchesCondition}).
 *       These translate in <em>strict</em> mode so a present non-String operand throws at execution
 *       exactly as native {@code Text} (String-only) does, rather than diverging by returning rows;
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
 * {@code eq(null)} rewrites to a bare {@code key IS NULL}. YTDB {@code IS NULL} is true for both
 * an absent property and a present-null value, which matches native Gremlin membership (pinned in
 * {@code PredicateTraversalEquivalenceTest.nullComparand_nativeMembership_pinnedBeforeEquivalence}).
 * {@code neq(null)} rewrites to {@code NOT(key IS NULL)} ({@code IS NOT NULL}), which is false on
 * absent and present-null and needs no guard. A null comparand on the four range comparisons has no
 * defined membership meaning and declines.
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
 *   <li>a {@code within} / {@code without} member or a scalar comparand is null, or the comparand
 *       is a type {@link MatchLiteralBuilder} cannot render (e.g. a deferred {@code GValue}
 *       parameter).
 * </ul>
 *
 * <p>A {@link Text} / regex string predicate on a non-String property no longer declines: it
 * translates in strict mode and throws at execution just as native {@code Text} does. The {@link
 * PropertyTypeGate} is now only a routing hint for {@code startingWith} — a declared-String
 * property uses the index-aware prefix range, everything else uses the strict full-scan {@code
 * STARTSWITH} node — never a decline.
 */
final class GremlinPredicateAdapter {

  /** Singleton — the adapter is stateless and cheap to share across recogniser calls. */
  static final GremlinPredicateAdapter INSTANCE = new GremlinPredicateAdapter();

  /** Stateless builder for the WHERE AST; construction is trivial so a shared instance is fine. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  /**
   * Decides whether a property key is a declared {@code STRING} schema type, which selects the
   * {@code startingWith} translation form: a declared String uses the index-aware prefix range,
   * everything else uses the strict full-scan {@code STARTSWITH} node (see the class Javadoc). A
   * recogniser builds this from the element's class and the resolved schema — the adapter itself
   * has no schema input, so the gate is passed in per call. Callers with no schema context (unit
   * tests, a generic {@code V} boundary whose leaf class is unknown) pass {@link #NO_TYPE_INFO},
   * which reports every key as not-a-declared-String, so {@code startingWith} routes to strict.
   */
  @FunctionalInterface
  interface PropertyTypeGate {
    boolean isDeclaredString(String key);
  }

  /** Type gate for callers with no schema context: reports no key as a declared String, so a
   *  {@code startingWith} routes to the strict full-scan form (the value type is unknown). */
  static final PropertyTypeGate NO_TYPE_INFO = key -> false;

  private GremlinPredicateAdapter() {
    // Singleton — instantiate via INSTANCE.
  }

  /**
   * Translates one {@link HasContainer} into a {@code WHERE} boolean expression with no schema
   * context. A {@code startingWith} then routes to the strict full-scan form. Prefer {@link
   * #toFilter(HasContainer, PropertyTypeGate)} from a recogniser that can resolve the element's
   * class, so a {@code startingWith} on a declared-String property uses the index-aware prefix
   * range.
   */
  @Nullable SQLBooleanExpression toFilter(HasContainer container) {
    return toFilter(container, NO_TYPE_INFO);
  }

  /**
   * Translates one {@link HasContainer} into a {@code WHERE} boolean expression, or returns {@code
   * null} to decline (see the class Javadoc for the decline cases). Never throws. The {@code
   * typeGate} routes {@code startingWith} between the index-aware prefix range (declared String)
   * and the strict full-scan {@code STARTSWITH} node (everything else).
   */
  @Nullable SQLBooleanExpression toFilter(HasContainer container, PropertyTypeGate typeGate) {
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
    return translate(key, predicate, typeGate);
  }

  /**
   * Translates one predicate against {@code key}. Dispatches the connectives ({@link NotP} / {@link
   * AndP} / {@link OrP}) before inspecting the bi-predicate — a connective's own bi-predicate is not
   * one of the leaf types, so checking it first would misroute the whole predicate to a decline.
   * Returns {@code null} to decline (propagated to a whole-traversal decline by the caller).
   */
  private @Nullable SQLBooleanExpression translate(String key, P<?> predicate,
      PropertyTypeGate typeGate) {
    if (predicate instanceof NotP<?> notP) {
      // NotP has no public getter for its wrapped predicate, but negate() returns it (a NotP is
      // built by P.negate(), and negating it back yields the original). Translate the inner
      // predicate positively, negate the SQL, and guard for absent: native NotP excludes an absent
      // property (HasContainer.test's empty iterator is false whatever the inner predicate), so
      // without IS DEFINED the NOT of a false-on-absent inner would wrongly include absent rows.
      var inner = translate(key, notP.negate(), typeGate);
      if (inner == null) {
        return null;
      }
      return guarded(key, WHERE.not(inner));
    }
    if (predicate instanceof AndP<?> andP) {
      return combine(key, andP.getPredicates(), /* and= */ true, typeGate);
    }
    if (predicate instanceof OrP<?> orP) {
      return combine(key, orP.getPredicates(), /* and= */ false, typeGate);
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
      return translateText(key, text, value, typeGate);
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
      boolean and, PropertyTypeGate typeGate) {
    if (children == null || children.isEmpty()) {
      // A connective with no children is degenerate; decline rather than emit an empty block.
      return null;
    }
    var translated = new ArrayList<SQLBooleanExpression>(children.size());
    for (var child : children) {
      var expr = translate(key, child, typeGate);
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
        case eq -> WHERE.isNull(key); // bare IS NULL — absent + present-null (native parity)
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
   * Translates the {@link Text} string predicates onto the string-predicate AST nodes, in strict
   * mode so a present non-String operand throws at execution exactly as native {@code Text} does
   * (String-only) rather than silently returning rows. The {@code not*} forms are the negation of
   * their positive counterpart and are true on an absent property, so they take the absent-property
   * guard. {@code startingWith} / {@code notStartingWith} route through {@link #startsWithFilter},
   * which picks the index-aware prefix range for a declared-String property and the strict
   * full-scan {@code STARTSWITH} node otherwise; neither declines, so no pathological prefix falls
   * back to native.
   */
  private @Nullable SQLBooleanExpression translateText(String key, Text text,
      @Nullable Object value, PropertyTypeGate typeGate) {
    if (!(value instanceof String string)) {
      // The predicate's comparand (the search string) is not a String — not a translatable Text
      // predicate. This is the argument, not the property value, so it is a decline, not a throw.
      return null;
    }
    return switch (text) {
      case containing -> WHERE.containsText(key, string, true);
      case notContaining -> guarded(key, WHERE.not(WHERE.containsText(key, string, true)));
      case startingWith -> startsWithFilter(key, string, typeGate);
      case notStartingWith -> guarded(key, WHERE.not(startsWithFilter(key, string, typeGate)));
      case endingWith -> WHERE.endsWith(key, string, true);
      case notEndingWith -> guarded(key, WHERE.not(WHERE.endsWith(key, string, true)));
    };
  }

  /**
   * Chooses the {@code startingWith} translation form. A declared-String property can only hold
   * String values, so it uses the index-aware half-open prefix range ({@link
   * MatchWhereBuilder#startsWith}, a B-tree prefix scan) when a finite range exists. Every other
   * case — an unknown / undeclared type, a declared non-String type, or a declared String whose
   * prefix has no finite range (empty or all-max-code-point) — uses the strict full-scan {@code
   * STARTSWITH} node ({@link MatchWhereBuilder#startsWithStrict}), which throws on a present
   * non-String value like native and matches on a String. An empty prefix under the strict node is
   * {@code startsWith("")}, which matches every present value — native {@code startingWith("")}
   * parity — so nothing declines.
   */
  private SQLBooleanExpression startsWithFilter(String key, String prefix,
      PropertyTypeGate typeGate) {
    if (typeGate.isDeclaredString(key)) {
      var range = startsWithRange(key, prefix);
      if (range != null) {
        return range;
      }
    }
    return WHERE.startsWithStrict(key, prefix);
  }

  /**
   * Builds the half-open prefix range for a {@code startingWith} prefix, or returns {@code null}
   * when no finite range exists so {@link #startsWithFilter} falls back to the strict full-scan
   * node. Two prefixes have no buildable range:
   *
   * <ul>
   *   <li>an empty prefix — its exclusive upper bound is undefined;
   *   <li>a prefix whose code points are all {@link Character#MAX_CODE_POINT} — it has no finite
   *       exclusive upper bound, so {@link MatchWhereBuilder#startsWith} throws {@link
   *       IllegalArgumentException}.
   * </ul>
   *
   * <p>The empty case is guarded before the call; the max-code-point case is caught, mirroring the
   * {@code toLiteral} exception handling in {@link #translateCompare} / {@link #translateContains}.
   */
  private @Nullable SQLBooleanExpression startsWithRange(String key, String prefix) {
    if (prefix.isEmpty()) {
      return null;
    }
    try {
      return WHERE.startsWith(key, prefix);
    } catch (IllegalArgumentException noFiniteUpperBound) {
      return null;
    }
  }

  /**
   * Translates a regex {@link Text.RegexPredicate} onto a find-mode {@code SQLMatchesCondition} in
   * strict mode, so a present non-String value throws at execution as native regex does rather than
   * returning rows. {@code notRegex} (the negate flag) is the negation of the positive match and is
   * true on an absent property, so it takes the absent-property guard. Regex stays case-sensitive
   * regardless of collation (collate-transforming a pattern would change its meaning).
   */
  private @Nullable SQLBooleanExpression translateRegex(String key, Text.RegexPredicate regex) {
    var pattern = regex.getPattern();
    if (pattern == null) {
      return null;
    }
    var matches = WHERE.matchesRegex(key, pattern, true);
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
