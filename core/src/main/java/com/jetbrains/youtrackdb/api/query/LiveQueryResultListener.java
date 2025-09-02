package com.jetbrains.youtrackdb.api.query;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.query.BasicLiveQueryResultListener;

public interface LiveQueryResultListener extends
    BasicLiveQueryResultListener<DatabaseSession, Result> {

}
