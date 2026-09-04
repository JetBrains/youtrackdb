package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
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

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

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

  /**
   * A slice behind {@code values(age)} promotes the absence drop into a pattern conjunct, so
   * {@code LIMIT} / {@code SKIP} / {@code range} count survivors. Only the last-scanned vertex
   * carries {@code age=44}: {@code limit(1)} keeps it, {@code skip(1)} and {@code range(1, 3)}
   * skip it and return empty. A leading {@code has(name, …)} still translates. {@code skip(0)}
   * is the no-op control that never promotes.
   */
  @Test
  public void valuesThenSlice_translatesAndCountsSurvivors() {
    seedAgeOnLastScannedVertex();

    assertEquivalent(
        "g.V().values(age).limit(1)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").limit(1));
    assertEquivalent(
        "g.V().values(age).skip(1)",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().values("age").skip(1));
    assertEquivalent(
        "g.V().values(age).range(1, 3)",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().values("age").range(1, 3));
    assertEquivalent(
        "g.V().values(age).skip(0)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").skip(0));

    withTranslatorOff(
        () -> {
          assertThat(graph.traversal().V().values("age").limit(1).toList())
              .as("native drops the four ageless rows first, so limit(1) keeps the one age")
              .containsExactly(44);
          assertThat(graph.traversal().V().values("age").skip(1).toList())
              .as("native has nothing left to skip past")
              .isEmpty();
        });
  }

  /**
   * A leading filter does not block the promotion: {@code has(name, Person4)} narrows the scan to
   * one vertex, and if that vertex is the aged one the slice still translates over the survivor.
   */
  @Test
  public void hasThenValuesThenLimit_translates() {
    seedAgeOnLastScannedVertex();
    var lastName = lastScannedVertexName();

    assertEquivalent(
        "g.V().has(name, " + lastName + ").values(age).limit(1)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", lastName).values("age").limit(1));
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
  // right only where nothing left in the child can turn the projection's empty
  // stream back into output. Two chain shapes qualify — the projection ends the
  // child, or a count() after it ends the child — and every other chain declines
  // whatever its length. The cases below are those two shapes, the declines, and
  // the native row set each has to reproduce.
  // ---------------------------------------------------------------------------

  /**
   * A {@code values(key)} that ends its captured child gets the presence conjunct, in every
   * combinator that captures one. The projection is discarded on commit and the conjunct is the only
   * carrier left for the drop, so a missing conjunct turns the combinator into a no-op that returns
   * every seeded vertex. The three spellings cover the three commit paths the conjunct travels:
   * {@code and} conjoins it, {@code or} disjoins two of them, and {@code not} negates a nested
   * combinator's pair.
   */
  @Test
  public void subWalkValuesEndingTheChild_keepsThePresenceFilter() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().and(values(age))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.values("age")));
    assertEquivalent(
        "g.V().or(values(age), values(name))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().or(__.values("age"), __.values("name")));
    assertEquivalent(
        "g.V().not(and(values(age), values(name)))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().not(__.and(__.values("age"), __.values("name"))));

    // Non-vacuity: the three shapes select three different subsets of the same three seeded
    // vertices, so none of the equalities above is over the whole scan and none restates another.
    withTranslatorOff(
        () -> {
          assertThat(graph.traversal().V().and(__.values("age")).toList())
              .as("native keeps the two age-bearing vertices of the three seeded")
              .hasSize(2);
          assertThat(graph.traversal().V().or(__.values("age"), __.values("name")).toList())
              .as("every seeded vertex carries one of the two keys, so the or keeps all three")
              .hasSize(3);
          assertThat(
              graph.traversal().V().not(__.and(__.values("age"), __.values("name"))).toList())
              .as("only one vertex carries both keys, so the not keeps the other two")
              .hasSize(2);
        });
  }

  /**
   * A {@code count()} that ends the captured child gets no conjunct, because it destroys the drop:
   * native counts an empty stream as {@code 0} and emits it, so a vertex without {@code age} survives
   * the child and the combinator keeps it. Contributing the presence conjunct here would filter rows
   * native returns. The third spelling puts the count child beside a plain projection child to show
   * the two answers compose — one contributes nothing, the other still contributes its conjunct.
   */
  @Test
  public void subWalkValuesBeforeTerminalCount_keepsEveryElement() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().and(values(age).count())",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.values("age").count()));
    assertEquivalent(
        "g.V().where(values(age).count())",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().where(__.values("age").count()));
    assertEquivalent(
        "g.V().and(values(age).count(), values(name))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.values("age").count(), __.values("name")));

    // Non-vacuity in the opposite direction from the presence cases: the first two must filter
    // nothing, so the guard is that native returns every seeded vertex and a conjunct leaking in
    // would return two. The mixed case must filter to the name-bearers and not to the age-bearers,
    // which separates "no conjunct from the count child" from "no conjunct at all".
    withTranslatorOff(
        () -> {
          assertThat(graph.traversal().V().and(__.values("age").count()).toList())
              .as("count() emits 0 for the age-less vertices, so native keeps all three")
              .hasSize(3);
          assertThat(graph.traversal().V().where(__.values("age").count()).toList())
              .as("the same holds under where(), which captures its child the same way")
              .hasSize(3);
          assertThat(
              graph.traversal().V().and(__.values("age").count(), __.values("name"))
                  .values("name").toList())
              .as("the count child keeps everything, so only the name child filters")
              .containsExactlyInAnyOrder("Alice", "Bob");
        });
  }

  /**
   * Any step surviving after the projection declines the walk, even one that leaves the drop intact.
   * {@code dedup()} does leave it intact and an earlier gate classified it as such, which was
   * incomplete in a way the row sets here pin: the gate read one step ahead, so
   * {@code and(values(age).dedup().count())} kept the conjunct that {@code count()} should have
   * withdrawn. The rule is a termination test rather than a list of tolerated successors, so the
   * single-step spellings decline for the same reason the longer ones do.
   *
   * <p>The scoped {@code dedup(label)} spelling is here because it reached the same gate: it is a
   * {@link org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep} like the
   * unscoped form, and the label-resolving decline in {@code DedupGlobalStepRecogniser} fires only
   * after the projection has already committed.
   */
  @Test
  public void subWalkValuesWithAnySurvivingStep_declinesToNative() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().and(values(age).dedup())",
        Recognition.DECLINED,
        () -> graph.traversal().V().and(__.values("age").dedup()));
    assertEquivalent(
        "g.V().and(values(age).dedup(), values(name))",
        Recognition.DECLINED,
        () -> graph.traversal().V().and(__.values("age").dedup(), __.values("name")));
    assertEquivalent(
        "g.V().as(a).and(values(age).dedup(a))",
        Recognition.DECLINED,
        () -> graph.traversal().V().as("a").and(__.values("age").dedup("a")));
    assertEquivalent(
        "g.V().and(values(age).limit(1))",
        Recognition.DECLINED,
        () -> graph.traversal().V().and(__.values("age").limit(1)));
    assertEquivalent(
        "g.V().and(values(age).order())",
        Recognition.DECLINED,
        () -> graph.traversal().V().and(__.values("age").order()));

    // Non-vacuity: native filters, and the two-child spelling filters harder, so the declines are
    // guarding real row sets rather than agreeing over the whole scan.
    withTranslatorOff(
        () -> {
          assertThat(graph.traversal().V().and(__.values("age").dedup()).toList())
              .as("native keeps the two age-bearing vertices")
              .hasSize(2);
          assertThat(
              graph.traversal().V().and(__.values("age").dedup(), __.values("name")).toList())
              .as("native keeps only the vertex carrying both properties")
              .hasSize(1);
        });
  }

  /**
   * A two-step tail after the projection declines in every combinator that captures a child. These
   * are the shapes a one-step-ahead gate mistranslated: it read the {@code dedup()} as leaving the
   * drop intact and never looked at the {@code count()} or {@code limit(0)} behind it, so the
   * conjunct was contributed where native keeps the row ({@code and} and {@code where} returned two
   * of three, {@code not} returned one of zero) and withheld where native drops it
   * ({@code limit(0)} returned two of zero). The row sets differ per combinator and two of them are
   * empty, so a decline that quietly became a shared filter could not satisfy all four.
   */
  @Test
  public void subWalkValuesWithMultiStepTail_declinesToNative() {
    seedNameAgeNickGraph();

    assertEquivalent(
        "g.V().and(values(age).dedup().count())",
        Recognition.DECLINED,
        () -> graph.traversal().V().and(__.values("age").dedup().count()));
    assertEquivalent(
        "g.V().where(values(age).dedup().count())",
        Recognition.DECLINED,
        () -> graph.traversal().V().where(__.values("age").dedup().count()));
    // Empty on both arms by design — the child always emits, so not() rejects every vertex. The
    // withTranslatorOff block below pins that answer, which is what makes the opt-out attributable.
    assertEquivalent(
        "g.V().not(values(age).dedup().count())",
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().not(__.values("age").dedup().count()));
    // Empty on both arms by design — limit(0) empties every child stream, so and() rejects every
    // vertex. Pinned in the withTranslatorOff block below.
    assertEquivalent(
        "g.V().and(values(age).dedup().limit(0))",
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().and(__.values("age").dedup().limit(0)));
    // The termination test applies to the count arm too, and this is the spelling that needs it: a
    // count() classified drop-destroying without checking what follows it hands the walk on to a
    // slice whose bound the child swallows, so the child contributes nothing and every vertex
    // survives — against native's none.
    assertEquivalent(
        "g.V().and(values(age).count().limit(0))",
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().and(__.values("age").count().limit(0)));

    // Non-vacuity: the five native answers are three distinct row sets, two of them empty for
    // opposite reasons — count() emits 0 so not() rejects every vertex, limit(0) emits nothing so
    // and() rejects every vertex.
    withTranslatorOff(
        () -> {
          assertThat(graph.traversal().V().and(__.values("age").dedup().count()).toList())
              .as("count() emits 0 for the age-less vertex too, so native keeps all three")
              .hasSize(3);
          assertThat(graph.traversal().V().where(__.values("age").dedup().count()).toList())
              .as("where() reads the same non-empty child, so it also keeps all three")
              .hasSize(3);
          assertThat(graph.traversal().V().not(__.values("age").dedup().count()).toList())
              .as("the child always emits, so not() rejects every vertex")
              .isEmpty();
          assertThat(graph.traversal().V().and(__.values("age").dedup().limit(0)).toList())
              .as("limit(0) empties every child stream, so and() rejects every vertex")
              .isEmpty();
          assertThat(graph.traversal().V().and(__.values("age").count().limit(0)).toList())
              .as("limit(0) discards the count too, so and() rejects every vertex here as well")
              .isEmpty();
        });
  }

  /**
   * The surface the termination rule withdraws is redundant spellings, not answers: an unscoped
   * {@code dedup()} inside a captured child is inert. A captured child is an existence test — the
   * combinator reads only whether a traverser survived — and {@code dedup()} maps an empty stream to
   * an empty one and a non-empty stream to a non-empty one, so it cannot move the answer. Measured
   * natively rather than argued, on a fixture where two vertices share an age: a duplicate value is
   * what a deduplication could act on, and a duplicate set leaking across traversers would drop the
   * second vertex.
   *
   * <p>Native-only by construction. There is no translated arm to compare against — the shapes now
   * decline, which {@code subWalkValuesWithAnySurvivingStep_declinesToNative} pins — so the guard
   * against vacuity is that the shared row set is a proper non-empty subset of the fixture.
   */
  @Test
  public void subWalkDedupIsInertInAnExistenceChild() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.tx().commit();

    withTranslatorOff(
        () -> {
          var withoutDedup = graph.traversal().V().and(__.values("age")).values("name").toList();
          assertThat(withoutDedup)
              .as("the two age-bearers, and not the third vertex — the comparisons below would be "
                  + "vacuous over an empty or whole-scan row set")
              .containsExactlyInAnyOrder("Alice", "Bob");

          assertThat(graph.traversal().V().and(__.values("age").dedup()).values("name").toList())
              .as("dedup() cannot empty a non-empty child, so the and selects the same vertices "
                  + "even though the two ages are equal")
              .containsExactlyInAnyOrderElementsOf(withoutDedup);
          assertThat(
              graph.traversal().V().as("a").and(__.values("age").dedup("a")).values("name")
                  .toList())
              .as("a scoped dedup collapses the child stream by path label and is inert for the "
                  + "same reason")
              .containsExactlyInAnyOrderElementsOf(withoutDedup);
          assertThat(graph.traversal().V().where(__.values("age").dedup()).values("name").toList())
              .as("where() captures its child identically")
              .containsExactlyInAnyOrderElementsOf(withoutDedup);
          assertThat(graph.traversal().V().not(__.values("age").dedup()).values("name").toList())
              .as("not() reads the same non-emptiness and inverts it")
              .containsExactly("Carol");
        });
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

    // Zero matched vertices → YQL aggregate null cell → dropNullRows drops the row. Empty by design,
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

  // ---------------------------------------------------------------------------
  // project(keys...).by(...): one modulated RETURN column per key, MAP per row.
  // Phase 1 resolves each by-modulator against the boundary alias only — a
  // by(select(priorLabel)) modulator has no boundary resolution and declines, so
  // these cases exercise the property-value modulators that translate.
  // ---------------------------------------------------------------------------

  /**
   * {@code project("n", "a").by("name").by("age")} emits a two-entry Map per row and matches native.
   * Two keys with property-value modulators is the shape {@link ProjectStepRecogniser} translates
   * into two RETURN columns; the map-canonicalising renderer compares the emitted maps entry by
   * entry, so a plan that dropped a column or mis-keyed one would diverge here. The seed gives each
   * vertex a distinct {@code name}/{@code age} pair so neither entry is constant across rows.
   */
  @Test
  public void projectTwoKeys_overPropertyValues_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.tx().commit();

    assertEquivalent(
        "g.V().project(n, a).by(name).by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().project("n", "a").by("name").by("age"));
  }

  /**
   * The three-key spelling reaches three distinct RETURN aliases ({@code nm}/{@code ag}/{@code ci})
   * from three property-value modulators, and each row is a three-entry Map matching native. Wider
   * than the two-key case so a column-ordering or arity regression that still balanced two columns
   * would surface here; the seed keeps all three values distinct per row.
   */
  @Test
  public void projectThreeKeys_reachingThreeAliases_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30, "city", "NYC");
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25, "city", "LON");
    graph.tx().commit();

    assertEquivalent(
        "g.V().project(nm, ag, ci).by(name).by(age).by(city)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().project("nm", "ag", "ci").by("name").by("age").by("city"));
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

  /**
   * {@code order().by("name")} — sequence equality on=off, and the sorted names themselves (seed is
   * deliberately not insertion-ordered).
   */
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
    assertThat(graph.traversal().V().order().by("name").values("name").toList())
        .as("sorted names must be Alice,Bob,Carol — not insertion order Carol,Alice,Bob")
        .containsExactly("Alice", "Bob", "Carol");
  }

  /**
   * Vertices that tie on the sort key must return the same tag sequence on both Gremlin arms once
   * YQL execution and native {@code order()} both tie-break on RID. The sequence itself is pinned
   * against the record identifier order, so two arms tied in the same wrong way would fail.
   */
  @Test
  public void orderByTiedName_matchesNativeAndTranslatedOrder() {
    graph.addVertex(T.label, "Person", "name", "Tie", "tag", "t3");
    graph.addVertex(T.label, "Person", "name", "Tie", "tag", "t1");
    graph.addVertex(T.label, "Person", "name", "Tie", "tag", "t2");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(name).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("tag"));
    assertThat(graph.traversal().V().order().by("name").values("tag").toList())
        .as("one tied key leaves the record identifier order deciding the whole sequence")
        .isEqualTo(tagsInIdentifierOrder());
  }

  /**
   * Bare {@code order()} (identity → {@code @rid} / strategy leaves identity) — same tag sequence
   * on both arms, and that sequence is the record identifier order.
   */
  @Test
  public void bareOrder_matchesNativeRidOrder() {
    graph.addVertex(T.label, "Person", "tag", "c");
    graph.addVertex(T.label, "Person", "tag", "a");
    graph.addVertex(T.label, "Person", "tag", "b");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().values("tag"));
    assertThat(graph.traversal().V().order().values("tag").toList())
        .as("a bare sort over elements is the record identifier order")
        .isEqualTo(tagsInIdentifierOrder());
  }

  /** Explicit {@code by(T.id)} — same RID total order as bare {@code order()} on elements. */
  @Test
  public void orderByTokenId_matchesNative() {
    graph.addVertex(T.label, "Person", "tag", "c");
    graph.addVertex(T.label, "Person", "tag", "a");
    graph.addVertex(T.label, "Person", "tag", "b");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(T.id).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by(T.id).values("tag"));
    assertThat(graph.traversal().V().order().by(T.id).values("tag").toList())
        .as("the element token sorts by the record identifier, numerically")
        .isEqualTo(tagsInIdentifierOrder());
  }

  /**
   * All ages tied — primary key does not separate rows; trailing RID from the strategy must make
   * on and off agree on the tag sequence, which is the record identifier order.
   */
  @Test
  public void orderByTiedAge_matchesNativeRidTieBreak() {
    graph.addVertex(T.label, "Person", "age", 30, "tag", "c");
    graph.addVertex(T.label, "Person", "age", 30, "tag", "a");
    graph.addVertex(T.label, "Person", "age", 30, "tag", "b");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(age).values(tag)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("age").values("tag"));
    assertThat(graph.traversal().V().order().by("age").values("tag").toList())
        .as("every row ties on the age, so the appended key decides the sequence")
        .isEqualTo(tagsInIdentifierOrder());
  }

  /**
   * The {@code tag} of every stored vertex in ascending record identifier order. An oracle for a
   * sort whose stated keys all tie: it is computed from the stored rows, so it holds whatever either
   * arm returns, and it does not assume that insertion order and identifier order agree.
   */
  private List<String> tagsInIdentifierOrder() {
    var vertices = new ArrayList<>(graph.traversal().V().toList());
    vertices.sort(Comparator.comparing(vertex -> (Identifiable) vertex.id()));
    return vertices.stream().map(vertex -> vertex.<String>value("tag")).toList();
  }

  /**
   * Descending property sort — sequence pin plus on=off. Seed is neither insertion- nor
   * ascending-ordered.
   */
  @Test
  public void orderByNameDesc_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Mallory");
    graph.addVertex(T.label, "Person", "name", "Zoe");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(name, desc).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name", Order.desc).values("name"));
    assertThat(graph.traversal().V().order().by("name", Order.desc).values("name").toList())
        .containsExactly("Zoe", "Mallory", "Alice");
  }

  /**
   * Multi-key with a tie on the first key: ages 20/30/20 → names Ann, Cy before Ben. Projects names
   * so the expected sequence is visible (not only RID strings).
   */
  @Test
  public void orderByAgeThenName_tiedAge_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Ben", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Cy", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Ann", "age", 20);
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(age).by(name).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .order().by("age", Order.asc).by("name", Order.asc)
            .values("name"));
    assertThat(
        graph.traversal().V()
            .order().by("age", Order.asc).by("name", Order.asc)
            .values("name").toList())
        .containsExactly("Ann", "Cy", "Ben");
  }

  /** {@code hasLabel} then {@code order().by(name)} — filter + sort, sequence on=off. */
  @Test
  public void hasLabel_orderByName_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Zoe");
    graph.addVertex(T.label, "Software", "name", "Ignore");
    graph.addVertex(T.label, "Person", "name", "Ada");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().hasLabel(Person).order().by(name).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").order().by("name").values("name"));
    assertThat(
        graph.traversal().V().hasLabel("Person").order().by("name").values("name").toList())
        .containsExactly("Ada", "Zoe");
  }

  /**
   * Property {@code id}, unique in this fixture, so the appended record identifier key separates
   * nothing and the {@code id} order alone decides the answer. The strategy no longer skips a
   * property named {@code id}, so this case pins that the appended key leaves a genuinely unique
   * primary key alone. The duplicate-value counterpart lives in
   * {@code OrderRidTieBreakEquivalenceTest}.
   */
  @Test
  public void orderByPropertyId_matchesNativeWithAppendedRid() {
    graph.addVertex(T.label, "Person", "id", "p3", "name", "Carol");
    graph.addVertex(T.label, "Person", "id", "p1", "name", "Alice");
    graph.addVertex(T.label, "Person", "id", "p2", "name", "Bob");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(id).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("id").values("name"));
    assertThat(graph.traversal().V().order().by("id").values("name").toList())
        .containsExactly("Alice", "Bob", "Carol");
  }

  /**
   * A real slice behind {@code order().by(k)} declines, because MATCH's {@code ORDER BY} on a
   * repeated key is a partial order and a bound cutting inside a tie group keeps an arbitrary
   * member of it. Three of the four vertices here share the name {@code Tie}, so {@code LIMIT 2}
   * cuts inside that group: before the decline the translated arm returned {@code [t1, t2]} where
   * native's stable sort returned {@code [t2, t1]}.
   *
   * <p>The comparison is the ordered one because {@code order()} makes the sequence the answer.
   * Where the same cut falls behind a hop it costs a row outright rather than a position — that
   * case lives beside the other cardinality-clause equivalences in
   * {@code OrderRangeStepRecogniserTest}.
   */
  @Test
  public void orderThenLimit_declinesOnTiedSortKey() {
    graph.addVertex(T.label, "Person", "name", "Tie", "tag", "t3");
    graph.addVertex(T.label, "Person", "name", "Tie", "tag", "t1");
    graph.addVertex(T.label, "Person", "name", "Tie", "tag", "t2");
    graph.addVertex(T.label, "Person", "name", "Zzz", "tag", "z");
    graph.tx().commit();

    // Fixture precondition: the bound is load-bearing only if the sort key ties across it. If
    // positions 1 and 2 of native's ordered answer carried different names, ORDER BY alone would
    // determine which rows LIMIT 2 keeps and the decline would be guarding nothing.
    var nativeKeys = nativeOrderedNames();
    assertThat(nativeKeys)
        .as("the fixture must supply at least three rows for the LIMIT 2 boundary to sit inside")
        .hasSizeGreaterThan(2);
    assertThat(nativeKeys.get(1))
        .as("the fixture must tie the sort key across the LIMIT 2 boundary")
        .isEqualTo(nativeKeys.get(2));

    assertEquivalentOrdered(
        "g.V().order().by(name).limit(2).values(tag)",
        Recognition.DECLINED,
        () -> graph.traversal().V().order().by("name").limit(2).values("tag"));
  }

  /** Native's ordered name sequence, read translator-off so the sort is Gremlin's own stable one. */
  private List<String> nativeOrderedNames() {
    var names = new ArrayList<String>();
    withTranslatorOff(
        () -> {
          var admin = graph.traversal().V().order().by("name").values("name").asAdmin();
          admin.applyStrategies();
          admin.toList().stream().map(String::valueOf).forEach(names::add);
        });
    return names;
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

  /**
   * The bare (no-{@code by}) empty-input companions of {@link #emptyInputGroup_emitsSingleEmptyMap}:
   * {@code group()} and {@code groupCount()} over a filtered-to-empty scan each emit a single empty
   * map {@code [{}]} on both paths, matching native. The bare forms take a different builder path
   * from the {@code by(name)} ones — the value side folds the current match / counts {@code *} rather
   * than keying on a property — so they need their own empty-input pin. The single empty map is a
   * non-empty result, so both keep the default non-empty guard.
   */
  @Test
  public void emptyInputBareGroupAndGroupCount_emitSingleEmptyMap() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().has(name, nobody).group()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").group());
    assertEquivalent(
        "g.V().has(name, nobody).groupCount()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").groupCount());
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
   * Under the portable opt-out, {@code order().by("age")} emits only the two vertices that carry
   * {@code age}. Gremlin's modulator is then a filter, so an element with no {@code age} produces
   * no value and its traverser is dropped.
   *
   * <p>The opt-out is explicit because the SHIPPED DEFAULT no longer drops: a global-scope order
   * keeps the ageless element and orders it as a null key, the way YQL {@code ORDER BY} does. This
   * case therefore pins the equivalence of the two arms under portable semantics only. The
   * absolute rows of the default are pinned by {@code YTDBProductiveOrderByStrategyTest}.
   */
  @Test
  public void orderByMissingKeyUnderPortableOptOut_dropsElementLikeNative() {
    seedAgedAndAgeless();

    // Ordered comparison: after the drop only Bob (25) and Alice (30) survive and their ages
    // differ, so the sorted payload is deterministic on both paths and the sort is asserted rather
    // than sorted away.
    assertEquivalentOrdered(
        "g.V().with(orderIncludesMissingKey, false).order().by(age)",
        Recognition.RECOGNIZED,
        () -> graph.traversal()
            .with(YTDBQueryConfigParam.orderIncludesMissingKey, false)
            .V().order().by("age"));
  }

  /**
   * Under the same portable opt-out the drop has to reach a following {@code count()}, which reads
   * the filtered pattern rather than the projected stream: {@code order().by("age").count()} is 2,
   * not 4. The shipped default counts all four instead, which
   * {@code YTDBProductiveOrderByStrategyTest} pins as an absolute value.
   */
  @Test
  public void countAfterOrderByMissingKeyUnderPortableOptOut_countsOnlyKeyBearers() {
    seedAgedAndAgeless();

    assertEquivalent(
        "g.V().with(orderIncludesMissingKey, false).order().by(age).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal()
            .with(YTDBQueryConfigParam.orderIncludesMissingKey, false)
            .V().order().by("age").count());
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
   * is the one shape where the drop cannot be done after the fact — YQL forms the bucket during
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

  /**
   * The descending direction of the same re-point. {@code order().by(Order.desc)} keeps the identity
   * value traversal and changes only the comparator, so it resolves through the same branch as the
   * ascending case — and a branch that resolved the property but dropped the direction would still
   * pass the ascending case above. The three names are seeded so that neither insertion order nor
   * ascending order matches the expected descending sequence.
   */
  @Test
  public void descendingOrderAfterValues_sortsByTheValueDescending() {
    graph.addVertex(T.label, "Person", "name", "Mallory");
    graph.addVertex(T.label, "Person", "name", "Zoe");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().values(name).order().by(Order.desc)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("name").order().by(Order.desc));
    assertThat(graph.traversal().V().values("name").order().by(Order.desc).toList())
        .as("the descending sequence is what both arms must produce — an ascending or "
            + "insertion-ordered answer differs from it on this seed")
        .containsExactly("Zoe", "Mallory", "Alice");
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
   * A slice after a grouping terminator selects among the maps the grouping emitted, and a grouping
   * terminator emits exactly one. Native {@code limit(1)} therefore keeps that whole map and
   * {@code skip(1)} drops it; a statement-level {@code LIMIT} / {@code SKIP} would instead cut the
   * {@code GROUP BY} rows that feed the map, returning a one-entry map for the first spelling and a
   * two-entry map for the second. Three distinct names make both directions visible.
   *
   * <p>The {@code skip} arm's payload comparison is vacuous by itself — both arms end up empty — so
   * the emptiness is pinned directly, and the recognised {@code groupCount().by(name)} case is what
   * separates the grouping gate from a prefix that stopped translating.
   */
  @Test
  public void slicesAfterGroup_decline() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Cleo");
    graph.tx().commit();

    // The answers come first so that a regression reopening either shape reports the wrong map
    // rather than only the boundary count.
    assertThat(graph.traversal().V().groupCount().by("name").limit(1).next())
        .as("limit(1) keeps the single emitted map whole — every name is still a key")
        .containsOnlyKeys("Alice", "Bob", "Cleo");
    assertThat(graph.traversal().V().group().by("name").skip(1).toList())
        .as("skip(1) drops the one emitted map, so nothing is returned")
        .isEmpty();

    assertEquivalent(
        "g.V().groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().groupCount().by("name"));

    assertEquivalent(
        "g.V().groupCount().by(name).limit(1)",
        Recognition.DECLINED,
        () -> graph.traversal().V().groupCount().by("name").limit(1));
    // Empty on both arms by design — the skip drops the single map the grouping emitted. The
    // assertion above pins that answer directly, so the opt-out is attributable.
    assertEquivalent(
        "g.V().group().by(name).skip(1)",
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().group().by("name").skip(1));
  }

  /**
   * {@code order().by(key)} after a grouping terminator sorts the maps the grouping emitted, and a
   * grouping terminator emits exactly one — so the sort has nothing to reorder and the map comes
   * through whole, including keys no entry holds. Translated, the same {@code by(age)} became a
   * pattern conjunct {@code age IS DEFINED} on the rows feeding the {@code GROUP BY} plus an
   * {@code ORDER BY} over them, which dropped the two ageless people from the map:
   * {@code {Alice=1, Bob=1}} against native's four entries. The conjunct is a filter the query never
   * asked for, so the shape declines.
   *
   * <p>The absent comparison is not the whole reason the map survives. {@code OrderGlobalStep}
   * projects the modulator per traverser before it sorts anything, and drops a traverser whose
   * projection is non-productive — over an element that is exactly how {@code order().by(k)} excludes
   * the elements lacking {@code k}. A {@code by(key)} over a {@code Map} is productive regardless,
   * yielding {@code map.get(key)} including {@code null}, so the projection cannot drop the map. The
   * second pin is that mechanism at its extreme: a key <em>nothing</em> in the graph carries still
   * returns the map whole, which an absent-key drop would have emptied.
   *
   * <p>The map is pinned before the decline is, so that a regression reopening the shape reports the
   * narrowed map rather than only the boundary count. The recognised control is what separates the
   * grouping gate from a prefix that stopped translating.
   */
  @Test
  public void orderByKeyAfterGroup_declines() {
    seedAgedAndAgeless();

    assertThat(graph.traversal().V().groupCount().by("name").order().by("age").next())
        .as("sorting one map reorders nothing, and a map projection is productive even where the key "
            + "is missing, so every name survives — a translated ORDER BY would filter the grouped "
            + "rows by an age the query never named")
        .containsOnlyKeys("Alice", "Bob", "Nemo", "Nobody");
    assertThat(graph.traversal().V().groupCount().by("name").order().by("zzz").toList())
        .as("a key no vertex carries would empty the stream if the modulator's projection could be "
            + "non-productive over a map; it cannot, so the one map still comes through")
        .hasSize(1);

    assertEquivalent(
        "g.V().groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().groupCount().by("name"));

    assertEquivalent(
        "g.V().groupCount().by(name).order().by(age)",
        Recognition.DECLINED,
        () -> graph.traversal().V().groupCount().by("name").order().by("age"));
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
   * ages are chosen not to divide evenly, because an evenly-dividing fixture cannot tell the YQL
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

  /**
   * The group value side reaches the same {@code mean} YQL function through a different builder
   * call than {@code values(k).mean()} does: a grouped RETURN column rather than a single-plan
   * property aggregate. It resolves only because that function is registered — before the
   * registration the shape translated and then failed at execution — so it needs its own case. The
   * ages do not divide evenly inside the two-member bucket, so a regression to {@code avg} surfaces
   * as an integer payload rather than as an equal one.
   */
  @Test
  public void groupValueSideMean_dividesInFloatingPointPerBucket() {
    graph.addVertex(T.label, "Person", "name", "Alice", "city", "NYC", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "city", "NYC", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Carol", "city", "LON", "age", 41);
    graph.tx().commit();

    assertEquivalent(
        "g.V().group().by(city).by(values(age).mean())",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().group().by("city").by(__.values("age").mean()));
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
    support.assertEquivalent(
        scenario,
        expected,
        cardinality,
        results -> canonicalize(results, ordered),
        traversalSupplier);
  }

  /**
   * Runs {@code body} with the translator on, restoring the previous setting afterwards. The flag
   * defaults to {@code true}, so restoring a hardcoded {@code false} would leave a later assertion
   * appended to the same method running translator-off and passing without exercising the translator.
   */
  private void withTranslatorOn(Runnable body) {
    support.withTranslator(true, body);
  }

  /** Runs {@code body} with the translator off, restoring the previous setting afterwards. */
  private void withTranslatorOff(Runnable body) {
    support.withTranslator(false, body);
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
   * Five vertices, then {@code age=44} put on whichever one scans last, read back at seed time
   * rather than assumed. Scan order is not stable across JVM forks, so the fixture discovers it;
   * what the slice cases need is only that the aged vertex is not among the first rows a slice
   * would take.
   */
  private void seedAgeOnLastScannedVertex() {
    for (var i = 0; i < 5; i++) {
      graph.addVertex(T.label, "Person", "name", "Person" + i);
    }
    graph.tx().commit();

    withTranslatorOff(
        () -> {
          var scanned = graph.traversal().V().toList();
          assertThat(scanned).as("fixture must seed five vertices").hasSize(5);
          ((Vertex) scanned.get(scanned.size() - 1)).property("age", 44);
          graph.tx().commit();
        });
  }

  /** The {@code name} of the vertex that currently scans last — the one {@link
   *  #seedAgeOnLastScannedVertex} ages. */
  private String lastScannedVertexName() {
    var holder = new String[1];
    withTranslatorOff(
        () -> {
          var scanned = graph.traversal().V().toList();
          holder[0] = ((Vertex) scanned.get(scanned.size() - 1)).value("name");
        });
    return holder[0];
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
