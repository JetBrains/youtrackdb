package io.youtrackdb.examples;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import java.util.ArrayList;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/// Getting Started with YouTrackDB and YQL.
///
/// This example demonstrates the primary features of YouTrackDB using YQL
/// (YouTrackDB Query Language), a SQL-based query language with graph
/// extensions. Each section prints results to stdout for verification.
///
/// Companion guide: {@code docs/getting-started.md}
public class GettingStartedExample {

  @SuppressWarnings("SystemOut")
  public static void main(String[] args) {
    // 1. Create a YouTrackDB instance. For embedded usage, provide the root
    //    directory where databases are stored. We use in-memory databases
    //    here, so the path is not significant.
    try (var ytdb = YourTracks.instance(".")) {
      ytdb.create("getting-started", DatabaseType.MEMORY, "admin", "adminpwd", "admin");

      try (var g = ytdb.openTraversal("getting-started", "admin", "adminpwd")) {

        // ---- Schema: Classes, Properties, and Indexes ----

        // Classes in YouTrackDB are similar to tables in relational databases.
        // Vertex classes extend V, edge classes extend E.
        g.executeInTx(tx -> {
          tx.command("CREATE CLASS Person EXTENDS V");
          tx.command("CREATE CLASS Movie EXTENDS V");
          tx.command("CREATE CLASS ActedIn EXTENDS E");
          tx.command("CREATE CLASS Directed EXTENDS E");
        });

        // Properties define the schema for a class. Constraints such as
        // MANDATORY and MIN enforce data integrity.
        g.executeInTx(tx -> {
          tx.command(
              "CREATE PROPERTY Person.name STRING (MANDATORY TRUE, MIN 1)");
          tx.command("CREATE PROPERTY Person.birthYear INTEGER");
          tx.command(
              "CREATE PROPERTY Movie.title STRING (MANDATORY TRUE)");
          tx.command("CREATE PROPERTY Movie.year INTEGER");
          tx.command("CREATE PROPERTY Movie.genre STRING");
          tx.command("CREATE PROPERTY ActedIn.role STRING");
        });

        // Indexes accelerate lookups. UNIQUE enforces uniqueness;
        // NOTUNIQUE allows duplicates.
        g.executeInTx(tx -> {
          tx.command(
              "CREATE INDEX Person.name ON Person (name) UNIQUE");
          tx.command(
              "CREATE INDEX Movie.title ON Movie (title) UNIQUE");
          tx.command(
              "CREATE INDEX Movie.year ON Movie (year) NOTUNIQUE");
        });
        System.out.println("output:schema created");

        // ---- Insert Data: Vertices ----

        g.executeInTx(tx -> {
          tx.yql(
              "CREATE VERTEX Person SET name = 'Alice', birthYear = 1990")
              .iterate();
          tx.yql(
              "CREATE VERTEX Person SET name = 'Bob', birthYear = 1985")
              .iterate();
          tx.yql(
              "CREATE VERTEX Person SET name = 'Charlie', birthYear = 1978")
              .iterate();
          tx.yql(
              "CREATE VERTEX Movie SET title = 'The Matrix', year = 1999, genre = 'Sci-Fi'")
              .iterate();
          tx.yql(
              "CREATE VERTEX Movie SET title = 'Inception', year = 2010, genre = 'Sci-Fi'")
              .iterate();
          tx.yql(
              "CREATE VERTEX Movie SET title = 'The Godfather', year = 1972, genre = 'Crime'")
              .iterate();
        });
        System.out.println("output:data inserted");

        // ---- Insert Data: Edges ----

        // Edges connect vertices. Sub-queries in FROM/TO select the endpoints.
        g.executeInTx(tx -> {
          tx.yql("CREATE EDGE ActedIn "
              + "FROM (SELECT FROM Person WHERE name = 'Alice') "
              + "TO (SELECT FROM Movie WHERE title = 'The Matrix') "
              + "SET role = 'Trinity'").iterate();
          tx.yql("CREATE EDGE ActedIn "
              + "FROM (SELECT FROM Person WHERE name = 'Alice') "
              + "TO (SELECT FROM Movie WHERE title = 'Inception') "
              + "SET role = 'Ariadne'").iterate();
          tx.yql("CREATE EDGE ActedIn "
              + "FROM (SELECT FROM Person WHERE name = 'Bob') "
              + "TO (SELECT FROM Movie WHERE title = 'The Matrix') "
              + "SET role = 'Neo'").iterate();
          tx.yql("CREATE EDGE ActedIn "
              + "FROM (SELECT FROM Person WHERE name = 'Charlie') "
              + "TO (SELECT FROM Movie WHERE title = 'The Godfather') "
              + "SET role = 'Michael'").iterate();
          tx.yql("CREATE EDGE Directed "
              + "FROM (SELECT FROM Person WHERE name = 'Charlie') "
              + "TO (SELECT FROM Movie WHERE title = 'Inception')").iterate();
        });
        System.out.println("output:edges created");

        // ---- Basic Queries with Projections ----

        // A SELECT with projections returns Maps (not full Vertex records).
        g.executeInTx(tx -> {
          var people = tx.yql(
              "SELECT name, birthYear FROM Person ORDER BY name").toList();
          for (var row : people) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) row;
            System.out.println(
                "output:person:" + map.get("name") + "," + map.get("birthYear"));
          }
        });

        // ---- Filtering with WHERE ----

        g.executeInTx(tx -> {
          var young = tx.yql(
              "SELECT name FROM Person WHERE birthYear > 1980 ORDER BY name")
              .toList();
          var names = new ArrayList<String>();
          for (var row : young) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) row;
            names.add((String) map.get("name"));
          }
          System.out.println(
              "output:born after 1980:" + String.join(",", names));
        });

        // ---- Querying Full Records ----

        // SELECT without projections returns Vertex objects.
        g.executeInTx(tx -> {
          var scifi = tx.yql(
              "SELECT FROM Movie WHERE genre = 'Sci-Fi' AND year >= 2000")
              .toList();
          for (var row : scifi) {
            var v = (Vertex) row;
            System.out.println(
                "output:scifi 2000s:" + v.value("title"));
          }
        });

        // ---- Aggregation ----

        g.executeInTx(tx -> {
          var result = tx.yql("SELECT COUNT(*) AS cnt FROM Person").next();
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) result;
          System.out.println("output:person count:" + map.get("cnt"));
        });

        // ---- Graph Pattern Matching with MATCH ----

        // MATCH is the recommended way to traverse graphs in YQL.
        // It uses declarative patterns to find connected vertices.
        g.executeInTx(tx -> {
          var matches = tx.yql(
              "MATCH {class: Person, as: actor}"
                  + " -ActedIn-> {class: Movie, as: movie}"
                  + " RETURN actor.name AS actor, movie.title AS movie"
                  + " ORDER BY actor, movie")
              .toList();
          for (var row : matches) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) row;
            System.out.println(
                "output:acted:" + map.get("actor") + " in " + map.get("movie"));
          }
        });

        // ---- Multi-hop Traversal: Co-actors ----

        // Find people who acted in the same movie as Alice.
        g.executeInTx(tx -> {
          var coActors = tx.yql(
              "MATCH {class: Person, as: alice, where: (name = 'Alice')}"
                  + " -ActedIn-> {class: Movie, as: movie}"
                  + " <-ActedIn- {class: Person, as: coactor,"
                  + " where: (name <> 'Alice')}"
                  + " RETURN DISTINCT coactor.name AS coactor,"
                  + " movie.title AS movie"
                  + " ORDER BY coactor, movie")
              .toList();
          for (var row : coActors) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) row;
            System.out.println(
                "output:coactor:" + map.get("coactor")
                    + " in " + map.get("movie"));
          }
        });

        // ---- Update Data ----

        g.executeInTx(tx -> {
          tx.yql(
              "UPDATE Movie SET genre = 'Sci-Fi/Thriller'"
                  + " WHERE title = 'Inception'")
              .iterate();
          var updated = tx.yql(
              "SELECT title, genre FROM Movie WHERE title = 'Inception'")
              .next();
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) updated;
          System.out.println(
              "output:updated:" + map.get("title") + "," + map.get("genre"));
        });

        // ---- Delete Data ----

        g.executeInTx(tx -> {
          // Delete an edge, then verify it is gone.
          tx.yql("DELETE EDGE Directed"
              + " FROM (SELECT FROM Person WHERE name = 'Charlie')"
              + " TO (SELECT FROM Movie WHERE title = 'Inception')")
              .iterate();
          var remaining = tx.yql(
              "MATCH {class: Person, as: p} -Directed->"
                  + " {class: Movie, as: m}"
                  + " RETURN p.name AS director, m.title AS movie")
              .toList();
          System.out.println("output:directed edges:" + remaining.size());
        });

        // ---- Parameterized Queries ----

        // Use :param placeholders and pass values as key-value pairs.
        // This is the recommended way to avoid injection and improve
        // plan caching.
        g.executeInTx(tx -> {
          var result = tx.yql(
              "SELECT name, birthYear FROM Person WHERE name = :name",
              "name", "Bob").next();
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) result;
          System.out.println(
              "output:param query:" + map.get("name") + ","
                  + map.get("birthYear"));
        });
      }

      // ---- Inheritance and Polymorphic Queries ----

      inheritanceExample(ytdb);
    }
  }

  @SuppressWarnings("SystemOut")
  static void inheritanceExample(YouTrackDB ytdb) {
    ytdb.create("inheritance-demo", DatabaseType.MEMORY,
        "admin", "adminpwd", "admin");

    try (var g = ytdb.openTraversal("inheritance-demo", "admin", "adminpwd")) {
      // Create an abstract base class and concrete subclasses.
      // Abstract classes cannot be instantiated directly.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Animal EXTENDS V ABSTRACT");
        tx.command("CREATE PROPERTY Animal.name STRING (MANDATORY TRUE)");
        tx.command("CREATE PROPERTY Animal.sound STRING");

        tx.command("CREATE CLASS Dog EXTENDS Animal");
        tx.command("CREATE PROPERTY Dog.breed STRING");

        tx.command("CREATE CLASS Cat EXTENDS Animal");
        tx.command("CREATE PROPERTY Cat.indoor BOOLEAN");
      });

      // YouTrackDB also supports multiple inheritance.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Pet EXTENDS V ABSTRACT");
        tx.command("CREATE PROPERTY Pet.ownerName STRING");

        // HouseDog extends both Dog and Pet — it inherits
        // properties from both classes.
        tx.command("CREATE CLASS HouseDog EXTENDS Dog, Pet");
      });

      // Insert data into the concrete classes.
      g.executeInTx(tx -> {
        tx.yql("CREATE VERTEX Dog SET name = 'Rex', sound = 'Woof', breed = 'Shepherd'")
            .iterate();
        tx.yql("CREATE VERTEX Cat SET name = 'Whiskers', sound = 'Meow', indoor = true")
            .iterate();
        tx.yql("CREATE VERTEX HouseDog SET name = 'Buddy', sound = 'Bark',"
            + " breed = 'Labrador', ownerName = 'Alice'")
            .iterate();
      });

      // Polymorphic query: SELECT FROM the base class returns all
      // subclass instances (Dog, Cat, HouseDog).
      g.executeInTx(tx -> {
        var animals = tx.yql(
            "SELECT name, @class AS cls FROM Animal ORDER BY name")
            .toList();
        for (var row : animals) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          System.out.println(
              "output:animal:" + map.get("name") + "," + map.get("cls"));
        }
      });

      // Querying a subclass directly returns only its instances
      // (including further subclasses). SELECT FROM Dog returns Dog
      // and HouseDog but not Cat.
      g.executeInTx(tx -> {
        var dogs = tx.yql(
            "SELECT name FROM Dog ORDER BY name").toList();
        var names = new ArrayList<String>();
        for (var row : dogs) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          names.add((String) map.get("name"));
        }
        System.out.println("output:dogs:" + String.join(",", names));
      });

      // Multiple inheritance: HouseDog is both a Dog and a Pet.
      g.executeInTx(tx -> {
        var pets = tx.yql(
            "SELECT name, ownerName FROM Pet ORDER BY name")
            .toList();
        for (var row : pets) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          System.out.println(
              "output:pet:" + map.get("name") + ",owner=" + map.get("ownerName"));
        }
      });
    }
  }
}
