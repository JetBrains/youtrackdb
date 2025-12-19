package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.exception.AcquireTimeoutException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;

/**
 * A Pool of databases.
 *
 * <p>Example of usage with an YouTrackDB context:
 *
 * <p>
 *
 * <pre>
 * <code>
 * YouTrackDB youTrackDb= new YouTrackDB("remote:localhost","root","password");
 * //...
 * SessionPool pool = new SessionPool(youTrackDb,"myDb","admin","adminpwd");
 * ODatabaseDocument session = pool.acquire();
 * //....
 * session.close();
 * pool.close();
 * youTrackDb.close();
 *
 * </code>
 * </pre>
 *
 * <p>
 *
 * <p>
 *
 * <p>Example of usage as simple access to a specific database without a context:
 *
 * <p>
 *
 * <pre><code>
 * SessionPool pool = new SessionPool("remote:localhost/myDb","admin","adminpwd");
 * ODatabaseDocument session = pool.acquire();
 * //....
 * session.close();
 * pool.close();
 *
 * </code></pre>
 *
 * <p>
 *
 * <p>
 */
public class SessionPoolImpl implements
    SessionPool {

  private final YouTrackDBImpl youTrackDb;
  private final DatabasePoolInternal internal;
  private final boolean autoclose;

  /**
   * Open a new database pool on a specific environment.
   *
   * @param environment the starting environment.
   * @param database    the database name
   * @param user        the database user for the current pool of databases.
   * @param password    the password relative to the user name
   */
  public SessionPoolImpl(YouTrackDBImpl environment, String database, String user,
      String password) {
    this(environment, database, user, password,
        (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig());
  }

  /**
   * Open a new database pool on a specific environment, with a specific configuration for this
   * pool.
   *
   * @param environment   the starting environment.
   * @param database      the database name
   * @param user          the database user for the current pool of databases.
   * @param password      the password relative to the user name
   * @param configuration the configuration relative for the current pool.
   */
  public SessionPoolImpl(
      YouTrackDBImpl environment,
      String database,
      String user,
      String password,
      YouTrackDBConfigImpl configuration) {
    youTrackDb = environment;
    autoclose = false;
    internal = youTrackDb.openPool(database, user, password, configuration);
  }


  public SessionPoolImpl(YouTrackDBImpl environment, DatabasePoolInternal internal) {
    this.youTrackDb = environment;
    this.internal = internal;
    autoclose = false;
  }

  /**
   * Acquire a session from the pool, if no session are available will wait until a session is
   * available or a timeout is reached
   *
   * @return a session from the pool.
   * @throws AcquireTimeoutException in case the timeout for waiting for a session is reached.
   */
  @Override
  public DatabaseSessionEmbedded acquire() throws AcquireTimeoutException {
    return internal.acquire();
  }

  @Override
  public void close() {
    internal.close();
    if (autoclose) {
      youTrackDb.close();
    }
  }

  @Override
  public String getDbName() {
    return internal.getDatabaseName();
  }

  @Override
  public String getUserName() {
    return internal.getUserName();
  }

  @Override
  public YTDBGraph asGraph() {
    var configuration = youTrackDb.internal.getConfiguration().toApacheConfiguration();
    configuration.setProperty(YTDBGraphFactory.CONFIG_DB_NAME, getDbName());
    return new YTDBGraphEmbedded(this, configuration);
  }

  /**
   * Check if database pool is closed
   *
   * @return true if database pool is closed
   */
  @Override
  public boolean isClosed() {
    return internal.isClosed();
  }
}
