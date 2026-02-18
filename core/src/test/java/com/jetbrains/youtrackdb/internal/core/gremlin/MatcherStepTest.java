package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertThat;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class MatcherStepTest extends GraphBaseTest {

  @Before
  public void setup() {
  }

  @SuppressWarnings("JUnit4TestNotRun")
  public void searchMatching() {
    var marko = graph.addVertex("name", "marko", "age", 29);
    var vadas = graph.addVertex("name", "vadas", "age", 27);
    var lop = graph.addVertex("name", "lop", "lang", "java");
    var josh = graph.addVertex("name", "josh", "age", 32);
    var ripple = graph.addVertex("name", "ripple", "lang", "java");
    var peter = graph.addVertex("name", "peter", "age", 35);
    marko.addEdge("knows", vadas, "weight", 0.5f);
    marko.addEdge("knows", josh, "weight", 1.0f);
    marko.addEdge("created", lop, "weight", 0.4f);
    josh.addEdge("created", ripple, "weight", 1.0f);
    josh.addEdge("created", lop, "weight", 0.4f);
    peter.addEdge("created", lop, "weight", 0.2f);

    graph.tx().commit();

    var g = graph.traversal();

    var result =
        g.V()
            .match(
                __.as("a").out("created").as("b"),
                __.as("b").has("name", "lop"),
                __.as("b").in("created").as("c"),
                __.as("c").has("age", 29))
            .select("a", "c")
            .by("name")
            .toList();

    assertThat(result, Matchers.hasSize(3));
    assertThat(result.get(0), CoreMatchers.allOf(
        Matchers.hasEntry("a", "marko"), Matchers.hasEntry("c", "marko")));
    assertThat(result.get(1), CoreMatchers.allOf(
        Matchers.hasEntry("a", "josh"), Matchers.hasEntry("c", "marko")));
    assertThat(result.get(2), CoreMatchers.allOf(
        Matchers.hasEntry("a", "peter"), Matchers.hasEntry("c", "marko")));
  }

  @Test
  public void singleMatching() {
    var marko = graph.addVertex("name", "marko", "age", 29);
    marko.addEdge("pays", marko);
    graph.tx().commit();

    var g = graph.traversal();

    var result =
        g.V()
            .match(__.as("a").out("pays").as("b"), __.as("b").has("name", "marko"))
            .select("a", "b")
            .by("name")
            .toList();

    assertThat(result.toString(), result, Matchers.hasSize(1));
    assertThat(result.getFirst(), CoreMatchers.allOf(
        Matchers.hasEntry("a", "marko"), Matchers.hasEntry("b", "marko")));
  }
}
