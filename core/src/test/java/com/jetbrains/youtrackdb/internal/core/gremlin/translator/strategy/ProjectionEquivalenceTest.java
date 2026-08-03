package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.WithOptions;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence for the projection and aggregate terminators
 * ({@code values} / {@code valueMap} / {@code elementMap} / {@code select} / {@code count} /
 * {@code mean} / {@code groupCount} / order / range / dedup), and for the element-returning
 * {@code properties(key)} form — which projects a {@code VertexProperty} rather than its payload and
 * therefore declines almost everywhere {@code values(key)} translates. Multiset equality is on a
 * string-canonicalised payload so Maps / scalars / lists compare without relying on Vertex RID
 * sorting from {@link EdgeTraversalEquivalenceTest}.
 */
public class ProjectionEquivalenceTest extends GraphBaseTest {

  private enum Recognition {
    RECOGNIZED, DECLINED
  }

  /**
   * Per-scenario cardinality opt-in for the anti-vacuity guard. A seeded {@code RECOGNIZED} case
   * must return rows ({@link #NON_EMPTY}) or the translator-on / translator-off multiset equality
   * holds vacuously over two empty lists and verifies nothing. Empty-by-design cases (empty-input
   * {@code sum}/{@code min}/{@code max}/{@code mean}, which drop the null aggregate row) opt out
   * with {@link #MAY_BE_EMPTY}. This is deliberately not a blanket {@code isNotEmpty}: the suite
   * hosts empty-result {@code RECOGNIZED} cases on purpose.
   */
  private enum Cardinality {
    NON_EMPTY, MAY_BE_EMPTY
  }

  /**
   * Absent vs present-null for {@code valueMap}: native and translated both omit absent keys and
   * include present-null as {@code [null]}.
   */
  @Test
  public void valueMap_absentVsPresentNull_matchNative() {
    var withNull = graph.addVertex(T.label, "Person", "name", "HasNull");
    withNull.property("foo", null);
    graph.addVertex(T.label, "Person", "name", "Absent");
    graph.tx().commit();

    assertEquivalent(
        "g.V().valueMap(foo)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().valueMap("foo"));
  }

  /**
   * {@code values("foo")} emits a null traverser for present-null and nothing for absent — the
   * {@code dropOnAbsent} path.
   */
  @Test
  public void values_absentVsPresentNull_matchNative() {
    var withNull = graph.addVertex(T.label, "Person", "name", "HasNull");
    withNull.property("foo", null);
    graph.addVertex(T.label, "Person", "name", "Absent");
    graph.tx().commit();

    assertEquivalent(
        "g.V().values(foo)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("foo"));
  }

  // ---------------------------------------------------------------------------
  // properties(key): the element form declines where the value would be read.
  // AdjacentToIncidentStrategy rewrites a written values(key) into the element
  // form wherever the payload is unread, so these cases are written as values()
  // and reach the recogniser as PropertyType.PROPERTY. The two escapes that keep
  // accepting are a count-consumed step and the end step of a combinator child;
  // everything else declines, and the decline is what the row sets here pin.
  // ---------------------------------------------------------------------------

  /**
   * The element-returning {@code properties(key)} form declines while {@code values(key)} on the same
   * seeded graph still translates, and with the translator on the shape yields a {@code Property}
   * element rather than its payload. The three assertions are one claim each and none is redundant:
   * the decline is carried by the boundary-step count inside {@code assertEquivalent}, which is the
   * discriminator here — a regression that projected the value would engage a boundary step and fail
   * there before any payload comparison. The {@code values} case is the positive control that the
   * withdrawal is specific to the element form. The element-type assertion runs only once the shape
   * has been proved untranslated, so it documents what native yields rather than guarding the
   * equality.
   */
  @Test
  public void propertiesElementForm_declines_whileValuesStillTranslates() {
    var marko = graph.addVertex(T.label, "Person", "name", "marko");
    marko.property("friendWeight", 1.5);
    graph.tx().commit();

    assertEquivalent(
        "g.V().properties(friendWeight)",
        Recognition.DECLINED,
        () -> graph.traversal().V().properties("friendWeight"));

    // Positive control on the same graph: the value form is unaffected and still translates.
    assertEquivalent(
        "g.V().values(friendWeight)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("friendWeight"));

    withTranslatorOn(
        () -> assertThat(graph.traversal().V().properties("friendWeight").next())
            .as("properties(key) must yield the VertexProperty element, not its payload")
            .isInstanceOf(Property.class));
  }

  /**
   * A main-line meta-property read through {@code properties(key).has(metaKey, value)} declines to
   * native. This is the shape TinkerPop's {@code addV} meta-property scenarios verify their mutation
   * with, and it returned nothing translated while returning the property element natively: the
   * element-returning step was projected as a field access, so the following {@code has} tested a
   * {@code Double} for a meta-property it cannot carry. The combinator spellings of the same read are
   * in {@code metaPropertyFilterInSubWalk_declinesInEveryCombinator}.
   */
  @Test
  public void metaPropertyFilterThroughProperties_declinesToNative() {
    seedMetaPropertyGraph();

    assertEquivalent(
        "g.V().properties(friendWeight).has(acl, private)",
        Recognition.DECLINED,
        () -> graph.traversal().V().properties("friendWeight").has("acl", "private"));

    // Non-vacuity: the shape returns a row on both arms, so the equality is over one element and not
    // over two empty lists. The fixture's third vertex carries acl as a top-level property, so a
    // plan that read acl off the vertex would select a different element rather than the same one.
    withTranslatorOn(
        () -> assertThat(
            graph.traversal().V().properties("friendWeight").has("acl", "private").toList())
            .as("the meta-property filter must select the one seeded property element")
            .hasSize(1));
  }

  /**
   * {@code group().by(properties(key))} declines, where a value-keyed translation silently merged
   * buckets. Native keys on the {@code VertexProperty} element, so two elements carrying the same
   * key and value are two distinct keys and two buckets; keying on the payload collapses them into
   * one. The Cucumber suite never exercised this shape, so the count-based residue could not have
   * caught it — the on/off comparison is the only net. {@code by(values(key))} is the positive
   * control.
   */
  @Test
  public void groupByPropertiesElementForm_declines_whileByValuesStillTranslates() {
    graph.addVertex(T.label, "Software", "name", "lop", "lang", "java");
    graph.addVertex(T.label, "Software", "name", "ripple", "lang", "java");
    graph.tx().commit();

    assertEquivalent(
        "g.V().group().by(properties(lang))",
        Recognition.DECLINED,
        () -> graph.traversal().V().group().by(__.properties("lang")));

    assertEquivalent(
        "g.V().group().by(values(lang))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().group().by(__.values("lang")));

    // Non-vacuity: native really does split the two same-valued property elements into two buckets,
    // which is the divergence the decline exists for. Without this the decline could be guarding
    // nothing.
    withTranslatorOff(
        () -> assertThat((Map<?, ?>) graph.traversal().V().group().by(__.properties("lang")).next())
            .as("native keys on the property element, so two same-valued elements are two "
                + "buckets")
            .hasSize(2));
  }

  /**
   * A count-consumed element form still translates. {@code values(age).count()} reaches the recogniser
   * as {@code PropertyType.PROPERTY} only because {@code AdjacentToIncidentStrategy} rewrote it, so an
   * unconditional element-form decline would silently stop translating a shape callers do write. One
   * property element per value leaves the row count unchanged, which is what makes the position safe.
   * {@code countAfterValues_countsOnlyKeyBearers} carries the hand-computed native answer for the same
   * traversal; this case pins that the rewritten form reaches the gate and is accepted.
   */
  @Test
  public void countConsumedPropertiesForm_stillTranslates() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertRewrittenToElementForm(
        "g.V().values(age).count()", () -> graph.traversal().V().values("age").count());

    assertEquivalent(
        "g.V().values(age).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").count());
  }

  /**
   * A sub-walk capture of the element form still translates, and the presence filter the projection
   * stands for survives the capture. {@code and(values(a), values(b))} is the shortest shape that
   * reaches this escape: each child routes through {@code walkChild}, where the projection is
   * discarded on commit and a pattern conjunct is the only carrier left for the drop
   * {@code values(key)} performs. Expressing that drop as result shaping instead — which the sub-walk
   * adapter swallows — made the AND a no-op that returned every seeded vertex against native's one.
   *
   * <p>The single-step {@code where(values(k))} spelling cannot serve as this escape's control: the
   * {@code has(key)} desugar claims it before the child is ever walked, which is what
   * {@code whereValuesPresence_matchesNativeThroughHasKeyDesugar} pins instead.
   */
  @Test
  public void subWalkPropertiesForm_stillTranslatesAndKeepsThePresenceFilter() {
    seedNameAgeNickGraph();

    assertRewrittenToElementForm(
        "g.V().and(values(age), values(name))",
        () -> graph.traversal().V().and(__.values("age"), __.values("name")));

    assertEquivalent(
        "g.V().and(values(age), values(name))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.values("age"), __.values("name")));

    // Non-vacuity: native keeps one of the three seeded vertices, so the equality above is over a
    // filtered row set. An AND that committed no conjunct would return all three and still be a
    // single-boundary-step translation.
    withTranslatorOff(
        () -> assertThat(graph.traversal().V().and(__.values("age"), __.values("name")).toList())
            .as("native keeps only the vertex carrying both properties")
            .hasSize(1));
  }

  /**
   * {@code where(values(key))} filters on presence and matches native through the {@code has(key)}
   * desugar, not through the sub-walk escape: {@link TraversalFilterStepRecogniser} claims a filter
   * child that is exactly one single-key {@code PropertiesStep} and maps it straight to
   * {@code IS DEFINED}, so the child never reaches {@code walkChild}. Measured by disabling the
   * escape, which leaves this case green. It is kept as a pin on that desugar; the escape's own
   * control is {@code subWalkPropertiesForm_stillTranslatesAndKeepsThePresenceFilter}.
   */
  @Test
  public void whereValuesPresence_matchesNativeThroughHasKeyDesugar() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().where(values(age))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().where(__.values("age")));

    // Non-vacuity: the presence filter selects two of the three seeded vertices, so the equality
    // above is neither over the whole scan nor over an empty result.
    withTranslatorOff(
        () -> assertThat(graph.traversal().V().where(__.values("age")).toList())
            .as("native keeps the two age-bearing vertices")
            .hasSize(2));
  }

  /**
   * A meta-property read inside a combinator child declines in all four spellings that reach the
   * sub-walk escape. The escape covers the child's end step only: a step after the projection reads
   * the payload and commits its own filter to the parent on the vertex alias, so a translated
   * {@code where(properties(friendWeight).has(acl, private))} tested each vertex's own {@code acl} and
   * returned {@code peter} — who carries a top-level {@code acl} and no {@code friendWeight} — where
   * native returns {@code marko}, whose {@code acl} lives on the property. The row sets were disjoint
   * in both directions, so no count-based check could have found it either. The value form in the same
   * combinator is the positive control: the decline is specific to a payload-reading successor and not
   * a withdrawal of sub-walk projections.
   */
  @Test
  public void metaPropertyFilterInSubWalk_declinesInEveryCombinator() {
    seedMetaPropertyGraph();

    assertEquivalent(
        "g.V().where(properties(friendWeight).has(acl, private))",
        Recognition.DECLINED,
        () -> graph.traversal().V().where(__.properties("friendWeight").has("acl", "private")));
    assertEquivalent(
        "g.V().filter(properties(friendWeight).has(acl, private))",
        Recognition.DECLINED,
        () -> graph.traversal().V().filter(__.properties("friendWeight").has("acl", "private")));
    assertEquivalent(
        "g.V().and(properties(friendWeight).has(acl, private))",
        Recognition.DECLINED,
        () -> graph.traversal().V().and(__.properties("friendWeight").has("acl", "private")));
    assertEquivalent(
        "g.V().not(properties(friendWeight).has(acl, private))",
        Recognition.DECLINED,
        () -> graph.traversal().V().not(__.properties("friendWeight").has("acl", "private")));

    // Positive control: the same combinator over the value form still translates.
    assertEquivalent(
        "g.V().and(values(friendWeight), values(name))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.values("friendWeight"), __.values("name")));

    // The fixture separates the two placements of the filter: native reads acl off the property
    // element, so it selects marko and not peter. Without the third vertex a plan that pushed the
    // filter onto the vertex would be indistinguishable from one that read the property element.
    withTranslatorOff(
        () -> assertThat(
            graph
                .traversal()
                .V()
                .where(__.properties("friendWeight").has("acl", "private"))
                .values("name")
                .toList())
            .as("native reads acl off the property element, not off the vertex")
            .containsExactly("marko"));
  }

  // ---------------------------------------------------------------------------
  // A captured child contributes one thing to its parent: whether it emitted a
  // traverser. So the presence conjunct a sub-walk values(key) stands for is
  // right only where the child's remaining steps leave the drop intact. The
  // three cases below are the three answers — preserved, destroyed, and not
  // classified — and each pins the native row set the answer has to reproduce.
  // ---------------------------------------------------------------------------

  /**
   * A drop-preserving successor inside a captured child still gets the presence conjunct.
   * {@code dedup()} cannot turn an empty stream into output, so a vertex without {@code age} produces
   * no traverser in the child either way and the {@code and} must drop it. The conjunct was gated on
   * the projection ending its child's walk, which is false here, so both spellings translated with an
   * empty filter map and returned every seeded vertex. The second spelling adds a sibling child to
   * show the conjunct composes rather than replacing what the other child contributed.
   */
  @Test
  public void subWalkValuesBeforeDedup_keepsThePresenceFilter() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().and(values(age).dedup())",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.values("age").dedup()));
    assertEquivalent(
        "g.V().and(values(age).dedup(), values(name))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.values("age").dedup(), __.values("name")));

    // Non-vacuity: native filters on both, and to different sizes, so neither equality above is over
    // the whole scan and the two-child case is not a restatement of the one-child case.
    withTranslatorOff(
        () -> {
          assertThat(graph.traversal().V().and(__.values("age").dedup()).toList())
              .as("native keeps the two age-bearing vertices of the three seeded")
              .hasSize(2);
          assertThat(
              graph.traversal().V().and(__.values("age").dedup(), __.values("name")).toList())
              .as("native keeps only the vertex carrying both properties")
              .hasSize(1);
        });
  }

  /**
   * A {@code count()} successor inside a captured child gets no conjunct, because it destroys the
   * drop: native counts an empty stream as {@code 0} and emits it, so a vertex without {@code age}
   * survives the child and the {@code and} keeps it. Contributing the presence conjunct here would
   * filter rows native returns, which is why the classification is three-way and not "successor or
   * no successor".
   */
  @Test
  public void subWalkValuesBeforeCount_keepsEveryElement() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().and(values(age).count())",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.values("age").count()));

    // Non-vacuity in the opposite direction from the dedup case: this shape must filter nothing, so
    // the guard is that native returns every seeded vertex. A conjunct leaking in would return two.
    withTranslatorOff(
        () -> assertThat(graph.traversal().V().and(__.values("age").count()).toList())
            .as("count() emits 0 for the age-less vertices, so native keeps all three")
            .hasSize(3));
  }

  /**
   * An unclassified successor inside a captured child declines the whole walk. A slice selects by
   * position — {@code limit(0)} empties every stream and {@code skip(n)} empties a stream of
   * {@code n} values — so it preserves the drop only for some bounds; {@code order()} carries
   * comparator modulators that read properties of their own and commit their own conjuncts. Both
   * translated before the classification existed and both disagreed with native, so the decline
   * costs no shape that was answering correctly.
   */
  @Test
  public void subWalkValuesBeforeSliceOrOrder_declinesToNative() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().and(values(age).limit(1))",
        Recognition.DECLINED,
        () -> graph.traversal().V().and(__.values("age").limit(1)));
    assertEquivalent(
        "g.V().and(values(age).order())",
        Recognition.DECLINED,
        () -> graph.traversal().V().and(__.values("age").order()));

    // Non-vacuity: native filters to two of the three seeded vertices, so the decline is guarding a
    // real divergence — the accepting translation returned all three.
    withTranslatorOff(
        () -> assertThat(graph.traversal().V().and(__.values("age").limit(1)).toList())
            .as("native keeps the two age-bearing vertices")
            .hasSize(2));
  }

  /**
   * A count with a slice between it and the projection declines in both its spellings — as written,
   * and as {@code CountStrategy} produces it from {@code count().is(gt(n))}. The element-form gate
   * sees the slice at the successor position and declines there; without it the count assembler's
   * cardinality gate declines one step later. This pins the claim the gate's own comment makes about
   * the position, so an {@code IsStep} recogniser landing without the gate extension the comment asks
   * for turns this red rather than silently mistranslating.
   */
  @Test
  public void countAfterValuesWithInterveningSlice_declinesToNative() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().values(age).limit(1).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().values("age").limit(1).count());
    assertEquivalent(
        "g.V().values(age).count().is(gt(1))",
        Recognition.DECLINED,
        () -> graph.traversal().V().values("age").count().is(P.gt(1L)));

    // Non-vacuity: the two shapes must return different answers, so a decline that quietly became a
    // shared count(*) over the unfiltered pattern could not satisfy both.
    withTranslatorOff(
        () -> {
          assertThat(graph.traversal().V().values("age").limit(1).count().next())
              .as("the slice cuts the two age values to one before the count")
              .isEqualTo(1L);
          assertThat(graph.traversal().V().values("age").count().is(P.gt(1L)).toList())
              .as("two age values are more than one, so the count survives its own filter")
              .containsExactly(2L);
        });
  }

  // --- Main-line projection, aggregate and result-shaping terminators -------------------------

  /** {@code elementMap("name")} matches native id/label/property maps. */
  @Test
  public void elementMap_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.tx().commit();

    assertEquivalent(
        "g.V().elementMap(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().elementMap("name"));
  }

  /** {@code select("v")} after {@code as("v")} returns the same vertex multiset as native. */
  @Test
  public void selectBoundLabel_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().as(v).select(v)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("v").select("v"));
  }

  /** Empty-input {@code count()} emits {@code 0L} on both paths. */
  @Test
  public void count_empty_emitsZero() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().has(name, nobody).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").count());
  }

  /** Empty-input {@code sum()} emits no traverser ({@code dropNullRows}). */
  @Test
  public void sum_empty_emitsNothing() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    // Zero matched vertices → SQL aggregate null cell → dropNullRows drops the row. Empty by design,
    // so it opts out of the non-empty guard.
    assertEquivalent(
        "g.V().has(name, nobody).values(age).sum()",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().has("name", "nobody").values("age").sum());
  }

  /** Non-empty {@code count()} matches native. */
  @Test
  public void count_seeded_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().count());
  }

  /** {@code groupCount().by("name")} matches native map (single traverser). */
  @Test
  public void groupCount_byName_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().groupCount().by("name"));
  }

  /** Bare {@code group()} keys the map by Vertex (value = singleton vertex list), matching native. */
  @Test
  public void group_bare_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().group()", Recognition.RECOGNIZED, () -> graph.traversal().V().group());
  }

  /** {@code group().by(name)} keys the map by the property value, matching native. */
  @Test
  public void group_byName_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().group().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().group().by("name"));
  }

  /** Bare {@code groupCount()} keys the map by Vertex, matching native (guards the RID→Vertex fix). */
  @Test
  public void groupCount_bare_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().groupCount()", Recognition.RECOGNIZED, () -> graph.traversal().V().groupCount());
  }

  /**
   * Seeded {@code sum}/{@code min}/{@code max}/{@code mean} match native values (exercises all
   * arms). The mean arm is coverage of the plumbing only: ages 10, 20 and 30 average to an integral
   * 20, which the canonicaliser folds to the same payload {@code avg}'s integer division produces,
   * so this arm cannot tell the two aggregates apart. {@link
   * #meanOverIntegerProperty_dividesInFloatingPoint} carries that discrimination on a fixture whose
   * ages do not divide evenly.
   */
  @Test
  public void numericAggregates_seeded_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 10);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Carol", "age", 30);
    graph.tx().commit();

    assertEquivalent("g.V().values(age).sum()", Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").sum());
    assertEquivalent("g.V().values(age).min()", Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").min());
    assertEquivalent("g.V().values(age).max()", Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").max());
    assertEquivalent("g.V().values(age).mean()", Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").mean());
  }

  /** Multi-label {@code select(a,b)} emits a two-entry map (unwrapSingletonMap=false), matching native. */
  @Test
  public void selectMultiLabel_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().as(a).as(b).select(a,b)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("a").as("b").select("a", "b"));
  }

  /**
   * {@code dedup()} after {@code values(k)} declines to native: a RETURN DISTINCT over the boundary
   * presence column deduped on (entity, value) and the unique entity defeated it. Native dedups the
   * projected names; the payloads must match.
   */
  @Test
  public void valuesDedup_declinesToNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().values(name).dedup()",
        Recognition.DECLINED,
        () -> graph.traversal().V().values("name").dedup());
  }

  /** {@code valueMap(k).dedup()} likewise declines to native (map output type, not ELEMENT). */
  @Test
  public void valueMapDedup_declinesToNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().valueMap(name).dedup()",
        Recognition.DECLINED,
        () -> graph.traversal().V().valueMap("name").dedup());
  }

  /** {@code order().by("name")} matches native order for a deterministic seed. */
  @Test
  public void orderByName_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(name).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name"));
  }

  /** {@code limit(2)} after order matches native. */
  @Test
  public void orderLimit_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(name).limit(2).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").limit(2).values("name"));
  }

  /** {@code dedup()} matches native vertex multiset. */
  @Test
  public void dedup_matchNative() {
    var a = graph.addVertex(T.label, "Person", "name", "Alice");
    var b = graph.addVertex(T.label, "Person", "name", "Bob");
    a.addEdge("knows", b);
    a.addEdge("knows", b);
    graph.tx().commit();

    assertEquivalent(
        "g.V().out(knows).dedup()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").dedup());
  }

  /**
   * Named {@code dedup("v")} on the current boundary keeps ELEMENT emission and matches native
   * (DISTINCT without rewriting RETURN under the user alias).
   */
  @Test
  public void namedDedup_currentBoundary_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().as(v).dedup(v)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("v").dedup("v"));
  }

  /**
   * {@code dedup().by("name")} declines to native — MATCH cannot DISTINCT-ON a property while
   * still emitting the current element.
   */
  @Test
  public void dedupByName_declinesToNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().dedup().by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V().dedup().by("name"));
  }

  /**
   * Named dedup on a prior path label declines to native (unique-by-{@code a}, emit-{@code b} is
   * not MATCH {@code DISTINCT} on RETURN).
   */
  @Test
  public void namedDedup_priorLabel_declinesToNative() {
    var a = graph.addVertex(T.label, "Person", "name", "Alice");
    var b1 = graph.addVertex(T.label, "Person", "name", "Bob");
    var b2 = graph.addVertex(T.label, "Person", "name", "Carol");
    a.addEdge("knows", b1);
    a.addEdge("knows", b2);
    graph.tx().commit();

    assertEquivalent(
        "g.V().as(a).out(knows).as(b).dedup(a)",
        Recognition.DECLINED,
        () -> graph.traversal().V().as("a").out("knows").as("b").dedup("a"));
  }

  // ---------------------------------------------------------------------------
  // B1 — a reducing / grouping terminator after a captured limit / skip / dedup
  // now declines to native (MATCH applies SKIP / LIMIT / DISTINCT after the
  // aggregate, Gremlin before it). Each case asserts the decline (no boundary
  // step, on/off parity) and the hand-computed native answer, so the parity is
  // not vacuous.
  // ---------------------------------------------------------------------------

  /** {@code limit(5).count()} declines to native; native counts the first 5 of 8 vertices. */
  @Test
  public void limit5Count_declinesToNative() {
    seedPeople(8);
    assertEquivalent(
        "g.V().limit(5).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().limit(5).count());
    assertThat(graph.traversal().V().limit(5).count().next()).isEqualTo(5L);
  }

  /** {@code skip(2).count()} declines to native; native counts the 6 remaining of 8 vertices. */
  @Test
  public void skip2Count_declinesToNative() {
    seedPeople(8);
    assertEquivalent(
        "g.V().skip(2).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().skip(2).count());
    assertThat(graph.traversal().V().skip(2).count().next()).isEqualTo(6L);
  }

  /** {@code range(1,3).count()} declines to native; native counts the 2 vertices in {@code [1,3)}. */
  @Test
  public void range1to3Count_declinesToNative() {
    seedPeople(8);
    assertEquivalent(
        "g.V().range(1,3).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().range(1, 3).count());
    assertThat(graph.traversal().V().range(1, 3).count().next()).isEqualTo(2L);
  }

  /**
   * {@code out(knows).dedup().count()} declines to native. A parallel edge makes Bob reachable
   * twice, so {@code out} yields {Bob, Bob, Carol}; native dedups to {Bob, Carol} before counting
   * (2). A mistranslation to {@code RETURN DISTINCT count(*)} would count the duplicates (3).
   */
  @Test
  public void outDedupCount_declinesToNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", bob); // parallel edge → Bob reached twice by out()
    alice.addEdge("knows", carol);
    graph.tx().commit();

    assertEquivalent(
        "g.V().out(knows).dedup().count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("knows").dedup().count());
    assertThat(graph.traversal().V().out("knows").dedup().count().next()).isEqualTo(2L);
  }

  /**
   * {@code limit(2).values(age).mean()} declines to native. All four ages are 30, so the mean is a
   * deterministic {@code 30.0} regardless of which two vertices the limit picks.
   */
  @Test
  public void limit2ValuesMean_declinesToNative() {
    for (var i = 0; i < 4; i++) {
      graph.addVertex(T.label, "Person", "name", "P" + i, "age", 30);
    }
    graph.tx().commit();

    assertEquivalent(
        "g.V().limit(2).values(age).mean()",
        Recognition.DECLINED,
        () -> graph.traversal().V().limit(2).values("age").mean());
    assertThat(graph.traversal().V().limit(2).values("age").mean().next()).isEqualTo(30.0);
  }

  // ---------------------------------------------------------------------------
  // TC1 / TC2 — empty-input aggregate and group parity.
  // ---------------------------------------------------------------------------

  /**
   * TC1: empty-input {@code min()}/{@code max()}/{@code mean()} emit no traverser on both paths
   * ({@code dropNullRows}), matching native — the companions to the already-covered empty {@code
   * sum()}. Empty by design, so they opt out of the non-empty guard.
   */
  @Test
  public void emptyInputMinMaxMean_emitNothing() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    assertEquivalent("g.V().has(name, nobody).values(age).min()", Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().has("name", "nobody").values("age").min());
    assertEquivalent("g.V().has(name, nobody).values(age).max()", Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().has("name", "nobody").values("age").max());
    assertEquivalent("g.V().has(name, nobody).values(age).mean()", Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().has("name", "nobody").values("age").mean());
  }

  /**
   * TC2: empty-input {@code group().by(name)} / {@code groupCount().by(name)} emit a single empty
   * map {@code [{}]} on both paths (the accumulate-map drain of zero GROUP BY rows), matching
   * native. The single empty map is a non-empty result, so these keep the default non-empty guard.
   */
  @Test
  public void emptyInputGroup_emitsSingleEmptyMap() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().has(name, nobody).group().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").group().by("name"));
    assertEquivalent(
        "g.V().has(name, nobody).groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").groupCount().by("name"));
  }

  // --- by(key) is filtering: an element without the key is dropped, not grouped under null ------

  /**
   * Seeds the split every {@code by(key)} case below needs: two vertices carrying {@code age} and
   * two that do not. Without the split a presence conjunct is indistinguishable from no conjunct,
   * and every assertion here would pass vacuously.
   */
  private void seedAgedAndAgeless() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.addVertex(T.label, "Person", "name", "Nemo");
    graph.tx().commit();
  }

  /**
   * {@code order().by("age")} emits only the two vertices that carry {@code age}. Gremlin's
   * modulator is a traversal, so an element with no {@code age} produces no value and its traverser
   * is dropped; a plain SQL {@code ORDER BY} would keep all four and sort the missing ones as null.
   */
  @Test
  public void orderByMissingKey_dropsElementLikeNative() {
    seedAgedAndAgeless();

    // Ordered comparison: after the drop only Bob (25) and Alice (30) survive and their ages
    // differ, so the sorted payload is deterministic on both paths and the sort is asserted rather
    // than sorted away.
    assertEquivalentOrdered(
        "g.V().order().by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("age"));
  }

  /**
   * The same drop has to reach a following {@code count()}, which reads the filtered pattern rather
   * than the projected stream: {@code order().by("age").count()} is 2, not 4.
   */
  @Test
  public void countAfterOrderByMissingKey_countsOnlyKeyBearers() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().order().by(age).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("age").count());
  }

  /** {@code select("a").by("age")} drops the elements without {@code age} the same way. */
  @Test
  public void selectByMissingKey_dropsElementLikeNative() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().as(a).select(a).by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("a").select("a").by("age"));
  }

  /**
   * The multi-label {@code select(a, n).by(age).by(name)} form drops the elements without
   * {@code age} too.
   *
   * <p>{@code as("a", "n")} labels one element twice, so both labels resolve to the same internal
   * alias. The fixture therefore pins the drop only — it cannot show <em>which</em> alias each
   * modulator's conjunct targets, because there is only one alias to target.
   */
  @Test
  public void multiLabelSelectByMissingKey_dropsElementLikeNative() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().as(a, n).select(a, n).by(age).by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("a", "n").select("a", "n").by("age").by("name"));
  }

  /**
   * {@code group().by("age")} and {@code groupCount().by("age")} carry no {@code null} bucket. This
   * is the one shape where the drop cannot be done after the fact — SQL forms the bucket during
   * aggregation, so the conjunct has to filter the rows that feed it.
   */
  @Test
  public void groupByMissingKey_hasNoNullBucket() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().group().by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().group().by("age"));
    assertEquivalent(
        "g.V().groupCount().by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().groupCount().by("age"));
  }

  /**
   * The positive control for the drop: under the default {@code ProductiveByStrategy} a
   * {@code by(key)} yields {@code null} instead of dropping, so the {@code null} bucket is the
   * correct answer and the conjunct must not be added. Without this case the four assertions above
   * would be equally green if the conjunct were added unconditionally, which is the bug this pins.
   *
   * <p>The {@code order()} arm compares multisets rather than sequences on purpose: null-valued
   * rows survive here, and where {@code ORDER BY} places a null is a known divergence between MATCH
   * and the native pipeline. The sibling {@code orderByMissingKey_dropsElementLikeNative} has no
   * nulls left after the drop, so it asserts the stronger ordered form.
   */
  @Test
  public void productiveByStrategy_keepsTheNullBucket() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.withStrategies(ProductiveByStrategy).V().groupCount().by(age)",
        Recognition.RECOGNIZED,
        () -> graph
            .traversal()
            .withStrategies(ProductiveByStrategy.instance())
            .V()
            .groupCount()
            .by("age"));
    assertEquivalent(
        "g.withStrategies(ProductiveByStrategy).V().order().by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().withStrategies(ProductiveByStrategy.instance()).V().order()
            .by("age"));
  }

  /**
   * A configured key set inverts the default: {@code ProductiveByStrategy} wraps — and so makes
   * productive — every {@code by(key)} whose key the caller did <em>not</em> list, because a listed
   * key is asserted to be present on every element already. So under
   * {@code productiveKeys("age")} the {@code by("age")} keeps Gremlin's drop and needs the conjunct,
   * while {@code by("name")} becomes productive and must not get one. Reading the membership test
   * the other way round is green under {@link #productiveByStrategy_keepsTheNullBucket}, whose
   * default instance has an empty key set that short-circuits before the membership test runs.
   */
  @Test
  public void productiveByStrategy_configuredKeysInvertPerKey() {
    seedAgedAndAgeless();
    // A second name-less vertex so the unlisted-key arm has a null bucket to keep.
    graph.addVertex(T.label, "Person", "age", 41);
    graph.tx().commit();

    var strategy = ProductiveByStrategy.build().productiveKeys("age").create();

    // "age" is listed → not wrapped → still drops → the conjunct is required, no null bucket.
    assertEquivalent(
        "g.withStrategies(ProductiveByStrategy(age)).V().groupCount().by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().withStrategies(strategy).V().groupCount().by("age"));
    // "name" is not listed → wrapped → yields null instead of dropping → the null bucket stays.
    assertEquivalent(
        "g.withStrategies(ProductiveByStrategy(age)).V().groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().withStrategies(strategy).V().groupCount().by("name"));
  }

  /**
   * {@code ProductiveByStrategy} cannot reach a {@code values(key)} step — it wraps
   * {@code ByModulating} traversal parents, and a {@code PropertiesStep} is neither — so the drop a
   * {@code values(key)} performs in its own right survives the strategy and its restated conjunct
   * must survive with it. Gating the projection-side conjunct on the same productive-by answer as
   * the modulator-side one makes {@code values("foo").sum()} emit a zero where Gremlin emits no
   * traverser at all.
   */
  @Test
  public void productiveByStrategy_doesNotReachTheValuesDrop() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.withStrategies(ProductiveByStrategy).V().values(age).groupCount()",
        Recognition.RECOGNIZED,
        () -> graph
            .traversal()
            .withStrategies(ProductiveByStrategy.instance())
            .V()
            .values("age")
            .groupCount());
    assertEquivalent(
        "g.withStrategies(ProductiveByStrategy).V().values(foo).sum()",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph
            .traversal()
            .withStrategies(ProductiveByStrategy.instance())
            .V()
            .values("foo")
            .sum());
  }

  // --- valueMap / elementMap: tokens do not decide list wrapping, and no keys means decline ------

  /**
   * {@code valueMap(true, "name")} wraps its property values in singleton lists and emits the id /
   * label tokens. Asking for tokens does not turn a {@code valueMap} into an {@code elementMap}, so
   * the wrapping stays; deriving it from the token bits emitted {@code name=Alice} where native
   * emits {@code name=[Alice]}.
   */
  @Test
  public void valueMapWithTokens_stillWrapsValuesInLists() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    assertEquivalent(
        "g.V().valueMap(true, name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().valueMap(true, "name"));
  }

  /**
   * A key-less {@code valueMap} / {@code elementMap} projects every property, which needs a
   * schema-driven enumeration this cut does not have, so all four spellings decline. Requesting the
   * tokens does not supply a key list: a plan built from the token columns alone returns
   * {@code {id, label}} per element and silently loses every property.
   */
  @Test
  public void keylessValueMapAndElementMap_decline() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    assertEquivalent(
        "g.V().valueMap()", Recognition.DECLINED, () -> graph.traversal().V().valueMap());
    assertEquivalent(
        "g.V().valueMap(true)", Recognition.DECLINED, () -> graph.traversal().V().valueMap(true));
    assertEquivalent(
        "g.V().elementMap()", Recognition.DECLINED, () -> graph.traversal().V().elementMap());
    // The fourth spelling is the one the deleted derivation keyed off: with(WithOptions.tokens) is
    // the second route to a non-zero token bit set on a step carrying no key list, so under
    // isElementMap = tokens != 0 it skipped the empty-key decline exactly as valueMap(true) did.
    assertEquivalent(
        "g.V().valueMap().with(WithOptions.tokens)",
        Recognition.DECLINED,
        () -> graph.traversal().V().valueMap().with(WithOptions.tokens));
  }

  // --- terminators that must read the value a preceding values(key) projected --------------------

  /**
   * {@code values("name").order()} sorts the names. A bare {@code order()} defaults to the element
   * RID, which after a value projection sorts the emitted strings into insertion order and calls it
   * sorted — green on any fixture whose insertion order happens to be alphabetical, which is why
   * the seed below is deliberately not.
   */
  @Test
  public void orderAfterValues_sortsByTheValueNotTheRid() {
    graph.addVertex(T.label, "Person", "name", "Zoe");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Mallory");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().values(name).order()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("name").order());
  }

  /** {@code values("name").groupCount()} keys on the names, not on the vertices that carry them. */
  @Test
  public void groupCountAfterValues_keysOnTheProjectedValue() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().values(name).groupCount()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("name").groupCount());
  }

  /**
   * A terminator after a grouping terminator consumes the maps the grouping emits, not the rows
   * that fed it: {@code group().by(label).count()} is 1, one map, and a second grouping folds that
   * map into a further bucket. Every assembler here would instead overwrite the grouped plan, so
   * all three decline.
   *
   * <p>The positive control is load-bearing. A decline is also what a prefix that stopped
   * translating would produce, and the payload comparison holds either way because both arms run
   * natively once the translator declines — so without pinning that
   * {@code g.V().group().by(label)} is recognised, none of the three cases below could tell the
   * grouping gate from a prefix regression.
   */
  @Test
  public void terminatorsAfterGroup_decline() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().group().by(label)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().group().by(T.label));

    assertEquivalent(
        "g.V().group().by(label).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().group().by(T.label).count());
    assertThat(graph.traversal().V().group().by(T.label).count().next()).isEqualTo(1L);

    // A second grouping over the emitted map: native buckets the map itself, where a translated
    // plan would re-key the underlying vertex rows and never see the map at all.
    assertEquivalent(
        "g.V().group().by(name).groupCount()",
        Recognition.DECLINED,
        () -> graph.traversal().V().group().by("name").groupCount());
    assertEquivalent(
        "g.V().group().by(name).group()",
        Recognition.DECLINED,
        () -> graph.traversal().V().group().by("name").group());
  }

  /**
   * {@code count()} after {@code values(key)} counts the values that step emitted, so the elements
   * without the key are gone before the count: 2 of the 4 seeded vertices carry {@code age}. The
   * drop lives in the row projection the count assembler discards, so it has to be restated as a
   * pattern conjunct — otherwise the {@code count(*)} runs over the unfiltered pattern and answers
   * 4.
   */
  @Test
  public void countAfterValues_countsOnlyKeyBearers() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().values(age).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").count());
    assertThat(graph.traversal().V().values("age").count().next()).isEqualTo(2L);
  }

  /**
   * A bare {@code group()} after {@code values(key)} buckets the projected values on both sides of
   * the map, not just the key side. Native groups a stream of strings, so each bucket holds the
   * strings; keying on the value while folding the vertices produces a map that looks right on the
   * half a reader checks first. {@code groupCount()} cannot show this — its value column is
   * {@code count(*)}.
   */
  @Test
  public void groupAfterValues_bucketsTheProjectedValue() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().values(name).group()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("name").group());
  }

  /**
   * {@code values("foo").sum()} over a graph where nothing carries {@code foo} emits nothing. The
   * drop that {@code values(key)} pins in the row projection is replaced by the aggregate column,
   * so it is restated as a pattern conjunct — otherwise the aggregate reduces four null-valued rows
   * and emits a zero where Gremlin emits no traverser at all.
   */
  @Test
  public void sumOverAbsentProperty_emitsNothing() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().values(foo).sum()",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().values("foo").sum());
  }

  /**
   * {@code values("age").mean()} divides in floating point: 30 and 25 average to 27.5, not 27. The
   * ages are chosen not to divide evenly, because an evenly-dividing fixture cannot tell the SQL
   * {@code mean} aggregate apart from {@code avg}, whose integer division is why {@code mean}
   * exists.
   */
  @Test
  public void meanOverIntegerProperty_dividesInFloatingPoint() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().values(age).mean()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").mean());
  }

  /** Seeds {@code count} Person vertices with distinct names and ages for the B1 cardinality cases. */
  private void seedPeople(int count) {
    for (var i = 0; i < count; i++) {
      graph.addVertex(T.label, "Person", "name", "P" + i, "age", 20 + i);
    }
    graph.tx().commit();
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier, false);
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, cardinality, traversalSupplier, false);
  }

  private void assertEquivalentOrdered(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier, true);
  }

  private void assertEquivalentInternal(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Supplier<GraphTraversal<?, ?>> traversalSupplier,
      boolean ordered) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onPayload = canonicalize(onAdmin.toList(), ordered);

      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offPayload = canonicalize(offAdmin.toList(), ordered);

      if (expected == Recognition.RECOGNIZED) {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must engage exactly one boundary step")
            .isEqualTo(1);
        // Anti-vacuity guard (opt-in): a NON_EMPTY RECOGNIZED fixture must return rows, or the
        // multiset equality below is vacuous over two empty lists (a seed regression that persisted
        // nothing would go green while verifying nothing). Empty-by-design cases pass MAY_BE_EMPTY.
        if (cardinality == Cardinality.NON_EMPTY) {
          assertThat(onPayload)
              .as(scenario + ": a NON_EMPTY RECOGNIZED fixture must return rows "
                  + "(else the multiset equality below is vacuous)")
              .isNotEmpty();
        }
      } else {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must decline — no boundary step")
            .isEqualTo(0);
      }
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onPayload)
          .as(scenario + ": translator-on and translator-off payloads must match")
          .isEqualTo(offPayload);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  private void setTranslatorEnabled(boolean enabled) {
    session
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
  }

  private boolean translatorEnabled() {
    return session
        .getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
  }

  /**
   * Runs {@code body} with the translator on, restoring the previous setting afterwards. The flag
   * defaults to {@code true}, so restoring a hardcoded {@code false} would leave a later assertion
   * appended to the same method running translator-off and passing without exercising the translator.
   */
  private void withTranslatorOn(Runnable body) {
    withTranslator(true, body);
  }

  /** Runs {@code body} with the translator off, restoring the previous setting afterwards. */
  private void withTranslatorOff(Runnable body) {
    withTranslator(false, body);
  }

  private void withTranslator(boolean enabled, Runnable body) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(enabled);
      body.run();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Asserts that {@code AdjacentToIncidentStrategy} rewrote the traversal's written
   * {@code values(key)} into the element form. Every case that pins an element-form escape rests on
   * that rewrite: the shapes are written as {@code values(key)} and only reach the recogniser as
   * {@link PropertyType#PROPERTY} because the strategy changed them. A fork upgrade that stopped
   * rewriting would leave those cases green while covering neither escape, which is the failure this
   * premise check exists to catch. Read with the translator off, since the translated arm folds the
   * step into a plan and leaves nothing to inspect.
   */
  private void assertRewrittenToElementForm(
      String scenario, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    withTranslatorOff(
        () -> {
          var admin = traversalSupplier.get().asAdmin();
          admin.applyStrategies();
          assertThat(firstPropertiesStepReturnType(admin))
              .as(scenario
                  + ": the strategy must have rewritten values(key) into the element form, "
                  + "or the case below exercises no escape")
              .isEqualTo(PropertyType.PROPERTY);
        });
  }

  /**
   * The {@link PropertyType} of the first {@code PropertiesStep} anywhere in a post-strategy traversal
   * tree, child traversals included, or {@code null} when there is none. The recursion is needed
   * because the sub-walk shapes carry their projection inside a combinator child.
   */
  private static PropertyType firstPropertiesStepReturnType(Traversal.Admin<?, ?> admin) {
    for (var step : admin.getSteps()) {
      if (step instanceof PropertiesStep<?> propertiesStep) {
        return propertiesStep.getReturnType();
      }
      if (step instanceof TraversalParent parent) {
        var children = new ArrayList<Traversal.Admin<?, ?>>(parent.getLocalChildren());
        children.addAll(parent.getGlobalChildren());
        for (var child : children) {
          var nested = firstPropertiesStepReturnType(child);
          if (nested != null) {
            return nested;
          }
        }
      }
    }
    return null;
  }

  /** Alice carries {@code name} and {@code age}, Bob only {@code name}, the third only {@code age}. */
  private void seedNameAgeNickGraph() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "age", 44, "nick", "c");
    graph.tx().commit();
  }

  /**
   * A meta-property fixture that separates the two placements of an {@code acl} filter: marko's
   * {@code acl} lives on his {@code friendWeight} property, josh's carries a different value, and
   * peter carries {@code acl} as a top-level vertex property and no {@code friendWeight} at all. A
   * plan that read {@code acl} off the vertex selects peter; native selects marko.
   */
  private void seedMetaPropertyGraph() {
    var marko = graph.addVertex(T.label, "Person", "name", "marko");
    marko.property("friendWeight", 1.5).property("acl", "private");
    var josh = graph.addVertex(T.label, "Person", "name", "josh");
    josh.property("friendWeight", 2.5).property("acl", "public");
    graph.addVertex(T.label, "Person", "name", "peter", "acl", "private");
    graph.tx().commit();
  }

  /**
   * Counts translated boundary steps of <em>any</em> kind across a step list (raw {@code
   * List<Step>}). The supertype is deliberate: a shape that splices a {@code MultiPlanMatchStep}
   * instead of a single-plan step is still a translation, and counting only the single-plan subtype
   * would let such a shape satisfy a decline expectation while the translator in fact accepted it.
   */
  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  private static List<String> canonicalize(List<?> results, boolean ordered) {
    var mapped = new ArrayList<String>(results.size());
    for (Object result : results) {
      mapped.add(canonicalizeOne(result));
    }
    if (!ordered) {
      mapped.sort(Comparator.naturalOrder());
    }
    return mapped;
  }

  private static String canonicalizeOne(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Vertex vertex) {
      return "V:" + Objects.toString(vertex.id());
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .sorted(Comparator.comparing(e -> Objects.toString(e.getKey())))
          .map(e -> canonicalizeOne(e.getKey()) + "=" + canonicalizeOne(e.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof Collection<?> collection) {
      var parts = collection.stream().map(ProjectionEquivalenceTest::canonicalizeOne).sorted()
          .collect(Collectors.joining(",", "[", "]"));
      return parts;
    }
    if (value instanceof Number number) {
      // Align Long / Integer / Double count cells (hardwired count may box differently).
      if (number.doubleValue() == Math.rint(number.doubleValue())) {
        return "N:" + number.longValue();
      }
      return "N:" + number.doubleValue();
    }
    return value.getClass().getSimpleName() + ":" + value;
  }
}
