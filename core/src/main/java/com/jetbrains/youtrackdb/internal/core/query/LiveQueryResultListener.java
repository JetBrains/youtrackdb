package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

public interface LiveQueryResultListener extends
    BasicLiveQueryResultListener<DatabaseSessionEmbedded, Result> {

}
