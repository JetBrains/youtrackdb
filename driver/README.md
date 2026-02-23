---
source_files:
  - driver/src/main/java/com/jetbrains/youtrackdb/internal/driver/**
  - driver/pom.xml
  - core/src/main/java/com/jetbrains/youtrackdb/api/YourTracks.java
  - core/src/main/java/com/jetbrains/youtrackdb/api/YouTrackDB.java
last_synced_commit: 2d5822220f
related_docs:
  - README.md
  - server/README.md
---

Implementation of the Gremlin driver for YouTrackDB.

To start to use this implementation please add `io.youtrackdb:youtrackdb-driver:${ytdb-version}`
dependency to your project.

Then you can connect to the server using commands

```java

import com.jetbrains.youtrackdb.api.YourTracks;

var ytdb = YourTracks.instance("localhost", "username", "password");
var traversalSource = ytdb.openTraversal("dbName");
```

or

```java

var ytdb = YourTracks.instance("localhost");
var traversalSource = ytdb.openTraversal("dbName", "username", "password");
```

The difference between these two approaches is that the second one allows issuing queries on behalf of 
users that are registered in the scope of the single database.