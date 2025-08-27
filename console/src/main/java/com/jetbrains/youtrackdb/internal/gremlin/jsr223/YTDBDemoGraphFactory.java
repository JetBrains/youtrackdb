package com.jetbrains.youtrackdb.internal.gremlin.jsr223;

import static org.apache.tinkerpop.gremlin.structure.io.IoCore.gryo;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import org.apache.tinkerpop.gremlin.structure.T;

public class YTDBDemoGraphFactory {

  private YTDBDemoGraphFactory() {
  }

  /// Create the "classic" graph which was the original toy graph from TinkerPop 2.x.
  public static YTDBGraph createClassic() {
    final var g = createYTDBGraph("classic");
    generateClassic(g);
    return g;
  }

  /// Generate the graph in [#createClassic()] into an existing graph.
  public static void generateClassic(final YTDBGraph g) {
    final var marko = g.addVertex("name", "marko", "age", 29);
    final var vadas = g.addVertex("name", "vadas", "age", 27);
    final var lop = g.addVertex("name", "lop", "lang", "java");
    final var josh = g.addVertex("name", "josh", "age", 32);
    final var ripple = g.addVertex("name", "ripple", "lang", "java");
    final var peter = g.addVertex("name", "peter", "age", 35);

    marko.addEdge("knows", vadas, "weight", 0.5f);
    marko.addEdge("knows", josh, "weight", 1.0f);
    marko.addEdge("created", lop, "weight", 0.4f);
    josh.addEdge("created", ripple, "weight", 1.0f);
    josh.addEdge("created", lop, "weight", 0.4f);
    peter.addEdge("created", lop, "weight", 0.2f);
  }

  /// Create the "modern" graph which has the same structure as the "classic" graph from TinkerPop
  /// 2.x but includes 3.x features like vertex labels.
  public static YTDBGraph createModern() {
    final var g = createYTDBGraph("modern");
    generateModern(g);
    return g;
  }

  /// Generate the graph in [#createModern()] into an existing graph.
  public static void generateModern(final YTDBGraph g) {
    final var marko = g.addVertex(T.label, "person");
    marko.property("name", "marko");
    marko.property("age", 29);
    final var vadas = g.addVertex(T.label, "person");
    vadas.property("name", "vadas");
    vadas.property("age", 27);
    final var lop = g.addVertex(T.label, "software");
    lop.property("name", "lop");
    lop.property("lang", "java");
    final var josh = g.addVertex(T.label, "person");
    josh.property("name", "josh");
    josh.property("age", 32);
    final var ripple = g.addVertex(T.label, "software");
    ripple.property("name", "ripple");
    ripple.property("lang", "java");
    final var peter = g.addVertex(T.label, "person");
    peter.property("name", "peter");
    peter.property("age", 35);

    marko.addEdge("knows", vadas, "weight", 0.5d);
    marko.addEdge("knows", josh, "weight", 1.0d);
    marko.addEdge("created", lop, "weight", 0.4d);
    josh.addEdge("created", ripple, "weight", 1.0d);
    josh.addEdge("created", lop, "weight", 0.4d);
    peter.addEdge("created", lop, "weight", 0.2d);
  }


  /// Creates the "kitchen sink" graph which is a collection of structures (e.g. self-loops) that
  /// aren't represented in other graphs and are useful for various testing scenarios.
  public static YTDBGraph createKitchenSink() {
    final var g = createYTDBGraph("kitchen-sink");
    generateKitchenSink(g);
    return g;
  }

  /// Generate the graph in [#createKitchenSink()] into an existing graph.
  public static void generateKitchenSink(final YTDBGraph graph) {
    final var g = graph.traversal();
    g.addV("loops").property("name", "loop").as("me").
        addE("self").to("me").iterate();
    g.addV("message").property("name", "a").as("a").
        addV("message").property("name", "b").as("b").
        addE("link").from("a").to("b").
        addE("link").from("a").to("a").iterate();
  }

  /// Creates the "grateful dead" graph which is a larger graph than most of the toy graphs but has
  /// real-world structure and application and is therefore useful for demonstrating more complex
  /// traversals.
  public static YTDBGraph createGratefulDead() {
    final var g = createYTDBGraph("grateful-dead");
    generateGratefulDead(g);
    return g;
  }

  /// Generate the graph in [#createGratefulDead()] into an existing graph.
  public static void generateGratefulDead(final YTDBGraph graph) {
    final var stream = YTDBDemoGraphFactory.class.getResourceAsStream("data/grateful-dead.kryo");
    try {
      graph.io(gryo()).reader().create().readGraph(stream, graph);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static YTDBGraph createYTDBGraph(String name) {
    var ytdb = YourTracks.instance(".");
    if (ytdb.exists(name)) {
      ytdb.drop(name);
    }

    ytdb.create(name, DatabaseType.MEMORY, "superuser", "password", "admin");
    return ytdb.openGraph(name, "superuser", "password");
  }
}
