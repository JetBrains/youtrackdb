package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * LRU cache for already parsed YQL/SQL statement executors using Guava Cache. Eliminates TOCTOU
 * race between cache lookup and parse via atomic {@code cache.get(key, loader)}.
 */
public class YqlStatementCache {

  private final int capacity;
  private final Cache<String, SQLStatement> cache;

  /**
   * @param size the size of the cache; 0 means cache disabled
   */
  public YqlStatementCache(int size) {
    this.capacity = size;
    this.cache = size > 0
        ? CacheBuilder.newBuilder().maximumSize(size).build()
        : null;
  }

  /**
   * @param statement an SQL statement
   * @return true if the corresponding executor is present in the cache
   */
  public boolean contains(String statement) {
    if (capacity == 0) {
      return false;
    }
    return cache.asMap().containsKey(statement);
  }

  /**
   * Returns an already parsed SQL executor, taking it from the cache if it exists or creating a new
   * one (parsing and then putting it into the cache) if it doesn't.
   *
   * @param statement the SQL statement
   * @param session   the current DB instance. If null, cache is ignored and a new executor is
   *                  created through statement parsing
   * @return a statement executor from the cache
   */
  public static SQLStatement get(String statement, DatabaseSessionEmbedded session) {
    if (session == null) {
      return parse(statement, session);
    }

    var resource = session.getSharedContext().getYqlStatementCache();
    return resource.getCached(statement, session);
  }

  /**
   * Returns an already parsed server-level SQL executor, taking it from the cache if it exists or
   * creating a new one (parsing and then putting it into the cache) if it doesn't.
   *
   * @param statement the SQL statement
   * @param db        the current YouTrackDB instance. If null, cache is ignored and a new executor
   *                  is created through statement parsing
   * @return a statement executor from the cache
   */
  public static SQLServerStatement getServerStatement(String statement, YouTrackDBInternal db) {
    // TODO create a global cache!
    return parseServerStatement(statement);
  }

  /**
   * Returns the cached statement or parses it atomically (only one thread parses a given key).
   *
   * @param statement an SQL statement
   * @param session   the database session (used for charset during parsing)
   * @return the parsed SQL statement
   */
  public @Nonnull SQLStatement getCached(String statement, DatabaseSessionEmbedded session) {
    if (capacity == 0) {
      return parse(statement, session);
    }

    try {
      // Atomic cache.get(key, loader) eliminates TOCTOU race:
      // only one thread will parse for a given key, others will wait and reuse the result
      return cache.get(statement, () -> parse(statement, session));
    } catch (CommandSQLParsingException e) {
      throw e;
    } catch (Exception e) {
      var cause = e.getCause();
      if (cause instanceof CommandSQLParsingException parsingException) {
        throw parsingException;
      }
      if (cause instanceof RuntimeException runtimeCause) {
        throw runtimeCause;
      }
      throw new RuntimeException("Failed to parse SQL statement: " + statement, e);
    }
  }

  /**
   * Parses an SQL statement and returns the corresponding executor.
   *
   * @param statement the SQL statement
   * @param session   the database session (for charset); may be null
   * @return the corresponding executor
   * @throws CommandSQLParsingException if the input parameter is not a valid SQL statement
   */
  @Nonnull
  protected static SQLStatement parse(String statement, DatabaseSessionEmbedded session)
      throws CommandSQLParsingException {
    try {
      InputStream is;
      if (session != null) {
        try {
          is = new ByteArrayInputStream(
              statement.getBytes(session.getStorage().getCharset()));
        } catch (UnsupportedEncodingException e2) {
          LogManager.instance()
              .warn(
                  YqlStatementCache.class,
                  "Unsupported charset for database "
                      + session
                      + " "
                      + session.getStorage().getCharset());
          is = new ByteArrayInputStream(statement.getBytes());
        }
      } else {
        is = new ByteArrayInputStream(statement.getBytes());
      }

      YouTrackDBSql osql;
      if (session == null) {
        osql = new YouTrackDBSql(is);
      } else {
        try {
          osql = new YouTrackDBSql(is, session.getStorage().getCharset());
        } catch (UnsupportedEncodingException e2) {
          LogManager.instance()
              .warn(
                  YqlStatementCache.class,
                  "Unsupported charset for database "
                      + session
                      + " "
                      + session.getStorage().getCharset());
          osql = new YouTrackDBSql(is);
        }
      }
      var result = osql.parse();
      result.originalStatement = statement;

      return result;
    } catch (ParseException e) {
      throwParsingException(e, statement);
    } catch (TokenMgrError e2) {
      throwParsingException(e2, statement);
    }
    // unreachable — throwParsingException always throws
    throw new AssertionError("unreachable");
  }

  /**
   * Parses an SQL statement and returns the corresponding executor.
   *
   * @param statement the SQL statement
   * @return the corresponding executor
   * @throws CommandSQLParsingException if the input parameter is not a valid SQL statement
   */
  @Nullable
  protected static SQLServerStatement parseServerStatement(String statement)
      throws CommandSQLParsingException {
    try {
      InputStream is = new ByteArrayInputStream(statement.getBytes());
      var osql = new YouTrackDBSql(is);
      var serverStatement = osql.parseServerStatement();

      return serverStatement;
    } catch (ParseException e) {
      throwParsingException(e, statement);
    } catch (TokenMgrError e2) {
      throwParsingException(e2, statement);
    }
    return null;
  }

  protected static void throwParsingException(ParseException e, String statement) {
    throw new CommandSQLParsingException(null, e, statement);
  }

  protected static void throwParsingException(TokenMgrError e, String statement) {
    throw new CommandSQLParsingException(null, e, statement);
  }

  public void clear() {
    if (cache != null) {
      cache.invalidateAll();
    }
  }
}
