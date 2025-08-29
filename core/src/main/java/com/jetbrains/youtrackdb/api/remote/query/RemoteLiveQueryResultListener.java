package com.jetbrains.youtrackdb.api.remote.query;

import com.jetbrains.youtrackdb.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;

public interface RemoteLiveQueryResultListener extends
    BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> {

}
