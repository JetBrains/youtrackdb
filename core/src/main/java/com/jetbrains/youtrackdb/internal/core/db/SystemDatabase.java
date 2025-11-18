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
package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import com.jetbrains.youtrackdb.internal.core.security.DefaultSecuritySystem;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

public class SystemDatabase {

  public static final String SYSTEM_DB_NAME = "OSystem";

  public static final String SERVER_INFO_CLASS = "ServerInfo";
  public static final String SERVER_ID_PROPERTY = "serverId";

  private final YouTrackDBInternalEmbedded context;
  private final boolean enabled;
  private String serverId;

  public SystemDatabase(final YouTrackDBInternalEmbedded context) {
    this.context = context;
    this.enabled = context.getConfiguration().getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED);
  }

  /**
   * Opens the System Database and returns an DatabaseSessionInternal object. The caller is
   * responsible for retrieving any ThreadLocal-stored database before openSystemDatabase() is
   * called and restoring it after the database is closed.
   */
  public DatabaseSessionEmbedded openSystemDatabaseSession() {
    checkIfEnabled();
    if (!exists()) {
      init();
    }

    return context.openNoAuthorization(SYSTEM_DB_NAME);
  }

  public <R> R execute(
      @Nonnull final BiFunction<ResultSet, DatabaseSession, R> callback, final String sql,
      final Object... args) {
    // BYPASS SECURITY
    try (final var session = openSystemDatabaseSession()) {
      try (var result = session.execute(sql, args)) {
        return callback.apply(result, session);
      }
    }
  }

  public <R> R query(
      @Nonnull final BiFunction<ResultSet, DatabaseSession, R> callback, final String sql,
      final Object... args) {
    // BYPASS SECURITY
    try (final DatabaseSession session = openSystemDatabaseSession()) {
      return session.computeInTx(transaction -> {
        try (var result = transaction.query(sql, args)) {
          return callback.apply(result, session);
        }
      });
    }
  }

  public void init() {
    checkIfEnabled();
    if (!exists()) {
      LogManager.instance()
          .info(this, "Creating the system database '%s' for current server", SYSTEM_DB_NAME);

      var config =
          YouTrackDBConfig.builder()
              .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
              .addGlobalConfigurationParameter(GlobalConfiguration.CLASS_COLLECTIONS_COUNT, 1)
              .build();
      var type = DatabaseType.DISK;
      if (context.isMemoryOnly()) {
        type = DatabaseType.MEMORY;
      }
      context.create(SYSTEM_DB_NAME, null, null, type, config);
      try (var session = (DatabaseSessionInternal) context.openNoAuthorization(SYSTEM_DB_NAME)) {
        DefaultSecuritySystem.createSystemRoles(session);
      }
    }
    checkServerId();

  }

  private synchronized void checkServerId() {
    try (var session = openSystemDatabaseSession()) {
      var clazz = session.getClass(SERVER_INFO_CLASS);
      if (clazz == null) {
        clazz = session.createClass(SERVER_INFO_CLASS);
      }
      var clz = clazz;
      session.executeInTx(
          transaction -> {
            Entity info;
            if (session.query("select count(*) as count from " + clz.getName()).
                findFirst(r -> r.<Long>getProperty("count") == 0)) {
              info = session.newEntity(SERVER_INFO_CLASS);
            } else {
              try (var it = session.browseClass(clz.getName())) {
                info = it.next();
              }
            }
            this.serverId = info.getProperty(SERVER_ID_PROPERTY);
            if (this.serverId == null) {
              this.serverId = UUID.randomUUID().toString();
              info.setProperty(SERVER_ID_PROPERTY, serverId);
            }
          });
    }
  }

  public void executeInDBScope(CallableFunction<Void, DatabaseSessionEmbedded> callback) {
    executeWithDB(callback);
  }

  public <T> T executeWithDB(CallableFunction<T, DatabaseSessionEmbedded> callback) {
    try (final var session = openSystemDatabaseSession()) {
      return callback.call(session);
    }
  }

  public boolean exists() {
    return context.exists(SYSTEM_DB_NAME, null, null);
  }

  public String getServerId() {
    return serverId;
  }

  private void checkIfEnabled() {
    if (!enabled) {
      throw new DatabaseException("System database is disabled");
    }
  }
}
