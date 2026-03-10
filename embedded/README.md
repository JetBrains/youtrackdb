### YouTrackDB Embedded

The `youtrackdb-embedded` module provides a **shaded uber-jar** for using YouTrackDB
as an embedded database. All third-party dependencies (Guava, Jackson, Groovy, ANTLR,
fastutil, etc.) are relocated under `com.jetbrains.youtrackdb.shade` to avoid classpath
conflicts with versions used by your application.

#### Maven

```xml
<dependency>
  <groupId>io.youtrackdb</groupId>
  <artifactId>youtrackdb-embedded</artifactId>
  <version>${ytdb-version}</version>
</dependency>
```

#### Gradle

```
dependencies {
    implementation 'io.youtrackdb:youtrackdb-embedded:${ytdb-version}'
}
```

#### Quick start

```java
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;

try (var ytdb = YourTracks.instance(".")) {
  ytdb.create("mydb", DatabaseType.MEMORY, "admin", "adminpwd", "admin");
  try (var g = ytdb.openTraversal("mydb", "admin", "adminpwd")) {
    g.executeInTx(traversal -> {
      traversal.addV("person").property("name", "Alice").property("age", 30).iterate();
    });
    g.executeInTx(traversal -> {
      var names = traversal.V().has("person", "name", "Alice")
          .<String>values("name").toList();
      System.out.println(names); // [Alice]
    });
  }
}
```

See the [examples](../examples/src/main/java/io/youtrackdb/examples) project for more
complete usage patterns.

#### What is shaded (relocated)

The following libraries are relocated under `com.jetbrains.youtrackdb.shade.*`:

- Guava, Jackson, Apache Commons, fastutil, ANTLR4, Groovy 4.x, ASM, JNR/JFFI,
  SnakeYAML, HPPC, Caffeine, LZ4, exp4j, JavaTuples, JavaPoet, and others.

#### What is NOT bundled

The following must be provided by your application if needed:

| Dependency | Reason |
|---|---|
| **SLF4J** | Logging facade &mdash; you choose the binding (Logback, Log4j2, etc.) |
| **GraalVM / Truffle** | Only needed if you use the Gremlin JavaScript scripting engine |
| **Netty** | Native transport breaks under relocation; excluded from the shaded jar |

#### TinkerPop API

The custom TinkerPop fork (`io.youtrackdb:gremlin-*`) retains the original
`org.apache.tinkerpop` Java package and is **not relocated**. This preserves
ServiceLoader entries, Gremlin plugin registrations, and reflection-based lookups.

If your project also depends on upstream Apache TinkerPop (`org.apache.tinkerpop`),
use Maven/Gradle dependency exclusions to avoid conflicts.
