/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.client.remote.db;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.exception.CommandScriptException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResultSet;
import com.jetbrains.youtrackdb.internal.client.remote.BinaryProtocolSession;
import com.jetbrains.youtrackdb.internal.client.remote.RemoteCommandsDispatcherImpl;
import com.jetbrains.youtrackdb.internal.client.remote.message.PaginatedResultSet;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.cache.WeakValueHashMap;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrackdb.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TimeZone;
import javax.annotation.Nullable;

public class DatabaseSessionRemote implements RemoteDatabaseSessionInternal {

  private final ThreadLocal<DatabaseSessionRemote> activeSession = new ThreadLocal<>();

  private final Map<String, RemoteResultSet> activeQueries =
      new WeakValueHashMap<>(true, this::closeRemoteQuery);

  private String url;
  protected STATUS status;

  private String userName;

  private boolean initialized = false;

  private BinaryProtocolSession sessionMetadata;
  private final RemoteCommandsDispatcherImpl commandsOrchestrator;
  private int resultSetReportThreshold = 10;

  @Nullable
  private TimeZone serverTimeZone;

  public DatabaseSessionRemote(final RemoteCommandsDispatcherImpl commandsOrchestrator) {
    activateOnCurrentThread();
    try {
      status = STATUS.CLOSED;

      // OVERWRITE THE URL
      url = commandsOrchestrator.getURL();
      this.commandsOrchestrator = commandsOrchestrator;
    } catch (Exception t) {
      activeSession.remove();

      throw BaseException.wrapException(
          new DatabaseException(url, "Error on opening database "), t, this);
    }
  }

  public void internalOpen(String user, String password, YouTrackDBConfigImpl config) {
    try {
      serverTimeZone = commandsOrchestrator.open(this, user, password, config.getConfiguration());
      status = STATUS.OPEN;

      initAtFirstOpen();

      this.resultSetReportThreshold = config.getConfiguration().getValueAsInteger(
          GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD);

      this.userName = user;
    } catch (BaseException e) {
      close();
      activeSession.remove();
      throw e;
    } catch (Exception e) {
      close();
      activeSession.remove();
      throw BaseException.wrapException(
          new DatabaseException(url, "Cannot open database url=" + url), e,
          this);
    }
  }


  private void initAtFirstOpen() {
    if (initialized) {
      return;
    }

    this.userName = null;

    initialized = true;
  }


  private void checkOpenness() {
    if (status == STATUS.CLOSED) {
      throw new DatabaseException(url, "Database '" + url + "' is closed");
    }
  }


  public BinaryProtocolSession getSessionMetadata() {
    return sessionMetadata;
  }

  public void setSessionMetadata(BinaryProtocolSession sessionMetadata) {
    assert assertIfNotActive();
    this.sessionMetadata = sessionMetadata;
  }

  public void activateOnCurrentThread() {
    activeSession.set(this);
  }

  @Override
  public boolean assertIfNotActive() {
    var currentDatabase = activeSession.get();

    if (currentDatabase != this) {
      throw new SessionNotActivatedException(url);
    }

    return true;
  }

  @Override
  public RemoteCommandsDispatcherImpl getCommandOrchestrator() {
    return commandsOrchestrator;
  }

  @Override
  public RemoteResultSet query(String query,
      Object... args) {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.query(this, query, args);
    return result.result();
  }

  @Override
  public RemoteResultSet query(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.query(this, query, args);
    return result.result();
  }

  @Override
  public RemoteResultSet execute(String query, Object... args) {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.command(this, query, args);
    return result.result();
  }

  @Override
  public RemoteResultSet execute(String query, @SuppressWarnings("rawtypes") Map args) {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.command(this, query, args);
    return result.result();
  }


  @Override
  public RemoteResultSet computeScript(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.execute(this, language, script, args);
    return result.result();
  }

  @Override
  public RemoteResultSet computeScript(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.execute(this, language, script, args);
    return result.result();
  }

  public synchronized void queryStarted(String id, RemoteResultSet resultSet) {
    assert assertIfNotActive();

    final var activeQueriesSize = activeQueries.size();

    if (resultSetReportThreshold > 0 &&
        activeQueriesSize > 1 &&
        activeQueriesSize % resultSetReportThreshold == 0) {
      var msg =
          "This database instance has "
              + activeQueriesSize
              + " open command/query result sets, please make sure you close them with"
              + " ResultSet.close()";
      LogManager.instance().warn(this, msg);
    }

    this.activeQueries.put(id, resultSet);
  }

  public void closeQuery(String queryId) {
    assert assertIfNotActive();
    closeRemoteQuery(queryId);
    queryClosed(queryId);
  }

  private void closeRemoteQuery(String queryId) {
    commandsOrchestrator.closeQuery(this, queryId);
  }

  public void rollbackActiveTx() {
    assert assertIfNotActive();
    commandsOrchestrator.rollbackActiveTx(this);
  }

  public void queryClosed(String id) {
    assert assertIfNotActive();
    this.activeQueries.remove(id);
  }

  public void fetchNextPage(PaginatedResultSet rs) {
    checkOpenness();
    assert assertIfNotActive();

    commandsOrchestrator.fetchNextPage(this, rs);
  }


  @Override
  public void backup(final Path path) throws UnsupportedOperationException {
    checkOpenness();
    assert assertIfNotActive();

    commandsOrchestrator.incrementalBackup(this, path.toAbsolutePath().toString());
  }

  @Nullable
  @Override
  public TimeZone getDatabaseTimeZone() {
    return serverTimeZone;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void freeze(final boolean throwException) {
    checkOpenness();
    LogManager.instance()
        .error(
            this,
            "Only local paginated storage supports freeze. If you are using remote client please"
                + " use YouTrackDB instance instead",
            null);
  }

  @Nullable
  @Override
  public String getCurrentUserName() {
    return userName;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void freeze() {
    freeze(false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    checkOpenness();
    LogManager.instance()
        .error(
            this,
            "Only local paginated storage supports release. If you are using remote client please"
                + " use YouTrackDB instance instead",
            null);
  }

  @Override
  public boolean isPooled() {
    return false;
  }

  @Override
  public void close() {
    internalClose(false);
  }

  @Override
  public STATUS getStatus() {
    return status;
  }

  @Override
  public String getURL() {
    return url;
  }

  @Override
  public String getDatabaseName() {
    return url;
  }

  @Override
  public boolean isClosed() {
    return status == STATUS.CLOSED || commandsOrchestrator.isClosed(this);
  }

  public void internalClose(boolean recycle) {
    if (status != STATUS.OPEN) {
      return;
    }

    try {
      closeActiveQueries();
      rollbackActiveTx();

      if (isClosed()) {
        status = STATUS.CLOSED;
        return;
      }

      status = STATUS.CLOSED;
      if (!recycle) {

        if (commandsOrchestrator != null) {
          commandsOrchestrator.close(this);
        }
      }

    } finally {
      // ALWAYS RESET TL
      activeSession.remove();
    }
  }

  public synchronized void closeActiveQueries() {
    for (var rs : new ArrayList<>(activeQueries.values())) {
      rs.close();
    }
  }
}
