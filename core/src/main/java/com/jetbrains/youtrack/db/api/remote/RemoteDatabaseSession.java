package com.jetbrains.youtrack.db.api.remote;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResultSet;
import com.jetbrains.youtrack.db.api.transaction.Transaction;
import java.util.Map;

public interface RemoteDatabaseSession extends
    BasicDatabaseSession<RemoteResult, RemoteResultSet> {

  /**
   * Executes an SQL query. The result set has to be closed after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.query("SELECT FROM V where name = ?", "John"); while(rs.hasNext()){ Result
   * item = rs.next(); ... } rs.close(); </code>
   *
   * @param query the query string
   * @param args  query parameters (positional)
   * @return the query result set
   */
  RemoteResultSet query(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException;

  /**
   * Executes an SQL query (idempotent). The result set has to be closed after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * Map&lt;String, Object&gt params = new HashMapMap&lt;&gt(); params.put("name", "John");
   * ResultSet rs = db.query("SELECT FROM V where name = :name", params); while(rs.hasNext()){
   * Result item = rs.next(); ... } rs.close();
   * </code>
   *
   * @param query the query string
   * @param args  query parameters (named)
   * @return the query result set
   */
  @SuppressWarnings("rawtypes")
  RemoteResultSet query(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException;

  /**
   * Executes a generic (idempotent or non-idempotent) command. The result set has to be closed
   * after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.execute("INSERT INTO Person SET name = ?", "John"); ... rs.close();
   * </code>
   *
   * @param args query arguments
   * @return the query result set
   */
  RemoteResultSet execute(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException;

  /**
   * Executes a generic (idempotent or non-idempotent) command. The result set has to be closed
   * after usage <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * ResultSet rs = db.execute("INSERT INTO Person SET name = :name", Map.of("name", "John")); ...
   * rs.close();
   * </code>
   */
  @SuppressWarnings("rawtypes")
  RemoteResultSet execute(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException;

  /**
   * Executes a generic (non-idempotent) command, ignoring the produced result. Works in the same
   * way as {@link Transaction#execute(String, Object...)}, but doesn't require closing the result
   * set after usage. <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * db.command("INSERT INTO Person SET name = ?", "John");
   * </code>
   *
   * @param args query arguments
   */
  default void command(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    execute(query, args).close();
  }

  /**
   * Executes a generic (non-idempotent) command, ignoring the produced result. Works in the same
   * way as {@link Transaction#execute(String, Map)}, but doesn't require closing the result set
   * after usage. <br>
   * <br>
   * Sample usage:
   *
   * <p><code>
   * db.command("INSERT INTO Person SET name = :name", Map.of("name", "John");
   * </code>
   *
   * @param args query arguments
   */
  @SuppressWarnings("rawtypes")
  default void command(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    execute(query, args).close();
  }
}
