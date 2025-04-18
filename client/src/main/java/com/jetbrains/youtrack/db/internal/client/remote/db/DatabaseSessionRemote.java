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

package com.jetbrains.youtrack.db.internal.client.remote.db;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.CommandScriptException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResultSet;
import com.jetbrains.youtrack.db.internal.client.remote.RemoteCommandsOrchestratorImpl;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemoteSession;
import com.jetbrains.youtrack.db.internal.client.remote.message.PaginatedResultSet;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.QueryDatabaseState;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrack.db.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.nio.file.Path;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

public class DatabaseSessionRemote implements RemoteDatabaseSessionInternal {

  private final ThreadLocal<DatabaseSessionRemote> activeSession = new ThreadLocal<>();
  private final ConcurrentHashMap<String, QueryDatabaseState<RemoteResultSet>>
      activeQueries = new ConcurrentHashMap<>();

  private String url;
  protected STATUS status;

  private String userName;

  private boolean initialized = false;

  private StorageRemoteSession sessionMetadata;
  private final RemoteCommandsOrchestratorImpl commandsOrchestrator;

  @Nullable
  private TimeZone serverTimeZone;

  public DatabaseSessionRemote(final RemoteCommandsOrchestratorImpl commandsOrchestrator) {
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


  public StorageRemoteSession getSessionMetadata() {
    return sessionMetadata;
  }

  public void setSessionMetadata(StorageRemoteSession sessionMetadata) {
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
  public RemoteCommandsOrchestratorImpl getCommandOrchestrator() {
    assert assertIfNotActive();
    return commandsOrchestrator;
  }

  public com.jetbrains.youtrack.db.api.remote.query.RemoteResultSet query(String query,
      Object... args) {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.query(this, query, args);
    return result.getResult();
  }

  public RemoteResultSet query(String query, @SuppressWarnings("rawtypes") Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.query(this, query, args);
    return result.getResult();
  }

  public RemoteResultSet execute(String query, Object... args) {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.command(this, query, args);
    return result.getResult();
  }

  public RemoteResultSet execute(String query, @SuppressWarnings("rawtypes") Map args) {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.command(this, query, args);
    return result.getResult();
  }

  @Override
  public RemoteResultSet runScript(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.execute(this, language, script, args);
    return result.getResult();
  }

  @Override
  public RemoteResultSet runScript(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    checkOpenness();
    assert assertIfNotActive();

    var result = commandsOrchestrator.execute(this, language, script, args);
    return result.getResult();
  }

  public synchronized void queryStarted(String id, QueryDatabaseState<RemoteResultSet> state) {
    assert assertIfNotActive();

    if (this.activeQueries.size() > 1 && this.activeQueries.size() % 10 == 0) {
      var msg =
          "This database instance has "
              + activeQueries.size()
              + " open command/query result sets, please make sure you close them with"
              + " ResultSet.close()";
      LogManager.instance().warn(this, msg);
    }

    this.activeQueries.put(id, state);
  }


  public void closeQuery(String queryId) {
    assert assertIfNotActive();
    commandsOrchestrator.closeQuery(this, queryId);

    queryClosed(queryId);
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
  public String incrementalBackup(final Path path) throws UnsupportedOperationException {
    checkOpenness();
    assert assertIfNotActive();

    return commandsOrchestrator.incrementalBackup(this, path.toAbsolutePath().toString());
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
    while (!activeQueries.isEmpty()) {
      this.activeQueries
          .values()
          .iterator()
          .next()
          .close(); // the query automatically unregisters itself
    }
  }
}
