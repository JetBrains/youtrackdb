package com.jetbrains.youtrack.db.api.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;

public interface LiveQueryResultListener extends
    BasicLiveQueryResultListener<DatabaseSession, Result> {

}
