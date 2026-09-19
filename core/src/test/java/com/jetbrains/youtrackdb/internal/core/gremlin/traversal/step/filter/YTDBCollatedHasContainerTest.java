package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.PBiPredicate;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.GValue;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedFactory;
import org.junit.Test;

/** Exercises native property filtering because translated MATCH already applies schema collation. */
public class YTDBCollatedHasContainerTest extends GraphBaseTest {

  /** Scalar comparison, ranges, membership, text predicates, and boolean trees use {@code ci}. */
  @Test
  public void postHopSupportedPredicatesUseDeclaredCollation() {
    var vertices = seedPeople();
    var source = vertices.getFirst();

    assertNames(source, P.eq("BOB"), "bob");
    assertNames(source, P.neq("BOB"), "Alice");
    assertNames(source, P.gte("BOB").and(P.lt("CAROL")), "bob");
    assertNames(source, P.within("BOB", "CHARLIE"), "bob");
    assertNames(source, P.without("BOB", "CHARLIE"), "Alice");
    assertNames(source, TextP.containing("O"), "bob");
    assertNames(source, TextP.notContaining("O"), "Alice");
    assertNames(source, TextP.startingWith("B"), "bob");
    assertNames(source, TextP.notStartingWith("B"), "Alice");
    assertNames(source, TextP.endingWith("B"), "bob");
    assertNames(source, TextP.notEndingWith("B"), "Alice");
    assertNames(source, P.eq("BOB").or(P.eq("MISSING")), "bob");
    assertNames(source, P.not(P.eq("BOB")), "Alice");
  }

  /** By-ID filtering reaches the same wrapped container without changing graph-start planning. */
  @Test
  public void byIdPropertyFilterUsesDeclaredCollation() {
    var vertices = seedPeople();
    var bob = vertices.get(1);

    withTranslator(false,
        () -> assertThat(graph.traversal().V(bob.id()).has("name", "BOB").toList())
            .containsExactly(bob));
  }

  /** Edge properties use their concrete edge class declaration and owner metadata. */
  @Test
  public void edgePropertyFilterUsesDeclaredCollation() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    var knows = session.createEdgeClass("Knows");
    knows.createProperty("role", PropertyType.STRING).setCollate("ci");
    var source = graph.addVertex(T.label, "Person", "name", "source");
    var target = graph.addVertex(T.label, "Person", "name", "target");
    source.addEdge("Knows", target, "role", "Supervisor");
    graph.tx().commit();

    withTranslator(false, () -> assertThat(
        graph.traversal().V(source.id()).outE("Knows").has("role", "SUPERVISOR").count().next())
        .isOne());
  }

  /** Effective inherited declarations apply while conflicting concrete declarations stay local. */
  @Test
  public void polymorphicOwnersResolveTheirEffectiveDeclarations() {
    var base = session.createVertexClass("BasePerson");
    base.createProperty("name", PropertyType.STRING).setCollate("ci");
    var schema = session.getMetadata().getSchema();
    var inherited = schema.createClass("InheritedPerson", base);
    var strict = schema.createClass("StrictPerson", base);
    strict.createProperty("name", PropertyType.STRING);
    var sourceClass = session.createVertexClass("Source");
    var source = graph.addVertex(T.label, sourceClass.getName());
    var collated = graph.addVertex(T.label, inherited.getName(), "name", "bob");
    var caseSensitive = graph.addVertex(T.label, strict.getName(), "name", "bob");
    source.addEdge("sees", collated);
    source.addEdge("sees", caseSensitive);
    graph.tx().commit();

    withTranslator(false, () -> assertThat(
        graph.traversal().V(source.id()).out("sees").has("name", "BOB").toList())
        .containsExactly(collated));
  }

  /** List equality and retained-set equality apply collation without changing collection shape. */
  @Test
  public void collectionEqualityUsesDeclaredCollation() {
    var person = session.createVertexClass("Person");
    person.createProperty("aliases", PropertyType.EMBEDDEDLIST)
        .setLinkedType(PropertyType.STRING)
        .setCollate("ci");
    person.createProperty("tags", PropertyType.EMBEDDEDSET)
        .setLinkedType(PropertyType.STRING)
        .setCollate("ci");
    var source = graph.addVertex(T.label, "Person");
    var target = graph.addVertex(
        T.label,
        "Person",
        "aliases",
        List.of("Bob", "Builder"),
        "tags",
        new LinkedHashSet<>(Set.of("Blue", "Green")));
    source.addEdge("sees", target);
    graph.tx().commit();

    withTranslator(false, () -> {
      assertThat(target.<List<String>>value("aliases"))
          .containsExactly("Bob", "Builder");
      assertThat(new YTDBCollatedHasContainer(
          "aliases", P.eq(List.of("BOB", "BUILDER"))).test(target)).isTrue();
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("aliases", P.eq(List.of("BOB", "BUILDER")))
          .toList()).containsExactly(target);
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("tags", P.eq(GValue.of("expectedTags", Set.of("BLUE", "GREEN"))))
          .toList()).containsExactly(target);
    });
  }

  /** Ordinary set literals retain the existing native Set-to-List mismatch approved as a non-goal. */
  @Test
  public void ordinarySetLiteralKeepsNativeShapeMismatch() {
    var person = session.createVertexClass("Person");
    person.createProperty("tags", PropertyType.EMBEDDEDSET)
        .setLinkedType(PropertyType.STRING)
        .setCollate("ci");
    var source = graph.addVertex(T.label, "Person");
    var target = graph.addVertex(
        T.label, "Person", "tags", new LinkedHashSet<>(Set.of("Blue", "Green")));
    source.addEdge("sees", target);
    graph.tx().commit();

    withTranslator(false, () -> {
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("tags", P.eq(Set.of("Blue", "Green")))
          .toList()).isEmpty();
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("tags", P.eq(Set.of("BLUE", "GREEN")))
          .toList()).isEmpty();
    });
  }

  /** Default, schemaless, and regular-expression predicates retain case-sensitive behavior. */
  @Test
  public void unsupportedOrMetadataFreePredicatesKeepNativeBehavior() {
    var declared = session.createVertexClass("Declared");
    declared.createProperty("name", PropertyType.STRING);
    var collated = session.createVertexClass("Collated");
    collated.createProperty("name", PropertyType.STRING).setCollate("ci");
    var source = graph.addVertex(T.label, "Declared");
    var strict = graph.addVertex(T.label, "Declared", "name", "bob");
    var schemaless = graph.addVertex(T.label, "V", "name", "bob");
    var regex = graph.addVertex(T.label, "Collated", "name", "bob");
    source.addEdge("sees", strict);
    source.addEdge("sees", schemaless);
    source.addEdge("sees", regex);
    graph.tx().commit();

    withTranslator(false, () -> {
      assertThat(graph.traversal().V(source.id()).out("sees").has("name", "BOB").toList())
          .containsExactly(regex);
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("name", TextP.regex("BOB"))
          .toList()).isEmpty();
    });
  }

  /** Strategy replacement preserves order, predicate identity, clones, and repeat application. */
  @Test
  public void strategyWrappingPreservesContainerContracts() {
    var first = P.eq("BOB");
    var second = P.gt(10);
    var traversal = graph.traversal().V().has("name", first).has("age", second).asAdmin();

    withTranslator(false, () -> {
      traversal.applyStrategies();
      var graphStep = (YTDBGraphStep<?, ?>) traversal.getStartStep();
      assertThat(graphStep.getHasContainers()).extracting(HasContainer::getKey)
          .containsExactly("name", "age");
      assertThat(graphStep.getHasContainers().getFirst())
          .isInstanceOf(YTDBCollatedHasContainer.class);
      assertThat(graphStep.getHasContainers().getFirst().getPredicate()).isSameAs(first);
      assertThat(graphStep.getHasContainers().get(1).getPredicate()).isSameAs(second);
      assertThat(graphStep.getHasContainers().getFirst().clone())
          .isInstanceOf(YTDBCollatedHasContainer.class);

      assertThat(YTDBCollatedHasContainer.wrap(graphStep.getHasContainers().getFirst()))
          .isSameAs(graphStep.getHasContainers().getFirst());
      assertThat(graphStep.getHasContainers()).hasSize(2);
    });
  }

  /** Mutable predicate values invalidate cached transformations, including equal-hash strings. */
  @Test
  public void mutablePredicateValueInvalidatesCachedOperand() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var first = graph.addVertex(T.label, "Person", "name", "FB");
    var second = graph.addVertex(T.label, "Person", "name", "Ea");
    graph.tx().commit();
    var predicate = P.eq("FB");
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertThat(container.test(first)).isTrue();
    predicate.setValue("Ea");
    assertThat(container.test(second)).isTrue();
  }

  /** Mutable collection contents invalidate cached transformations without changing membership. */
  @Test
  public void mutableCollectionInvalidatesCachedOperand() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var candidates = new ArrayList<>(List.of("BOB"));
    var predicate = P.within(candidates);
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertThat(container.test(bob)).isTrue();
    candidates.set(0, "ALICE");
    assertThat(container.test(bob)).isFalse();
    assertThat(container.test(alice)).isTrue();
  }

  /** The transient operand cache stays absent until the first cacheable operand transformation. */
  @Test
  public void operandCacheAllocatesLazilyOnFirstUse() throws Exception {
    var predicate = P.eq("BOB");
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertThat(transformedOperands(container)).isNull();

    container.transformedOperand(predicate, new CaseInsensitiveCollate());

    assertThat(transformedOperands(container)).isNotNull();
  }

  /** Deserialization resets the transient cache, then collated evaluation restores it lazily. */
  @Test
  public void deserializedContainerRestoresOperandCache() throws Exception {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    var original = new YTDBCollatedHasContainer("name", P.eq("BOB"));
    original.test(bob);

    var restored = roundTrip(original);

    assertThat(transformedOperands(restored)).isNull();
    assertThat(restored.test(bob)).isTrue();
    assertThat(transformedOperands(restored)).isNotNull();
  }

  /** Parameter updates bypass cached operands and immediately affect collated evaluation. */
  @Test
  public void parameterUpdateUsesCurrentOperand() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var predicate = P.eq(GValue.of("expectedName", "BOB"));
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertThat(container.test(bob)).isTrue();
    predicate.updateVariable("expectedName", "ALICE");
    assertThat(container.test(bob)).isFalse();
    assertThat(container.test(alice)).isTrue();
  }

  /** A populated clone resets its cache and follows only its cloned predicate changes. */
  @Test
  public void populatedCloneHasIndependentOperandCache() throws Exception {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var original = new YTDBCollatedHasContainer("name", P.eq("BOB"));
    assertThat(original.test(bob)).isTrue();
    var originalCache = transformedOperands(original);
    assertThat(originalCache).isNotNull();

    var clone = original.clone();
    assertThat(transformedOperands(clone)).isNull();
    @SuppressWarnings("unchecked")
    var clonePredicate = (P<Object>) clone.getPredicate();
    clonePredicate.setValue("ALICE");

    assertThat(clone.test(alice)).isTrue();
    assertThat(transformedOperands(clone)).isNotNull().isNotSameAs(originalCache);
    assertThat(original.test(bob)).isTrue();
    assertThat(transformedOperands(original)).isSameAs(originalCache);
  }

  /** Cached collection operands transform once, then mutable state triggers one new transform. */
  @Test
  public void cachedCollectionOperandAvoidsRepeatedTransformation() {
    var collate = new CountingCaseInsensitiveCollate();
    var candidates = new ArrayList<>(List.of("BOB", "ALICE"));
    var predicate = P.within(candidates);
    var container = new YTDBCollatedHasContainer("name", predicate);

    var first = container.transformedOperand(predicate, collate);
    var second = container.transformedOperand(predicate, collate);

    assertThat(second).isSameAs(first);
    assertThat(collate.stringTransforms).isEqualTo(2);
    candidates.set(1, "CAROL");
    var third = container.transformedOperand(predicate, collate);
    assertThat(third).isNotSameAs(first);
    assertThat(collate.stringTransforms).isEqualTo(4);
  }

  /** A supported leaf gives a custom collation its complete operand before evaluation. */
  @Test
  public void customCollateTransformsCompleteOperand() {
    var operand = List.of("FIRST", "SECOND");
    var predicate = P.within(operand);
    var collate = new RecordingCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertThat(container.evaluate(predicate, "first", collate)).isTrue();
    assertThat(collate.seenOperand).isEqualTo(operand);
    assertThat(collate.collectionTransforms).isOne();
  }

  /** Parameterized collection operands retain lazy transformation and membership early exit. */
  @Test
  public void parameterizedCollectionOperandTransformsOnlyConsumedPrefix() {
    var values = stringValues(10_000);
    var predicate = new CollectionPredicate(values, true);
    var collate = new CountingCaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);

    var transformed = (List<?>) container.transformedOperand(predicate, collate);

    assertThat(transformed.getFirst()).isEqualTo("value-0");
    assertThat(collate.stringTransforms).isOne();
  }

  /** Non-cacheable collection operands stop validation and retain a lazy transforming view. */
  @Test
  public void nonCacheableCollectionOperandAvoidsFullScanAndTransformation() {
    var values = new CountingObjectList(10_000);
    var predicate = new CollectionPredicate(values, false);
    var container = new YTDBCollatedHasContainer("rank", predicate);
    values.resetReads();

    var transformed = (List<?>) container.transformedOperand(
        predicate, new CaseInsensitiveCollate());

    assertThat(transformed.getFirst()).isEqualTo(0);
    assertThat(values.reads).isEqualTo(2);
  }

  /** Parameterized retained sets transform only the first member needed by membership. */
  @Test
  public void parameterizedSetMembershipTransformsOnlyConsumedPrefix() {
    var values = new LinkedHashSet<>(stringValues(10_000));
    var predicate = new CollectionPredicate(values, true);
    var collate = new CountingCaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertThat(container.evaluate(predicate, "VALUE-0", collate)).isTrue();
    assertThat(collate.stringTransforms).isEqualTo(2);
  }

  /** A retained set with an uncacheable member still exits membership after its first match. */
  @Test
  public void nonCacheableSetMembershipTransformsOnlyConsumedPrefix() {
    var values = new LinkedHashSet<>();
    values.add("VALUE-0");
    values.add(new Object());
    values.addAll(stringValues(10_000));
    var predicate = new CollectionPredicate(values, false);
    var collate = new CountingCaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertThat(container.evaluate(predicate, "VALUE-0", collate)).isTrue();
    assertThat(collate.stringTransforms).isEqualTo(2);
  }

  /** Parameterized membership reuses each normalized position without replaying source reads. */
  @Test
  public void parameterizedMembershipReusesNormalizedPositions() {
    var values = new MutableCountingList(stringValues(256));
    var predicate = new CollectionPredicate(values, true);
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);

    var first = (List<?>) container.transformedOperand(predicate, collate);
    var firstNormalized = first.get(128);
    var second = (List<?>) container.transformedOperand(predicate, collate);
    assertThat(second.get(128)).isSameAs(firstNormalized);

    values.resetReads();
    for (var evaluation = 0; evaluation < 512; evaluation++) {
      assertThat(container.evaluate(predicate, "MISSING", collate)).isFalse();
    }
    assertThat(values.reads).isEqualTo(256 * 512);

    var reused = new ArrayList<Object>(values.size());
    var populated = (List<?>) container.transformedOperand(predicate, collate);
    for (var index = 0; index < values.size(); index++) {
      reused.add(populated.get(index));
    }
    assertThat(container.evaluate(predicate, "MISSING", collate)).isFalse();
    var afterEvaluation = (List<?>) container.transformedOperand(predicate, collate);
    for (var index = 0; index < values.size(); index++) {
      assertThat(afterEvaluation.get(index)).isSameAs(reused.get(index));
    }

    values.resetReads();
    values.set(0, "MATCH");
    for (var evaluation = 0; evaluation < 512; evaluation++) {
      assertThat(container.evaluate(predicate, "match", collate)).isTrue();
    }
    assertThat(values.reads).isEqualTo(512);
  }

  /** Stable-size early matches never enumerate the populated positional slot map. */
  @Test
  public void parameterizedMembershipAvoidsStableSizeSlotScans() throws Exception {
    var values = new MutableCountingList(stringValues(1_024));
    var predicate = new CollectionPredicate(values, true);
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);
    values.resetReads();
    assertThat(container.evaluate(predicate, "MISSING", collate)).isFalse();

    var cached = parameterizedOperandState(container);
    var slots = cached.getClass().getDeclaredField("slots");
    slots.setAccessible(true);
    slots.set(cached, new NoScanMap((Map<?, ?>) slots.get(cached)));

    assertThat(container.evaluate(predicate, "value-0", collate)).isTrue();
    assertThat(values.reads).isEqualTo(1_025);
  }

  /** Empty and wholly uncacheable evaluations do not allocate retained identity state. */
  @Test
  public void parameterizedMembershipAdmitsCacheOnFirstReusableString() throws Exception {
    var collate = new CaseInsensitiveCollate();

    var empty = new CollectionPredicate(List.of(), true);
    var emptyContainer = new YTDBCollatedHasContainer("name", empty);
    assertThat(emptyContainer.evaluate(empty, "missing", collate)).isFalse();
    assertThat(parameterizedOperands(emptyContainer)).isNull();

    var marker = new Object();
    var nonString = new CollectionPredicate(List.of(marker), true);
    var nonStringContainer = new YTDBCollatedHasContainer("name", nonString);
    assertThat(nonStringContainer.evaluate(nonString, marker, collate)).isTrue();
    assertThat(parameterizedOperands(nonStringContainer)).isNull();

    var oversized = new CollectionPredicate(List.of("A".repeat(129)), true);
    var oversizedContainer = new YTDBCollatedHasContainer("name", oversized);
    assertThat(oversizedContainer.evaluate(oversized, "a".repeat(129), collate)).isTrue();
    assertThat(parameterizedOperands(oversizedContainer)).isNull();
  }

  /** Without stops at a matching first member and never evaluates a throwing lazy tail. */
  @Test
  public void parameterizedWithoutShortCircuitsBeforeThrowingTail() {
    var values = new ThrowingTailList();
    var predicate = new CollectionPredicate(values, true, Contains.without);
    var container = new YTDBCollatedHasContainer("name", predicate);
    values.arm();

    assertThat(container.evaluate(predicate, "match", new CaseInsensitiveCollate())).isFalse();
    assertThat(values.reads).isOne();
  }

  /** Within and without retain sequential exits while mutations invalidate only consumed slots. */
  @Test
  public void parameterizedMembershipTracksListMutationsAndRebinding() {
    var values = new MutableCountingList(List.of("FIRST", "MIDDLE", "LAST"));
    var within = new CollectionPredicate(values, true);
    var without = new CollectionPredicate(values, true, Contains.without);
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", within);

    assertThat(container.evaluate(within, "first", collate)).isTrue();
    assertThat(container.evaluate(within, "middle", collate)).isTrue();
    assertThat(container.evaluate(within, "last", collate)).isTrue();
    assertThat(container.evaluate(without, "missing", collate)).isTrue();
    assertThat(container.evaluate(without, "middle", collate)).isFalse();

    values.set(0, "ONE");
    values.set(1, "TWO");
    values.set(2, "THREE");
    assertThat(container.evaluate(within, "one", collate)).isTrue();
    assertThat(container.evaluate(within, "two", collate)).isTrue();
    assertThat(container.evaluate(within, "three", collate)).isTrue();

    values.add(1, "INSERTED");
    assertThat(container.evaluate(within, "inserted", collate)).isTrue();
    values.remove(2);
    assertThat(container.evaluate(within, "two", collate)).isFalse();
    var moved = values.remove(2);
    values.add(0, moved);
    assertThat(container.evaluate(within, "three", collate)).isTrue();
  }

  /** Shrinking an operand discards removed slots before equal values regrow at those positions. */
  @Test
  public void parameterizedMembershipTruncatesSlotsAfterShrink() {
    var values = new MutableCountingList(List.of("FIRST", "SECOND", "THIRD"));
    var predicate = new CollectionPredicate(values, true);
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);
    var first = (List<?>) container.transformedOperand(predicate, collate);
    var removedNormalized = first.get(2);

    values.remove(2);
    first.getFirst();
    values.add("THIRD");
    var regrown = (List<?>) container.transformedOperand(predicate, collate);

    assertThat(regrown.get(2)).isEqualTo(removedNormalized).isNotSameAs(removedNormalized);
  }

  /** The positional cap and string guards retain only explicitly bounded normalized values. */
  @Test
  public void parameterizedMembershipHonorsPositionAndLengthBounds() {
    var values = new MutableCountingList(stringValues(1_025));
    var predicate = new CollectionPredicate(values, true);
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertPositionReuse(container, predicate, collate, 1_023, true);
    assertPositionReuse(container, predicate, collate, 1_024, false);

    values.set(0, "A".repeat(127));
    assertPositionReuse(container, predicate, collate, 0, true);
    values.set(0, "A".repeat(128));
    assertPositionReuse(container, predicate, collate, 0, true);
    values.set(0, "A".repeat(129));
    assertPositionReuse(container, predicate, collate, 0, false);
    values.set(0, "\u0130".repeat(64));
    assertPositionReuse(container, predicate, collate, 0, true);
    values.set(0, "\u0130".repeat(65));
    assertPositionReuse(container, predicate, collate, 0, false);

    values.set(0, "SHORT");
    var beforeOversize = transformedPosition(container, predicate, collate, 0);
    values.set(0, "A".repeat(129));
    transformedPosition(container, predicate, collate, 0);
    values.set(0, "SHORT");
    assertThat(transformedPosition(container, predicate, collate, 0))
        .isNotSameAs(beforeOversize);
  }

  /** Eight identity entries use access order and evict the least recently used ninth entry. */
  @Test
  public void parameterizedMembershipBoundsIdentityCache() {
    var values = new MutableCountingList(List.of("VALUE"));
    var predicates = new ArrayList<CollectionPredicate>();
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", P.eq("unused"));
    var normalized = new ArrayList<Object>();
    for (var index = 0; index < 8; index++) {
      var predicate = new CollectionPredicate(values, true);
      predicates.add(predicate);
      normalized.add(transformedPosition(container, predicate, collate, 0));
    }

    assertThat(transformedPosition(container, predicates.getFirst(), collate, 0))
        .isSameAs(normalized.getFirst());
    transformedPosition(container, new CollectionPredicate(values, true), collate, 0);

    assertThat(transformedPosition(container, predicates.getFirst(), collate, 0))
        .isSameAs(normalized.getFirst());
    assertThat(transformedPosition(container, predicates.get(1), collate, 0))
        .isNotSameAs(normalized.get(1));

    var collates = new ArrayList<CaseInsensitiveCollate>();
    var collateNormalized = new ArrayList<Object>();
    var collateContainer = new YTDBCollatedHasContainer("name", predicates.getFirst());
    for (var index = 0; index < 9; index++) {
      var identity = new CaseInsensitiveCollate();
      collates.add(identity);
      collateNormalized.add(
          transformedPosition(collateContainer, predicates.getFirst(), identity, 0));
    }
    assertThat(transformedPosition(
        collateContainer, predicates.getFirst(), collates.getFirst(), 0))
        .isNotSameAs(collateNormalized.getFirst());
  }

  /** Updating first, middle, and last variables invalidates their resolved positions. */
  @Test
  public void parameterizedMembershipTracksVariableRebinding() {
    @SuppressWarnings("unchecked")
    var predicate = (P<Object>) (P<?>) P.within(List.of(
        GValue.of("first", "FIRST"),
        GValue.of("middle", "MIDDLE"),
        GValue.of("last", "LAST")));
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);

    assertThat(container.evaluate(predicate, "first", collate)).isTrue();
    assertThat(container.evaluate(predicate, "middle", collate)).isTrue();
    assertThat(container.evaluate(predicate, "last", collate)).isTrue();
    predicate.updateVariable("first", "ONE");
    predicate.updateVariable("middle", "TWO");
    predicate.updateVariable("last", "THREE");
    assertThat(container.evaluate(predicate, "first", collate)).isFalse();
    assertThat(container.evaluate(predicate, "middle", collate)).isFalse();
    assertThat(container.evaluate(predicate, "last", collate)).isFalse();
    assertThat(container.evaluate(predicate, "one", collate)).isTrue();
    assertThat(container.evaluate(predicate, "two", collate)).isTrue();
    assertThat(container.evaluate(predicate, "three", collate)).isTrue();
  }

  /** Ineligible shapes and collations clear identity state and retain existing fallback behavior. */
  @Test
  public void parameterizedMembershipTransitionsUseFallback() {
    @SuppressWarnings("unchecked")
    var predicate = (P<Object>) (P<?>) P.within(
        List.of(GValue.of("candidate", "VALUE")));
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);
    var cached = transformedPosition(container, predicate, collate, 0);

    predicate.setValue(List.of("VALUE"));
    assertThat(container.evaluate(predicate, "value", collate)).isTrue();
    predicate.setValue(Set.of("VALUE"));
    assertThat(predicate.getValue()).isInstanceOf(List.class);
    assertThat(predicate.isParameterized()).isFalse();
    assertThat(container.evaluate(predicate, "value", collate)).isTrue();
    predicate.setValue(List.of(List.of("VALUE")));
    assertThat(container.evaluate(predicate, List.of("value"), collate)).isTrue();
    predicate.setValue("VALUE");
    assertThatThrownBy(() -> container.evaluate(predicate, "value", collate))
        .isInstanceOf(ClassCastException.class);
    predicate.setValue(GValue.of("candidate", "VALUE"));
    assertThatThrownBy(() -> container.evaluate(predicate, "value", collate))
        .isInstanceOf(ClassCastException.class);
    predicate.setValue(List.of(GValue.of("candidate", "VALUE")));
    assertThat(transformedPosition(container, predicate, collate, 0)).isNotSameAs(cached);

    var subclass = new CountingCaseInsensitiveCollate();
    var first = transformedPosition(container, predicate, subclass, 0);
    var second = transformedPosition(container, predicate, subclass, 0);
    assertThat(second).isEqualTo(first).isNotSameAs(first);
  }

  /** A populated stale view stays uncached and cannot evict active identity entries. */
  @Test
  public void ineligibleTransitionTombstonesPopulatedView() throws Exception {
    @SuppressWarnings("unchecked")
    var predicate = (P<Object>) (P<?>) P.within(
        List.of(GValue.of("candidate", "OBSOLETE")));
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);
    var stale = (List<?>) container.transformedOperand(predicate, collate);
    assertThat(stale.getFirst()).isEqualTo("obsolete");

    predicate.setValue("INELIGIBLE");
    container.transformedOperand(predicate, collate);

    var livePredicates = new ArrayList<CollectionPredicate>();
    var liveNormalized = new ArrayList<Object>();
    for (var index = 0; index < 8; index++) {
      var live = new CollectionPredicate(List.of("LIVE-" + index), true);
      livePredicates.add(live);
      liveNormalized.add(transformedPosition(container, live, collate, 0));
    }
    assertThat((Map<?, ?>) parameterizedOperands(container)).hasSize(8);

    assertThat(stale.getFirst()).isEqualTo("obsolete");
    assertThat((Map<?, ?>) parameterizedOperands(container)).hasSize(8);
    assertThat(transformedPosition(container, livePredicates.getFirst(), collate, 0))
        .isSameAs(liveNormalized.getFirst());

    predicate.setValue(List.of(GValue.of("candidate", "CURRENT")));
    var current = transformedPosition(container, predicate, collate, 0);
    assertThat(current).isEqualTo("current");
    assertThat(transformedPosition(container, predicate, collate, 0)).isSameAs(current);
  }

  /** An unconsumed stale view cannot admit state, while a later eligible view can. */
  @Test
  public void ineligibleTransitionTombstonesUnconsumedView() throws Exception {
    @SuppressWarnings("unchecked")
    var predicate = (P<Object>) (P<?>) P.within(
        List.of(GValue.of("candidate", "OBSOLETE")));
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);
    var stale = (List<?>) container.transformedOperand(predicate, collate);
    assertThat(parameterizedOperands(container)).isNull();

    predicate.setValue("INELIGIBLE");
    container.transformedOperand(predicate, collate);
    assertThat(stale.getFirst()).isEqualTo("obsolete");
    assertThat(parameterizedOperands(container)).isNull();

    predicate.setValue(List.of(GValue.of("candidate", "CURRENT")));
    var current = transformedPosition(container, predicate, collate, 0);
    assertThat(current).isEqualTo("current");
    assertThat(transformedPosition(container, predicate, collate, 0)).isSameAs(current);
  }

  /** Non-string positions stay lazy and do not replay a consumed prefix before type failures. */
  @Test
  public void parameterizedMembershipKeepsUnsupportedMembersSequential() {
    var marker = new Object();
    var values = new MutableCountingList(List.of("FIRST", marker, List.of("NESTED")));
    var predicate = new CollectionPredicate(values, true);
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);

    values.resetReads();
    assertThat(container.evaluate(predicate, marker, collate)).isTrue();
    assertThat(values.reads).isEqualTo(2);
    values.resetReads();
    assertThat(container.evaluate(predicate, List.of("nested"), collate)).isTrue();
    assertThat(values.reads).isEqualTo(3);
  }

  /** Clone and serialization drop positional reuse while preserving parameterized behavior. */
  @Test
  public void parameterizedMembershipCacheResetsAcrossLifecycle() throws Exception {
    @SuppressWarnings("unchecked")
    var predicate = (P<Object>) (P<?>) P.within(
        List.of(GValue.of("candidate", "VALUE")));
    var collate = new CaseInsensitiveCollate();
    var container = new YTDBCollatedHasContainer("name", predicate);
    var original = transformedPosition(container, predicate, collate, 0);

    var clone = container.clone();
    var clonePredicate = clone.getPredicate();
    assertThat(transformedPosition(clone, clonePredicate, collate, 0)).isNotSameAs(original);

    var restored = roundTrip(container);
    var restoredPredicate = restored.getPredicate();
    assertThat(transformedPosition(restored, restoredPredicate, collate, 0))
        .isNotSameAs(original);
    assertThat(restored.evaluate(restoredPredicate, "value", collate)).isTrue();
  }

  /** Mixed supported and regex leaves collate only supported leaves and retain regex casing. */
  @Test
  public void mixedPredicateTreeDelegatesRegexLeaves() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var mixed = new YTDBCollatedHasContainer(
        "name", P.eq("BOB").or(TextP.regex("A.*")));
    var lowerCaseRegex = new YTDBCollatedHasContainer(
        "name", P.eq("MISSING").or(TextP.regex("a.*")));

    assertThat(mixed.test(bob)).isTrue();
    assertThat(mixed.test(alice)).isTrue();
    assertThat(lowerCaseRegex.test(alice)).isFalse();
  }

  /** Detached metadata reaches collation lookup and delegates, while invalid types keep errors. */
  @Test
  public void detachedAndInvalidTypeBehaviorRemainsNative() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    person.createProperty("rank", PropertyType.INTEGER).setCollate("ci");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "rank", 7);
    graph.tx().commit();
    var detached = DetachedFactory.detach(bob, true).property("name");

    assertThat(new YTDBCollatedHasContainer(T.value.getAccessor(), P.eq("BOB"))
        .test((Property<?>) detached)).isFalse();
    assertThatThrownBy(() -> new YTDBCollatedHasContainer(
        "rank", TextP.containing("7")).test(bob))
        .isInstanceOf(ClassCastException.class);
  }

  /** Native and translated routes return the same multisets for supported collation behavior. */
  @Test
  public void supportedRoutesMatchWithTranslatorEnabledAndDisabled() {
    var vertices = seedPeople();
    var source = vertices.getFirst();
    var bob = vertices.get(1);
    var alice = vertices.get(2);

    assertTranslatedVertexParity(
        () -> graph.traversal().V().hasLabel("Person").has("name", "BOB").asAdmin(),
        List.of(bob.id()));
    assertTranslatedVertexParity(
        () -> graph.traversal().V().has("name", P.within("BOB", "MISSING")).asAdmin(),
        List.of(bob.id()));
    assertTranslatedVertexParity(
        () -> graph.traversal().V(source.id()).out("knows")
            .has("name", P.gte("ALICE").and(P.lt("CAROL"))).asAdmin(),
        List.of(bob.id(), alice.id()));
    assertTranslatedVertexParity(
        () -> graph.traversal().V(source.id()).out("knows")
            .has("name", P.within("BOB", "MISSING")).asAdmin(),
        List.of(bob.id()));
    assertTextParity(TextP.containing("O"), List.of(bob.id()));
    assertTextParity(TextP.notContaining("O"), List.of(alice.id()));
    assertTextParity(TextP.startingWith("B"), List.of(bob.id()));
    assertTextParity(TextP.notStartingWith("B"), List.of(alice.id()));
    assertTextParity(TextP.endingWith("B"), List.of(bob.id()));
    assertTextParity(TextP.notEndingWith("B"), List.of(alice.id()));
    assertTranslatedVertexParity(
        () -> graph.traversal().V(source.id()).out("knows")
            .has("name", P.eq("BOB").or(P.eq("ALICE"))).asAdmin(),
        List.of(bob.id(), alice.id()));

    assertDeclinedVertexParity(
        () -> graph.traversal().V(bob.id()).has("name", "BOB").asAdmin(),
        List.of(bob.id()));
    assertDeclinedVertexParity(
        () -> graph.traversal().V(source.id())
            .where(__.out("knows").has("name", "BOB")).asAdmin(),
        List.of(source.id()));
    assertRouteParity(
        () -> graph.traversal().V(source.id()).out("knows")
            .has("name", "BOB").path().by(T.id).asAdmin(),
        List.of(List.of(source.id(), bob.id())),
        false,
        Path::objects);
  }

  /** Parallel matching edges preserve explicit multiplicity in native and MATCH execution. */
  @Test
  public void parallelCollatedMatchesPreserveMultiplicity() {
    var sourceClass = session.createVertexClass("Source");
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var source = graph.addVertex(T.label, sourceClass.getName());
    var bob = graph.addVertex(T.label, person.getName(), "name", "bob");
    source.addEdge("knows", bob);
    source.addEdge("knows", bob);
    graph.tx().commit();

    assertTranslatedVertexParity(
        () -> graph.traversal().V(source.id()).out("knows").has("name", "BOB").asAdmin(),
        List.of(bob.id(), bob.id()));
  }

  /** Collection equality agrees across native and translated post-hop execution. */
  @Test
  public void collectionEqualityMatchesWithTranslatorEnabledAndDisabled() {
    var person = session.createVertexClass("Person");
    person.createProperty("aliases", PropertyType.EMBEDDEDLIST)
        .setLinkedType(PropertyType.STRING)
        .setCollate("ci");
    person.createProperty("tags", PropertyType.EMBEDDEDSET)
        .setLinkedType(PropertyType.STRING)
        .setCollate("ci");
    var source = graph.addVertex(T.label, "Person");
    var target = graph.addVertex(
        T.label,
        "Person",
        "aliases",
        List.of("Bob", "Builder"),
        "tags",
        new LinkedHashSet<>(Set.of("Blue", "Green")));
    source.addEdge("sees", target);
    graph.tx().commit();

    assertTranslatedVertexParity(
        () -> graph.traversal().V(source.id()).out("sees")
            .has("aliases", P.eq(List.of("BOB", "BUILDER"))).asAdmin(),
        List.of(target.id()));
    assertTranslatedVertexParity(
        () -> graph.traversal().V(source.id()).out("sees")
            .has("tags", P.eq(GValue.of("expectedTags", Set.of("BLUE", "GREEN"))))
            .asAdmin(),
        List.of(target.id()));
  }

  /** Indexed graph-start equality keeps the original predicate and an index-backed SQL plan. */
  @Test
  public void indexedEqualityRemainsPlannerEligible() {
    var person = session.createVertexClass("Person");
    var property = person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var index = property.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    var predicate = P.eq("BOB");

    withTranslator(false, () -> {
      var traversal = graph.traversal().V().hasLabel("Person").has("name", predicate).asAdmin();
      traversal.applyStrategies();
      var graphStep = (YTDBGraphStep<?, ?>) traversal.getStartStep();
      assertThat(graphStep.getHasContainers().get(1).getPredicate()).isSameAs(predicate);
      assertThat(traversal.toList()).containsExactly(bob);
      assertThat(graphStep.getLastExecutionPlan()).isNotNull();
      assertThat(graphStep.getLastExecutionPlan().prettyPrint(0, 2)).contains(index);
    });
  }

  /** Lazy property transformation reads only the prefix consumed by an unequal comparison. */
  @Test
  public void caseInsensitiveListTransformationDoesNotCopyWholeInput() {
    var values = new CountingList(10_000);
    var transformed = YTDBCollatedHasContainer.transform(values, new CaseInsensitiveCollate());

    assertThat(YTDBCollatedHasContainer.valuesEqual(
        transformed, differentValues(values.size()))).isFalse();
    assertThat(values.reads).isLessThan(values.size());
  }

  private List<Vertex> seedPeople() {
    var sourceClass = session.createVertexClass("Source");
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var source = graph.addVertex(T.label, sourceClass.getName());
    var bob = graph.addVertex(T.label, person.getName(), "name", "bob");
    var alice = graph.addVertex(T.label, person.getName(), "name", "Alice");
    source.addEdge("knows", bob);
    source.addEdge("knows", alice);
    graph.tx().commit();
    return List.of(source, bob, alice);
  }

  private void assertTextParity(TextP predicate, List<Object> expected) {
    assertTranslatedVertexParity(
        () -> graph.traversal().V().has("name", predicate).asAdmin(), expected);
  }

  private void assertNames(Vertex source, P<?> predicate, String... names) {
    withTranslator(false, () -> assertThat(graph.traversal().V(source.id())
        .out("knows")
        .has("name", predicate)
        .values("name")
        .toList()).containsExactlyInAnyOrderElementsOf(List.of(names)));
  }

  private void assertTranslatedVertexParity(
      Supplier<Traversal.Admin<?, Vertex>> traversal, List<Object> expected) {
    assertRouteParity(traversal, expected, true, Vertex::id);
  }

  private void assertDeclinedVertexParity(
      Supplier<Traversal.Admin<?, Vertex>> traversal, List<Object> expected) {
    assertRouteParity(traversal, expected, false, Vertex::id);
  }

  /** The translated traversal used for route proof is also drained for the result assertion. */
  private <T, R> void assertRouteParity(
      Supplier<Traversal.Admin<?, T>> traversal,
      List<R> expected,
      boolean translationExpected,
      Function<T, R> projection) {
    var nativeResults = queryWithTranslator(false,
        () -> traversal.get().toList().stream().map(projection).toList());
    var translatedResults = queryWithTranslator(true, () -> {
      var admin = traversal.get();
      admin.applyStrategies();
      if (translationExpected) {
        assertThat(admin.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      } else {
        assertThat(admin.getStartStep()).isNotInstanceOf(YTDBMatchPlanStep.class);
      }
      return admin.toList().stream().map(projection).toList();
    });

    assertThat(nativeResults).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(translatedResults).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(translatedResults).containsExactlyInAnyOrderElementsOf(nativeResults);
  }

  private static YTDBCollatedHasContainer roundTrip(YTDBCollatedHasContainer container)
      throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var output = new ObjectOutputStream(bytes)) {
      output.writeObject(container);
    }
    try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (YTDBCollatedHasContainer) input.readObject();
    }
  }

  private static Object transformedOperands(YTDBCollatedHasContainer container)
      throws ReflectiveOperationException {
    var field = YTDBCollatedHasContainer.class.getDeclaredField("transformedOperands");
    field.setAccessible(true);
    return field.get(container);
  }

  private static Object parameterizedOperands(YTDBCollatedHasContainer container)
      throws ReflectiveOperationException {
    var field = YTDBCollatedHasContainer.class.getDeclaredField("parameterizedOperands");
    field.setAccessible(true);
    return field.get(container);
  }

  private static Object parameterizedOperandState(YTDBCollatedHasContainer container)
      throws ReflectiveOperationException {
    var operands = (Map<?, ?>) parameterizedOperands(container);
    assertThat(operands).hasSize(1);
    return operands.values().iterator().next();
  }

  private static List<String> stringValues(int size) {
    var values = new ArrayList<String>(size);
    for (var index = 0; index < size; index++) {
      values.add("VALUE-" + index);
    }
    return values;
  }

  private static List<String> differentValues(int size) {
    var values = new ArrayList<String>(size);
    values.add("different");
    for (var index = 1; index < size; index++) {
      values.add("value-" + index);
    }
    return values;
  }

  private static void assertPositionReuse(
      YTDBCollatedHasContainer container,
      P<?> predicate,
      Collate collate,
      int index,
      boolean expected) {
    var first = transformedPosition(container, predicate, collate, index);
    var second = transformedPosition(container, predicate, collate, index);
    if (expected) {
      assertThat(second).isSameAs(first);
    } else {
      assertThat(second).isEqualTo(first).isNotSameAs(first);
    }
  }

  private static Object transformedPosition(
      YTDBCollatedHasContainer container, P<?> predicate, Collate collate, int index) {
    return ((List<?>) container.transformedOperand(predicate, collate)).get(index);
  }

  private void withTranslator(boolean enabled, Runnable body) {
    queryWithTranslator(enabled, () -> {
      body.run();
      return null;
    });
  }

  private <T> T queryWithTranslator(boolean enabled, Supplier<T> body) {
    graph.tx().readWrite();
    var configuration = ((YTDBTransaction) graph.tx()).getDatabaseSession().getConfiguration();
    var original = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
    try {
      return body.get();
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
          original);
    }
  }

  private static final class CountingCaseInsensitiveCollate extends CaseInsensitiveCollate {

    private int stringTransforms;

    @Override
    public Object transform(Object value) {
      if (value instanceof String) {
        stringTransforms++;
      }
      return super.transform(value);
    }
  }

  private static final class RecordingCollate implements Collate {

    private Object seenOperand;
    private int collectionTransforms;

    @Override
    public String getName() {
      return "recording";
    }

    @Override
    public Object transform(Object value) {
      if (value instanceof Collection<?> collection) {
        seenOperand = value;
        collectionTransforms++;
        return collection.stream().map(this::transform).toList();
      }
      return value instanceof String string ? string.toLowerCase() : value;
    }
  }

  private static final class CollectionPredicate extends P<Object> {

    private final Collection<?> value;
    private final boolean parameterized;

    private CollectionPredicate(Collection<?> value, boolean parameterized) {
      this(value, parameterized, Contains.within);
    }

    private CollectionPredicate(
        Collection<?> value, boolean parameterized, Contains contains) {
      super(containsPredicate(contains), value);
      this.value = value;
      this.parameterized = parameterized;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public boolean isParameterized() {
      return parameterized;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PBiPredicate<Object, Object> containsPredicate(Contains contains) {
      return (PBiPredicate) contains;
    }
  }

  private static final class MutableCountingList extends AbstractList<Object> {

    private final List<Object> values;
    private int reads;

    private MutableCountingList(Collection<?> values) {
      this.values = new ArrayList<>(values);
    }

    @Override
    public Object get(int index) {
      reads++;
      return values.get(index);
    }

    @Override
    public int size() {
      return values.size();
    }

    @Override
    public Object set(int index, Object element) {
      return values.set(index, element);
    }

    @Override
    public void add(int index, Object element) {
      values.add(index, element);
    }

    @Override
    public Object remove(int index) {
      return values.remove(index);
    }

    private void resetReads() {
      reads = 0;
    }
  }

  private static final class NoScanMap extends HashMap<Integer, Object> {

    private NoScanMap(Map<?, ?> values) {
      for (var entry : values.entrySet()) {
        put((Integer) entry.getKey(), entry.getValue());
      }
    }

    @Override
    public Set<Integer> keySet() {
      throw new AssertionError("Stable size must not enumerate retained slots");
    }
  }

  private static final class ThrowingTailList extends AbstractList<Object> {

    private int reads;
    private boolean armed;

    @Override
    public Object get(int index) {
      reads++;
      if (index == 0) {
        return "MATCH";
      }
      if (armed) {
        throw new AssertionError("Membership consumed the lazy tail");
      }
      return "TAIL";
    }

    @Override
    public int size() {
      return 2;
    }

    private void arm() {
      reads = 0;
      armed = true;
    }
  }

  private static final class CountingObjectList extends AbstractList<Object> {

    private final int size;
    private int reads;

    private CountingObjectList(int size) {
      this.size = size;
    }

    @Override
    public Object get(int index) {
      reads++;
      return index;
    }

    @Override
    public int size() {
      return size;
    }

    private void resetReads() {
      reads = 0;
    }
  }

  private static final class CountingList extends AbstractList<String> {

    private final List<String> values;
    private int reads;

    private CountingList(int size) {
      values = new ArrayList<>(size);
      for (var index = 0; index < size; index++) {
        values.add("value-" + index);
      }
    }

    @Override
    public String get(int index) {
      reads++;
      return values.get(index);
    }

    @Override
    public int size() {
      return values.size();
    }
  }
}
