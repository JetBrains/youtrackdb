/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrack.db.internal.security.auditing;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.List;
import java.util.Map;

public class SystemDBImporter extends Thread {

  private boolean enabled = false;
  private List<String> databaseList;
  private final String auditingClass = "AuditingLog";
  private int limit = 1000; // How many records to import during each iteration.
  private int sleepPeriod = 1000; // How long to sleep (in ms) after importing 'limit' records.
  private final YouTrackDBInternal context;
  private boolean isRunning = true;

  public boolean isEnabled() {
    return enabled;
  }

  public SystemDBImporter(final YouTrackDBInternal context, final Map<String, Object> jsonConfig) {
    super(YouTrackDBEnginesManager.instance().getThreadGroup(),
        "YouTrackDB Auditing Log Importer Thread");

    this.context = context;

    try {
      if (jsonConfig.containsKey("enabled")) {
        enabled = (Boolean) jsonConfig.get("enabled");
      }

      if (jsonConfig.containsKey("databases")) {
        //noinspection unchecked
        databaseList = (List<String>) jsonConfig.get("databases");
      }

      if (jsonConfig.containsKey("limit")) {
        limit = (Integer) jsonConfig.get("limit");
      }

      if (jsonConfig.containsKey("sleepPeriod")) {
        sleepPeriod = (Integer) jsonConfig.get("sleepPeriod");
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "SystemDBImporter()", ex);
    }

    setDaemon(true);
  }

  public void shutdown() {
    isRunning = false;
    interrupt();
  }

  @Override
  public void run() {
    try {
      if (enabled && databaseList != null) {
        for (var dbName : databaseList) {
          if (!isRunning) {
            break;
          }

          importDB(dbName);
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "run()", ex);
    }
  }

  private void importDB(final String dbName) {
    DatabaseSessionInternal db = null;
    DatabaseSessionInternal sysdb = null;

    try {
      db = context.openNoAuthorization(dbName);
      db.setProperty(DefaultAuditing.IMPORTER_FLAG, true);

      if (db == null) {
        LogManager.instance()
            .error(this, "importDB() Unable to import auditing log for database: %s", null, dbName);
        return;
      }

      sysdb = context.getSystemDatabase().openSystemDatabaseSession();

      LogManager.instance()
          .info(this, "Starting import of the auditing log from database: %s", dbName);

      var totalImported = 0;

      // We modify the query after the first iteration, using the last imported RID as a starting
      // point.
      var sql = String.format("select from %s order by @rid limit ?", auditingClass);

      while (isRunning) {
        db.activateOnCurrentThread();
        // Retrieve the auditing log records from the local database.
        var result = db.query(sql, limit);

        var count = 0;

        String lastRID = null;

        while (result.hasNext()) {
          var entity = result.next();
          try {
            Entity copy = new EntityImpl(db);

            if (entity.hasProperty("date")) {
              copy.setProperty("date", entity.getProperty("date"), PropertyType.DATETIME);
            }

            if (entity.hasProperty("operation")) {
              copy.setProperty("operation", entity.getProperty("operation"), PropertyType.BYTE);
            }

            if (entity.hasProperty("record")) {
              copy.setProperty("record", entity.getProperty("record"), PropertyType.LINK);
            }

            if (entity.hasProperty("changes")) {
              copy.setProperty("changes", entity.getProperty("changes"), PropertyType.EMBEDDED);
            }

            if (entity.hasProperty("note")) {
              copy.setProperty("note", entity.getProperty("note"), PropertyType.STRING);
            }

            try {
              // Convert user RID to username.
              if (entity.hasProperty("user")) {
                // entity.field("user") will throw an exception if the user's RID is not found.
                EntityImpl userDoc = entity.getProperty("user");
                final String username = userDoc.getProperty("name");

                if (username != null) {
                  copy.setProperty("user", username);
                }
              }
            } catch (Exception userEx) {
            }

            // Add the database name as part of the log stored in the system db.
            copy.setProperty("database", dbName);

            sysdb.activateOnCurrentThread();
            lastRID = entity.getIdentity().toString();

            count++;

            db.activateOnCurrentThread();
            db.delete(entity.asEntity());
          } catch (Exception ex) {
            LogManager.instance().error(this, "importDB()", ex);
          }
        }

        totalImported += count;

        LogManager.instance()
            .info(
                this,
                "Imported %d auditing log %s from database: %s",
                count,
                count == 1 ? "record" : "records",
                dbName);

        Thread.sleep(sleepPeriod);

        if (lastRID != null) {
          sql =
              String.format(
                  "select from %s where @rid > %s order by @rid limit ?", auditingClass, lastRID);
        }
      }

      LogManager.instance()
          .info(
              this,
              "Completed importing of %d auditing log %s from database: %s",
              totalImported,
              totalImported == 1 ? "record" : "records",
              dbName);

    } catch (Exception ex) {
      LogManager.instance().error(this, "importDB()", ex);
    } finally {
      if (sysdb != null) {
        sysdb.activateOnCurrentThread();
        sysdb.close();
      }

      if (db != null) {
        db.activateOnCurrentThread();
        db.close();
      }
    }
  }
}
