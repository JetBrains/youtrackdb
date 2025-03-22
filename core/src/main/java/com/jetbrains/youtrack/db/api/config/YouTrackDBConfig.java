package com.jetbrains.youtrack.db.api.config;

import com.jetbrains.youtrack.db.api.DatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrack.db.api.SessionListener;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

public interface YouTrackDBConfig {

  @Nonnull
  static YouTrackDBConfig defaultConfig() {
    return new YouTrackDBConfigImpl();
  }

  @Nonnull
  static YouTrackDBConfigBuilder builder() {
    return new YouTrackDBConfigBuilderImpl();
  }

  @Nonnull
  Map<ATTRIBUTES, Object> getAttributes();

  @Nonnull
  Set<SessionListener> getListeners();
}
