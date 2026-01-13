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

The difference between these two approaches is that the second one allows issuing queries using
users that are registered in the scope of the single database.