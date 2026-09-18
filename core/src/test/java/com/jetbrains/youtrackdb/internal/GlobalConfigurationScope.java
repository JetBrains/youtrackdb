package com.jetbrains.youtrackdb.internal;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;

/** Restores both the effective value and explicit state of a global configuration entry. */
public final class GlobalConfigurationScope implements AutoCloseable {

  private final GlobalConfiguration configuration;
  private final Object previousValue;
  private final boolean previouslyChanged;

  private GlobalConfigurationScope(GlobalConfiguration configuration) {
    this.configuration = configuration;
    previousValue = configuration.getValue();
    previouslyChanged = configuration.isChanged();
  }

  public static GlobalConfigurationScope capture(GlobalConfiguration configuration) {
    return new GlobalConfigurationScope(configuration);
  }

  public static GlobalConfigurationScope set(
      GlobalConfiguration configuration, Object value) {
    var scope = capture(configuration);
    configuration.setValue(value);
    return scope;
  }

  @Override
  public void close() {
    if (previouslyChanged) {
      configuration.setValue(previousValue);
    } else {
      configuration.resetToDefault();
    }
  }
}
