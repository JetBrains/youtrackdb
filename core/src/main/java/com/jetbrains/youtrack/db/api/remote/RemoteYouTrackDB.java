package com.jetbrains.youtrack.db.api.remote;

import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;

public interface RemoteYouTrackDB extends BasicYouTrackDB<RemoteResult, RemoteDatabaseSession> {
}
