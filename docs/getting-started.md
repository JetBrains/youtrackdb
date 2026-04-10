# Getting Started with YouTrackDB

This guide walks you through the primary features of YouTrackDB using YQL
(YouTrackDB Query Language). YQL is a SQL-based query language with extensions
for graph functionality — if you know SQL, you already know most of YQL.

All examples use the public API and run as embedded in-memory databases.
The complete source code is in
[`GettingStartedExample.java`](../examples/src/main/java/io/youtrackdb/examples/GettingStartedExample.java),
with tests in
[`ExamplesTest.java`](../examples/src/test/java/io/youtrackdb/examples/ExamplesTest.java).

## Prerequisites

- JDK 21 or later
- Maven dependency:
  ```xml
  <dependency>
    <groupId>io.youtrackdb</groupId>
    <artifactId>youtrackdb-embedded</artifactId>
    <version>0.5.0-SNAPSHOT</version>
  </dependency>
  ```

## 1. Create a Database

```java
try (var ytdb = YourTracks.instance(".")) {
  ytdb.create("getting-started", DatabaseType.MEMORY, "admin", "adminpwd", "admin");

  try (var g = ytdb.openTraversal("getting-started", "admin", "adminpwd")) {
    // All operations go here — g is a YTDBGraphTraversalSource
  }
}
```

`YourTracks.instance(".")` creates an embedded YouTrackDB instance. The path is the
root directory for database storage (not significant for in-memory databases).

`openTraversal()` returns a `YTDBGraphTraversalSource` — the entry point for both
Gremlin traversals and YQL queries.

## 2. Define a Schema

Classes in YouTrackDB are analogous to tables in a relational database.
Vertex classes extend `V`, edge classes extend `E`.

```java
g.executeInTx(tx -> {
  tx.command("CREATE CLASS Person EXTENDS V");
  tx.command("CREATE CLASS Movie EXTENDS V");
  tx.command("CREATE CLASS ActedIn EXTENDS E");
  tx.command("CREATE CLASS Directed EXTENDS E");
});
```

Properties define the schema. Constraints like `MANDATORY` and `MIN` enforce
data integrity at the database level.

```java
g.executeInTx(tx -> {
  tx.command("CREATE PROPERTY Person.name STRING (MANDATORY TRUE, MIN 1)");
  tx.command("CREATE PROPERTY Person.birthYear INTEGER");
  tx.command("CREATE PROPERTY Movie.title STRING (MANDATORY TRUE)");
  tx.command("CREATE PROPERTY Movie.year INTEGER");
  tx.command("CREATE PROPERTY Movie.genre STRING");
  tx.command("CREATE PROPERTY ActedIn.role STRING");
});
```

Indexes accelerate lookups. `UNIQUE` enforces uniqueness; `NOTUNIQUE`
allows duplicates.

```java
g.executeInTx(tx -> {
  tx.command("CREATE INDEX Person.name ON Person (name) UNIQUE");
  tx.command("CREATE INDEX Movie.title ON Movie (title) UNIQUE");
  tx.command("CREATE INDEX Movie.year ON Movie (year) NOTUNIQUE");
});
```

For the full YQL reference, see:
- [CREATE CLASS](yql/YQL-Create-Class.md)
- [CREATE PROPERTY](yql/YQL-Create-Property.md)
- [CREATE INDEX](yql/YQL-Create-Index.md)

## 3. Insert Data

### Vertices

Use `CREATE VERTEX` to add records. The `SET` clause defines property values.

```java
g.executeInTx(tx -> {
  tx.yql("CREATE VERTEX Person SET name = 'Alice', birthYear = 1990").iterate();
  tx.yql("CREATE VERTEX Person SET name = 'Bob', birthYear = 1985").iterate();
  tx.yql("CREATE VERTEX Person SET name = 'Charlie', birthYear = 1978").iterate();
  tx.yql("CREATE VERTEX Movie SET title = 'The Matrix', year = 1999, genre = 'Sci-Fi'").iterate();
  tx.yql("CREATE VERTEX Movie SET title = 'Inception', year = 2010, genre = 'Sci-Fi'").iterate();
  tx.yql("CREATE VERTEX Movie SET title = 'The Godfather', year = 1972, genre = 'Crime'").iterate();
});
```

### Edges

Edges connect vertices. Use sub-queries in `FROM` and `TO` to select the endpoints.

```java
g.executeInTx(tx -> {
  tx.yql("CREATE EDGE ActedIn "
      + "FROM (SELECT FROM Person WHERE name = 'Alice') "
      + "TO (SELECT FROM Movie WHERE title = 'The Matrix') "
      + "SET role = 'Trinity'").iterate();
  tx.yql("CREATE EDGE ActedIn "
      + "FROM (SELECT FROM Person WHERE name = 'Bob') "
      + "TO (SELECT FROM Movie WHERE title = 'The Matrix') "
      + "SET role = 'Neo'").iterate();
  tx.yql("CREATE EDGE Directed "
      + "FROM (SELECT FROM Person WHERE name = 'Charlie') "
      + "TO (SELECT FROM Movie WHERE title = 'Inception')").iterate();
});
```

For the full YQL reference, see:
- [CREATE VERTEX](yql/YQL-Create-Vertex.md)
- [CREATE EDGE](yql/YQL-Create-Edge.md)

## 4. Query Data

### Projections

A `SELECT` with projections returns `Map<String, Object>` results:

```java
g.executeInTx(tx -> {
  var people = tx.yql(
      "SELECT name, birthYear FROM Person ORDER BY name").toList();
  for (var row : people) {
    var map = (Map<String, Object>) row;
    System.out.println(map.get("name") + ", born " + map.get("birthYear"));
  }
});
// Alice, born 1990
// Bob, born 1985
// Charlie, born 1978
```

### Full Records

A `SELECT` without projections returns `Vertex` objects:

```java
g.executeInTx(tx -> {
  var scifi = tx.yql(
      "SELECT FROM Movie WHERE genre = 'Sci-Fi' AND year >= 2000").toList();
  for (var row : scifi) {
    var v = (Vertex) row;
    System.out.println(v.value("title")); // Inception
  }
});
```

### Filtering with WHERE

YQL supports familiar SQL conditions — comparisons, `LIKE`, `BETWEEN`, `IN`,
`CONTAINS`, and more:

```java
g.executeInTx(tx -> {
  var young = tx.yql(
      "SELECT name FROM Person WHERE birthYear > 1980 ORDER BY name").toList();
  // Returns: Alice, Bob
});
```

### Aggregation

Standard SQL aggregation functions work as expected:

```java
g.executeInTx(tx -> {
  var result = tx.yql("SELECT COUNT(*) AS cnt FROM Person").next();
  var map = (Map<String, Object>) result;
  System.out.println("Total: " + map.get("cnt")); // Total: 3
});
```

For the full YQL reference, see:
- [SELECT](yql/YQL-Query.md)
- [WHERE conditions](yql/YQL-Where.md)
- [Projections](yql/YQL-Projections.md)
- [Functions](yql/YQL-Functions.md)

## 5. Graph Pattern Matching with MATCH

The `MATCH` statement is the recommended way to traverse graphs in YQL. It uses
declarative patterns to find connected vertices.

### Basic Pattern: Actors and Movies

```java
g.executeInTx(tx -> {
  var matches = tx.yql(
      "MATCH {class: Person, as: actor}"
          + " -ActedIn-> {class: Movie, as: movie}"
          + " RETURN actor.name AS actor, movie.title AS movie"
          + " ORDER BY actor, movie")
      .toList();
  for (var row : matches) {
    var map = (Map<String, Object>) row;
    System.out.println(map.get("actor") + " in " + map.get("movie"));
  }
});
// Alice in Inception
// Alice in The Matrix
// Bob in The Matrix
// Charlie in The Godfather
```

The pattern reads naturally: start at a `Person` vertex, follow an outgoing
`ActedIn` edge (`-ActedIn->`), and arrive at a `Movie` vertex.

### Multi-hop Traversal: Co-actors

Patterns can chain multiple hops. This finds people who acted in the same
movie as Alice:

```java
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
  // Returns: Bob in The Matrix
});
```

The pattern traverses two hops: Alice → Movie → co-actor, using `<-ActedIn-`
for the reverse direction.

For the full MATCH reference, see [MATCH](yql/YQL-Match.md).

## 6. Update Data

```java
g.executeInTx(tx -> {
  tx.yql("UPDATE Movie SET genre = 'Sci-Fi/Thriller'"
      + " WHERE title = 'Inception'").iterate();
});
```

`UPDATE` supports `SET`, `REMOVE`, `CONTENT` (replace entire record), and
`MERGE` (merge JSON into record). See [UPDATE](yql/YQL-Update.md).

## 7. Delete Data

```java
g.executeInTx(tx -> {
  tx.yql("DELETE EDGE Directed"
      + " FROM (SELECT FROM Person WHERE name = 'Charlie')"
      + " TO (SELECT FROM Movie WHERE title = 'Inception')").iterate();
});
```

Always delete edges before deleting vertices that they connect to.
`DELETE VERTEX` automatically removes all connected edges.
See [DELETE VERTEX](yql/YQL-Delete-Vertex.md) and
[DELETE EDGE](yql/YQL-Delete-Edge.md).

## 8. Parameterized Queries

Use `:param` placeholders and pass values as key-value pairs. This prevents
injection and improves query plan caching.

```java
g.executeInTx(tx -> {
  var result = tx.yql(
      "SELECT name, birthYear FROM Person WHERE name = :name",
      "name", "Bob").next();
  var map = (Map<String, Object>) result;
  System.out.println(map.get("name") + ", born " + map.get("birthYear"));
  // Bob, born 1985
});
```

## 9. Transactions

All data operations in YouTrackDB are transactional. The `executeInTx()` method
automatically starts, commits, and rolls back transactions:

```java
// Read-write transaction — commits on success, rolls back on exception
g.executeInTx(tx -> {
  tx.yql("CREATE VERTEX Person SET name = 'Diana', birthYear = 1995").iterate();
});

// Read-only transaction that returns a result
var count = g.computeInTx(tx ->
    ((Map<String, Object>) tx.yql("SELECT COUNT(*) AS cnt FROM Person").next())
        .get("cnt"));
```

- `executeInTx()` — runs code in a transaction without returning a value
- `computeInTx()` — runs code in a transaction and returns a result

## 10. Inheritance and Polymorphic Queries

YouTrackDB supports class inheritance — including multiple inheritance. Querying
a parent class automatically returns instances of all its subclasses.

### Abstract Base Classes

```java
g.executeInTx(tx -> {
  // Abstract classes cannot be instantiated directly.
  tx.command("CREATE CLASS Animal EXTENDS V ABSTRACT");
  tx.command("CREATE PROPERTY Animal.name STRING (MANDATORY TRUE)");
  tx.command("CREATE PROPERTY Animal.sound STRING");

  // Concrete subclasses inherit all properties from Animal.
  tx.command("CREATE CLASS Dog EXTENDS Animal");
  tx.command("CREATE PROPERTY Dog.breed STRING");

  tx.command("CREATE CLASS Cat EXTENDS Animal");
  tx.command("CREATE PROPERTY Cat.indoor BOOLEAN");
});
```

### Multiple Inheritance

A class can extend more than one superclass. It inherits properties from all of them.

```java
g.executeInTx(tx -> {
  tx.command("CREATE CLASS Pet EXTENDS V ABSTRACT");
  tx.command("CREATE PROPERTY Pet.ownerName STRING");

  // HouseDog inherits from both Dog and Pet.
  tx.command("CREATE CLASS HouseDog EXTENDS Dog, Pet");
});
```

### Polymorphic Queries

Querying the base class returns all subclass instances:

```java
g.executeInTx(tx -> {
  tx.yql("CREATE VERTEX Dog SET name = 'Rex', sound = 'Woof', breed = 'Shepherd'").iterate();
  tx.yql("CREATE VERTEX Cat SET name = 'Whiskers', sound = 'Meow', indoor = true").iterate();
  tx.yql("CREATE VERTEX HouseDog SET name = 'Buddy', sound = 'Bark',"
      + " breed = 'Labrador', ownerName = 'Alice'").iterate();
});

// SELECT FROM Animal returns Dog, Cat, and HouseDog instances.
g.executeInTx(tx -> {
  var animals = tx.yql("SELECT name, @class AS cls FROM Animal ORDER BY name").toList();
  // Buddy (HouseDog), Rex (Dog), Whiskers (Cat)
});
```

Querying a subclass directly returns only its instances (including further
subclasses). `SELECT FROM Dog` returns Dog and HouseDog but not Cat:

```java
g.executeInTx(tx -> {
  var dogs = tx.yql("SELECT name FROM Dog ORDER BY name").toList();
  // Buddy, Rex
});
```

Multiple inheritance means HouseDog also appears in `Pet` queries:

```java
g.executeInTx(tx -> {
  var pets = tx.yql("SELECT name, ownerName FROM Pet ORDER BY name").toList();
  // Buddy, owner=Alice
});
```

For a deeper dive into property types, schema evolution, and edge inheritance,
see the [Object-Oriented Data Modeling](object-oriented.md) guide.

For the full YQL reference, see:
- [CREATE CLASS](yql/YQL-Create-Class.md) (including `ABSTRACT` and `EXTENDS`)
- [ALTER CLASS](yql/YQL-Alter-Class.md) (change superclasses at runtime)

## Next Steps

- [Fine-Grained Security](security.md) — predicate-based security policies, per-role filtering
- [YQL Introduction](yql/YQL-Introduction.md) — overview of the YQL dialect
- [YQL Commands Reference](yql/YQL-Commands.md) — complete list of all YQL commands
- [MATCH Statement](yql/YQL-Match.md) — full guide to graph pattern matching
- [YQL Functions](yql/YQL-Functions.md) — built-in functions (graph, math, collections)
- [EXPLAIN](yql/YQL-Explain.md) and [PROFILE](yql/YQL-Profile.md) — query analysis tools
