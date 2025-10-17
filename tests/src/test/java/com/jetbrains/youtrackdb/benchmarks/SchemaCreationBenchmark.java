package com.jetbrains.youtrackdb.benchmarks;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;

public class SchemaCreationBenchmark {

  public static final String DB_NAME = "indexBenchmark";

  public static void main(String[] args) {
    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        "./target/databases/" + SchemaCreationBenchmark.class.getName())) {
      if (youTrackDB.exists(DB_NAME)) {
        System.out.println("Dropping existing database");
        youTrackDB.drop(DB_NAME);
      }
      youTrackDB.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");

      try (var graph = youTrackDB.openGraph(DB_NAME, "admin", "admin")) {
        var start = System.nanoTime();
        graph.executeInTx(g -> {
              var traversal = g.inject((Vertex) null);

              for (var i = 0; i < 1_000; i++) {
                if (i % 10 == 0) {
                  System.out.println("Creating class " + (i + 1) + " of 1000");
                }

                traversal.addSchemaClass("TestClass" + i);
                for (var j = 0; j < 20; j++) {
                  traversal.sideEffect(__.addSchemaProperty("TestProperty" + j,
                      PropertyType.STRING));
                }
              }

              traversal.iterate();
            }
        );
        var end = System.nanoTime();

        var min = ((end - start) / 1_000_000_000 / 60);
        var sec = ((end - start) / 1_000_000_000) % 60;

        System.out.println(
            "Time taken to create 1000 classes with 20 properties each: " + min + "m " + sec + "s");

        System.out.println("Creating index");
        start = System.nanoTime();

        graph.executeInTx(g -> {
              var traversal = g.inject((Vertex) null);

              var counter = 0;
              for (var i = 0; i < 1_000; i++) {
                for (var j = 0; j < 20; j++) {
                  if (counter % 10 == 0) {
                    System.out.println("Creating index " + (counter + 1) + " of 20,000");
                  }

                  counter++;
                  traversal.schemaClass("TestClass" + i).addClassIndex(
                      "TestIndex" + (i * 1_000) + j, IndexType.UNIQUE, "TestProperty" + j);
                }
              }

              traversal.iterate();
            }
        );

        end = System.nanoTime();

        min = ((end - start) / 1_000_000_000 / 60);
        sec = ((end - start) / 1_000_000_000) % 60;

        System.out.println(
            "Time taken to create 20,000 indexes: " + min + "m " + sec + "s");
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
