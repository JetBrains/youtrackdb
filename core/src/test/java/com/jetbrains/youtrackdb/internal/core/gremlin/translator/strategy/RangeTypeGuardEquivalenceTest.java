package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Equivalence fixture for the per-record type guard on a range comparison, and for the fold latch
 * that scopes it.
 *
 * <h2>The two answers a range comparison can have</h2>
 *
 * {@code has(key, gt(v))} is answered by two different comparators depending on where the container
 * ends up. When it lands in the run of {@code has} steps directly after the traversal's {@code V()},
 * {@code YTDBGraphStepStrategy} folds it into {@code YTDBGraphStep} and the comparison runs with SQL
 * ordering, which ranks a String above an Integer and so answers a cross-type comparison rather than
 * rejecting it. Anywhere else the container survives as a {@code HasStep} and TinkerPop's {@code
 * GremlinValueComparator} answers it, whose rule is that operands from different comparability
 * blocks never compare — a cross-type range comparison matches nothing.
 *
 * <p>Translated SQL naturally reproduces the first answer. In an unfolded position that is the wrong
 * one, so the translator emits {@code key.type() IN [<the literal's block>] AND key <cmp> literal}
 * there, reproducing the comparator's partition per record without needing the property's declared
 * type. The latch on {@link RecognitionContext#atTraversalStart()} is what tells the two positions
 * apart.
 *
 * <h2>What the fixture holds</h2>
 *
 * Nine vertices whose types are deliberately mixed, so a comparison that ignores runtime type gives
 * a visibly different answer from one that respects it: four {@code Item}s with a declared-STRING
 * {@code name} and a declared-INTEGER {@code num}, two {@code Loose}es on a class that declares
 * nothing (one holds the String {@code "zulu"} under {@code name}, the other the Integer {@code 99}),
 * two {@code Anyp}s holding a String and an Integer under an undeclared {@code val}, and one {@code
 * Root} linking to all four {@code Item}s. Every vertex carries a {@code tag}, which is what the
 * assertions compare on.
 */
public class RangeTypeGuardEquivalenceTest extends GraphBaseTest {

  /** The alias the walker mints for the root {@code V()} scan. */
  private static final String ORIGIN_ALIAS = "$g2m_v0";

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(this::graphSession);

  /** The hub every {@code Types} vertex hangs off, so the partition shape has a hop to cross. */
  private Vertex typesHub;

  /** All four {@code Item} tags, the answer to "every Item". */
  private static final List<String> ALL_ITEMS = List.of("alpha", "bravo", "charlie", "delta");

  // ---------------------------------------------------------------------------
  // The fold boundary itself — pinned in both directions, so a change to either
  // side of it breaks loudly instead of silently re-scoping the guard.
  // ---------------------------------------------------------------------------

  /**
   * The fold's own answer, pinned. {@code g.V().has("name", gt(27))} is folded, so both arms run
   * {@code YTDBGraphStep}'s comparator, and that comparator ranks <em>every</em> String above the
   * Integer 27 — all six name-bearing vertices come back, including the one whose {@code name} is
   * the Integer 99 and the four whose names are words. The complementary direction returns nothing.
   *
   * <p>This is the answer the guard must not touch. If {@code YTDBGraphStep} ever learns TinkerPop's
   * comparability rule, this case fails and the guard's scoping has to be revisited in the same
   * change rather than discovered later through a divergence.
   */
  @Test
  public void foldedRootRange_keepsTheGraphStepComparatorsCrossTypeAnswer() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().has(name, gt(27)) — folded",
        () -> graph.traversal().V().has("name", P.gt(27)),
        List.of("alpha", "bravo", "charlie", "delta", "loose_num", "loose_zulu"));
    assertAgreesWithNative(
        "g.V().has(name, lt(27)) — folded, the other direction",
        () -> graph.traversal().V().has("name", P.lt(27)),
        List.of());
  }

  /**
   * {@code where(...)}, {@code filter(...)} and an all-filter {@code and(...)} are rewritten by
   * TinkerPop's {@code InlineFilterStrategy} into top-level {@code HasStep}s before any provider
   * strategy runs, so their containers reach the fold even though the user wrote them inside a
   * child. An {@code and(...)} with one non-filter arm keeps its {@code AndStep} — that is the
   * boundary of the inlining.
   *
   * <p>The latch reads the post-inlining step list, so it classifies all three correctly without
   * knowing anything about the surface syntax. This case pins the inlining itself: a TinkerPop
   * upgrade that stops inlining {@code where} would move those containers out of the fold, and the
   * guard would then have to start firing on them. Driven with the translator off, because an
   * accepted translation replaces the step list with a single boundary step and leaves nothing to
   * inspect.
   */
  @Test
  public void inlineFilterStrategy_hoistsFilterOnlyChildrenToTopLevelBeforeTheTranslatorRuns() {
    seedMixedTypeFixture();

    assertInlinedIntoTheFold(
        "where(has(...))", "name", () -> graph.traversal().V().where(__.has("name", P.gt(27))));
    assertInlinedIntoTheFold(
        "filter(has(...))", "name", () -> graph.traversal().V().filter(__.has("name", P.gt(27))));
    assertInlinedIntoTheFold(
        "and(has(...), has(...))", "name",
        () -> graph.traversal().V().and(__.has("name", P.gt(27)), __.has("num", P.gt(0))));

    withTranslator(false, () -> {
      var admin = graph.traversal().V().and(__.has("name", P.gt(27)), __.out("link")).asAdmin();
      admin.applyStrategies();
      assertThat(admin.getSteps().stream().anyMatch(AndStep.class::isInstance))
          .as("an and(...) with a non-filter arm must keep its AndStep — this is the boundary of "
              + "the inlining, and without it the case above proves nothing about scope")
          .isTrue();
    });
  }

  /**
   * The latch's classification, read off the emitted plan rather than inferred from row counts. The
   * folded root comparison emits a bare {@code name > ?}; the same comparison after a hop emits the
   * {@code name.type() IN [...]} conjunct beside it.
   *
   * <p>Row counts alone cannot separate "the guard fired" from "the shape declined" or "the fixture
   * happens to be empty", so this is the case that says which branch ran.
   */
  @Test
  public void foldLatch_emitsTheGuardOnlyInTheUnfoldedPosition() {
    seedMixedTypeFixture();

    assertThat(boundaryPlanText(() -> graph.traversal().V().has("name", P.gt(27))))
        .as("a folded range comparison must translate exactly as before — no type guard")
        .doesNotContain("type()");
    assertThat(boundaryPlanText(() -> graph.traversal().V().out("link").has("name", P.gt(27))))
        .as("the same comparison after a hop is unfolded and must carry the guard")
        .contains("type()");
  }

  /**
   * An explicit {@code barrier()} between the {@code V()} and the comparison breaks the fold, so the
   * comparison is unfolded and takes the guard. This is the one shape where the two step lists
   * disagree: the fold's rule treats a barrier as an ordinary step and closes the run on it, while
   * the walker's cursor treats it as transparent and skips it. The walker has to notice the skip
   * anyway, or it would classify this comparison as folded and answer it with six rows where native
   * answers one.
   */
  @Test
  public void explicitBarrierBeforeTheRange_closesTheFoldTheSameWayTheGraphStepStrategyDoes() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().barrier().has(name, gt(27))",
        () -> graph.traversal().V().barrier().has("name", P.gt(27)),
        List.of("loose_num"));
  }

  // ---------------------------------------------------------------------------
  // The five divergences the guard closes.
  // ---------------------------------------------------------------------------

  /**
   * A range comparison after a hop. {@code g.V().out().has("name", gt(27))} returned all four
   * {@code Item}s translated against none native, because the surviving {@code HasStep} is answered
   * by TinkerPop's comparator, which refuses to compare a String {@code name} with an Integer
   * literal. Guarded, both arms return nothing — and the same-type control beside it still returns
   * its two rows, so the empty answer is the type rule rather than a broken hop.
   */
  @Test
  public void rangeAfterHop_matchesNothingLikeNative_whileTheSameTypeControlStillMatches() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().out().has(name, gt(27))",
        () -> graph.traversal().V().out("link").has("name", P.gt(27)),
        List.of());
    assertAgreesWithNative(
        "control: g.V().out().has(num, gt(25)) — same-type, must keep matching",
        () -> graph.traversal().V().out("link").has("num", P.gt(25)),
        List.of("charlie", "delta"));
  }

  /**
   * The same shape with the class named next to the comparison. {@code
   * g.V().out().hasLabel("Item").has("name", gt(27))} leaves one {@code HasStep} carrying both the
   * {@code ~label} and the property container, still unfolded, and it diverged the same way. Its
   * same-type sibling must keep returning its two rows.
   */
  @Test
  public void rangeAfterHopWithLabel_matchesNothingLikeNative() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().out().hasLabel(Item).has(name, gt(27))",
        () -> graph.traversal().V().out("link").hasLabel("Item").has("name", P.gt(27)),
        List.of());
    assertAgreesWithNative(
        "control: g.V().out().hasLabel(Item).has(num, gt(25))",
        () -> graph.traversal().V().out("link").hasLabel("Item").has("num", P.gt(25)),
        List.of("charlie", "delta"));
  }

  /**
   * A range comparison in an {@code or(...)} arm, at the root and after a hop. {@code or} is never
   * inlined, so its arms are always unfolded and always diverged: at the root six rows translated
   * against two native, after a hop four against one. The third case is the one that must not move
   * — both arms of {@code or(has(name, gt(27)), has(name, eq("zulu")))} over the mixed schema-less
   * class agree today and still agree, since the guard admits the Integer-valued row for the range
   * arm and the String-valued one for the equality arm.
   */
  @Test
  public void rangeInsideOr_matchesNativeAtTheRootAndAfterAHop() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().or(has(name, gt(27)), has(num, eq(10)))",
        () -> graph.traversal().V().or(__.has("name", P.gt(27)), __.has("num", P.eq(10))),
        List.of("alpha", "loose_num"));
    assertAgreesWithNative(
        "g.V().hasLabel(Item).or(has(name, gt(27)), has(num, eq(10)))",
        () -> graph.traversal().V().hasLabel("Item")
            .or(__.has("name", P.gt(27)), __.has("num", P.eq(10))),
        List.of("alpha"));
    assertAgreesWithNative(
        "g.V().out().or(has(name, gt(27)), has(num, eq(10)))",
        () -> graph.traversal().V().out("link")
            .or(__.has("name", P.gt(27)), __.has("num", P.eq(10))),
        List.of("alpha"));
    assertAgreesWithNative(
        "control: g.V().hasLabel(Loose).or(has(name, gt(27)), has(name, eq(zulu)))",
        () -> graph.traversal().V().hasLabel("Loose")
            .or(__.has("name", P.gt(27)), __.has("name", P.eq("zulu"))),
        List.of("loose_num", "loose_zulu"));
  }

  /**
   * A {@code where(...)} after a hop. {@code InlineFilterStrategy} hoists the child's {@code has}
   * to top level, but the hop already closed the fold, so the hoisted container is unfolded — four
   * rows translated against none native before the guard.
   */
  @Test
  public void rangeInsideWhereAfterHop_matchesNothingLikeNative() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().out().where(has(name, gt(27)))",
        () -> graph.traversal().V().out("link").where(__.has("name", P.gt(27))),
        List.of());
  }

  /**
   * The undeclared-property edge filter, which is the shape the alternative design would have
   * withdrawn. {@code since} is declared nowhere, so no static type gate could have said whether the
   * comparison was safe; the per-record guard answers it from the stored value instead and the shape
   * keeps translating with the answer it already had.
   */
  @Test
  public void undeclaredEdgePropertyRange_keepsTranslatingAndKeepsItsRows() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().outE(link).has(since, lt(2025)).inV() — since is undeclared",
        () -> graph.traversal().V().outE("link").has("since", P.lt(2025)).inV(),
        List.of("alpha", "bravo"));
  }

  /**
   * The edge-side witness for the guard. The case above cannot fail when the guard is removed,
   * because its four {@code since} values are all Integers and the comparand is an Integer too — the
   * emitted {@code since.type() IN [numeric block]} is then true for every candidate row and the
   * guarded and unguarded translations select the same rows. This fixture stores one {@code since}
   * as a String, which is where the two comparators disagree: SQL ordering ranks a String above
   * every number and would admit that edge for {@code gt(2025)}, while TinkerPop's comparator
   * refuses to compare the two and drops it. {@code EdgeHopRecogniser} hardcodes the guard on rather
   * than computing it the way {@code HasStepRecogniser} does, so the vertex-side coverage transfers
   * nothing here.
   */
  @Test
  public void crossTypeEdgePropertyRange_matchesTheComparatorNotSqlOrdering() {
    seedCrossTypeEdgeFixture();

    assertAgreesWithNative(
        "g.V().outE(bond).has(since, gt(2025)).inV() — one since is a String",
        () -> graph.traversal().V().outE("bond").has("since", P.gt(2025)).inV(),
        List.of("late_one", "late_two"));
    // The other direction of the same partition: below the comparand the String is excluded too, so
    // a guard that admitted it would show up as an extra row here rather than a missing one.
    assertAgreesWithNative(
        "g.V().outE(bond).has(since, lt(2025)).inV()",
        () -> graph.traversal().V().outE("bond").has("since", P.lt(2025)).inV(),
        List.of("early_one"));
  }

  // ---------------------------------------------------------------------------
  // Scoping — the folded positions must keep their current answers.
  // ---------------------------------------------------------------------------

  /**
   * Seven folded shapes that agree with native today and must go on agreeing. Applying the guard
   * everywhere rather than only in unfolded positions breaks every one of them, because in a folded
   * position {@code YTDBGraphStep}'s comparator <em>is</em> native's answer and the guard contradicts
   * it. Three of the seven reach the fold through {@code InlineFilterStrategy} rather than by being
   * written adjacent to the {@code V()}, which is why the latch has to key on the post-inlining step
   * list.
   */
  @Test
  public void foldedPositions_keepTranslatingWithTheGraphStepComparatorsAnswer() {
    seedMixedTypeFixture();

    var everyNameBearing =
        List.of("alpha", "bravo", "charlie", "delta", "loose_num", "loose_zulu");
    assertAgreesWithNative(
        "g.V().has(name, gt(27))",
        () -> graph.traversal().V().has("name", P.gt(27)), everyNameBearing);
    assertAgreesWithNative(
        "g.V().hasLabel(Item).has(name, gt(27))",
        () -> graph.traversal().V().hasLabel("Item").has("name", P.gt(27)), ALL_ITEMS);
    assertAgreesWithNative(
        "g.V().hasLabel(Loose).has(name, gt(27)) — schema-less, mixed runtime types",
        () -> graph.traversal().V().hasLabel("Loose").has("name", P.gt(27)),
        List.of("loose_num", "loose_zulu"));
    assertAgreesWithNative(
        "g.V().hasLabel(Anyp).has(val, gt(27)) — undeclared key, mixed runtime types",
        () -> graph.traversal().V().hasLabel("Anyp").has("val", P.gt(27)),
        List.of("anyp_num", "anyp_str"));
    assertAgreesWithNative(
        "g.V().where(has(name, gt(27))) — inlined into the fold",
        () -> graph.traversal().V().where(__.has("name", P.gt(27))), everyNameBearing);
    assertAgreesWithNative(
        "g.V().filter(has(name, gt(27))) — inlined into the fold",
        () -> graph.traversal().V().filter(__.has("name", P.gt(27))), everyNameBearing);
    assertAgreesWithNative(
        "g.V().and(has(name, gt(27)), has(num, gt(0))) — both arms inlined into the fold",
        () -> graph.traversal().V().and(__.has("name", P.gt(27)), __.has("num", P.gt(0))),
        ALL_ITEMS);
  }

  // ---------------------------------------------------------------------------
  // The negated forms, which used to decline outright.
  // ---------------------------------------------------------------------------

  /**
   * Every negated range shape now translates and agrees. These used to decline: a {@code not(...)}
   * child is never folded, so the two native answers disagreed with each other and no unguarded
   * translation matched both. The guard reproduces the unfolded answer directly, so the static gate
   * that used to sit in {@code not(...)} is gone.
   *
   * <p>The {@code hasLabel(Loose)} case is the one no static gate could have answered: the class
   * declares nothing and its two rows hold different runtime types under the same key, so the right
   * answer differs per record.
   */
  @Test
  public void negatedRangeComparisons_translateAndMatchNative() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().not(has(name, gt(27)))",
        () -> graph.traversal().V().not(__.has("name", P.gt(27))),
        List.of("alpha", "anyp_num", "anyp_str", "bravo", "charlie", "delta", "loose_zulu",
            "root"));
    assertAgreesWithNative(
        "g.V().hasLabel(Item).not(has(name, gt(27)))",
        () -> graph.traversal().V().hasLabel("Item").not(__.has("name", P.gt(27))), ALL_ITEMS);
    assertAgreesWithNative(
        "g.V().not(not(has(name, gt(27))))",
        () -> graph.traversal().V().not(__.not(__.has("name", P.gt(27)))), List.of("loose_num"));
    assertAgreesWithNative(
        "g.V().out().not(has(name, gt(27)))",
        () -> graph.traversal().V().out("link").not(__.has("name", P.gt(27))), ALL_ITEMS);
    assertAgreesWithNative(
        "g.V().hasLabel(Loose).not(has(name, gt(27))) — mixed types under one key",
        () -> graph.traversal().V().hasLabel("Loose").not(__.has("name", P.gt(27))),
        List.of("loose_zulu"));
  }

  /**
   * A composite predicate arrives as a connective over range leaves, so the guard has to recurse
   * into it rather than only wrap the top. {@code P.outside(lo, hi)} is the shape that shows it:
   * TinkerPop decomposes it into {@code OrP[lt(lo), gt(hi)]} before the translator sees it, and both
   * arms are unbounded, so a foreign runtime type falls inside one of them under SQL ordering.
   *
   * <p>On the two-runtime-type {@code Loose} class, {@code loose_zulu} holds the String
   * {@code "zulu"} under the same key {@code loose_num} holds the Integer {@code 99}. Natively the
   * container is unfolded (it sits in a {@code not(…)} child), so the String never compares with an
   * Integer comparand and the inner predicate is false for it — the {@code NOT} keeps it. A
   * translation that guarded only the top and left the two {@code OrP} arms bare would emit
   * {@code NOT(name < 28 OR name > 33)}: SQL ranks every String above every number, so
   * {@code "zulu" > 33} is true, the inner is true, and the row is dropped. The expected list is
   * therefore the discriminating assertion, not decoration.
   *
   * <p>{@code between} / {@code inside} decompose the same way, into bounded arms, so a String
   * cannot witness them — it sorts above both bounds and fails the upper arm guarded or not. A
   * stored type that a numeric bound converts <em>into</em> witnesses them anyway; that is the case
   * below, {@link #betweenOverStoredDatesAndBooleans_needsBothConnectiveArmsGuarded}, which carries
   * the {@code AndP} half as this one carries the {@code OrP} half.
   */
  @Test
  public void notWithOutsideOverMixedRuntimeTypes_needsBothConnectiveArmsGuarded() {
    seedMixedTypeFixture();

    assertAgreesWithNative(
        "g.V().hasLabel(Loose).not(has(name, outside(28, 33)))",
        () -> graph.traversal().V().hasLabel("Loose").not(__.has("name", P.outside(28, 33))),
        List.of("loose_zulu"));
  }

  /**
   * The {@code AndP} half of the same recursion. {@code P.between(lo, hi)} decomposes into
   * {@code AndP[gte lo, lt hi]}, whose arms are both bounded, and a String cannot witness that
   * shape: SQL converts each numeric bound to a String, every stored String here sorts above both,
   * and the upper arm rejects it guarded or not. A stored {@code Date} or {@code Boolean} does
   * witness it, because the conversion carries the literal rather than ranking the classes.
   * {@code SQLBinaryCompareOperator.doCompare} converts the <em>literal</em> into the stored
   * value's class, and {@code DATETIME} reads a {@code Number} as epoch millis, so
   * {@code t_date_early}'s 1000 ms sits inside {@code [0, 1_000_000_000)}; a numeric bound converts
   * to a Boolean the same way, which is why {@code t_bool_false} clears both bounds while
   * {@code t_bool_true} fails the upper one. Natively the container is unfolded, so
   * {@code GremlinValueComparator} puts a Date and a Boolean in different blocks from a numeric
   * comparand and excludes both rows.
   *
   * <p>Measured rather than argued: rebuilding the adapter's connective recursion to hand every
   * {@code AndP} / {@code OrP} child an off guard makes the translated arm return
   * {@code t_bool_false} and {@code t_date_early} on top of the seven numerics, so the expected
   * list is the discriminating assertion. {@code t_date_late} holds 9e9 ms and stays above the
   * upper bound either way, so only one of the two Dates leaks.
   *
   * <p>Runs on the {@code Types} fixture rather than the mixed-type one because that is where the
   * stored Dates and Booleans live. Bounds are Integers on purpose: with {@code Long} bounds an
   * unguarded arm throws inside {@code PropertyTypeInternal.castComparableNumber}, which would end
   * the case in an error before the rows could be compared.
   */
  @Test
  public void betweenOverStoredDatesAndBooleans_needsBothConnectiveArmsGuarded() {
    seedOneValueOfEachType();

    assertAgreesWithNative(
        "g.V().hasLabel(Hub).out(holds).has(v, between(0, 1_000_000_000))",
        () -> typesRange(P.between(0, 1_000_000_000)),
        List.of("t_byte", "t_decimal", "t_double", "t_float", "t_integer", "t_long", "t_short"));
  }

  // ---------------------------------------------------------------------------
  // The comparability partition, one value of each runtime type.
  // ---------------------------------------------------------------------------

  /**
   * Ten literal / direction combinations over a class holding one value of every runtime type,
   * asserting the translated answer equals native's in each. This is the partition the guard claims
   * to reproduce: a numeric literal reaches every numeric subtype and nothing else, a String reaches
   * only Strings, a Boolean only Booleans, and the two Dates are reachable from none of them.
   *
   * <p>Eight of the ten are cases where an unguarded SQL comparison is wrong — it would order a
   * String or a Date against a number and return rows native excludes. The two {@code lt} cases over
   * the small numerics are where plain SQL happens to agree, and they are here so a guard that
   * over-restricted would be caught too.
   */
  @Test
  public void comparabilityPartition_matchesNativeForEveryLiteralType() {
    seedOneValueOfEachType();

    var smallNumerics = List.of("t_byte", "t_integer", "t_short");
    var largeNumerics = List.of("t_decimal", "t_double", "t_float", "t_long");
    assertAgreesWithNative("v > 27 (Integer)", () -> typesRange(P.gt(27)), largeNumerics);
    assertAgreesWithNative("v < 27 (Integer)", () -> typesRange(P.lt(27)), smallNumerics);
    assertAgreesWithNative("v > 27L (Long)", () -> typesRange(P.gt(27L)), largeNumerics);
    assertAgreesWithNative("v < 27L (Long)", () -> typesRange(P.lt(27L)), smallNumerics);
    assertAgreesWithNative("v > 27.5 (Double)", () -> typesRange(P.gt(27.5d)),
        List.of("t_decimal", "t_double", "t_long"));
    assertAgreesWithNative("v < 27.5 (Double)", () -> typesRange(P.lt(27.5d)), smallNumerics);
    assertAgreesWithNative("v > 'm' (String)", () -> typesRange(P.gt("m")), List.of("t_string_hi"));
    assertAgreesWithNative("v < 'm' (String)", () -> typesRange(P.lt("m")), List.of("t_string_lo"));
    assertAgreesWithNative("v > true (Boolean)", () -> typesRange(P.gt(true)), List.of());
    assertAgreesWithNative("v < true (Boolean)", () -> typesRange(P.lt(true)),
        List.of("t_bool_false"));
  }

  /**
   * NaN is admitted by the guard — the type accessor reports it as {@code DOUBLE} — and excluded by
   * IEEE arithmetic on both arms, since every comparison against NaN is false. TinkerPop reaches the
   * same exclusion by a different route: its comparator has an explicit NaN check and reports NaN
   * incomparable with everything, itself included.
   *
   * <p>Two mechanisms landing on the same answer is exactly the kind of agreement that stops holding
   * quietly, so it is pinned rather than re-derived.
   */
  @Test
  public void nanValue_isExcludedByBothArmsEvenThoughTheGuardAdmitsIt() {
    seedOneValueOfEachType();
    addTypedVertex("t_nan", Double.NaN);

    assertAgreesWithNative(
        "v <= 1000 with a NaN row present",
        () -> typesRange(P.lte(1000)),
        List.of("t_byte", "t_decimal", "t_double", "t_float", "t_integer", "t_long", "t_short"));
  }

  /**
   * A literal whose class names no comparability block declines the whole traversal rather than
   * translating unguarded. {@code java.time.Instant} is the case in hand: TinkerPop types it as
   * unknown rather than as a date, and the SQL type accessor reports {@code null} for a stored one,
   * so there is no list of type names that describes the rows it compares with.
   *
   * <p>The control runs first and is what keeps the decline meaningful. The declined shape's native
   * answer is legitimately empty — TinkerPop's comparator rejects an {@code Instant} against every
   * stored value — so its row comparison is empty against empty and cannot fail. The control pins
   * that the same shape with a nameable literal does translate and does return rows on this
   * fixture, so a decline here is the {@code Instant} and not the fixture having drifted empty.
   */
  @Test
  public void literalWithNoComparabilityBlock_declinesToNative() {
    seedOneValueOfEachType();

    assertAgreesWithNative(
        "control: the same shape with a comparable literal translates and returns rows",
        () -> typesRange(P.gt(27)),
        List.of("t_decimal", "t_double", "t_float", "t_long"));

    assertDeclinesAndMatchesNative(
        "v > Instant — no block can be named",
        () -> typesRange(P.gt(java.time.Instant.ofEpochMilli(1000))));
  }

  // ---------------------------------------------------------------------------
  // Plan shape — the guard must not look like a selective filter to the planner.
  // ---------------------------------------------------------------------------

  /**
   * On a class large enough to clear the filter-selectivity estimator's early bail (604 rows against
   * its threshold of 100), a guarded alias on the far side of a hop must not capture the plan root,
   * and the indexed range beside it must still drive an index fetch.
   *
   * <p>The hazard the case exists for: the estimator accepts a binary {@code =} condition, cannot
   * resolve a distinct count for a left side carrying a modifier, and falls through to a default
   * that makes the alias look like a one-row alias — which would reorder edges and mis-forecast the
   * hash join. The guard is emitted as an {@code IN} condition rather than an equality precisely to
   * stay out of that branch, and the row count here is what makes the estimator run at all rather
   * than bail early.
   *
   * <p>Which assertion carries which claim, since they are not interchangeable. The rendered alias
   * filter is the one that sees the node form, and it is the end-to-end witness for the choice: it
   * exercises the whole walker → adapter → builder path, where
   * {@code MatchWhereBuilderTest.typeIn_buildsAnInConditionOverTheMethodCall} pins the builder in
   * isolation. The plan-root and index-fetch assertions <em>cannot</em> see the form — measured, by
   * rebuilding {@code typeIn} to emit {@code =} for a single-name block. Under that build the
   * node-form assertion above is the one that reddens — the walk renders
   * {@code mixed.type() = "STRING"} — and it aborts the test before the two
   * assertions below run; with it suspended they both still pass, and no other case in this class
   * moves, because {@code = 'STRING'} and {@code IN ['STRING']} select the same rows. Both
   * estimator paths are blind to the form on a one-edge pattern: {@code SQLWhereClause.estimate}'s
   * equality path is gated on {@code isBaseIdentifier()}, which a {@code .type()} modifier fails
   * whatever the operator, and the tier-3 default the paragraph above names lives on
   * {@code estimateFilterSelectivity}, which feeds edge ordering and the hash-join forecast —
   * neither of which exists with one edge. What those two assertions do pin is that the plan shape
   * is the unguarded one, which is worth having and is a different claim. The compiled plan's
   * pretty-print cannot carry the form either: it collapses an alias's filter into the pattern line.
   */
  @Test
  public void guardedAliasAboveTheEstimatorThreshold_doesNotCaptureThePlanRoot() {
    seedIndexedBulkClass();

    var shape = (Supplier<GraphTraversal<?, ?>>) () -> graph.traversal().V()
        .hasLabel("Bulk").has("k", P.gt("k000")).out("chain").has("mixed", P.gt(27));

    // The hop target's `mixed` values are deliberately of two runtime types, so the rows say whether
    // the guard fired: unguarded SQL ranks the String above 27 and returns it too.
    assertAgreesWithNative(
        "guarded alias on a 604-row class", shape, List.of("b601"));

    // The node-form witness runs on a String comparand rather than on the shape above, because a
    // single-name block is the only case where an equality is expressible at all: a numeric
    // literal's block names seven types and `=` cannot carry them. Same pattern, same guarded
    // alias, so what it observes is the same emission path.
    var singleNameBlock = (Supplier<GraphTraversal<?, ?>>) () -> graph.traversal().V()
        .hasLabel("Bulk").has("k", P.gt("k000")).out("chain").has("mixed", P.gt("a"));
    assertThat(renderedAliasFilterMentioning(singleNameBlock, "mixed"))
        .as("the guard must reach the walk as an IN condition — an equality is the one node shape "
            + "the filter-selectivity estimator does score, and nothing else in this case can tell "
            + "the two apart")
        .contains("mixed.type() IN [")
        .doesNotContain("mixed.type() =");

    var plan = boundaryPlanText(shape);
    assertThat(planRootAlias(plan))
        .as("the plan must still root at the origin — a guarded alias that captured the root would "
            + "mean the estimator scored the guard as a selective filter")
        .isEqualTo(ORIGIN_ALIAS);
    assertThat(plan)
        .as("the indexed range beside the guard must still drive an index fetch — a modifier on the "
            + "guard's left side keeps it out of the index-key map, not the comparison beside it")
        .contains("FETCH FROM INDEX");
  }

  // ---------------------------------------------------------------------------
  // Fixtures.
  // ---------------------------------------------------------------------------

  /**
   * Nine vertices across three classes with deliberately mixed runtime types under shared keys, plus
   * a {@code Root} linking to the four {@code Item}s. {@code Item} declares its two keys; {@code
   * Loose} declares nothing; {@code Anyp} exists but leaves {@code val} undeclared. The edge class
   * {@code chain} is unused here — {@code link} carries an undeclared {@code since}, which is the
   * property the edge-filter case needs to be undeclared.
   */
  private void seedMixedTypeFixture() {
    var item = session.createVertexClass("Item");
    item.createProperty("name", PropertyType.STRING);
    item.createProperty("num", PropertyType.INTEGER);
    session.createVertexClass("Loose");
    session.createVertexClass("Anyp");
    session.createVertexClass("Root");
    session.createEdgeClass("link");

    var alpha = graph.addVertex(T.label, "Item", "tag", "alpha", "name", "alpha", "num", 10);
    var bravo = graph.addVertex(T.label, "Item", "tag", "bravo", "name", "bravo", "num", 20);
    var charlie = graph.addVertex(T.label, "Item", "tag", "charlie", "name", "charlie", "num", 30);
    var delta = graph.addVertex(T.label, "Item", "tag", "delta", "name", "delta", "num", 40);
    graph.addVertex(T.label, "Loose", "tag", "loose_zulu", "name", "zulu");
    graph.addVertex(T.label, "Loose", "tag", "loose_num", "name", 99);
    graph.addVertex(T.label, "Anyp", "tag", "anyp_str", "val", "sierra");
    graph.addVertex(T.label, "Anyp", "tag", "anyp_num", "val", 50);
    var root = graph.addVertex(T.label, "Root", "tag", "root");

    // `since` is set only where it is meaningful and is declared nowhere, so the edge filter over it
    // is the undeclared-property shape. Two edges fall below 2025 and two above.
    root.addEdge("link", alpha, "since", 2020);
    root.addEdge("link", bravo, "since", 2024);
    root.addEdge("link", charlie, "since", 2030);
    root.addEdge("link", delta, "since", 2031);
    graph.tx().commit();
  }

  /**
   * Four {@code bond} edges out of one hub, three carrying a numeric {@code since} and one a String,
   * so one undeclared edge-property key holds two runtime types. Kept apart from {@link
   * #seedMixedTypeFixture} on purpose: the cases there pin exact row lists over {@code out("link")}
   * and over the whole vertex set, and a fifth link target would move several of them for reasons
   * unrelated to the guard.
   */
  private void seedCrossTypeEdgeFixture() {
    session.createVertexClass("Node");
    session.createEdgeClass("bond");

    var hub = graph.addVertex(T.label, "Node", "tag", "hub");
    var earlyOne = graph.addVertex(T.label, "Node", "tag", "early_one");
    var lateOne = graph.addVertex(T.label, "Node", "tag", "late_one");
    var lateTwo = graph.addVertex(T.label, "Node", "tag", "late_two");
    var wordy = graph.addVertex(T.label, "Node", "tag", "wordy");

    hub.addEdge("bond", earlyOne, "since", 2020);
    hub.addEdge("bond", lateOne, "since", 2030);
    hub.addEdge("bond", lateTwo, "since", 2031);
    // The String value is the whole point of this fixture: SQL ordering ranks it above 2025, the
    // TinkerPop comparator refuses to rank it against 2025 at all.
    hub.addEdge("bond", wordy, "since", "recently");
    graph.tx().commit();
  }

  /**
   * One vertex per runtime type on a class whose {@code v} key is undeclared, so nothing about the
   * stored types is knowable statically. The numeric values straddle 27 and 27.5 in both directions
   * so a numeric literal separates them; the three Strings straddle {@code "m"}; the two Booleans
   * and the two Dates are there to be excluded by every numeric and String literal.
   */
  private void seedOneValueOfEachType() {
    session.createVertexClass("Types");
    session.createVertexClass("Hub");
    session.createEdgeClass("holds");
    typesHub = graph.addVertex(T.label, "Hub", "tag", "hub");
    addTypedVertex("t_byte", (byte) 10);
    addTypedVertex("t_short", (short) 20);
    addTypedVertex("t_integer", 26);
    addTypedVertex("t_long", 30L);
    addTypedVertex("t_float", 27.5f);
    addTypedVertex("t_double", 40.0d);
    addTypedVertex("t_decimal", new BigDecimal("50"));
    addTypedVertex("t_string_lo", "alpha");
    addTypedVertex("t_string_mid", "m");
    addTypedVertex("t_string_hi", "zulu");
    addTypedVertex("t_bool_true", true);
    addTypedVertex("t_bool_false", false);
    addTypedVertex("t_date_early", new Date(1_000L));
    addTypedVertex("t_date_late", new Date(9_000_000_000L));
    graph.tx().commit();
  }

  /** Adds one {@code Types} vertex holding {@code value} and links the hub to it, so the hop-based
   *  unfolded shape below reaches it. */
  private void addTypedVertex(String tag, Object value) {
    typesHub.addEdge("holds", graph.addVertex(T.label, "Types", "tag", tag, "v", value));
    graph.tx().commit();
  }

  /**
   * 604 {@code Bulk} vertices — above the filter-selectivity estimator's early-bail threshold of
   * 100 — with an indexed {@code k}, and three {@code chain} edges so the shape has a hop to plan.
   */
  private void seedIndexedBulkClass() {
    var bulk = session.createVertexClass("Bulk");
    bulk.createProperty("k", PropertyType.STRING).createIndex(INDEX_TYPE.NOTUNIQUE);
    session.createEdgeClass("chain");

    var vertices = new java.util.ArrayList<Vertex>(604);
    for (var i = 0; i < 604; i++) {
      vertices
          .add(graph.addVertex(T.label, "Bulk", "tag", "b" + i, "k", String.format("k%03d", i)));
    }
    // The three hop targets hold `mixed` under two runtime types, so a post-hop range comparison
    // over it separates the guarded answer from the unguarded one.
    vertices.get(601).property("mixed", 30);
    vertices.get(602).property("mixed", "zz");
    vertices.get(603).property("mixed", 10);
    // The sources start at index 1, because k000 is excluded by the root's own k > "k000" filter and
    // an edge from it would make the hop target unreachable for a reason unrelated to the guard.
    for (var i = 1; i <= 3; i++) {
      vertices.get(i).addEdge("chain", vertices.get(600 + i));
    }
    graph.tx().commit();
  }

  /**
   * The partition shape: a range over {@code v} reached through the hub, so the container is one hop
   * past the {@code V()} and therefore unfolded. Written as a hop rather than at the root on purpose
   * — at the root the fold would answer it with SQL ordering and the rows would measure the fold's
   * comparator instead of the guard.
   */
  private GraphTraversal<?, ?> typesRange(P<?> predicate) {
    return graph.traversal().V().hasLabel("Hub").out("holds").has("v", predicate);
  }

  // ---------------------------------------------------------------------------
  // Assertions.
  // ---------------------------------------------------------------------------

  /**
   * Runs {@code shape} with the translator on and again off, asserting that the translated arm
   * engaged exactly one boundary step, that the native arm engaged none, that both arms returned
   * {@code expectedTags}, and hence that they agree. Pinning the expected tags rather than only
   * comparing the two arms is what stops an empty-on-both-sides regression from passing.
   *
   * <p>The off arm's boundary count is pinned at zero for the reason the sibling helpers in this
   * package pin it: the flag defaults on, and {@code setTranslatorEnabled} resolves its target
   * through {@code graphSession()}, so a write that landed on a handle the traversal does not read
   * would leave both arms translated and turn every case in this class into the guarded engine
   * compared against itself. The hand-written {@code expectedTags} would not catch it either — they
   * were derived from a run, so a run with both arms translated would have encoded translated
   * behaviour into them.
   */
  private void assertAgreesWithNative(
      String scenario, Supplier<GraphTraversal<?, ?>> shape, List<String> expectedTags) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = shape.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onTags = tagsOf(onAdmin.toList());

      setTranslatorEnabled(false);
      var offAdmin = shape.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offTags = tagsOf(offAdmin.toList());

      assertThat(boundaryOn)
          .as(scenario + " (translator on) must engage exactly one boundary step — a decline would "
              + "make the row comparison below trivially true")
          .isEqualTo(1);
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step — otherwise the "
              + "\"native\" arm is the translated one and the comparison below is self-agreement")
          .isZero();
      assertThat(offTags).as(scenario + " (native) rows").isEqualTo(expectedTags);
      assertThat(onTags).as(scenario + " (translated) rows").isEqualTo(expectedTags);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Asserts the shape declines (no boundary step with the translator on) and that both arms still
   * return the same rows.
   *
   * <p>The row half of this helper cannot fail on its own: a declined on arm <em>is</em> the native
   * pipeline, so the comparison is native against native. What carries the case is the decline
   * assertion, plus the off arm's zero-boundary pin (see {@link #assertAgreesWithNative} for why
   * that pin exists) and, at the call site, a control shape that does translate on the same fixture.
   *
   * <p>{@link Cardinality#MAY_BE_EMPTY} rather than the shared driver's default, and the opt-out is
   * measured rather than assumed: this helper's only shape compares stored values against an {@code
   * Instant}, which TinkerPop's comparator rejects for every one of them, so its native answer is
   * legitimately the empty list. Arming the non-emptiness pin here would redden a case whose empty
   * answer is the correct one. The call-site control is what keeps the empty result attributable to
   * the {@code Instant} rather than to a fixture that drifted empty.
   */
  private void assertDeclinesAndMatchesNative(
      String scenario, Supplier<GraphTraversal<?, ?>> shape) {
    support.assertEquivalent(
        scenario,
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        RangeTypeGuardEquivalenceTest::tagsOf,
        shape);
  }

  /**
   * Asserts that TinkerPop's optimisation strategies hoisted {@code shape}'s filter-only child to
   * top level and that {@code key}'s container then reached the fold, ending up inside a {@code
   * YTDBGraphStep}. Two assertions rather than one because they can fail independently: a surviving
   * wrapper means the inlining stopped, while an inlined container that stayed a separate {@code
   * HasStep} would mean the fold's own rule changed.
   *
   * <p>Driven with the translator off. Those strategies run before the provider stage the translator
   * occupies, so they behave identically either way, and an accepted translation would replace the
   * step list with a single boundary step and leave nothing to inspect.
   */
  private void assertInlinedIntoTheFold(
      String scenario, String key, Supplier<GraphTraversal<?, ?>> shape) {
    withTranslator(false, () -> {
      var admin = shape.get().asAdmin();
      admin.applyStrategies();
      var steps = admin.getSteps();
      assertThat(steps.stream().anyMatch(
          s -> s instanceof WhereTraversalStep<?> || s instanceof TraversalFilterStep<?>
              || s instanceof AndStep<?>))
          .as(scenario + " must leave no wrapper behind — a surviving wrapper would mean the "
              + "container never reached the fold")
          .isFalse();
      assertThat(steps.stream()
          .filter(YTDBGraphStep.class::isInstance)
          .map(s -> (YTDBGraphStep<?, ?>) s)
          .anyMatch(s -> s.getHasContainers().stream()
              .anyMatch(c -> key.equals(c.getKey()))))
          .as(scenario + ": the inlined " + key + " container must end up inside the graph step, "
              + "which is what makes its position a folded one")
          .isTrue();
    });
  }

  // ---------------------------------------------------------------------------
  // Plumbing.
  // ---------------------------------------------------------------------------

  /** Sorted {@code tag} values of the returned vertices (or of the returned edges' target, for a
   *  shape that ends on a vertex step). */
  private static List<String> tagsOf(List<?> results) {
    return results.stream()
        .map(v -> String.valueOf(((Vertex) v).<Object>value("tag")))
        .sorted()
        .toList();
  }

  /**
   * Renders the walk's alias filter that mentions {@code key}, with inline literals preserved.
   *
   * <p>Rendered with an empty parameter map rather than {@code toGenericStatement}, because the
   * latter collapses every string literal to {@code ?} — including the comparability-block names,
   * which are exactly what the caller needs to read. A bound comparison value renders as
   * {@code null} under an empty map, which is harmless here.
   */
  private String renderedAliasFilterMentioning(
      Supplier<GraphTraversal<?, ?>> shape, String key) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var result = GremlinStepWalker.production().walk(shape.get().asAdmin());
      assertThat(result).as("the shape must translate for it to have alias filters").isNotNull();
      var inputs = result.inputs();
      assertThat(inputs).as("a single-plan shape must carry MatchPlanInputs").isNotNull();
      for (var clause : inputs.aliasFilters().values()) {
        var rendered = new StringBuilder();
        clause.toString(Map.of(), rendered);
        if (rendered.indexOf(key) >= 0) {
          return rendered.toString();
        }
      }
      throw new AssertionError("no alias filter mentions " + key);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** Applies strategies with the translator on and renders the boundary step's compiled plan. */
  private String boundaryPlanText(Supplier<GraphTraversal<?, ?>> shape) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var admin = shape.get().asAdmin();
      admin.applyStrategies();
      var boundary = admin.getSteps().stream()
          .filter(YTDBMatchPlanStep.class::isInstance)
          .map(s -> (YTDBMatchPlanStep<?, ?>) s)
          .findFirst()
          .orElseThrow(() -> new AssertionError("expected a translated boundary step"));
      return boundary.getPlan().prettyPrint(0, 2);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** The alias the compiled plan roots at, read off the {@code SET <alias>} line. */
  private static String planRootAlias(String planText) {
    var lines = planText.lines().toList();
    for (var i = 0; i < lines.size() - 1; i++) {
      if ("+ SET".equals(lines.get(i).strip())) {
        return lines.get(i + 1).strip();
      }
    }
    throw new AssertionError("plan names no root alias on a SET line:\n" + planText);
  }

  private void withTranslator(boolean enabled, Runnable body) {
    support.withTranslator(enabled, body);
  }

  private boolean translatorEnabled() {
    return support.translatorEnabled();
  }

  private void setTranslatorEnabled(boolean enabled) {
    support.setTranslatorEnabled(enabled);
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }
}
