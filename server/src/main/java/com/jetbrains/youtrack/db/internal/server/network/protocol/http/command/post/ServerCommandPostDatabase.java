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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.config.StorageEntryConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.engine.local.EngineLocalPaginated;
import com.jetbrains.youtrack.db.internal.core.engine.memory.EngineMemory;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserIml;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONWriter;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.OHttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ServerCommandPostDatabase extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"POST|database/*"};

  public ServerCommandPostDatabase() {
    super("database.create");
  }

  @Override
  public boolean execute(final OHttpRequest iRequest, HttpResponse iResponse) throws Exception {
    String[] urlParts = checkSyntax(iRequest.getUrl(), 3, "Syntax error: database/<db>/<type>");

    iRequest.getData().commandInfo = "Create database";

    final String databaseName = urlParts[1];
    final String storageMode = urlParts[2];
    String url = getStoragePath(databaseName, storageMode);
    final String type = urlParts.length > 3 ? urlParts[3] : "document";

    boolean createAdmin = false;
    String adminPwd = null;
    if (iRequest.getContent() != null && !iRequest.getContent().isEmpty()) {
      // CONTENT REPLACES TEXT
      if (iRequest.getContent().startsWith("{")) {
        // JSON PAYLOAD

        var objectMapper = new ObjectMapper();
        var result = objectMapper.readTree(iRequest.getContent());

        if (result.has("adminPassword")) {
          createAdmin = true;
          adminPwd = result.findValue("adminPassword").asText();
        }
      }
    }

    if (url != null) {
      if (server.existsDatabase(databaseName)) {
        sendJsonError(
            iResponse,
            HttpUtils.STATUS_CONFLICT_CODE,
            HttpUtils.STATUS_CONFLICT_DESCRIPTION,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Database '" + databaseName + "' already exists.",
            null);
      } else {
        server.createDatabase(
            databaseName, DatabaseType.valueOf(storageMode.toUpperCase(Locale.ENGLISH)), null);

        try (DatabaseSessionInternal database =
            server.openDatabase(databaseName, serverUser, serverPassword, null)) {

          if (createAdmin) {
            try {
              database.begin();
              database.command("CREATE USER admin IDENTIFIED BY ? ROLE admin", adminPwd);
              database.commit();
            } catch (Exception e) {
              LogManager.instance()
                  .warn(this, "Could not create admin user for database " + databaseName, e);
            }
          }

          sendDatabaseInfo(iRequest, iResponse, database);
        }
      }
    } else {
      throw new CommandExecutionException(
          "The '" + storageMode + "' storage mode does not exists.");
    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }

  protected String getStoragePath(final String databaseName, final String iStorageMode) {
    if (iStorageMode.equals(EngineLocalPaginated.NAME)) {
      return iStorageMode + ":" + server.getDatabaseDirectory() + databaseName;
    } else if (iStorageMode.equals(EngineMemory.NAME)) {
      return iStorageMode + ":" + databaseName;
    }

    return null;
  }

  protected void sendDatabaseInfo(
      final OHttpRequest iRequest, final HttpResponse iResponse,
      final DatabaseSessionInternal db)
      throws IOException {
    final StringWriter buffer = new StringWriter();
    final JSONWriter json = new JSONWriter(buffer);

    json.beginObject();

    if (db.getMetadata().getSchema().getClasses(db) != null) {
      json.beginCollection(1, false, "classes");
      Set<String> exportedNames = new HashSet<String>();
      for (SchemaClass cls : db.getMetadata().getSchema().getClasses(db)) {
        if (!exportedNames.contains(cls.getName())) {
          try {
            exportClass(db, json, (SchemaClassInternal) cls);
            exportedNames.add(cls.getName());
          } catch (Exception e) {
            LogManager.instance().error(this, "Error on exporting class '" + cls + "'", e);
          }
        }
      }
      json.endCollection(1, true);
    }

    if (db.getClusterNames() != null) {
      json.beginCollection(1, false, "clusters");
      for (String clusterName : db.getClusterNames()) {
        final int clusterId = db.getClusterIdByName(clusterName);
        if (clusterId < 0) {
          continue;
        }

        try {
          json.beginObject(2, true, null);
          json.writeAttribute(3, false, "id", clusterId);
          json.writeAttribute(3, false, "name", clusterName);
          json.writeAttribute(3, false, "records", db.countClusterElements(clusterId));
          json.writeAttribute(3, false, "size", "-");
          json.writeAttribute(3, false, "filled", "-");
          json.writeAttribute(3, false, "maxSize", "-");
          json.writeAttribute(3, false, "files", "-");
        } catch (Exception e) {
          json.writeAttribute(3, false, "records", "? (Unauthorized)");
        }
        json.endObject(2, false);
      }
      json.endCollection(1, true);
    }

    if (db.geCurrentUser() != null) {
      json.writeAttribute(1, false, "currentUser", db.geCurrentUser().getName(db));
    }

    json.beginCollection(1, false, "users");
    SecurityUserIml user;
    for (EntityImpl entity : db.getMetadata().getSecurity().getAllUsers()) {
      user = new SecurityUserIml(db, entity);
      json.beginObject(2, true, null);
      json.writeAttribute(3, false, "name", user.getName(db));
      json.writeAttribute(
          3,
          false,
          "roles",
          user.getRoles() != null ? Arrays.toString(user.getRoles().toArray()) : "null");
      json.endObject(2, false);
    }
    json.endCollection(1, true);

    json.beginCollection(1, true, "roles");
    Role role;
    for (EntityImpl entity : db.getMetadata().getSecurity().getAllRoles()) {
      role = new Role(db, entity);
      json.beginObject(2, true, null);
      json.writeAttribute(3, false, "name", role.getName(db));
      json.writeAttribute(3, false, "mode", role.getMode().toString());

      json.beginCollection(3, true, "rules");
      for (Map.Entry<String, Byte> rule : role.getRules().entrySet()) {
        json.beginObject(4);
        json.writeAttribute(4, true, "name", rule.getKey());
        json.writeAttribute(4, false, "create", role.allow(rule.getKey(), Role.PERMISSION_CREATE));
        json.writeAttribute(4, false, "read", role.allow(rule.getKey(), Role.PERMISSION_READ));
        json.writeAttribute(4, false, "update", role.allow(rule.getKey(), Role.PERMISSION_UPDATE));
        json.writeAttribute(4, false, "delete", role.allow(rule.getKey(), Role.PERMISSION_DELETE));
        json.endObject(4, true);
      }
      json.endCollection(3, false);

      json.endObject(2, true);
    }
    json.endCollection(1, true);

    json.beginObject(1, true, "config");

    json.beginCollection(2, true, "values");
    json.writeObjects(
        3,
        true,
        null,
        new Object[]{
            "name", "dateFormat", "value", db.getStorage().getConfiguration().getDateFormat()
        },
        new Object[]{
            "name", "dateTimeFormat", "value",
            db.getStorage().getConfiguration().getDateTimeFormat()
        },
        new Object[]{
            "name", "localeCountry", "value", db.getStorage().getConfiguration().getLocaleCountry()
        },
        new Object[]{
            "name", "localeLanguage", "value",
            db.getStorage().getConfiguration().getLocaleLanguage()
        },
        new Object[]{
            "name", "definitionVersion", "value", db.getStorage().getConfiguration().getVersion()
        });
    json.endCollection(2, true);

    json.beginCollection(2, true, "properties");
    if (db.getStorage().getConfiguration().getProperties() != null) {
      for (StorageEntryConfiguration entry : db.getStorage().getConfiguration().getProperties()) {
        if (entry != null) {
          json.beginObject(3, true, null);
          json.writeAttribute(4, false, "name", entry.name);
          json.writeAttribute(4, false, "value", entry.value);
          json.endObject(3, true);
        }
      }
    }
    json.endCollection(2, true);

    json.endObject(1, true);
    json.endObject();
    json.flush();

    iResponse.send(
        HttpUtils.STATUS_OK_CODE,
        HttpUtils.STATUS_OK_DESCRIPTION,
        HttpUtils.CONTENT_JSON,
        buffer.toString(),
        null);
  }

  protected void exportClass(
      final DatabaseSessionInternal db, final JSONWriter json, final SchemaClassInternal cls)
      throws IOException {
    json.beginObject(2, true, null);
    json.writeAttribute(3, true, "name", cls.getName());
    json.writeAttribute(
        3, true, "superClass", cls.getSuperClass(db) != null ? cls.getSuperClass(db).getName() : "");
    json.writeAttribute(3, true, "alias", cls.getShortName());
    json.writeAttribute(3, true, "clusters", cls.getClusterIds());
    json.writeAttribute(3, true, "clusterSelection", cls.getClusterSelectionStrategyName());
    try {
      json.writeAttribute(3, false, "records", db.countClass(cls.getName()));
    } catch (SecurityAccessException e) {
      json.writeAttribute(3, false, "records", "? (Unauthorized)");
    }

    if (cls.properties(db) != null && cls.properties(db).size() > 0) {
      json.beginCollection(3, true, "properties");
      for (final SchemaProperty prop : cls.properties(db)) {
        json.beginObject(4, true, null);
        json.writeAttribute(4, true, "name", prop.getName());
        if (prop.getLinkedClass(db) != null) {
          json.writeAttribute(4, true, "linkedClass", prop.getLinkedClass(db).getName());
        }
        if (prop.getLinkedType() != null) {
          json.writeAttribute(4, true, "linkedType", prop.getLinkedType().toString());
        }
        json.writeAttribute(4, true, "type", prop.getType().toString());
        json.writeAttribute(4, true, "mandatory", prop.isMandatory());
        json.writeAttribute(4, true, "readonly", prop.isReadonly());
        json.writeAttribute(4, true, "notNull", prop.isNotNull());
        json.writeAttribute(4, true, "min", prop.getMin());
        json.writeAttribute(4, true, "max", prop.getMax());
        json.endObject(3, true);
      }
      json.endCollection(1, true);
    }

    final Set<Index> indexes = cls.getIndexesInternal(db);
    if (!indexes.isEmpty()) {
      json.beginCollection(3, true, "indexes");
      for (final Index index : indexes) {
        json.beginObject(4, true, null);
        json.writeAttribute(4, true, "name", index.getName());
        json.writeAttribute(4, true, "type", index.getType());

        final IndexDefinition indexDefinition = index.getDefinition();
        if (indexDefinition != null && !indexDefinition.getFields().isEmpty()) {
          json.writeAttribute(4, true, "fields", indexDefinition.getFields());
        }
        json.endObject(3, true);
      }
      json.endCollection(1, true);
    }

    json.endObject(1, false);
  }
}
