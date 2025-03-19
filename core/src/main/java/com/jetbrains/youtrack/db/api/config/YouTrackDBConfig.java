package com.jetbrains.youtrack.db.api.config;

import com.jetbrains.youtrack.db.api.DatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrack.db.api.SessionListener;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import java.util.Map;
import java.util.Set;

public interface YouTrackDBConfig {
  static YouTrackDBConfig defaultConfig() {
    return new YouTrackDBConfigImpl();
  }

  static YouTrackDBConfigBuilder builder() {
    return new YouTrackDBConfigBuilderImpl();
  }

  Map<ATTRIBUTES, Object> getAttributes();

  Set<SessionListener> getListeners();
}
