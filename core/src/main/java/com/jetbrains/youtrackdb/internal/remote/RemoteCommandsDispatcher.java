package com.jetbrains.youtrackdb.internal.remote;

import com.jetbrains.youtrackdb.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrackdb.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResult;
import com.jetbrains.youtrackdb.internal.core.db.DatabasePoolInternal;
import java.util.Map;

public interface RemoteCommandsDispatcher {
  LiveQueryMonitor live(DatabasePoolInternal<RemoteDatabaseSession> sessionPool, String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener, Map<String, ?> args);
  LiveQueryMonitor live(DatabasePoolInternal<RemoteDatabaseSession> sessionPool, String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener, Object... args);
}
