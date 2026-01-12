package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.core.db.SystemDatabase;
import com.jetbrains.youtrackdb.internal.remote.RemoteProtocolConstants;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.MapConfiguration;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.OpProcessor;
import org.apache.tinkerpop.gremlin.server.handler.StateKey;
import org.apache.tinkerpop.gremlin.server.op.OpProcessorException;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class YTDBServerCommandsOpProcessor implements OpProcessor {

  private static final Logger logger = LoggerFactory.getLogger(YTDBServerCommandsOpProcessor.class);

  public static final String CREATE_DATABASE_ROLE = "database.create";
  public static final String DROP_DATABASE_ROLE = "database.drop";

  public static final String LIST_DATABASES_ROLE = "server.listDatabases";
  public static final String MANAGE_SYSTEM_USERS = "server.manageSystemUsers";

  @Override
  public String getName() {
    return RemoteProtocolConstants.PROCESSOR_NAME;
  }

  @Override
  public ThrowingConsumer<Context> select(Context ctx) throws OpProcessorException {
    var msg = ctx.getRequestMessage();
    var op = msg.getOp();

    return switch (op) {
      case RemoteProtocolConstants.SERVER_COMMAND_CREATE_DATABASE ->
          context -> doCreateDatabase(context, false);
      case RemoteProtocolConstants.SERVER_COMMAND_CREATE_DATABASE_IF_NOT_EXIST ->
          context -> doCreateDatabase(context, true);
      case RemoteProtocolConstants.SERVER_COMMAND_DROP_DATABASE ->
          YTDBServerCommandsOpProcessor::dropDatabase;
      case RemoteProtocolConstants.SERVER_COMMAND_LIST_DATABASES ->
          YTDBServerCommandsOpProcessor::listDatabases;
      case RemoteProtocolConstants.SERVER_COMMAND_EXISTS ->
          YTDBServerCommandsOpProcessor::isDatabaseExists;
      case RemoteProtocolConstants.SERVER_COMMAND_RESTORE ->
          YTDBServerCommandsOpProcessor::restoreDatabase;
      case RemoteProtocolConstants.SERVER_COMMAND_CREATE_SYSTEM_USER ->
          YTDBServerCommandsOpProcessor::createSystemUser;
      case RemoteProtocolConstants.SERVER_COMMAND_DROP_SYSTEM_USER ->
          YTDBServerCommandsOpProcessor::dropSystemUser;
      case RemoteProtocolConstants.SERVER_COMMAND_LIST_SYSTEM_USERS ->
          YTDBServerCommandsOpProcessor::listSystemUsers;
      case null, default -> {
        var errorMessage = "Unknown server command: " + op;
        logger.error(errorMessage);

        throw new OpProcessorException(errorMessage,
            ResponseMessage.build(msg).code(
                    ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST).statusMessage(errorMessage)
                .create());
      }
    };
  }

  @Override
  public void close() throws Exception {
  }

  private static void createSystemUser(Context ctx) throws OpProcessorException {
    checkServerRole(ctx, MANAGE_SYSTEM_USERS);

    var msg = ctx.getRequestMessage();

    var userName = geUserName(msg);
    var userPassword = geUserPassword(msg);
    var userRoles = geUserRoles(msg);

    if (userRoles.isEmpty()) {
      throw new OpProcessorException("User roles are not specified",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("User roles are not specified").create());
    }

    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    var youTrackDB = server.getYouTrackDB();
    youTrackDB.createSystemUser(userName, userPassword, userRoles.toArray(String[]::new));

    ctx.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.SUCCESS).create());
  }

  private static void dropSystemUser(Context ctx) throws OpProcessorException {
    checkServerRole(ctx, MANAGE_SYSTEM_USERS);

    var msg = ctx.getRequestMessage();
    var userName = geUserName(msg);

    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    var youTrackDB = server.getYouTrackDB();
    youTrackDB.dropSystemUser(userName);

    ctx.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.SUCCESS).create());
  }

  private static void listSystemUsers(Context ctx) throws OpProcessorException {
    checkServerRole(ctx, MANAGE_SYSTEM_USERS);

    var msg = ctx.getRequestMessage();
    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    var youTrackDB = server.getYouTrackDB();
    var users = youTrackDB.listSystemUsers();

    ctx.writeAndFlush(
        ResponseMessage.build(msg).code(ResponseStatusCode.SUCCESS).result(users).create());
  }

  private static void restoreDatabase(Context ctx) throws OpProcessorException {
    checkServerRole(ctx, CREATE_DATABASE_ROLE);

    var msg = ctx.getRequestMessage();
    var args = msg.getArgs();

    var databaseName = getDatabaseName(msg);
    var backupPath = getBackupPath(msg);
    var configuration = getConfiguration(args);

    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    var youTrackDB = server.getYouTrackDB();
    youTrackDB.restore(databaseName, backupPath, configuration);

    ctx.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.SUCCESS).create());
  }

  private static void dropDatabase(Context ctx) throws OpProcessorException {
    checkServerRole(ctx, DROP_DATABASE_ROLE);

    var msg = ctx.getRequestMessage();
    var databaseName = getDatabaseName(msg);

    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    var youTrackDB = server.getYouTrackDB();
    youTrackDB.drop(databaseName);

    ctx.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.SUCCESS).create());
  }


  private static void listDatabases(Context ctx) throws OpProcessorException {
    checkServerRole(ctx, LIST_DATABASES_ROLE);

    var msg = ctx.getRequestMessage();

    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    var youTrackDB = server.getYouTrackDB();

    var databases = youTrackDB.listDatabases();
    databases.remove(SystemDatabase.SYSTEM_DB_NAME);

    var response = ResponseMessage.build(msg).code(ResponseStatusCode.SUCCESS).result(databases)
        .statusMessage("Databases listed successfully").create();

    ctx.writeAndFlush(response);
  }

  private static void isDatabaseExists(final Context ctx)
      throws OpProcessorException {
    checkServerRole(ctx, LIST_DATABASES_ROLE);

    var msg = ctx.getRequestMessage();
    var databaseName = getDatabaseName(msg);

    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    var exists = server.getYouTrackDB().exists(databaseName);
    ctx.writeAndFlush(
        ResponseMessage.build(msg).code(ResponseStatusCode.SUCCESS).result(exists).create());
  }

  private static void doCreateDatabase(final Context ctx, boolean ifNotExist)
      throws OpProcessorException {
    checkServerRole(ctx, CREATE_DATABASE_ROLE);

    var msg = ctx.getRequestMessage();
    var args = msg.getArgs();

    var databaseName = getDatabaseName(msg);
    var databaseType = getDatabaseType(msg);
    var userCredentials = getUserCredentials(msg);
    var configuration = getConfiguration(args);

    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    if (!ifNotExist) {
      server.getYouTrackDB().create(databaseName, databaseType, configuration,
          userCredentials.toArray(String[]::new));
    } else {
      server.getYouTrackDB()
          .createIfNotExists(databaseName, databaseType, configuration,
              userCredentials.toArray(String[]::new));
    }

    ctx.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.SUCCESS).create());
  }

  private static @Nonnull String getDatabaseName(RequestMessage msg)
      throws OpProcessorException {
    var args = msg.getArgs();
    var databaseName = (String) args.get(RemoteProtocolConstants.DATABASE_NAME_PARAMETER);

    if (databaseName == null) {
      throw new OpProcessorException("Database name is not specified",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("Database name is not specified").create());
    }
    return databaseName;
  }

  private static @Nonnull String geUserName(RequestMessage msg)
      throws OpProcessorException {
    var args = msg.getArgs();
    var userName = (String) args.get(RemoteProtocolConstants.USER_NAME_PARAMETER);

    if (userName == null) {
      throw new OpProcessorException("User name is not specified",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("User name is not specified").create());
    }

    return userName;
  }

  private static @Nonnull String geUserPassword(RequestMessage msg)
      throws OpProcessorException {
    var args = msg.getArgs();
    var userPassword = (String) args.get(RemoteProtocolConstants.USER_PASSWORD_PARAMETER);

    if (userPassword == null) {
      throw new OpProcessorException("User password is not specified",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("User password is not specified").create());
    }
    return userPassword;
  }

  private static @Nonnull List<String> geUserRoles(RequestMessage msg)
      throws OpProcessorException {
    var args = msg.getArgs();
    @SuppressWarnings("unchecked") var userRoles = (List<String>) args.get(
        RemoteProtocolConstants.USER_ROLES_PARAMETER);

    if (userRoles == null) {
      throw new OpProcessorException("User roles are not specified",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("User roles are not specified").create());
    }

    return userRoles;
  }

  private static @Nonnull String getBackupPath(RequestMessage msg)
      throws OpProcessorException {
    var args = msg.getArgs();
    var backupPath = (String) args.get(RemoteProtocolConstants.BACKUP_PATH_PARAMETER);

    if (backupPath == null) {
      throw new OpProcessorException("Path to the backup directory is not specified",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("Path to the backup directory is not specified").create());
    }
    return backupPath;
  }

  private static @Nonnull Configuration getConfiguration(Map<String, Object> args) {
    @SuppressWarnings("unchecked")
    var mapConfig = (Map<String, ?>) args.get(RemoteProtocolConstants.CONFIGURATION_PARAMETER);

    Configuration configuration;
    if (mapConfig == null) {
      configuration = new BaseConfiguration();
    } else {
      configuration = new MapConfiguration(mapConfig);
    }
    return configuration;
  }

  private static @Nonnull List<String> getUserCredentials(RequestMessage msg)
      throws OpProcessorException {
    var args = msg.getArgs();
    @SuppressWarnings("unchecked") var userCredentials = (List<String>)
        args.get(RemoteProtocolConstants.USER_CREDENTIALS_PARAMETER);
    if (userCredentials == null || userCredentials.isEmpty()) {
      throw new OpProcessorException("User credentials are not specified",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("User credentials are not specified").create());
    }
    if (userCredentials.size() % 3 != 0) {
      throw new OpProcessorException("Invalid user credentials format",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("Invalid user credentials format").create());
    }
    return userCredentials;
  }

  private static @Nonnull DatabaseType getDatabaseType(RequestMessage msg)
      throws OpProcessorException {
    var args = msg.getArgs();
    var databaseTypeStr = (String) args.get(RemoteProtocolConstants.DATABASE_TYPE_PARAMETER);
    if (databaseTypeStr == null) {
      throw new OpProcessorException("Database type is not specified",
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("Database type is not specified").create());
    }

    DatabaseType databaseType;
    try {
      databaseType = DatabaseType.valueOf(databaseTypeStr);
    } catch (IllegalArgumentException e) {
      throw new OpProcessorException("Invalid database type: " + databaseTypeStr,
          ResponseMessage.build(msg).code(
                  ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST)
              .statusMessage("Invalid database type: " + databaseTypeStr).create());
    }
    return databaseType;
  }


  private static void checkServerRole(final Context ctx, final String role)
      throws OpProcessorException {
    var user = ctx.getChannelHandlerContext().channel().attr(StateKey.AUTHENTICATED_USER).get();
    if (user == null) {
      throw new OpProcessorException("User is not authenticated",
          ResponseMessage.build(ctx.getRequestMessage()).code(
              ResponseStatusCode.UNAUTHORIZED).statusMessage("User is not authenticated").create());
    }

    var settings = (YTDBSettings) ctx.getSettings();
    var server = settings.server;

    var authorized = server.getDatabases().getSystemDatabase().executeWithDB(
        session -> server.getSecurity().isAuthorized(session, user.getName(), role)
    );

    if (!authorized) {
      throw new OpProcessorException("User is not authorized",
          ResponseMessage.build(ctx.getRequestMessage()).code(
              ResponseStatusCode.UNAUTHORIZED).statusMessage("User is not authorized").create());
    }
  }
}
