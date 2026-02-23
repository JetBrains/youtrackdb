package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
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
import org.junit.runner.RunWith;

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
        () -> gp().V().hasLabel("Parent").hasLabel("Child")
    );

    checkSize(
        1,
        () -> gp().V().hasLabel("Child").hasLabel("Parent")
    );

    checkSize(
        0,
        () -> gp().V().hasLabel("Parent").where(__.not(__.hasLabel("Child")))
    );

    checkSize(
        0,
        () -> gp().V().hasLabel("Child").where(__.not(__.hasLabel("Parent")))
    );
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
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        "character", false,
        t -> t.has("noOfEyes", P.lt(2))
    ).isEmpty();

    assertThatNames(
        "pirate", true,
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        "pirate", false,
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        "character", false,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(
        "character", true,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins", "John Silver");

    assertThatNames(
        "character", false,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(
        "character", true,
        t -> t.has("name", TextP.endingWith("s")).has("noOfLegs", P.gt(1))
    ).containsExactlyInAnyOrder("Jim Hawkins", "Billy Bones");

    assertThatNames(
        "character", false,
        t -> t.has("name", TextP.endingWith("s")).has("noOfLegs", P.gt(1))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames("character", false,
        t -> t.has("noOfLegs", P.neq(2))
    ).isEmpty();

    assertThatNames("character", true,
        t -> t.has("noOfLegs", P.neq(2))
    ).containsExactlyInAnyOrder("John Silver");
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
            List.of("animal", "mammal")
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of("animal", "mammal")
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal", "someMammal");

    assertThatNames(
        List.of(
            List.of("animal", "mammal", "human")
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of("animal", "mammal", "human")
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someHuman");

    assertThatNames(
        List.of(
            List.of("mammal", "dolphin", "fish")
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someFish", "someHuman");

    assertThatNames(
        List.of(
            List.of("mammal", "dolphin", "fish")
        ),
        false
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someFish");

    assertThatNames(
        List.of(
            List.of("mammal", "human"),
            List.of("fish"),
            List.of("animal")
        ),
        true
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of("mammal", "human"),
            List.of("fish"),
            List.of("animal")
        ),
        false
    ).isEmpty();

    // intersection semantics
    assertThatNames(
        List.of(
            List.of("animal"),
            List.of("mammal")
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someHuman");

    assertThatNames(
        List.of(
            List.of("animal"),
            List.of("mammal")
        ),
        false
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("human")
        ),
        true
    ).containsExactlyInAnyOrder("someHuman");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("human")
        ),
        false
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of("animal"),
            List.of("animal")
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of("animal"),
            List.of("animal")
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("mammal", "human")
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someHuman");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("mammal", "human")
        ),
        false
    ).containsExactlyInAnyOrder("someMammal");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("animal", "human")
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of("animal", "mammal"),
            List.of("animal", "human")
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal");
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
            .V().hasLabel("Child")
    );

    checkSize(
        parentCount * parentCount,
        () -> gn()
            .V().hasLabel("Parent")
            .V().hasLabel("Parent")
    );
    checkSize(
        parentCount * parentCount,
        () -> gn()
            .V().where(__.hasLabel("Parent"))
            .V().where(__.hasLabel("Parent"))
    );
    checkSize(
        (childCount + parentCount) * (childCount + parentCount),
        () -> gp()
            .V().hasLabel("Parent")
            .V().hasLabel("Parent")
    );
    checkSize(
        (childCount + parentCount) * (childCount + parentCount),
        () -> gp()
            .V().where(__.hasLabel("Parent"))
            .V().where(__.hasLabel("Parent"))
    );
    checkSize(
        (parentCount + childCount) * grandparentCount,
        () -> gp()
            .V().hasLabel("Parent")
            .V().hasLabel("Grandparent").not(__.hasLabel("Parent"))
    );
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
              __.V().hasLabel(label).has("name", "child2")
          )
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
