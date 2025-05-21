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
package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONWriter;
import com.jetbrains.youtrack.db.internal.core.storage.disk.LocalStorage;
import java.io.IOException;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Returns information about the server.
 */
public class ServerInfo {

  public static String getServerInfo(final YouTrackDBServer server) throws IOException {
    final var jsonBuffer = new StringWriter();
    final var json = new JSONWriter(jsonBuffer);
    json.beginObject();

    getConnections(server, json, null);
    getDatabases(server, json);
    getStorages(server, json);
    getProperties(server, json);
    getGlobalProperties(server, json);

    json.endObject();

    return jsonBuffer.toString();
  }

  public static void getConnections(
      final YouTrackDBServer server, final JSONWriter json, final String databaseName)
      throws IOException {
    final DateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    json.beginCollection(null, 1, true, "connections");

    final var conns = server.getClientConnectionManager().getConnections();
    for (var c : conns) {
      final var data = c.getData();
      final var stats = c.getStats();

      if (databaseName != null && !databaseName.equals((stats.lastDatabase)))
      // SKIP IT
      {
        continue;
      }

      final String lastCommandOn;
      final String connectedOn;

      synchronized (dateTimeFormat) {
        lastCommandOn = dateTimeFormat.format(new Date(stats.lastCommandReceived));
        connectedOn = dateTimeFormat.format(new Date(c.getSince()));
      }
      String lastDatabase;
      String lastUser;
      if (stats.lastDatabase != null && stats.lastUser != null) {
        lastDatabase = stats.lastDatabase;
        lastUser = stats.lastUser;
      } else {
        lastDatabase = data.lastDatabase;
        lastUser = data.lastUser;
      }
      json.beginObject(2);
      writeField(json, 2, "connectionId", c.getId());
      writeField(
          json,
          2,
          "remoteAddress",
          c.getProtocol().getChannel() != null
              ? c.getProtocol().getChannel().toString()
              : "Disconnected");
      writeField(json, 2, "db", lastDatabase != null ? lastDatabase : "-");
      writeField(json, 2, "user", lastUser != null ? lastUser : "-");
      writeField(json, 2, "totalRequests", stats.totalRequests);
      writeField(json, 2, "commandInfo", data.commandInfo);
      writeField(json, 2, "commandDetail", data.commandDetail);
      writeField(json, 2, "lastCommandOn", lastCommandOn);
      writeField(json, 2, "lastCommandInfo", stats.lastCommandInfo);
      writeField(json, 2, "lastCommandDetail", stats.lastCommandDetail);
      writeField(json, 2, "lastExecutionTime", stats.lastCommandExecutionTime);
      writeField(json, 2, "totalWorkingTime", stats.totalCommandExecutionTime);
      writeField(json, 2, "connectedOn", connectedOn);
      writeField(json, 2, "protocol", c.getProtocol().getType());
      writeField(json, 2, "sessionId", data.sessionId);
      writeField(json, 2, "clientId", data.clientId);

      final var driver = new StringBuilder(128);
      if (data.driverName != null) {
        driver.append(data.driverName);
        driver.append(" v");
        driver.append(data.driverVersion);
        driver.append(" Protocol v");
        driver.append(data.protocolVersion);
      }

      writeField(json, 2, "driver", driver.toString());
      json.endObject(2);
    }
    json.endCollection(1, false);
  }

  public static void getGlobalProperties(final YouTrackDBServer server, final JSONWriter json)
      throws IOException {
    json.beginCollection(null, 2, true, "globalProperties");

    for (var c : GlobalConfiguration.values()) {
      json.beginObject(3, true, null);
      json.writeAttribute(null, 4, false, "key", c.getKey());
      json.writeAttribute(null, 4, false, "description", c.getDescription());
      json.writeAttribute(null, 4, false, "value", c.isHidden() ? "<hidden>" : c.getValue());
      json.writeAttribute(null, 4, false, "defaultValue", c.getDefValue());
      json.writeAttribute(null, 4, false, "canChange", c.isChangeableAtRuntime());
      json.endObject(3, true);
    }

    json.endCollection(2, true);
  }

  public static void getProperties(final YouTrackDBServer server, final JSONWriter json)
      throws IOException {
    json.beginCollection(null, 2, true, "properties");

    var confProperties = server.getConfiguration().properties;
    if (confProperties != null) {
      for (var entry : confProperties) {
        json.beginObject(3, true, null);
        json.writeAttribute(null, 4, false, "name", entry.name);
        json.writeAttribute(null, 4, false, "value", entry.value);
        json.endObject(3, true);
      }
    }
    json.endCollection(2, true);
  }

  public static void getStorages(final YouTrackDBServer server, final JSONWriter json)
      throws IOException {
    json.beginCollection(null, 1, true, "storages");
    var storages = server.getDatabases().getStorages();
    for (var s : storages) {
      json.beginObject(2);
      writeField(json, 2, "name", s.getName());
      writeField(json, 2, "type", s.getClass().getSimpleName());
      writeField(
          json,
          2,
          "path",
          s instanceof LocalStorage
              ? ((LocalStorage) s).getStoragePath().toString().replace('\\', '/')
              : "");
      writeField(json, 2, "activeUsers", "n.a.");
      json.endObject(2);
    }
    json.endCollection(1, false);
  }

  public static void getDatabases(final YouTrackDBServer server, final JSONWriter json)
      throws IOException {
    json.beginCollection(null, 1, true, "dbs");
    // TODO:get this info from somewhere else
    //    if (!server.getDatabasePoolFactory().isClosed()) {
    //      Collection<PartitionedDatabasePool> dbPools =
    // server.getDatabasePoolFactory().getPools();
    //      for (PartitionedDatabasePool pool : dbPools) {
    //        writeField(json, 2, "db", pool.getUrl());
    //        writeField(json, 2, "user", pool.getUserName());
    //        json.endObject(2);
    //      }
    //    }
    json.endCollection(1, false);
  }

  private static void writeField(
      final JSONWriter json,
      final int iLevel,
      final String iAttributeName,
      final Object iAttributeValue)
      throws IOException {
    json.writeAttribute(null,
        iLevel, true, iAttributeName, iAttributeValue != null ? iAttributeValue.toString() : "-");
  }
}
