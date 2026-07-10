package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import java.util.HashMap;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Unit tests for {@link GremlinPredicateAdapter}, the {@code has(...)} → MATCH {@code WHERE}
 * chokepoint. The initial skeleton translates flat scalar {@link
 * org.apache.tinkerpop.gremlin.process.traversal.Compare} comparisons over a literal value and
 * declines (returns {@code null}) everything else, so the recogniser turns an untranslatable
 * predicate into a whole-traversal decline rather than a wrong filter. Each test names the predicate
 * it drives and the expected outcome (an operator mapping, or a decline).
 */
public class GremlinPredicateAdapterTest {

  // ---------------------------------------------------------------------------
  // Accept path — the six scalar comparisons map to their SQL operators.
  // ---------------------------------------------------------------------------

  /** {@code has("since", P.eq(2010))} maps to {@code since = 2010} — SQLEqualsOperator over {@code 2010}. */
  @Test
  public void eq_mapsToEqualsOperator() {
    var condition = translateScalar("since", P.eq(2010));
    assertThat(condition.getOperator()).isInstanceOf(SQLEqualsOperator.class);
    assertThat(renderLeft(condition)).isEqualTo("since");
    // Assert the literal value operand too: a regression that dropped the literal, substituted a
    // constant, or swapped operands would still pass the operator/field checks above.
    assertThat(renderRight(condition)).as("the compared value must survive as the right operand")
        .isEqualTo("2010");
  }

  /**
   * {@code has("since", P.neq(2010))} maps to {@code since IS DEFINED AND since <> 2010}, not a bare
   * {@code since <> 2010}. The presence guard is load-bearing: native {@code has(key, neq(v))}
   * excludes an element that lacks the property (HasContainer.test is false for an absent property),
   * but a bare {@code <>} WHERE evaluates a null (absent) operand to true and would wrongly include
   * it. This pins the emitted AST shape; {@code
   * EdgeTraversalEquivalenceTest#nonAdjacentEdgeFilter_neqExcludesAbsentProperty} proves the
   * end-to-end multiset.
   */
  @Test
  public void neq_mapsToPresenceGuardedNeq() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.neq(2010)));
    assertThat(expr).as("neq must translate, not decline").isNotNull();
    // toGenericStatement renders the compared value as a bound `?` placeholder, so assert the
    // structure (the IS DEFINED presence guard AND-ed with the <> comparison on the field), not the
    // inlined literal. The value binding is MatchLiteralBuilder's job, covered by its own tests.
    var rendered = render(expr);
    assertThat(rendered)
        .as("neq is guarded with a presence check (IS DEFINED) so absent-property elements are "
            + "excluded, matching native")
        .containsIgnoringCase("since is defined")
        .contains("since <>");
  }

  /** {@code has("since", P.lt(2015))} maps to a less-than ({@code <}) condition over {@code 2015} — the IC2 shape. */
  @Test
  public void lt_mapsToLtOperator() {
    var condition = translateScalar("since", P.lt(2015));
    assertThat(condition.getOperator()).isInstanceOf(SQLLtOperator.class);
    assertThat(renderRight(condition)).isEqualTo("2015");
  }

  /** {@code has("since", P.lte(2015))} maps to a less-than-or-equal ({@code <=}) condition over {@code 2015}. */
  @Test
  public void lte_mapsToLeOperator() {
    var condition = translateScalar("since", P.lte(2015));
    assertThat(condition.getOperator()).isInstanceOf(SQLLeOperator.class);
    assertThat(renderRight(condition)).isEqualTo("2015");
  }

  /** {@code has("since", P.gt(2015))} maps to a greater-than ({@code >}) condition over {@code 2015}. */
  @Test
  public void gt_mapsToGtOperator() {
    var condition = translateScalar("since", P.gt(2015));
    assertThat(condition.getOperator()).isInstanceOf(SQLGtOperator.class);
    assertThat(renderRight(condition)).isEqualTo("2015");
  }

  /** {@code has("since", P.gte(2015))} maps to a greater-than-or-equal ({@code >=}) condition over {@code 2015}. */
  @Test
  public void gte_mapsToGeOperator() {
    var condition = translateScalar("since", P.gte(2015));
    assertThat(condition.getOperator()).isInstanceOf(SQLGeOperator.class);
    assertThat(renderRight(condition)).isEqualTo("2015");
  }

  /** A String literal value is accepted and renders as a quoted string literal ({@code "alice"}) — not only numbers. */
  @Test
  public void stringValue_isAccepted() {
    var condition = translateScalar("name", P.eq("alice"));
    assertThat(condition.getOperator()).isInstanceOf(SQLEqualsOperator.class);
    assertThat(renderLeft(condition)).isEqualTo("name");
    // A String literal renders as a quoted, encoded string literal, not a bare identifier — so the
    // predicate compares against the value "alice", not a field or variable named alice.
    assertThat(renderRight(condition)).as("a String value renders as a quoted string literal")
        .isEqualTo("\"alice\"");
  }

  // ---------------------------------------------------------------------------
  // Decline path — everything outside the skeleton's scope returns null so the
  // whole traversal falls back to the native pipeline.
  // ---------------------------------------------------------------------------

  /** A {@code within} predicate carries a {@code Contains} bi-predicate, not a Compare — declines. */
  @Test
  public void withinPredicate_declines() {
    assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.within(1, 2))))
        .as("Contains (within) is out of the skeleton's scope")
        .isNull();
  }

  /** A connective {@code and} predicate carries an {@code AndP} bi-predicate — declines. */
  @Test
  public void connectiveAndPredicate_declines() {
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(
            new HasContainer("since", P.gt(2000).and(P.lt(2020)))))
        .as("connective and(...) predicates are a later track")
        .isNull();
  }

  /**
   * A {@code hasLabel}-shaped container keys on the reserved {@code ~label} token, which the skeleton
   * declines (label narrowing is a later track's job through the class-filter seam).
   */
  @Test
  public void reservedLabelKey_declines() {
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(
            new HasContainer(T.label.getAccessor(), P.eq("Person"))))
        .as("reserved ~label key is out of the skeleton's scope")
        .isNull();
  }

  /** A blank property key declines — an empty field name is not a translatable filter. */
  @Test
  public void blankKey_declines() {
    assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("  ", P.eq(1))))
        .as("a blank key is not translatable")
        .isNull();
  }

  /**
   * A {@code $}-prefixed property key declines rather than translating. Such a key would become a
   * bare WHERE identifier that the executor resolves as a query context variable (e.g. {@code
   * $parent}) instead of a record property, diverging from native Gremlin — which treats {@code
   * $parent} as a plain property name. Declining keeps the reserved {@code $} namespace off the
   * identifier path, mirroring the walker's reserved-{@code $} label pre-flight.
   */
  @Test
  public void reservedDollarKey_declines() {
    assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("$parent", P.eq(5))))
        .as("a $-prefixed key must not reach the context-variable identifier space")
        .isNull();
  }

  /**
   * A {@code @}-prefixed property key declines rather than translating. YouTrackDB's identifier
   * resolver treats a bare {@code @class} / {@code @rid} / {@code @version} identifier as record
   * metadata (the record-attribute namespace) rather than a plain property, so translating such a
   * key would diverge from native Gremlin — which treats {@code @class} as an ordinary property the
   * record does not carry (matching nothing on an edge). Declining keeps the reserved
   * record-attribute namespace off the WHERE identifier path, the same conservative fallback the
   * {@code $} minted-alias and {@code ~} hidden-key prefixes get.
   */
  @Test
  public void reservedRecordAttributeKey_declines() {
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("@class", P.eq("Knows"))))
        .as("a @-prefixed key must not reach the record-attribute identifier space")
        .isNull();
  }

  /**
   * A null comparison value declines rather than rendering {@code field = null}. {@code
   * has("since", P.eq(null))} produces a Compare predicate whose value is null, reaching the {@code
   * value == null} guard in {@code toFilter}; without it a null comparand would render as {@code
   * since = null} — a present-null set-membership semantic that diverges from native. This is a
   * distinct branch from {@link #unsupportedValueType_declines}, which drives the unrenderable-type
   * path inside the literal builder's try/catch (the value-null check runs before that try).
   */
  @Test
  public void nullComparisonValue_declines() {
    assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.eq(null))))
        .as("a null comparison value must decline, not render field = null")
        .isNull();
  }

  /**
   * A comparison value of a type {@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder} cannot
   * render declines rather than throwing — the adapter catches the builder's exception and returns
   * {@code null}. A bare {@link Object} is such an unsupported type.
   */
  @Test
  public void unsupportedValueType_declines() {
    assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("k", P.eq(new Object()))))
        .as("an unrenderable value type must decline, not throw")
        .isNull();
  }

  /** A null container declines rather than throwing (defensive). */
  @Test
  public void nullContainer_declines() {
    assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(null)).isNull();
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Translates {@code has(key, predicate)} and asserts the result is a scalar {@link
   * SQLBinaryCondition}, returning it for operator/operand assertions.
   */
  private static SQLBinaryCondition translateScalar(String key, P<?> predicate) {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer(key, predicate));
    assertThat(expr).as("a scalar comparison must translate")
        .isInstanceOf(SQLBinaryCondition.class);
    return (SQLBinaryCondition) expr;
  }

  /** Renders the left operand of a binary condition (the property field name). */
  private static String renderLeft(SQLBinaryCondition condition) {
    var sb = new StringBuilder();
    condition.getLeft().toString(new HashMap<>(), sb);
    return sb.toString();
  }

  /** Renders the right operand of a binary condition (the compared literal value). */
  private static String renderRight(SQLBinaryCondition condition) {
    var sb = new StringBuilder();
    condition.getRight().toString(new HashMap<>(), sb);
    return sb.toString();
  }

  /** Renders a whole boolean expression to its generic SQL text (for non-binary shapes such as an
   *  AND block). */
  private static String render(SQLBooleanExpression expr) {
    var sb = new StringBuilder();
    expr.toGenericStatement(sb);
    return sb.toString();
  }
}
