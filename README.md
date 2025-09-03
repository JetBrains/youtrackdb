## YouTrackDB

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)</br>
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

YouTrackDB is an object-oriented graph database that supports full-text search, reactivity, and geospatial concepts.\
YouTrackDB has been supported and developed by [YouTrack](https://www.jetbrains.com/youtrack)
project from JetBrains.

YouTrackDB's key features are:

1. **Fast data processing**: Links traversal is processed with O(1) complexity. There are no
   expensive run-time JOINs.
2. **Object-oriented API**: This API implements rich graph and object-oriented data models. Fundamental concepts of inheritance and polymorphism are implemented on the database level.
3. **Implementation of TinkerPop API and [Gremlin query language](https://tinkerpop.apache.org/)**:
   You can use both Gremlin query language for your queries and TinkerPop API out of the box.
4. **Scalable development workflow**: YouTrackDB works in schema-less, schema-mixed, and schema-full
   modes.
5. **Strong security**: A strong security profiling system based on user, role, and predicate
   security.
6. **Encryption of data at rest**: Optionally encrypts all data stored on disk.
7. **GEO-queries and full-text search**: GEO-queries and full-text search are supported using Lucene integration.

### Easy to install and use

YouTrackDB can run on any platform without configuration and installation.
The full Server distribution is a few MBs without the demo database.

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
    <id>ytdb-github</id>
    <name>youtrackdb-snapshots</name>
    <url>https://maven.pkg.github.com/youtrackdb/youtrackdb</url>
    <releases>
      <enabled>false</enabled>
    </releases>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>
```