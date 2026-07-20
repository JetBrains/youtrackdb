package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsTextCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEndsWithCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchesCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStartsWithCondition;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Unit tests for {@link GremlinPredicateAdapter}, the {@code has(...)} → MATCH {@code WHERE}
 * chokepoint. The adapter maps the whole Phase-1 predicate surface — scalar {@code Compare},
 * {@code Contains} membership, the {@code Text} / {@code TextP} string predicates, and the {@code
 * and} / {@code or} / {@code not} connectives (including the {@code between} / {@code inside} /
 * {@code outside} range decompositions) — into an {@link SQLBooleanExpression}, and declines
 * (returns {@code null}) everything it cannot faithfully reproduce so the recogniser falls the whole
 * traversal back to the native pipeline. Each test names the predicate it drives and the expected
 * outcome (an AST shape, or a decline), with special attention to the absent-property guard,
 * the NULL comparand rewrites, and the singleton-collection decline.
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
   * it. This pins the emitted AST shape.
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

  /**
   * {@code has("since", P.lt(null))} declines: a range comparison against null has no defined
   * set-membership meaning (only {@code eq} / {@code neq} have absent-safe null rewrites), so the
   * traversal falls back to native rather than emit {@code since < null}.
   */
  @Test
  public void ltNull_declines() {
    assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.lt(null))))
        .as("a range comparison against null has no membership meaning and declines")
        .isNull();
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
  // NULL comparands — eq(null) / neq(null) have absent-safe rewrites.
  // ---------------------------------------------------------------------------

  /**
   * {@code has("since", P.eq(null))} maps to {@code since IS DEFINED AND since IS NULL}, not a bare
   * {@code since IS NULL}. YTDB {@code IS NULL} conflates an absent property with a literal-null
   * value, so a bare {@code IS NULL} would match an element that lacks the key — which native
   * excludes (HasContainer.test is false on an empty property iterator). The {@code IS DEFINED}
   * guard restores native's "property must be present" rule while still matching present-null values.
   */
  @Test
  public void eqNull_mapsToDefinedAndIsNull() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.eq(null)));
    assertThat(expr).as("eq(null) must translate to the guarded IS NULL form, not decline")
        .isInstanceOf(SQLAndBlock.class);
    var rendered = render(expr);
    assertThat(rendered)
        .as("eq(null) is a presence-guarded IS NULL so an absent property is excluded, matching "
            + "native")
        .containsIgnoringCase("since is defined")
        .containsIgnoringCase("since is null");
  }

  /**
   * {@code has("since", P.neq(null))} maps to {@code NOT(since IS NULL)} ({@code IS NOT NULL}).
   * That form is false on an absent property (YTDB {@code IS NULL} is true on absent, so its
   * negation is false), which matches native's exclusion of absent — so unlike {@code neq(v)} it
   * needs no separate {@code IS DEFINED} guard.
   */
  @Test
  public void neqNull_mapsToIsNotNull() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.neq(null)));
    assertThat(expr).as("neq(null) must translate to NOT(IS NULL), not decline")
        .isInstanceOf(SQLNotBlock.class);
    assertThat(render(expr)).containsIgnoringCase("not").containsIgnoringCase("since is null");
  }

  // ---------------------------------------------------------------------------
  // Contains membership (within / without) and the singleton-collection decline.
  // ---------------------------------------------------------------------------

  /** {@code has("since", P.within(1, 2))} maps to {@code since IN [1, 2]} — an SQLInCondition. */
  @Test
  public void within_mapsToInCondition() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.within(1, 2)));
    assertThat(expr).as("within maps to an IN condition").isInstanceOf(SQLInCondition.class);
    assertThat(render(expr)).containsIgnoringCase("since").contains(" IN ");
  }

  /**
   * {@code has("since", P.without(1, 2))} maps to {@code since IS DEFINED AND NOT(since IN [1, 2])}.
   * {@code without} is a negated membership: {@code NOT IN} is true on an absent property, so it
   * takes the absent-property guard to reproduce native's exclusion of elements lacking the key.
   */
  @Test
  public void without_mapsToGuardedNotIn() {
    var expr =
        GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("since", P.without(1, 2)));
    assertThat(expr).as("without is a guarded NOT IN").isInstanceOf(SQLAndBlock.class);
    var rendered = render(expr);
    assertThat(rendered).containsIgnoringCase("since is defined").containsIgnoringCase("not")
        .contains(" IN ");
  }

  /**
   * {@code has("age", P.eq([30]))} — a size-1 collection under {@code eq} — declines. {@code
   * QueryOperatorEquals} auto-unboxes a singleton against a scalar, and field cardinality is unknown
   * at translation time, so a translated {@code age = [30]} could diverge from native. Declining
   * falls the traversal back to the native pipeline.
   */
  @Test
  public void eqSingletonCollection_declines() {
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("age", P.eq(List.of(30)))))
        .as("a size-1 collection under eq declines under the singleton-collection rule")
        .isNull();
  }

  /** {@code has("age", P.neq([30]))} — a size-1 collection under {@code neq} — declines, symmetric to eq. */
  @Test
  public void neqSingletonCollection_declines() {
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("age", P.neq(List.of(30)))))
        .as("a size-1 collection under neq declines under the singleton-collection rule")
        .isNull();
  }

  /**
   * {@code has("age", P.eq([30, 40]))} — a size-2 collection — translates (the singleton
   * auto-unbox ambiguity does not apply for size ≥2), so only size-1 declines.
   */
  @Test
  public void eqMultiElementCollection_translates() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("age", P.eq(List.of(30, 40))));
    assertThat(expr).as("a size-2 collection under eq translates (not the singleton-decline case)")
        .isInstanceOf(SQLBinaryCondition.class);
  }

  /** {@code has("age", P.eq([]))} — an empty collection — translates (only the size-1 case declines). */
  @Test
  public void eqEmptyCollection_translates() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("age", P.eq(List.of())));
    assertThat(expr).as("an empty collection under eq translates (not the singleton-decline case)")
        .isInstanceOf(SQLBinaryCondition.class);
  }

  /**
   * {@code has("since", P.within(1, null))} declines: a null member cannot be rendered as a literal
   * (MatchLiteralBuilder rejects null), and translating only the non-null members would change the
   * multiset, so the whole predicate declines to native.
   */
  @Test
  public void within_withNullElement_declines() {
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(
            new HasContainer("since", P.within(Arrays.asList(1, null)))))
        .as("a null collection member is not renderable and declines")
        .isNull();
  }

  /**
   * {@code has("since", P.within([Object]))} declines: a member of a type MatchLiteralBuilder cannot
   * render (a bare {@link Object}) makes the whole membership predicate untranslatable, so it
   * declines to native rather than throw.
   */
  @Test
  public void within_withUnsupportedElement_declines() {
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(
            new HasContainer("since", P.within(Arrays.asList(new Object())))))
        .as("an unrenderable collection member declines")
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // Range decompositions — between / inside / outside arrive as AndP / OrP of
  // scalar comparisons; the adapter must preserve the exact boundary semantics.
  // ---------------------------------------------------------------------------

  /**
   * {@code has("since", P.between(2000, 2020))} maps to {@code since >= 2000 AND since < 2020} — the
   * right-exclusive {@code [2000, 2020)} range. TinkerPop decomposes {@code between} into an {@code
   * AndP[gte, lt]}, so the adapter must emit {@code >=} on the low bound and a strict {@code <} on
   * the high bound, never a closed {@code SQLBetweenCondition} (which would include 2020).
   */
  @Test
  public void between_mapsToRightExclusiveRange() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("since", P.between(2000, 2020)));
    assertThat(expr).as("between decomposes to an AND block").isInstanceOf(SQLAndBlock.class);
    var rendered = render(expr);
    assertThat(rendered).as("between is right-exclusive: >= low AND < high, never BETWEEN")
        .contains("since >= ")
        .contains("since < ")
        .contains(" AND ")
        .doesNotContain("BETWEEN")
        // The high bound must be strict `<`, not `<=`: `since <= ` would wrongly include the bound.
        .doesNotContain("since <= ");
  }

  /**
   * {@code has("since", P.inside(2000, 2020))} maps to {@code since > 2000 AND since < 2020} — open
   * at both ends. TinkerPop decomposes {@code inside} into an {@code AndP[gt, lt]}, so both bounds
   * are strict.
   */
  @Test
  public void inside_mapsToOpenRange() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("since", P.inside(2000, 2020)));
    assertThat(expr).as("inside decomposes to an AND block").isInstanceOf(SQLAndBlock.class);
    var rendered = render(expr);
    assertThat(rendered).as("inside is open at both ends: > low AND < high")
        .contains("since > ")
        .contains("since < ")
        .contains(" AND ")
        .doesNotContain("since >= ")
        .doesNotContain("since <= ");
  }

  /**
   * {@code has("since", P.outside(2000, 2020))} maps to {@code since < 2000 OR since > 2020}.
   * TinkerPop decomposes {@code outside} into an {@code OrP[lt, gt]}, so the adapter must emit an OR
   * of the two strict comparisons.
   */
  @Test
  public void outside_mapsToOrRange() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("since", P.outside(2000, 2020)));
    assertThat(expr).as("outside decomposes to an OR block").isInstanceOf(SQLOrBlock.class);
    var rendered = render(expr);
    assertThat(rendered).as("outside is < low OR > high")
        .contains("since < ")
        .contains("since > ")
        .contains(" OR ");
  }

  // ---------------------------------------------------------------------------
  // Connectives — P.and / P.or / P.not.
  // ---------------------------------------------------------------------------

  /** {@code has("since", P.gt(2000).and(P.lt(2020)))} maps to an AND block of the two comparisons. */
  @Test
  public void and_mapsToAndBlock() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("since", P.gt(2000).and(P.lt(2020))));
    assertThat(expr).as("P.and maps to an AND block").isInstanceOf(SQLAndBlock.class);
    assertThat(render(expr)).contains("since > ").contains("since < ").contains(" AND ");
  }

  /** {@code has("since", P.lt(2000).or(P.gt(2020)))} maps to an OR block of the two comparisons. */
  @Test
  public void or_mapsToOrBlock() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("since", P.lt(2000).or(P.gt(2020))));
    assertThat(expr).as("P.or maps to an OR block").isInstanceOf(SQLOrBlock.class);
    assertThat(render(expr)).contains("since < ").contains("since > ").contains(" OR ");
  }

  /**
   * {@code has("since", P.eq(5).negate())} — a {@code NotP} wrapping {@code eq(5)} (the shape {@code
   * P.not(...)} produces) — maps to {@code since IS DEFINED AND NOT(since = 5)}. Native NotP
   * excludes an absent property (HasContainer.test's empty iterator is false whatever the inner
   * predicate), so the {@code IS DEFINED} guard is required; without it {@code NOT(false-on-absent)}
   * would wrongly include absent rows.
   */
  @Test
  public void not_mapsToGuardedNegation() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("since", P.eq(5).negate()));
    assertThat(expr).as("NotP maps to a guarded NOT block").isInstanceOf(SQLAndBlock.class);
    var rendered = render(expr);
    assertThat(rendered).containsIgnoringCase("since is defined").contains("NOT")
        .contains("since = ");
  }

  /**
   * {@code has("since", P.gt(2000).and(customPredicate))} declines: any child of a connective that
   * cannot be translated fails the whole connective (all-or-nothing), so an {@code and} with one
   * untranslatable child returns null rather than a partial filter.
   */
  @Test
  public void and_withDecliningChild_declines() {
    PBiPredicate<Integer, Integer> custom = (a, b) -> true;
    P<Integer> declining = new P<>(custom, 5);
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(
            new HasContainer("since", P.gt(2000).and(declining))))
        .as("an AND with an untranslatable child declines the whole connective")
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // Text / TextP string predicates.
  // ---------------------------------------------------------------------------

  /**
   * {@code has("name", TextP.containing("li"))} maps to a strict {@code name CONTAINSTEXT "li"}. The
   * strict flag makes the node throw at execution on a present non-String value, matching native
   * {@code Text.containing} (String-only); the generic statement carries the distinct {@code
   * CONTAINSTEXT(strict)} token.
   */
  @Test
  public void containing_mapsToStrictContainsText() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.containing("li")));
    assertThat(expr).as("containing maps to CONTAINSTEXT")
        .isInstanceOf(SQLContainsTextCondition.class);
    assertThat(((SQLContainsTextCondition) expr).isStrict())
        .as("containing translates in strict mode for native type parity").isTrue();
    assertThat(render(expr)).contains("name CONTAINSTEXT(strict) ");
  }

  /**
   * {@code has("name", TextP.notContaining("li"))} maps to {@code name IS DEFINED AND NOT(name
   * CONTAINSTEXT(strict) "li")}. The negated form is true on an absent property, so it takes the
   * absent-property guard; the inner node is strict.
   */
  @Test
  public void notContaining_mapsToGuardedNotContainsText() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.notContaining("li")));
    assertThat(expr).as("notContaining is a guarded NOT CONTAINSTEXT")
        .isInstanceOf(SQLAndBlock.class);
    assertThat(render(expr)).containsIgnoringCase("name is defined").contains("NOT")
        .contains("name CONTAINSTEXT(strict) ");
  }

  /**
   * {@code has("name", TextP.startingWith("al"))} on a declared-String property maps to the half-open
   * prefix range {@code name >= "al" AND name < "al⁺"} — an AND block of two range conditions. A
   * declared String can only hold String values, so the index-aware range form (a B-tree prefix
   * scan) is safe; the {@code true} gate models the declared-String routing.
   */
  @Test
  public void startingWith_declaredString_mapsToIndexAwareRange() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.startingWith("al")), key -> true);
    assertThat(expr).as("startingWith on a declared String maps to a prefix range AND block")
        .isInstanceOf(SQLAndBlock.class);
    var and = (SQLAndBlock) expr;
    assertThat(and.getSubBlocks()).as("the range is a pair of binary conditions").hasSize(2);
    assertThat(and.getSubBlocks().get(0)).isInstanceOf(SQLBinaryCondition.class);
    assertThat(and.getSubBlocks().get(1)).isInstanceOf(SQLBinaryCondition.class);
    assertThat(render(expr)).contains("name >= ").contains("name < ").contains(" AND ");
  }

  /**
   * {@code has("name", TextP.startingWith("al"))} on an unknown / undeclared / non-String property
   * (the gate reports not-a-declared-String) maps to the strict full-scan {@code SQLStartsWithCondition}
   * rather than the index-aware range: the range form cannot throw on a non-String value, so the
   * strict node is used for native type parity.
   */
  @Test
  public void startingWith_unknownType_mapsToStrictStartsWith() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.startingWith("al")), key -> false);
    assertThat(expr).as("startingWith on an unknown type maps to the strict full-scan node")
        .isInstanceOf(SQLStartsWithCondition.class);
    assertThat(((SQLStartsWithCondition) expr).isStrict()).isTrue();
    assertThat(render(expr)).contains("name STARTSWITH(strict) ");
  }

  /**
   * {@code has("name", TextP.startingWith(""))} maps to a strict full-scan {@code STARTSWITH} node
   * (no longer declines): an empty prefix has no range upper bound, so even a declared-String
   * property falls back to the strict node. {@code startsWith("")} matches every present value,
   * matching native {@code startingWith("")}, and throws on a present non-String like native.
   */
  @Test
  public void startingWithEmptyPrefix_mapsToStrictStartsWith() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.startingWith("")), key -> true);
    assertThat(expr).as("an empty startingWith prefix maps to the strict full-scan node")
        .isInstanceOf(SQLStartsWithCondition.class);
    assertThat(((SQLStartsWithCondition) expr).isStrict()).isTrue();
  }

  /**
   * {@code has("name", TextP.startingWith(maxCodePoint))} — a single maximum code point (U+10FFFF),
   * which has no finite exclusive upper bound — maps to the strict full-scan {@code STARTSWITH} node
   * even on a declared-String property, rather than declining or throwing. The range builder cannot
   * produce a range for it, so the strict node handles it.
   */
  @Test
  public void startingWithMaxCodePointPrefix_mapsToStrictStartsWith() {
    var maxCodePoint = new String(Character.toChars(Character.MAX_CODE_POINT));
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.startingWith(maxCodePoint)), key -> true);
    assertThat(expr).as("an all-max-code-point prefix maps to the strict full-scan node")
        .isInstanceOf(SQLStartsWithCondition.class);
  }

  /**
   * {@code has("name", TextP.notStartingWith(maxCodePoint))} — the negated pathological prefix — maps
   * to a guarded NOT of the strict full-scan node (no longer declines): the prefix-range fallback
   * routes to the strict node, which the guarded negation then wraps.
   */
  @Test
  public void notStartingWithMaxCodePointPrefix_mapsToGuardedStrictNegation() {
    var maxCodePoint = new String(Character.toChars(Character.MAX_CODE_POINT));
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.notStartingWith(maxCodePoint)), key -> true);
    assertThat(expr)
        .as("a notStartingWith on an all-max prefix is a guarded NOT of the strict node")
        .isInstanceOf(SQLAndBlock.class);
    assertThat(render(expr)).containsIgnoringCase("name is defined").contains("NOT")
        .contains("name STARTSWITH(strict) ");
  }

  /**
   * {@code has("name", TextP.notStartingWith("al"))} with no schema context maps to {@code name IS
   * DEFINED AND NOT(name STARTSWITH(strict) "al")} — the guarded negation of the strict full-scan
   * node (the default routing when the type is unknown).
   */
  @Test
  public void notStartingWith_mapsToGuardedNegation() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.notStartingWith("al")));
    assertThat(expr).as("notStartingWith is a guarded NOT of the strict full-scan node")
        .isInstanceOf(SQLAndBlock.class);
    assertThat(render(expr)).containsIgnoringCase("name is defined").contains("NOT")
        .contains("name STARTSWITH(strict) ");
  }

  /** {@code has("name", TextP.endingWith("ce"))} maps to a strict {@code name ENDSWITH "ce"}. */
  @Test
  public void endingWith_mapsToStrictEndsWith() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.endingWith("ce")));
    assertThat(expr).as("endingWith maps to the ENDSWITH node")
        .isInstanceOf(SQLEndsWithCondition.class);
    assertThat(((SQLEndsWithCondition) expr).isStrict())
        .as("endingWith translates in strict mode for native type parity").isTrue();
    assertThat(render(expr)).contains("name ENDSWITH(strict) ");
  }

  /**
   * {@code has("name", TextP.notEndingWith("ce"))} maps to {@code name IS DEFINED AND NOT(name
   * ENDSWITH(strict) "ce")} — the guarded negation of the strict suffix match.
   */
  @Test
  public void notEndingWith_mapsToGuardedNegation() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.notEndingWith("ce")));
    assertThat(expr).as("notEndingWith is a guarded NOT ENDSWITH").isInstanceOf(SQLAndBlock.class);
    assertThat(render(expr)).containsIgnoringCase("name is defined").contains("NOT")
        .contains("name ENDSWITH(strict) ");
  }

  /**
   * {@code has("name", TextP.regex("a.*e"))} maps to a strict find-mode {@code MATCHES} — an
   * unanchored match anywhere in the value (Gremlin {@code Text.regex} semantics) that throws on a
   * present non-String value like native. The generic statement carries both the {@code (find)} and
   * {@code (strict)} tokens.
   */
  @Test
  public void regex_mapsToStrictFindModeMatches() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.regex("a.*e")));
    assertThat(expr).as("regex maps to a MATCHES condition")
        .isInstanceOf(SQLMatchesCondition.class);
    assertThat(((SQLMatchesCondition) expr).isStrict())
        .as("regex translates in strict mode for native type parity").isTrue();
    assertThat(render(expr)).as("regex is unanchored find-mode, strict")
        .contains("name MATCHES(find)(strict) ");
  }

  /**
   * {@code has("name", TextP.notRegex("a.*e"))} maps to {@code name IS DEFINED AND NOT(name
   * MATCHES(find)(strict) ...)} — the guarded negation of the strict find-mode match.
   */
  @Test
  public void notRegex_mapsToGuardedNegation() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.notRegex("a.*e")));
    assertThat(expr).as("notRegex is a guarded NOT of the find-mode match")
        .isInstanceOf(SQLAndBlock.class);
    assertThat(render(expr)).containsIgnoringCase("name is defined").contains("NOT")
        .contains("name MATCHES(find)(strict) ");
  }

  // ---------------------------------------------------------------------------
  // Type gate — no longer declines a Text / regex predicate on a non-String
  // property; it only routes the startingWith form. Text / regex always
  // translate strict and throw at execution like native.
  // ---------------------------------------------------------------------------

  /**
   * {@code has("age", TextP.containing("3"))} no longer declines regardless of the type gate: it
   * produces a strict {@code CONTAINSTEXT} node that throws at execution on a present non-String
   * value, matching native {@code Text}. The gate ({@code key -> true}, a declared String) does not
   * affect the {@code containing} form.
   */
  @Test
  public void containingWithTypeGate_producesStrictNode() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("age", TextP.containing("3")), key -> true);
    assertThat(expr).as("containing no longer declines on type — it produces a strict node")
        .isInstanceOf(SQLContainsTextCondition.class);
    assertThat(((SQLContainsTextCondition) expr).isStrict()).isTrue();
  }

  /**
   * {@code has("age", TextP.notContaining("3"))} — a negated Text form — also no longer declines: it
   * produces a guarded NOT wrapping a strict {@code CONTAINSTEXT} node.
   */
  @Test
  public void negatedTextWithTypeGate_producesGuardedStrictNode() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("age", TextP.notContaining("3")), key -> false);
    assertThat(expr).as("a negated Text predicate no longer declines on type")
        .isInstanceOf(SQLAndBlock.class);
    assertThat(render(expr)).containsIgnoringCase("age is defined").contains("NOT")
        .contains("age CONTAINSTEXT(strict) ");
  }

  /**
   * {@code has("age", TextP.regex("3"))} no longer declines on type: it produces a strict find-mode
   * {@code MATCHES} node that throws at execution on a present non-String value, like native regex.
   */
  @Test
  public void regexWithTypeGate_producesStrictNode() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("age", TextP.regex("3")), key -> false);
    assertThat(expr).as("a regex predicate no longer declines on type — it produces a strict node")
        .isInstanceOf(SQLMatchesCondition.class);
    assertThat(((SQLMatchesCondition) expr).isStrict()).isTrue();
  }

  /**
   * The type gate affects only the {@code startingWith} form: {@code has("age", P.eq(30))} with any
   * gate still translates — a scalar comparison is unaffected by the gate.
   */
  @Test
  public void scalarCompareOnNonStringProperty_stillTranslatesUnderTypeGate() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("age", P.eq(30)), key -> true);
    assertThat(expr)
        .as("a scalar comparison is unaffected by the type gate")
        .isInstanceOf(SQLBinaryCondition.class);
  }

  /**
   * A {@code containing} on any property (the gate reports {@code false}) translates to a strict
   * {@code CONTAINSTEXT} — the gate never declines a {@code containing}, it only routes {@code
   * startingWith}.
   */
  @Test
  public void textWithTypeGate_translatesToStrictNode() {
    var expr = GremlinPredicateAdapter.INSTANCE.toFilter(
        new HasContainer("name", TextP.containing("li")), key -> false);
    assertThat(expr)
        .as("a Text predicate translates to a strict node regardless of the gate")
        .isInstanceOf(SQLContainsTextCondition.class);
  }

  // ---------------------------------------------------------------------------
  // Decline path — predicates the adapter cannot faithfully reproduce return
  // null so the whole traversal falls back to the native pipeline.
  // ---------------------------------------------------------------------------

  /**
   * A custom {@link PBiPredicate} (a user lambda, not {@code Compare} / {@code Contains} / {@code
   * Text} / a regex predicate) declines: the translator cannot reproduce arbitrary user logic as a
   * WHERE clause, so it falls the traversal back to native rather than guess.
   */
  @Test
  public void customBiPredicate_declines() {
    PBiPredicate<Object, Object> custom = (a, b) -> true;
    assertThat(GremlinPredicateAdapter.INSTANCE.toFilter(new HasContainer("k", new P<>(custom, 5))))
        .as("a custom bi-predicate is not modelled and declines")
        .isNull();
  }

  /**
   * A {@code hasLabel}-shaped container keys on the reserved {@code ~label} token, which the adapter
   * declines (label narrowing is the recogniser's job through the boundary-node re-typing seam,
   * before the adapter runs).
   */
  @Test
  public void reservedLabelKey_declines() {
    assertThat(
        GremlinPredicateAdapter.INSTANCE.toFilter(
            new HasContainer(T.label.getAccessor(), P.eq("Person"))))
        .as("reserved ~label key is out of the adapter's scope")
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
