package com.jetbrains.youtrackdb.internal.core.config;

import com.jetbrains.youtrackdb.internal.core.db.BasicDatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.db.SessionListener;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.Configuration;

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

  @Nonnull
  Configuration toApacheConfiguration();
}
