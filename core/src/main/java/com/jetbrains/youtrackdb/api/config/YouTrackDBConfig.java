package com.jetbrains.youtrackdb.api.config;

import com.jetbrains.youtrackdb.api.SessionListener;
import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
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

  @Nonnull
  ContextConfiguration getConfiguration();
}
