/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrack.db.internal.jdbc;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.util.URLHelper;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 *
 */
public class YouTrackDbJdbcConnection implements Connection {

  private DatabaseSession database;
  private String dbUrl;
  private final Properties info;
  private final YouTrackDB youTrackDB;
  private boolean readOnly;
  private boolean autoCommit;
  private DatabaseSession.STATUS status;

  private final boolean youtrackDBisPrivate;

  public YouTrackDbJdbcConnection(final String jdbcdDUrl, final Properties info) {

    this.dbUrl = jdbcdDUrl.replace("jdbc:youtrackdb:", "");

    this.info = info;

    readOnly = false;

    final var username = info.getProperty("user", "admin");
    final var password = info.getProperty("password", "admin");
    final var serverUsername = info.getProperty("serverUser", "");
    final var serverPassword = info.getProperty("serverPassword", "");

    var connUrl = URLHelper.parseNew(dbUrl);
    youTrackDB =
        new YouTrackDBImpl(
            connUrl.getType() + ":" + connUrl.getPath(),
            serverUsername,
            serverPassword,
            YouTrackDBConfig.defaultConfig());

    if (!serverUsername.isEmpty() && !serverPassword.isEmpty()) {
      youTrackDB.createIfNotExists(
          connUrl.getDbName(),
          connUrl.getDbType().orElse(DatabaseType.MEMORY),
          username,
          password,
          "admin");
    }

    database = youTrackDB.open(connUrl.getDbName(), username, password);

    youtrackDBisPrivate = true;
    status = DatabaseSession.STATUS.OPEN;
  }

  public YouTrackDbJdbcConnection(DatabaseSession database, YouTrackDB youTrackDB,
      Properties info) {
    this.database = database;
    this.youTrackDB = youTrackDB;
    this.info = info;
    youtrackDBisPrivate = false;
    status = DatabaseSession.STATUS.OPEN;
  }

  protected YouTrackDB getYouTrackDb() {
    return youTrackDB;
  }

  public Statement createStatement() throws SQLException {

    return new YouTrackDbJdbcStatement(this);
  }

  public PreparedStatement prepareStatement(String sql) throws SQLException {
    return new YouTrackDbJdbcPreparedStatement(this, sql);
  }

  public CallableStatement prepareCall(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  public String nativeSQL(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  public void clearWarnings() throws SQLException {
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {

    throw new SQLFeatureNotSupportedException();
  }

  public boolean isWrapperFor(Class<?> iface) throws SQLException {

    throw new SQLFeatureNotSupportedException();
  }

  public String getUrl() {
    return dbUrl;
  }

  public void close() throws SQLException {
    status = DatabaseSession.STATUS.CLOSED;
    if (database != null) {
      database.close();
      database = null;
    }
    if (youtrackDBisPrivate) {
      youTrackDB.close();
    }
  }

  public void commit() throws SQLException {
    database.commit();
  }

  public void rollback() throws SQLException {
    database.rollback();
  }

  public boolean isClosed() throws SQLException {
    return status == DatabaseSession.STATUS.CLOSED;
  }

  public boolean isReadOnly() throws SQLException {
    return readOnly;
  }

  public void setReadOnly(boolean iReadOnly) throws SQLException {
    readOnly = iReadOnly;
  }

  public boolean isValid(int timeout) throws SQLException {
    return true;
  }

  public Array createArrayOf(String typeName, Object[] elements) throws SQLException {

    return null;
  }

  public Blob createBlob() throws SQLException {

    return null;
  }

  public Clob createClob() throws SQLException {

    return null;
  }

  public NClob createNClob() throws SQLException {

    return null;
  }

  public SQLXML createSQLXML() throws SQLException {

    return null;
  }

  public Statement createStatement(int resultSetType, int resultSetConcurrency)
      throws SQLException {
    return new YouTrackDbJdbcStatement(this);
  }

  public Statement createStatement(
      int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    return new YouTrackDbJdbcStatement(this);
  }

  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {

    return null;
  }

  public boolean getAutoCommit() throws SQLException {

    return autoCommit;
  }

  public void setAutoCommit(boolean autoCommit) throws SQLException {
    this.autoCommit = autoCommit;
  }

  public String getCatalog() throws SQLException {
    return database.getDatabaseName();
  }

  public void setCatalog(String catalog) throws SQLException {
  }

  public Properties getClientInfo() throws SQLException {

    return null;
  }

  public void setClientInfo(Properties properties) throws SQLClientInfoException {
    // noop
  }

  public String getClientInfo(String name) throws SQLException {
    return null;
  }

  public int getHoldability() throws SQLException {
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  public void setHoldability(int holdability) throws SQLException {
  }

  public DatabaseMetaData getMetaData() throws SQLException {
    return new YouTrackDbJdbcDatabaseMetaData(this, (DatabaseSessionInternal) database);
  }

  public int getTransactionIsolation() throws SQLException {
    return Connection.TRANSACTION_SERIALIZABLE;
  }

  public void setTransactionIsolation(int level) throws SQLException {
  }

  public Map<String, Class<?>> getTypeMap() throws SQLException {
    return null;
  }

  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
  }

  public SQLWarning getWarnings() throws SQLException {
    return null;
  }

  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  public CallableStatement prepareCall(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    return new YouTrackDbJdbcPreparedStatement(this, sql);
  }

  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
    return new YouTrackDbJdbcPreparedStatement(this, sql);
  }

  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
    return new YouTrackDbJdbcPreparedStatement(this, sql);
  }

  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    return new YouTrackDbJdbcPreparedStatement(this, resultSetType, resultSetConcurrency, sql);
  }

  public PreparedStatement prepareStatement(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    return new YouTrackDbJdbcPreparedStatement(
        this, resultSetType, resultSetConcurrency, resultSetHoldability, sql);
  }

  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  public void rollback(Savepoint savepoint) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  public void setClientInfo(String name, String value) throws SQLClientInfoException {
    // noop
  }

  public Savepoint setSavepoint() throws SQLException {

    return null;
  }

  public Savepoint setSavepoint(String name) throws SQLException {

    return null;
  }

  public DatabaseSession getDatabaseSession() {
    return database;
  }

  public void abort(Executor arg0) throws SQLException {
  }

  public int getNetworkTimeout() throws SQLException {
    return GlobalConfiguration.NETWORK_SOCKET_TIMEOUT.getValueAsInteger();
  }

  /**
   * No schema is supported.
   */
  public String getSchema() throws SQLException {
    return null;
  }

  public void setSchema(String arg0) throws SQLException {
  }

  public void setNetworkTimeout(Executor arg0, int arg1) throws SQLException {
    GlobalConfiguration.NETWORK_SOCKET_TIMEOUT.setValue(arg1);
  }

  public Properties getInfo() {
    return info;
  }
}
