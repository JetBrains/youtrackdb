package com.jetbrains.youtrack.db.api.config;

import com.jetbrains.youtrack.db.api.DatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrack.db.api.session.SessionListener;
import java.util.Map;

public interface YouTrackDBConfigBuilder {

  YouTrackDBConfigBuilder fromGlobalConfigurationParameters(
      Map<GlobalConfiguration, Object> values);

  YouTrackDBConfigBuilder fromMap(Map<String, Object> values);

  YouTrackDBConfigBuilder addSessionListener(SessionListener listener);

  YouTrackDBConfigBuilder addAttribute(final ATTRIBUTES attribute, final Object value);

  YouTrackDBConfigBuilder addGlobalConfigurationParameter(GlobalConfiguration configuration,
      Object value);

  YouTrackDBConfig build();
}
