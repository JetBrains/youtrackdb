package com.jetbrains.youtrackdb.internal.core.config;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.db.SessionListener;
import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.Configuration;

public interface YouTrackDBConfigBuilder {

  @Nonnull
  YouTrackDBConfigBuilder fromApacheConfiguration(@Nonnull Configuration configuration);

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
