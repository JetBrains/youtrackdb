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

package com.jetbrains.youtrackdb.internal.server.handler;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.common.parser.VariableParser;
import com.jetbrains.youtrackdb.internal.common.parser.VariableParserListener;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.config.ServerParameterConfiguration;
import com.jetbrains.youtrackdb.internal.server.plugin.ServerPluginAbstract;
import com.jetbrains.youtrackdb.internal.server.plugin.ServerPluginConfigurable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatically creates a backup at configured time. Starting from v2.2, this component is able
 * also to create incremental backup and export of databases. If you need a mix of different modes,
 * configure more instances of the same component.
 */
public class AutomaticBackup extends ServerPluginAbstract implements ServerPluginConfigurable {

  private Map<String, Object> configuration;

  private final Set<AutomaticBackupListener> listeners =
      Collections.newSetFromMap(new ConcurrentHashMap<AutomaticBackupListener, Boolean>());

  public enum VARIABLES {
    DBNAME,
    DATE
  }

  public enum MODE {
    INCREMENTAL_BACKUP,
    EXPORT
  }

  private String configFile = "${YOUTRACKDB_HOME}/config/automatic-backup.json";
  private Date firstTime = null;
  private long delay = -1;
  private int bufferSize = 1048576;
  private int compressionLevel = 9;
  private MODE mode = MODE.INCREMENTAL_BACKUP;
  private String exportOptions;

  private String targetDirectory = "backup";
  private String targetFileName;
  private final Set<String> includeDatabases = new HashSet<String>();
  private final Set<String> excludeDatabases = new HashSet<String>();
  private YouTrackDBServer serverInstance;

  @Override
  public void config(final YouTrackDBServer iServer,
      final ServerParameterConfiguration[] iParams) {
    serverInstance = iServer;

    configuration = new HashMap<>();
    for (var param : iParams) {
      if (param.name.equalsIgnoreCase("config") && !param.value.trim().isEmpty()) {
        configFile = param.value.trim();

        final var f = new File(SystemVariableResolver.resolveSystemVariables(configFile));
        if (!f.exists()) {
          throw new ConfigurationException((String) null,
              "Automatic Backup configuration file '"
                  + configFile
                  + "' not found. Automatic Backup will be disabled");
        }
        break;

        // LEGACY <v2.2: CONVERT ALL SETTINGS IN JSON
      } else if (param.name.equalsIgnoreCase("enabled")) {
        configuration.put("enabled", Boolean.parseBoolean(param.value));
      } else if (param.name.equalsIgnoreCase("delay")) {
        configuration.put("delay", param.value);
      } else if (param.name.equalsIgnoreCase("firstTime")) {
        configuration.put("firstTime", param.value);
      } else if (param.name.equalsIgnoreCase("target.directory")) {
        configuration.put("targetDirectory", param.value);
      } else if (param.name.equalsIgnoreCase("db.include") && !param.value.trim().isEmpty()) {
        configuration.put("dbInclude", param.value);
      } else if (param.name.equalsIgnoreCase("db.exclude") && !param.value.trim().isEmpty()) {
        configuration.put("dbExclude", param.value);
      } else if (param.name.equalsIgnoreCase("target.fileName")) {
        configuration.put("targetFileName", param.value);
      } else if (param.name.equalsIgnoreCase("bufferSize")) {
        configuration.put("bufferSize", Integer.parseInt(param.value));
      } else if (param.name.equalsIgnoreCase("compressionLevel")) {
        configuration.put("compressionLevel", Integer.parseInt(param.value));
      } else if (param.name.equalsIgnoreCase("mode")) {
        configuration.put("mode", param.value);
      } else if (param.name.equalsIgnoreCase("exportOptions")) {
        configuration.put("exportOptions", param.value);
      }
    }

    // LOAD CFG FROM JSON FILE. THIS FILE, IF SPECIFIED, OVERWRITE DEFAULT AND XML SETTINGS
    configure();

    if (enabled) {
      if (delay <= 0) {
        throw new ConfigurationException((String) null, "Cannot find mandatory parameter 'delay'");
      }
      if (!targetDirectory.endsWith("/")) {
        targetDirectory += "/";
      }

      final var filePath = new File(targetDirectory);
      if (filePath.exists()) {
        if (!filePath.isDirectory()) {
          throw new ConfigurationException((String) null,
              "Parameter 'path' points to a file, not a directory");
        }
      } else {
        // CREATE BACKUP FOLDER(S) IF ANY
        filePath.mkdirs();
      }

      LogManager.instance()
          .info(
              this,
              "Automatic Backup plugin installed and active: delay=%dms, firstTime=%s,"
                  + " targetDirectory=%s",
              delay,
              firstTime,
              targetDirectory);

      final var timerTask =
          new Runnable() {
            @Override
            public void run() {
              LogManager.instance().info(this, "Scanning databases to backup...");

              var ok = 0;
              var errors = 0;

              final var databases = serverInstance.getAvailableStorageNames();
              for (final var database : databases.entrySet()) {
                final var dbName = database.getKey();
                final var dbURL = database.getValue();

                boolean include;

                if (!includeDatabases.isEmpty()) {
                  include = includeDatabases.contains(dbName);
                } else {
                  include = true;
                }

                if (excludeDatabases.contains(dbName)) {
                  include = false;
                }

                if (include) {
                  try (var db =
                      serverInstance.getDatabases().openNoAuthorization(dbName)) {

                    final var begin = System.currentTimeMillis();

                    switch (mode) {
                      case INCREMENTAL_BACKUP:
                        incrementalBackupDatabase(dbURL, targetDirectory, db);

                        LogManager.instance()
                            .info(
                                this,
                                "Incremental Backup of database '"
                                    + dbURL
                                    + "' completed in "
                                    + (System.currentTimeMillis() - begin)
                                    + "ms");
                        break;

                      case EXPORT:
                        exportDatabase(dbURL, targetDirectory + getFileName(database), db);

                        LogManager.instance()
                            .info(
                                this,
                                "Export of database '"
                                    + dbURL
                                    + "' completed in "
                                    + (System.currentTimeMillis() - begin)
                                    + "ms");
                        break;
                    }

                    try {

                      for (var listener : listeners) {
                        listener.onBackupCompleted(dbName);
                      }
                    } catch (Exception e) {
                      LogManager.instance()
                          .error(this, "Error on listener for database '" + dbURL, e);
                    }
                    ok++;

                  } catch (Exception e) {

                    LogManager.instance()
                        .error(
                            this,
                            "Error on backup of database '"
                                + dbURL
                                + "' to directory: "
                                + targetDirectory,
                            e);

                    try {
                      for (var listener : listeners) {
                        listener.onBackupError(dbName, e);
                      }
                    } catch (Exception l) {
                      LogManager.instance()
                          .error(this, "Error on listener for database '" + dbURL, l);
                    }
                    errors++;
                  }
                }
              }
              LogManager.instance()
                  .info(this, "Automatic Backup finished: %d ok, %d errors", ok, errors);
            }
          };

      var task =
          new TimerTask() {

            @Override
            public void run() {
              serverInstance.getDatabases().execute(timerTask);
            }
          };
      if (firstTime == null) {
        serverInstance.getDatabases().schedule(task, delay, delay);
      } else {
        YouTrackDBEnginesManager.instance().getScheduler().scheduleTask(task, firstTime, delay);
      }
    } else {
      LogManager.instance().info(this, "Automatic Backup plugin is disabled");
    }
  }

  private void configure() {
    final var f = new File(SystemVariableResolver.resolveSystemVariables(configFile));
    if (f.exists()) {
      // READ THE FILE
      try {
        final var configurationContent = IOUtils.readFileAsString(f);
        configuration = JSONSerializerJackson.INSTANCE.mapFromJson(configurationContent);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new ConfigurationException((String) null,
                "Cannot load Automatic Backup configuration file '"
                    + configFile
                    + "'. Automatic Backup will be disabled"),
            e, (String) null);
      }

    } else {
      // AUTO CONVERT XML CONFIGURATION (<v2.2) TO JSON FILE
      try {
        f.getParentFile().mkdirs();
        f.createNewFile();
        IOUtils.writeFile(f, JSONSerializerJackson.INSTANCE.mapToJson(configuration));

        LogManager.instance()
            .info(this, "Automatic Backup: migrated configuration to file '%s'", f);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new ConfigurationException((String) null,
                "Cannot create Automatic Backup configuration file '"
                    + configFile
                    + "'. Automatic Backup will be disabled"),
            e, (String) null);
      }
    }

    // PARSE THE JSON FILE
    for (var entry : configuration.entrySet()) {
      var settingName = entry.getKey();
      final var settingValue = entry.getValue();
      final var settingValueAsString = settingValue != null ? settingValue.toString() : null;

      if (settingName.equalsIgnoreCase("enabled")) {
        if (!(Boolean) settingValue) {
          enabled = false;
          // DISABLE IT
          return;
        }
      } else if (settingName.equalsIgnoreCase("delay")) {
        delay = IOUtils.getTimeAsMillisecs(settingValue);
      } else if (settingName.equalsIgnoreCase("firstTime")) {
        try {
          firstTime = IOUtils.getTodayWithTime(settingValueAsString);
          if (firstTime.before(new Date())) {
            var cal = Calendar.getInstance();
            cal.setTime(firstTime);
            cal.add(Calendar.DAY_OF_MONTH, 1);
            firstTime = cal.getTime();
          }
        } catch (ParseException e) {
          throw BaseException.wrapException(
              new ConfigurationException((String) null,
                  "Parameter 'firstTime' has invalid format, expected: HH:mm:ss"),
              e, (String) null);
        }
      } else if (settingName.equalsIgnoreCase("targetDirectory")) {
        targetDirectory = settingValueAsString;
      } else if (settingName.equalsIgnoreCase("dbInclude")) {
        var included = getDbsList(settingName, settingValueAsString);
        Collections.addAll(includeDatabases, included);
      } else if (settingName.equalsIgnoreCase("dbExclude")
          && settingValueAsString.trim().length() > 0) {
        var excluded = getDbsList(settingName, settingValueAsString);
        Collections.addAll(excludeDatabases, excluded);
      } else if (settingName.equalsIgnoreCase("targetFileName")) {
        targetFileName = settingValueAsString;
      } else if (settingName.equalsIgnoreCase("bufferSize")) {
        bufferSize = (Integer) settingValue;
      } else if (settingName.equalsIgnoreCase("compressionLevel")) {
        compressionLevel = (Integer) settingValue;
      } else if (settingName.equalsIgnoreCase("mode")) {
        mode = MODE.valueOf(settingValueAsString.toUpperCase(Locale.ENGLISH));
      } else if (settingName.equalsIgnoreCase("exportOptions")) {
        exportOptions = settingValueAsString;
      }
    }
  }

  private String[] getDbsList(String settingName, String settingValueAsString) {
    String[] included = null;
    var val = configuration.get(settingName);
    if (val instanceof Collection<?> dbs) {
      included = new String[dbs.size()];
      var i = 0;
      for (var o : dbs) {
        included[i] = o.toString();
        i++;
      }
    } else {
      if (!settingValueAsString.trim().isEmpty()) {
        included = settingValueAsString.split(",");
      }
    }
    return included;
  }

  protected void incrementalBackupDatabase(
      final String dbURL, String iPath, final DatabaseSessionInternal db) throws IOException {
    // APPEND DB NAME TO THE DIRECTORY NAME
    if (!iPath.endsWith("/")) {
      iPath += "/";
    }
    iPath += db.getDatabaseName();

    LogManager.instance()
        .info(
            this,
            "AutomaticBackup: executing incremental backup of database '%s' to %s",
            dbURL,
            iPath);

    db.incrementalBackup(Path.of(iPath));
  }

  protected void exportDatabase(
      final String dbURL, final String iPath, final DatabaseSessionEmbedded db)
      throws IOException {

    LogManager.instance()
        .info(this, "AutomaticBackup: executing export of database '%s' to %s", dbURL, iPath);

    final var exp =
        new DatabaseExport(
            db,
            iPath,
            new CommandOutputListener() {
              @Override
              public void onMessage(String iText) {
                LogManager.instance().info(this, iText);
              }
            });

    if (exportOptions != null && !exportOptions.trim().isEmpty()) {
      exp.setOptions(exportOptions.trim());
    }

    exp.exportDatabase().close();
  }

  protected String getFileName(final Entry<String, String> dbName) {
    return (String)
        VariableParser.resolveVariables(
            targetFileName,
            SystemVariableResolver.VAR_BEGIN,
            SystemVariableResolver.VAR_END,
            new VariableParserListener() {
              @Override
              public String resolve(final String iVariable) {
                if (iVariable.equalsIgnoreCase(VARIABLES.DBNAME.toString())) {
                  return dbName.getKey();
                } else if (iVariable.startsWith(VARIABLES.DATE.toString())) {
                  return new SimpleDateFormat(
                      iVariable.substring(VARIABLES.DATE.toString().length() + 1))
                      .format(new Date());
                }

                // NOT FOUND
                throw new IllegalArgumentException("Variable '" + iVariable + "' was not found");
              }
            });
  }

  @Override
  public String getName() {
    return "automaticBackup";
  }

  @Override
  public Map<String, Object> getConfig() {
    return configuration;
  }

  // TODO change current config and restart the automatic backup plugin
  @Override
  public void changeConfig(EntityImpl entity) {
  }

  public void registerListener(AutomaticBackupListener listener) {
    listeners.add(listener);
  }

  public void unregisterListener(AutomaticBackupListener listener) {
    listeners.remove(listener);
  }

  public interface AutomaticBackupListener {

    void onBackupCompleted(String database);

    void onBackupError(String database, Exception e);
  }
}
