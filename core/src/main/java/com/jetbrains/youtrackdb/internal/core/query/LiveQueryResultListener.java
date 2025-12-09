package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;

public interface LiveQueryResultListener extends
    BasicLiveQueryResultListener<DatabaseSession, Result> {

}
