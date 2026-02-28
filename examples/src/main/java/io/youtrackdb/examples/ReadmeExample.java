package io.youtrackdb.examples;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBDemoGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONMapper;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONVersion;

import static org.apache.tinkerpop.gremlin.process.traversal.P.gt;

/// Minimal example of usage of YouTrackDB as an embedded database.
/// This file is embedded into README.md by embedme — keep it self-contained.
public class ReadmeExample {

  public static void main(String[] args) throws Exception {
    // Create a YouTrackDB database manager instance and provide the root
    // folder where all databases will be stored.
    // We work with in-memory databases here, so we use "." as a root folder.
    try (var ytdb = YourTracks.instance(".")) {
      // Use YourTracks.instance("localhost", "root-name", "root-password")
      // if you want to connect to a server instead.

      // Create the database with demo data to play with it.
      try (var traversalSource = YTDBDemoGraphFactory.createModern(ytdb)) {
        // Prepare GraphSONMapper to check our results.
        var jsonMapper = GraphSONMapper.build()
            .version(GraphSONVersion.V1_0) // use the simplest version for brevity
            .addRegistry(YTDBIoRegistry.instance()) // add serializer for custom types
            .create().createMapper();

        // YTDB data manipulation is performed inside a transaction.
        // YTDBGraphTraversal will start a transaction automatically if one is
        // not started yet, but then you need to commit it manually and the
        // transaction borders become diluted.  We suggest using the
        // lambda-style API to automatically start/commit/rollback transactions.
        traversalSource.executeInTx(g -> {
          // Find a vertex with class "person" and property "name" equal to "marko".
          var v = g.V().has("person", "name", "marko").next();
          System.out.println("output:" + jsonMapper.writeValueAsString(v));
          // output:{
          //   "id":{..},
          //   "label":"person",
          //   "type":"vertex",
          //   "properties":{
          //     "name":[{"id":{..},"value":"marko"}],
          //     "age":[{"id":{..},"value":29}]
          //   }
          // }

          // Get the names of the people the vertex knows who are over 30.
          var friendNames = g.V(v.id()).out("knows").has("age",
              gt(30)).<String>values("name").toList();
          System.out.println("output:" + String.join(", ", friendNames));
          // output: josh
        });

        // Create an empty database with the name "tg", username "superuser",
        // admin role and password "adminpwd".
        ytdb.create("tg", DatabaseType.MEMORY, "superuser", "adminpwd", "admin");
        // Open a YTDBGraphTraversal instance for the new database.
        try (var newTraversal = ytdb.openTraversal("tg", "superuser", "adminpwd")) {
          newTraversal.executeInTx(g -> {
            // Create a vertex with class(label) "person" and properties.
            var v1 = g.addV("person")
                .property("name", "marko").property("age", 29).next();
            System.out.println("output:" + jsonMapper.writeValueAsString(v1));
            // output:{
            //   "id":{..},
            //   "label":"person",
            //   "type":"vertex",
            //   "properties":{
            //     "name":[{"id":{..},"value":"marko"}],
            //     "age":[{"id":{..},"value":29}]
            //   }
            // }

            // Create a vertex with class(label) "software" and properties.
            var v2 = g.addV("software")
                .property("name", "lop").property("lang", "java").next();
            // Connect both vertices by "created" relation.
            // We need to call iterate() here to execute the traversal flow.
            g.addE("created").from(v1).to(v2).property("weight", 0.4).iterate();
          });

          // Check the results of data modification after commit.
          traversalSource.executeInTx(g -> {
            var createdSoftware = g.V().has("person", "name", "marko")
                .out("created").<String>values("name").toList();
            System.out.println("output:" + String.join(", ", createdSoftware));
            // output: lop
          });
        }
      }
    }
  }
}
