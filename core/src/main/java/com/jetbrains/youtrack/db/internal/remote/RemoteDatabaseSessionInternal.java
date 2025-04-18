package com.jetbrains.youtrack.db.internal.remote;

import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.internal.core.db.PooledSession;

public interface RemoteDatabaseSessionInternal extends RemoteDatabaseSession{
    boolean assertIfNotActive();
    RemoteCommandsOrchestrator getCommandOrchestrator();
}
