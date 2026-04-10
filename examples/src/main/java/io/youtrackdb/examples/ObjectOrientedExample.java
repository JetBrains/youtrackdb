package io.youtrackdb.examples;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import java.util.ArrayList;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/// Object-Oriented Data Modeling in YouTrackDB.
///
/// This example demonstrates YouTrackDB's object-oriented features:
/// class inheritance, abstract classes, multiple inheritance, polymorphic
/// queries, property types with constraints, and schema evolution at runtime.
///
/// Companion guide: {@code docs/object-oriented.md}
public class ObjectOrientedExample {

  @SuppressWarnings("SystemOut")
  public static void main(String[] args) {
    try (var ytdb = YourTracks.instance(".")) {
      inheritanceExample(ytdb);
      propertyTypesExample(ytdb);
      schemaEvolutionExample(ytdb);
    }
  }

  // ----------------------------------------------------------------
  // 1. Inheritance and Polymorphic Queries
  // ----------------------------------------------------------------
  @SuppressWarnings("SystemOut")
  static void inheritanceExample(YouTrackDB ytdb) {
    ytdb.create("oo-inherit", DatabaseType.MEMORY,
        "admin", "adminpwd", "admin");

    try (var g = ytdb.openTraversal("oo-inherit", "admin", "adminpwd")) {
      // Abstract base class — cannot be instantiated directly.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Vehicle EXTENDS V ABSTRACT");
        tx.command("CREATE PROPERTY Vehicle.make STRING");
        tx.command("CREATE PROPERTY Vehicle.model STRING");
        tx.command("CREATE PROPERTY Vehicle.year INTEGER");
      });

      // Concrete subclasses inherit all Vehicle properties.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Car EXTENDS Vehicle");
        tx.command("CREATE PROPERTY Car.doors INTEGER");

        tx.command("CREATE CLASS Truck EXTENDS Vehicle");
        tx.command("CREATE PROPERTY Truck.payloadTons DOUBLE");

        tx.command("CREATE CLASS Motorcycle EXTENDS Vehicle");
        tx.command("CREATE PROPERTY Motorcycle.engineCC INTEGER");
      });

      // Multiple inheritance: ElectricCar inherits from both Car
      // and an abstract Electric mixin.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Electric EXTENDS V ABSTRACT");
        tx.command("CREATE PROPERTY Electric.batteryKWh DOUBLE");
        tx.command("CREATE PROPERTY Electric.range INTEGER");

        tx.command("CREATE CLASS ElectricCar EXTENDS Car, Electric");
      });

      g.executeInTx(tx -> {
        tx.yql("CREATE VERTEX Car SET make = 'Toyota', model = 'Camry',"
            + " year = 2023, doors = 4").iterate();
        tx.yql("CREATE VERTEX Truck SET make = 'Ford', model = 'F-150',"
            + " year = 2022, payloadTons = 1.5").iterate();
        tx.yql("CREATE VERTEX Motorcycle SET make = 'Honda',"
            + " model = 'CB500', year = 2024, engineCC = 471").iterate();
        tx.yql("CREATE VERTEX ElectricCar SET make = 'Tesla',"
            + " model = 'Model 3', year = 2024, doors = 4,"
            + " batteryKWh = 60.0, range = 350").iterate();
      });

      // Polymorphic query: SELECT FROM the base class returns
      // ALL subclass instances.
      g.executeInTx(tx -> {
        var vehicles = tx.yql(
            "SELECT make, model, @class AS type"
                + " FROM Vehicle ORDER BY make")
            .toList();
        for (var row : vehicles) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          System.out.println("output:vehicle:"
              + map.get("make") + " " + map.get("model")
              + "," + map.get("type"));
        }
      });

      // Mid-level class query: Car returns Car + ElectricCar.
      g.executeInTx(tx -> {
        var cars = tx.yql(
            "SELECT make, model FROM Car ORDER BY make").toList();
        var names = new ArrayList<String>();
        for (var row : cars) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          names.add(map.get("make") + " " + map.get("model"));
        }
        System.out.println("output:cars:" + String.join(",", names));
      });

      // Multiple inheritance: Electric returns ElectricCar.
      g.executeInTx(tx -> {
        var evs = tx.yql(
            "SELECT make, model, batteryKWh"
                + " FROM Electric ORDER BY make")
            .toList();
        for (var row : evs) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          System.out.println("output:electric:"
              + map.get("make") + " " + map.get("model")
              + ",battery=" + map.get("batteryKWh"));
        }
      });

      // Edge classes also support inheritance.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Owns EXTENDS E");
        tx.command("CREATE CLASS Leases EXTENDS Owns");
        tx.command("CREATE PROPERTY Owns.since INTEGER");

        tx.command("CREATE CLASS Person EXTENDS V");
      });

      g.executeInTx(tx -> {
        tx.yql("CREATE VERTEX Person SET name = 'Alice'").iterate();
        tx.yql("CREATE EDGE Leases"
            + " FROM (SELECT FROM Person WHERE name = 'Alice')"
            + " TO (SELECT FROM ElectricCar)"
            + " SET since = 2024").iterate();
      });

      // Querying the parent edge class returns subclass edges.
      g.executeInTx(tx -> {
        var results = tx.yql(
            "SELECT expand(outE('Owns')) FROM Person"
                + " WHERE name = 'Alice'")
            .toList();
        var edge = (Edge) results.get(0);
        System.out.println("output:edge class:" + edge.label());
        System.out.println("output:edge since:" + edge.value("since"));
      });
    }
  }

  // ----------------------------------------------------------------
  // 2. Property Types and Embedded Collections
  // ----------------------------------------------------------------
  @SuppressWarnings("SystemOut")
  static void propertyTypesExample(YouTrackDB ytdb) {
    ytdb.create("oo-props", DatabaseType.MEMORY,
        "admin", "adminpwd", "admin");

    try (var g = ytdb.openTraversal("oo-props", "admin", "adminpwd")) {
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Employee EXTENDS V");

        // Standard types
        tx.command("CREATE PROPERTY Employee.name STRING");
        tx.command("CREATE PROPERTY Employee.age INTEGER");
        tx.command("CREATE PROPERTY Employee.salary DOUBLE");
        tx.command("CREATE PROPERTY Employee.active BOOLEAN");

        // Container types: embedded list, set, and map
        tx.command("CREATE PROPERTY Employee.skills EMBEDDEDLIST STRING");
        tx.command("CREATE PROPERTY Employee.tags EMBEDDEDSET STRING");
        tx.command(
            "CREATE PROPERTY Employee.metadata EMBEDDEDMAP STRING");

        // Auto-generated immutable ID via DEFAULT + READONLY
        tx.command("CREATE PROPERTY Employee.uid STRING");
        tx.command("ALTER PROPERTY Employee.uid DEFAULT \"uuid()\"");
        tx.command("ALTER PROPERTY Employee.uid READONLY TRUE");
      });

      g.executeInTx(tx -> {
        tx.yql("CREATE VERTEX Employee SET name = 'Alice', age = 30,"
            + " salary = 85000.0, active = true,"
            + " skills = ['Java', 'Python', 'SQL'],"
            + " tags = ['engineering', 'senior', 'engineering'],"
            + " metadata = {'team': 'platform', 'level': 'L5'}")
            .iterate();
      });

      g.executeInTx(tx -> {
        var emp = (Vertex) tx.yql("SELECT FROM Employee").next();

        // uid was auto-generated
        var uid = (String) emp.value("uid");
        System.out.println(
            "output:uid generated:" + (uid != null && !uid.isEmpty()));

        // EMBEDDEDLIST preserves order and duplicates
        System.out.println("output:skills:" + emp.value("skills"));

        // EMBEDDEDSET removes duplicates
        var tags = emp.value("tags").toString();
        System.out.println(
            "output:tags unique:" + !tags.contains("engineering, engineering"));

        // EMBEDDEDMAP for key-value data
        @SuppressWarnings("unchecked")
        var meta = (Map<String, String>) emp.value("metadata");
        System.out.println("output:metadata team:" + meta.get("team"));
      });
    }
  }

  // ----------------------------------------------------------------
  // 3. Schema Evolution at Runtime
  // ----------------------------------------------------------------
  @SuppressWarnings("SystemOut")
  static void schemaEvolutionExample(YouTrackDB ytdb) {
    ytdb.create("oo-evolve", DatabaseType.MEMORY,
        "admin", "adminpwd", "admin");

    try (var g = ytdb.openTraversal("oo-evolve", "admin", "adminpwd")) {
      // Start with a simple class.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Account EXTENDS V");
        tx.command("CREATE PROPERTY Account.name STRING");
        tx.command("CREATE PROPERTY Account.email STRING");
      });

      g.executeInTx(tx -> {
        tx.yql("CREATE VERTEX Account SET name = 'Alice',"
            + " email = 'alice@example.com'").iterate();
        tx.yql("CREATE VERTEX Account SET name = 'Bob',"
            + " email = 'bob@example.com'").iterate();
      });

      // Rename a class at runtime. Existing data is preserved.
      g.command("ALTER CLASS Account NAME Customer");

      // Add new properties to the renamed class.
      g.command("CREATE PROPERTY Customer.phone STRING");

      // Rename a property.
      g.command("ALTER PROPERTY Customer.email NAME contactEmail");

      // Add a superclass after creation.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS Auditable EXTENDS V ABSTRACT");
        tx.command("CREATE PROPERTY Auditable.createdAt DATETIME");
      });
      g.command("ALTER CLASS Customer SUPERCLASSES V, Auditable");

      // Data is preserved through all schema changes.
      g.executeInTx(tx -> {
        var customers = tx.yql(
            "SELECT name, contactEmail FROM Customer ORDER BY name")
            .toList();
        for (var row : customers) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          System.out.println("output:customer:"
              + map.get("name") + "," + map.get("contactEmail"));
        }
      });

      // Polymorphic query through the new superclass.
      g.executeInTx(tx -> {
        var results = tx.yql(
            "SELECT COUNT(*) AS cnt FROM Auditable").next();
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) results;
        System.out.println("output:auditable count:" + map.get("cnt"));
      });

      // Drop a property (removes from schema only, data is kept).
      g.command("DROP PROPERTY Customer.phone IF EXISTS");

      // Drop an empty class.
      g.executeInTx(tx -> {
        tx.command("CREATE CLASS TempClass EXTENDS V");
      });
      g.command("DROP CLASS TempClass IF EXISTS");
      System.out.println("output:drop class:ok");
    }
  }
}
