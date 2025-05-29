package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrains.youtrack.db.api.YouTrackDB;

public interface YTDBSingleThreadGraphFactory {
    boolean isOpen();

    void close();

    YTDBGraph openGraph();

    YouTrackDB getYouTrackDB();

    String getDatabaseName();
}
