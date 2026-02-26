package com.jetbrains.youtrackdb.internal.core.db;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.jspecify.annotations.NonNull;

public class YouTrackDBImpl implements YouTrackDB, AutoCloseable {

  private final ConcurrentLinkedHashMap<DatabasePoolInternal, SessionPoolImpl> cachedPools =
      new ConcurrentLinkedHashMap.Builder<DatabasePoolInternal, SessionPoolImpl>()
          .maximumWeightedCapacity(100)
          .build();

  private final Lock lock = new ReentrantLock();
  public final YouTrackDBInternal internal;

  public YouTrackDBImpl(YouTrackDBInternal internal) {
    this.internal = internal;
  }

  @Override
  public String toString() {
    return "youtrackdb:" + internal.getBasePath() + ":v-" + YouTrackDBConstants.getVersion();
  }

  /**
   * Open a database
   *
   * @param database the database to open
   * @param user     username of a database user or a server user allowed to open the database
   * @param password related to the specified username
   * @return the opened database
   */
  public DatabaseSessionEmbedded open(String database, String user, String password) {
    return open(database, user, password, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Open a database
   *
   * @param database the database to open
   * @param user     username of a database user or a server user allowed to open the database
   * @param password related to the specified username
   * @param config   custom configuration for current database
   * @return the opened database
   */
  public DatabaseSessionEmbedded open(
      String database, String user, String password, YouTrackDBConfig config) {
    return internal.open(database, user, password, config);
  }

  /**
   * Create a new database without users. In case if you want to create users during creation please
   * use {@link YouTrackDB#create(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @see YouTrackDB#create(String, DatabaseType, String...)
   */
  public void create(String database, DatabaseType type) {
    create(database, type, YouTrackDBConfig.defaultConfig());
  }

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
  @Override
  public void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      String... userCredentials) {
    create(databaseName, type, YouTrackDBConfig.defaultConfig(), userCredentials);


  }

  public void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull YouTrackDBConfig youTrackDBConfig, String... userCredentials) {
    doCreate(databaseName, type, youTrackDBConfig, true, userCredentials);
  }

  private void doCreate(@NonNull String databaseName, @NonNull DatabaseType type,
      @NonNull YouTrackDBConfig youTrackDBConfig, boolean failIfExist, String[] userCredentials) {
    if (userCredentials != null && userCredentials.length > 0) {
      if (userCredentials.length % 3 != 0) {
        throw new IllegalArgumentException(
            "User credentials should be provided as a sequence of triple strings");
      }
    }

    internal.create(databaseName, null, null, type,
        youTrackDBConfig, failIfExist, session -> {

          session.executeInTx(transaction -> {
            var security = session.getMetadata().getSecurity();

            for (var i = 0; i < userCredentials.length; i += 3) {
              var userName = userCredentials[i];
              var password = userCredentials[i + 1];
              var role = userCredentials[i + 2];

              security.createUser(userName, password, role);
            }
          });

          return null;
        });
  }

  /**
   * Creates a new database without users. In case if you want to create users during creation
   * please use {@link YouTrackDB#create(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @param config   custom configuration for current database
   */
  public void create(String database, DatabaseType type, YouTrackDBConfig config) {
    this.internal.create(database, null, null, type, config);
  }

  /**
   * Create a new database without users if it does not exist. In case if you want to create users
   * during creation please use
   * {@link YouTrackDB#createIfNotExists(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @return true if the database has been created, false if already exists
   */
  public boolean createIfNotExists(String database, DatabaseType type) {
    return createIfNotExists(database, type, YouTrackDBConfig.defaultConfig());
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
  @Override
  public void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      String... userCredentials) {
    createIfNotExists(databaseName, type, YouTrackDBConfig.defaultConfig(), userCredentials);
  }

  public void createIfNotExists(@Nonnull String database, @Nonnull DatabaseType type,
      @Nonnull YouTrackDBConfig config, String... userCredentials) {
    doCreate(database, type, config, false, userCredentials);
  }

  /// Open the YTDB Graph instance by database name, using the current username and password.
  ///
  /// @param databaseName Database name
  /// @param userName     user name
  @Nonnull
  private YTDBGraph openGraph(@Nonnull String databaseName, @Nonnull String userName,
      @Nonnull String userPassword) {
    var sessionPool = cachedPool(databaseName, userName, userPassword);
    return sessionPool.asGraph();
  }


  @Override
  public @NonNull YTDBGraphTraversalSource openTraversal(@NonNull String databaseName,
      @NonNull String userName, @NonNull String userPassword) {
    var graph = openGraph(databaseName, userName, userPassword);

    return new StandaloneYTDBGraphTraversalSource(graph);
  }

  @Override
  public @NonNull YTDBGraphTraversalSource openTraversal(@NonNull String databaseName) {
    throw new DatabaseException("This method can be used only for remote databases");
  }

  /**
   * Create a new database without users if not exists. In case if you want to create users during
   * creation please use {@link YouTrackDB#createIfNotExists(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @param config   custom configuration for current database
   * @return true if the database has been created, false if already exists
   */
  public boolean createIfNotExists(String database, DatabaseType type, YouTrackDBConfig config) {
    if (!this.internal.exists(database)) {
      this.internal.create(database, null, null, type, config);
      return true;
    }
    return false;
  }

  @Override
  public void createSystemUser(@Nonnull String username, @Nonnull String password,
      @Nonnull String... role) {
    internal.getSystemDatabase().executeInDBScope(session -> {
      session.executeInTx(transaction -> {
        var security = session.getMetadata().getSecurity();
        security.createUser(username, password, role);
      });
      return null;
    });
  }

  @Override
  public List<String> listSystemUsers() {
    return internal.getSystemDatabase()
        .executeWithDB(session -> session.computeInTx(transaction -> {
          var security = session.getMetadata().getSecurity();
          return security.getAllUsers().stream()
              .map(entity -> entity.getString(SecurityUserImpl.NAME_PROPERTY)).toList();
        }));
  }

  @Override
  public void dropSystemUser(@NonNull String username) {
    internal.getSystemDatabase().executeInDBScope(session -> {
      session.executeInTx(transaction -> {
        var security = session.getMetadata().getSecurity();
        security.dropUser(username);
      });
      return null;
    });
  }

  /// Drop a database
  ///
  /// @param databaseName database name
  @Override
  public void drop(@Nonnull String databaseName) {
    this.internal.drop(databaseName, null, null);
  }

  /// Check if a database exists
  ///
  /// @param databaseName database name to check
  /// @return boolean true if exist false otherwise.
  @Override
  public boolean exists(@Nonnull String databaseName) {
    return this.internal.exists(databaseName);
  }

  /// List exiting databases in the current environment
  ///
  /// @return a list of existing databases.
  @Override
  @Nonnull
  public List<String> listDatabases() {
    return new ArrayList<>(this.internal.listDatabases(null, null));
  }

  /// Close the current YouTrackDB database manager with all related databases and pools.
  @Override
  public void restore(@Nonnull String databaseName,
      @Nonnull String path) {
    internal.restore(databaseName, path, null, null);
  }

  @Override
  public void restore(@Nonnull String databaseName,
      @Nonnull String path,
      @Nullable String expectedUUID, @Nonnull Configuration config) {
    internal.restore(databaseName, path, expectedUUID,
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
  }

  @Override
  public void close() {
    lock.lock();
    try {
      if (isOpen()) {
        this.cachedPools.clear();
        this.internal.close();

        YTDBGraphFactory.unregisterYTDBInstance(this, () -> {
          this.cachedPools.clear();
          this.internal.close();
        });
      }
    } finally {
      lock.unlock();
    }
  }

  /// Check if the current YouTrackDB database manager is open
  ///
  /// @return boolean true if is open false otherwise.
  @Override
  public boolean isOpen() {
    return this.internal.isOpen();
  }

  @SuppressWarnings("SameParameterValue")
  DatabasePoolInternal openPool(
      String database, String user, String password, YouTrackDBConfig config) {
    return this.internal.openPool(database, user, password, config);
  }

  public SessionPool cachedPool(String database, String user,
      String password) {
    return cachedPool(database, user, password, YouTrackDBConfig.defaultConfig());
  }

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
  public SessionPool cachedPool(
      String database, String user, String password, YouTrackDBConfig config) {
    var internalPool = internal.cachedPool(database, user, password, config);

    var pool = cachedPools.get(internalPool);

    if (pool != null) {
      return pool;
    }

    return cachedPools.computeIfAbsent(internalPool,
        key -> new SessionPoolImpl(this, internalPool));
  }

  public @Nonnull YTDBGraphTraversalSource openTraversalNoAuthenticate(@Nonnull String databaseName,
      @Nonnull String userName) {
    var graph = openCachedPoolNoAuthenticate(databaseName, userName).asGraph();
    return new StandaloneYTDBGraphTraversalSource(graph);
  }

  private SessionPool openCachedPoolNoAuthenticate(String database, String user) {
    var internalPool = internal.cachedPoolNoAuthentication(database, user,
        YouTrackDBConfig.defaultConfig());
    var pool = cachedPools.get(internalPool);

    if (pool != null) {
      return pool;
    }

    return cachedPools.computeIfAbsent(internalPool,
        key -> new SessionPoolImpl(this, internalPool));
  }

  public void restore(String name, String path, YouTrackDBConfig config) {
    internal.restore(name, null, path, config);
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
  @Override
  public void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration youTrackDBConfig, String... userCredentials) {
    var builder = YouTrackDBConfig.builder().fromApacheConfiguration(youTrackDBConfig);
    create(databaseName, type, builder.build(), userCredentials);
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
  @Override
  public void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration config, String... userCredentials) {
    var builder = YouTrackDBConfig.builder().fromApacheConfiguration(config);
    createIfNotExists(databaseName, type, builder.build(), userCredentials);
  }

  /// Creates a database by restoring it from incremental backup. The backup should be created with
  /// [#incrementalBackup(Path)].
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// @param databaseName Name of a database to be created.
  /// @param path         Path to the backup directory.
  /// @param config       YouTrackDB configuration.
  @Override
  public void restore(@Nonnull String databaseName,
      @Nonnull String path,
      @Nullable Configuration config) {
    if (config == null) {
      internal.restore(databaseName, null, path, YouTrackDBConfig.defaultConfig());
    } else {
      var builder = YouTrackDBConfig.builder().fromApacheConfiguration(config);
      internal.restore(databaseName, null, path, builder.build());
    }
  }

  public void invalidateCachedPools() {
    synchronized (this) {
      cachedPools.forEach((internalPool, pool) -> pool.close());
      cachedPools.clear();
    }
  }

  private static final class StandaloneYTDBGraphTraversalSource extends YTDBGraphTraversalSource {

    private StandaloneYTDBGraphTraversalSource(Graph graph) {
      super(graph);
    }

    @Override
    public String toString() {
      var config = getGraph().configuration();
      final var graphString = config.getString(YTDBGraphFactory.CONFIG_DB_NAME);
      return "ytdbGraphTraversalSource[" + graphString + "]:v-" + YouTrackDBConstants.getVersion();
    }

    @Override
    public void close() {
      super.close();

      try {
        getGraph().close();
      } catch (Exception e) {
        throw new RuntimeException("Error during closing of Graph instance", e);
      }
    }
  }
}
