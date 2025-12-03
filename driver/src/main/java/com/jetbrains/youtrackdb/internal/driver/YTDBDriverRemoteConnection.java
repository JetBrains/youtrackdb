package com.jetbrains.youtrackdb.internal.driver;

import static org.apache.tinkerpop.gremlin.driver.RequestOptions.getRequestOptions;

import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteTransaction;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnectionException;
import org.apache.tinkerpop.gremlin.process.remote.traversal.RemoteTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.structure.Transaction;

public class YTDBDriverRemoteConnection extends DriverRemoteConnection {

  private final HashMap<RecordIdInternal, Set<ChangeableRecordId>> changeableRIDMap = new HashMap<>();

  public YTDBDriverRemoteConnection(Configuration conf) {
    super(conf);
  }


  public YTDBDriverRemoteConnection(Cluster cluster, Configuration conf) {
    super(cluster, conf);
  }

  public YTDBDriverRemoteConnection(Cluster cluster, boolean tryCloseCluster,
      String remoteTraversalSourceName) {
    super(cluster, tryCloseCluster, remoteTraversalSourceName);
  }

  public YTDBDriverRemoteConnection(Client client,
      String remoteTraversalSourceName, boolean tryCloseClient) {
    super(client, remoteTraversalSourceName, tryCloseClient);
  }

  @Override
  public <E> CompletableFuture<RemoteTraversal<?, E>> submitAsync(Bytecode bytecode)
      throws RemoteConnectionException {
    try {
      return client.submitAsync(bytecode, getRequestOptions(bytecode))
          .thenApply(
              rs -> new YTDBDriverRemoteTraversal<>(rs, client, attachElements, changeableRIDMap,
                  conf));
    } catch (Exception ex) {
      throw new RemoteConnectionException(ex);
    }
  }

  @Override
  public Transaction tx() {
    final var session = new YTDBDriverRemoteConnection(
        client.getCluster().connect(UUID.randomUUID().toString()), remoteTraversalSourceName, true);
    return new DriverRemoteTransaction(session);
  }

  @Override
  public void close() throws Exception {
    super.close();
    changeableRIDMap.clear();
  }
}
