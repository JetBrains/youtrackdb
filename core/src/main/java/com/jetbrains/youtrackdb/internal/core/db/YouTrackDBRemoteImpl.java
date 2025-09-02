package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.api.remote.RemoteYouTrackDB;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResult;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;

public class YouTrackDBRemoteImpl extends
    YouTrackDBAbstract<RemoteResult, RemoteDatabaseSession> implements
    RemoteYouTrackDB {

  private static final Pattern URL_PATTERN = Pattern.compile("[,;]");

  public YouTrackDBRemoteImpl(YouTrackDBInternal<RemoteDatabaseSession> internal) {
    super(internal);
  }

  /// Create a new YouTrackDB manager instance for a remote deployment with default configuration.
  ///
  /// @param url            the url for the database server for example "localhost" or
  ///                       "localhost:2424"
  /// @param serverUser     the server user allowed to manipulate databases.
  /// @param serverPassword relative to the server user.
  /// @return a new YouTrackDB instance
  public static RemoteYouTrackDB remote(@Nonnull String url, @Nonnull String serverUser,
      @Nonnull String serverPassword) {
    return remote(url, serverUser, serverPassword, new BaseConfiguration());
  }

  /// Create a new YouTrackDB manager instance for a remote deployment with custom configuration.
  ///
  /// @param url            the url for the database server for example "localhost" or
  ///                       "localhost:2424"
  /// @param serverUser     the server user allowed to manipulate databases.
  /// @param serverPassword relative to the server user.
  /// @param config         custom configuration for current environment
  /// @return a new YouTrackDB instance
  public static RemoteYouTrackDB remote(
      @Nonnull String url, @Nonnull String serverUser, @Nonnull String serverPassword,
      @Nonnull Configuration config) {
    var builder = YouTrackDBConfig.builder().fromApacheConfiguration(config);
    var youTrackDB =
        new YouTrackDBRemoteImpl(
            YouTrackDBInternal.remote(URL_PATTERN.split(url.substring(url.indexOf(':') + 1)),
                (YouTrackDBConfigImpl) builder.build()));

    youTrackDB.serverUser = serverUser;
    youTrackDB.serverPassword = serverPassword;

    return youTrackDB;
  }
}
