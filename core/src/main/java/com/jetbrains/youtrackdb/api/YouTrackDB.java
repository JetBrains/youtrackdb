package com.jetbrains.youtrackdb.api;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.jspecify.annotations.NonNull;

/// YouTrackDB management environment, it allows connecting to an environment and manipulate
/// databases or open [com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource] instances.
///
/// Usage examples: Remote Example:
/// <pre>
/// <code>
/// try(var youTrackDB = YourTracks.instance("localhost","root","root") {
///  youTrackDB.createIfNotExists("test",DatabaseType.DISK, "superuser", "password", "admin",
///  "writer" , "password2", "writer");
///  try(var g = youTrackDB.openTraversal("test","superuser","password")) {
///     graph.addV("MyClass").iterate();
///   }
/// }
/// </code>
/// </pre>
///
/// Embedded example:
/// <pre>
/// <code>
/// try(var youTrackDB = YourTracks.instance("./databases/")) {
///  youTrackDB.createIfNotExists("test",DatabaseType.DISK, "superuser", "password", "admin",
///  "writer" , "password2", "writer");
///   try(var g = youTrackDB.openTraversal("test","superuser","password")) {
///     graph.addV("MyClass").iterate();
///   }
/// }
/// </code>
/// </pre>
///
/// Database Manipulation Example:
/// <pre>
/// <code>
/// try(var youTrackDB = ...) {
///  if(!youTrackDB.exists("dbOne")) {
///     youTrackDB.create("dbOne",DatabaseType.DISK, "superuser", "password", "admin", "writer,
///     "password2", "writer");
///  }
///  if(youTrackDB.exists("dbTwo")) {
///    youTrackDB.drop("dbTwo");
///  }
///  List<tString> databases = youTrackDB.list();
///  assertEquals(databases.size(),1);
///  assertEquals(databases.get(0),"dbOne");
/// }
/// </code>
/// </pre>
public interface YouTrackDB extends AutoCloseable {

  /// Configuration parameters passed during creation of the database or during opening of the graph
  /// traversal instance using methods of [YouTrackDB]. Except parameters listed in this interface,
  /// you can also specify any other parameter listed in
  /// [com.jetbrains.youtrackdb.api.config.GlobalConfiguration] class. If the parameter listed in
  /// [com.jetbrains.youtrackdb.api.config.GlobalConfiguration] is not specified directly, then a
  /// database will use the default value from of parameter indicated in
  /// [com.jetbrains.youtrackdb.api.config.GlobalConfiguration].
  interface DatabaseConfigurationParameters {

    /// Default date format for the database. This parameter is used during data serialization and
    /// query processing.
    String CONFIG_DB_DATE_FORMAT = "youtrackdb.database.dateFormat";
    /// Default date-time format for the database. This parameter is used during data serialization
    /// and query processing.
    String CONFIG_DB_DATE_TIME_FORMAT = "youtrackdb.database.dateTimeFormat";
    /// Default time zone for the database. This parameter is used for query processing.
    String CONFIG_DB_TIME_ZONE = "youtrackdb.database.timeZone";
    /// Default locale country for the database. This parameter is used during data serialization,
    /// query processing and index manipulation.
    String CONFIG_DB_LOCALE_COUNTRY = "youtrackdb.database.locale.country";
    /// Default locale language for the database. This parameter is used during data serialization,
    /// query processing and index manipulation.
    String CONFIG_DB_LOCALE_LANGUAGE = "youtrackdb.database.locale.language";
    /// Default charset for the database. This parameter is used during data serialization, query
    /// processing and index manipulation.
    String CONFIG_DB_CHARSET = "youtrackdb.database.charset";
  }

  /// List of predefined roles for users registered on the database level, not the server level (not
  /// system users)
  enum PredefinedLocalRole {
    ADMIN, READER, WRITER
  }

  /// List of predefined roles for system users registered on the server level
  enum PredefinedSystemRole {
    /// Everything is allowed for user with this role
    ROOT,
    /// User can only list databases and test DB exists
    GUEST
  }

  /// Credential for a local user on the database level, not the server level.
  record LocalUserCredential(String username, String password, PredefinedLocalRole role) {

  }

  /// Creates a new database alongside users, passwords and roles.
  ///
  /// If you want to create users during the creation of a database, you should provide array that
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
  void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      String... userCredentials);

  /// Creates a new database alongside users, passwords and roles. For example:
  ///
  /// @param databaseName         database name
  /// @param type                 can be disk or memory
  /// @param localUserCredentials List of users and their credentials
  default void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      LocalUserCredential... localUserCredentials) {
    var userCredentialsArray = createUserCredentialsArray(localUserCredentials);
    create(databaseName, type, userCredentialsArray);
  }

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
  default void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration youTrackDBConfig, String... userCredentials) {
  }

  /// Creates a new database alongside users, passwords and roles and also allows to specify
  /// database configuration.
  ///
  /// @param databaseName         database name
  /// @param type                 can be disk or memory
  /// @param youTrackDBConfig     database configuration
  /// @param localUserCredentials List of users and their credentials
  default void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration youTrackDBConfig, LocalUserCredential... localUserCredentials) {
    var userCredentialsArray = createUserCredentialsArray(localUserCredentials);
    create(databaseName, type, youTrackDBConfig, userCredentialsArray);
  }

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

  /// Creates a new database alongside users, passwords and roles if such a one does not exist yet.
  ///
  /// @param databaseName         database name
  /// @param type                 can be disk or memory
  /// @param localUserCredentials List of users and their credentials
  default void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      LocalUserCredential... localUserCredentials) {
    var userCredentialsArray = createUserCredentialsArray(localUserCredentials);
    createIfNotExists(databaseName, type, userCredentialsArray);
  }

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

  /// Creates a new database alongside users, passwords and roles if such a one does not exist yet
  /// and also allows to specify database configuration.
  ///
  /// @param databaseName         database name
  /// @param type                 can be disk or memory
  /// @param config               database configuration
  /// @param localUserCredentials List of users and their credentials
  default void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration config, LocalUserCredential... localUserCredentials) {
    var userCredentialsArray = createUserCredentialsArray(localUserCredentials);
    createIfNotExists(databaseName, type, config, userCredentialsArray);
  }

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

  /// Opens [YTDBGraphTraversalSource] instance for the given embedded database by database name,
  /// using provided the username and password.
  ///
  /// Please keep a single instance of this traversal source per application.
  ///
  /// @param databaseName Database name
  /// @param userName     the username. For remote database this parameter is ignored, and the
  ///                     username provided during connection is used.
  /// @param userPassword user password. For remote database this parameter is ignored.
  @Nonnull
  YTDBGraphTraversalSource openTraversal(@Nonnull String databaseName, @Nonnull String userName,
      @Nonnull String userPassword);

  /// Opens [YTDBGraphTraversalSource] instance for the given embedded database by database name,
  /// using the username and password provided during connection to the server.
  ///
  /// This method can be used only for remote databases.
  ///
  /// Please keep a single instance of this traversal source per application.
  ///
  /// @param databaseName Database name
  @Nonnull
  YTDBGraphTraversalSource openTraversal(@Nonnull String databaseName);

  /// Creates a database by restoring it from backup. The backup should be created with
  /// [YTDBGraphTraversalSource#backup(Path)].
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// @param databaseName Name of a database to be created.
  /// @param path         Path to the backup directory.
  void restore(@Nonnull String databaseName, @Nonnull String path);

  /// Creates a database by restoring it from backup. The backup should be created with
  /// [YTDBGraphTraversalSource#backup(Path)].
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// @param databaseName Name of a database to be created.
  /// @param path         Path to the backup directory.
  /// @param config       YouTrackDB configuration.
  void restore(@Nonnull String databaseName, @Nonnull String path, @Nullable Configuration config);

  /// Creates user on that can be used to authenticate for all databases.
  ///
  /// This functionality works for both remote and embedded deployments. In the case of the remote
  /// deployments system users can be used to connect to the server.
  ///
  /// User with rights to mangage databases on server (create, drop, list) can be created using this
  /// method passing `root` as a user role.
  ///
  /// @param username User name
  /// @param password User password
  /// @param role     List of user roles
  void createSystemUser(@Nonnull String username, @Nonnull String password,
      @Nonnull String... role);

  /// Creates user on that can be used to authenticate for all databases.
  ///
  /// This functionality works for both remote and embedded deployments. In the case of the remote
  /// deployments system users can be used to connect to the server.
  ///
  /// @param username User name
  /// @param password User password
  /// @param role     List of user roles
  default void createSystemUser(@Nonnull String username, @Nonnull String password,
      @Nonnull PredefinedSystemRole... role) {
    var roles = new String[role.length];
    for (var i = 0; i < role.length; i++) {
      roles[i] = role[i].name().toLowerCase();
    }
    createSystemUser(username, password, roles);
  }

  /// List names of the users registered inside the system databases. Those users can be used to
  /// authenticate for all databases.
  ///
  /// This functionality works for both remote and embedded deployments. In the case of the remote
  /// deployments system users can be used to connect to the server and log in into any database.
  ///
  /// @return List of usernames
  /// @see YouTrackDB#createSystemUser(String, String, String...)
  List<String> listSystemUsers();

  /// Removes user from the system database.
  ///
  /// @param username User name
  /// @see YouTrackDB#createSystemUser(String, String, String...)
  void dropSystemUser(@Nonnull String username);

  private static String @NonNull [] createUserCredentialsArray(
      LocalUserCredential[] localUserCredentials) {
    var userCredentialsArray = new String[localUserCredentials.length * 3];

    for (var i = 0; i < localUserCredentials.length; i++) {
      userCredentialsArray[i * 3] = localUserCredentials[i].username();
      userCredentialsArray[i * 3 + 1] = localUserCredentials[i].password();
      userCredentialsArray[i * 3 + 2] = localUserCredentials[i].role().name();
    }

    return userCredentialsArray;
  }

  /// Creates a database by restoring it from backup. The backup should be created with
  /// [YTDBGraphTraversalSource#backup(Path)].
  ///
  /// At the moment only disk-based databases are supported, you can not restore memory databases.
  ///
  /// @param databaseName Name of a database to be created.
  /// @param path         Path to the backup directory.
  /// @param expectedUUID UUID of the database to be restored. If null, the database will be
  ///                     restored only if the directory contains backup only from one database.
  /// @param config       database configuration
  void restore(@Nonnull String databaseName, @Nonnull String path, @Nullable String expectedUUID,
      @Nonnull Configuration config);
}
