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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RecordHookAbstract;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.session.SessionListener;
import com.jetbrains.youtrack.db.api.session.Transaction;
import com.jetbrains.youtrack.db.internal.common.parser.VariableParser;
import com.jetbrains.youtrack.db.internal.common.parser.VariableParserListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SystemDatabase;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.security.AuditingOperation;
import com.jetbrains.youtrack.db.internal.core.security.SecuritySystem;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Hook to audit database access.
 */
public class AuditingHook extends RecordHookAbstract implements SessionListener {

  private final Map<String, AuditingClassConfig> classes =
      new HashMap<String, AuditingClassConfig>(20);
  private final AuditingLoggingThread auditingThread;

  private final Map<DatabaseSession, List<Map<String, ?>>> operations = new ConcurrentHashMap<>();
  private volatile LinkedBlockingQueue<Map<String, ?>> auditingQueue;
  private final Set<AuditingCommandConfig> commands = new HashSet<AuditingCommandConfig>();
  private boolean onGlobalCreate;
  private boolean onGlobalRead;
  private boolean onGlobalUpdate;
  private boolean onGlobalDelete;
  private AuditingClassConfig defaultConfig = new AuditingClassConfig();
  private AuditingSchemaConfig schemaConfig;
  private Map<String, Object> iConfiguration;

  private static class AuditingCommandConfig {

    public String regex;
    public String message;

    public AuditingCommandConfig(final Map<String, String> cfg) {
      regex = cfg.get("regex");
      message = cfg.get("message");
    }
  }

  private static class AuditingClassConfig {

    public boolean polymorphic = true;
    public boolean onCreateEnabled = false;
    public String onCreateMessage;
    public boolean onReadEnabled = false;
    public String onReadMessage;
    public boolean onUpdateEnabled = false;
    public String onUpdateMessage;
    public boolean onUpdateChanges = true;
    public boolean onDeleteEnabled = false;
    public String onDeleteMessage;

    public AuditingClassConfig() {
    }

    public AuditingClassConfig(final Map<String, Object> cfg) {
      if (cfg.containsKey("polymorphic")) {
        polymorphic = (Boolean) cfg.get("polymorphic");
      }

      // CREATE
      if (cfg.containsKey("onCreateEnabled")) {
        onCreateEnabled = (Boolean) cfg.get("onCreateEnabled");
      }
      if (cfg.containsKey("onCreateMessage")) {
        onCreateMessage = cfg.get("onCreateMessage").toString();
      }

      // READ
      if (cfg.containsKey("onReadEnabled")) {
        onReadEnabled = (Boolean) cfg.get("onReadEnabled");
      }
      if (cfg.containsKey("onReadMessage")) {
        onReadMessage = cfg.get("onReadMessage").toString();
      }

      // UPDATE
      if (cfg.containsKey("onUpdateEnabled")) {
        onUpdateEnabled = (Boolean) cfg.get("onUpdateEnabled");
      }
      if (cfg.containsKey("onUpdateMessage")) {
        onUpdateMessage = cfg.get("onUpdateMessage").toString();
      }
      if (cfg.containsKey("onUpdateChanges")) {
        onUpdateChanges = (Boolean) cfg.get("onUpdateChanges");
      }

      // DELETE
      if (cfg.containsKey("onDeleteEnabled")) {
        onDeleteEnabled = (Boolean) cfg.get("onDeleteEnabled");
      }
      if (cfg.containsKey("onDeleteMessage")) {
        onDeleteMessage = cfg.get("onDeleteMessage").toString();
      }
    }
  }

  // Handles the auditing-config "schema" configuration.
  private static class AuditingSchemaConfig extends AuditingConfig {

    private boolean onCreateClassEnabled = false;
    private final String onCreateClassMessage;

    private boolean onDropClassEnabled = false;
    private final String onDropClassMessage;

    public AuditingSchemaConfig(final Map<String, Object> cfg) {
      if (cfg.containsKey("onCreateClassEnabled")) {
        onCreateClassEnabled = (Boolean) cfg.get("onCreateClassEnabled");
      }

      onCreateClassMessage = (String) cfg.get("onCreateClassMessage");

      if (cfg.containsKey("onDropClassEnabled")) {
        onDropClassEnabled = (Boolean) cfg.get("onDropClassEnabled");
      }

      onDropClassMessage = (String) cfg.get("onDropClassMessage");
    }

    @Override
    public String formatMessage(final AuditingOperation op, final String subject) {
      if (op == AuditingOperation.CREATEDCLASS) {
        return resolveMessage(onCreateClassMessage, "class", subject);
      } else if (op == AuditingOperation.DROPPEDCLASS) {
        return resolveMessage(onDropClassMessage, "class", subject);
      }

      return subject;
    }

    @Override
    public boolean isEnabled(AuditingOperation op) {
      if (op == AuditingOperation.CREATEDCLASS) {
        return onCreateClassEnabled;
      } else if (op == AuditingOperation.DROPPEDCLASS) {
        return onDropClassEnabled;
      }

      return false;
    }
  }

  public AuditingHook(DatabaseSessionInternal session, final Map<String, Object> iConfiguration,
      final SecuritySystem system) {
    this.iConfiguration = iConfiguration;

    onGlobalCreate = onGlobalRead = onGlobalUpdate = onGlobalDelete = false;

    @SuppressWarnings("unchecked")
    var classesCfg = (Map<String, Map<String, Object>>) iConfiguration.get("classes");
    if (classesCfg != null) {
      for (var entry : classesCfg.entrySet()) {
        final var cfg = new AuditingClassConfig(entry.getValue());
        if (entry.getKey().equals("*")) {
          defaultConfig = cfg;
        } else {
          classes.put(entry.getKey(), cfg);
        }

        if (cfg.onCreateEnabled) {
          onGlobalCreate = true;
        }
        if (cfg.onReadEnabled) {
          onGlobalRead = true;
        }
        if (cfg.onUpdateEnabled) {
          onGlobalUpdate = true;
        }
        if (cfg.onDeleteEnabled) {
          onGlobalDelete = true;
        }
      }
    }

    @SuppressWarnings("unchecked") final var commandCfg = (Iterable<Map<String, String>>) iConfiguration.get(
        "commands");

    if (commandCfg != null) {
      for (var cfg : commandCfg) {
        commands.add(new AuditingCommandConfig(cfg));
      }
    }

    @SuppressWarnings("unchecked")
    var schemaCfgDoc = (Map<String, Object>) iConfiguration.get("schema");
    if (schemaCfgDoc != null) {
      schemaConfig = new AuditingSchemaConfig(schemaCfgDoc);
    }

    auditingQueue = new LinkedBlockingQueue<>();
    auditingThread =
        new AuditingLoggingThread(session.getDatabaseName(),
            auditingQueue,
            system.getContext(),
            system);

    auditingThread.start();
  }

  public AuditingHook(final SecuritySystem server) {
    auditingQueue = new LinkedBlockingQueue<>();
    auditingThread =
        new AuditingLoggingThread(
            SystemDatabase.SYSTEM_DB_NAME, auditingQueue, server.getContext(), server);

    auditingThread.start();
  }

  @Override
  public void onAfterTxRollback(Transaction transaction) {
    synchronized (operations) {
      operations.remove(transaction.getSession());
    }
  }

  @Override
  public void onAfterTxCommit(Transaction transaction) {
    List<Map<String, ?>> entries;

    synchronized (operations) {
      entries = operations.remove(transaction.getSession());
    }
    if (entries != null) {
      for (var oDocument : entries) {
        auditingQueue.offer(oDocument);
      }
    }
  }

  public Map<String, Object> getConfiguration() {
    return iConfiguration;
  }

  @Override
  public void onRecordAfterCreate(DatabaseSession session, final DBRecord iRecord) {
    if (!onGlobalCreate) {
      return;
    }

    log(session, AuditingOperation.CREATED, iRecord);
  }

  @Override
  public void onRecordAfterRead(DatabaseSession session, final DBRecord iRecord) {
    if (!onGlobalRead) {
      return;
    }

    log(session, AuditingOperation.LOADED, iRecord);
  }

  @Override
  public void onRecordAfterUpdate(DatabaseSession session, final DBRecord iRecord) {

    if (iRecord instanceof EntityImpl entity) {
      SchemaImmutableClass clazz = null;
      clazz = entity.getImmutableSchemaClass((DatabaseSessionInternal) session);

      if (clazz.isUser() &&
          entity.getDirtyPropertiesBetweenCallbacksInternal(false, false).contains(
              "password")) {
        String name = entity.getProperty("name");
        var message = String.format("The password for user '%s' has been changed", name);
        log(session, AuditingOperation.CHANGED_PWD, session.getDatabaseName(),
            ((DatabaseSessionInternal) session).getCurrentUser(), message);
      }
    }
    if (!onGlobalUpdate) {
      return;
    }

    log(session, AuditingOperation.UPDATED, iRecord);
  }

  @Override
  public void onRecordAfterDelete(DatabaseSession session, final DBRecord iRecord) {
    if (!onGlobalDelete) {
      return;
    }

    log(session, AuditingOperation.DELETED, iRecord);
  }

  protected void log(DatabaseSession db, final AuditingOperation operation,
      final DBRecord iRecord) {
    if (auditingQueue == null)
    // LOGGING THREAD INACTIVE, SKIP THE LOG
    {
      return;
    }

    final var cfg = getAuditConfiguration(iRecord);
    if (cfg == null)
    // SKIP
    {
      return;
    }

    Entity changes = null;
    String note = null;

    switch (operation) {
      case CREATED:
        if (!cfg.onCreateEnabled)
        // SKIP
        {
          return;
        }
        note = cfg.onCreateMessage;
        break;
      case UPDATED:
        if (!cfg.onUpdateEnabled)
        // SKIP
        {
          return;
        }
        note = cfg.onUpdateMessage;

        if (iRecord instanceof EntityImpl entity && cfg.onUpdateChanges) {
          changes = db.getActiveTransaction().newEmbeddedEntity();

          for (var f : entity.getDirtyPropertiesBetweenCallbacksInternal(false, false)) {
            var fieldChanges = db.getActiveTransaction().newEntity();
            fieldChanges.setProperty("from", entity.getOriginalValue(f));
            fieldChanges.setProperty("to", entity.getProperty(f));
            changes.setProperty(f, fieldChanges, PropertyType.EMBEDDED);
          }
        }
        break;
      case DELETED:
        if (!cfg.onDeleteEnabled)
        // SKIP
        {
          return;
        }
        note = cfg.onDeleteMessage;
        break;
    }

    var entity =
        createLogEntry(db, operation, db.getDatabaseName(),
            ((DatabaseSessionInternal) db).getCurrentUser(),
            formatNote(iRecord, note));
    entity.put("record", iRecord.getIdentity());
    if (changes != null) {
      entity.put("changes", changes);
    }

    if (((DatabaseSessionInternal) db).getTransactionInternal().isActive()) {
      synchronized (operations) {
        var entries = operations.computeIfAbsent(db, k -> new ArrayList<>());
        entries.add(entity);
      }
    } else {
      auditingQueue.offer(entity);
    }
  }

  private static String formatNote(final DBRecord iRecord, final String iNote) {
    if (iNote == null) {
      return null;
    }

    return (String)
        VariableParser.resolveVariables(
            iNote,
            "${",
            "}",
            new VariableParserListener() {
              @Override
              public Object resolve(final String iVariable) {
                if (iVariable.startsWith("field.")) {
                  if (iRecord instanceof EntityImpl) {
                    final var fieldName = iVariable.substring("field.".length());
                    return ((EntityImpl) iRecord).getProperty(fieldName);
                  }
                }
                return null;
              }
            });
  }

  private AuditingClassConfig getAuditConfiguration(final DBRecord iRecord) {
    AuditingClassConfig cfg = null;

    if (iRecord instanceof EntityImpl entity) {
      var session = entity.getSession();
      SchemaClass cls = entity.getImmutableSchemaClass(session);

      if (cls != null) {
        if (cls.getName().equals(DefaultAuditing.AUDITING_LOG_CLASSNAME))
        // SKIP LOG CLASS
        {
          return null;
        }

        cfg = classes.get(cls.getName());

        // BROWSE SUPER CLASSES UP TO ROOT
        var classesToCheck = new HashSet<SchemaClass>();
        classesToCheck.add(cls);

        configLoop:
        while (cfg == null) {
          var newClassesToCheck = new HashSet<SchemaClass>();

          for (var clz : classesToCheck) {
            cfg = classes.get(clz.getName());
            if (cfg != null && !cfg.polymorphic) {
              // NOT POLYMORPHIC: IGNORE IT AND EXIT FROM THE LOOP
              cfg = null;
              break configLoop;
            }

            var superClasses = cls.getSuperClasses();
            newClassesToCheck.addAll(superClasses);
          }

          classesToCheck = newClassesToCheck;
        }
      }
    }

    if (cfg == null)
    // ASSIGN DEFAULT CFG (*)
    {
      cfg = defaultConfig;
    }

    return cfg;
  }

  public void shutdown(final boolean waitForAllLogs) {
    if (auditingThread != null) {
      auditingThread.sendShutdown(waitForAllLogs);
      auditingQueue = null;
    }
  }

  protected void logClass(DatabaseSessionInternal db, final AuditingOperation operation,
      final String note) {
    final var user = db.getCurrentUser();

    var entity = createLogEntry(db, operation, db.getDatabaseName(), user, note);
    auditingQueue.offer(entity);
  }

  protected void logClass(DatabaseSessionInternal db,
      final AuditingOperation operation, final SchemaClass cls) {
    if (schemaConfig != null && schemaConfig.isEnabled(operation)) {
      logClass(db, operation, schemaConfig.formatMessage(operation, cls.getName()));
    }
  }

  public void onCreateClass(DatabaseSession db, SchemaClass iClass) {
    logClass((DatabaseSessionInternal) db, AuditingOperation.CREATEDCLASS, iClass);
  }

  public void onDropClass(DatabaseSession db, SchemaClass iClass) {
    logClass((DatabaseSessionInternal) db, AuditingOperation.DROPPEDCLASS, iClass);
  }

  public void log(
      DatabaseSession db, final AuditingOperation operation,
      final String dbName,
      SecurityUser user,
      final String message) {
    if (auditingQueue != null) {
      auditingQueue.offer(createLogEntry(db, operation, dbName, user, message));
    }
  }

  private static Map<String, Object> createLogEntry(
      DatabaseSession session, final AuditingOperation operation,
      final String dbName,
      SecurityUser user,
      final String message) {
    final var entity = new HashMap<String, Object>();

    entity.put("date", System.currentTimeMillis());
    entity.put("operation", operation.getByte());

    if (user != null) {
      entity.put("user", user.getName((DatabaseSessionInternal) session));
      entity.put("userType", user.getUserType());
    }

    if (message != null) {
      entity.put("note", message);
    }

    if (dbName != null) {
      entity.put("database", dbName);
    }

    return entity;
  }
}
