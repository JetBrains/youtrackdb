package com.jetbrains.youtrack.db.api.config;

import com.jetbrains.youtrack.db.api.DatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrack.db.api.SessionListener;
import java.util.Map;
import javax.annotation.Nonnull;

public interface YouTrackDBConfigBuilder {
  @Nonnull
  YouTrackDBConfigBuilder fromGlobalConfigurationParameters(
      @Nonnull Map<GlobalConfiguration, Object> values);

  @Nonnull
  YouTrackDBConfigBuilder fromMap(@Nonnull Map<String, Object> values);

  @Nonnull
  YouTrackDBConfigBuilder addSessionListener(@Nonnull SessionListener listener);

  @Nonnull
  YouTrackDBConfigBuilder addAttribute(@Nonnull final ATTRIBUTES attribute,
      @Nonnull final Object value);

  @Nonnull
  YouTrackDBConfigBuilder addGlobalConfigurationParameter(
      @Nonnull GlobalConfiguration configuration,
      @Nonnull Object value);

  @Nonnull
  YouTrackDBConfig build();
}
