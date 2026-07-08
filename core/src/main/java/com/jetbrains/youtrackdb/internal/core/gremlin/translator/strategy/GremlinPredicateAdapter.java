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
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;

/**
 * Translates a TinkerPop {@link HasContainer} (one {@code has(key, predicate)} clause) into a MATCH
 * {@code WHERE} {@link SQLBooleanExpression}, or declines by returning {@code null}.
 *
 * <h2>Chokepoint role — initial skeleton</h2>
 *
 * This is the seam every {@code has(...)}-bearing recogniser routes through, so predicate coverage
 * grows in one reviewable place. The initial skeleton needs only enough of it to filter an edge
 * inside the non-adjacent {@code outE(L).has(edgeProp).inV()} chain, so it handles the flat scalar
 * comparisons {@link Compare} (eq, neq, lt, lte, gt, gte) over a single literal value. Later work
 * fills in the rest (text predicates, {@code within}/{@code without}, connective {@code and}/{@code
 * or} predicates, {@code hasNot} / entity-presence, {@code hasLabel}). Everything this skeleton does
 * not understand declines to {@code null} so the whole traversal falls back to the native pipeline
 * rather than translating a predicate wrong.
 *
 * <h2>Decline (return {@code null}) — never throw</h2>
 *
 * A recogniser turns a {@code null} here into a whole-traversal decline, so this adapter must never
 * throw on an unrecognised predicate. It declines when:
 *
 * <ul>
 *   <li>the key is null, blank, or a reserved token ({@code ~label} / {@code ~id} from {@code
 *       hasLabel} / {@code hasId}) — key handling beyond a plain property is a later track;
 *   <li>the key starts with the reserved {@code $} prefix — such a key would reach a WHERE
 *       identifier that the executor resolves as a query context variable, not a record property
 *       (see the {@link #RESERVED_ALIAS_PREFIX} field);
 *   <li>the predicate's bi-predicate is not a scalar {@link Compare} (e.g. {@code Contains}, a
 *       connective {@code AndP} / {@code OrP}, or a text predicate);
 *   <li>the compared value is null, or a type {@link MatchLiteralBuilder} cannot render (e.g. a
 *       deferred {@code GValue} parameter).
 * </ul>
 */
final class GremlinPredicateAdapter {

  /** Singleton — the adapter is stateless and cheap to share across recogniser calls. */
  static final GremlinPredicateAdapter INSTANCE = new GremlinPredicateAdapter();

  /** Prefix of TinkerPop's reserved hidden keys ({@code ~label}, {@code ~id}), which {@code
   *  hasLabel} / {@code hasId} produce and this skeleton declines. */
  private static final String HIDDEN_KEY_PREFIX = "~";

  /**
   * Reserved prefix for the translator's minted aliases ({@code $g2m_...}). A user property key in
   * this space would become a bare WHERE identifier that {@code SQLSuffixIdentifier} resolves as a
   * query context variable ({@code $parent}, or any {@code $name} bound in the execution context)
   * rather than a record property, diverging from native Gremlin — which treats {@code $foo} as a
   * plain property name that simply does not exist. Declining such keys mirrors the walker's
   * reserved-{@code $} label pre-flight ({@code GremlinStepWalker.RESERVED_ALIAS_PREFIX}) and keeps
   * user identifiers out of the context-variable namespace.
   */
  private static final String RESERVED_ALIAS_PREFIX = "$";

  /** Stateless builder for the comparison AST; construction is trivial so a shared instance is fine. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private GremlinPredicateAdapter() {
    // Singleton — instantiate via INSTANCE.
  }

  /**
   * Translates one {@link HasContainer} into a {@code field OP literal} boolean expression, or
   * returns {@code null} to decline (see the class Javadoc for the decline cases). Never throws.
   */
  @Nullable SQLBooleanExpression toFilter(HasContainer container) {
    if (container == null) {
      return null;
    }
    var key = container.getKey();
    if (key == null
        || key.isBlank()
        || key.startsWith(HIDDEN_KEY_PREFIX)
        || key.startsWith(RESERVED_ALIAS_PREFIX)) {
      // Blank/reserved keys are out of the skeleton's scope — decline. ~label/~id (hasLabel/hasId)
      // are a later track; a $-prefixed key would reach a WHERE identifier the executor resolves as
      // a query context variable rather than a property, so it declines to keep native behaviour.
      return null;
    }
    // Only a flat scalar comparison is in scope. A connective predicate (and/or) or a Contains
    // (within/without) carries a non-Compare bi-predicate, so it declines here for a later track.
    var predicate = container.getPredicate();
    if (predicate == null || !(predicate.getBiPredicate() instanceof Compare compare)) {
      return null;
    }
    var operator = toOperator(compare);
    if (operator == null) {
      return null;
    }
    // The literal value; a null value or a type the literal builder cannot render (e.g. a deferred
    // GValue parameter) declines rather than throwing.
    var value = predicate.getValue();
    if (value == null) {
      return null;
    }
    SQLExpression literal;
    try {
      literal = MatchLiteralBuilder.toLiteral(value);
    } catch (RuntimeException unsupportedType) {
      return null;
    }
    return WHERE.op(key, operator, literal);
  }

  /**
   * Maps a TinkerPop {@link Compare} onto the matching SQL comparison operator. The {@code switch} is
   * exhaustive over the six scalar comparisons; a bi-predicate that is not one of them was already
   * ruled out by the {@code instanceof Compare} gate in {@link #toFilter}, so this only ever sees a
   * real {@link Compare} constant.
   */
  @Nullable private static SQLBinaryCompareOperator toOperator(Compare compare) {
    return switch (compare) {
      case eq -> SQLEqualsOperator.INSTANCE;
      case neq -> new SQLNeqOperator(-1);
      case lt -> SQLLtOperator.INSTANCE;
      case lte -> SQLLeOperator.INSTANCE;
      case gt -> SQLGtOperator.INSTANCE;
      case gte -> new SQLGeOperator(-1);
    };
  }
}
