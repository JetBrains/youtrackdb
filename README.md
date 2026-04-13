## YouTrackDB

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) [![official JetBrains project](https://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)</br>
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?style=for-the-badge&logo=Bluesky&logoColor=white)](https://bsky.app/profile/youtrackdb.io)
[![Zulip](https://img.shields.io/badge/Zulip-50ADFF?style=for-the-badge&logo=Zulip&logoColor=white)](https://youtrackdb.zulipchat.com/)
[![Medium](https://img.shields.io/badge/Medium-12100E?style=for-the-badge&logo=medium&logoColor=white)](https://medium.com/@youtrackdb)
[![Reddit](https://img.shields.io/badge/Reddit-%23FF4500.svg?style=for-the-badge&logo=Reddit&logoColor=white)](https://www.reddit.com/r/youtrackdb/)<br/>

------
[Issue tracker](https://youtrack.jetbrains.com/issues/YTDB) | [Getting started](docs/getting-started.md)

### Join our Zulip community!

If you are interested in YouTrackDB, consider joining our [Zulip](https://youtrackdb.zulipchat.com/)
community.
Tell us about exciting applications you are building, ask for help, or just chat with friends 😃

### What is YouTrackDB?

YouTrackDB is a **general use** object-oriented graph database.
YouTrackDB is being supported and developed by JetBrains and is used internally in production.

YouTrackDB's key features are:
1. **Fast data processing**: Links traversal is processed with O(1) complexity. There are no
   expensive run-time JOINs.
2. **[Object-oriented API](docs/object-oriented.md)**: This API implements rich graph and
   object-oriented data models. Fundamental concepts of
   [inheritance and polymorphism](docs/yql/YQL-Create-Class.md) are implemented on the database
   level.
3. **Snapshot isolation by default**: All transactions run under snapshot isolation. Each
   transaction sees a stable snapshot of the database as of its start time, eliminating dirty
   reads, non-repeatable reads, and phantom reads.
4. **Implementation of TinkerPop API and [Gremlin query language](https://tinkerpop.apache.org/)**:
   You can use both Gremlin query language for your queries and TinkerPop API out of the box. 
   Support of `GQL` with seamless integration with `Gremlin` is [in progress](https://youtrackdb.zulipchat.com/#narrow/channel/511446-dev/topic/GQL.20.20implementation/with/567918479).
   For maximum query performance, we suggest using [YQL](docs/yql/YQL-Introduction.md) for initial
   data prefetching.
5. **[YQL](docs/yql/YQL-Introduction.md) (YouTrackDB Query Language)**: A SQL-based query language
   with extensions for graph functionality. YQL uses intuitive dot notation for link traversal
   instead of JOINs, supports the powerful [MATCH statement](docs/yql/YQL-Match.md) for graph
   pattern matching, and includes automatic index usage for query optimization.
6. **Scalable development workflow**: YouTrackDB works in schema-less, schema-mixed, and schema-full
   modes.
7. **[Strong security](docs/security.md)**: A strong security profiling system based on user, role,
   and predicate [security policies](docs/yql/YQL-Create-Security-Policy.md).
8. **Encryption of data at rest**: Optionally encrypts all data stored on disk.

### Easy to install and use

YouTrackDB can run on any platform without configuration and installation.

If you want to experiment with YouTrackDB, please check out our REPL [console](console/README.md).

```bash
docker run -it youtrackdb/youtrackdb-console
```

To install YouTrackDB as an embedded database, add the following dependency to your Maven project:

```xml

<dependency>
  <groupId>io.youtrackdb</groupId>
  <artifactId>youtrackdb-embedded</artifactId>
  <version>0.5.0-SNAPSHOT</version>
</dependency>
```

The `youtrackdb-embedded` artifact is a shaded uber-jar that relocates all third-party
dependencies (Guava, Jackson, Groovy, etc.) under `com.jetbrains.youtrackdb.shade`,
so they won't conflict with versions used by your application.

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
    implementation 'io.youtrackdb:youtrackdb-embedded:0.5.0-SNAPSHOT'
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

If you want to work with YouTrackDB server, you can start it using the Docker image:

```bash
docker run -p 8182:8182 \
 -v $(pwd)/secrets:/opt/ytdb-server/secrets \
 -v $(pwd)/databases:/opt/ytdb-server/databases \
 -v $(pwd)/conf:/opt/ytdb-server/conf \
 -v $(pwd)/log:/opt/ytdb-server/log \
 youtrackdb/youtrackdb-server
```

and provide root password for the database in the `secrets/root_password` file.

YouTrackDB requires at least JDK 21.

To learn how to use YouTrackDB, see the [Getting Started](docs/getting-started.md) guide.

For more examples covering both server and embedded deployments, check out the
[examples](examples/src/main/java/io/youtrackdb/examples) project.