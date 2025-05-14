package com.jetbrains.youtrack.db.api.remote.query;

import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;

public interface RemoteLiveQueryResultListener extends
    BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> {
}
