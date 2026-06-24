package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.assertj.core.api.ListAssert;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

@Category(SequentialTest.class)
@RunWith(GremlinProcessRunner.class)
public class YTDBHasLabelProcessTest extends YTDBAbstractGremlinTest {

  private YTDBGraphTraversalSource gp() {
    return g().with(YTDBQueryConfigParam.polymorphicQuery, true);
  }

  private YTDBGraphTraversalSource gn() {
    return g().with(YTDBQueryConfigParam.polymorphicQuery, false);
  }

  private void createSimpleHierarchy() {
    g().command("CREATE CLASS Grandparent IF NOT EXISTS EXTENDS V");
    g().command("CREATE CLASS Parent IF NOT EXISTS EXTENDS Grandparent");
    g().command("CREATE CLASS Child IF NOT EXISTS EXTENDS Parent");
  }

  @Test
  public void testPolymorphicSimple() {
    createSimpleHierarchy();

    g().addV("Child").next();

    checkSize(1, () -> gp().V().hasLabel("Child"));
    checkSize(1, () -> gp().V().hasLabel("Parent"));
    checkSize(1, () -> gp().V().hasLabel("Grandparent"));

    g().addV("Parent").next();

    checkSize(1, () -> gp().V().hasLabel("Child"));
    checkSize(2, () -> gp().V().hasLabel("Parent"));
    checkSize(2, () -> gp().V().hasLabel("Grandparent"));

    g().addV("Grandparent").next();

    checkSize(1, () -> gp().V().hasLabel("Child"));
    checkSize(2, () -> gp().V().hasLabel("Parent"));
    checkSize(3, () -> gp().V().hasLabel("Grandparent"));
  }

  @Test
  public void testPolymorphicWithAdditionalHasLabelFiltering() {
    createSimpleHierarchy();

    g().addV("Child").next();

    checkSize(
        1,
        () -> gp().V().hasLabel("Parent").hasLabel("Child"));

    checkSize(
        1,
        () -> gp().V().hasLabel("Child").hasLabel("Parent"));

    checkSize(
        0,
        () -> gp().V().hasLabel("Parent").where(__.not(__.hasLabel("Child"))));

    checkSize(
        0,
        () -> gp().V().hasLabel("Child").where(__.not(__.hasLabel("Parent"))));
  }

  @Test
  public void testNonPolymorphicSimple() {
    createSimpleHierarchy();

    g().addV("Child").next();
    checkSize(1, () -> gn().V().hasLabel("Child"));
    checkSize(0, () -> gn().V().hasLabel("Parent"));
    checkSize(0, () -> gn().V().hasLabel("Grandparent"));

    g().addV("Parent").next();

    checkSize(1, () -> gn().V().hasLabel("Child"));
    checkSize(1, () -> gn().V().hasLabel("Parent"));
    checkSize(0, () -> gn().V().hasLabel("Grandparent"));

    g().addV("Grandparent").next();

    checkSize(1, () -> gn().V().hasLabel("Child"));
    checkSize(1, () -> gn().V().hasLabel("Parent"));
    checkSize(1, () -> gn().V().hasLabel("Grandparent"));
  }

  @Test
  public void testPolymorphicByIdHasLabel() {
    createSimpleHierarchy();

    final var child = g().addV("Child").next();
    final var childId = child.id();

    // The by-ids path (V(id).hasLabel) must honour polymorphism exactly like the class-scan path
    // (V().hasLabel): a Child IS a Parent IS a Grandparent.
    checkSize(1, () -> gp().V(childId).hasLabel("Child"));
    checkSize(1, () -> gp().V(childId).hasLabel("Parent"));
    checkSize(1, () -> gp().V(childId).hasLabel("Grandparent"));
  }

  @Test
  public void testNonPolymorphicByIdHasLabel() {
    createSimpleHierarchy();

    final var child = g().addV("Child").next();
    final var childId = child.id();

    // Non-polymorphic: only the exact concrete label matches.
    checkSize(1, () -> gn().V(childId).hasLabel("Child"));
    checkSize(0, () -> gn().V(childId).hasLabel("Parent"));
    checkSize(0, () -> gn().V(childId).hasLabel("Grandparent"));
  }

  @Test
  public void testPolymorphicHasIdHasLabel() {
    createSimpleHierarchy();

    final var child = g().addV("Child").next();
    final var childId = child.id();

    // Class-scan with an id filter: V().hasId(id).hasLabel(...) — should be polymorphic.
    checkSize(1, () -> gp().V().hasId(childId).hasLabel("Child"));
    checkSize(1, () -> gp().V().hasId(childId).hasLabel("Parent"));
    checkSize(1, () -> gp().V().hasId(childId).hasLabel("Grandparent"));
  }

  @Test
  public void testNonPolymorphicHasIdHasLabel() {
    createSimpleHierarchy();

    final var child = g().addV("Child").next();
    final var childId = child.id();

    checkSize(1, () -> gn().V().hasId(childId).hasLabel("Child"));
    checkSize(0, () -> gn().V().hasId(childId).hasLabel("Parent"));
    checkSize(0, () -> gn().V().hasId(childId).hasLabel("Grandparent"));
  }

  @Test
  public void testByIdHasLabelCountHonoursId() {
    createSimpleHierarchy();

    // Two vertices of the same class; only one is pinned by id. The count() rewrite must keep the
    // id filter (regression for the count id-drop): without the getIds() guard the strategy would
    // rewrite g.V(id).hasLabel(...).count() into a whole-class count and report 2 instead of 1.
    final var child = g().addV("Child").next();
    final var childId = child.id();
    final var secondChild = g().addV("Child").next();

    // checkSize asserts toList().size() and count() agree, so each line also pins the equality.
    checkSize(1, () -> gp().V(childId).hasLabel("Child"));
    checkSize(1, () -> gp().V(childId).hasLabel("Parent"));
    checkSize(1, () -> gp().V(childId).hasLabel("Grandparent"));

    checkSize(1, () -> gn().V(childId).hasLabel("Child"));
    checkSize(0, () -> gn().V(childId).hasLabel("Parent"));
    checkSize(0, () -> gn().V(childId).hasLabel("Grandparent"));

    // Two pinned ids: the count guard must fire for ids.length > 1 too, and the per-element filter
    // is applied independently to each loaded element. Both children match polymorphically, so
    // count() and toList().size() agree at 2 (a third Child exists but is unpinned).
    g().addV("Child").next();
    checkSize(2, () -> gp().V(childId, secondChild.id()).hasLabel("Parent"));
    checkSize(2, () -> gn().V(childId, secondChild.id()).hasLabel("Child"));
  }

  @Test
  public void testByIdHasLabelEdgePolymorphism() {
    // Edge class hierarchy: SubEdge extends SuperEdge. The by-id edge path must honour polymorphism
    // the same way the by-id vertex path does, since both go through YTDBGraphStep.elements().
    g().command("CREATE CLASS SuperEdge IF NOT EXISTS EXTENDS E");
    g().command("CREATE CLASS SubEdge IF NOT EXISTS EXTENDS SuperEdge");

    final var from = g().addV("V").next();
    final var to = g().addV("V").next();
    final var edge = g().V(from.id()).addE("SubEdge").to(__.V(to.id())).next();
    final var edgeId = edge.id();

    // A second SubEdge between fresh vertices makes the count() path falsifiable: the by-id count
    // guard (YTDBGraphCountStrategy) must keep the id filter and report 1, not the whole-class 2.
    final var otherFrom = g().addV("V").next();
    final var otherTo = g().addV("V").next();
    g().V(otherFrom.id()).addE("SubEdge").to(__.V(otherTo.id())).next();

    // Polymorphic: a SubEdge IS-A SuperEdge, so both the exact and the supertype label match.
    assertEquals(1, gp().E(edgeId).hasLabel("SubEdge").toList().size());
    assertEquals(1, gp().E(edgeId).hasLabel("SuperEdge").toList().size());

    // Non-polymorphic: only the exact concrete label matches.
    assertEquals(1, gn().E(edgeId).hasLabel("SubEdge").toList().size());
    assertEquals(0, gn().E(edgeId).hasLabel("SuperEdge").toList().size());

    // The edge count path goes through the same id-drop guard as the vertex path; assert count()
    // agrees with toList().size() for both the polymorphic and the non-polymorphic edge by-id case.
    assertEquals(1L, gp().E(edgeId).hasLabel("SuperEdge").count().next().longValue());
    assertEquals(1, gp().E(edgeId).hasLabel("SuperEdge").toList().size());
    assertEquals(1L, gn().E(edgeId).hasLabel("SubEdge").count().next().longValue());
    assertEquals(1, gn().E(edgeId).hasLabel("SubEdge").toList().size());
  }

  @Test
  public void testByIdHasLabelMultipleArguments() {
    createSimpleHierarchy();

    final var child = g().addV("Child").next();
    final var childId = child.id();

    // Multi-argument hasLabel carries OR semantics within the single container. A Child matches the
    // "Parent" disjunct polymorphically; it never matches the unrelated "Grandparent" sibling here
    // by anything other than supertype, so the OR plus polymorphism both get exercised.
    checkSize(1, () -> gp().V(childId).hasLabel("Child", "Parent"));
    checkSize(1, () -> gp().V(childId).hasLabel("Parent", "Grandparent"));
    checkSize(0, () -> gp().V(childId).hasLabel("Unrelated", "AlsoUnrelated"));

    // Non-polymorphic: the OR still applies, but only against the exact concrete class name.
    checkSize(1, () -> gn().V(childId).hasLabel("Child", "Parent"));
    checkSize(0, () -> gn().V(childId).hasLabel("Parent", "Grandparent"));
  }

  @Test
  public void testByIdHasLabelWithPropertyFilter() {
    createSimpleHierarchy();

    final var child = g().addV("Child").property("name", "keep").next();
    final var childId = child.id();

    // On the by-id path, a property has(...) lands in otherContainers (HasContainer.testAll) and the
    // hasLabel in labelContainers (the polymorphic matcher); the two partitions are ANDed. The
    // polymorphic label matches the same pinned element either way, so the property filter is what
    // flips the result: it matches only when the property value agrees.
    checkSize(1, () -> gp().V(childId).hasLabel("Parent").has("name", "keep"));
    checkSize(0, () -> gp().V(childId).hasLabel("Parent").has("name", "other"));
  }

  @Test
  public void testByIdChainedHasLabelIsConjunction() {
    createSimpleHierarchy();
    // Sibling shares the Grandparent supertype with Child but is not on Child's own ancestry chain.
    g().command("CREATE CLASS Sibling IF NOT EXISTS EXTENDS Grandparent");

    final var child = g().addV("Child").next();
    final var childId = child.id();

    // Two distinct hasLabel containers on the by-id path are ANDed via allMatch (not OR-collapsed).
    // A Child IS-A Parent AND IS-A Grandparent polymorphically, so the conjunction matches.
    checkSize(1, () -> gp().V(childId).hasLabel("Parent").hasLabel("Grandparent"));

    // A Child is not a Sibling, so the second container fails the AND and the element is excluded.
    // An allMatch->anyMatch (AND->OR) regression would wrongly return 1 here.
    checkSize(0, () -> gp().V(childId).hasLabel("Parent").hasLabel("Sibling"));
  }

  @Test
  public void testByIdHasLabelSiblingClassDoesNotMatch() {
    createSimpleHierarchy();
    // Sibling IS-A Grandparent but is not a Parent or a Child; it has a real, non-empty superclass
    // chain (Grandparent) that the polymorphic matcher walks and must still reject for "Parent".
    g().command("CREATE CLASS Sibling IF NOT EXISTS EXTENDS Grandparent");

    final var sibling = g().addV("Sibling").next();
    final var siblingId = sibling.id();

    // The pinned element belongs to an unrelated sibling class; the polymorphic supertype walk
    // matches its real ancestor but excludes the cousin labels.
    checkSize(1, () -> gp().V(siblingId).hasLabel("Grandparent"));
    checkSize(0, () -> gp().V(siblingId).hasLabel("Parent"));
    checkSize(0, () -> gp().V(siblingId).hasLabel("Child"));
  }

  private static void checkSize(int size, Supplier<GraphTraversal<Vertex, Vertex>> query) {
    assertEquals(size, query.get().toList().size());
    assertEquals(size, query.get().count().next().longValue());
  }

  @Test
  public void testPolymorphicComplex() {

    //            animal
    //     /     /      \      \
    // fish    mammal   insect  bird
    //        /     \     \
    //      cat    human   bee

    g().command("CREATE CLASS animal IF NOT EXISTS EXTENDS V");
    g().command("CREATE CLASS fish IF NOT EXISTS EXTENDS animal");
    g().command("CREATE CLASS mammal IF NOT EXISTS EXTENDS animal");
    g().command("CREATE CLASS bird IF NOT EXISTS EXTENDS animal");
    g().command("CREATE CLASS insect IF NOT EXISTS EXTENDS animal");
    g().command("CREATE CLASS bee IF NOT EXISTS EXTENDS insect");
    g().command("CREATE CLASS cat IF NOT EXISTS EXTENDS mammal");
    g().command("CREATE CLASS human IF NOT EXISTS EXTENDS mammal");

    g().addV("animal").property("name", "someAnimal").iterate();
    g().addV("fish").property("name", "someFish").iterate();
    g().addV("cat").property("name", "someCat").iterate();
    g().addV("cat").property("name", "otherCat").iterate();
    g().addV("insect").property("name", "someInsect").iterate();

    assertThatNames("animal", true)
        .containsExactlyInAnyOrder("someAnimal", "someFish", "someCat", "otherCat", "someInsect");

    assertThatNames("animal", false)
        .containsExactlyInAnyOrder("someAnimal");

    assertThatNames("fish", true)
        .containsExactlyInAnyOrder("someFish");

    assertThatNames("fish", false)
        .containsExactlyInAnyOrder("someFish");

    assertThatNames("mammal", true)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames("mammal", false)
        .isEmpty();

    assertThatNames("insect", true)
        .containsExactlyInAnyOrder("someInsect");

    assertThatNames("insect", false)
        .containsExactlyInAnyOrder("someInsect");

    assertThatNames("bird", true)
        .isEmpty();

    assertThatNames("bird", false)
        .isEmpty();

    assertThatNames("cat", true)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames("cat", false)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames("human", true)
        .isEmpty();

    assertThatNames("human", false)
        .isEmpty();
  }

  @Test
  public void testPolymorphicWithFilters() {

    g().command("CREATE CLASS character EXTENDS V");
    g().command("CREATE CLASS pirate EXTENDS character");

    g().addV("pirate")
        .property("name", "Billy Bones")
        .property("noOfLegs", 2)
        .property("noOfEyes", 2)
        .iterate();

    g().addV("pirate")
        .property("name", "John Silver")
        .property("noOfLegs", 1)
        .property("noOfEyes", 2)
        .iterate();

    g().addV("pirate")
        .property("name", "Blind Pew")
        .property("noOfLegs", 2)
        .property("noOfEyes", 0)
        .iterate();

    g().addV("character")
        .property("name", "Jim Hawkins")
        .property("noOfLegs", 2)
        .property("noOfEyes", 2)
        .iterate();

    assertThatNames("character", false)
        .containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames("character", true)
        .containsExactlyInAnyOrder("Jim Hawkins", "Billy Bones", "John Silver", "Blind Pew");

    assertThatNames(
        "character", true,
        t -> t.has("noOfEyes", P.lt(2))).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        "character", false,
        t -> t.has("noOfEyes", P.lt(2))).isEmpty();

    assertThatNames(
        "pirate", true,
        t -> t.has("noOfEyes", P.lt(2))).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        "pirate", false,
        t -> t.has("noOfEyes", P.lt(2))).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        "character", false,
        t -> t.has("name", TextP.startingWith("J"))).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(
        "character", true,
        t -> t.has("name", TextP.startingWith("J")))
        .containsExactlyInAnyOrder("Jim Hawkins", "John Silver");

    assertThatNames(
        "character", false,
        t -> t.has("name", TextP.startingWith("J"))).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(
        "character", true,
        t -> t.has("name", TextP.endingWith("s")).has("noOfLegs", P.gt(1)))
        .containsExactlyInAnyOrder("Jim Hawkins", "Billy Bones");

    assertThatNames(
        "character", false,
        t -> t.has("name", TextP.endingWith("s")).has("noOfLegs", P.gt(1)))
        .containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames("character", false,
        t -> t.has("noOfLegs", P.neq(2))).isEmpty();

    assertThatNames("character", true,
        t -> t.has("noOfLegs", P.neq(2))).containsExactlyInAnyOrder("John Silver");
  }

  @Test
  public void testPolymorphicMultipleLabels() {
    g().command("CREATE CLASS animal IF NOT EXISTS EXTENDS V");
    g().command("CREATE CLASS mammal IF NOT EXISTS  EXTENDS animal");
    g().command("CREATE CLASS dolphin IF NOT EXISTS EXTENDS mammal");
    g().command("CREATE CLASS human IF NOT EXISTS EXTENDS mammal");
    g().command("CREATE CLASS fish IF NOT EXISTS EXTENDS animal");

    g().addV("animal").property("name", "someAnimal").iterate();
    g().addV("mammal").property("name", "someMammal").iterate();
    g().addV("dolphin").property("name", "someDolphin").iterate();
    g().addV("human").property("name", "someHuman").iterate();
    g().addV("fish").property("name", "someFish").iterate();

    // union semantics
    assertThatNames(
        List.of(
            List.of("animal", "mammal")),
        true).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman",
            "someFish");

    assertThatNames(
        List.of(
            List.of("animal", "mammal")),
        false).containsExactlyInAnyOrder("someAnimal", "someMammal");

    assertThatNames(
        List.of(
            List.of("animal", "mammal", "human")),
        true).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman",
            "someFish");

    assertThatNames(
        List.of(
            List.of("animal", "mammal", "human")),
        false).containsExactlyInAnyOrder("someAnimal", "someMammal", "someHuman");

    assertThatNames(
        List.of(
            List.of("mammal", "dolphin", "fish")),
        true).containsExactlyInAnyOrder("someMammal", "someDolphin", "someFish", "someHuman");

    assertThatNames(
        List.of(
            List.of("mammal", "dolphin", "fish")),
        false).containsExactlyInAnyOrder("someMammal", "someDolphin", "someFish");

    assertThatNames(
        List.of(
            List.of("mammal", "human"),
            List.of("fish"),
            List.of("animal")),
        true).isEmpty();

    assertThatNames(
        List.of(
            List.of("mammal", "human"),
            List.of("fish"),
            List.of("animal")),
        false).isEmpty();

    // intersection semantics
    assertThatNames(
        List.of(
            List.of("animal"),
            List.of("mammal")),
        true).containsExactlyInAnyOrder("someMammal", "someDolphin", "someHuman");

    assertThatNames(
        List.of(
            List.of("animal"),
            List.of("mammal")),
        false).isEmpty();

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("human")),
        true).containsExactlyInAnyOrder("someHuman");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("human")),
        false).isEmpty();

    assertThatNames(
        List.of(
            List.of("animal"),
            List.of("animal")),
        true).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman",
            "someFish");

    assertThatNames(
        List.of(
            List.of("animal"),
            List.of("animal")),
        false).containsExactlyInAnyOrder("someAnimal");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("mammal", "human")),
        true).containsExactlyInAnyOrder("someMammal", "someDolphin", "someHuman");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("mammal", "human")),
        false).containsExactlyInAnyOrder("someMammal");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("animal", "human")),
        true).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman",
            "someFish");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("animal", "human")),
        false).containsExactlyInAnyOrder("someAnimal");
  }

  @Test
  public void testHasLabelWithGraphStepMidTraversal() {
    createSimpleHierarchy();

    final var childCount = 11;
    final var parentCount = 5;
    final var grandparentCount = 3;

    for (var i = 0; i < grandparentCount; i++) {
      g().addV("Grandparent").iterate();
    }
    for (var i = 0; i < parentCount; i++) {
      g().addV("Parent").iterate();
    }
    for (var i = 0; i < childCount; i++) {
      g().addV("Child").iterate();
    }

    checkSize(
        childCount * childCount,
        () -> gp()
            .V().hasLabel("Child")
            .V().hasLabel("Child"));

    checkSize(
        parentCount * parentCount,
        () -> gn()
            .V().hasLabel("Parent")
            .V().hasLabel("Parent"));
    checkSize(
        parentCount * parentCount,
        () -> gn()
            .V().where(__.hasLabel("Parent"))
            .V().where(__.hasLabel("Parent")));
    checkSize(
        (childCount + parentCount) * (childCount + parentCount),
        () -> gp()
            .V().hasLabel("Parent")
            .V().hasLabel("Parent"));
    checkSize(
        (childCount + parentCount) * (childCount + parentCount),
        () -> gp()
            .V().where(__.hasLabel("Parent"))
            .V().where(__.hasLabel("Parent")));
    checkSize(
        (parentCount + childCount) * grandparentCount,
        () -> gp()
            .V().hasLabel("Parent")
            .V().hasLabel("Grandparent").not(__.hasLabel("Parent")));
  }

  @Test
  public void testCompoundQuery() {
    createSimpleHierarchy();

    g().addV("Child").property("name", "child1").property("age", 15).iterate();
    g().addV("Child").property("name", "child2").property("age", 25).iterate();
    g().addV("Child").property("name", "child3").property("age", 35).iterate();

    for (var label : List.of("Child", "Parent", "Grandparent")) {
      final var result = g()
          .union(
              __.V().hasLabel(label).has("name", "child1"),
              __.V().hasLabel(label).has("name", "child2"))
          .aggregate("var1")
          .fold()
          .V()
          .hasLabel(label)
          .has("age", P.gt(20))
          .where(P.within("var1"))
          .toList();

      assertEquals(1, result.size());
      assertEquals("child2", result.getFirst().value("name"));
    }
  }

  @After
  @Override
  public void tearDown() {
    g().V().drop().iterate();
  }

  private ListAssert<String> assertThatNames(String clazz, boolean polymorphic) {
    return assertThatNames(clazz, polymorphic, Function.identity());
  }

  private ListAssert<String> assertThatNames(String clazz, boolean polymorphic,
      Function<YTDBGraphTraversal<Vertex, Vertex>, YTDBGraphTraversal<Vertex, Vertex>> filter) {

    return assertThatNames(List.of(List.of(clazz)), polymorphic, filter);
  }

  private ListAssert<String> assertThatNames(List<List<String>> classes, boolean polymorphic) {
    return assertThatNames(classes, polymorphic, Function.identity());
  }

  private ListAssert<String> assertThatNames(List<List<String>> classes, boolean polymorphic,
      Function<YTDBGraphTraversal<Vertex, Vertex>, YTDBGraphTraversal<Vertex, Vertex>> filter) {

    var t = g()
        .with(YTDBQueryConfigParam.polymorphicQuery, polymorphic)
        .V();

    for (var classesInner : classes) {
      final var head = classesInner.getFirst();
      final var tail = classesInner.stream().skip(1).toArray(String[]::new);

      t = t.hasLabel(head, tail);
    }

    return assertThat(filter.apply(t).<String>values("name").toList());
  }
}
