package com.jetbrains.youtrackdb.api;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
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

  /// Create a new YouTrackDB manager instance for an embedded deployment with the default
  /// configuration. Created instance is registered in
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] and reused if already exists with
  /// the configuration that was used during its creation.
  ///
  /// The given instance will be unregistered when [YouTrackDB#close()] method is called.
  ///
  /// @param directoryPath the directory where the databases are stored. For in memory database use
  ///                      "."
  public static YouTrackDB instance(@Nonnull Path directoryPath) {
    return instance(directoryPath.toAbsolutePath().normalize().toString(), new BaseConfiguration());
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

  /// Create a new YouTrackDB manager instance for an embedded deployment with custom configuration.
  /// Created instance is registered in [org.apache.tinkerpop.gremlin.structure.util.GraphFactory]
  /// and reused if already exists with the configuration that was used during its creation.
  ///
  /// The given instance will be unregistered when [YouTrackDB#close()] method is called.
  ///
  /// @param directoryPath the directory where the databases are stored. For in memory database use
  ///                      "."
  /// @param configuration custom configuration for current environment
  public static YouTrackDB instance(@Nonnull Path directoryPath,
      @Nonnull Configuration configuration) {
    return YTDBGraphFactory.ytdbInstance(directoryPath.toAbsolutePath().normalize().toString(),
        configuration);
  }


  /// Creates a new YouTrackDB manager instance for a case when YTDB database is managed by Gremlin
  /// Server.
  ///
  /// The name of passed in user should belong to the user that is either registered in the server
  /// configuration or system database.
  ///
  /// Integration with SSO and user directories is going to be added soon.
  ///
  /// @param serverAddress server address
  /// @param serverPort    server port
  /// @param username      user name
  /// @param password      user password
  public static YouTrackDB instance(@Nonnull String serverAddress, int serverPort,
      @Nonnull String username,
      @Nonnull String password) {
    try {
      var cls = YourTracks.class.getClassLoader()
          .loadClass("com.jetbrains.youtrackdb.internal.driver.YouTrackDBRemote");
      var method = cls.getMethod("instance", String.class, int.class, String.class, String.class);
      return (YouTrackDB) method.invoke(null, serverAddress, serverPort, username, password);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("YTDB remote driver is not registered in class path.", e);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Invalid YTDB remote driver method signature.", e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException("Error during YTDB remote driver invocation.", e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /// Creates a new YouTrackDB manager instance for a case when YTDB database is managed by Gremlin
  /// Server. The name of passed in user should belong to the user that is either registered in the
  /// server configuration or system database.
  ///
  /// Server connection will use port `8182` by default.
  ///
  /// Integration with SSO and user directories is going to be added soon.
  ///
  /// @param serverAddress server address
  /// @param username      user name
  /// @param password      user password
  public static YouTrackDB instance(@Nonnull String serverAddress, @Nonnull String username,
      @Nonnull String password) {
    try {
      var cls = YourTracks.class.getClassLoader()
          .loadClass("com.jetbrains.youtrackdb.internal.driver.YouTrackDBRemote");
      var method = cls.getMethod("instance", String.class, String.class, String.class);
      return (YouTrackDB) method.invoke(null, serverAddress, username, password);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("YTDB remote driver is not registered in class path.", e);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Invalid YTDB remote driver method signature.", e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException("Error during YTDB remote driver invocation.", e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
