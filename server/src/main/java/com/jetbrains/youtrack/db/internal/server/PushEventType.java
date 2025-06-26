package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.internal.client.remote.message.BinaryPushRequest;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.NetworkProtocolBinary;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PushEventType {

  private final ConcurrentMap<String, BinaryPushRequest<?>> databases = new ConcurrentHashMap<>();
  protected final ConcurrentMap<String, Set<WeakReference<NetworkProtocolBinary>>> listeners =
      new ConcurrentHashMap<>();

  public synchronized void send(
      DatabaseSessionInternal session, String database, BinaryPushRequest<?> request,
      PushManager pushManager) {
    var prev = databases.put(database, request);
    if (prev == null) {
      pushManager.genericNotify(session, listeners, database, this);
    }
  }

  public synchronized BinaryPushRequest<?> getRequest(String database) {
    return databases.remove(database);
  }

  public synchronized void subscribe(String database, NetworkProtocolBinary protocol) {
    var pushSockets = listeners.get(database);
    if (pushSockets == null) {
      pushSockets = new HashSet<>();
      listeners.put(database, pushSockets);
    }
    pushSockets.add(new WeakReference<>(protocol));
  }

  public synchronized void cleanListeners() {
    for (var value : listeners.values()) {
      var iter = value.iterator();
      while (iter.hasNext()) {
        if (iter.next().get() == null) {
          iter.remove();
        }
      }
    }
  }
}
