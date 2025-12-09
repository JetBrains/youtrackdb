package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * This class is an LRU cache for already parsed SQL statement executors. It stores itself in the
 * storage as a resource. It also acts an an entry point for the SQL parser.
 */
public class StatementCache {

  private final Map<String, SQLStatement> map;
  private final int mapSize;

  /**
   * @param size the size of the cache
   */
  public StatementCache(int size) {
    this.mapSize = size;
    map =
        new LinkedHashMap<String, SQLStatement>(size) {
          protected boolean removeEldestEntry(final Map.Entry<String, SQLStatement> eldest) {
            return super.size() > mapSize;
          }
        };
  }

  /**
   * @param statement an SQL statement
   * @return true if the corresponding executor is present in the cache
   */
  public boolean contains(String statement) {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return false;
    }

    synchronized (map) {
      return map.containsKey(statement);
    }
  }

  /**
   * returns an already parsed SQL executor, taking it from the cache if it exists or creating a new
   * one (parsing and then putting it into the cache) if it doesn't
   *
   * @param statement the SQL statement
   * @param session        the current DB instance. If null, cache is ignored and a new executor is
   *                  created through statement parsing
   * @return a statement executor from the cache
   */
  public static SQLStatement get(String statement, DatabaseSessionInternal session) {
    if (session == null) {
      return parse(statement, session);
    }

    var resource = session.getSharedContext().getStatementCache();
    return resource.getCached(statement, session);
  }

  /**
   * returns an already parsed server-level SQL executor, taking it from the cache if it exists or
   * creating a new one (parsing and then putting it into the cache) if it doesn't
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
   * @param statement an SQL statement
   * @param session
   * @return the corresponding executor, taking it from the internal cache, if it exists
   */
  public SQLStatement getCached(String statement, DatabaseSessionInternal session) {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return parse(statement, session);
    }
    SQLStatement result;
    synchronized (map) {
      // LRU
      result = map.remove(statement);
      if (result != null) {
        map.put(statement, result);
      }
    }
    if (result == null) {
      result = parse(statement, session);
      synchronized (map) {
        map.put(statement, result);
      }
    }
    return result;
  }

  /**
   * parses an SQL statement and returns the corresponding executor
   *
   * @param statement the SQL statement
   * @param session
   * @return the corresponding executor
   * @throws CommandSQLParsingException if the input parameter is not a valid SQL statement
   */
  @Nullable
  protected static SQLStatement parse(String statement, DatabaseSessionInternal session)
      throws CommandSQLParsingException {
    try {
      InputStream is;
      try {
        is =
            new ByteArrayInputStream(
                statement.getBytes(session.getStorageInfo().getConfiguration().getCharset()));
      } catch (UnsupportedEncodingException e2) {
        LogManager.instance()
            .warn(
                StatementCache.class,
                "Unsupported charset for database "
                    + session
                    + " "
                    + session.getStorageInfo().getConfiguration().getCharset());
        is = new ByteArrayInputStream(statement.getBytes());
      }

      YouTrackDBSql osql = null;
      if (session == null) {
        osql = new YouTrackDBSql(is);
      } else {
        try {
          osql = new YouTrackDBSql(is, session.getStorageInfo().getConfiguration().getCharset());
        } catch (UnsupportedEncodingException e2) {
          LogManager.instance()
              .warn(
                  StatementCache.class,
                  "Unsupported charset for database "
                      + session
                      + " "
                      + session.getStorageInfo().getConfiguration().getCharset());
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
    return null;
  }

  /**
   * parses an SQL statement and returns the corresponding executor
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
      var result = osql.parseServerStatement();
      //      result.originalStatement = statement;

      return result;
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
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return;
    }

    synchronized (map) {
      map.clear();
    }
  }
}
