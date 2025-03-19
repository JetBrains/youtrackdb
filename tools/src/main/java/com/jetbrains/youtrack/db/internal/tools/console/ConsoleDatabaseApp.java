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
package com.jetbrains.youtrack.db.internal.tools.console;

import static com.jetbrains.youtrack.db.api.config.GlobalConfiguration.WARNING_DEFAULT_USERS;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.client.remote.DatabaseImportRemote;
import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import com.jetbrains.youtrack.db.internal.client.remote.YouTrackDBRemote;
import com.jetbrains.youtrack.db.internal.common.console.ConsoleApplication;
import com.jetbrains.youtrack.db.internal.common.console.ConsoleProperties;
import com.jetbrains.youtrack.db.internal.common.console.TTYConsoleReader;
import com.jetbrains.youtrack.db.internal.common.console.annotation.ConsoleCommand;
import com.jetbrains.youtrack.db.internal.common.console.annotation.ConsoleParameter;
import com.jetbrains.youtrack.db.internal.common.exception.SystemException;
import com.jetbrains.youtrack.db.internal.common.io.YTIOException;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.SignalHandler;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.BonsaiTreeRepair;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExportException;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImportException;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseRepair;
import com.jetbrains.youtrack.db.internal.core.db.tool.GraphRepair;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.security.SecurityManager;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerStringAbstract;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.util.DatabaseURLConnection;
import com.jetbrains.youtrack.db.internal.core.util.URLHelper;
import com.jetbrains.youtrack.db.internal.tools.config.ServerConfigurationManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class ConsoleDatabaseApp extends ConsoleApplication
    implements CommandOutputListener, ProgressListener, TableFormatter.OTableOutput {

  protected DatabaseSessionInternal currentDatabaseSession;
  protected String currentDatabaseName;
  protected List<RawPair<RID, Object>> currentResultSet;
  protected DatabaseURLConnection urlConnection;
  protected YouTrackDBImpl youTrackDB;
  private int lastPercentStep;
  private String currentDatabaseUserName;
  private String currentDatabaseUserPassword;
  private static final int maxMultiValueEntries = 10;

  public ConsoleDatabaseApp(final String[] args) {
    super(args);
  }

  public static void main(final String[] args) {
    var result = 0;

    final var interactiveMode = isInteractiveMode(args);
    try {
      final var console = new ConsoleDatabaseApp(args);
      var tty = false;
      try {
        if (setTerminalToCBreak(interactiveMode)) {
          tty = true;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> restoreTerminal(interactiveMode)));

      } catch (Exception ignored) {
      }

      new SignalHandler().installDefaultSignals(signal -> restoreTerminal(interactiveMode));

      if (tty) {
        console.setReader(new TTYConsoleReader(console.historyEnabled()));
      }

      result = console.run();

    } finally {
      restoreTerminal(interactiveMode);
    }

    YouTrackDBEnginesManager.instance().shutdown();
    System.exit(result);
  }

  protected static void restoreTerminal(final boolean interactiveMode) {
    try {
      stty("echo", interactiveMode);
    } catch (Exception ignored) {
    }
  }

  protected static boolean setTerminalToCBreak(final boolean interactiveMode)
      throws IOException, InterruptedException {
    // set the console to be character-buffered instead of line-buffered
    var result = stty("-icanon min 1", interactiveMode);
    if (result != 0) {
      return false;
    }

    // disable character echoing
    stty("-echo", interactiveMode);
    return true;
  }

  /**
   * Execute the stty command with the specified arguments against the current active terminal.
   */
  protected static int stty(final String args, final boolean interactiveMode)
      throws IOException, InterruptedException {
    if (!interactiveMode) {
      return -1;
    }

    final var cmd = "stty " + args + " < /dev/tty";

    final var p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
    p.waitFor(10, TimeUnit.SECONDS);

    return p.exitValue();
  }

  private void checkDefaultPassword(String database, String user, String password) {
    if ((("admin".equals(user) && "admin".equals(password))
        || ("reader".equals(user) && "reader".equals(password))
        || ("writer".equals(user) && "writer".equals(password)))
        && WARNING_DEFAULT_USERS.getValueAsBoolean()) {
      message(
          String.format(
              "IMPORTANT! Using default password is unsafe, please change password for user '%s' on"
                  + " database '%s'",
              user, database));
    }
  }

  @ConsoleCommand(
      aliases = {"use database"},
      description = "Connect to a database or a remote Server instance",
      onlineHelp = "Console-Command-Connect")
  public void connect(
      @ConsoleParameter(
          name = "url",
          description =
              "The url of the remote server or the database to connect to in the format"
                  + " '<mode>:<path>'")
      String iURL,
      @ConsoleParameter(name = "user", description = "User name") String iUserName,
      @ConsoleParameter(name = "password", description = "User password", optional = true)
      String iUserPassword)
      throws IOException {
    disconnect();

    if (iUserPassword == null) {
      message("Enter password: ");
      final var br = new BufferedReader(new InputStreamReader(this.in));
      iUserPassword = br.readLine();
      message("\n");
    }

    currentDatabaseUserName = iUserName;
    currentDatabaseUserPassword = iUserPassword;
    urlConnection = URLHelper.parseNew(iURL);
    if (urlConnection.getDbName() != null && !"".equals(urlConnection.getDbName())) {
      checkDefaultPassword(
          urlConnection.getDbName(), currentDatabaseUserName, currentDatabaseUserPassword);
    }
    youTrackDB =
        new YouTrackDBImpl(
            urlConnection.getType() + ":" + urlConnection.getPath(),
            iUserName,
            iUserPassword,
            YouTrackDBConfig.defaultConfig());

    if (!"".equals(urlConnection.getDbName())) {
      // OPEN DB
      message("\nConnecting to database [" + iURL + "] with user '" + iUserName + "'...");
      currentDatabaseSession =
          (DatabaseSessionInternal)
              youTrackDB.open(urlConnection.getDbName(), iUserName, iUserPassword);
      currentDatabaseName = currentDatabaseSession.getDatabaseName();
    }

    message("OK");
  }

  @ConsoleCommand(
      aliases = {"close database"},
      description = "Disconnect from the current database",
      onlineHelp = "Console-Command-Disconnect")
  public void disconnect() {
    if (currentDatabaseSession != null) {
      message("\nDisconnecting from the database [" + currentDatabaseName + "]...");

      currentDatabaseSession.activateOnCurrentThread();
      if (!currentDatabaseSession.isClosed()) {
        currentDatabaseSession.close();
      }

      currentDatabaseSession = null;
      currentDatabaseName = null;

      message("OK");
      out.println();
    }
    urlConnection = null;
    if (youTrackDB != null) {
      youTrackDB.close();
    }
  }

  @ConsoleCommand(
      description =
          "Create a new database. For encrypted database or portion of database, set the variable"
              + " 'storage.encryptionKey' with the key to use",
      onlineHelp = "Console-Command-Create-Database")
  public void createDatabase(
      @ConsoleParameter(
          name = "database-url",
          description = "The url of the database to create in the format '<mode>:<path>'")
      String databaseURL,
      @ConsoleParameter(name = "user", optional = true, description = "Server administrator name")
      String userName,
      @ConsoleParameter(
          name = "password",
          optional = true,
          description = "Server administrator password")
      String userPassword,
      @ConsoleParameter(
          name = "storage-type",
          optional = true,
          description =
              "The type of the storage: 'disk' for disk-based databases and 'memory' for"
                  + " in-memory database")
      String storageType,
      @ConsoleParameter(
          name = "db-type",
          optional = true,
          description =
              "The type of the database used between 'document' and 'graph'. By default is"
                  + " graph.")
      String databaseType,
      @ConsoleParameter(
          name = "[options]",
          optional = true,
          description = "Additional options, example: -encryption=aes -compression=nothing") final String options)
      throws IOException {

    disconnect();

    if (userName == null) {
      userName = SecurityUserImpl.ADMIN;
    }
    if (userPassword == null) {
      userPassword = SecurityUserImpl.ADMIN;
    }

    currentDatabaseUserName = userName;
    currentDatabaseUserPassword = userPassword;
    final var omap = parseCommandOptions(options);

    urlConnection = URLHelper.parseNew(databaseURL);
    var config = (YouTrackDBConfigBuilderImpl) YouTrackDBConfig.builder();

    DatabaseType type;
    if (storageType != null) {
      type = DatabaseType.valueOf(storageType.toUpperCase());
    } else {
      type = urlConnection.getDbType().orElse(DatabaseType.DISK);
    }

    message("\nCreating database [" + databaseURL + "] using the storage type [" + type + "]...");
    var conn = urlConnection.getType() + ":" + urlConnection.getPath();
    if (youTrackDB != null) {
      var contectSession = YouTrackDBInternal.extract(youTrackDB);
      var user = YouTrackDBInternal.extractUser(youTrackDB);
      if (!contectSession.getConnectionUrl().equals(conn)
          || user == null
          || !user.equals(userName)) {
        youTrackDB =
            new YouTrackDBImpl(
                conn, currentDatabaseUserName, currentDatabaseUserPassword, config.build());
      }
    } else {
      youTrackDB =
          new YouTrackDBImpl(conn, currentDatabaseUserName, currentDatabaseUserPassword,
              config.build());
    }

    final var backupPath = omap.remove("-restore");

    if (backupPath != null) {
      var internal = YouTrackDBInternal.extract(youTrackDB);
      internal.restore(
          urlConnection.getDbName(),
          currentDatabaseUserName,
          currentDatabaseUserPassword,
          type,
          backupPath,
          config.build());
    } else {
      var internal = YouTrackDBInternal.extract(youTrackDB);
      if (internal.isEmbedded()) {
        youTrackDB.createIfNotExists(
            urlConnection.getDbName(),
            type,
            currentDatabaseUserName,
            currentDatabaseUserPassword,
            "admin");
      } else {
        youTrackDB.create(urlConnection.getDbName(), type);
      }
    }
    currentDatabaseSession =
        (DatabaseSessionInternal) youTrackDB.open(urlConnection.getDbName(), userName,
            userPassword);
    currentDatabaseName = currentDatabaseSession.getDatabaseName();

    message("\nDatabase created successfully.");
    message("\n\nCurrent database is: " + databaseURL);
  }

  @SuppressWarnings("MethodMayBeStatic")
  protected Map<String, String> parseCommandOptions(
      @ConsoleParameter(
          name = "[options]",
          optional = true,
          description = "Additional options, example: -encryption=aes -compression=nothing")
      String options) {
    final Map<String, String> omap = new HashMap<String, String>();
    if (options != null) {
      final var kvOptions = StringSerializerHelper.smartSplit(options, ',', false);
      for (var option : kvOptions) {
        final var values = option.split("=");
        if (values.length == 2) {
          omap.put(values[0], values[1]);
        } else {
          omap.put(values[0], null);
        }
      }
    }
    return omap;
  }

  @ConsoleCommand(
      description = "List all the databases available on the connected server",
      onlineHelp = "Console-Command-List-Databases")
  public void listDatabases() throws IOException {
    if (youTrackDB != null) {
      final var databases = youTrackDB.list();
      message(String.format("\nFound %d databases:\n", databases.size()));
      for (var database : databases) {
        message(String.format("\n* %s ", database));
      }
    } else {
      message(
          "\n"
              + "Not connected to the Server instance. You've to connect to the Server using"
              + " server's credentials (look at orientdb-*server-config.xml file)");
    }
    out.println();
  }

  @ConsoleCommand(
      description = "List all the active connections to the server",
      onlineHelp = "Console-Command-List-Connections")
  public void listConnections() {
    checkForRemoteServer();
    var remote = (YouTrackDBRemote) YouTrackDBInternal.extract(youTrackDB);
    final var serverInfo =
        remote.getServerInfo(currentDatabaseUserName, currentDatabaseUserPassword);

    List<RawPair<RID, Object>> resultSet = new ArrayList<>();

    @SuppressWarnings("unchecked") final var connections = (List<Map<String, Object>>) serverInfo.get(
        "connections");
    for (var conn : connections) {

      var commandDetail = new StringBuilder();
      var commandInfo = (String) conn.get("commandInfo");
      if (commandInfo != null) {
        commandDetail.append(commandInfo);
      }

      if (((String) conn.get("commandDetail")).length() > 1) {
        commandDetail.append(" (").append(conn.get("commandDetail")).append(")");
      }

      var row = Map.of(
          "ID",
          conn.get("connectionId"),
          "REMOTE_ADDRESS",
          conn.get("remoteAddress"),
          "PROTOC",
          conn.get("protocol"),
          "LAST_OPERATION_ON",
          conn.get("lastCommandOn"),
          "DATABASE",
          conn.get("db"),
          "USER",
          conn.get("user"),
          "COMMAND",
          commandDetail.toString(),
          "TOT_REQS",
          conn.get("totalRequests"));

      resultSet.add(new RawPair<>(new ChangeableRecordId(), row));
    }

    resultSet.sort((o1, o2) -> {
      @SuppressWarnings("unchecked") final var o1s = ((Map<String, String>) o1.second).get(
          "LAST_OPERATION_ON");
      @SuppressWarnings("unchecked") final var o2s = ((Map<String, String>) o2.second).get(
          "LAST_OPERATION_ON");
      return o2s.compareTo(o1s);
    });

    final var formatter = new TableFormatter(this);
    formatter.setMaxWidthSize(getConsoleWidth());
    formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

    formatter.writeRecords(resultSet, -1, currentDatabaseSession);

    out.println();
  }

  @ConsoleCommand(description = "Reload the database schema")
  public void reloadSchema() throws IOException {
    message("\nreloading database schema...");
    updateDatabaseInfo();
    message("\n\nDone.");
  }

  @ConsoleCommand(
      splitInWords = false,
      description =
          "Create a new cluster in the current database. The cluster can be physical or memory")
  public void createCluster(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nCluster created correctly in %.2f seconds\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      description =
          "Remove a cluster in the current database. The cluster can be physical or memory")
  public void dropCluster(
      @ConsoleParameter(
          name = "cluster-name",
          description = "The name or the id of the cluster to remove")
      String iClusterName) {
    checkForDatabase();

    message("\nDropping cluster [" + iClusterName + "] in database " + currentDatabaseName + "...");

    var result = currentDatabaseSession.dropCluster(iClusterName);

    if (!result) {
      // TRY TO GET AS CLUSTER ID
      try {
        var clusterId = Integer.parseInt(iClusterName);
        if (clusterId > -1) {
          result = currentDatabaseSession.dropCluster(clusterId);
        }
      } catch (Exception ignored) {
      }
    }

    if (result) {
      message("\nCluster correctly removed");
    } else {
      message("\nCannot find the cluster to remove");
    }
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      splitInWords = false,
      description =
          "Alters a cluster in the current database. The cluster can be physical or memory")
  public void alterCluster(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("alter", iCommandText, "\nCluster updated successfully.\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(description = "Begins a transaction. All the changes will remain local")
  public void begin() throws IOException {
    checkForDatabase();

    if (currentDatabaseSession.getTransactionInternal().isActive()) {
      message(
          "\nError: an active transaction is currently open (id="
              + currentDatabaseSession.getTransactionInternal().getId()
              + "). Commit or rollback before starting a new one.");
      return;
    }

    if (currentDatabaseSession.isRemote()) {
      message(
          """
              WARNING - Transactions are not supported from console in remote, please use an sql\
               script:\s
              eg.
              
              script sql
              begin;
              <your commands here>
              commit;
              end
              
              """);
      return;
    }

    currentDatabaseSession.begin();
    message(
        "\nTransaction " + currentDatabaseSession.getTransactionInternal().getId() + " is running");
  }

  @ConsoleCommand(description = "Commits transaction changes to the database")
  public void commit() throws IOException {
    checkForDatabase();

    if (!currentDatabaseSession.getTransactionInternal().isActive()) {
      message("\nError: no active transaction is currently open.");
      return;
    }

    final var begin = System.currentTimeMillis();

    final var txId = currentDatabaseSession.getTransactionInternal().getId();
    currentDatabaseSession.commit();

    message(
        "\nTransaction "
            + txId
            + " has been committed in "
            + (System.currentTimeMillis() - begin)
            + "ms");
  }

  @ConsoleCommand(description = "Rolls back transaction changes to the previous state")
  public void rollback() throws IOException {
    checkForDatabase();

    if (!currentDatabaseSession.getTransactionInternal().isActive()) {
      message("\nError: no active transaction is running right now.");
      return;
    }

    final var begin = System.currentTimeMillis();

    final var txId = currentDatabaseSession.getTransactionInternal().getId();
    currentDatabaseSession.rollback();
    message(
        "\nTransaction "
            + txId
            + " has been rollbacked in "
            + (System.currentTimeMillis() - begin)
            + "ms");
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Truncate the class content in the current database")
  public void truncateClass(
      @ConsoleParameter(name = "text", description = "The name of the class to truncate")
      String iCommandText) {
    sqlCommand("truncate", iCommandText, "\nClass truncated.\n", false);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Truncate the cluster content in the current database")
  public void truncateCluster(
      @ConsoleParameter(name = "text", description = "The name of the class to truncate")
      String iCommandText) {
    sqlCommand("truncate", iCommandText, "\nTruncated %d record(s) in %f sec(s).\n", true);
  }

  @ConsoleCommand(splitInWords = false, description = "Truncate a record deleting it at low level")
  public void truncateRecord(
      @ConsoleParameter(name = "text", description = "The record(s) to truncate")
      String iCommandText) {
    sqlCommand("truncate", iCommandText, "\nTruncated %d record(s) in %f sec(s).\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Explain how a command is executed profiling it",
      onlineHelp = "SQL-Explain")
  public void explain(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    var result = sqlCommand("explain", iCommandText, "\n", false);
    if (result.size() == 1
        && result.getFirst() instanceof Entity) {
      message(((EntityImpl) (result.getFirst())).getProperty("executionPlanAsString"));
    }
  }

  @ConsoleCommand(splitInWords = false, description = "Executes a command inside a transaction")
  public void transactional(
      @ConsoleParameter(name = "command-text", description = "The command to execute")
      String iCommandText) {
    sqlCommand("transactional", iCommandText, "\nResult: '%s'. Executed in %f sec(s).\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Insert a new record into the database",
      onlineHelp = "SQL-Insert")
  public void insert(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("insert", iCommandText, "\nInserted record '%s' in %f sec(s).\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a new vertex into the database",
      onlineHelp = "SQL-Create-Vertex")
  public void createVertex(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nCreated vertex '%s' in %f sec(s).\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a new edge into the database",
      onlineHelp = "SQL-Create-Edge")
  public void createEdge(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {

    var command = "create " + iCommandText;
    resetResultSet();
    final var start = System.currentTimeMillis();

    var rs = currentDatabaseSession.execute(command);
    var result =
        rs.stream().map(x -> new RawPair<RID, Object>(x.getIdentity(), x.toMap())).toList();
    rs.close();

    var elapsedSeconds = getElapsedSecs(start);
    currentResultSet = result;

    var displayLimit = Integer.parseInt(properties.get(ConsoleProperties.LIMIT));

    dumpResultSet(displayLimit);

    message(String.format("\nCreated '%s' edges in %f sec(s).\n", result.size(), elapsedSeconds));
  }

  @ConsoleCommand(description = "Switches on storage profiling for upcoming set of commands")
  public void profileStorageOn() {
    sqlCommand("profile", " storage on", "\nProfiling of storage is switched on.\n", false);
  }

  @ConsoleCommand(
      description =
          "Switches off storage profiling for issued set of commands and "
              + "returns reslut of profiling.")
  public void profileStorageOff() throws Exception {
    var result =
        sqlCommand(
            "profile", " storage off", "\nProfiling of storage is switched off\n", false);

    final var profilingWasNotSwitchedOn =
        "Can not retrieve results of profiling, probably profiling was not switched on";

    if (result == null) {
      message(profilingWasNotSwitchedOn);
      return;
    }

    final var profilerIterator = result.iterator();
    if (profilerIterator.hasNext()) {
      var profilerEntry = profilerIterator.next();
      if (profilerEntry == null) {
        message(profilingWasNotSwitchedOn);
      } else {
        var objectMapper = new ObjectMapper();
        message(
            String.format("Profiling result is : \n%s\n",
                objectMapper.writeValueAsString(profilerEntry)));
      }
    } else {
      message(profilingWasNotSwitchedOn);
    }
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Update records in the database",
      onlineHelp = "SQL-Update")
  public void update(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("update", iCommandText, "\nUpdated record(s) '%s' in %f sec(s).\n", true);
    updateDatabaseInfo();
    currentDatabaseSession.getLocalCache().invalidate();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "High Availability commands",
      onlineHelp = "SQL-HA")
  public void ha(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("ha", iCommandText, "\nExecuted '%s' in %f sec(s).\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Move vertices to another position (class/cluster)",
      priority = 8,
      onlineHelp = "SQL-Move-Vertex")
  // EVALUATE THIS BEFORE 'MOVE'
  public void moveVertex(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand(
        "move",
        iCommandText,
        "\nMove vertex command executed with result '%s' in %f sec(s).\n",
        true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Optimizes the current database",
      onlineHelp = "SQL-Optimize-Database")
  public void optimizeDatabase(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("optimize", iCommandText, "\nDatabase optimized '%s' in %f sec(s).\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Delete records from the database",
      onlineHelp = "SQL-Delete")
  public void delete(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("delete", iCommandText, "\nDelete record(s) '%s' in %f sec(s).\n", true);
    updateDatabaseInfo();
    currentDatabaseSession.getLocalCache().invalidate();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Grant privileges to a role",
      onlineHelp = "SQL-Grant")
  public void grant(
      @ConsoleParameter(name = "text", description = "Grant command") String iCommandText) {
    sqlCommand("grant", iCommandText, "\nPrivilege granted to the role: %s.\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Revoke privileges to a role",
      onlineHelp = "SQL-Revoke")
  public void revoke(
      @ConsoleParameter(name = "text", description = "Revoke command") String iCommandText) {
    sqlCommand("revoke", iCommandText, "\nPrivilege revoked to the role: %s.\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a link from a JOIN",
      onlineHelp = "SQL-Create-Link")
  public void createLink(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nCreated %d link(s) in %f sec(s).\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Find all references the target record id @rid",
      onlineHelp = "SQL-Find-References")
  public void findReferences(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("find", iCommandText, "\nFound %s in %f sec(s).\n", true);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Alter a database property",
      onlineHelp = "SQL-Alter-Database")
  public void alterDatabase(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("alter", iCommandText, "\nDatabase updated successfully.\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      description = "Freeze database and flush on the disk",
      onlineHelp = "Console-Command-Freeze-Database")
  public void freezeDatabase(
      @ConsoleParameter(
          name = "storage-type",
          description = "Storage type of server database",
          optional = true)
      String storageType)
      throws IOException {
    checkForDatabase();

    final var dbName = currentDatabaseSession.getDatabaseName();

    if (currentDatabaseSession.isRemote()) {
      if (storageType == null) {
        storageType = "disk";
      }

      new ServerAdmin(currentDatabaseSession.getURL())
          .connect(currentDatabaseUserName, currentDatabaseUserPassword)
          .freezeDatabase(storageType);
    } else {
      // LOCAL CONNECTION
      currentDatabaseSession.freeze();
    }

    message("\n\nDatabase '" + dbName + "' was frozen successfully");
  }

  @ConsoleCommand(
      description = "Release database after freeze",
      onlineHelp = "Console-Command-Release-Db")
  public void releaseDatabase(
      @ConsoleParameter(
          name = "storage-type",
          description = "Storage type of server database",
          optional = true)
      String storageType)
      throws IOException {
    checkForDatabase();

    final var dbName = currentDatabaseSession.getDatabaseName();

    if (currentDatabaseSession.isRemote()) {
      if (storageType == null) {
        storageType = "disk";
      }

      new ServerAdmin(currentDatabaseSession.getURL())
          .connect(currentDatabaseUserName, currentDatabaseUserPassword)
          .releaseDatabase(storageType);
    } else {
      // LOCAL CONNECTION
      currentDatabaseSession.release();
    }

    message("\n\nDatabase '" + dbName + "' was released successfully");
  }

  @ConsoleCommand(description = "Flushes all database content to the disk")
  public void flushDatabase(
      @ConsoleParameter(
          name = "storage-type",
          description = "Storage type of server database",
          optional = true)
      String storageType)
      throws IOException {
    freezeDatabase(storageType);
    releaseDatabase(storageType);
  }

  @ConsoleCommand(splitInWords = false, description = "Alter a class in the database schema")
  public void alterClass(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("alter", iCommandText, "\nClass updated successfully.\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a class",
      onlineHelp = "SQL-Create-Class")
  public void createClass(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nClass created successfully.\n", true);
    updateDatabaseInfo();
  }

  @ConsoleCommand(splitInWords = false, description = "Create a sequence in the database")
  public void createSequence(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nSequence created successfully.\n", true);
    updateDatabaseInfo();
  }

  @ConsoleCommand(splitInWords = false, description = "Alter an existent sequence in the database")
  public void alterSequence(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("alter", iCommandText, "\nSequence altered successfully.\n", true);
    updateDatabaseInfo();
  }

  @ConsoleCommand(splitInWords = false, description = "Remove a sequence from the database")
  public void dropSequence(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("drop", iCommandText, "Sequence removed successfully.\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a user",
      onlineHelp = "SQL-Create-User")
  public void createUser(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nUser created successfully.\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(splitInWords = false, description = "Drop a user", onlineHelp = "SQL-Drop-User")
  public void dropUser(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("drop", iCommandText, "\nUser dropped successfully.\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Alter a class property in the database schema",
      onlineHelp = "SQL-Alter-Property")
  public void alterProperty(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("alter", iCommandText, "\nProperty updated successfully.\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a property",
      onlineHelp = "SQL-Create-Property")
  public void createProperty(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nProperty created successfully.\n", true);
    updateDatabaseInfo();
  }

  /**
   * * Creates a function.
   *
   * @param iCommandText the command text to execute
   */
  @ConsoleCommand(
      splitInWords = false,
      description = "Create a stored function",
      onlineHelp = "SQL-Create-Function")
  public void createFunction(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nFunction created successfully with id=%s.\n", true);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Traverse records and display the results",
      onlineHelp = "SQL-Traverse")
  public void traverse(
      @ConsoleParameter(name = "query-text", description = "The traverse to execute")
      String iQueryText) {
    final int limit;
    if (iQueryText.toLowerCase(Locale.ENGLISH).contains(" limit ")) {
      // RESET CONSOLE FLAG
      limit = -1;
    } else {
      limit = Integer.parseInt(properties.get(ConsoleProperties.LIMIT));
    }

    var start = System.currentTimeMillis();
    var rs = currentDatabaseSession.execute("traverse " + iQueryText);
    currentResultSet = rs.stream().map(x -> new RawPair<RID, Object>(x.getIdentity(), x.toMap()))
        .toList();
    rs.close();

    var elapsedSeconds = getElapsedSecs(start);

    dumpResultSet(limit);

    message(
        "\n\n"
            + currentResultSet.size()
            + " item(s) found. Traverse executed in "
            + elapsedSeconds
            + " sec(s).");
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Execute a query against the database and display the results",
      onlineHelp = "SQL-Query")
  public void select(
      @ConsoleParameter(name = "query-text", description = "The query to execute")
      String queryText) {
    checkForDatabase();

    if (queryText == null) {
      return;
    }

    queryText = queryText.trim();

    if (queryText.isEmpty() || queryText.equalsIgnoreCase("select")) {
      return;
    }

    queryText = "select " + queryText;

    final int displayLimit;
    if (queryText.toLowerCase(Locale.ENGLISH).contains(" limit ")) {
      displayLimit = -1;
    } else {
      // USE LIMIT + 1 TO DISCOVER IF MORE ITEMS ARE PRESENT
      displayLimit = Integer.parseInt(properties.get(ConsoleProperties.LIMIT));
    }

    final var start = System.currentTimeMillis();
    List<RawPair<RID, Object>> result = new ArrayList<>();
    try (var rs = currentDatabaseSession.query(queryText)) {
      var count = 0;
      while (rs.hasNext()) {
        var item = rs.next();
        if (item.isBlob()) {
          result.add(new RawPair<>(item.getIdentity(), item.asBlob().toStream()));
        } else {
          result.add(new RawPair<>(item.getIdentity(), item.toMap()));
        }
      }
    }
    currentResultSet = result;

    var elapsedSeconds = getElapsedSecs(start);

    dumpResultSet(displayLimit);

    long tot =
        displayLimit > -1
            ? Math.min(currentResultSet.size(), displayLimit)
            : currentResultSet.size();
    message("\n\n" + tot + " item(s) found. Query executed in " + elapsedSeconds + " sec(s).");
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Execute a MATCH query against the database and display the results",
      onlineHelp = "SQL-Match")
  public void match(
      @ConsoleParameter(name = "query-text", description = "The query to execute")
      String queryText) {
    checkForDatabase();

    if (queryText == null) {
      return;
    }

    queryText = queryText.trim();
    if (queryText.isEmpty() || queryText.equalsIgnoreCase("match")) {
      return;
    }

    queryText = "match " + queryText;

    final int queryLimit;
    final int displayLimit;
    if (queryText.toLowerCase(Locale.ENGLISH).contains(" limit ")) {
      queryLimit = -1;
      displayLimit = -1;
    } else {
      // USE LIMIT + 1 TO DISCOVER IF MORE ITEMS ARE PRESENT
      displayLimit = Integer.parseInt(properties.get(ConsoleProperties.LIMIT));
      queryLimit = displayLimit + 1;
    }

    final var start = System.currentTimeMillis();
    List<RawPair<RID, Object>> result = new ArrayList<>();
    var rs = currentDatabaseSession.query(queryText);
    var count = 0;
    while (rs.hasNext() && (queryLimit < 0 || count < queryLimit)) {
      var resultItem = rs.next();
      result.add(new RawPair<>(resultItem.getIdentity(), resultItem.toMap()));
    }
    rs.close();
    currentResultSet = result;

    var elapsedSeconds = getElapsedSecs(start);

    dumpResultSet(displayLimit);

    long tot =
        displayLimit > -1
            ? Math.min(currentResultSet.size(), displayLimit)
            : currentResultSet.size();
    message("\n\n" + tot + " item(s) found. Query executed in " + elapsedSeconds + " sec(s).");
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Execute a script containing multiple commands separated by ; or new line")
  public void script(
      @ConsoleParameter(name = "text", description = "Commands to execute, one per line")
      String iText) {
    final String language;
    final var languageEndPos = iText.indexOf(';');
    var splitted = iText.split(" ")[0].split(";")[0].split("\n")[0].split("\t");
    language = splitted[0];
    iText = iText.substring(language.length() + 1);
    if (iText.trim().isEmpty()) {
      throw new IllegalArgumentException(
          "Missing language in script (sql, js, gremlin, etc.) as first argument");
    }

    executeServerSideScript(language, iText);
  }

  @ConsoleCommand(splitInWords = false, description = "Execute javascript commands in the console")
  public void js(
      @ConsoleParameter(
          name = "text",
          description =
              "The javascript to execute. Use 'db' to reference to a database, 'gdb'"
                  + " for a graph database") final String iText) {
    if (iText == null) {
      return;
    }

    resetResultSet();

    var start = System.currentTimeMillis();
    currentResultSet = currentDatabaseSession.runScript("JavaScript", iText).stream()
        .map(result -> new RawPair<RID, Object>(result.getIdentity(), result.toMap())).toList();
    var elapsedSeconds = getElapsedSecs(start);

    dumpResultSet(-1);
    message(
        String.format(
            "\nClient side script executed in %f sec(s). Returned %d records",
            elapsedSeconds, currentResultSet.size()));
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Execute javascript commands against a remote server")
  public void jss(
      @ConsoleParameter(
          name = "text",
          description =
              "The javascript to execute. Use 'db' to reference to a database, 'gdb'"
                  + " for a graph database") final String iText) {
    checkForRemoteServer();

    executeServerSideScript("javascript", iText);
  }

  @ConsoleCommand(
      description =
          "Set a server user. If the user already exists, the password and permissions are updated.",
      onlineHelp = "Console-Command-Set-Server-User")
  public void setServerUser(
      @ConsoleParameter(name = "user-name", description = "User name") String iServerUserName,
      @ConsoleParameter(name = "user-password", description = "User password")
      String iServerUserPasswd,
      @ConsoleParameter(
          name = "user-permissions",
          description =
              "User permissions")
      String iPermissions) {

    if (iServerUserName == null || iServerUserName.isEmpty()) {
      throw new IllegalArgumentException("User name null or empty");
    }

    if (iPermissions == null || iPermissions.isEmpty()) {
      throw new IllegalArgumentException("User permissions null or empty");
    }

    final var serverCfgFile = new File("../config/youtrackdb-server-config.xml");
    if (!serverCfgFile.exists()) {
      throw new ConfigurationException(currentDatabaseSession,
          "Cannot access to file " + serverCfgFile);
    }

    try {
      final var serverCfg = new ServerConfigurationManager(serverCfgFile);

      final var defAlgo =
          GlobalConfiguration.SECURITY_USER_PASSWORD_DEFAULT_ALGORITHM.getValueAsString();

      final var hashedPassword = SecurityManager.createHash(iServerUserPasswd, defAlgo, true);

      serverCfg.setUser(iServerUserName, hashedPassword, iPermissions);
      serverCfg.saveConfiguration();

      message(String.format("\nServer user '%s' set correctly", iServerUserName));

    } catch (Exception e) {
      error(String.format("\nError on loading %s file: %s", serverCfgFile, e));
    }
  }

  @ConsoleCommand(
      description =
          "Drop a server user.",
      onlineHelp = "Console-Command-Drop-Server-User")
  public void dropServerUser(
      @ConsoleParameter(name = "user-name", description = "User name") String iServerUserName) {

    if (iServerUserName == null || iServerUserName.isEmpty()) {
      throw new IllegalArgumentException("User name null or empty");
    }

    final var serverCfgFile = new File("../config/youtrackdb-server-config.xml");
    if (!serverCfgFile.exists()) {
      throw new ConfigurationException(currentDatabaseSession,
          "Cannot access to file " + serverCfgFile);
    }

    try {
      final var serverCfg = new ServerConfigurationManager(serverCfgFile);

      if (!serverCfg.existsUser(iServerUserName)) {
        error(String.format("\nServer user '%s' not found in configuration", iServerUserName));
        return;
      }

      serverCfg.dropUser(iServerUserName);
      serverCfg.saveConfiguration();

      message(String.format("\nServer user '%s' dropped correctly", iServerUserName));

    } catch (Exception e) {
      error(String.format("\nError on loading %s file: %s", serverCfgFile, e));
    }
  }

  @ConsoleCommand(
      description =
          "Display all the server user names.",
      onlineHelp = "Console-Command-List-Server-User")
  public void listServerUsers() {
    final var serverCfgFile = new File("../config/youtrackdb-server-config.xml");
    if (!serverCfgFile.exists()) {
      throw new ConfigurationException(currentDatabaseSession,
          "Cannot access to file " + serverCfgFile);
    }

    try {
      final var serverCfg = new ServerConfigurationManager(serverCfgFile);

      message("\nSERVER USERS\n");
      final var users = serverCfg.getUsers();
      if (users.isEmpty()) {
        message("\nNo users found");
      } else {
        for (var u : users) {
          message(String.format("\n- '%s', permissions: %s", u.name, u.resources));
        }
      }

    } catch (Exception e) {
      error(String.format("\nError on loading %s file: %s", serverCfgFile, e));
    }
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create an index against a property",
      onlineHelp = "SQL-Create-Index")
  public void createIndex(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText)
      throws IOException {
    message("\n\nCreating index...");

    sqlCommand("create", iCommandText, "\nCreated index successfully in %f sec(s).\n", false);
    updateDatabaseInfo();
    message("\n\nIndex created successfully");
  }

  @ConsoleCommand(
      description = "Delete the current database",
      onlineHelp = "Console-Command-Drop-Database")
  public void dropDatabase(
      @ConsoleParameter(
          name = "storage-type",
          description = "Storage type of server database",
          optional = true)
      String storageType)
      throws IOException {
    checkForDatabase();

    final var dbName = currentDatabaseSession.getDatabaseName();
    currentDatabaseSession.close();
    if (storageType != null
        && !"disk".equalsIgnoreCase(storageType)
        && !"local".equalsIgnoreCase(storageType)
        && !"memory".equalsIgnoreCase(storageType)) {
      message("\n\nInvalid storage type for db: '" + storageType + "'");
      return;
    }
    youTrackDB.drop(dbName);
    currentDatabaseSession = null;
    currentDatabaseName = null;
    message("\n\nDatabase '" + dbName + "' deleted successfully");
  }

  @ConsoleCommand(
      description = "Delete the specified database",
      onlineHelp = "Console-Command-Drop-Database")
  public void dropDatabase(
      @ConsoleParameter(
          name = "database-url",
          description = "The url of the database to drop in the format '<mode>:<path>'")
      String iDatabaseURL,
      @ConsoleParameter(name = "user", description = "Server administrator name") String iUserName,
      @ConsoleParameter(name = "password", description = "Server administrator password")
      String iUserPassword,
      @ConsoleParameter(
          name = "storage-type",
          description = "Storage type of server database",
          optional = true)
      String storageType)
      throws IOException {

    connect(iDatabaseURL, iUserName, iUserPassword);
    dropDatabase(null);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Remove an index",
      onlineHelp = "SQL-Drop-Index")
  public void dropIndex(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText)
      throws IOException {
    message("\n\nRemoving index...");

    sqlCommand("drop", iCommandText, "\nDropped index in %f sec(s).\n", false);
    updateDatabaseInfo();
    message("\n\nIndex removed successfully");
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Rebuild an index if it is automatic",
      onlineHelp = "SQL-Rebuild-Index")
  public void rebuildIndex(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText)
      throws IOException {
    message("\n\nRebuilding index(es)...");

    sqlCommand(
        "rebuild", iCommandText, "\nRebuilt index(es). Found %d link(s) in %f sec(s).\n", true);
    updateDatabaseInfo();
    message("\n\nIndex(es) rebuilt successfully");
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Remove a class from the schema",
      onlineHelp = "SQL-Drop-Class")
  public void dropClass(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText)
      throws IOException {
    sqlCommand("drop", iCommandText, "\nRemoved class in %f sec(s).\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Remove a property from a class",
      onlineHelp = "SQL-Drop-Property")
  public void dropProperty(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText)
      throws IOException {
    sqlCommand("drop", iCommandText, "\nRemoved class property in %f sec(s).\n", false);
    updateDatabaseInfo();
  }

  @ConsoleCommand(
      description = "Browse all records of a class",
      onlineHelp = "Console-Command-Browse-Class")
  public void browseClass(
      @ConsoleParameter(name = "class-name", description = "The name of the class") final String iClassName) {
    checkForDatabase();

    resetResultSet();

    final var it = currentDatabaseSession.browseClass(iClassName);

    browseRecords(it);
  }

  @ConsoleCommand(
      description = "Browse all records of a cluster",
      onlineHelp = "Console-Command-Browse-Cluster")
  public void browseCluster(
      @ConsoleParameter(name = "cluster-name", description = "The name of the cluster") final String iClusterName) {
    checkForDatabase();

    resetResultSet();

    final RecordIteratorCluster<?> it = currentDatabaseSession.browseCluster(iClusterName);

    browseRecords(it);
  }

  @ConsoleCommand(
      aliases = {"status"},
      description = "Display information about the database",
      onlineHelp = "Console-Command-Info")
  public void info() {
    if (currentDatabaseName != null) {
      message(
          "\nCurrent database: " + currentDatabaseName + " (url=" + currentDatabaseSession.getURL()
              + ")");

      currentDatabaseSession.getMetadata().reload();

      listProperties();
      listClusters(null);
      listClasses();
      listIndexes();
    }
  }

  @ConsoleCommand(description = "Display the database properties")
  public void listProperties() {
    if (currentDatabaseSession == null) {
      return;
    }

    final var dbCfg = currentDatabaseSession.getStorageInfo().getConfiguration();

    message("\n\nDATABASE PROPERTIES");

    if (dbCfg.getProperties() != null) {
      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      if (dbCfg.getName() != null) {
        resultSet.add(new RawPair<>(null,
            Map.of("NAME", "Name", "VALUE", dbCfg.getName())));
      }

      resultSet.add(new RawPair<>(null, Map.of("NAME", "Version"
          , "VALUE", dbCfg.getVersion())));
      resultSet.add(
          new RawPair<>(null, Map.of("NAME", "Date-Format",
              "VALUE", dbCfg.getDateFormat())));
      resultSet.add(
          new RawPair<>(null, Map.of("NAME", "Datetime-Format"
              , "VALUE", dbCfg.getDateTimeFormat())));
      resultSet.add(new RawPair<>(null, Map.of("NAME", "Timezone",
          "VALUE", dbCfg.getTimeZone().getID())));
      resultSet.add(new RawPair<>(null, Map.of("NAME", "Locale-Country",
          "VALUE", dbCfg.getLocaleCountry())));
      resultSet.add(new RawPair<>(null, Map.of("NAME", "Locale-Language",
          "VALUE", dbCfg.getLocaleLanguage())));
      resultSet.add(new RawPair<>(null, Map.of("NAME", "Charset"
          , "VALUE", dbCfg.getCharset())));
      resultSet.add(new RawPair<>(null,
          Map.of("NAME", "Schema-RID", "VALUE", dbCfg.getSchemaRecordId())));
      resultSet.add(
          new RawPair<>(null,
              Map.of("NAME", "Index-Manager-RID", "VALUE", dbCfg.getIndexMgrRecordId())));

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);

      message("\n");

      if (!dbCfg.getProperties().isEmpty()) {
        message("\n\nDATABASE CUSTOM PROPERTIES:");

        final List<RawPair<RID, Object>> dbResultSet = new ArrayList<>();
        for (var cfg : dbCfg.getProperties()) {
          dbResultSet.add(
              new RawPair<>(null, Map.of("NAME", cfg.name, "VALUE", cfg.value)));
        }

        final var dbFormatter = new TableFormatter(this);
        formatter.setMaxWidthSize(getConsoleWidth());
        formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

        dbFormatter.writeRecords(dbResultSet, -1, currentDatabaseSession);
      }
    }
  }

  @ConsoleCommand(
      aliases = {"desc"},
      description = "Display a class in the schema",
      onlineHelp = "Console-Command-Info-Class")
  public void infoClass(
      @ConsoleParameter(name = "class-name", description = "The name of the class") final String iClassName) {
    checkForDatabase();

    currentDatabaseSession.getMetadata().reload();

    final var cls =
        currentDatabaseSession.getMetadata().getImmutableSchemaSnapshot()
            .getClassInternal(iClassName);

    if (cls == null) {
      message(
          "\n! Class '"
              + iClassName
              + "' does not exist in the database '"
              + currentDatabaseName
              + "'");
      return;
    }

    message("\nCLASS '" + cls.getName() + "'\n");

    final var count = currentDatabaseSession.countClass(cls.getName(), false);
    message("\nRecords..............: " + count);

    if (cls.hasSuperClasses()) {
      message("\nSuper classes........: " + Arrays.toString(
          cls.getSuperClassesNames().toArray()));
    }

    message(
        "\nDefault cluster......: "
            + currentDatabaseSession.getClusterNameById(
            cls.getClusterIds()[0])
            + " (id="
            + cls.getClusterIds()[0]
            + ")");

    final var clusters = new StringBuilder();
    for (var clId : cls.getClusterIds()) {
      if (!clusters.isEmpty()) {
        clusters.append(", ");
      }

      clusters.append(currentDatabaseSession.getClusterNameById(clId));
      clusters.append("(");
      clusters.append(clId);
      clusters.append(")");
    }

    message("\nSupported clusters...: " + clusters);
    if (!cls.getSubclasses().isEmpty()) {
      message("\nSubclasses.........: ");
      var i = 0;
      for (var c : cls.getSubclasses()) {
        if (i > 0) {
          message(", ");
        }
        message(c.getName());
        ++i;
      }
      out.println();
    }

    if (!cls.properties().isEmpty()) {
      message("\n\nPROPERTIES");
      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      for (final var p : cls.properties()) {
        try {
          var row = new HashMap<>();
          resultSet.add(new RawPair<>(null, row));

          row.put("NAME", p.getName());
          row.put("TYPE", p.getType());
          row.put(
              "LINKED-TYPE/CLASS",
              p.getLinkedClass() != null ? p.getLinkedClass(
              )
                  : p.getLinkedType());
          row.put("MANDATORY", p.isMandatory());
          row.put("READONLY", p.isReadonly());
          row.put("NOT-NULL", p.isNotNull());
          row.put("MIN",
              p.getMin() != null ? p.getMin() : "");
          row.put("MAX",
              p.getMax() != null ? p.getMax() : "");
          row.put("COLLATE",
              p.getCollate() != null ? p.getCollate()
                  .getName() : "");
          row.put("DEFAULT",
              p.getDefaultValue() != null ? p.getDefaultValue(
              ) : "");

        } catch (Exception ignored) {
        }
      }

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);
    }

    final var indexes = cls.getClassIndexes();
    if (!indexes.isEmpty()) {
      message("\n\nINDEXES (" + indexes.size() + " altogether)");

      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      for (final var index : indexes) {
        var row = new HashMap<>();
        resultSet.add(new RawPair<>(null, row));

        row.put("NAME", index);
      }

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);
    }

    if (!cls.getCustomKeys().isEmpty()) {
      message("\n\nCUSTOM ATTRIBUTES");

      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      for (final var k : cls.getCustomKeys()) {
        try {
          var row = new HashMap<>();
          resultSet.add(new RawPair<>(null, row));

          row.put("NAME", k);
          row.put("VALUE", cls.getCustom(k));

        } catch (Exception ignored) {
          // IGNORED
        }
      }

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);
    }
  }

  @ConsoleCommand(
      description = "Display a class property",
      onlineHelp = "Console-Command-Info-Property")
  public void infoProperty(
      @ConsoleParameter(
          name = "property-name",
          description = "The name of the property as <class>.<property>") final String iPropertyName) {
    checkForDatabase();

    if (iPropertyName.indexOf('.') == -1) {
      throw new SystemException("Property name is in the format <class>.<property>");
    }

    final var parts = iPropertyName.split("\\.");

    final var cls =
        currentDatabaseSession.getMetadata().getImmutableSchemaSnapshot()
            .getClassInternal(parts[0]);

    if (cls == null) {
      message(
          "\n! Class '"
              + parts[0]
              + "' does not exist in the database '"
              + currentDatabaseName
              + "'");
      return;
    }

    var prop = cls.getPropertyInternal(parts[1]);

    if (prop == null) {
      message("\n! Property '" + parts[1] + "' does not exist in class '" + parts[0] + "'");
      return;
    }

    message("\nPROPERTY '" + prop.getFullName() + "'\n");
    message("\nType.................: " + prop.getType());
    message("\nMandatory............: " + prop.isMandatory());
    message("\nNot null.............: " + prop.isNotNull());
    message("\nRead only............: " + prop.isReadonly());
    message("\nDefault value........: " + prop.getDefaultValue());
    message("\nMinimum value........: " + prop.getMin());
    message("\nMaximum value........: " + prop.getMax());
    message("\nREGEXP...............: " + prop.getRegexp());
    message("\nCollate..............: " + prop.getCollate());
    message("\nLinked class.........: " + prop.getLinkedClass());
    message("\nLinked type..........: " + prop.getLinkedType());

    if (!prop.getCustomKeys().isEmpty()) {
      message("\n\nCUSTOM ATTRIBUTES");

      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      for (final var k : prop.getCustomKeys()) {
        try {
          var row = new HashMap<>();
          resultSet.add(new RawPair<>(null, row));

          row.put("NAME", k);
          row.put("VALUE", prop.getCustom(k));

        } catch (Exception ignored) {
        }
      }

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);
    }

    if (currentDatabaseSession.isRemote()) {
      final var indexes = prop.getAllIndexes();
      if (!indexes.isEmpty()) {
        message("\n\nINDEXES (" + indexes.size() + " altogether)");

        final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

        for (final var index : indexes) {
          var row = new HashMap<>();
          resultSet.add(new RawPair<>(null, row));

          row.put("NAME", index);
        }
        final var formatter = new TableFormatter(this);
        formatter.setMaxWidthSize(getConsoleWidth());
        formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

        formatter.writeRecords(resultSet, -1, currentDatabaseSession);
      }
    }
  }

  @ConsoleCommand(
      description = "Display all indexes",
      aliases = {"indexes"},
      onlineHelp = "Console-Command-List-Indexes")
  public void listIndexes() {
    if (currentDatabaseName != null) {
      message("\n\nINDEXES");

      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      var totalIndexes = 0;
      long totalRecords = 0;

      final List<Index> indexes =
          new ArrayList<Index>(
              currentDatabaseSession.getMetadata().getIndexManagerInternal().getIndexes(
                  currentDatabaseSession));
      indexes.sort((o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName()));

      long totalIndexedRecords = 0;

      for (final var index : indexes) {
        var row = new HashMap<String, Object>();
        resultSet.add(new RawPair<>(null, row));

        final var indexSize = index.getSize(
            currentDatabaseSession); // getInternal doesn't work in remote...
        totalIndexedRecords += indexSize;

        row.put("NAME", index.getName());
        row.put("TYPE", index.getType());
        row.put("RECORDS", indexSize);
        try {
          final var indexDefinition = index.getDefinition();
          final var size = index.size(currentDatabaseSession);
          if (indexDefinition != null) {
            row.put("CLASS", indexDefinition.getClassName());
            row.put("COLLATE", indexDefinition.getCollate().getName());

            final var fields = indexDefinition.getFields();
            final var buffer = new StringBuilder();
            for (var i = 0; i < fields.size(); ++i) {
              if (!buffer.isEmpty()) {
                buffer.append(",");
              }

              buffer.append(fields.get(i));
              buffer.append("(");
              buffer.append(indexDefinition.getTypes()[i]);
              buffer.append(")");
            }

            row.put("FIELDS", buffer.toString());
          }

          totalIndexes++;
          totalRecords += size;
        } catch (Exception ignored) {
        }
      }

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.setColumnAlignment("RECORDS", TableFormatter.ALIGNMENT.RIGHT);

      var footer = Map.of("NAME", "TOTAL",
          "RECORDS", String.valueOf(totalIndexedRecords));
      formatter.setFooter(footer);

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);

    } else {
      message("\nNo database selected yet.");
    }
  }

  @ConsoleCommand(
      description = "Display all the configured clusters",
      aliases = {"clusters"},
      onlineHelp = "Console-Command-List-Clusters")
  public void listClusters(
      @ConsoleParameter(
          name = "[options]",
          optional = true,
          description = "Additional options, example: -v=verbose") final String options) {
    final var commandOptions = parseCommandOptions(options);

    if (currentDatabaseName != null) {
      message("\n\nCLUSTERS (collections)");

      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      int clusterId;
      long totalElements = 0;
      long totalSpaceUsed = 0;
      long totalTombstones = 0;
      long count;

      final List<String> clusters = new ArrayList<>(currentDatabaseSession.getClusterNames());
      Collections.sort(clusters);

      final var isRemote = currentDatabaseSession.isRemote();
      for (var clusterName : clusters) {
        try {
          var row = new HashMap<String, Object>();
          resultSet.add(new RawPair<>(null, row));

          clusterId = currentDatabaseSession.getClusterIdByName(clusterName);

          final var conflictStrategy =
              Optional.ofNullable(
                      currentDatabaseSession.getClusterRecordConflictStrategy(clusterId))
                  .orElse("");

          count = currentDatabaseSession.countClusterElements(clusterName);
          totalElements += count;

          final var cls =
              currentDatabaseSession
                  .getMetadata()
                  .getImmutableSchemaSnapshot()
                  .getClassByClusterId(clusterId);
          final var className = Optional.ofNullable(cls)
              .map(schemaClass -> schemaClass.getName())
              .orElse(null);

          row.put("NAME", clusterName);
          row.put("ID", clusterId);
          row.put("CLASS", className);
          row.put("COUNT", count);
        } catch (Exception e) {
          if (e instanceof YTIOException) {
            break;
          }
          throw e;
        }
      }

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.setColumnAlignment("ID", TableFormatter.ALIGNMENT.RIGHT);
      formatter.setColumnAlignment("COUNT", TableFormatter.ALIGNMENT.RIGHT);

      var footer = new HashMap<String, String>();
      footer.put("NAME", "TOTAL");
      footer.put("COUNT", String.valueOf(totalElements));
      formatter.setFooter(footer);

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);

      message("\n");

    } else {
      message("\nNo database selected yet.");
    }
  }

  @ConsoleCommand(
      description = "Display all the configured classes",
      aliases = {"classes"},
      onlineHelp = "Console-Command-List-Classes")
  public void listClasses() {
    if (currentDatabaseName != null) {
      message("\n\nCLASSES");

      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      long totalElements = 0;
      long count;

      currentDatabaseSession.getMetadata().reload();
      final List<SchemaClass> classes =
          new ArrayList<>(
              currentDatabaseSession.getMetadata().getImmutableSchemaSnapshot()
                  .getClasses());
      classes.sort(
          (o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName(
          )));

      for (var cls : classes) {
        try {
          final var row = new HashMap<String, Object>();
          resultSet.add(new RawPair<>(null, row));

          final var clusters = new StringBuilder(1024);
          if (cls.isAbstract()) {
            clusters.append("-");
          } else {
            var clusterIds = cls.getClusterIds();
            for (var i = 0; i < clusterIds.length; ++i) {
              if (i > 0) {
                clusters.append(",");
              }

              clusters.append(currentDatabaseSession.getClusterNameById(clusterIds[i]));
              clusters.append("(");
              clusters.append(clusterIds[i]);
              clusters.append(")");
            }
          }

          count = currentDatabaseSession.countClass(cls.getName(), false);
          totalElements += count;

          final var superClasses =
              cls.hasSuperClasses() ? Arrays.toString(
                  cls.getSuperClassesNames().toArray()) : "";

          row.put("NAME", cls.getName());
          row.put("SUPER-CLASSES", superClasses);
          row.put("CLUSTERS", clusters);
          row.put("COUNT", count);

        } catch (Exception ignored) {
          // IGNORED
        }
      }

      final var formatter = new TableFormatter(this);

      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.setColumnAlignment("COUNT", TableFormatter.ALIGNMENT.RIGHT);

      var footer = new HashMap<String, String>();
      footer.put("NAME", "TOTAL");
      footer.put("COUNT", String.valueOf(totalElements));
      formatter.setFooter(footer);

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);

      message("\n");

    } else {
      message("\nNo database selected yet.");
    }
  }

  @ConsoleCommand(description = "Check database integrity", splitInWords = false)
  public void checkDatabase(
      @ConsoleParameter(name = "options", description = "Options: -v", optional = true) final String iOptions)
      throws IOException {
    checkForDatabase();

    if (currentDatabaseSession.getStorage().isRemote()) {
      message("\nCannot check integrity of non-local database. Connect to it using local mode.");
      return;
    }

    var verbose = iOptions != null && iOptions.contains("-v");

    message("\nChecking storage.");
    try {
      ((AbstractPaginatedStorage) currentDatabaseSession.getStorage()).check(verbose, this);
    } catch (DatabaseImportException e) {
      printError(e);
    }
  }

  @ConsoleCommand(description = "Repair database structure", splitInWords = false)
  public void repairDatabase(
      @ConsoleParameter(
          name = "options",
          description =
              "Options: [--fix-graph] [--force-embedded-ridbags] [--fix-links] [-v]]"
                  + " [--fix-ridbags] [--fix-bonsai]",
          optional = true)
      String iOptions)
      throws IOException {
    checkForDatabase();
    final var force_embedded =
        iOptions == null || iOptions.contains("--force-embedded-ridbags");
    final var fix_graph = iOptions == null || iOptions.contains("--fix-graph");
    if (force_embedded) {
      GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(Integer.MAX_VALUE);
      GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(Integer.MAX_VALUE);
    }
    if (fix_graph || force_embedded) {
      // REPAIR GRAPH
      final var options = parseOptions(iOptions);
      new GraphRepair().repair(currentDatabaseSession, this, options);
    }

    final var fix_links = iOptions == null || iOptions.contains("--fix-links");
    if (fix_links) {
      // REPAIR DATABASE AT LOW LEVEL
      var verbose = iOptions != null && iOptions.contains("-v");

      new DatabaseRepair(currentDatabaseSession)
          .setDatabaseSession(currentDatabaseSession)
          .setOutputListener(
              new CommandOutputListener() {
                @Override
                public void onMessage(String iText) {
                  message(iText);
                }
              })
          .setVerbose(verbose)
          .run();
    }

    if (!currentDatabaseSession.getURL().startsWith("disk")) {
      message("\n fix-bonsai can be run only on disk connection \n");
      return;
    }

    final var fix_ridbags = iOptions == null || iOptions.contains("--fix-ridbags");
    final var fix_bonsai = iOptions == null || iOptions.contains("--fix-bonsai");
    if (fix_ridbags || fix_bonsai || force_embedded) {
      var repairer = new BonsaiTreeRepair();
      BonsaiTreeRepair.repairDatabaseRidbags(currentDatabaseSession, this);
    }
  }

  @ConsoleCommand(description = "Compare two databases")
  public void compareDatabases(
      @ConsoleParameter(name = "db1-url", description = "URL of the first database") final String iDb1URL,
      @ConsoleParameter(name = "db2-url", description = "URL of the second database") final String iDb2URL,
      @ConsoleParameter(name = "username", description = "User name", optional = false) final String iUserName,
      @ConsoleParameter(name = "password", description = "User password", optional = false) final String iUserPassword,
      @ConsoleParameter(
          name = "detect-mapping-data",
          description =
              "Whether RID mapping data after DB import should be tried to found on the disk",
          optional = true)
      String autoDiscoveringMappingData)
      throws IOException {
    var firstUrl = URLHelper.parseNew(iDb1URL);
    var secondUrl = URLHelper.parseNew(iDb2URL);
    YouTrackDB firstContext =
        new YouTrackDBImpl(
            firstUrl.getType() + ":" + firstUrl.getPath(),
            iUserName,
            iUserPassword,
            YouTrackDBConfig.defaultConfig());
    YouTrackDB secondContext;
    if (!firstUrl.getType().equals(secondUrl.getType())
        || !firstUrl.getPath().equals(secondUrl.getPath())) {
      secondContext =
          new YouTrackDBImpl(
              secondUrl.getType() + ":" + secondUrl.getPath(),
              iUserName,
              iUserPassword,
              YouTrackDBConfig.defaultConfig());
    } else {
      secondContext = firstContext;
    }
    try (var firstDB =
        (DatabaseSessionInternal)
            firstContext.open(firstUrl.getDbName(), iUserName, iUserPassword)) {

      try (var secondDB =
          (DatabaseSessionInternal)
              secondContext.open(secondUrl.getDbName(), iUserName, iUserPassword)) {
        final var compare = new DatabaseCompare(firstDB, secondDB, this);

        compare.setAutoDetectExportImportMap(
            autoDiscoveringMappingData == null || Boolean.parseBoolean(autoDiscoveringMappingData));
        compare.setCompareIndexMetadata(true);
        compare.compare();
      } catch (DatabaseExportException e) {
        printError(e);
      }
    }
    firstContext.close();
    secondContext.close();
  }

  @ConsoleCommand(
      description = "Load a sql script into the current database",
      splitInWords = true,
      onlineHelp = "Console-Command-Load-Script")
  public void loadScript(
      @ConsoleParameter(name = "scripPath", description = "load script scriptPath") final String scriptPath)
      throws IOException {

    checkForDatabase();

    message("\nLoading script " + scriptPath + "...");

    executeBatch(scriptPath);

    message("\nLoaded script " + scriptPath);
  }

  @ConsoleCommand(
      description = "Import a database into the current one",
      splitInWords = false,
      onlineHelp = "Console-Command-Import")
  public void importDatabase(
      @ConsoleParameter(name = "options", description = "Import options") final String text)
      throws IOException {
    checkForDatabase();

    message("\nImporting database " + text + "...");

    final var items = StringSerializerHelper.smartSplit(text, ' ');
    final var fileName =
        items.size() <= 0 || (items.get(1)).charAt(0) == '-' ? null : items.get(1);
    final var options =
        fileName != null
            ? text.substring((items.get(0)).length() + (items.get(1)).length() + 1).trim()
            : text;

    try {
      if (currentDatabaseSession.isRemote()) {
        var databaseImport =
            new DatabaseImportRemote(currentDatabaseSession, fileName, this);

        databaseImport.setOptions(options);
        databaseImport.importDatabase();
        databaseImport.close();

      } else {
        var databaseImport = new DatabaseImport(currentDatabaseSession, fileName, this);

        databaseImport.setOptions(options);
        databaseImport.importDatabase();
        databaseImport.close();
      }
    } catch (DatabaseImportException e) {
      printError(e);
    }
  }

  @ConsoleCommand(
      description = "Backup a database",
      splitInWords = false,
      onlineHelp = "Console-Command-Backup")
  public void backupDatabase(
      @ConsoleParameter(name = "options", description = "Backup options") final String iText)
      throws IOException {
    checkForDatabase();

    final var items = StringSerializerHelper.smartSplit(iText, ' ', ' ');

    if (items.size() < 2) {
      try {
        syntaxError("backupDatabase", getClass().getMethod("backupDatabase", String.class));
      } catch (NoSuchMethodException ignored) {
      }
      return;
    }

    final var fileName =
        items.get(1).charAt(0) == '-' ? null : items.get(1);

    if (fileName == null || fileName.trim().isEmpty()) {
      try {
        syntaxError("backupDatabase", getClass().getMethod("backupDatabase", String.class));
        return;
      } catch (NoSuchMethodException ignored) {
      }
    }

    var bufferSize = Integer.parseInt(properties.get(ConsoleProperties.BACKUP_BUFFER_SIZE));
    var compressionLevel =
        Integer.parseInt(properties.get(ConsoleProperties.BACKUP_COMPRESSION_LEVEL));

    for (var i = 2; i < items.size(); ++i) {
      final var item = items.get(i);
      final var sep = item.indexOf('=');

      final String parName;
      final String parValue;
      if (sep > -1) {
        parName = item.substring(1, sep);
        parValue = item.substring(sep + 1);
      } else {
        parName = item.substring(1);
        parValue = null;
      }

      if (parName.equalsIgnoreCase("bufferSize")) {
        bufferSize = Integer.parseInt(parValue);
      } else if (parName.equalsIgnoreCase("compressionLevel")) {
        compressionLevel = Integer.parseInt(parValue);
      }
    }

    final var startTime = System.currentTimeMillis();
    String fName = null;
    try {
      out.println(
          "Executing incremental backup of database '"
              + currentDatabaseName
              + "' to: "
              + iText
              + "...");
      fName = currentDatabaseSession.incrementalBackup(Path.of(fileName));

      message(
          String.format(
              "\nIncremental Backup executed in %.2f seconds stored in file %s",
              ((float) (System.currentTimeMillis() - startTime) / 1000), fName));
    } catch (DatabaseExportException e) {
      printError(e);
    }
  }

  @ConsoleCommand(
      description = "Export a database",
      splitInWords = false,
      onlineHelp = "Console-Command-Export")
  public void exportDatabase(
      @ConsoleParameter(name = "options", description = "Export options") final String iText)
      throws IOException {
    checkForDatabase();

    out.println("Exporting current database to: " + iText + " in GZipped JSON format ...");
    final var items = StringSerializerHelper.smartSplit(iText, ' ');
    final var fileName =
        items.size() <= 1 || items.get(1).charAt(0) == '-' ? null : items.get(1);
    final var options =
        fileName != null
            ? iText.substring(items.get(0).length() + items.get(1).length() + 1).trim()
            : iText;

    try {
      new DatabaseExport(currentDatabaseSession, fileName, this)
          .setOptions(options)
          .exportDatabase()
          .close();
    } catch (DatabaseExportException e) {
      printError(e);
    }
  }

  @ConsoleCommand(description = "Return all configured properties")
  public void properties() {
    message("\nPROPERTIES:");

    final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

    for (var p : properties.entrySet()) {
      final var row = new HashMap<>();
      resultSet.add(new RawPair<>(null, row));

      row.put("NAME", p.getKey());
      row.put("VALUE", p.getValue());
    }

    final var formatter = new TableFormatter(this);
    formatter.setMaxWidthSize(getConsoleWidth());
    formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

    formatter.writeRecords(resultSet, -1, currentDatabaseSession);

    message("\n");
  }

  @ConsoleCommand(description = "Return the value of a property")
  public void get(
      @ConsoleParameter(name = "property-name", description = "Name of the property") final String iPropertyName) {
    Object value = properties.get(iPropertyName);

    out.println();

    if (value == null) {
      message("\nProperty '" + iPropertyName + "' is not setted");
    } else {
      out.println(iPropertyName + " = " + value);
    }
  }

  @ConsoleCommand(
      description = "Change the value of a property",
      onlineHelp = "Console-Command-Set")
  public void set(
      @ConsoleParameter(name = "property-name", description = "Name of the property") final String iPropertyName,
      @ConsoleParameter(name = "property-value", description = "Value to set") final String iPropertyValue) {
    Object prevValue = properties.get(iPropertyName);

    out.println();

    if (iPropertyName.equalsIgnoreCase("limit")
        && (Integer.parseInt(iPropertyValue) == 0 || Integer.parseInt(iPropertyValue) < -1)) {
      message("\nERROR: Limit must be > 0 or = -1 (no limit)");
    } else {

      if (prevValue != null) {
        message("\nPrevious value was: " + prevValue);
      }

      properties.put(iPropertyName, iPropertyValue);

      out.println();
      out.println(iPropertyName + " = " + iPropertyValue);
    }
  }

  @ConsoleCommand(description = "Return the value of a configuration value")
  public void configGet(
      @ConsoleParameter(name = "config-name", description = "Name of the configuration") final String iConfigName)
      throws IOException {
    final var config = GlobalConfiguration.findByKey(iConfigName);
    if (config == null) {
      throw new IllegalArgumentException(
          "Configuration variable '" + iConfigName + "' wasn't found");
    }

    final String value;
    if (!YouTrackDBInternal.extract(youTrackDB).isEmbedded()) {
      value =
          ((YouTrackDBRemote) YouTrackDBInternal.extract(youTrackDB))
              .getGlobalConfiguration(currentDatabaseUserName, currentDatabaseUserPassword, config);
      message("\nRemote configuration: ");
    } else {
      value = config.getValueAsString();
      message("\nLocal configuration: ");
    }
    out.println(iConfigName + " = " + value);
  }

  @SuppressWarnings("MethodMayBeStatic")
  @ConsoleCommand(description = "Sleep X milliseconds")
  public void sleep(final String iTime) {
    try {
      Thread.sleep(Long.parseLong(iTime));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @ConsoleCommand(description = "Change the value of a configuration value")
  public void configSet(
      @ConsoleParameter(name = "config-name", description = "Name of the configuration") final String iConfigName,
      @ConsoleParameter(name = "config-value", description = "Value to set") final String iConfigValue)
      throws IOException {
    final var config = GlobalConfiguration.findByKey(iConfigName);
    if (config == null) {
      throw new IllegalArgumentException("Configuration variable '" + iConfigName + "' not found");
    }

    if (youTrackDB != null && !YouTrackDBInternal.extract(youTrackDB).isEmbedded()) {
      ((YouTrackDBRemote) YouTrackDBInternal.extract(youTrackDB))
          .setGlobalConfiguration(
              currentDatabaseUserName, currentDatabaseUserPassword, config, iConfigValue);
      message("\nRemote configuration value changed correctly");
    } else {
      config.setValue(iConfigValue);
      message("\nLocal configuration value changed correctly");
    }
    out.println();
  }

  @ConsoleCommand(description = "Return all the configuration values")
  public void config() throws IOException {
    if (!YouTrackDBInternal.extract(youTrackDB).isEmbedded()) {
      final var values =
          ((YouTrackDBRemote) YouTrackDBInternal.extract(youTrackDB))
              .getGlobalConfigurations(currentDatabaseUserName, currentDatabaseUserPassword);

      message("\nREMOTE SERVER CONFIGURATION");

      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      for (var p : values.entrySet()) {
        var row = new HashMap<String, Object>();
        resultSet.add(new RawPair<>(null, row));

        row.put("NAME", p.getKey());
        row.put("VALUE", p.getValue());
      }

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);

    } else {
      // LOCAL STORAGE
      message("\nLOCAL SERVER CONFIGURATION");

      final List<RawPair<RID, Object>> resultSet = new ArrayList<>();

      for (var cfg : GlobalConfiguration.values()) {
        var row = new HashMap<>();
        resultSet.add(new RawPair<>(null, row));

        row.put("NAME", cfg.getKey());
        row.put("VALUE", cfg.getValue());
      }

      final var formatter = new TableFormatter(this);
      formatter.setMaxWidthSize(getConsoleWidth());
      formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

      formatter.writeRecords(resultSet, -1, currentDatabaseSession);
    }

    message("\n");
  }

  /**
   * Should be used only by console commands
   */
  public DatabaseSession getCurrentDatabaseSession() {
    return currentDatabaseSession;
  }

  /**
   * Pass an existent database instance to be used as current.
   */
  public ConsoleDatabaseApp setCurrentDatabaseSession(
      final DatabaseSessionInternal iCurrentDatabase) {
    currentDatabaseSession = iCurrentDatabase;
    currentDatabaseName = iCurrentDatabase.getDatabaseName();
    return this;
  }

  /**
   * Should be used only by console commands
   */
  public String getCurrentDatabaseName() {
    return currentDatabaseName;
  }

  /**
   * Should be used only by console commands
   */
  public String getCurrentDatabaseUserName() {
    return currentDatabaseUserName;
  }

  /**
   * Should be used only by console commands
   */
  public String getCurrentDatabaseUserPassword() {
    return currentDatabaseUserPassword;
  }

  /**
   * Should be used only by console commands
   */
  public List<RawPair<RID, Object>> getCurrentResultSet() {
    return currentResultSet;
  }


  /**
   * console command to open a db
   *
   * <p>usage: <code>
   * open dbName dbUser dbPwd
   * </code>
   */
  @ConsoleCommand(description = "Open a database", onlineHelp = "Console-Command-Use")
  public void open(
      @ConsoleParameter(name = "db-name", description = "The database name") final String dbName,
      @ConsoleParameter(name = "user", description = "The database user") final String user,
      @ConsoleParameter(name = "password", description = "The database password") final String password) {

    if (youTrackDB == null) {
      message("Invalid context. Please use 'connect env' first");
      return;
    }

    currentDatabaseSession = (DatabaseSessionInternal) youTrackDB.open(dbName, user, password);

    currentDatabaseName = currentDatabaseSession.getDatabaseName();
    message("OK");
  }

  @Override
  protected RESULT executeServerCommand(String iCommand) {
    if (super.executeServerCommand(iCommand) == RESULT.NOT_EXECUTED) {
      iCommand = iCommand.trim();
      if (iCommand.toLowerCase().startsWith("connect ")) {
        if (iCommand.substring("connect ".length()).trim().toLowerCase().startsWith("env ")) {
          return connectEnv(iCommand);
        }
        return RESULT.NOT_EXECUTED;
      }
      if (youTrackDB != null) {
        var displayLimit = 20;
        try {
          if (properties.get(ConsoleProperties.LIMIT) != null) {
            displayLimit = Integer.parseInt(properties.get(ConsoleProperties.LIMIT));
          }
          var rs = youTrackDB.execute(iCommand);
          var count = 0;
          List<RawPair<RID, Object>> result = new ArrayList<>();
          while (rs.hasNext() && (displayLimit < 0 || count < displayLimit)) {
            var item = rs.next();
            if (item.isBlob()) {
              result.add(new RawPair<>(item.getIdentity(), item.asBlob()));
            } else {
              result.add(new RawPair<>(item.getIdentity(), item.toMap()));
            }
          }
          currentResultSet = result;
          dumpResultSet(displayLimit);
          return RESULT.OK;
        } catch (CommandExecutionException e) {
          printError(e);
          return RESULT.ERROR;
        } catch (Exception e) {
          if (e.getCause() instanceof CommandExecutionException) {
            printError(e);
            return RESULT.ERROR;
          }
          return RESULT.NOT_EXECUTED;
        }
      }
    }
    return RESULT.NOT_EXECUTED;
  }

  /**
   * console command to open an YouTrackDB context
   *
   * <p>usage: <code>
   * connect env URL serverUser serverPwd
   * </code> eg. <code>
   * connect env remote:localhost root root
   * <p>
   * connect env embedded:. root root
   * </code>
   */
  private RESULT connectEnv(String iCommand) {
    var p = iCommand.split(" ");
    var parts = Arrays.stream(p).filter(x -> !x.isEmpty()).toList();
    if (parts.size() < 3) {
      error(String.format("\n!Invalid syntax: '%s'", iCommand));
      return RESULT.ERROR;
    }
    var url = parts.get(2);
    String user = null;
    String pw = null;

    if (parts.size() > 4) {
      user = parts.get(3);
      pw = parts.get(4);
    }

    youTrackDB = new YouTrackDBImpl(url, user, pw, YouTrackDBConfig.defaultConfig());
    return RESULT.OK;
  }

  /**
   * Should be used only by console commands
   */
  protected void checkForRemoteServer() {
    if (youTrackDB == null || YouTrackDBInternal.extract(youTrackDB).isEmbedded()) {
      throw new SystemException(
          "Remote server is not connected. Use 'connect remote:<host>[:<port>][/<database-name>]'"
              + " to connect");
    }
  }

  /**
   * Should be used only by console commands
   */
  protected void checkForDatabase() {
    if (currentDatabaseSession == null) {
      throw new SystemException(
          "Database not selected. Use 'connect <url> <user> <password>' to connect to a database.");
    }
    if (currentDatabaseSession.isClosed()) {
      throw new DatabaseException(currentDatabaseSession,
          "Database '" + currentDatabaseName + "' is closed");
    }
  }


  public String ask(final String iText) {
    out.print(iText);
    final var scanner = new Scanner(in);
    final var answer = scanner.nextLine();
    scanner.close();
    return answer;
  }

  public void onMessage(final String iText) {
    message(iText);
  }

  @Override
  public void onBegin(final Object iTask, final long iTotal, Object metadata) {
    lastPercentStep = 0;

    message("[");
    if (interactiveMode) {
      for (var i = 0; i < 10; ++i) {
        message(" ");
      }
      message("]   0%");
    }
  }

  public boolean onProgress(final Object iTask, final long iCounter, final float iPercent) {
    final var completitionBar = (int) iPercent / 10;

    if (((int) (iPercent * 10)) == lastPercentStep) {
      return true;
    }

    final var buffer = new StringBuilder(64);

    if (interactiveMode) {
      buffer.append("\r[");
      buffer.append("=".repeat(Math.max(0, completitionBar)));
      buffer.append(" ".repeat(Math.max(0, 10 - completitionBar)));
      message(String.format("] %3.1f%% ", iPercent));
    } else {
      buffer.append("=".repeat(Math.max(0, completitionBar - lastPercentStep / 100)));
    }

    message(buffer.toString());

    lastPercentStep = (int) (iPercent * 10);
    return true;
  }

  @ConsoleCommand(description = "Display the current path")
  public void pwd() {
    message("\nCurrent path: " + new File("").getAbsolutePath());
  }

  public void onCompletition(DatabaseSessionInternal session, final Object iTask,
      final boolean iSucceed) {
    if (interactiveMode) {
      if (iSucceed) {
        message("\r[==========] 100% Done.");
      } else {
        message(" Error!");
      }
    } else {
      message(iSucceed ? "] Done." : " Error!");
    }
  }

  /**
   * Closes the console freeing all the used resources.
   */
  public void close() {
    if (currentDatabaseSession != null) {
      currentDatabaseSession.activateOnCurrentThread();
      currentDatabaseSession.close();
      currentDatabaseSession = null;
    }
    if (youTrackDB != null) {
      youTrackDB.close();
    }
    currentResultSet = null;
    commandBuffer.setLength(0);
  }

  @Override
  protected boolean isCollectingCommands(final String iLine) {
    return iLine.startsWith("js") || iLine.startsWith("script");
  }

  @Override
  protected void onBefore() {
    printApplicationInfo();

    currentResultSet = new ArrayList<>();

    // DISABLE THE NETWORK AND STORAGE TIMEOUTS
    properties.put(ConsoleProperties.LIMIT, "20");
    properties.put(ConsoleProperties.DEBUG, "false");
    properties.put(ConsoleProperties.COLLECTION_MAX_ITEMS, "10");
    properties.put(ConsoleProperties.MAX_BINARY_DISPLAY, "150");
    properties.put(ConsoleProperties.VERBOSE, "2");
    properties.put(ConsoleProperties.IGNORE_ERRORS, "false");
    properties.put(ConsoleProperties.BACKUP_COMPRESSION_LEVEL, "9"); // 9 = MAX
    properties.put(ConsoleProperties.BACKUP_BUFFER_SIZE, "1048576"); // 1MB
    properties.put(
        ConsoleProperties.COMPATIBILITY_LEVEL, "" + ConsoleProperties.COMPATIBILITY_LEVEL_LATEST);
  }

  protected void printApplicationInfo() {
    message(
        "\nYouTrackDB console v." + YouTrackDBConstants.getVersion() + " "
            + YouTrackDBConstants.YOUTRACKDB_URL);
    message("\nType 'help' to display all the supported commands.");
  }

  protected void dumpResultSet(final int limit) {
    new TableFormatter(this)
        .setMaxWidthSize(getConsoleWidth())
        .setMaxMultiValueEntries(getMaxMultiValueEntries())
        .writeRecords(currentResultSet, limit, currentDatabaseSession);
  }

  protected static float getElapsedSecs(final long start) {
    return (float) (System.currentTimeMillis() - start) / 1000;
  }

  protected void printError(final Exception e) {
    if (properties.get(ConsoleProperties.DEBUG) != null
        && Boolean.parseBoolean(properties.get(ConsoleProperties.DEBUG))) {
      message("\n\n!ERROR:");
      e.printStackTrace(err);
    } else {
      // SHORT FORM
      message("\n\n!ERROR: " + e.getMessage());

      if (e.getCause() != null) {
        var t = e.getCause();
        while (t != null) {
          message("\n-> " + t.getMessage());
          t = t.getCause();
        }
      }
    }
  }

  protected void updateDatabaseInfo() {
    currentDatabaseSession.reload();
  }

  @Override
  protected String getContext() {
    final var buffer = new StringBuilder(64);

    if (currentDatabaseSession != null && currentDatabaseName != null) {
      currentDatabaseSession.activateOnCurrentThread();

      buffer.append(" {db=");
      buffer.append(currentDatabaseName);
      if (currentDatabaseSession.getTransactionInternal().isActive()) {
        buffer.append(" tx=[");
        buffer.append(currentDatabaseSession.getTransactionInternal().getEntryCount());
        buffer.append(" entries]");
      }
    } else if (urlConnection != null) {
      buffer.append(" {server=");
      buffer.append(urlConnection.getUrl());
    }

    final var promptDateFormat = properties.get(ConsoleProperties.PROMPT_DATE_FORMAT);
    if (promptDateFormat != null) {
      buffer.append(" (");
      final var df = new SimpleDateFormat(promptDateFormat);
      buffer.append(df.format(new Date()));
      buffer.append(")");
    }

    if (!buffer.isEmpty()) {
      buffer.append("}");
    }

    return buffer.toString();
  }

  @Override
  protected String getPrompt() {
    return String.format("orientdb%s> ", getContext());
  }

  protected void setResultSet(final List<RawPair<RID, Object>> iResultSet) {
    currentResultSet = iResultSet;
  }

  protected void resetResultSet() {
    currentResultSet = null;
  }

  protected void executeServerSideScript(final String iLanguage, final String script) {
    if (script == null) {
      return;
    }

    resetResultSet();
    var start = System.currentTimeMillis();
    var rs = currentDatabaseSession.runScript(iLanguage, script);
    currentResultSet = rs.stream().map(x -> new RawPair<RID, Object>(x.getIdentity(), x.toMap()))
        .toList();
    rs.close();
    var elapsedSeconds = getElapsedSecs(start);

    dumpResultSet(-1);
    message(
        String.format(
            "\nServer side script executed in %f sec(s). Returned %d records",
            elapsedSeconds, currentResultSet.size()));
  }

  protected Map<String, List<String>> parseOptions(final String iOptions) {
    final Map<String, List<String>> options = new HashMap<String, List<String>>();
    if (iOptions != null) {
      final var opts = StringSerializerHelper.smartSplit(iOptions, ' ');
      for (var o : opts) {
        final var sep = o.indexOf('=');
        if (sep == -1) {
          LogManager.instance().warn(this, "Unrecognized option %s, skipped", o);
          continue;
        }

        final var option = o.substring(0, sep);
        final var items = StringSerializerHelper.smartSplit(o.substring(sep + 1), ' ');

        options.put(option, items);
      }
    }
    return options;
  }

  public int getMaxMultiValueEntries() {
    if (properties.containsKey(ConsoleProperties.MAX_MULTI_VALUE_ENTRIES)) {
      return Integer.parseInt(properties.get(ConsoleProperties.MAX_MULTI_VALUE_ENTRIES));
    }
    return maxMultiValueEntries;
  }

  private void printSupportedSerializerFormat() {
    message("\nSupported formats are:");

    for (var s : RecordSerializerFactory.instance().getFormats()) {
      if (s instanceof RecordSerializerStringAbstract) {
        message("\n- " + s);
      }
    }
  }

  private void browseRecords(final Iterator<? extends RecordAbstract> it) {
    final var limit = Integer.parseInt(properties.get(ConsoleProperties.LIMIT));
    final var tableFormatter =
        new TableFormatter(this)
            .setMaxWidthSize(getConsoleWidth())
            .setMaxMultiValueEntries(maxMultiValueEntries);

    currentResultSet = new ArrayList<>();
    while (it.hasNext() && currentResultSet.size() <= limit) {
      var identifialble = it.next();
      var record = identifialble.getRecord(currentDatabaseSession);
      if (record instanceof Entity entity) {
        currentResultSet.add(new RawPair<>(identifialble.getIdentity(), entity.toMap()));
      } else if (record instanceof Blob blob) {
        currentResultSet.add(new RawPair<>(identifialble.getIdentity(), blob.toStream()));
      }
    }

    tableFormatter.writeRecords(currentResultSet, limit, currentDatabaseSession);
  }

  private List<Map<String, ?>> sqlCommand(
      final String iExpectedCommand,
      String iReceivedCommand,
      final String iMessageSuccess,
      final boolean iIncludeResult) {
    final var iMessageFailure = "\nCommand failed.\n";
    checkForDatabase();

    if (iReceivedCommand == null) {
      return null;
    }

    iReceivedCommand = iExpectedCommand + " " + iReceivedCommand.trim();

    resetResultSet();

    final var start = System.currentTimeMillis();

    List<Map<String, ?>> result;
    try (var rs = currentDatabaseSession.execute(iReceivedCommand)) {
      result = rs.stream().map(Result::toMap).collect(Collectors.toList());
    }
    var elapsedSeconds = getElapsedSecs(start);

    if (iIncludeResult) {
      message(String.format(iMessageSuccess, result, elapsedSeconds));
    } else {
      message(String.format(iMessageSuccess, elapsedSeconds));
    }

    return result;
  }

  @Override
  protected void onException(Throwable e) {
    var current = e;
    while (current != null) {
      err.print("\nError: " + current + "\n");
      current = current.getCause();
    }
  }

  @Override
  protected void onAfter() {
    out.println();
  }

  protected static String format(final String iValue, final int iMaxSize) {
    if (iValue == null) {
      return null;
    }

    if (iValue.length() > iMaxSize) {
      return iValue.substring(0, iMaxSize - 3) + "...";
    }
    return iValue;
  }

  public boolean historyEnabled() {
    for (var arg : args) {
      if (arg.equalsIgnoreCase(PARAM_DISABLE_HISTORY)) {
        return false;
      }
    }
    return true;
  }
}
