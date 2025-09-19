package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import java.util.List;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.assertj.core.api.ListAssert;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;


public class GraphQueryTest extends GraphBaseTest {

  @Test
  public void shouldCountVerticesEdges() {
    initGraph(graph);

    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();
    Assert.assertEquals(4L, count.longValue());

    count = graph.traversal().V().hasLabel("Person").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().V().hasLabel("Animal").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().V().hasLabel("Animal", "Person").count().toList().getFirst();
    Assert.assertEquals(4L, count.longValue());

    // Count on E

    count = graph.traversal().E().count().toList().getFirst();
    Assert.assertEquals(3L, count.longValue());

    count = graph.traversal().E().hasLabel("HasFriend").count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().E().hasLabel("HasAnimal").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().E().hasLabel("HasAnimal", "HasFriend").count().toList().getFirst();
    Assert.assertEquals(3L, count.longValue());

    // Inverted Count

    count = graph.traversal().V().hasLabel("HasFriend").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    count = graph.traversal().E().hasLabel("Person").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    // More Complex Count

    count =
        graph
            .traversal()
            .V()
            .has("Person", "name", "Jon")
            .out("HasFriend", "HasAnimal")
            .count()
            .toList()
            .getFirst();
    Assert.assertEquals(2L, count.longValue());

    // With Polymorphism

    count = graph.traversal().V().has("Person", "name", "Jon").
        out("E").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    // With Base Class V/E

    count = graph.traversal().V().has("name", "Jon").count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().E().has("name", "Jon").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    count = graph.traversal().V().has("name", "Jon").out("E").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().E().has("marker", 10).count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().V().has("marker", 10).count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldCountVerticesEdgesOnTXRollback() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("name", "Jon");

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().rollback();

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldExecuteTraversalWithSpecialCharacters() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("identifier", 1);

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().commit();

    count = graph.traversal().V().has("~identifier", 1).count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldNotBlowWithWrongClass() {
    initGraph(graph);

    var count = graph.traversal().V().hasLabel("Wrong").toList().size();

    Assert.assertEquals(0, count);

    // Count on Person + Wrong Class

    count = graph.traversal().V().hasLabel("Person", "Wrong").toList().size();

    Assert.assertEquals(2, count);
  }

  @Test
  public void hasIdWithString() {
    final var labelVertex = "VertexLabel";
    var v1 = graph.addVertex(labelVertex);

    graph.tx().commit();

    Assert.assertEquals(1, graph.traversal().V().hasId(v1.id()).toList().size());
  }

  @Test
  @Ignore
  public void hasIdWithVertex() {
    final var labelVertex = "VertexLabel";
    var v1 = graph.addVertex(labelVertex);

    graph.tx().commit();

    Assert.assertEquals(1, graph.traversal().V().hasId(v1).toList().size());
  }

  @Test
  public void shouldCountVerticesEdgesOnTXCommit() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("name", "Jon");

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().commit();

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());
  }

  @Test
  public void shouldWorkWithTwoLabels() {
    session.getSchema().createVertexClass("Person");

    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex(T.label, "Person", "name", "Jon");

    count =
        graph
            .traversal()
            .V()
            .hasLabel("Person")
            .has("name", "Jon")
            .hasLabel("Person")
            .has("name", "Jon")
            .count()
            .toList()
            .getFirst();

    Assert.assertEquals(1L, count.longValue());
  }

  protected void initGraph(Graph graph) {
    var schema = session.getSchema();

    schema.createVertexClass("Person");
    schema.createVertexClass("Animal");
    schema.createEdgeClass("HasFriend");
    session.createEdgeClass("HasAnimal");

    var v1 = graph.addVertex(T.label, "Person", "name", "Jon");
    var v2 = graph.addVertex(T.label, "Person", "name", "Frank");

    v1.addEdge("HasFriend", v2);

    var v3 = graph.addVertex(T.label, "Animal", "name", "Foo");
    var v4 = graph.addVertex(T.label, "Animal", "name", "Bar");

    v1.addEdge("HasAnimal", v3, "marker", 10);
    v2.addEdge("HasAnimal", v4);
  }

  @Test
  public void testPolymorphicLabelsSimple() {

    //            animal
    //     /     /      \      \
    // fish    mammal   insect  bird
    //        /     \     \
    //      cat    human   bee

    final var prefix = "testPolymorphicLabelsSimple_";
    final var animal = session.createVertexClass(prefix + "animal");
    final var fish = session.createClass(prefix + "fish", animal.getName());
    final var mammal = session.createClass(prefix + "mammal", animal.getName());
    final var bird = session.createClass(prefix + "bird", animal.getName());
    final var insect = session.createClass(prefix + "insect", animal.getName());
    final var bee = session.createClass(prefix + "bee", insect.getName());
    final var cat = session.createClass(prefix + "cat", mammal.getName());
    final var human = session.createClass(prefix + "human", mammal.getName());

    session.executeInTx(tx -> {
      tx.newVertex(animal).setProperty("name", "someAnimal");

      tx.newVertex(fish).setProperty("name", "someFish");

      tx.newVertex(cat).setProperty("name", "someCat");

      tx.newVertex(cat).setProperty("name", "otherCat");

      tx.newVertex(insect).setProperty("name", "someInsect");
    });

    assertThatNames(animal, true)
        .containsExactlyInAnyOrder("someAnimal", "someFish", "someCat", "otherCat", "someInsect");

    assertThatNames(animal, false)
        .containsExactlyInAnyOrder("someAnimal");

    assertThatNames(fish, true)
        .containsExactlyInAnyOrder("someFish");

    assertThatNames(fish, false)
        .containsExactlyInAnyOrder("someFish");

    assertThatNames(mammal, true)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames(mammal, false)
        .isEmpty();

    assertThatNames(insect, true)
        .containsExactlyInAnyOrder("someInsect");

    assertThatNames(insect, false)
        .containsExactlyInAnyOrder("someInsect");

    assertThatNames(bird, true)
        .isEmpty();

    assertThatNames(bird, false)
        .isEmpty();

    assertThatNames(cat, true)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames(cat, false)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames(human, true)
        .isEmpty();

    assertThatNames(human, false)
        .isEmpty();
  }

  @Test
  public void testPolymorphicLabelsWithFilters() {

    final var prefix = "testPolymorphicLabelsWithFilters_";
    final var character = session.createVertexClass(prefix + "character");
    final var pirate = session.createClass(prefix + "pirate", character.getName());

    session.executeInTx(tx -> {
      final var billyBones = tx.newVertex(pirate);
      billyBones.setProperty("name", "Billy Bones");
      billyBones.setProperty("noOfLegs", 2);
      billyBones.setProperty("noOfEyes", 2);

      final var longJohn = tx.newVertex(pirate);
      longJohn.setProperty("name", "John Silver");
      longJohn.setProperty("noOfLegs", 1);
      longJohn.setProperty("noOfEyes", 2);

      final var blindPew = tx.newVertex(pirate);
      blindPew.setProperty("name", "Blind Pew");
      blindPew.setProperty("noOfLegs", 2);
      blindPew.setProperty("noOfEyes", 0);

      final var jim = tx.newVertex(character);
      jim.setProperty("name", "Jim Hawkins");
      jim.setProperty("noOfLegs", 2);
      jim.setProperty("noOfEyes", 2);
    });

    (assertThatNames(character, false))
        .containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(character, true)
        .containsExactlyInAnyOrder("Jim Hawkins", "Billy Bones", "John Silver", "Blind Pew");

    assertThatNames(
        character, true,
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        character, false,
        t -> t.has("noOfEyes", P.lt(2))
    ).isEmpty();

    assertThatNames(
        pirate, true,
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        pirate, false,
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        character, false,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(
        character, true,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins", "John Silver");

    assertThatNames(
        character, false,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(
        character, true,
        t -> t.has("name", TextP.endingWith("s")).has("noOfLegs", P.gt(1))
    ).containsExactlyInAnyOrder("Jim Hawkins", "Billy Bones");

    assertThatNames(
        character, false,
        t -> t.has("name", TextP.endingWith("s")).has("noOfLegs", P.gt(1))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(character, false,
        t -> t.has("noOfLegs", P.neq(2))
    ).isEmpty();

    assertThatNames(character, true,
        t -> t.has("noOfLegs", P.neq(2))
    ).containsExactlyInAnyOrder("John Silver");
  }

  @Test
  public void testMultipleLabels() {

    final var prefix = "testMultipleLabels_";
    final var animal = session.createVertexClass(prefix + "animal");
    final var mammal = session.createClass(prefix + "mammal", animal.getName());
    final var dolphin = session.createClass(prefix + "dolphin", mammal.getName());
    final var human = session.createClass(prefix + "human", mammal.getName());
    final var fish = session.createClass(prefix + "fish", animal.getName());

    session.executeInTx(tx -> {
      tx.newVertex(animal).setProperty("name", "someAnimal");
      tx.newVertex(mammal).setProperty("name", "someMammal");
      tx.newVertex(dolphin).setProperty("name", "someDolphin");
      tx.newVertex(human).setProperty("name", "someHuman");
      tx.newVertex(fish).setProperty("name", "someFish");
    });

    // union semantics
    assertThatNames(
        List.of(
            List.of(animal, mammal)
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of(animal, mammal)
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal", "someMammal");

    assertThatNames(
        List.of(
            List.of(animal, mammal, human)
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of(animal, mammal, human)
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someHuman");

    assertThatNames(
        List.of(
            List.of(mammal, dolphin, fish)
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someFish", "someHuman");

    assertThatNames(
        List.of(
            List.of(mammal, dolphin, fish)
        ),
        false
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someFish");

    assertThatNames(
        List.of(
            List.of(mammal, human),
            List.of(fish),
            List.of(animal)
        ),
        true
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of(mammal, human),
            List.of(fish),
            List.of(animal)
        ),
        false
    ).isEmpty();

    // intersection semantics
    assertThatNames(
        List.of(
            List.of(animal),
            List.of(mammal)
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someHuman");

    assertThatNames(
        List.of(
            List.of(animal),
            List.of(mammal)
        ),
        false
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of(animal, mammal),
            List.of(human)
        ),
        true
    ).containsExactlyInAnyOrder("someHuman");

    assertThatNames(
        List.of(
            List.of(animal, mammal),
            List.of(human)
        ),
        false
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of(animal),
            List.of(animal)
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of(animal),
            List.of(animal)
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal");

    assertThatNames(
        List.of(
            List.of(animal, mammal),
            List.of(mammal, human)
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someHuman");

    assertThatNames(
        List.of(
            List.of(animal, mammal),
            List.of(mammal, human)
        ),
        false
    ).containsExactlyInAnyOrder("someMammal");

    assertThatNames(
        List.of(
            List.of(animal, mammal),
            List.of(animal, human)
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of(animal, mammal),
            List.of(animal, human)
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal");
  }

  private ListAssert<String> assertThatNames(SchemaClass clazz, boolean polymorphic) {
    return assertThatNames(clazz, polymorphic, Function.identity());
  }

  private ListAssert<String> assertThatNames(SchemaClass clazz, boolean polymorphic,
      Function<YTDBGraphTraversal<Vertex, Vertex>, YTDBGraphTraversal<Vertex, Vertex>> filter) {

    return assertThatNames(List.of(List.of(clazz)), polymorphic, filter);
  }

  private ListAssert<String> assertThatNames(List<List<SchemaClass>> classes, boolean polymorphic) {
    return assertThatNames(classes, polymorphic, Function.identity());
  }

  private ListAssert<String> assertThatNames(List<List<SchemaClass>> classes, boolean polymorphic,
      Function<YTDBGraphTraversal<Vertex, Vertex>, YTDBGraphTraversal<Vertex, Vertex>> filter) {

    var t = pool.asGraph().traversal()
        .with(YTDBQueryConfigParam.polymorphicQuery, polymorphic)
        .V();

    for (var classesInner : classes) {
      final var head = classesInner.getFirst().getName();
      final var tail = classesInner.stream().skip(1).map(SchemaClass::getName)
          .toArray(String[]::new);

      t = t.hasLabel(head, tail);
    }

    return assertThat(filter.apply(t).<String>values("name").toList());
  }

}
