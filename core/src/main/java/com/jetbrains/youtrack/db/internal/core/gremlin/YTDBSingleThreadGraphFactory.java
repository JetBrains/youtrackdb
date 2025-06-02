package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;

public interface YTDBSingleThreadGraphFactory {
    boolean isOpen();

    void close();

    YTDBGraph openGraph();

    YouTrackDB getYouTrackDB();

    String getDatabaseName();
}
