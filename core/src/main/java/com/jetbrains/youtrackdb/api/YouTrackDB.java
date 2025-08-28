package com.jetbrains.youtrackdb.api;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;

/// YouTrackDB management environment, it allows to connect to an environment and manipulate
/// databases or open graph instances.
///
/// Usage examples: Remote Example:
/// <pre>
/// <code>
/// try(var youTrackDB = YourTracks.remote("localhost","root","root") {
///  youTrackDB.createIfNotExists("test",DatabaseType.DISK, "superuser", "password", "admin",
///  "writer" , "password2", "writer");
///  try(var graph = youTrackDB.openGraph("test","superuser","password")) {
///     graph.addVertex("MyClass");
///   }
///  try(var graph = youTrackDB.openGraph("test","writer","password2")) {
///     //...
///  }
/// }
/// </code>
/// </pre>
///
/// Embedded example:
/// <pre>
/// <code>
/// try(var youTrackDB = YourTracks.embedded("./databases/")) {
///  youTrackDB.createIfNotExists("test",DatabaseType.DISK, "superuser", "password", "admin",
///  "writer" , "password2", "writer");
///   try(var graph = youTrackDB.openGraph("test","superuser","password")) {
///     graph.addVertex("MyClass");
///   }
///
///   try(var graph = youTrackDB.openGraph("test","writer","password2")) {
///     //...
///   }
/// }
/// </code>
/// </pre>
///
/// Database Manipulation Example:
/// <pre>
/// <code>
/// try(var youTrackDB = ...) {
///  if(!youTrackDB.exists("one")) {
///     youTrackDB.create("one",DatabaseType.DISK, "superuser", "password", "admin", "writer,
///     "password2", "writer");
///  }
///  if(youTrackDB.exists("two")) {
///    youTrackDB.drop("two");
///  }
///  List<tString> databases = youTrackDB.list();
///  assertEquals(databases.size(),1);
///  assertEquals(databases.get(0),"one");
/// }
/// </code>
/// </pre>
public interface YouTrackDB extends AutoCloseable {

  /// Configuration parameters passed during creation of the database or during opening of the graph
  /// instance using methods of [YouTrackDB] and
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] classes. Even though work using
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] is supported, we do recommend using
  /// [YouTrackDB] methods to open graph instances. Except parameters listed in this interface, you
  /// can also specify any other parameter listed in
  /// [com.jetbrains.youtrackdb.api.config.GlobalConfiguration] class. If the parameter listed in
  /// [com.jetbrains.youtrackdb.api.config.GlobalConfiguration] is not specified directly, then a
  /// database will use the default value from of parameter indicated in
  /// [com.jetbrains.youtrackdb.api.config.GlobalConfiguration].
  interface ConfigurationParameters {

    /// Path to the root folder that contains all embedded databases managed by [YouTrackDB], this
    /// parameter is used only in [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open
    /// the [YTDBGraph] instance.
    String CONFIG_DB_PATH = "youtrackdb.embedded.path";
    /// Name of the embedded database to open, this parameter is used only in
    /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open the [YTDBGraph]
    /// instance.
    String CONFIG_DB_NAME = "youtrackdb.database.name";
    /// Type of the embedded database to open, this parameter is used only in
    /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open the [YTDBGraph] instance.
    /// Allowed values listed in [DatabaseType] enum.
    String CONFIG_DB_TYPE = "youtrackdb.database.type";
    /// Current username, this parameter is used only in
    /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open the [YTDBGraph]
    /// instance.
    String CONFIG_USER_NAME = "youtrackdb.user.name";
    /// User role that will be created during database creation, this parameter is used only in
    /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open the [YTDBGraph] instance.
    /// Database is created if it does not exist yet.
    String CONFIG_USER_ROLE = "youtrackdb.user.role";
    /// Current user password, this parameter is used only in
    /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open the [YTDBGraph]
    /// instance.
    String CONFIG_USER_PWD = "youtrackdb.user.pwd";
    /// This parameter indicates if a database should be created during the opening of [YTDBGraph]
    /// instance. This parameter is used only in
    /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open the [YTDBGraph]
    /// instance.
    String CONFIG_CREATE_IF_NOT_EXISTS = "youtrackdb.database.createIfNotExists";

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

  /// Creates a new database alongside users, passwords and roles.
  ///
  /// If you want to create users during creation of a database, you should provide array that
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

  /// Creates a database by restoring it from backup. The backup should be created with
  /// [YTDBGraph#backup(Path)].
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// @param databaseName Name of a database to be created.
  /// @param path         Path to the backup directory.
  void restore(@Nonnull String databaseName, @Nonnull String path);

  /// Creates a database by restoring it from backup. The backup should be created with
  /// [YTDBGraph#backup(Path)].
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// @param databaseName Name of a database to be created.
  /// @param path         Path to the backup directory.
  /// @param config       database configuration
  void restore(@Nonnull String databaseName, @Nonnull String path, @Nonnull Configuration config);

  /// Creates a database by restoring it from backup. The backup should be created with
  /// [YTDBGraph#backup(Path)].
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

  /// Creates the database by restoring it from backup. The backup should be created with
  /// [YTDBGraph#backup(Supplier, Function, Function, Consumer)] or [YTDBGraph#backup(Path)]
  /// methods.
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// This method can be used to restore a database from the backup located on abstract backup
  /// storage, on a remote server, for example.
  ///
  /// It does not operate by concepts of a local file system instead it accepts lambda instances to
  /// operate by backup files.
  ///
  /// @param databaseName                   Name of a database to be created.
  /// @param ibuFilesSupplier       Lambda that will provide a list of the backup files used to
  ///                               restore the database.
  /// @param ibuInputStreamSupplier lambda that will provide an input stream for each backup file.
  /// @param expectedUUID           UUID of the database to be restored. If null, the database will
  ///                               be restored only if the directory contains backup only from one
  ///                               database.
  /// @param config                 database configuration
  void restore(@Nonnull String databaseName,
      @Nonnull final Supplier<Iterator<String>> ibuFilesSupplier,
      @Nonnull Function<String, InputStream> ibuInputStreamSupplier, @Nullable String expectedUUID,
      @Nonnull Configuration config);
}
