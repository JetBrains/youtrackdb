package com.jetbrains.youtrackdb.api;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;

public abstract class YourTracks {

  /// Create a new YouTrackDB manager instance for an embedded deployment with the default
  /// configuration. Created instance is registered in
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] and reused if already exists with
  /// the configuration that was used during its creation.
  ///
  /// The given instance will be unregistered when [YouTrackDB#close()] method is called.
  ///
  /// @param directoryPath the directory where the databases are stored. For in memory database use
  ///                      "."
  public static YouTrackDB instance(@Nonnull String directoryPath) {
    return instance(directoryPath, new BaseConfiguration());
  }

  /// Create a new YouTrackDB manager instance for an embedded deployment with custom configuration.
  /// Created instance is registered in [org.apache.tinkerpop.gremlin.structure.util.GraphFactory]
  /// and reused if already exists with the configuration that was used during its creation.
  ///
  /// The given instance will be unregistered when [YouTrackDB#close()] method is called.
  ///
  /// @param directoryPath the directory where the databases are stored. For in memory database use
  ///                      "."
  /// @param configuration custom configuration for current environment
  public static YouTrackDB instance(@Nonnull String directoryPath,
      @Nonnull Configuration configuration) {
    return YTDBGraphFactory.ytdbInstance(directoryPath, configuration);
  }
}
