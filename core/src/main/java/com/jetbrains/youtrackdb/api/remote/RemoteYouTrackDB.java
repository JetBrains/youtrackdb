package com.jetbrains.youtrackdb.api.remote;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;

public interface RemoteYouTrackDB extends AutoCloseable {

  /// Creates a new database alongside users, passwords and roles.
  ///
  /// If you want to create users during creation of a database you should provide array that
  /// consists of triple strings. Each triple string should contain the username, password and
  /// role.
  ///
  /// For example:
  ///
  /// `youTrackDB.create("test", DatabaseType.DISK, "user1", "password1", "admin","user2",
  /// "password2", "reader");`
  ///
  /// The predefined roles are:
  ///
  ///   - admin: has all privileges on the database
  ///   - reader: can read the data but cannot modify it
  ///   - writer: can read and modify the data but cannot create or delete classes
  ///
  /// @param databaseName    database name
  /// @param type            can be disk or memory
  /// @param userCredentials usernames, passwords and roles provided as a sequence of triple
  ///                        strings
  void create(@Nonnull String databaseName, @Nonnull DatabaseType type, String... userCredentials);

  /// Creates a new database alongside users, passwords and roles and also allows to specify
  /// database configuration.
  ///
  /// If you want to create users during creation of a database you should provide an array that
  /// consists of triple strings. Each triple string should contain the username, password and
  /// role.
  ///
  /// For example:
  ///
  /// `youTrackDB.create("test", DatabaseType.DISK, "user1", "password1", "admin","user2",
  /// "password2", "reader");`
  ///
  /// The predefined roles are:
  ///
  ///   - admin: has all privileges on the database
  ///   - reader: can read the data but cannot modify it
  ///   - writer: can read and modify the data but cannot create or delete classes
  ///
  /// @param databaseName     database name
  /// @param type             can be disk or memory
  /// @param youTrackDBConfig database configuration
  /// @param userCredentials  usernames, passwords and roles provided as a sequence of triple
  ///                         strings
  void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration youTrackDBConfig, String... userCredentials);

  /// Creates a new database alongside users, passwords and roles if such a one does not exist yet.
  ///
  /// If you want to create users during creation of database you should provide array that consists
  /// of triple strings. Each triple string should contain the username, password and role.
  ///
  /// The predefined roles are:
  ///
  ///   - admin: has all privileges on the database
  ///   - reader: can read the data but cannot modify it
  ///   - writer: can read and modify the data but cannot create or delete classes
  ///
  ///
  /// For example:
  ///
  /// `youTrackDB.createIfNotExists("test", DatabaseType.DISK, "user1", "password1","admin",
  /// "user2", "password2", "reader");`
  ///
  /// @param databaseName    database name
  /// @param type            can be disk or memory
  /// @param userCredentials usernames, passwords and roles provided as a sequence of triple
  ///                        strings
  void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      String... userCredentials);

  /// Creates a new database alongside users, passwords and roles if such a one does not exist yet
  /// and also allows to specify database configuration.
  ///
  /// If you want to create users during the creation of a database, you should provide an array
  /// that consists of triple strings. Each triple string should contain the username, password and
  /// role.
  ///
  /// The predefined roles are:
  ///
  ///   - admin: has all privileges on the database
  ///   - reader: can read the data but cannot modify it
  ///   - writer: can read and modify the data but cannot create or delete classes
  ///
  ///
  /// For example:
  ///
  /// `youTrackDB.createIfNotExists("test", DatabaseType.DISK, "user1", "password1","admin",
  /// "user2", "password2", "reader");`
  ///
  /// @param databaseName    database name
  /// @param type            can be disk or memory
  /// @param config          database configuration
  /// @param userCredentials usernames, passwords and roles provided as a sequence of triple
  ///                        strings
  void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration config, String... userCredentials);

  /// Drop a database
  ///
  /// @param databaseName database name
  void drop(@Nonnull String databaseName);

  /// Check if a database exists
  ///
  /// @param databaseName database name to check
  /// @return boolean true if exist false otherwise.
  boolean exists(@Nonnull String databaseName);

  /// List exiting databases in the current environment
  ///
  /// @return a list of existing databases.
  @Nonnull
  List<String> listDatabases();

  /// Close the current YouTrackDB database manager with all related databases and pools.
  @Override
  void close();

  /// Check if the current YouTrackDB database manager is open
  ///
  /// @return boolean true if is open false otherwise.
  boolean isOpen();

  /// Open the YTDB Graph instance by database name, using the current username and password.
  ///
  /// @param databaseName Database name
  /// @param userName     user name
  @Nonnull
  YTDBGraph openGraph(@Nonnull String databaseName, @Nonnull String userName,
      @Nonnull String userPassword);

  /// Open the YTDB Graph instance by database name, using the current username and password. This
  /// method allows one to specify database configuration.
  ///
  /// @param databaseName Database name
  /// @param userName     user name
  /// @param config       database configuration
  @Nonnull
  YTDBGraph openGraph(@Nonnull String databaseName, @Nonnull String userName,
      @Nonnull String userPassword, @Nonnull Configuration config);

  /// Creates a database by restoring it from incremental backup. The backup should be created with
  /// [#incrementalBackup(Path)].
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// @param databaseName Name of a database to be created.
  /// @param userName     Name of server user, not needed for local databases.
  /// @param userPassword User password, not needed for local databases.
  /// @param path         Path to the backup directory.
  /// @param config       YouTrackDB configuration.
  void restore(@Nonnull String databaseName, @Nonnull String userName, @Nonnull String userPassword,
      @Nonnull String path, @Nullable Configuration config);
}
