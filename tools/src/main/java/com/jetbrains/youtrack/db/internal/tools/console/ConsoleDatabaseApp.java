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
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.RemoteYouTrackDB;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.client.remote.DatabaseImportRemote;
import com.jetbrains.youtrack.db.internal.client.remote.YouTrackDBInternalRemote;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.common.console.ConsoleApplication;
import com.jetbrains.youtrack.db.internal.common.console.ConsoleProperties;
import com.jetbrains.youtrack.db.internal.common.console.TTYConsoleReader;
import com.jetbrains.youtrack.db.internal.common.console.annotation.ConsoleCommand;
import com.jetbrains.youtrack.db.internal.common.console.annotation.ConsoleParameter;
import com.jetbrains.youtrack.db.internal.common.exception.SystemException;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.SignalHandler;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExportException;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImportException;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.security.SecurityManager;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@SuppressWarnings("unused")
public class ConsoleDatabaseApp extends ConsoleApplication
    implements CommandOutputListener, ProgressListener, TableFormatter.OTableOutput {

  protected RemoteDatabaseSession currentDatabaseSession;
  private DatabaseSessionEmbedded currentEmbeddedDatabaseSession;

  protected String currentDatabaseName;
  protected List<RawPair<RID, Object>> currentResultSet;
  protected DatabaseURLConnection urlConnection;

  protected BasicYouTrackDB<?, ?> basicYouTrackDB;

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

    var connectionType = urlConnection.getType();
    YouTrackDB embeddedYouTrackDB = null;
    RemoteYouTrackDB remoteYouTrackDB = null;

    if (connectionType.equalsIgnoreCase("remote")) {
      remoteYouTrackDB = YourTracks.remote(urlConnection.getUrl(), iUserName, iUserPassword);
      basicYouTrackDB = remoteYouTrackDB;
    } else {
      embeddedYouTrackDB = YourTracks.embedded(urlConnection.getPath());
      basicYouTrackDB = embeddedYouTrackDB;
    }

    if (!"".equals(urlConnection.getDbName())) {
      // OPEN DB
      message("\nConnecting to database [" + iURL + "] with user '" + iUserName + "'...");
      if (embeddedYouTrackDB != null) {
        currentEmbeddedDatabaseSession = (DatabaseSessionEmbedded) embeddedYouTrackDB.open(
            urlConnection.getDbName(), iUserName,
            iUserPassword);
        currentDatabaseSession = currentEmbeddedDatabaseSession.asRemoteSession();
      } else {
        currentDatabaseSession = remoteYouTrackDB.open(urlConnection.getDbName(), iUserName,
            iUserPassword);
      }

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

      if (!currentDatabaseSession.isClosed()) {
        currentDatabaseSession.close();

        if (currentEmbeddedDatabaseSession != null) {
          currentEmbeddedDatabaseSession.close();
          currentEmbeddedDatabaseSession = null;
        }
      }

      currentDatabaseSession = null;
      currentDatabaseName = null;

      message("OK");
      out.println();
    }
    urlConnection = null;

    if (basicYouTrackDB != null) {
      basicYouTrackDB.close();
    }
  }

  @ConsoleCommand(
      description =
          "Create a new database. For encrypted database or portion of database, set the variable"
              + " 'youtrackdb.storage.encryptionKey' with the key to use",
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
    var configBuilder = YouTrackDBConfig.builder();

    DatabaseType type;
    if (storageType != null) {
      type = DatabaseType.valueOf(storageType.toUpperCase());
    } else {
      type = urlConnection.getDbType().orElse(DatabaseType.DISK);
    }

    message("\nCreating database [" + databaseURL + "] using the storage type [" + type + "]...");

    var connectionType = urlConnection.getType();
    if (connectionType.equalsIgnoreCase("remote")) {
      basicYouTrackDB = YourTracks.remote(urlConnection.getUrl(), userName, userPassword);
    } else {
      basicYouTrackDB = YourTracks.embedded(urlConnection.getPath());
    }

    final var backupPath = omap.remove("-restore");
    if (backupPath != null) {
      basicYouTrackDB.restore(
          urlConnection.getDbName(),
          currentDatabaseUserName,
          currentDatabaseUserPassword,
          backupPath,
          configBuilder.build());
    } else {
      basicYouTrackDB.createIfNotExists(
          urlConnection.getDbName(),
          type,
          currentDatabaseUserName,
          currentDatabaseUserPassword,
          "admin");
      if (basicYouTrackDB instanceof YouTrackDB youTrackDB) {
        currentEmbeddedDatabaseSession = (DatabaseSessionEmbedded) youTrackDB.open(
            urlConnection.getDbName(), userName,
            userPassword);
        currentDatabaseSession = currentEmbeddedDatabaseSession.asRemoteSession();
      } else {
        basicYouTrackDB.create(urlConnection.getDbName(), type);
        var remoteYouTrackDB = (RemoteYouTrackDB) basicYouTrackDB;
        currentDatabaseSession = remoteYouTrackDB.open(urlConnection.getDbName(), userName,
            userPassword);
      }
    }
    currentDatabaseName = currentDatabaseSession.getDatabaseName();

    message("\nDatabase created successfully.");
    message("\n\nCurrent database is: " + databaseURL);
  }

  protected static Map<String, String> parseCommandOptions(
      @ConsoleParameter(
          name = "[options]",
          optional = true,
          description = "Additional options, example: -encryption=aes -compression=nothing")
      String options) {

    final Map<String, String> omap = new HashMap<>();
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
    if (basicYouTrackDB != null) {
      final var databases = basicYouTrackDB.listDatabases();
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
    var remote = (YouTrackDBInternalRemote) YouTrackDBInternal.extract(
        (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB);
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
      @SuppressWarnings("unchecked") final var o1s = ((Map<String, String>) o1.second()).get(
          "LAST_OPERATION_ON");
      @SuppressWarnings("unchecked") final var o2s = ((Map<String, String>) o2.second()).get(
          "LAST_OPERATION_ON");
      return o2s.compareTo(o1s);
    });

    final var formatter = new TableFormatter(this);
    formatter.setMaxWidthSize(getConsoleWidth());
    formatter.setMaxMultiValueEntries(getMaxMultiValueEntries());

    formatter.writeRecords(resultSet, -1, currentDatabaseSession);

    out.println();
  }

  @ConsoleCommand(description = "Begins a transaction. All the changes will remain local")
  public void begin() throws IOException {
    checkForDatabase();

    var result = currentDatabaseSession.execute("begin").findFirst();
    message(
        "\nTransaction " + result.getLong("txId") + " is running");
  }

  @ConsoleCommand(description = "Commits transaction changes to the database")
  public void commit() throws IOException {
    checkForDatabase();

    final var begin = System.currentTimeMillis();

    var result = currentDatabaseSession.execute("commit").findFirst();
    var txId = result.getLong("txId");
    var activeTxCount = result.getInt("activeTxCount");

    if (activeTxCount == 0) {
      message(
          "\nTransaction "
              + txId
              + " has been committed in "
              + (System.currentTimeMillis() - begin)
              + "ms");
    } else {
      message(
          "\nNested transaction "
              + txId
              + " has been committed in "
              + (System.currentTimeMillis() - begin)
              + "ms. There are " + activeTxCount + " nested transactions running.");
    }
  }

  @ConsoleCommand(description = "Rolls back transaction changes to the previous state")
  public void rollback() throws IOException {
    checkForDatabase();

    final var begin = System.currentTimeMillis();

    var result = currentDatabaseSession.execute("rollback").findFirst();
    var txId = result.getLong("txId");

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
      description = "Truncate the collection content in the current database")
  public void truncateCollection(
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
  }


  @ConsoleCommand(
      splitInWords = false,
      description = "Move vertices to another position (class/collection)",
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
  }

  @ConsoleCommand(
      description = "Freeze database and flush on the disk",
      onlineHelp = "Console-Command-Freeze-Database")
  public void freezeDatabase(
      @ConsoleParameter(
          name = "storage-type",
          description = "Storage type of server database",
          optional = true)
      String storageType) {
    checkForDatabase();

    final var dbName = currentDatabaseSession.getDatabaseName();
    if (currentEmbeddedDatabaseSession != null) {
      currentDatabaseSession.freeze();
    } else {
      var youTrackDBInternalRemoteImpl = (YouTrackDBInternalRemote) YouTrackDBInternal.extract(
          (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB);
      youTrackDBInternalRemoteImpl.freezeDatabase(dbName, currentDatabaseUserName,
          currentDatabaseUserPassword);
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
      String storageType) {
    checkForDatabase();
    final var dbName = currentDatabaseSession.getDatabaseName();

    if (currentEmbeddedDatabaseSession != null) {
      // LOCAL CONNECTION
      currentDatabaseSession.release();
    } else {
      var youTrackDBInternalRemoteImpl = (YouTrackDBInternalRemote) YouTrackDBInternal.extract(
          (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB);
      youTrackDBInternalRemoteImpl.releaseDatabase(dbName, currentDatabaseUserName,
          currentDatabaseUserPassword);
    }

    message("\n\nDatabase '" + dbName + "' was released successfully");
  }

  @ConsoleCommand(description = "Flushes all database content to the disk")
  public void flushDatabase(
      @ConsoleParameter(
          name = "storage-type",
          description = "Storage type of server database",
          optional = true)
      String storageType) {
    freezeDatabase(storageType);
    releaseDatabase(storageType);
  }

  @ConsoleCommand(splitInWords = false, description = "Alter a class in the database schema")
  public void alterClass(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("alter", iCommandText, "\nClass updated successfully.\n", false);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a class",
      onlineHelp = "SQL-Create-Class")
  public void createClass(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nClass created successfully.\n", true);
  }

  @ConsoleCommand(splitInWords = false, description = "Create a sequence in the database")
  public void createSequence(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nSequence created successfully.\n", true);
  }

  @ConsoleCommand(splitInWords = false, description = "Alter an existent sequence in the database")
  public void alterSequence(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("alter", iCommandText, "\nSequence altered successfully.\n", true);
  }

  @ConsoleCommand(splitInWords = false, description = "Remove a sequence from the database")
  public void dropSequence(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("drop", iCommandText, "Sequence removed successfully.\n", false);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a user",
      onlineHelp = "SQL-Create-User")
  public void createUser(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nUser created successfully.\n", false);
  }

  @ConsoleCommand(splitInWords = false, description = "Drop a user", onlineHelp = "SQL-Drop-User")
  public void dropUser(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("drop", iCommandText, "\nUser dropped successfully.\n", false);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Alter a class property in the database schema",
      onlineHelp = "SQL-Alter-Property")
  public void alterProperty(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("alter", iCommandText, "\nProperty updated successfully.\n", false);
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Create a property",
      onlineHelp = "SQL-Create-Property")
  public void createProperty(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("create", iCommandText, "\nProperty created successfully.\n", true);
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
          result.add(new RawPair<>(item.getIdentity(), item.asBlob()));
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
    currentResultSet = currentDatabaseSession.computeScript("JavaScript", iText).stream()
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
    message("\n\nIndex created successfully");
  }

  @ConsoleCommand(
      description = "Delete the current database",
      onlineHelp = "Console-Command-Drop-Database")
  public void dropDatabase()
      throws IOException {
    checkForDatabase();

    final var dbName = currentDatabaseSession.getDatabaseName();
    currentDatabaseSession.close();
    currentDatabaseSession = null;

    if (currentEmbeddedDatabaseSession != null) {
      currentEmbeddedDatabaseSession.close();
      currentEmbeddedDatabaseSession = null;
    }

    basicYouTrackDB.drop(dbName);

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
    dropDatabase();
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Remove an index",
      onlineHelp = "SQL-Drop-Index")
  public void dropIndex(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    message("\n\nRemoving index...");

    sqlCommand("drop", iCommandText, "\nDropped index in %f sec(s).\n", false);
    message("\n\nIndex removed successfully");
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Rebuild an index if it is automatic",
      onlineHelp = "SQL-Rebuild-Index")
  public void rebuildIndex(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    message("\n\nRebuilding index(es)...");

    sqlCommand(
        "rebuild", iCommandText, "\nRebuilt index(es). Found %d link(s) in %f sec(s).\n", true);
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
  }

  @ConsoleCommand(
      splitInWords = false,
      description = "Remove a property from a class",
      onlineHelp = "SQL-Drop-Property")
  public void dropProperty(
      @ConsoleParameter(name = "command-text", description = "The command text to execute")
      String iCommandText) {
    sqlCommand("drop", iCommandText, "\nRemoved class property in %f sec(s).\n", false);
  }

  @ConsoleCommand(
      description = "Load a sql script into the current database",
      onlineHelp = "Console-Command-Load-Script")
  public void loadScript(
      @ConsoleParameter(name = "scripPath", description = "load script scriptPath") final String scriptPath) {

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
      if (currentEmbeddedDatabaseSession == null) {
        var databaseImport =
            new DatabaseImportRemote((DatabaseSessionRemote) currentDatabaseSession, fileName,
                this);

        databaseImport.setOptions(options);
        databaseImport.importDatabase();
        databaseImport.close();

      } else {
        var databaseImport = new DatabaseImport(currentEmbeddedDatabaseSession, fileName, this);

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
      @ConsoleParameter(name = "options", description = "Backup options") final String iText) {
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

    final var startTime = System.currentTimeMillis();
    String fName;
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

    if (currentEmbeddedDatabaseSession == null) {
      error("\nExporting of database is supported only for embedded databases");
      return;
    }

    out.println("Exporting current database to: " + iText + " in GZipped JSON format ...");
    final var items = StringSerializerHelper.smartSplit(iText, ' ');
    final var fileName =
        items.size() <= 1 || items.get(1).charAt(0) == '-' ? null : items.get(1);
    final var options =
        fileName != null
            ? iText.substring(items.get(0).length() + items.get(1).length() + 1).trim()
            : iText;

    try {
      new DatabaseExport(currentEmbeddedDatabaseSession, fileName, this)
          .setOptions(options)
          .exportDatabase()
          .close();
    } catch (DatabaseExportException e) {
      printError(e);
    }
  }


  @ConsoleCommand(description = "Return the value of a configuration value")
  public void configGet(
      @ConsoleParameter(name = "config-name", description = "Name of the configuration") final String iConfigName) {
    final var config = GlobalConfiguration.findByKey(iConfigName);
    if (config == null) {
      throw new IllegalArgumentException(
          "Configuration variable '" + iConfigName + "' wasn't found");
    }

    final String value;
    if (!YouTrackDBInternal.extract(
            (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB)
        .isEmbedded()) {
      value =
          ((YouTrackDBInternalRemote) YouTrackDBInternal.extract(
              (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB))
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
      @ConsoleParameter(name = "config-value", description = "Value to set") final String iConfigValue) {
    final var config = GlobalConfiguration.findByKey(iConfigName);
    if (config == null) {
      throw new IllegalArgumentException("Configuration variable '" + iConfigName + "' not found");
    }

    if (basicYouTrackDB != null && !YouTrackDBInternal.extract(
            (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB)
        .isEmbedded()) {
      ((YouTrackDBInternalRemote) YouTrackDBInternal.extract(
          (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB))
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
    if (!YouTrackDBInternal.extract(
            (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB)
        .isEmbedded()) {
      final var values =
          ((YouTrackDBInternalRemote) YouTrackDBInternal.extract(
              (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) basicYouTrackDB))
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

  /**
   * Should be used only by console commands
   */
  public RemoteDatabaseSession getCurrentDatabaseSession() {
    return currentDatabaseSession;
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

    if (basicYouTrackDB == null) {
      message("Invalid context. Please use 'connect env' first");
      return;
    }

    if (basicYouTrackDB instanceof YouTrackDB embeddedYouTrackDB) {
      if (currentDatabaseSession != null) {
        currentDatabaseSession.close();
      }
      if (currentEmbeddedDatabaseSession != null) {
        currentEmbeddedDatabaseSession.close();
      }

      currentEmbeddedDatabaseSession = (DatabaseSessionEmbedded) embeddedYouTrackDB.open(dbName,
          user,
          password);
      currentDatabaseSession = currentEmbeddedDatabaseSession.asRemoteSession();
    } else {
      var remoteYouTrackDB = (RemoteYouTrackDB) basicYouTrackDB;
      currentDatabaseSession = remoteYouTrackDB.open(dbName, user, password);
    }

    currentDatabaseName = currentDatabaseSession.getDatabaseName();
    message("OK");
  }

  /**
   * Should be used only by console commands
   */
  protected void checkForRemoteServer() {
    if (basicYouTrackDB == null || basicYouTrackDB instanceof YouTrackDB) {
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

  @Override
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

  @Override
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

  @Override
  public void onCompletition(DatabaseSessionEmbedded session, final Object iTask,
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
      currentDatabaseSession.close();
      currentDatabaseSession = null;
    }
    if (basicYouTrackDB != null) {
      basicYouTrackDB.close();
    }
    currentResultSet = null;
    commandBuffer.setLength(0);
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
      if (basicYouTrackDB != null) {
        var displayLimit = 20;
        try {
          if (properties.get(ConsoleProperties.LIMIT) != null) {
            displayLimit = Integer.parseInt(properties.get(ConsoleProperties.LIMIT));
          }
          var rs = basicYouTrackDB.execute(iCommand);
          var count = 0;
          List<RawPair<RID, Object>> result = new ArrayList<>();
          while (rs.hasNext() && (displayLimit < 0 || count < displayLimit)) {
            var item = rs.next();
            result.add(new RawPair<>(item.getIdentity(), item.toMap()));
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

    var urlConnection = URLHelper.parseNew(url);
    if (urlConnection.getType().equalsIgnoreCase("remote")) {
      basicYouTrackDB = YourTracks.remote(urlConnection.getUrl(), user, pw);
    } else {
      basicYouTrackDB = YourTracks.embedded(urlConnection.getPath());
    }

    return RESULT.OK;
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

  @Override
  protected String getContext() {
    final var buffer = new StringBuilder(64);

    if (currentDatabaseSession != null && currentDatabaseName != null) {
      buffer.append(" {db=");
      buffer.append(currentDatabaseName);
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
    var rs = currentDatabaseSession.computeScript(iLanguage, script);
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
    final Map<String, List<String>> options = new HashMap<>();
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

  @Nullable
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
      result = rs.stream().map(RemoteResult::toMap).collect(Collectors.toList());
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

  @Nullable
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
