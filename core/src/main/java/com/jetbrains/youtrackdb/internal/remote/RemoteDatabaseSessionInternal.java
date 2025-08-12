package com.jetbrains.youtrackdb.internal.remote;

import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;

public interface RemoteDatabaseSessionInternal extends RemoteDatabaseSession {
    boolean assertIfNotActive();

    RemoteCommandsDispatcher getCommandOrchestrator();
}
