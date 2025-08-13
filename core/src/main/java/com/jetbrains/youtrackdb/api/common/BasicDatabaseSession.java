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
package com.jetbrains.youtrackdb.api.common;

import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import com.jetbrains.youtrackdb.api.common.query.BasicResultSet;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.exception.CommandScriptException;
import com.jetbrains.youtrackdb.api.exception.ModificationOperationProhibitedException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TimeZone;
import javax.annotation.Nullable;

/**
 * Session for database operations with a specific user.
 */
public interface BasicDatabaseSession<R extends BasicResult, RS extends BasicResultSet<R>> extends
    AutoCloseable {

  enum STATUS {
    OPEN,
    CLOSED,
    IMPORTING
  }

  /**
   * @return <code>true</code> if database is obtained from the pool and <code>false</code>
   * otherwise.
   */
  boolean isPooled();

  /**
   * Closes an opened database, if the database is already closed does nothing, if a transaction is
   * active will be rollback.
   */
  @Override
  void close();

  /**
   * Returns the current status of database.
   */
  STATUS getStatus();


  /**
   * Returns the database URL.
   *
   * @return URL of the database
   */
  String getURL();

  /**
   * Returns the database name in case of embedded database or URL in case of remote database.
   */
  String getDatabaseName();

  /**
   * Checks if the database is closed.
   *
   * @return true if is closed, otherwise false.
   */
  boolean isClosed();


  /**
   * Flush cached storage content to the disk.
   *
   * <p>After this call users can perform only idempotent calls like read records and
   * select/traverse queries. All write-related operations will queued till {@link #release()}
   * command will be called.
   *
   * <p>Given command waits till all on going modifications in indexes or DB will be finished.
   *
   * <p>IMPORTANT: This command is not reentrant.
   *
   * @see #release()
   */
  void freeze();

  /**
   * Allows to execute write-related commands on DB. Called after {@link #freeze()} command.
   *
   * @see #freeze()
   */
  void release();

  /**
   * Flush cached storage content to the disk.
   *
   * <p>After this call users can perform only select queries. All write-related commands will
   * queued till {@link #release()} command will be called or exception will be thrown on attempt to
   * modify DB data. Concrete behaviour depends on <code>throwException</code> parameter.
   *
   * <p>IMPORTANT: This command is not reentrant.
   *
   * @param throwException If <code>true</code> {@link ModificationOperationProhibitedException}
   *                       exception will be thrown in case of write command will be performed.
   */
  void freeze(boolean throwException);

  @Nullable
  String getCurrentUserName();


  /**
   * Execute a script in a specified query language. The result set has to be closed after usage
   * <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * String script = "INSERT INTO Person SET name = 'foo', surname = ?;"+ "INSERT INTO Person SET
   * name = 'bar', surname = ?;"+ "INSERT INTO Person SET name = 'baz', surname = ?;";
   * <p>
   * ResultSet rs = db.runScript("sql", script, "Surname1", "Surname2", "Surname3"); ...
   * rs.close();
   * </code>
   */
  RS computeScript(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException;

  default RS computeSQLScript(String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    return computeScript("sql", script, args);
  }

  default RS computeGremlinScript(String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    return computeScript("gremlin", script, args);
  }

  default void executeScript(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    computeScript(language, script, args).close();
  }

  default void executeSQLScript(String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    executeScript("sql", script, args);
  }

  default void executeGremlinScript(String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    executeScript("gremlin", script, args);
  }

  /**
   * Execute a script of a specified query language The result set has to be closed after usage
   * <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * Map&lt;String, Object&gt params = new HashMapMap&lt;&gt(); params.put("surname1", "Jones");
   * params.put("surname2", "May"); params.put("surname3", "Ali");
   * <p>
   * String script = "INSERT INTO Person SET name = 'foo', surname = :surname1;"+ "INSERT INTO
   * Person SET name = 'bar', surname = :surname2;"+ "INSERT INTO Person SET name = 'baz', surname =
   * :surname3;";
   * <p>
   * ResultSet rs = db.runScript("sql", script, params); ... rs.close(); </code>
   */
  RS computeScript(String language, String script,
      Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException;

  default RS computeSQLScript(String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    return computeScript("sql", script, args);
  }

  default RS computeGremlinScript(String language, String script, Map<String, ?> args) {
    return computeScript("gremlin", script, args);
  }

  default void executeScript(String language, String script,
      Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    computeScript(language, script, args).close();
  }

  default void executeSQLScript(String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    executeScript("sql", script, args);
  }

  default void executeGremlinScript(String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    executeScript("gremlin", script, args);
  }

  /**
   * Performs incremental backup of database content to the selected folder. This is thread safe
   * operation and can be done in normal operational mode.
   *
   * <p>If it will be first backup of data full content of database will be copied into folder
   * otherwise only changes after last backup in the same folder will be copied.
   *
   * @param path Path to backup folder.
   */
  void backup(Path path);

  @Nullable
  TimeZone getDatabaseTimeZone();

  enum ATTRIBUTES {
    DATEFORMAT,
    DATE_TIME_FORMAT,
    TIMEZONE,
    LOCALE_COUNTRY,
    LOCALE_LANGUAGE,
    CHARSET,
  }
}
