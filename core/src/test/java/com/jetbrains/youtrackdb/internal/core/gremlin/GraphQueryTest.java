package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBQueryConfigParam;
import java.util.List;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
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
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("Person")
    );

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

  protected static void initGraph(YTDBGraph graph) {
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("Person").createSchemaClass("Animal").
            createStateFullEdgeClass("HasFriend").createStateFullEdgeClass("HasAnimal")
    );

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

    var animalClassName = prefix + "Animal";
    var fishClassName = prefix + "Fish";
    var mammalClassName = prefix + "Mammal";
    var birdClassName = prefix + "Bird";
    var insectClassName = prefix + "Insect";
    var beeClassName = prefix + "bee";
    var catClassName = prefix + "cat";
    var humanClassName = prefix + "human";

    graph.autoExecuteInTx(g ->
        g.createSchemaClass(animalClassName).
            createSchemaClass(fishClassName).addParentClass(animalClassName).
            createSchemaClass(mammalClassName).addParentClass(animalClassName).
            createSchemaClass(catClassName).addParentClass(mammalClassName).
            createSchemaClass(birdClassName).addParentClass(animalClassName).
            createSchemaClass(insectClassName).addParentClass(animalClassName).
            createSchemaClass(beeClassName).addParentClass(insectClassName).
            createSchemaClass(catClassName).addParentClass(mammalClassName).
            createSchemaClass(humanClassName).addParentClass(mammalClassName)
    );

    session.executeInTx(tx -> {
      tx.newVertex(animalClassName).setProperty("name", "someAnimal");

      tx.newVertex(fishClassName).setProperty("name", "someFish");

      tx.newVertex(catClassName).setProperty("name", "someCat");

      tx.newVertex(catClassName).setProperty("name", "otherCat");

      tx.newVertex(insectClassName).setProperty("name", "someInsect");
    });

    assertThatNames(animalClassName, true)
        .containsExactlyInAnyOrder("someAnimal", "someFish", "someCat", "otherCat", "someInsect");

    assertThatNames(animalClassName, false)
        .containsExactlyInAnyOrder("someAnimal");

    assertThatNames(fishClassName, true)
        .containsExactlyInAnyOrder("someFish");

    assertThatNames(fishClassName, false)
        .containsExactlyInAnyOrder("someFish");

    assertThatNames(mammalClassName, true)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames(mammalClassName, false)
        .isEmpty();

    assertThatNames(insectClassName, true)
        .containsExactlyInAnyOrder("someInsect");

    assertThatNames(insectClassName, false)
        .containsExactlyInAnyOrder("someInsect");

    assertThatNames(birdClassName, true)
        .isEmpty();

    assertThatNames(birdClassName, false)
        .isEmpty();

    assertThatNames(catClassName, true)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames(catClassName, false)
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThatNames(humanClassName, true)
        .isEmpty();

    assertThatNames(humanClassName, false)
        .isEmpty();
  }

  @Test
  public void testPolymorphicLabelsWithFilters() {

    final var prefix = "testPolymorphicLabelsWithFilters_";

    var characterClassName = prefix + "Character";
    var pirateClassName = prefix + "Pirate";

    graph.autoExecuteInTx(g -> g.createSchemaClass(characterClassName)
        .createSchemaClass(pirateClassName).addParentClass(characterClassName));

    session.executeInTx(tx -> {
      final var billyBones = tx.newVertex(pirateClassName);
      billyBones.setProperty("name", "Billy Bones");
      billyBones.setProperty("noOfLegs", 2);
      billyBones.setProperty("noOfEyes", 2);

      final var longJohn = tx.newVertex(pirateClassName);
      longJohn.setProperty("name", "John Silver");
      longJohn.setProperty("noOfLegs", 1);
      longJohn.setProperty("noOfEyes", 2);

      final var blindPew = tx.newVertex(pirateClassName);
      blindPew.setProperty("name", "Blind Pew");
      blindPew.setProperty("noOfLegs", 2);
      blindPew.setProperty("noOfEyes", 0);

      final var jim = tx.newVertex(characterClassName);
      jim.setProperty("name", "Jim Hawkins");
      jim.setProperty("noOfLegs", 2);
      jim.setProperty("noOfEyes", 2);
    });

    (assertThatNames(characterClassName, false))
        .containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(characterClassName, true)
        .containsExactlyInAnyOrder("Jim Hawkins", "Billy Bones", "John Silver", "Blind Pew");

    assertThatNames(
        characterClassName, true,
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        characterClassName, false,
        t -> t.has("noOfEyes", P.lt(2))
    ).isEmpty();

    assertThatNames(
        pirateClassName, true,
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        pirateClassName, false,
        t -> t.has("noOfEyes", P.lt(2))
    ).containsExactlyInAnyOrder("Blind Pew");

    assertThatNames(
        characterClassName, false,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(
        characterClassName, true,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins", "John Silver");

    assertThatNames(
        characterClassName, false,
        t -> t.has("name", TextP.startingWith("J"))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(
        characterClassName, true,
        t -> t.has("name", TextP.endingWith("s")).has("noOfLegs", P.gt(1))
    ).containsExactlyInAnyOrder("Jim Hawkins", "Billy Bones");

    assertThatNames(
        characterClassName, false,
        t -> t.has("name", TextP.endingWith("s")).has("noOfLegs", P.gt(1))
    ).containsExactlyInAnyOrder("Jim Hawkins");

    assertThatNames(characterClassName, false,
        t -> t.has("noOfLegs", P.neq(2))
    ).isEmpty();

    assertThatNames(characterClassName, true,
        t -> t.has("noOfLegs", P.neq(2))
    ).containsExactlyInAnyOrder("John Silver");
  }

  @Test
  public void testMultipleLabels() {

    final var prefix = "testMultipleLabels_";

    var animalClassName = prefix + "Animal";
    var mammalClassName = prefix + "Mammal";
    var dolphinClassName = prefix + "Dolphin";
    var humanClassName = prefix + "Human";
    var fishClassName = prefix + "Fish";

    graph.autoExecuteInTx(g ->
        g.createSchemaClass(animalClassName).
            createSchemaClass(mammalClassName).addParentClass(animalClassName).
            createSchemaClass(dolphinClassName).addParentClass(mammalClassName).
            createSchemaClass(humanClassName).addParentClass(mammalClassName).
            createSchemaClass(fishClassName).addParentClass(animalClassName)
    );

    session.executeInTx(tx -> {
      tx.newVertex(animalClassName).setProperty("name", "someAnimal");
      tx.newVertex(mammalClassName).setProperty("name", "someMammal");
      tx.newVertex(dolphinClassName).setProperty("name", "someDolphin");
      tx.newVertex(humanClassName).setProperty("name", "someHuman");
      tx.newVertex(fishClassName).setProperty("name", "someFish");
    });

    // union semantics
    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName)
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName)
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal", "someMammal");

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName, humanClassName)
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName, humanClassName)
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someHuman");

    assertThatNames(
        List.of(
            List.of(mammalClassName, dolphinClassName, fishClassName)
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someFish", "someHuman");

    assertThatNames(
        List.of(
            List.of(mammalClassName, dolphinClassName, fishClassName)
        ),
        false
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someFish");

    assertThatNames(
        List.of(
            List.of(mammalClassName, humanClassName),
            List.of(fishClassName),
            List.of(animalClassName)
        ),
        true
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of(mammalClassName, humanClassName),
            List.of(fishClassName),
            List.of(animalClassName)
        ),
        false
    ).isEmpty();

    // intersection semantics
    assertThatNames(
        List.of(
            List.of(animalClassName),
            List.of(mammalClassName)
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someHuman");

    assertThatNames(
        List.of(
            List.of(animalClassName),
            List.of(mammalClassName)
        ),
        false
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName),
            List.of(humanClassName)
        ),
        true
    ).containsExactlyInAnyOrder("someHuman");

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName),
            List.of(humanClassName)
        ),
        false
    ).isEmpty();

    assertThatNames(
        List.of(
            List.of(animalClassName),
            List.of(animalClassName)
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of(animalClassName),
            List.of(animalClassName)
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal");

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName),
            List.of(mammalClassName, humanClassName)
        ),
        true
    ).containsExactlyInAnyOrder("someMammal", "someDolphin", "someHuman");

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName),
            List.of(mammalClassName, humanClassName)
        ),
        false
    ).containsExactlyInAnyOrder("someMammal");

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName),
            List.of(animalClassName, humanClassName)
        ),
        true
    ).containsExactlyInAnyOrder("someAnimal", "someMammal", "someDolphin", "someHuman", "someFish");

    assertThatNames(
        List.of(
            List.of(animalClassName, mammalClassName),
            List.of(animalClassName, humanClassName)
        ),
        false
    ).containsExactlyInAnyOrder("someAnimal");
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

    var t = pool.asGraph().traversal()
        .with(YTDBQueryConfigParam.polymorphicQuery, polymorphic)
        .V();

    for (var classesInner : classes) {
      final var head = classesInner.getFirst();
      final var tail = classesInner.stream().skip(1)
          .toArray(String[]::new);

      t = t.hasLabel(head, tail);
    }

    return assertThat(filter.apply(t).<String>values("name").toList());
  }

}
