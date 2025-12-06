package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import java.nio.file.Files;

public final class YTDBDemoGraphFactory {
  private YTDBDemoGraphFactory() {
  }

  /// Create the "classic" graph which was the original toy graph from TinkerPop 2.x.
  public static YTDBGraphTraversalSource createClassic(YouTrackDB youTrackDB) {
    final var g = createYTDBGraph(youTrackDB, "classic");
    generateClassic(g);
    return g;
  }

  /// Generate the graph in [#createClassic()] into an existing graph.
  public static void generateClassic(final YTDBGraphTraversalSource traversal) {
    traversal.autoExecuteInTx(g ->
        g.addV().property("name", "marko").property("age", 29).as("marko")
            .addV().property("name", "vadas").property("age", 27).as("vadas").
            addV().property("name", "lop").property("lang", "java").as("lop").
            addV().property("name", "josh").property("age", 32).as("josh").
            addV().property("name", "ripple").property("lang", "java").as("ripple").
            addV().property("name", "peter").property("age", 35).
            select("marko").addE("knows").to("vadas").property("weight", 0.5f).
            select("marko").addE("knows").to("josh").property("weight", 1.0f).
            select("marko").addE("created").to("lop").property("weight", 0.4f).
            select("josh").addE("created").to("ripple").property("weight", 1.0f).
            select("josh").addE("created").to("lop").property("weight", 0.4f).
            select("peter").addE("created").to("lop").property("weight", 0.2f)
    );
  }

  /// Create the "modern" graph which has the same structure as the "classic" graph from TinkerPop
  /// 2.x but includes 3.x features like vertex labels.
  public static YTDBGraphTraversalSource createModern(YouTrackDB youTrackDB) {
    final var g = createYTDBGraph(youTrackDB, "modern");
    generateModern(g);
    return g;
  }

  /// Generate the graph in [#createModern()] into an existing graph.
  public static void generateModern(final YTDBGraphTraversalSource traversal) {
    traversal.autoExecuteInTx(g ->
        g.addV("person").property("name", "marko").property("age", 29).as("marko").
            addV("person").property("name", "vadas").property("age", 27).as("vadas").
            addV("software").property("name", "lop").property("lang", "java").as("lop").
            addV("person").property("name", "josh").property("age", 32).as("josh").
            addV("software").property("name", "ripple").property("lang", "java").as("ripple").
            addV("person").property("name", "peter").property("age", 35).as("peter").
            select("marko").addE("knows").to("vadas").property("weight", 0.5f).
            select("marko").addE("knows").to("josh").property("weight", 1.0f).
            select("marko").addE("created").to("lop").property("weight", 0.4f).
            select("josh").addE("created").to("ripple").property("weight", 1.0f).
            select("peter").addE("created").to("lop").property("weight", 0.2f)
    );
  }


  /// Creates the "kitchen sink" graph which is a collection of structures (e.g. self-loops) that
  /// aren't represented in other graphs and are useful for various testing scenarios.
  public static YTDBGraphTraversalSource createKitchenSink(YouTrackDB youTrackDB) {
    final var g = createYTDBGraph(youTrackDB, "kitchen-sink");
    generateKitchenSink(g);
    return g;
  }

  /// Generate the graph in [#createKitchenSink()] into an existing graph.
  public static void generateKitchenSink(final YTDBGraphTraversalSource traversal) {
    traversal.autoExecuteInTx(g ->
        g.addV("loops").property("name", "loop").as("me").
            addE("self").to("me").
            addV("message").property("name", "a").as("a").
            addV("message").property("name", "b").as("b").
            addE("link").from("a").to("b").
            addE("link").from("a").to("a")
    );

  }

  /// Creates the "grateful dead" graph which is a larger graph than most of the toy graphs but has
  /// real-world structure and application and is therefore useful for demonstrating more complex
  /// traversals.
  public static YTDBGraphTraversalSource createGratefulDead(YouTrackDB youTrackDB) {
    final var g = createYTDBGraph(youTrackDB, "grateful-dead");
    generateGratefulDead(g);
    return g;
  }

  /// Generate the graph in [#createGratefulDead()] into an existing graph.
  public static void generateGratefulDead(final YTDBGraphTraversalSource traversal) {
    final var iStream = YTDBDemoGraphFactory.class.getResourceAsStream("grateful-dead.kryo");
    try {
      var tempFile = Files.createTempFile("grateful-dead", ".kryo");
      try (var oStream = Files.newOutputStream(tempFile)) {
        IOUtils.copyStream(iStream, oStream);
      }

      var realPath = tempFile.toRealPath().toString();
      traversal.autoExecuteInTx(g ->
          (YTDBGraphTraversal<?, ?>) g.io(realPath)
      );
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static YTDBGraphTraversalSource createYTDBGraph(YouTrackDB ytdb, String name) {
    if (ytdb.exists(name)) {
      ytdb.drop(name);
    }

    ytdb.create(name, DatabaseType.MEMORY, "superuser", "password", "admin");
    return ytdb.openTraversal(name, "superuser", "password");
  }
}
