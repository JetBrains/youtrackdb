# Object-Oriented Data Modeling

YouTrackDB is an object-oriented graph database. Fundamental concepts of
inheritance and polymorphism are implemented at the database level — classes
form hierarchies, subclasses inherit properties, and queries on a parent class
automatically return instances of all its subclasses.

All examples use the public API and run as embedded in-memory databases.
The complete source code is in
[`ObjectOrientedExample.java`](../examples/src/main/java/io/youtrackdb/examples/ObjectOrientedExample.java),
with tests in
[`ExamplesTest.java`](../examples/src/test/java/io/youtrackdb/examples/ExamplesTest.java).

## 1. Classes and Inheritance

Classes in YouTrackDB are analogous to tables in a relational database.
Vertex classes extend `V`, edge classes extend `E`.

### Abstract Base Classes

Abstract classes define shared schema but cannot be instantiated directly:

```java
g.executeInTx(tx -> {
  tx.command("CREATE CLASS Vehicle EXTENDS V ABSTRACT");
  tx.command("CREATE PROPERTY Vehicle.make STRING");
  tx.command("CREATE PROPERTY Vehicle.model STRING");
  tx.command("CREATE PROPERTY Vehicle.year INTEGER");
});
```

### Concrete Subclasses

Subclasses inherit all properties from their parent:

```java
g.executeInTx(tx -> {
  tx.command("CREATE CLASS Car EXTENDS Vehicle");
  tx.command("CREATE PROPERTY Car.doors INTEGER");

  tx.command("CREATE CLASS Truck EXTENDS Vehicle");
  tx.command("CREATE PROPERTY Truck.payloadTons DOUBLE");

  tx.command("CREATE CLASS Motorcycle EXTENDS Vehicle");
  tx.command("CREATE PROPERTY Motorcycle.engineCC INTEGER");
});
```

### Multiple Inheritance

A class can extend more than one superclass, inheriting properties from all
of them:

```java
g.executeInTx(tx -> {
  tx.command("CREATE CLASS Electric EXTENDS V ABSTRACT");
  tx.command("CREATE PROPERTY Electric.batteryKWh DOUBLE");
  tx.command("CREATE PROPERTY Electric.range INTEGER");

  // ElectricCar has: make, model, year (from Vehicle via Car),
  //                  doors (from Car), batteryKWh, range (from Electric)
  tx.command("CREATE CLASS ElectricCar EXTENDS Car, Electric");
});
```

For the full syntax, see [CREATE CLASS](yql/YQL-Create-Class.md).

## 2. Polymorphic Queries

Querying a parent class automatically returns instances of all its subclasses.
This is one of the key advantages of YouTrackDB's object-oriented model —
polymorphic queries are accelerated by the query engine.

### Base Class Query

```java
g.executeInTx(tx -> {
  var vehicles = tx.yql(
      "SELECT make, model, @class AS type FROM Vehicle ORDER BY make")
      .toList();
  // Returns:
  //   Ford F-150 (Truck)
  //   Honda CB500 (Motorcycle)
  //   Tesla Model 3 (ElectricCar)
  //   Toyota Camry (Car)
});
```

### Mid-Level Class Query

Querying a mid-level class returns its instances and those of its subclasses:

```java
g.executeInTx(tx -> {
  var cars = tx.yql("SELECT make, model FROM Car ORDER BY make").toList();
  // Returns: Tesla Model 3 (ElectricCar), Toyota Camry (Car)
  // Truck and Motorcycle are NOT included.
});
```

### Multiple Inheritance Query

ElectricCar appears in queries on both `Car` and `Electric`:

```java
g.executeInTx(tx -> {
  var evs = tx.yql(
      "SELECT make, model, batteryKWh FROM Electric ORDER BY make").toList();
  // Returns: Tesla Model 3, battery=60.0
});
```

## 3. Edge Inheritance

Edge classes support the same inheritance model as vertex classes. This lets
you define a base relationship and specialize it:

```java
g.executeInTx(tx -> {
  tx.command("CREATE CLASS Owns EXTENDS E");
  tx.command("CREATE CLASS Leases EXTENDS Owns");
  tx.command("CREATE PROPERTY Owns.since INTEGER");
});

g.executeInTx(tx -> {
  tx.yql("CREATE EDGE Leases"
      + " FROM (SELECT FROM Person WHERE name = 'Alice')"
      + " TO (SELECT FROM ElectricCar)"
      + " SET since = 2024").iterate();
});

// Querying the parent edge class returns subclass edges.
g.executeInTx(tx -> {
  var results = tx.yql(
      "SELECT expand(outE('Owns')) FROM Person WHERE name = 'Alice'")
      .toList();
  var edge = (Edge) results.get(0);
  edge.label();  // "Leases"
  edge.value("since");  // 2024
});
```

## 4. Property Types

YouTrackDB supports a rich set of property types.

### Standard Types

| | | | | |
|---|---|---|---|---|
| `BOOLEAN` | `SHORT` | `DATE` | `DATETIME` | `BYTE` |
| `INTEGER` | `LONG` | `STRING` | `LINK` | `DECIMAL` |
| `DOUBLE` | `FLOAT` | `BINARY` | | |

### Container Types

| | | |
|---|---|---|
| `EMBEDDEDLIST` | `EMBEDDEDSET` | `EMBEDDEDMAP` |
| `LINKLIST` | `LINKSET` | `LINKMAP` |

### Example: All Types in Action

```java
g.executeInTx(tx -> {
  tx.command("CREATE CLASS Employee EXTENDS V");
  tx.command("CREATE PROPERTY Employee.name STRING");
  tx.command("CREATE PROPERTY Employee.age INTEGER");
  tx.command("CREATE PROPERTY Employee.salary DOUBLE");
  tx.command("CREATE PROPERTY Employee.active BOOLEAN");

  // Embedded collections
  tx.command("CREATE PROPERTY Employee.skills EMBEDDEDLIST STRING");
  tx.command("CREATE PROPERTY Employee.tags EMBEDDEDSET STRING");
  tx.command("CREATE PROPERTY Employee.metadata EMBEDDEDMAP STRING");

  // Auto-generated immutable ID
  tx.command("CREATE PROPERTY Employee.uid STRING");
  tx.command("ALTER PROPERTY Employee.uid DEFAULT \"uuid()\"");
  tx.command("ALTER PROPERTY Employee.uid READONLY TRUE");
});

g.executeInTx(tx -> {
  tx.yql("CREATE VERTEX Employee SET name = 'Alice', age = 30,"
      + " salary = 85000.0, active = true,"
      + " skills = ['Java', 'Python', 'SQL'],"
      + " tags = ['engineering', 'senior', 'engineering'],"
      + " metadata = {'team': 'platform', 'level': 'L5'}").iterate();
});
```

- `EMBEDDEDLIST` preserves insertion order and allows duplicates
- `EMBEDDEDSET` automatically removes duplicates
- `EMBEDDEDMAP` stores key-value pairs
- `DEFAULT` + `READONLY` creates auto-generated immutable fields

For the full syntax, see [CREATE PROPERTY](yql/YQL-Create-Property.md) and
[ALTER PROPERTY](yql/YQL-Alter-Property.md).

## 5. Schema Evolution at Runtime

YouTrackDB lets you modify the schema at runtime without downtime. Existing
data is preserved through all schema changes.

### Rename a Class

```java
g.command("ALTER CLASS Account NAME Customer");
```

### Rename a Property

```java
g.command("ALTER PROPERTY Customer.email NAME contactEmail");
```

### Add a Superclass After Creation

```java
g.executeInTx(tx -> {
  tx.command("CREATE CLASS Auditable EXTENDS V ABSTRACT");
  tx.command("CREATE PROPERTY Auditable.createdAt DATETIME");
});
g.command("ALTER CLASS Customer SUPERCLASSES V, Auditable");

// Now Customer is queryable through Auditable:
g.executeInTx(tx -> {
  var results = tx.yql("SELECT COUNT(*) AS cnt FROM Auditable").next();
  // Returns count of all Customer records
});
```

### Add and Remove Properties

```java
// Add a new property to an existing class
g.command("CREATE PROPERTY Customer.phone STRING");

// Remove a property (schema only — existing data values are kept)
g.command("DROP PROPERTY Customer.phone IF EXISTS");
```

### Drop a Class

```java
// Drop an empty class
g.command("DROP CLASS TempClass IF EXISTS");

// Force-drop a class that still contains data
g.command("DROP CLASS OldData IF EXISTS UNSAFE");
```

For the full syntax, see [ALTER CLASS](yql/YQL-Alter-Class.md),
[DROP CLASS](yql/YQL-Drop-Class.md), and
[DROP PROPERTY](yql/YQL-Drop-Property.md).

## YQL Reference

- [CREATE CLASS](yql/YQL-Create-Class.md) — create classes with inheritance
- [ALTER CLASS](yql/YQL-Alter-Class.md) — rename, change superclasses, strict mode
- [DROP CLASS](yql/YQL-Drop-Class.md) — remove classes
- [CREATE PROPERTY](yql/YQL-Create-Property.md) — define typed properties with constraints
- [ALTER PROPERTY](yql/YQL-Alter-Property.md) — rename, add defaults, set readonly
- [DROP PROPERTY](yql/YQL-Drop-Property.md) — remove properties from schema
