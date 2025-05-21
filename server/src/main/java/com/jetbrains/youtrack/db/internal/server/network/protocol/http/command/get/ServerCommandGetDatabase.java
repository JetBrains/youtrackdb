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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONWriter;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ServerCommandGetDatabase extends ServerCommandGetConnect {

  private static final String[] NAMES = {"GET|database/*"};

  public static void exportClass(
      final DatabaseSessionInternal session, final JSONWriter json, final SchemaClassInternal cls)
      throws IOException {
    json.beginObject();
    json.writeAttribute(session, "name", cls.getName());
    json.beginCollection(session, "superClasses");
    var i = 0;
    for (var oClass : cls.getSuperClasses()) {
      json.write((i > 0 ? "," : "") + "\"" + oClass.getName() + "\"");
      i++;
    }
    json.endCollection();

    json.writeAttribute(session, "abstract", cls.isAbstract());
    json.writeAttribute(session, "strictmode", cls.isStrictMode());
    json.writeAttribute(session, "collections", cls.getCollectionIds());
    if (cls instanceof SchemaClassImpl) {
      final var custom = ((SchemaClassImpl) cls).getCustomInternal();
      if (custom != null && !custom.isEmpty()) {
        json.writeAttribute(session, "custom", custom);
      }
    }

    try {
      json.writeAttribute(session, "records", session.countClass(cls.getName()));
    } catch (SecurityAccessException e) {
      json.writeAttribute(session, "records", "? (Unauthorized)");
    } catch (Exception e) {
      json.writeAttribute(session, "records", "? (Error)");
    }

    if (cls.getProperties() != null && !cls.getProperties().isEmpty()) {
      json.beginCollection(session, "properties");
      for (final var prop : cls.getProperties()) {
        json.beginObject();
        json.writeAttribute(session, "name", prop.getName());
        if (prop.getLinkedClass() != null) {
          json.writeAttribute(session, "linkedClass",
              prop.getLinkedClass().getName());
        }
        if (prop.getLinkedType() != null) {
          json.writeAttribute(session, "linkedType", prop.getLinkedType().toString());
        }
        json.writeAttribute(session, "type", prop.getType().toString());
        json.writeAttribute(session, "mandatory", prop.isMandatory());
        json.writeAttribute(session, "readonly", prop.isReadonly());
        json.writeAttribute(session, "notNull", prop.isNotNull());
        json.writeAttribute(session, "min", prop.getMin());
        json.writeAttribute(session, "max", prop.getMax());
        json.writeAttribute(session, "regexp", prop.getRegexp());
        json.writeAttribute(session,
            "collate",
            prop.getCollate() != null ? prop.getCollate().getName() : "default");
        json.writeAttribute(session, "defaultValue", prop.getDefaultValue());

        if (prop instanceof SchemaPropertyImpl) {
          final var custom = ((SchemaPropertyImpl) prop).getCustomInternal();
          if (custom != null && !custom.isEmpty()) {
            json.writeAttribute(session, "custom", custom);
          }
        }

        json.endObject();
      }
      json.endCollection();
    }

    final var indexes = cls.getIndexesInternal();
    if (!indexes.isEmpty()) {
      json.beginCollection(session, "indexes");
      for (final var index : indexes) {
        json.beginObject();
        json.writeAttribute(session, "name", index.getName());
        json.writeAttribute(session, "type", index.getType());

        final var indexDefinition = index.getDefinition();
        if (indexDefinition != null && !indexDefinition.getFields().isEmpty()) {
          json.writeAttribute(session, "fields", indexDefinition.getFields());
        }
        json.endObject();
      }
      json.endCollection();
    }

    json.endObject();
  }

  @Override
  public void configure(final YouTrackDBServer server) {
    super.configure(server);
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    var urlParts = checkSyntax(iRequest.getUrl(), 2, "Syntax error: database/<database>");

    iRequest.getData().commandInfo = "Database info";
    iRequest.getData().commandDetail = urlParts[1];

    exec(iRequest, iResponse, urlParts);
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }

  protected void exec(
      final HttpRequest iRequest, final HttpResponse iResponse, final String[] urlParts)
      throws InterruptedException, IOException {
    DatabaseSessionInternal session = null;
    try {
      if (urlParts.length > 2) {
        session = server.openSession(urlParts[1], urlParts[2], urlParts[3]);
      } else {
        session = getProfiledDatabaseSessionInstance(iRequest);
      }

      final var buffer = new StringWriter();
      final var json = new JSONWriter(buffer);
      json.beginObject();

      json.beginObject("server");
      json.writeAttribute(session, "version", YouTrackDBConstants.getRawVersion());
      if (YouTrackDBConstants.getBuildNumber() != null) {
        json.writeAttribute(session, "build", YouTrackDBConstants.getBuildNumber());
      }
      json.writeAttribute(session, "osName", System.getProperty("os.name"));
      json.writeAttribute(session, "osVersion", System.getProperty("os.version"));
      json.writeAttribute(session, "osArch", System.getProperty("os.arch"));
      json.writeAttribute(session, "javaVendor", System.getProperty("java.vm.vendor"));
      json.writeAttribute(session, "javaVersion", System.getProperty("java.vm.version"));

      json.beginCollection(session, "conflictStrategies");

      var strategies =
          YouTrackDBEnginesManager.instance().getRecordConflictStrategy()
              .getRegisteredImplementationNames();

      var i = 0;
      for (var strategy : strategies) {
        json.write((i > 0 ? "," : "") + "\"" + strategy + "\"");
        i++;
      }
      json.endCollection();

      json.beginCollection(session, "collectionSelectionStrategies");
      var collectionSelectionStrategies =
          session.getMetadata()
              .getImmutableSchemaSnapshot()
              .getCollectionSelectionFactory()
              .getRegisteredNames();
      var j = 0;
      for (var strategy : collectionSelectionStrategies) {
        json.write((j > 0 ? "," : "") + "\"" + strategy + "\"");
        j++;
      }
      json.endCollection();

      json.endObject();

      if (session.getMetadata().getImmutableSchemaSnapshot().getClasses() != null) {
        json.beginCollection(session, "classes");
        List<String> classNames = new ArrayList<String>();

        for (var cls : session.getMetadata().getImmutableSchemaSnapshot().getClasses()) {
          classNames.add(cls.getName());
        }
        Collections.sort(classNames);

        for (var className : classNames) {
          var cls = session.getMetadata().getImmutableSchemaSnapshot()
              .getClassInternal(className);

          try {
            exportClass(session, json, cls);
          } catch (Exception e) {
            LogManager.instance().error(this, "Error on exporting class '" + cls + "'", e);
          }
        }
        json.endCollection();
      }

      if (session.getCollectionNames() != null) {
        json.beginCollection(session, "collections");
        for (var collectionName : session.getCollectionNames()) {
          final var collectionId = session.getCollectionIdByName(collectionName);
          if (collectionId < 0) {
            continue;
          }
          try {
            final var conflictStrategy = session.getCollectionRecordConflictStrategy(collectionId);

            json.beginObject();
            json.writeAttribute(session, "id", collectionId);
            json.writeAttribute(session, "name", collectionName);
            json.writeAttribute(session, "records", session.countCollectionElements(collectionId));
            json.writeAttribute(session, "conflictStrategy", conflictStrategy);
            json.writeAttribute(session, "size", "-");
            json.writeAttribute(session, "filled", "-");
            json.writeAttribute(session, "maxSize", "-");
            json.writeAttribute(session, "files", "-");
          } catch (Exception e) {
            json.writeAttribute(session, "records", "? (Unauthorized)");
          }
          json.endObject();
        }
        json.endCollection();
      }

      if (session.getCurrentUser() != null) {
        json.writeAttribute(session, "currentUser", session.getCurrentUser().getName(session));

        // exportSecurityInfo(db, json);
      }
      final var idxManager = session.getSharedContext().getIndexManager();
      json.beginCollection(session, "indexes");
      for (var index : idxManager.getIndexes()) {
        json.beginObject();
        try {
          json.writeAttribute(session, "name", index.getName());
          json.writeAttribute(session, "configuration", index.getConfiguration(session));
          // Exclude index size because it's too costly
          // json.writeAttribute("size", index.getSize());
        } catch (Exception e) {
          LogManager.instance().error(this, "Cannot serialize index configuration", e);
        }
        json.endObject();
      }
      json.endCollection();

      json.beginObject("config");

      json.beginCollection(session, "values");
      var configuration = session.getStorageInfo().getConfiguration();
      json.writeObjects(session,
          null,
          new Object[]{"name", "dateFormat", "value", configuration.getDateFormat()},
          new Object[]{"name", "dateTimeFormat", "value", configuration.getDateTimeFormat()},
          new Object[]{"name", "localeCountry", "value", configuration.getLocaleCountry()},
          new Object[]{"name", "localeLanguage", "value", configuration.getLocaleLanguage()},
          new Object[]{"name", "charSet", "value", configuration.getCharset()},
          new Object[]{"name", "timezone", "value", configuration.getTimeZone().getID()},
          new Object[]{"name", "definitionVersion", "value", configuration.getVersion()},
          new Object[]{"name", "collectionSelection", "value",
              configuration.getCollectionSelection()},
          new Object[]{"name", "minimumCollections", "value",
              configuration.getMinimumCollections()},
          new Object[]{"name", "conflictStrategy", "value", configuration.getConflictStrategy()});
      json.endCollection();

      json.beginCollection(session, "properties");
      if (configuration.getProperties() != null) {
        for (var entry : configuration.getProperties()) {
          if (entry != null) {
            json.beginObject();
            json.writeAttribute(session, "name", entry.name);
            json.writeAttribute(session, "value", entry.value);
            json.endObject();
          }
        }
      }
      json.endCollection();

      json.endObject();
      json.endObject();
      json.flush();

      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_JSON,
          buffer.toString(),
          null);
    } finally {
      if (session != null) {
        session.close();
      }
    }
  }

}
