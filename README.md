## YouTrackDB

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) [![official JetBrains project](https://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)</br>
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?style=for-the-badge&logo=Bluesky&logoColor=white)](https://bsky.app/profile/youtrackdb.io)
[![Zulip](https://img.shields.io/badge/Zulip-50ADFF?style=for-the-badge&logo=Zulip&logoColor=white)](https://youtrackdb.zulipchat.com/)
[![Medium](https://img.shields.io/badge/Medium-12100E?style=for-the-badge&logo=medium&logoColor=white)](https://medium.com/@youtrackdb)
[![Reddit](https://img.shields.io/badge/Reddit-%23FF4500.svg?style=for-the-badge&logo=Reddit&logoColor=white)](https://www.reddit.com/r/youtrackdb/)<br/>

------
[Issue tracker](https://youtrack.jetbrains.com/issues/YTDB) | [Knowledge base](https://youtrack.jetbrains.com/articles/YTDB) | [Roadmap](https://youtrack.jetbrains.com/articles/YTDB-A-3/Project-roadmap)

### Join our Zulip community!

If you are interested in YouTrackDB, consider joining our [Zulip](https://youtrackdb.zulipchat.com/) community. 
Tell us about exciting applications you are building, ask for help, or just chat with friends 😃

### What is YouTrackDB?

YouTrackDB is a *<b>general use</b>* object-oriented graph database that supports full-text search,
and geospatial concepts.\
YouTrackDB is being supported and developed by JetBrains and is used internally in production.

YouTrackDB's key features are:

1. **Fast data processing**: Links traversal is processed with O(1) complexity. There are no
   expensive run-time JOINs.
2. **Object-oriented API**: This API implements rich graph and object-oriented data models. Fundamental concepts of inheritance and polymorphism are implemented on the database level.
3. **Implementation of TinkerPop API and [Gremlin query language](https://tinkerpop.apache.org/)**:
   You can use both Gremlin query language for your queries and TinkerPop API out of the box.
4. **Scalable development workflow**: YouTrackDB works in schema-less, schema-mixed, and schema-full
   modes.
5. **Strong security**: A strong security profiling system based on user, role, and predicate
   security. (Currently implemented using a private API. Implementation of the public API is in
   progress.)
6. **Encryption of data at rest**: Optionally encrypts all data stored on disk.
7. **GEO-queries and full-text search**: GEO-queries and full-text search are supported via Lucene
   integration. (Currently implemented using a private API. Implementation of the public API is in
   progress.)

### Easy to install and use
YouTrackDB can run on any platform without configuration and installation.

If you want to experiment with YouTrackDB, please check out our REPL [console](console/README.md).

```bash
docker run -it youtrackdb/youtrackdb-console
```

To install YouTrackDB as an embedded database (migration to the Gremlin Server is in progress), add
the
following dependency to your Maven project:

```xml
<dependency>
  <groupId>io.youtrackdb</groupId>
  <artifactId>youtrackdb-core</artifactId>
  <version>0.5.0-SNAPSHOT</version>
</dependency>
```

You also need to add a YTDB snapshot repository to your Maven pom.xml file:

```xml
<repositories>
  <repository>
    <name>Central Portal Snapshots</name>
    <id>central-portal-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases>
      <enabled>false</enabled>
    </releases>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>
```

or in case of Gradle:

```
dependencies {
    implementation 'io.youtrackdb:youtrackdb-core:0.5.0-SNAPSHOT'
}
```

and

```
repositories {
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}
```

YourTrackDB requires at least JDK 21.

To start to work with YouTrackDB:

```java
package io.youtrackdb;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBDemoGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONMapper;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONVersion;

import java.util.List;

import static org.apache.tinkerpop.gremlin.process.traversal.P.gt;

/// Minimal example of usage of YouTrackDB.
public class Example {
    public static void main(String[] args) throws Exception {
        //Create a YouTrackDB database manager instance and provide the root folder where all databases will be stored
        try (var ytdb = YourTracks.instance("./target/data")) {
           //Prepare GraphSONMapper to check our results
           var jsonMapper = GraphSONMapper.build()
                   .version(GraphSONVersion.V1_0) // use the simplest version for brevity
                   .addRegistry(YTDBIoRegistry.instance())//add serializer for custom types
                   .create().createMapper();

           //Create the database with demo data to play with it
           try (var traversalSource = YTDBDemoGraphFactory.createModern(ytdb)) {
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
}
```

To check the full example of usage of YouTrackDB, please check out our [examples](examples).

## Stargazers over time

[![Stargazers over time](https://starchart.cc/JetBrains/youtrackdb.svg?variant=adaptive)](https://starchart.cc/JetBrains/youtrackdb)
