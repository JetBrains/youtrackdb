package com.jetbrains.youtrack.db.api.common;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.common.query.BasicResultSet;
import com.jetbrains.youtrack.db.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * YouTrackDB management environment, it allows to connect to an environment and manipulate
 * databases or open sessions.
 *
 * <p>Usage examples: Remote Example:
 *
 * <pre>
 * <code>
 * try(var youTrackDB = YourTracks.remote("localhost","root","root") {
 *  youTrackDB.createIfNotExists("test",DatabaseType.DISK, "superuser", "password", "admin",
 *  "writer" , "password2", "writer");
 *  try(var session = youTrackDB.open("test","superuser","password")) {
 *     session.createClass("MyClass");
 *   }
 *  try(var session = youTrackDB.open("test","writer","password2")) {
 *     //...
 *  }
 * }
 * </code>
 * </pre>
 * <p>
 * Embedded example:
 *
 * <pre>
 * <code>
 * try(YouTrackDB youTrackDB = YourTracks.embedded("./databases/")) {
 *  youTrackDB.createIfNotExists("test",DatabaseType.DISK, "superuser", "password", "admin",
 *  "writer" , "password2", "writer");
 *   try(var session = youTrackDB.open("test","superuser","password")) {
 *     session.createClass("MyClass");
 *   }
 *
 *   try(var session = youTrackDB.open("test","writer","password2")) {
 *     //...
 *   }
 * }
 * </code>
 * </pre>
 * <p>
 * Database Manipulation Example:
 *
 * <pre>
 * <code>
 * tru(YouTrackDB youTrackDB = ...) {
 *  if(!youTrackDB.exists("one")) {
 *     youTrackDB.create("one",DatabaseType.DISK, "superuser", "password", "admin", "writer,
 *     "password2", "writer");
 *  }
 *  if(youTrackDB.exists("two")) {
 *    youTrackDB.drop("two");
 *  }
 *  List<tString> databases = youTrackDB.list();
 *  assertEquals(databases.size(),1);
 *  assertEquals(databases.get("0"),"one");
 * }
 * </code>
 * </pre>
 */
public interface BasicYouTrackDB<R extends BasicResult, S extends BasicDatabaseSession<R, ?>> extends
    AutoCloseable {
  /**
   * Open a database
   *
   * @param database the database to open
   * @param user     username of a database user or a server user allowed to open the database
   * @param password related to the specified username
   * @return the opened database
   */
  S open(String database, String user, String password);

  /**
   * Open a database
   *
   * @param database the database to open
   * @param user     username of a database user or a server user allowed to open the database
   * @param password related to the specified username
   * @param config   custom configuration for current database
   * @return the opened database
   */
  S open(
      String database, String user, String password, YouTrackDBConfig config);

  /**
   * Create a new database without users. In case if you want to create users during creation please
   * use {@link #create(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @see #create(String, DatabaseType, String...)
   */
  void create(String database, DatabaseType type);

  /**
   * Creates a new database alongside with users, passwords and roles.
   *
   * <p>If you want to create users during creation of database you should provide array that
   * consist of triple strings. Each triple string should contain user name, password and role.
   *
   * <p>For example:
   *
   * <p>{@code youTrackDB.create("test", DatabaseType.DISK, "user1", "password1", "admin",
   * "user2", "password2", "reader"); }
   *
   * <p>The predefined roles are:
   *
   * <ul>
   *   <li>admin: has all privileges on the database
   *   <li>reader: can read the data but cannot modify it
   *   <li>writer: can read and modify the data but cannot create or delete classes
   * </ul>
   *
   * @param database        database name
   * @param type            can be disk or memory
   * @param userCredentials user names, passwords and roles provided as a sequence of triple
   *                        strings
   */
  void create(@Nonnull String database, @Nonnull DatabaseType type, String... userCredentials);

  void create(@Nonnull String database, @Nonnull DatabaseType type,
      @Nonnull YouTrackDBConfig youTrackDBConfig, String... userCredentials);

  /**
   * Creates a new database without users. In case if you want to create users during creation
   * please use {@link #create(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @param config   custom configuration for current database
   */
  void create(String database, DatabaseType type, YouTrackDBConfig config);

  /**
   * Create a new database without users if it does not exist. In case if you want to create users
   * during creation please use {@link #createIfNotExists(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @return true if the database has been created, false if already exists
   */
  boolean createIfNotExists(String database, DatabaseType type);


  /**
   * Creates a new database alongside with users, passwords and roles if such one does not exist
   * yet.
   *
   * <p>If you want to create users during creation of database you should provide array that
   * consist of triple strings. Each triple string should contain user name, password and role.
   *
   * <p>The predefined roles are:
   *
   * <ul>
   *   <li>admin: has all privileges on the database
   *   <li>reader: can read the data but cannot modify it
   *   <li>writer: can read and modify the data but cannot create or delete classes
   * </ul>
   *
   * <p>For example:
   *
   * <p>{@code youTrackDB.createIfNotExists("test", DatabaseType.DISK, "user1", "password1",
   * "admin", "user2", "password2", "reader"); }
   *
   * @param database        database name
   * @param type            can be disk or memory
   * @param userCredentials user names, passwords and roles provided as a sequence of triple
   *                        strings
   */
  void createIfNotExists(@Nonnull String database, @Nonnull DatabaseType type,
      String... userCredentials);

  void createIfNotExists(@Nonnull String database, @Nonnull DatabaseType type,
      @Nonnull YouTrackDBConfig config, String... userCredentials);

  /**
   * Create a new database without users if not exists. In case if you want to create users during
   * creation please use {@link #createIfNotExists(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @param config   custom configuration for current database
   * @return true if the database has been created, false if already exists
   */
  boolean createIfNotExists(String database, DatabaseType type, YouTrackDBConfig config);

  /**
   * Drop a database
   *
   * @param database database name
   */
  void drop(String database);

  /**
   * Check if a database exists
   *
   * @param database database name to check
   * @return boolean true if exist false otherwise.
   */
  boolean exists(String database);

  /**
   * List exiting databases in the current environment
   *
   * @return a list of existing databases.
   */
  List<String> listDatabases();


  /**
   * Close the current YouTrackDB context with all related databases and pools.
   */
  @Override
  void close();

  /**
   * Check if the current YouTrackDB context is open
   *
   * @return boolean true if is open false otherwise.
   */
  boolean isOpen();

  SessionPool<S> cachedPool(String database, String user, String password);

  /**
   * Retrieve cached database pool with given username and password
   *
   * @param database database name
   * @param user     user name
   * @param password user password
   * @param config   YouTrackDB config for pool if need create it (in case if there is no cached
   *                 pool)
   * @return cached {@link SessionPool}
   */
  SessionPool<S> cachedPool(
      String database, String user, String password, YouTrackDBConfig config);

  /**
   * Creates database by restoring it from incremental backup. The backup should be created with
   * {@link BasicDatabaseSession#incrementalBackup(Path)}.
   * <p/>
   * At the moment only disk based databases are supported, you can not restore memory databases.
   *
   * @param name     Name of database to be created.
   * @param user     Name of server user, not needed for local databases.
   * @param password User password, not needed for local databases.
   * @param path     Path to the backup directory.
   * @param config   YouTrackDB config, the same as for
   *                 {@link #create(String, DatabaseType, YouTrackDBConfig)}
   */
  void restore(String name, String user, String password, String path,
      YouTrackDBConfig config);

  /**
   * Subscribe a query as a live query for future create/update event with the referred conditions
   *
   * @param query    live query
   * @param listener the listener that receive the query results
   * @param args     the live query args
   */
  LiveQueryMonitor live(String databaseName, String user, String password, YouTrackDBConfig config,
      String query, BasicLiveQueryResultListener<S, R> listener, Map<String, ?> args);

  /**
   * Subscribe a query as a live query for future create/update event with the referred conditions
   *
   * @param query    live query
   * @param listener the listener that receive the query results
   * @param args     the live query args
   */
  LiveQueryMonitor live(String databaseName, String user, String password,
      String query, BasicLiveQueryResultListener<S, R> listener, Map<String, ?> args);

  /**
   * Subscribe a query as a live query for future create/update event with the referred conditions
   *
   * @param query    live query
   * @param listener the listener that receive the query results
   * @param args     the live query args
   */
  LiveQueryMonitor live(String databaseName, String user, String password, YouTrackDBConfig config,
      String query, BasicLiveQueryResultListener<S, R> listener, Object... args);

  /**
   * Subscribe a query as a live query for future create/update event with the referred conditions
   *
   * @param query    live query
   * @param listener the listener that receive the query results
   * @param args     the live query args
   */
  LiveQueryMonitor live(String databaseName, String user, String password,
      String query, BasicLiveQueryResultListener<S, R> listener, Object... args);

  BasicResultSet<? extends BasicResult> execute(String script, Map<String, Object> params);

  BasicResultSet<? extends BasicResult> execute(String script, Object... params);
}
