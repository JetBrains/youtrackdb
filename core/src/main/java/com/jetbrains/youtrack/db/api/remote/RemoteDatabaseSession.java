package com.jetbrains.youtrack.db.api.remote;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResultSet;

public interface RemoteDatabaseSession extends
    BasicDatabaseSession<RemoteResult, RemoteResultSet> {

}
