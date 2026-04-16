/**
 * Copyright (c) 2024 JetBrains s.r.o. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrackdb.ycsb.binding;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.ycsb.ByteIterator;
import com.jetbrains.youtrackdb.ycsb.DB;
import com.jetbrains.youtrackdb.ycsb.DBException;
import com.jetbrains.youtrackdb.ycsb.Status;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YouTrackDB YCSB driver using YQL (YouTrackDB Query Language) for all
 * database operations. Each YCSB client thread gets its own instance of
 * this class, but all instances share a single {@link YouTrackDB} connection
 * and {@link YTDBGraphTraversalSource}.
 *
 * <p>The driver creates a "usertable" vertex class with a unique index on
 * the {@code ycsb_key} property and 10 string field properties
 * ({@code field0}..{@code field9}).
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code ytdb.url} — database directory path (default: {@code ./target/ycsb-db})</li>
 *   <li>{@code ytdb.dbname} — database name (default: {@code ycsb})</li>
 *   <li>{@code ytdb.user} — database user (default: {@code admin})</li>
 *   <li>{@code ytdb.password} — database password (default: {@code admin})</li>
 *   <li>{@code ytdb.newdb} — drop and recreate the database on init (default: {@code true})</li>
 *   <li>{@code ytdb.dbtype} — database type: DISK or MEMORY (default: {@code DISK})</li>
 * </ul>
 */
public class YouTrackDBYqlClient extends DB {

  private static final Logger logger = LoggerFactory.getLogger(YouTrackDBYqlClient.class);

  static final String URL_PROPERTY = "ytdb.url";
  static final String URL_DEFAULT = "./target/ycsb-db";

  static final String DB_NAME_PROPERTY = "ytdb.dbname";
  static final String DB_NAME_DEFAULT = "ycsb";

  static final String USER_PROPERTY = "ytdb.user";
  static final String USER_DEFAULT = "admin";

  static final String PASSWORD_PROPERTY = "ytdb.password";
  static final String PASSWORD_DEFAULT = "admin";

  static final String NEW_DB_PROPERTY = "ytdb.newdb";
  static final String NEW_DB_DEFAULT = "true";

  static final String DB_TYPE_PROPERTY = "ytdb.dbtype";
  static final String DB_TYPE_DEFAULT = "DISK";

  private static final int FIELD_COUNT = 10;

  private static final ReentrantLock initLock = new ReentrantLock();
  private static final AtomicInteger clientCount = new AtomicInteger(0);

  private static volatile YouTrackDB dbInstance;
  private static volatile YTDBGraphTraversalSource traversalSource;

  @Override
  public void init() throws DBException {
    Properties props = getProperties();
    String url = props.getProperty(URL_PROPERTY, URL_DEFAULT);
    String dbName = props.getProperty(DB_NAME_PROPERTY, DB_NAME_DEFAULT);
    String user = props.getProperty(USER_PROPERTY, USER_DEFAULT);
    String password = props.getProperty(PASSWORD_PROPERTY, PASSWORD_DEFAULT);
    boolean newDb = Boolean.parseBoolean(
        props.getProperty(NEW_DB_PROPERTY, NEW_DB_DEFAULT));
    DatabaseType dbType = DatabaseType.valueOf(
        props.getProperty(DB_TYPE_PROPERTY, DB_TYPE_DEFAULT));

    initLock.lock();
    try {
      if (dbInstance == null) {
        logger.info("Initializing YouTrackDB at {} with database '{}' (type={})",
            url, dbName, dbType);

        YouTrackDB db = YourTracks.instance(url);
        boolean success = false;
        YTDBGraphTraversalSource g = null;
        try {
          if (newDb && db.exists(dbName)) {
            logger.info("Dropping existing database '{}'", dbName);
            db.drop(dbName);
          }

          if (!db.exists(dbName)) {
            db.create(dbName, dbType, user, password, "admin");
            logger.info("Created database '{}'", dbName);
          }

          g = db.openTraversal(dbName, user, password);
          createSchema(g);

          dbInstance = db;
          traversalSource = g;
          success = true;
        } finally {
          if (!success) {
            if (g != null) {
              try {
                g.close();
              } catch (Exception ignored) {
              }
            }
            try {
              db.close();
            } catch (Exception ignored) {
            }
          }
        }
      }
      clientCount.incrementAndGet();
    } catch (Exception e) {
      throw new DBException("Failed to initialize YouTrackDB", e);
    } finally {
      initLock.unlock();
    }
  }

  @Override
  public void cleanup() throws DBException {
    initLock.lock();
    try {
      if (clientCount.decrementAndGet() == 0) {
        logger.info("Last client thread cleaning up — closing YouTrackDB");
        try {
          if (traversalSource != null) {
            traversalSource.close();
          }
        } finally {
          traversalSource = null;
          try {
            if (dbInstance != null) {
              dbInstance.close();
            }
          } finally {
            dbInstance = null;
          }
        }
      }
    } catch (Exception e) {
      throw new DBException("Failed to cleanup YouTrackDB", e);
    } finally {
      initLock.unlock();
    }
  }

  @Override
  public Status insert(String table, String key, Map<String, ByteIterator> values) {
    try {
      traversalSource.executeInTx(tx -> {
        var g = (YTDBGraphTraversalSource) tx;

        // Build: CREATE VERTEX usertable SET ycsb_key = :key, field0 = :f0, ...
        StringBuilder sql = new StringBuilder("CREATE VERTEX ");
        sql.append(table).append(" SET ycsb_key = :key");

        // 2 args per entry (name, value) + 2 for key
        Object[] args = new Object[2 + values.size() * 2];
        args[0] = "key";
        args[1] = key;

        int argIdx = 2;
        for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
          String fieldName = entry.getKey();
          sql.append(", ").append(fieldName).append(" = :").append(fieldName);
          args[argIdx++] = fieldName;
          args[argIdx++] = entry.getValue().toString();
        }

        g.yql(sql.toString(), args).iterate();
      });
      return Status.OK;
    } catch (Exception e) {
      logger.error("Insert failed for key {}", key, e);
      return Status.ERROR;
    }
  }

  @Override
  public Status read(String table, String key, Set<String> fields,
      Map<String, ByteIterator> result) {
    return Status.NOT_IMPLEMENTED;
  }

  @Override
  public Status scan(String table, String startkey, int recordcount, Set<String> fields,
      Vector<HashMap<String, ByteIterator>> result) {
    return Status.NOT_IMPLEMENTED;
  }

  @Override
  public Status update(String table, String key, Map<String, ByteIterator> values) {
    return Status.NOT_IMPLEMENTED;
  }

  @Override
  public Status delete(String table, String key) {
    return Status.NOT_IMPLEMENTED;
  }

  /**
   * Returns the shared traversal source. Package-private for test access.
   */
  static YTDBGraphTraversalSource getTraversalSource() {
    return traversalSource;
  }

  /**
   * Creates the usertable schema: vertex class, key property with unique
   * index, and 10 string field properties.
   */
  private static void createSchema(YTDBGraphTraversalSource g) {
    g.executeInTx(tx -> {
      var tg = (YTDBGraphTraversalSource) tx;
      tg.yql("CREATE CLASS usertable IF NOT EXISTS EXTENDS V").iterate();
      tg.yql("CREATE PROPERTY usertable.ycsb_key IF NOT EXISTS STRING").iterate();
      for (int i = 0; i < FIELD_COUNT; i++) {
        tg.yql("CREATE PROPERTY usertable.field" + i + " IF NOT EXISTS STRING").iterate();
      }
      tg.yql(
          "CREATE INDEX usertable.ycsb_key IF NOT EXISTS ON usertable (ycsb_key) UNIQUE")
          .iterate();
    });
    logger.info("Schema created: usertable with {} field properties and unique key index",
        FIELD_COUNT);
  }
}
