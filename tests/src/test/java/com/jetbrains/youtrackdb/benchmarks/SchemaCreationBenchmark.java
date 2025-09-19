package com.jetbrains.youtrackdb.benchmarks;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;

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
      try (var session = youTrackDB.open(DB_NAME, "admin", "admin")) {
        var schema = session.getSchema();

        var start = System.nanoTime();
        for (var i = 0; i < 1_000; i++) {
          if (i % 10 == 0) {
            System.out.println("Creating class " + (i + 1) + " of 1000");
          }

          var cls = schema.createClass("TestClass" + i);
          for (var j = 0; j < 20; j++) {
            cls.createProperty("TestProperty" + j, PropertyType.STRING);
          }
        }
        var end = System.nanoTime();

        var min = ((end - start) / 1_000_000_000 / 60);
        var sec = ((end - start) / 1_000_000_000) % 60;

        System.out.println(
            "Time taken to create 1000 classes with 20 properties each: " + min + "m " + sec + "s");

        System.out.println("Creating index");
        start = System.nanoTime();
        var counter = 0;
        for (var i = 0; i < 1_000; i++) {
          for (var j = 0; j < 20; j++) {
            if (counter % 10 == 0) {
              System.out.println("Creating index " + (counter + 1) + " of 20,000");
            }

            counter++;
            var cls = schema.getClass("TestClass" + i);
            cls.createIndex("TestIndex" + (i * 1_000) + j, INDEX_TYPE.UNIQUE, "TestProperty" + j);
          }
        }
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
