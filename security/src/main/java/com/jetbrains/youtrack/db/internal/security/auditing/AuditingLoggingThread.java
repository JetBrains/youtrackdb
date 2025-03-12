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

import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.security.AuditingOperation;
import com.jetbrains.youtrack.db.internal.core.security.SecuritySystem;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Thread that logs asynchronously.
 */
public class AuditingLoggingThread extends Thread {

  private final BlockingQueue<Map<String, ?>> auditingQueue;
  private volatile boolean running = true;
  private volatile boolean waitForAllLogs = true;
  private final YouTrackDBInternal context;

  private final String className;
  private final SecuritySystem security;

  public AuditingLoggingThread(
      final String iDatabaseName,
      final BlockingQueue<Map<String, ?>> auditingQueue,
      final YouTrackDBInternal context,
      SecuritySystem security) {
    super(
        YouTrackDBEnginesManager.instance().getThreadGroup(),
        "YouTrackDB Auditing Logging Thread - " + iDatabaseName);

    this.auditingQueue = auditingQueue;
    this.context = context;
    this.security = security;
    setDaemon(true);

    // This will create a cluster in the system database for logging auditing events for
    // "databaseName", if it doesn't already
    // exist.
    // server.getSystemDatabase().createCluster(DefaultAuditing.AUDITING_LOG_CLASSNAME,
    // DefaultAuditing.getClusterName(databaseName));

    className = DefaultAuditing.getClassName(iDatabaseName);

    context
        .getSystemDatabase()
        .executeInDBScope(
            session -> {
              Schema schema = session.getMetadata().getSchema();
              if (!schema.existsClass(className)) {
                var clazz = schema.getClass(DefaultAuditing.AUDITING_LOG_CLASSNAME);
                var cls = schema.createClass(className, clazz);
                cls.createIndex(className + ".date", SchemaClass.INDEX_TYPE.NOTUNIQUE,
                    "date");
              }
              return null;
            });
  }

  @Override
  public void run() {

    while (running || waitForAllLogs) {
      try {
        if (!running && auditingQueue.isEmpty()) {
          break;
        }

        var logEntry = auditingQueue.take();
        var systemDatabase = context.getSystemDatabase();
        try (var systemSession = systemDatabase.openSystemDatabaseSession()) {
          systemSession.executeInTx(
              () -> {
                var log = systemSession.newEntity(className);
                log.updateFromMap(logEntry);

                if (security.getSyslog() != null) {
                  var byteOp = AuditingOperation.UNSPECIFIED.getByte();

                  if (log.hasProperty("operation")) {
                    byteOp = log.getProperty("operation");
                  }

                  String username = log.getProperty("user");
                  String message = log.getProperty("note");
                  String dbName = log.getProperty("database");

                  security.getSyslog()
                      .log(AuditingOperation.getByByte(byteOp).toString(), dbName, username,
                          message);
                }
              });
        }
      } catch (InterruptedException e) {
        // IGNORE AND SOFTLY EXIT
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  public void sendShutdown(final boolean iWaitForAllLogs) {
    this.waitForAllLogs = iWaitForAllLogs;
    running = false;
    interrupt();
  }
}
