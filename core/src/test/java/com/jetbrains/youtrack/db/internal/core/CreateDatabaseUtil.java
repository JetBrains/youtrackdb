package com.jetbrains.youtrack.db.internal.core;

import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;

/**
 * Used as part of the security test refactoring of the ODB `core` module, cf.
 * https://gist.github.com/tglman/4a24fa59efd88415e765a78487d64366#file-test-migrations-md
 */
public class CreateDatabaseUtil {

  public static final String NEW_ADMIN_PASSWORD = "adminpwd";

  public static final String TYPE_DISK = DatabaseType.DISK.name().toLowerCase(); // "disk";
  public static final String TYPE_MEMORY = DatabaseType.MEMORY.name().toLowerCase(); // "memory";

  public static YouTrackDBAbstract<?, ?> createDatabase(
      final String database, final String url, final String type) {
    var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
            .build();
    var internal = YouTrackDBInternal.fromUrl(url, config);
    final var youTrackDB = internal.newYouTrackDb();
    ;
    if (!youTrackDB.exists(database)) {
      youTrackDB.execute(
          "create database "
              + database
              + " "
              + type
              + " users ( admin identified by '"
              + NEW_ADMIN_PASSWORD
              + "' role admin)");
    }
    return youTrackDB;
  }

  public static void createDatabase(
      final String database, final BasicYouTrackDB<?, ?> youTrackDB, final String type) {
    if (!youTrackDB.exists(database)) {
      youTrackDB.execute(
          "create database "
              + database
              + " "
              + type
              + " users ( admin identified by '"
              + NEW_ADMIN_PASSWORD
              + "' role admin)");
    }
  }
}
