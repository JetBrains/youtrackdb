package com.jetbrains.youtrack.db.api;

import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.query.Result;

public interface YouTrackDB extends BasicYouTrackDB<Result, DatabaseSession> {
}
