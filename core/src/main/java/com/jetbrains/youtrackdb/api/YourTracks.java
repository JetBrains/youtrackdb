package com.jetbrains.youtrackdb.api;


import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.remote.RemoteYouTrackDB;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import java.util.regex.Pattern;

public abstract class YourTracks {
  private static final Pattern URL_PATTERN = Pattern.compile("[,;]");

  /**
   * Create a new YouTrackDB manager instance for an embedded deployment with default configuration.
   * For in memory database use any directory name, for example "mydb"
   *
   * @param directoryPath the directory where the database are stored
   */
  public static YouTrackDB embedded(String directoryPath) {
    return embedded(directoryPath, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Create a new YouTrackDB manager instance for a embedded deployment with custom configuration.
   * For in memory database use any directory name, for example "mydb"
   *
   * @param directoryPath the directory where the database are stored
   * @param config        custom configuration for current environment
   */
  public static YouTrackDB embedded(String directoryPath, YouTrackDBConfig config) {
    return new YouTrackDBImpl(YouTrackDBInternal.embedded(directoryPath, config, false));
  }

  /**
   * Create a new YouTrackDB manager instance for a remote deployment with default configuration.
   *
   * @param url            the url for the database server for example "localhost" or
   *                       "localhost:2424"
   * @param serverUser     the server user allowed to manipulate databases.
   * @param serverPassword relative to the server user.
   * @return a new YouTrackDB instance
   */
  public static RemoteYouTrackDB remote(String url, String serverUser, String serverPassword) {
    return remote(url, serverUser, serverPassword, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Create a new YouTrackDB manager instance for a remote deployment with custom configuration.
   *
   * @param url            the url for the database server for example "localhost" or
   *                       "localhost:2424"
   * @param serverUser     the server user allowed to manipulate databases.
   * @param serverPassword relative to the server user.
   * @param config         custom configuration for current environment
   * @return a new YouTrackDB instance
   */
  public static RemoteYouTrackDB remote(
      String url, String serverUser, String serverPassword, YouTrackDBConfig config) {
    var youTrackDB =
        new YouTrackDBRemoteImpl(
            YouTrackDBInternal.remote(URL_PATTERN.split(url.substring(url.indexOf(':') + 1)),
                (YouTrackDBConfigImpl) config));

    youTrackDB.serverUser = serverUser;
    youTrackDB.serverPassword = serverPassword;

    return youTrackDB;
  }
}
