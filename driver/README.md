Implementation of the Gremlin driver for YouTrackDB.

To start to use this implementation please add `youtrackdb.io:youtrackdb-diver:${ytdb-version}`
dependency to your project.
Then call `Cluster.Builder#channelizer(YTDBDriverWebSocketChannelizer.class)` during establishing
connection to the server.

```java
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;


var cluster = Cluster.build().addContactPoint("localhost").port(8182)
    .channelizer(YTDBDriverWebSocketChannelizer.class).create();
var client = cluster.connect();
var connection = DriverRemoteConnection.using(client, "ytdb{yourDbNameHere}");
var g = traversal(YTDBGraphTraversal.class).withRemote(connection);
```