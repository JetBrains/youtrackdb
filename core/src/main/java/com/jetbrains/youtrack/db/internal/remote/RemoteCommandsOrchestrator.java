package com.jetbrains.youtrack.db.internal.remote;

import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import java.util.Map;

public interface RemoteCommandsOrchestrator {
  LiveQueryMonitor live(DatabasePoolInternal<RemoteDatabaseSession> sessionPool, String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener, Map<String, ?> args);
  LiveQueryMonitor live(DatabasePoolInternal<RemoteDatabaseSession> sessionPool, String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener, Object... args);
}
