package com.jetbrains.youtrackdb.api;

import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;

public final class YourTracks {

  private YourTracks() {
  }

  /// Create a new YouTrackDB manager instance for an embedded deployment with the default
  /// configuration.
  ///
  /// A created instance is cached and reused if the method is called for the second time unless
  /// it is closed by the user.
  ///
  /// @param directoryPath the directory where the databases are stored. For in memory database
  ///                      use "."
  public static YouTrackDB instance(@Nonnull String directoryPath) {
    return instance(directoryPath, new BaseConfiguration());
  }

  /// Create a new YouTrackDB manager instance for an embedded deployment with the default
  /// configuration.
  ///
  /// A created instance is cached and reused if the method is called for the second time unless
  /// it is closed by the user.
  ///
  /// @param directoryPath the directory where the databases are stored. For in memory database
  ///                      use "."
  public static YouTrackDB instance(@Nonnull Path directoryPath) {
    return instance(directoryPath.toAbsolutePath().normalize().toString(), new BaseConfiguration());
  }

  /// Create a new YouTrackDB manager instance for an embedded deployment with custom
  /// configuration.
  ///
  /// A created instance is cached and reused if the method is called for the second time unless
  /// it is closed by the user.
  ///
  /// @param directoryPath the directory where the databases are stored. For in memory database
  ///                      use "."
  /// @param configuration custom configuration for current environment
  public static YouTrackDB instance(@Nonnull String directoryPath,
      @Nonnull Configuration configuration) {
    return YTDBGraphFactory.ytdbInstance(directoryPath, configuration);
  }

  /// Create a new YouTrackDB manager instance for an embedded deployment with custom
  /// configuration.
  ///
  /// A created instance is cached and reused if the method is called for the second time unless
  /// it is closed by the user.
  ///
  /// @param directoryPath the directory where the databases are stored. For in memory database
  ///                      use "."
  /// @param configuration custom configuration for current environment
  public static YouTrackDB instance(@Nonnull Path directoryPath,
      @Nonnull Configuration configuration) {
    return YTDBGraphFactory.ytdbInstance(directoryPath.toAbsolutePath().normalize().toString(),
        configuration);
  }

  /// Creates a new YouTrackDB manager instance for a case when YTDB database is managed by Gremlin
  /// Server.
  ///
  /// As username and password are not passed during establishing the connection,
  /// the method [YouTrackDB#openTraversal(String, String, String)]
  /// should be used to send Gremlin queries to the server.
  ///
  /// @param serverAddress server address
  /// @param serverPort    server port
  ///
  /// @see YouTrackDB#createSystemUser(String, String, String...)
  /// @see YouTrackDB#createSystemUser(String, String, PredefinedRole...)

  public static YouTrackDB instance(@Nonnull String serverAddress, int serverPort) {
    try {
      var cls = YourTracks.class.getClassLoader()
          .loadClass("com.jetbrains.youtrackdb.internal.driver.YouTrackDBRemote");
      var method = cls.getMethod("instance", String.class, int.class);
      return (YouTrackDB) method.invoke(null, serverAddress, serverPort);
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
  /// Server.
  ///
  /// The name of passed in user should belong to the user that is either registered in the server
  /// configuration or system database.
  ///
  /// Integration with SSO and user directories is going to be added soon.
  ///
  /// As username and password are already passed during establishing the connection,
  /// the method [YouTrackDB#openTraversal(String)]
  /// is recommended to be used to send Gremlin queries to the server.
  ///
  /// @param serverAddress server address
  /// @param serverPort    server port
  /// @param username      user name
  /// @param password      user password
  ///
  /// @see YouTrackDB#createSystemUser(String, String, String...)
  /// @see YouTrackDB#createSystemUser(String, String, PredefinedRole...)
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
  /// Server. The name of passed in user should belong to the user either registered in the
  /// server configuration or system database.
  ///
  /// Server connection will use port `8182` by default.
  ///
  /// Integration with SSO and user directories is going to be added soon.
  ///
  /// As username and password are already passed during establishing the connection,
  /// the method [YouTrackDB#openTraversal(String)]
  /// is recommended to be used to send Gremlin queries to the server.
  ///
  /// @param serverAddress server address
  /// @param username      user name
  /// @param password      user password
  ///
  /// @see YouTrackDB#createSystemUser(String, String, String...)
  /// @see YouTrackDB#createSystemUser(String, String, PredefinedRole...)
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
