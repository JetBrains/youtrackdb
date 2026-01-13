package io.youtrackdb.examples;

import static org.apache.tinkerpop.gremlin.process.traversal.P.gt;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.gremlin.YTDBDemoGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONMapper;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONVersion;
import org.apache.tinkerpop.shaded.jackson.core.JsonProcessingException;

public class AbstractExample {
  public static void ytdManipulationExample(@Nonnull YouTrackDB ytdb)
      throws JsonProcessingException {
    //Create the database with demo data to play with it
    try (var traversalSource = YTDBDemoGraphFactory.createModern(ytdb)) {
      //Prepare GraphSONMapper to check our results
      var jsonMapper = GraphSONMapper.build()
          .version(GraphSONVersion.V1_0) // use the simplest version for brevity
          .addRegistry(YTDBIoRegistry.instance())//add serializer for custom types
          .create().createMapper();

      //YTDB data manipulation is performed inside a transaction, so let us start one.
      //YTDBGraphTraversal will start transaction automatically if it is not started yet.
      //But in such a case you will need to commit it manually, and borders of transaction will be diluted,
      //we suggest using lambda-style API to automatically start/commit/rollback transactions.
      traversalSource.executeInTx(g -> {
        //Find a vertex with class "person" and property "name" equals to "marko".
        var v = g.V().has("person", "name", "marko").next();
        System.out.println("output:" + jsonMapper.writeValueAsString(v));
        //output:{
        //  "id":{..},
        //  "label":"person",
        //  "type":"vertex",
        //  "properties":{
        //    "name":[{"id":{..},"value":"marko"}],
        //    "age":[{"id":{..},"value":29}]
        //   }
        // }
        // there is ongoing change to implement conversion of vertices from/to native JSON
        // by using additional metadata provided by DB schema.
        //
        //Get the names of the people the vertex knows who are over the age of 30.
        var friendNames = g.V(v.id()).out("knows").has("age",
            gt(30)).<String>values("name").toList();
        System.out.println("output:" + String.join(", ", friendNames));
        //output: josh
      });

      //Create an empty database with the name "tg", username "superuser", admin role and password "adminpwd".
      ytdb.create("tg", DatabaseType.MEMORY, "superuser", "adminpwd", "admin");
      //and then open the YTDBGraphGraphTraversal instance
      try (var newTraversal = ytdb.openTraversal("tg", "superuser", "adminpwd")) {
        newTraversal.executeInTx(g -> {
          //create a vertex with class(label) "person" and properties' name and age.
          var v1 = g.addV("person").property("name", "marko").property("age", 29).next();
          System.out.println("output:" + jsonMapper.writeValueAsString(v1));
          // output : {
          //        "id":{..},
          //        "label":"person",
          //        "type":"vertex",
          //        "properties": {
          //          "name": [{"id":{...}, "value":"marko"}],
          //          "age":[{"id":{ ...}, "value":29}]
          //       }
          //  }

          // create a vertex with class(label) "software" and properties' name and lang.
          var v2 = g.addV("software").property("name", "lop").property("lang", "java").next();
          //connect both vertices by "created" relation.
          // we need to call iterate() here to execute traversal flow.
          g.addE("created").from(v1).to(v2).property("weight", 0.4).iterate();
        });

        //let us check the results of data modification after commit
        traversalSource.executeInTx(g -> {
          var createdSoftware = g.V().has("person", "name", "marko").out(
              "created").<String>values("name").toList();
          System.out.println("output:" + String.join(", ", createdSoftware));
          //output: lop
        });
      }
    }
  }
}
