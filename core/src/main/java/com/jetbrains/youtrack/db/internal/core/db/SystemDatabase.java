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
package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.security.DefaultSecuritySystem;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

public class SystemDatabase {

  public static final String SYSTEM_DB_NAME = "OSystem";

  public static final String SERVER_INFO_CLASS = "ServerInfo";
  public static final String SERVER_ID_PROPERTY = "serverId";

  private final YouTrackDBInternal context;
  private String serverId;

  public SystemDatabase(final YouTrackDBInternal context) {
    this.context = context;
  }

  /**
   * Opens the System Database and returns an DatabaseSessionInternal object. The caller is
   * responsible for retrieving any ThreadLocal-stored database before openSystemDatabase() is
   * called and restoring it after the database is closed.
   */
  public DatabaseSessionInternal openSystemDatabaseSession() {
    if (!exists()) {
      init();
    }
    return context.openNoAuthorization(SYSTEM_DB_NAME);
  }

  public <R> R execute(
      @Nonnull final BiFunction<ResultSet, DatabaseSession, R> callback, final String sql,
      final Object... args) {
    // BYPASS SECURITY
    try (final DatabaseSession session = openSystemDatabaseSession()) {
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
      return session.computeInTx(() -> {
        try (var result = session.query(sql, args)) {
          return callback.apply(result, session);
        }
      });
    }
  }

  public void init() {
    if (!exists()) {
      LogManager.instance()
          .info(this, "Creating the system database '%s' for current server", SYSTEM_DB_NAME);

      var config =
          YouTrackDBConfig.builder()
              .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
              .addGlobalConfigurationParameter(GlobalConfiguration.CLASS_MINIMUM_CLUSTERS, 1)
              .build();
      var type = DatabaseType.PLOCAL;
      if (context.isMemoryOnly()) {
        type = DatabaseType.MEMORY;
      }
      context.create(SYSTEM_DB_NAME, null, null, type, config);
      try (var session = context.openNoAuthorization(SYSTEM_DB_NAME)) {
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
          () -> {
            Entity info;
            if (session.query("select count(*) as count from " + clz.getName()).
                findFirst(r -> r.<Long>getProperty("count") == 0)) {
              info = session.newEntity(SERVER_INFO_CLASS);
            } else {
              info = session.browseClass(clz.getName()).next();
            }
            this.serverId = info.getProperty(SERVER_ID_PROPERTY);
            if (this.serverId == null) {
              this.serverId = UUID.randomUUID().toString();
              info.setProperty(SERVER_ID_PROPERTY, serverId);
            }
          });
    }
  }

  public void executeInDBScope(CallableFunction<Void, DatabaseSessionInternal> callback) {
    executeWithDB(callback);
  }

  public <T> T executeWithDB(CallableFunction<T, DatabaseSessionInternal> callback) {
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
}
