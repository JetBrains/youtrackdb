package com.jetbrains.youtrack.db.internal.remote;

import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;

public interface RemoteDatabaseSessionInternal extends RemoteDatabaseSession {
    boolean assertIfNotActive();

    RemoteCommandsDispatcher getCommandOrchestrator();
}
